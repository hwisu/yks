package dev.yks

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateContentIdsTest {
    @Test
    fun contentIdsConstructionMergeExcludeIntersectAndEncodingRoundTrip() {
        val left = createContentIds(
            inserts = idSet(1, 0, 3),
            deletes = idSet(1, 1, 1),
        )
        val right = createContentIds(
            inserts = idSet(1, 2, 2, 2, 0, 1),
            deletes = idSet(3, 0, 1),
        )

        val merged = mergeContentIds(listOf(left, right))
        val excluded = excludeContentIds(merged, left)
        val intersected = intersectContentIds(
            merged,
            createContentIds(inserts = idSet(1, 2, 5), deletes = idSet(1, 0, 10)),
        )
        val intersectedWithMap = intersectContentIds(
            merged,
            createContentMapFromContentIds(
                createContentIds(inserts = idSet(1, 3, 2), deletes = idSet(3, 0, 1)),
                insertAttrs = listOf(createContentAttribute("source", "map")),
            ),
        )
        val decoded = decodeContentIds(encodeContentIds(merged))
        val encoder = BinaryEncoder()
        writeContentIds(encoder, merged)
        val decoder = BinaryDecoder(encoder.toByteArray())
        val read = readContentIds(decoder)

        assertContentIdsEquals(createContentIds(
            inserts = idSet(1, 0, 4, 2, 0, 1),
            deletes = idSet(1, 1, 1, 3, 0, 1),
        ), merged)
        assertContentIdsEquals(createContentIds(
            inserts = idSet(1, 3, 1, 2, 0, 1),
            deletes = idSet(3, 0, 1),
        ), excluded)
        assertContentIdsEquals(createContentIds(
            inserts = idSet(1, 2, 2),
            deletes = idSet(1, 1, 1),
        ), intersected)
        assertContentIdsEquals(createContentIds(
            inserts = idSet(1, 3, 1),
            deletes = idSet(3, 0, 1),
        ), intersectedWithMap)
        assertContentIdsEquals(merged, decoded)
        assertContentIdsEquals(merged, read)
        assertFalse(decoder.hasRemaining())
        assertTrue(createContentIds().inserts.isEmpty())
        assertTrue(createContentIds().deletes.isEmpty())
    }

    @Test
    fun contentIdsCanBeDerivedFromDocsAndDocDiffs() {
        val older = YDoc(clientId = 1)
        older.getText("body").insert(0, "a")
        val newer = cloneDoc(older).also { it.clientId = 2 }
        newer.getText("body").insert(1, "b")
        newer.getText("body").delete(0)

        val fromDoc = createContentIdsFromDoc(newer)
        val fromUpdate = createContentIdsFromUpdate(newer.encodeStateAsUpdate())
        val diff = createContentIdsFromDocDiff(newer, older)

        assertContentIdsEquals(fromUpdate, fromDoc)
        assertTrue(diff.inserts.has(2, 0))
        assertFalse(diff.inserts.has(1, 0))
        assertTrue(diff.deletes.has(1, 0))
    }

    @Test
    fun insertAndDeleteSetsCanBeDerivedFromDocs() {
        val doc = YDoc(clientId = 1)
        val text = doc.getText("body")
        text.insert(0, "abc")
        text.delete(1)

        val allInserts = createInsertSetFromDoc(doc)
        val visibleInserts = createInsertSetFromDoc(doc, filterDeleted = true)
        val deletes = createDeleteSetFromDoc(doc)

        assertContentIdsEquals(createContentIds(allInserts, deletes), createContentIdsFromDoc(doc))
        assertEquals(allInserts.ranges(), createInsertSetFromStructStore(doc).ranges())
        assertEquals(visibleInserts.ranges(), createInsertSetFromStructStore(doc, filterDeleted = true).ranges())
        assertEquals(deletes.ranges(), createDeleteSetFromStructStore(doc).ranges())
        assertEquals(listOf(1L to IdRange(0, 3)), allInserts.ranges())
        assertEquals(listOf(1L to IdRange(0, 1), 1L to IdRange(2, 1)), visibleInserts.ranges())
        assertEquals(listOf(1L to IdRange(1, 1)), deletes.ranges())
    }

    @Test
    fun contentMapsConvertMergeExcludeIntersectFilterAndEncode() {
        val local = createContentAttribute("source", "local")
        val remote = createContentAttribute("source", "remote")
        val deleted = createContentAttribute("op", "delete")
        val left = createContentMapFromContentIds(
            createContentIds(inserts = idSet(1, 0, 3), deletes = idSet(1, 1, 1)),
            insertAttrs = listOf(local),
            deleteAttrs = listOf(deleted),
        )
        val right = createContentMapFromContentIds(
            createContentIds(inserts = idSet(1, 2, 2, 2, 0, 1), deletes = idSet(3, 0, 1)),
            insertAttrs = listOf(remote),
        )

        val merged = mergeContentMaps(listOf(left, right))
        val excludedByIds = excludeContentMap(
            merged,
            createContentIds(inserts = idSet(1, 1, 1), deletes = idSet(1, 1, 1)),
        )
        val excludedByMap = excludeContentMap(merged, right)
        val intersectedByIds = intersectContentMap(
            merged,
            createContentIds(inserts = idSet(1, 2, 5), deletes = idSet(3, 0, 1)),
        )
        val intersectedByMap = intersectContentMap(merged, right)
        val filtered = filterContentMap(
            merged,
            insertPredicate = { attrs -> local in attrs },
            deletePredicate = { false },
        )
        val decoded = decodeContentMap(encodeContentMap(merged))
        val encoder = BinaryEncoder()
        writeContentMap(encoder, merged)
        val decoder = BinaryDecoder(encoder.toByteArray())
        val read = readContentMap(decoder)

        assertContentIdsEquals(createContentIds(
            inserts = idSet(1, 0, 4, 2, 0, 1),
            deletes = idSet(1, 1, 1, 3, 0, 1),
        ), createContentIdsFromContentMap(merged))
        assertFalse(excludedByIds.inserts.has(1, 1))
        assertFalse(excludedByIds.deletes.has(1, 1))
        assertFalse(excludedByMap.inserts.has(1, 3))
        assertTrue(intersectedByIds.inserts.has(1, 2))
        assertFalse(intersectedByIds.inserts.has(1, 0))
        assertTrue(intersectedByIds.deletes.has(3, 0))
        assertTrue(
            intersectedByMap.inserts
                .slice(1, 2, 1)
                .first()
                .attrs
                .orEmpty()
                .containsAll(listOf(local, remote)),
        )
        assertTrue(filtered.inserts.has(1, 0))
        assertFalse(filtered.inserts.has(1, 3))
        assertTrue(filtered.deletes.isEmpty())
        assertContentMapEquals(merged, decoded)
        assertContentMapEquals(merged, read)
        assertFalse(decoder.hasRemaining())
    }

    @Test
    fun createContentIdsFromUpdateReportsInsertedAndDeletedRanges() {
        val doc = YDoc(clientId = 1)
        val text = doc.getText("body")
        text.insert(0, "abc")
        text.delete(1)

        val ids = createContentIdsFromUpdate(doc.encodeStateAsUpdate())

        assertTrue(ids.inserts.has(1, 0))
        assertTrue(ids.inserts.has(1, 1))
        assertTrue(ids.inserts.has(1, 2))
        assertTrue(ids.deletes.has(1, 1))
        assertFalse(ids.deletes.has(1, 0))
    }

    @Test
    fun intersectUpdateWithContentIdsFiltersInsertedItems() {
        val source = YDoc(clientId = 1)
        source.getText("body").insert(0, "abc")
        val update = source.encodeStateAsUpdate()
        val ids = ContentIds(
            inserts = createIdSet().also { it.add(1, 0, 1) },
            deletes = createIdSet(),
        )

        val target = createDocFromUpdate(intersectUpdateWithContentIds(update, ids))

        assertEquals("a", target.getText("body").toString())
    }

    @Test
    fun intersectUpdateWithContentIdsKeepsSparseMidStreamSelections() {
        val source = YDoc(clientId = 1)
        source.transact {
            repeat(10) { index -> source.getMap("m").setAttr("k$index", index) }
        }
        val update = source.encodeStateAsUpdate()
        val contentIds = createContentIdsFromUpdate(update)

        val chunk = intersectUpdateWithContentIds(
            update,
            ContentIds(
                inserts = createIdSet().also { it.add(1, 5, 2) },
                deletes = createIdSet(),
            ),
        )
        val structs = decodeUpdate(chunk).structs

        assertEquals(listOf(Id(1, 5), Id(1, 6)), structs.map { it.id })
        assertEquals(listOf(1L, 1L), structs.map { it.length })
        assertEquals(mapOf("k5" to 5L, "k6" to 6L), createDocFromUpdate(chunk).getMap("m").toMap())

        val splitChunk = intersectUpdateWithContentIds(
            update,
            ContentIds(
                inserts = createIdSet().also { selected ->
                    selected.add(1, 1, 2)
                    selected.add(1, 7, 1)
                },
                deletes = createIdSet(),
            ),
        )
        assertEquals(
            listOf(Id(1, 1), Id(1, 2), Id(1, 7)),
            decodeUpdate(splitChunk).structs.map { it.id },
        )

        val allSelected = createIdSet().also { selected ->
            contentIds.inserts.ranges().forEach { (client, range) ->
                selected.add(client, range.clock, range.len)
            }
        }
        val fullChunk = intersectUpdateWithContentIds(
            update,
            ContentIds(inserts = allSelected, deletes = createIdSet()),
        )

        assertContentEquals(update, fullChunk)
    }

    @Test
    fun intersectUpdateWithContentIdsKeepsOnlySelectedDeletes() {
        val source = YDoc(clientId = 1)
        val text = source.getText("body")
        text.insert(0, "abc")
        val initialUpdate = source.encodeStateAsUpdate()
        val stateAfterInsert = source.encodeStateVector()

        text.delete(1)
        val deleteUpdate = source.encodeStateAsUpdate(stateAfterInsert)
        val targetWithoutDelete = createDocFromUpdate(initialUpdate)
        targetWithoutDelete.applyUpdate(
            intersectUpdateWithContentIds(
                deleteUpdate,
                ContentIds(inserts = createIdSet(), deletes = createIdSet()),
            ),
        )

        assertEquals("abc", targetWithoutDelete.getText("body").toString())

        val targetWithDelete = createDocFromUpdate(initialUpdate)
        targetWithDelete.applyUpdate(
            intersectUpdateWithContentIds(
                deleteUpdate,
                ContentIds(inserts = createIdSet(), deletes = createIdSet().also { it.add(1, 1, 1) }),
            ),
        )

        assertEquals("ac", targetWithDelete.getText("body").toString())
    }

    @Test
    fun readAndApplyDeleteSetReturnsUnappliedDeletesForMissingClocks() {
        val source = YDoc(clientId = 1)
        val text = source.getText("body")
        text.insert(0, "ab")
        val target = createDocFromUpdate(source.encodeStateAsUpdate())
        val targetState = target.encodeStateVector()
        text.insert(2, "c")
        val missingInsert = source.encodeStateAsUpdate(targetState)
        val deleteIds = createIdSet().also { it.add(1, 1, 2) }
        val encoder = IdSetEncoderV1()
        writeIdSet(encoder, deleteIds)
        val decoder = IdSetDecoderV1(encoder.toByteArray())

        val unapplied = readAndApplyDeleteSet(decoder, target, origin = "delete-read")

        assertFalse(decoder.hasRemaining())
        assertEquals("a", target.getText("body").toString())
        val unappliedIds = decodeUpdate(unapplied!!).deleteSet.toIdSet()
        assertFalse(unappliedIds.has(1, 1))
        assertTrue(unappliedIds.has(1, 2))

        applyUpdate(target, missingInsert)
        assertEquals("ac", target.getText("body").toString())
        applyUpdate(target, unapplied)
        assertEquals("a", target.getText("body").toString())
    }

    @Test
    fun readAndApplyDeleteSetReturnsNullWhenAllDeletesApply() {
        val source = YDoc(clientId = 1)
        source.getText("body").insert(0, "ab")
        val target = createDocFromUpdate(source.encodeStateAsUpdate())
        val encoder = BinaryEncoder()
        writeIdSet(encoder, createIdSet().also { it.add(1, 0, 2) })

        val unapplied = readAndApplyDeleteSet(BinaryDecoder(encoder.toByteArray()), target)

        assertNull(unapplied)
        assertEquals("", target.getText("body").toString())
    }

    @Test
    fun contentIdsWorkWithMergedUpdatesAndV2Aliases() {
        val left = YDoc(clientId = 1)
        val right = YDoc(clientId = 2)
        left.getArray("items").push(listOf("left"))
        right.getArray("items").push(listOf("right"))

        val merged = mergeUpdatesV2(listOf(encodeStateAsUpdateV2(left), encodeStateAsUpdateV2(right)))
        val ids = createContentIdsFromUpdateV2(merged)

        assertTrue(ids.inserts.has(1, 0))
        assertTrue(ids.inserts.has(2, 0))

        val filtered = intersectUpdateWithContentIdsV2(
            merged,
            ContentIds(inserts = createIdSet().also { it.add(2, 0, 1) }, deletes = createIdSet()),
        )
        val target = createDocFromUpdateV2(filtered)

        assertEquals(mapOf("items" to listOf("right")), target.toJson())
    }

    private fun idSet(vararg triples: Long): IdSet {
        require(triples.size % 3 == 0)
        val idSet = createIdSet()
        triples.asList().chunked(3).forEach { (client, clock, len) -> idSet.add(client, clock, len) }
        return idSet
    }

    private fun assertContentIdsEquals(expected: ContentIds, actual: ContentIds) {
        assertTrue(
            equalIdSets(expected.inserts, actual.inserts),
            "expected inserts ${expected.inserts.ranges()} but was ${actual.inserts.ranges()}",
        )
        assertTrue(
            equalIdSets(expected.deletes, actual.deletes),
            "expected deletes ${expected.deletes.ranges()} but was ${actual.deletes.ranges()}",
        )
    }

    private fun assertContentMapEquals(expected: ContentMap, actual: ContentMap) {
        assertTrue(
            equalIdMaps(expected.inserts, actual.inserts),
            "expected inserts ${expected.inserts.ranges()} but was ${actual.inserts.ranges()}",
        )
        assertTrue(
            equalIdMaps(expected.deletes, actual.deletes),
            "expected deletes ${expected.deletes.ranges()} but was ${actual.deletes.ranges()}",
        )
    }
}
