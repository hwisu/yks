package dev.yks

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BlockSetTest {
    @Test
    fun blockSetToIdSetIgnoresSkipsAndMergesContiguousBlocks() {
        val blocks = BlockSet(
            linkedMapOf(
                1L to BlockRange(
                    mutableListOf(
                        item(1, 0, "ab"),
                        Skip(Id(1, 2), 1),
                        GC(Id(1, 3), 2),
                        item(1, 5, "f"),
                    ),
                ),
            ),
        )

        val idSet = blocks.toIdSet()

        assertEquals(listOf(1L to IdRange(0, 2), 1L to IdRange(3, 3)), idSet.ranges())
    }

    @Test
    fun blockSetExcludeSplitsCoveredBlocksAndConvertsExcludedRangeToSkip() {
        val blocks = BlockSet(
            linkedMapOf(
                1L to BlockRange(
                    mutableListOf(
                        item(1, 0, "abcd"),
                        GC(Id(1, 4), 2),
                    ),
                ),
            ),
        )
        val exclude = createIdSet().also { it.add(1, 1, 4) }

        blocks.exclude(exclude)

        val refs = blocks.clients.getValue(1).refs
        assertEquals(listOf(Id(1, 0), Id(1, 1), Id(1, 5)), refs.map { it.id })
        assertEquals(listOf(1L, 4L, 1L), refs.map { it.length })
        assertTrue(refs[1] is Skip)
        assertEquals("a", (refs[0] as ItemStruct).content.getContent().joinToString(separator = ""))
        assertEquals(listOf(1L to IdRange(0, 1), 1L to IdRange(5, 1)), blocks.toIdSet().ranges())
    }

    @Test
    fun blockSetInsertIntoFillsDisjointGapsAndClearsInsertedSet() {
        val base = BlockSet(linkedMapOf(1L to BlockRange(mutableListOf(item(1, 0, "ab")))))
        val inserts = BlockSet(linkedMapOf(1L to BlockRange(mutableListOf(item(1, 4, "e")))))

        base.insertInto(inserts)

        val refs = base.clients.getValue(1).refs
        assertEquals(listOf(Id(1, 0), Id(1, 2), Id(1, 4)), refs.map { it.id })
        assertEquals(listOf(2L, 2L, 1L), refs.map { it.length })
        assertTrue(refs[1] is Skip)
        assertTrue(inserts.clients.isEmpty())
        assertEquals(listOf(1L to IdRange(0, 2), 1L to IdRange(4, 1)), base.toIdSet().ranges())
    }

    @Test
    fun blockSetInsertIntoSlicesOverlappingInsertedBlocks() {
        val base = BlockSet(linkedMapOf(1L to BlockRange(mutableListOf(item(1, 0, "abc")))))
        val inserts = BlockSet(linkedMapOf(1L to BlockRange(mutableListOf(item(1, 2, "cde")))))

        base.insertInto(inserts)

        val refs = base.clients.getValue(1).refs
        assertEquals(listOf(Id(1, 0), Id(1, 3)), refs.map { it.id })
        assertEquals(listOf(3L, 2L), refs.map { it.length })
        assertEquals("abc", (refs[0] as ItemStruct).content.getContent().joinToString(separator = ""))
        assertEquals("de", (refs[1] as ItemStruct).content.getContent().joinToString(separator = ""))
        assertEquals(listOf(1L to IdRange(0, 5)), base.toIdSet().ranges())
    }

    @Test
    fun sliceStructReturnsRightPartWithoutMutatingOriginalStructView() {
        val original = item(1, 0, "hello")
        val sliced = sliceStruct(original, 2) as ItemStruct

        assertEquals(Id(1, 2), sliced.id)
        assertEquals(3, sliced.length)
        assertEquals("llo", sliced.content.getContent().joinToString(separator = ""))
        assertEquals(5, original.length)
        assertEquals("hello", original.content.getContent().joinToString(separator = ""))
    }

    @Test
    fun blockSetReadWriteRoundTripsThroughUpdateEncoders() {
        val blocks = BlockSet(
            linkedMapOf(
                1L to BlockRange(
                    mutableListOf(
                        item(1, 0, "ab"),
                        Skip(Id(1, 2), 2),
                        GC(Id(1, 4), 1),
                    ),
                ),
                2L to BlockRange(
                    mutableListOf(
                        ItemStruct(
                            id = Id(2, 3),
                            length = 2,
                            deleted = true,
                            origin = Id(2, 1),
                            rightOrigin = Id(2, 5),
                            parent = "body",
                            parentSub = null,
                            kind = RootKind.Text,
                            content = ContentString("xy"),
                        ),
                    ),
                ),
            ),
        )

        val encoderV1 = UpdateEncoderV1()
        writeBlockSet(encoderV1, blocks)
        val decoderV1 = UpdateDecoderV1(encoderV1.toByteArray())
        val decodedV1 = readBlockSet(decoderV1)

        assertBlockSetStructurallyEquals(blocks, decodedV1)
        assertFalse(decoderV1.hasRemaining())

        val encoderV2 = UpdateEncoderV2()
        writeBlockSet(encoderV2, blocks)
        val decoderV2 = UpdateDecoderV2(encoderV2.toUint8Array())
        val decodedV2 = readBlockSet(decoderV2)

        assertBlockSetStructurallyEquals(blocks, decodedV2)
        assertFalse(decoderV2.hasRemaining())
    }

    private fun item(client: Long, clock: Long, text: String): ItemStruct =
        ItemStruct(
            id = Id(client, clock),
            length = text.length.toLong(),
            deleted = false,
            origin = null,
            rightOrigin = null,
            parent = "text",
            parentSub = null,
            kind = RootKind.Text,
            content = ContentString(text),
        )

    private fun assertBlockSetStructurallyEquals(expected: BlockSet, actual: BlockSet) {
        assertEquals(expected.clients.keys.toSet(), actual.clients.keys.toSet())
        expected.clients.keys.forEach { client ->
            val expectedRefs = expected.clients.getValue(client).refs
            val actualRefs = actual.clients.getValue(client).refs
            assertEquals(expectedRefs.size, actualRefs.size)
            expectedRefs.zip(actualRefs).forEach { (expectedRef, actualRef) ->
                assertEquals(expectedRef.structuralFields(), actualRef.structuralFields())
            }
        }
        assertTrue(equalIdSets(expected.toIdSet(), actual.toIdSet()))
    }

    private fun AbstractStruct.structuralFields(): List<Any?> = when (this) {
        is ItemStruct -> listOf(
            "item",
            id,
            length,
            deleted,
            origin,
            rightOrigin,
            parent,
            parentSub,
            kind,
            content,
        )
        is GC -> listOf("gc", id, length, deleted)
        is Skip -> listOf("skip", id, length, deleted)
        else -> listOf(this::class.qualifiedName, id, length, deleted)
    }
}
