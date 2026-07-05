package dev.yks

import kotlin.test.Test
import kotlin.test.assertEquals

class YArrayDeltaTest {
    @Test
    fun sliceSupportsYjsStyleNegativeEndAndAliases() {
        val doc = YDoc(clientId = 1)
        val array = doc.getArray("array")
        array.insert(0, listOf(1, 2, 3))

        assertEquals(listOf(1L, 2L, 3L), array.slice(0))
        assertEquals(listOf(2L, 3L), array.slice(1))
        assertEquals(listOf(1L, 2L), array.slice(0, -1))

        array.unshift(listOf(0))

        assertEquals(listOf(0L, 1L, 2L, 3L), array.toArray())
        assertEquals(listOf(0L, 1L), array.slice(0, 2))
    }

    @Test
    fun toDeltaRendersArrayContentAsSingleInsert() {
        val doc = YDoc(clientId = 1)
        val array = doc.getArray("array")
        array.push(listOf(1, true, null, "x"))

        assertEquals(
            listOf(YArrayDeltaOp(insert = listOf(1L, true, null, "x"))),
            array.toDelta(),
        )
    }

    @Test
    fun applyDeltaSupportsRetainDeleteAndInsert() {
        val doc = YDoc(clientId = 1)
        val array = doc.getArray("array")
        array.push(listOf("a", "b", "d"))

        array.applyDelta(
            listOf(
                YArrayDeltaOp(retain = 1),
                YArrayDeltaOp(delete = 1),
                YArrayDeltaOp(insert = listOf("B", "c")),
            ),
        )

        assertEquals(listOf("a", "B", "c", "d"), array.toArray())
    }

    @Test
    fun applyDeltaUsesActiveRendererForArrayIndexes() {
        val doc = YDoc(clientId = 1)
        val array = doc.getArray("array")
        val renderer = object : BaseRenderer() {
            override val attributed: IdSet = createIdSet().also { it.add(1, 0, 1) }

            override fun contentLength(item: ItemStruct): Long =
                if (item.id == Id(1, 0)) 0 else super.contentLength(item)
        }
        array.push(listOf("a", "b", "c"))
        array.useRenderer(renderer)

        array.applyDelta(listOf(
            YArrayDeltaOp(retain = 1),
            YArrayDeltaOp(insert = listOf("!")),
            YArrayDeltaOp(delete = 1),
        ))

        assertEquals(listOf("a", "b", "!"), array.toArray())
    }

    @Test
    fun applyDeltaRendererArgumentOverridesArrayActiveRenderer() {
        val doc = YDoc(clientId = 1)
        val array = doc.getArray("array")
        val renderer = object : BaseRenderer() {
            override val attributed: IdSet = createIdSet().also { it.add(1, 0, 1) }

            override fun contentLength(item: ItemStruct): Long =
                if (item.id == Id(1, 0)) 0 else super.contentLength(item)
        }
        array.push(listOf("a", "b"))
        array.useRenderer(renderer)

        array.applyDelta(listOf(YArrayDeltaOp(retain = 1), YArrayDeltaOp(insert = listOf("!"))), renderer = baseRenderer)

        assertEquals(listOf("a", "!", "b"), array.toArray())
    }

    @Test
    fun arrayMapAndForEachUseVisibleContent() {
        val doc = YDoc(clientId = 1)
        val array = doc.getArray("array")
        array.push(listOf(1, 2, 3))
        array.delete(1)

        assertEquals(listOf(2L, 6L), array.map { (it as Long) * 2 })

        val seen = mutableListOf<Any?>()
        array.forEach { seen.add(it) }
        assertEquals(listOf<Any?>(1L, 3L), seen)
    }

    @Test
    fun arrayDeltaConvergesThroughUpdatesAndUndo() {
        val left = YDoc(clientId = 1)
        val right = YDoc(clientId = 2)
        val array = left.getArray("array")
        val undoManager = UndoManager(array, UndoManagerOptions(captureTimeoutMillis = 0))

        array.applyDelta(listOf(YArrayDeltaOp(insert = listOf("a", "b", "c"))))
        array.applyDelta(listOf(YArrayDeltaOp(retain = 1), YArrayDeltaOp(delete = 1), YArrayDeltaOp(insert = listOf("B"))))

        right.applyUpdate(left.encodeStateAsUpdate())

        assertEquals(listOf("a", "B", "c"), right.getArray("array").toArray())

        undoManager.undo()
        assertEquals(listOf("a", "b", "c"), array.toArray())

        undoManager.redo()
        assertEquals(listOf("a", "B", "c"), array.toArray())
    }
}
