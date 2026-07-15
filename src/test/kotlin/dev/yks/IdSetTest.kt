package dev.yks

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IdSetTest {
    @Test
    fun idSetCanBeConstructedAndIteratedLikeUpstreamExport() {
        val idSet = IdSet()
        val seen = mutableListOf<Pair<Long, IdRange>>()

        idSet.add(2, 10, 1)
        idSet.add(1, 0, 2)
        idSet.add(1, 2, 1)
        idSet.forEach { range, client -> seen.add(client to range) }

        assertEquals(
            listOf(1L to IdRange(0, 3), 2L to IdRange(10, 1)),
            seen,
        )
        assertEquals(listOf(IdRange(0, 3)), idSet.clients.getValue(1L).toList())
        assertFalse(idSet.isEmpty())
    }

    @Test
    fun idSetNormalizesEmptyAdjacentAndOverlappingRanges() {
        assertIdSetEquals(idSet(), idSet(0, 1, 0))
        assertIdSetEquals(idSet(0, 1, 1), idSet(0, 1, 1, 0, 2, 0))
        assertIdSetEquals(idSet(0, 0, 3), idSet(0, 1, 2, 0, 0, 2))
        assertIdSetEquals(idSet(0, 1, 3), idSet(0, 1, 2, 0, 3, 1))
    }

    @Test
    fun idSetDiffHandlesSubsetAndOverlapCases() {
        assertIdSetEquals(idSet(0, 1, 1), diffIdSet(idSet(0, 1, 1, 0, 3, 1), idSet(0, 3, 1)))
        assertIdSetEquals(idSet(0, 2, 2), diffIdSet(idSet(0, 1, 3), idSet(0, 1, 1)))
        assertIdSetEquals(idSet(0, 1, 2), diffIdSet(idSet(0, 1, 3), idSet(0, 3, 1)))
        assertIdSetEquals(idSet(0, 1, 1, 0, 3, 1), diffIdSet(idSet(0, 1, 3), idSet(0, 2, 1)))
        assertIdSetEquals(idSet(0, 2, 2), diffIdSet(idSet(0, 1, 3), idSet(0, 0, 2)))
        assertIdSetEquals(idSet(), diffIdSet(idSet(0, 1, 3), idSet(0, 0, 5)))
    }

    @Test
    fun idSetHelpersAcceptIdMapExcludeAndIntersectLikeUpstream() {
        val attr = createContentAttribute("mark", true)
        val idMap = createIdMap()
        idMap.add(0, 1, 2, listOf(attr))
        idMap.add(1, 1, 1, listOf(attr))
        val source = idSet(0, 0, 5, 1, 0, 2)

        assertIdSetEquals(
            idSet(0, 0, 1, 0, 3, 2, 1, 0, 1),
            diffIdSet(source, idMap),
        )
        assertIdSetEquals(diffIdSet(source, idMap), _diffSet(source, idMap))
        assertIdSetEquals(
            idSet(0, 1, 2, 1, 1, 1),
            intersectSets(source, idMap),
        )
        assertIdSetEquals(intersectSets(source, idMap), _intersectSets(source, idMap))
    }

    @Test
    fun idSetDeleteMatchesDiffing() {
        val source = idSet(0, 1, 3, 0, 5, 2)
        val diffed = diffIdSet(source, idSet(0, 2, 4))
        _deleteRangeFromIdSet(source, 0, 2, 4)

        assertIdSetEquals(idSet(0, 1, 1, 0, 6, 1), source)
        assertIdSetEquals(source, diffed)
        assertFalse(source.has(0, 2))
        assertTrue(source.hasId(Id(0, 6)))
    }

    @Test
    fun idSetMergeIntersectSliceAndEncodingRoundTrip() {
        val left = idSet(1, 0, 3, 2, 10, 1)
        val right = idSet(1, 2, 4, 3, 0, 1)
        val merged = mergeIdSets(listOf(left, right))

        assertIdSetEquals(idSet(1, 0, 6, 2, 10, 1, 3, 0, 1), merged)
        assertIdSetEquals(idSet(1, 2, 1), intersectSets(left, right))
        assertEquals(
            listOf(
                MaybeIdRange(0, 5, true),
            ),
            merged.slice(1, 0, 5),
        )
        assertEquals(listOf(MaybeIdRange(2, 0, false)), merged.slice(1, 2, 0))
        assertIdSetEquals(merged, decodeIdSet(encodeIdSet(merged)))
        val encoder = BinaryEncoder()
        writeIdSet(encoder, merged)
        val decoder = BinaryDecoder(encoder.toByteArray())
        assertIdSetEquals(merged, readIdSet(decoder))
        assertFalse(decoder.hasRemaining())
    }

    @Test
    fun encodeIdSetUsesUpstreamV2ClockDeltas() {
        val encoded = encodeIdSet(idSet(3, 2, 2, 3, 7, 1))

        assertContentEquals(byteArrayOf(1, 3, 2, 2, 1, 3, 0), encoded)
        assertIdSetEquals(idSet(3, 2, 2, 3, 7, 1), decodeIdSet(encoded))
    }

    @Test
    fun idRangeSearchHelpersMirrorUpstreamBoundaries() {
        val ranges = listOf(IdRange(2, 3), IdRange(8, 2), IdRange(12, 1))

        assertEquals(IdRange(6, 1), ranges.first().copyWith(6, 1))
        assertEquals(emptyList(), ranges.first().attrs)
        assertEquals(createMaybeIdRange(5, 2, false), MaybeIdRange(5, 2, false))
        assertEquals(0, findIndexInIdRanges(ranges, 2))
        assertEquals(0, findIndexInIdRanges(ranges, 4))
        assertEquals(1, findIndexInIdRanges(ranges, 9))
        assertEquals(null, findIndexInIdRanges(ranges, 5))
        assertEquals(null, findIndexInIdRanges(ranges, 13))
        assertEquals(0, findRangeStartInIdRanges(ranges, 0))
        assertEquals(0, findRangeStartInIdRanges(ranges, 4))
        assertEquals(1, findRangeStartInIdRanges(ranges, 5))
        assertEquals(2, findRangeStartInIdRanges(ranges, 10))
        assertEquals(null, findRangeStartInIdRanges(ranges, 13))
    }

    @Test
    fun idRangesWrapperNormalizesAndCopiesRanges() {
        val ranges = IdRanges()
        ranges.add(3, 2)
        ranges.add(1, 3)
        ranges.add(8, 1)

        assertEquals(listOf(IdRange(1, 4), IdRange(8, 1)), ranges.getIds())

        val copy = ranges.copy()
        ranges.delete(2, 2)

        assertEquals(listOf(IdRange(1, 1), IdRange(4, 1), IdRange(8, 1)), ranges.getIds())
        assertEquals(listOf(IdRange(1, 4), IdRange(8, 1)), copy.getIds())
    }

    @Test
    fun idSetHandlesLargeRangesWithoutExpandingClocks() {
        val idSet = createIdSet()

        idSet.add(3, 0, 1_000_000_000)
        idSet.add(3, 500_000_000, 1_000_000_000)

        assertEquals(listOf(IdRange(0, 1_500_000_000)), idSet.ranges(3))
        assertTrue(idSet.has(3, 1_499_999_999))
        assertEquals(
            listOf(MaybeIdRange(1_499_999_999, 1, true), MaybeIdRange(1_500_000_000, 1, false)),
            idSet.slice(3, 1_499_999_999, 2),
        )
    }

    @Test
    fun idSetRejectsOverflowBeforeMutatingState() {
        val idSet = createIdSet()

        assertFailsWith<IllegalStateException> {
            idSet.add(1, Long.MAX_VALUE, 1)
        }

        assertTrue(idSet.isEmpty())
    }

    private fun idSet(vararg triples: Long): IdSet {
        require(triples.size % 3 == 0)
        val idSet = createIdSet()
        triples.asList().chunked(3).forEach { (client, clock, len) -> idSet.add(client, clock, len) }
        return idSet
    }

    private fun assertIdSetEquals(expected: IdSet, actual: IdSet) {
        assertTrue(equalIdSets(expected, actual), "expected ${expected.ranges()} but was ${actual.ranges()}")
    }
}
