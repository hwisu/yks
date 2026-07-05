package dev.yks

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IdMapTest {
    private val a1 = createContentAttribute("user", "a")
    private val a2 = createContentAttribute("user", "b")
    private val a3 = createContentAttribute("time", 1)

    @Test
    fun idMapCanBeConstructedAndIteratedLikeUpstreamExport() {
        val map = IdMap()
        val seen = mutableListOf<Pair<Long, AttrRange>>()

        map.add(2, 10, 1, listOf(a3))
        map.add(1, 0, 2, listOf(a1))
        map.add(1, 1, 1, listOf(a2))
        map.forEach { range, client -> seen.add(client to range) }

        assertEquals(
            listOf(
                1L to AttrRange(0, 1, listOf(a1)),
                1L to AttrRange(1, 1, listOf(a1, a2)),
                2L to AttrRange(10, 1, listOf(a3)),
            ),
            seen,
        )
        assertEquals(listOf(AttrRange(10, 1, listOf(a3))), map.clients.getValue(2L).toList())
        assertFalse(map.isEmpty())
    }

    @Test
    fun idMapNormalizesOverlapsAndCombinesAttributes() {
        val map = createIdMap()
        map.add(0, 1, 2, listOf(a1))
        map.add(0, 0, 2, listOf(a2))

        assertEquals(
            listOf(
                0L to AttrRange(0, 1, listOf(a2)),
                0L to AttrRange(1, 1, listOf(a2, a1)),
                0L to AttrRange(2, 1, listOf(a1)),
            ),
            map.ranges(),
        )
    }

    @Test
    fun idMapMergeDiffIntersectAndFilter() {
        val left = createIdMap()
        left.add(0, 0, 3, listOf(a1))
        val right = createIdMap()
        right.add(0, 2, 3, listOf(a2))
        right.add(1, 0, 1, listOf(a3))

        val merged = mergeIdMaps(listOf(left, right))
        assertTrue(merged.hasId(Id(0, 4)))
        assertTrue(merged.has(1, 0))

        val diffed = diffIdMap(merged, idSet(0, 1, 3))
        assertFalse(diffed.has(0, 1))
        assertFalse(diffed.has(0, 2))
        assertFalse(diffed.has(0, 3))
        assertTrue(diffed.has(0, 0))
        assertTrue(diffed.has(0, 4))

        val intersected = intersectMaps(left, right)
        assertEquals(listOf(0L to AttrRange(2, 1, listOf(a1, a2))), intersected.ranges())

        val filtered = filterIdMap(merged) { attrs -> attrs.any { it.name == "time" } }
        assertEquals(listOf(1L to AttrRange(0, 1, listOf(a3))), filtered.ranges())
    }

    @Test
    fun idMapHelpersAcceptIdSetSourcesAndIntersectionsLikeUpstream() {
        val idMap = createIdMap()
        val idSet = createIdSet()
        idMap.add(0, 1, 1, listOf(a1))
        idSet.add(0, 0, 3)
        idSet.add(1, 0, 1)

        insertIntoIdMap(idMap, idSet)

        assertEquals(
            listOf(
                0L to AttrRange(0, 1, emptyList()),
                0L to AttrRange(1, 1, listOf(a1)),
                0L to AttrRange(2, 1, emptyList()),
                1L to AttrRange(0, 1, emptyList()),
            ),
            idMap.ranges(),
        )
        assertEquals(
            listOf(
                0L to AttrRange(1, 1, listOf(a1)),
                0L to AttrRange(2, 1, emptyList()),
            ),
            intersectMaps(idMap, createIdSet().also { it.add(0, 1, 2) }).ranges(),
        )
        assertTrue(
            equalIdMaps(
                intersectMaps(idMap, createIdSet().also { it.add(0, 1, 2) }),
                _intersectSets(idMap, createIdSet().also { it.add(0, 1, 2) }),
            ),
        )

        val deleted = createIdMap()
        deleted.add(0, 0, 3, listOf(a1))
        _deleteRangeFromIdSet(deleted, 0, 1, 1)
        assertEquals(listOf(0L to AttrRange(0, 1, listOf(a1)), 0L to AttrRange(2, 1, listOf(a1))), deleted.ranges())
        assertTrue(equalIdMaps(diffIdMap(idMap, createIdSet().also { it.add(0, 1, 1) }), _diffSet(idMap, createIdSet().also { it.add(0, 1, 1) })))
    }

    @Test
    fun idMapConvertsToAndFromIdSetAndEncodes() {
        val idSet = idSet(0, 1, 2, 2, 0, 1)
        val idMap = createIdMapFromIdSet(idSet, listOf(a1, a3))
        val stripped = createIdSetFromIdMap(idMap)

        assertTrue(equalIdSets(idSet, stripped))
        assertIdMapEquals(idMap, decodeIdMap(encodeIdMap(idMap)))
        val encoder = BinaryEncoder()
        writeIdMap(encoder, idMap)
        val decoder = BinaryDecoder(encoder.toByteArray())
        assertIdMapEquals(idMap, readIdMap(decoder))
        assertFalse(decoder.hasRemaining())
        assertEquals(listOf(MaybeAttrRange(1, 2, listOf(a1, a3))), idMap.slice(0, 1, 2))
        assertEquals(listOf(MaybeAttrRange(3, 1, null)), idMap.slice(0, 3, 1))
        assertEquals(listOf(MaybeAttrRange(1, 0, null)), idMap.slice(0, 1, 0))
    }

    @Test
    fun encodeIdMapUsesUpstreamV2DeltasAndAttributeReferences() {
        val idMap = createIdMap()
        idMap.add(1, 0, 1, listOf(a1))
        idMap.add(1, 2, 1, listOf(a1))
        idMap.add(3, 0, 1, listOf(a2))

        val encoded = encodeIdMap(idMap)

        assertContentEquals(
            byteArrayOf(
                2,
                1, 2,
                0, 0, 1, 0, 0, 4, 117, 115, 101, 114, 5, 1, 97,
                1, 0, 1, 0,
                2, 1,
                0, 0, 1, 1, 0, 5, 1, 98,
            ),
            encoded,
        )
        assertIdMapEquals(idMap, decodeIdMap(encoded))
    }

    @Test
    fun idMapAttributeHelpersMirrorUpstreamFactoryAndEquality() {
        val binaryLeft = createContentAttribute("bytes", byteArrayOf(1, 2))
        val binaryRight = createContentAttribute("bytes", byteArrayOf(1, 2))
        val missing = createContentAttribute("user", "missing")
        val map = IdMap()

        map.add(0, 0, 1, listOf(a1, binaryLeft, binaryRight))

        assertEquals(AttrRange(4, 1, listOf(a1)), AttrRange(0, 2, listOf(a1)).copyWith(4, 1))
        assertEquals(createMaybeAttrRange(4, 2, listOf(a1)), MaybeAttrRange(4, 2, listOf(a1)))
        assertTrue(idmapAttrsEqual(listOf(a1, a3, binaryLeft), listOf(binaryRight, a3, a1)))
        assertFalse(idmapAttrsEqual(listOf(a1, a3), listOf(a1, missing)))
        assertFalse(idmapAttrsEqual(listOf(a1), listOf(a1, a3)))
        assertEquals(binaryLeft.hash(), binaryRight.hash())
        assertEquals(setOf(a1, binaryLeft), map.attrs)
        assertEquals(binaryLeft, map.attrsH.getValue(binaryRight.hash()))
        assertEquals(listOf(AttrRange(0, 1, listOf(a1, binaryLeft))), map.ranges(0))
    }

    @Test
    fun attrRangesWrapperNormalizesOverlapsAndCopiesRanges() {
        val ranges = AttrRanges()
        ranges.add(2, 2, listOf(a1))
        ranges.add(1, 2, listOf(a2))
        ranges.add(5, 1, listOf(a3))

        assertEquals(
            listOf(
                AttrRange(1, 1, listOf(a2)),
                AttrRange(2, 1, listOf(a2, a1)),
                AttrRange(3, 1, listOf(a1)),
                AttrRange(5, 1, listOf(a3)),
            ),
            ranges.getIds(),
        )

        val copy = ranges.copy()
        ranges.add(4, 1, listOf(a1))

        assertEquals(
            listOf(
                AttrRange(1, 1, listOf(a2)),
                AttrRange(2, 1, listOf(a2, a1)),
                AttrRange(3, 2, listOf(a1)),
                AttrRange(5, 1, listOf(a3)),
            ),
            ranges.getIds(),
        )
        assertEquals(
            listOf(
                AttrRange(1, 1, listOf(a2)),
                AttrRange(2, 1, listOf(a2, a1)),
                AttrRange(3, 1, listOf(a1)),
                AttrRange(5, 1, listOf(a3)),
            ),
            copy.getIds(),
        )
    }

    @Test
    fun idMapDeleteRemovesRangesWithoutTouchingOtherClients() {
        val map = createIdMap()
        map.add(0, 0, 4, listOf(a1))
        map.add(1, 0, 2, listOf(a2))

        map.delete(0, 1, 2)

        assertEquals(listOf(0L to AttrRange(0, 1, listOf(a1)), 0L to AttrRange(3, 1, listOf(a1)), 1L to AttrRange(0, 2, listOf(a2))), map.ranges())
    }

    private fun idSet(vararg triples: Long): IdSet {
        require(triples.size % 3 == 0)
        val idSet = createIdSet()
        triples.asList().chunked(3).forEach { (client, clock, len) -> idSet.add(client, clock, len) }
        return idSet
    }

    private fun assertIdMapEquals(expected: IdMap, actual: IdMap) {
        assertTrue(equalIdMaps(expected, actual), "expected ${expected.ranges()} but was ${actual.ranges()}")
    }
}
