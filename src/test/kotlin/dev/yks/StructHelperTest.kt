package dev.yks

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StructHelperTest {
    @Test
    fun abstractStructHelpersSearchSplitMergeAndAddToIdSet() {
        val skip = Skip(Id(1, 0), 2)
        val split = skip.splice(1)
        val gc = GC(Id(1, 2), 1)
        assertTrue(gc.mergeWith(GC(Id(1, 3), 2)))

        val structs = listOf(skip, split, gc)
        val idSet = createIdSet()
        addStructToIdSet(idSet, gc)

        assertEquals(1L, skip.length)
        assertEquals(Id(1, 1), split.id)
        assertEquals(1L, split.length)
        assertEquals(3L, gc.length)
        assertEquals(structSkipRefNumber, skip.ref)
        assertEquals(structGCRefNumber, gc.ref)
        assertTrue(skip.deleted)
        assertTrue(gc.deleted)
        assertEquals(0, findIndexSS(structs, 0))
        assertEquals(1, findIndexSS(structs, 1))
        assertEquals(2, findIndexSS(structs, 4))
        assertFailsWith<IllegalStateException> { findIndexSS(structs, 5) }
        assertTrue(idSet.has(1, 2))
        assertTrue(idSet.has(1, 4))
    }

    @Test
    fun documentStructHelpersExposeItemSnapshotsAndIteration() {
        val doc = YDoc(clientId = 1, gc = false)
        val text = doc.getText("body")
        text.insert(0, "abc")
        text.delete(1)

        val structs = getTypeStructs(text)
        val visited = mutableListOf<Pair<Id, Long>>()
        iterateStructsByIdSet(doc, idSet(1, 0, 3, 1, 10, 1)) { struct, offset, length ->
            assertEquals(0L, offset)
            visited.add(struct.id to length)
        }

        assertEquals(listOf(Id(1, 0), Id(1, 1), Id(1, 2)), structs.map { it.id })
        assertEquals(listOf(false, true, false), structs.map { it.deleted })
        assertEquals(setOf(true), structs.map { it.isItem }.toSet())
        assertEquals(listOf("a", "b", "c"), structs.map { (it.content as ContentString).str })
        assertEquals(1, findIndexSS(structs, 1))
        assertEquals(structs[1], getItemCleanStart(doc, Id(1, 1)))
        assertEquals(structs[1], getItemCleanEnd(doc, Id(1, 1)))
        assertEquals(listOf(Id(1, 0) to 1L, Id(1, 1) to 1L, Id(1, 2) to 1L), visited)
        assertFailsWith<IllegalStateException> { getItemCleanStart(doc, Id(1, 10)) }
    }

    @Test
    fun publicItemAliasNamesTheStructViewNotTheInternalStoreEntry() {
        val doc = YDoc(clientId = 1)
        val text = doc.getText("body")
        text.insert(0, "x")

        val item: Item = getItemCleanStart(doc, Id(1, 0))

        assertEquals(Id(1, 0), item.id)
        assertEquals(1L, item.length)
        assertTrue(item.isItem)
        assertEquals("x", (item.content as ContentString).str)
    }

    @Test
    fun followRedoneExposesCurrentItemForOriginalIds() {
        val doc = YDoc(clientId = 1)
        val text = doc.getText("body")
        text.insert(0, "ab")

        val unchanged = followRedone(doc, Id(1, 0))

        assertEquals(Id(1, 0), unchanged.item.id)
        assertEquals(0L, unchanged.diff)
        assertEquals("ab", (unchanged.item.content as ContentString).str)

        val undoManager = UndoManager(text, UndoManagerOptions(captureTimeoutMillis = 0))
        text.delete(0, 1)
        undoManager.undo()

        val followed = followRedone(doc, Id(1, 0))

        assertEquals(Id(1, 2), followed.item.id)
        assertEquals(0L, followed.diff)
        assertEquals("a", (followed.item.content as ContentString).str)
    }

    @Test
    fun gcHelpersCollectDeletedEligibleStructsWithoutChangingObservableDocument() {
        val doc = YDoc(clientId = 1, gc = false)
        val text = doc.getText("body")
        text.insert(0, "abcd")
        text.delete(1, 2)

        val eligible = gcIdSet(doc, idSet(1, 0, 4))

        assertFalse(eligible.has(1, 0))
        assertTrue(eligible.has(1, 1))
        assertTrue(eligible.has(1, 2))
        assertFalse(eligible.has(1, 3))
        assertTrue(structAt(doc, 1).content is ContentDeleted)
        assertTrue(structAt(doc, 2).content is ContentDeleted)
        assertEquals("ad", text.toString())

        val peer = YDoc()
        applyUpdate(peer, encodeStateAsUpdate(doc))
        assertEquals("ad", peer.getText("body").toString())
    }

    @Test
    fun gcHelperAliasesCollectFilteredDeletedStructs() {
        val filteredDoc = deletedTextDoc()
        val filtered = tryGc(filteredDoc, idSet(1, 2, 1)) { struct -> struct.id.clock == 2L }
        val aliasDoc = deletedTextDoc()
        val fromIdSetAlias = tryGcDeleteSet(aliasDoc, idSet(1, 1, 1)) { struct -> struct.id.clock == 1L }
        val deleteSetDoc = deletedTextDoc()
        val deleteSet = DeleteSet.empty().also { deleteSet -> deleteSet.add(Id(1, 1), 2) }
        val fromDeleteSetAlias = tryGcDeleteSet(deleteSetDoc, deleteSet)

        assertFalse(filtered.has(1, 1))
        assertTrue(filtered.has(1, 2))
        assertTrue(structAt(filteredDoc, 2).content is ContentDeleted)
        assertFalse(structAt(filteredDoc, 1).content is ContentDeleted)
        assertTrue(fromIdSetAlias.has(1, 1))
        assertFalse(fromIdSetAlias.has(1, 2))
        assertTrue(structAt(aliasDoc, 1).content is ContentDeleted)
        assertFalse(structAt(aliasDoc, 2).content is ContentDeleted)
        assertTrue(fromDeleteSetAlias.has(1, 1))
        assertTrue(fromDeleteSetAlias.has(1, 2))
        assertTrue(structAt(deleteSetDoc, 1).content is ContentDeleted)
        assertTrue(structAt(deleteSetDoc, 2).content is ContentDeleted)
    }

    @Test
    fun gcHelpersUseDocumentGcFilterByDefaultAndAllowOverrides() {
        val doc = YDoc(clientId = 1, gc = false, gcFilter = { struct -> struct.id.clock == 2L })
        val text = doc.getText("body")
        text.insert(0, "abcd")
        text.delete(1, 2)

        val fromDocFilter = gcIdSet(doc, idSet(1, 2, 1))
        val overrideFilter = tryGc(doc, idSet(1, 1, 1)) { struct -> struct.id.clock == 1L }

        assertFalse(fromDocFilter.has(1, 1))
        assertTrue(fromDocFilter.has(1, 2))
        assertTrue(overrideFilter.has(1, 1))
        assertFalse(overrideFilter.has(1, 2))
        assertTrue(structAt(doc, 1).content is ContentDeleted)
        assertTrue(structAt(doc, 2).content is ContentDeleted)
    }

    @Test
    fun gcHelpersUseMutableDocumentGcFilterAtCallTime() {
        val doc = YDoc(clientId = 1, gc = false)
        val text = doc.getText("body")
        text.insert(0, "abcd")
        text.delete(1, 2)

        doc.gcFilter = { struct -> struct.id.clock == 1L }
        val firstFilter = gcIdSet(doc, idSet(1, 1, 1))

        doc.gcFilter = { struct -> struct.id.clock == 2L }
        val secondFilter = gcIdSet(doc, idSet(1, 2, 1))

        assertTrue(firstFilter.has(1, 1))
        assertFalse(firstFilter.has(1, 2))
        assertFalse(secondFilter.has(1, 1))
        assertTrue(secondFilter.has(1, 2))
        assertTrue(structAt(doc, 1).content is ContentDeleted)
        assertTrue(structAt(doc, 2).content is ContentDeleted)
    }

    @Test
    fun gcIdSetOnlyCollectsReferencedDeletedRangeAndIsIdempotent() {
        val doc = YDoc(clientId = 1, gc = false)
        val text = doc.getText("body")
        text.insert(0, "abcde")
        text.delete(1, 4)

        val collected = gcIdSet(doc, idSet(1, 2, 2))
        val encodedAfterFirstCollect = encodeStateAsUpdate(doc)
        val secondCollect = gcIdSet(doc, idSet(1, 2, 2))

        assertFalse(collected.has(1, 1))
        assertTrue(collected.has(1, 2))
        assertTrue(collected.has(1, 3))
        assertFalse(collected.has(1, 4))
        assertTrue(secondCollect.isEmpty())
        assertContentString(doc, 1, "b")
        assertTrue(structAt(doc, 2).content is ContentDeleted)
        assertTrue(structAt(doc, 3).content is ContentDeleted)
        assertContentString(doc, 4, "e")
        assertEquals("a", text.toString())
        val encodedAfterSecondCollect = encodeStateAsUpdate(doc)
        assertEquals(
            createDocFromUpdate(encodedAfterFirstCollect).getText("body").toString(),
            createDocFromUpdate(encodedAfterSecondCollect).getText("body").toString(),
        )
        assertEquals(
            decodeStateVector(encodeStateVectorFromUpdate(encodedAfterFirstCollect)),
            decodeStateVector(encodeStateVectorFromUpdate(encodedAfterSecondCollect)),
        )
    }

    @Test
    fun gcIdSetCollectsNestedTypeContentWhenDirectTypeReferenceIsCollected() {
        val doc = YDoc(clientId = 1, gc = false)
        val root = doc.getArray("root")
        val nested = doc.createMap()
        val child = doc.createText()
        root.push(nested)
        nested.setAttr("body", child)
        child.insert(0, "hi")
        root.delete(0)

        val collected = gcIdSet(doc, idSet(1, 0, 1)) { struct -> struct.id.clock == 0L }
        val peer = YDoc()
        assertFailsWith<UnsupportedYjsStandardUpdateException> { encodeStateAsUpdate(doc) }
        applyUpdateLossless(peer, encodeStateAsUpdateLossless(doc))

        assertTrue(collected.has(1, 0))
        assertTrue(collected.has(1, 1))
        assertTrue(collected.has(1, 2))
        assertTrue(collected.has(1, 3))
        assertTrue(structAt(doc, 0).content is ContentDeleted)
        assertTrue(structAt(doc, 1).content is ContentDeleted)
        assertTrue(structAt(doc, 2).content is ContentDeleted)
        assertTrue(structAt(doc, 3).content is ContentDeleted)
        assertEquals(emptyList(), root.toArray())
        assertEquals(emptyMap(), nested.toMap())
        assertEquals("", child.toString())
        assertEquals(emptyList(), peer.getArray("root").toArray())
    }

    @Test
    fun keepItemPreventsGcEligibilityForItemAndParentChain() {
        val doc = YDoc(clientId = 1, gc = false)
        val root = doc.getArray("root")
        val nested = doc.createText()
        root.push(nested)
        nested.insert(0, "ab")
        nested.delete(0, 1)
        val deleted = getTypeStructs(nested).first()
        val nestedRef = getTypeStructs(root).first()

        val kept = keepItem(doc, deleted)

        assertTrue(kept.hasId(deleted.id))
        assertTrue(kept.hasId(nestedRef.id))
        assertFalse(gcIdSet(doc, idSet(deleted.id.client, deleted.id.clock, deleted.length)).hasId(deleted.id))

        keepItem(doc, deleted, keep = false)

        assertTrue(gcIdSet(doc, idSet(deleted.id.client, deleted.id.clock, deleted.length)).hasId(deleted.id))
    }

    @Test
    fun cleanStartAndSplitStructHelpersSplitMutableStructLists() {
        val structs = mutableListOf<AbstractStruct>(item(1, 0, "abcd"), GC(Id(1, 4), 2))

        val index = findIndexCleanStart(structs, 2)

        assertEquals(1, index)
        assertEquals(listOf(Id(1, 0), Id(1, 2), Id(1, 4)), structs.map { it.id })
        assertEquals(listOf(2L, 2L, 2L), structs.map { it.length })
        assertEquals("ab", (structs[0] as ItemStruct).content.getContent().joinToString(separator = ""))
        assertEquals("cd", (structs[1] as ItemStruct).content.getContent().joinToString(separator = ""))
    }

    @Test
    fun iterateStructsSplitsRangeBoundariesWhileWithoutSplitsReportsOffsets() {
        val splitStructs = mutableListOf<AbstractStruct>(item(1, 0, "abcde"))
        val splitVisited = mutableListOf<Pair<Id, Long>>()

        iterateStructs(splitStructs, 1, 3) { struct ->
            splitVisited.add(struct.id to struct.length)
        }

        assertEquals(listOf(Id(1, 1) to 3L), splitVisited)
        assertEquals(listOf(Id(1, 0), Id(1, 1), Id(1, 4)), splitStructs.map { it.id })
        assertEquals(listOf(1L, 3L, 1L), splitStructs.map { it.length })

        val unsplitVisited = mutableListOf<Triple<Id, Long, Long>>()
        iterateStructsWithoutSplits(listOf(item(1, 0, "abcde")), 1, 3) { struct, offset, length ->
            unsplitVisited.add(Triple(struct.id, offset, length))
        }

        assertEquals(listOf(Triple(Id(1, 0), 1L, 4L)), unsplitVisited)
    }

    @Test
    fun structRangeHelpersRejectOverflowWithoutPartiallyMerging() {
        val gc = GC(Id(1, 0), Long.MAX_VALUE)
        val skip = Skip(Id(2, 0), Long.MAX_VALUE)

        assertFailsWith<IllegalStateException> {
            gc.mergeWith(GC(Id(1, Long.MAX_VALUE), 1))
        }
        assertFailsWith<IllegalStateException> {
            skip.mergeWith(Skip(Id(2, Long.MAX_VALUE), 1))
        }
        assertEquals(Long.MAX_VALUE, gc.length)
        assertEquals(Long.MAX_VALUE, skip.length)

        val nearLimit = mutableListOf<AbstractStruct>(GC(Id(3, Long.MAX_VALUE), 1))
        assertFailsWith<IllegalStateException> {
            iterateStructs(nearLimit, Long.MAX_VALUE, 1) {}
        }
        assertFailsWith<IllegalStateException> {
            iterateStructsWithoutSplits(nearLimit, Long.MAX_VALUE, 1) { _, _, _ -> }
        }
    }

    @Test
    fun iterateStructsByIdSetWithoutSplitsUsesStoreRangesAndSkipsMissingClocks() {
        val doc = YDoc(clientId = 1)
        val text = doc.getText("body")
        text.insert(0, "abcd")

        val fromDoc = mutableListOf<Triple<Id, Long, Long>>()
        iterateStructsByIdSetWithoutSplits(doc, idSet(1, 1, 2, 1, 10, 1, 2, 0, 1)) { struct, offset, length ->
            fromDoc.add(Triple(struct.id, offset, length))
        }

        val fromStore = mutableListOf<Triple<Id, Long, Long>>()
        iterateStructsByIdSetWithoutSplits(doc.store, idSet(1, 2, 2)) { struct, offset, length ->
            fromStore.add(Triple(struct.id, offset, length))
        }

        assertEquals(listOf(Triple(Id(1, 0), 1L, 3L)), fromDoc)
        assertEquals(listOf(Triple(Id(1, 0), 2L, 4L)), fromStore)
    }

    @Test
    fun tryToMergeWithLeftsCompactsCompatibleNeighbors() {
        val structs = mutableListOf<AbstractStruct>(
            Skip(Id(1, 0), 1),
            Skip(Id(1, 1), 2),
            GC(Id(1, 3), 1),
            GC(Id(1, 4), 2),
            item(1, 6, "x"),
        )

        assertEquals(1, tryToMergeWithLefts(structs, 3))
        assertEquals(listOf(Id(1, 0), Id(1, 1), Id(1, 3), Id(1, 6)), structs.map { it.id })
        assertEquals(3L, structs[2].length)

        assertEquals(1, tryToMergeWithLefts(structs, 1))
        assertEquals(listOf(Id(1, 0), Id(1, 3), Id(1, 6)), structs.map { it.id })
        assertEquals(3L, structs[0].length)
    }

    @Test
    fun replaceStructAndTryMergeMutateBlockSetRanges() {
        val refs = mutableListOf<AbstractStruct>(
            item(1, 0, "a"),
            item(1, 1, "b"),
        )
        val replacement = item(1, 1, "b").copy(deleted = true)

        assertEquals(replacement, replaceStruct(refs, refs[1], replacement))
        assertEquals(listOf(false, true), refs.map { it.deleted })
        assertFailsWith<IllegalArgumentException> {
            replaceStruct(refs, refs[0], GC(Id(1, 0), 2))
        }

        val blocks = BlockSet(
            linkedMapOf(
                1L to BlockRange(
                    mutableListOf(
                        GC(Id(1, 0), 1),
                        GC(Id(1, 1), 1),
                        Skip(Id(1, 2), 1),
                        Skip(Id(1, 3), 1),
                        item(1, 4, "x"),
                    ),
                ),
            ),
        )
        val blockReplacement = GC(Id(1, 4), 1)

        replaceStruct(blocks, blocks.clients.getValue(1).refs[4], blockReplacement)
        assertTrue(blocks.clients.getValue(1).refs[4] is GC)
        assertEquals(2, tryMerge(idSet(1, 0, 5), blocks))
        assertEquals(listOf(Id(1, 0), Id(1, 2), Id(1, 4)), blocks.clients.getValue(1).refs.map { it.id })
        assertEquals(listOf(2L, 2L, 1L), blocks.clients.getValue(1).refs.map { it.length })
    }

    @Test
    fun updateCurrentFormatsMirrorsContentFormatStateHelper() {
        val currentFormats = linkedMapOf<String, Any?>(
            "bold" to false,
            "color" to "red",
        )

        updateCurrentFormats(currentFormats, ContentFormat("bold", true))
        updateCurrentFormats(currentFormats, ContentFormat("color", null))
        updateCurrentFormats(currentFormats, ContentFormat("count", 0))
        updateCurrentFormats(currentFormats, ContentFormat("enabled", false))

        val expected = linkedMapOf<String, Any?>(
            "bold" to true,
            "count" to 0,
            "enabled" to false,
        )
        assertEquals(
            expected,
            currentFormats,
        )
    }

    @Test
    fun createInsertSliceFromStructsMergesStoreShapedRangesAndFiltersDeleted() {
        val structs = listOf(
            item(1, 0, "a"),
            item(1, 1, "b").copy(deleted = true),
            item(1, 2, "c"),
            item(1, 3, "d"),
            item(1, 6, "gap"),
            item(2, 0, "other"),
        )

        assertEquals(
            listOf(IdRange(0, 4), IdRange(6, 3), IdRange(0, 5)),
            createInsertSliceFromStructs(structs),
        )
        assertEquals(
            listOf(IdRange(0, 1), IdRange(2, 2), IdRange(6, 3), IdRange(0, 5)),
            createInsertSliceFromStructs(structs, filterDeleted = true),
        )
        assertEquals(
            createInsertSliceFromStructs(structs, filterDeleted = true),
            _createInsertSliceFromStructs(structs, filterDeleted = true),
        )
    }

    @Test
    fun nextIDMirrorsCurrentDocumentClientClock() {
        val doc = YDoc(clientId = 1)
        assertEquals(Id(1, 0), nextID(doc))

        doc.getText("body").insert(0, "x")

        assertEquals(Id(1, 1), nextID(doc))
    }

    @Test
    fun transactionShapedStructHelpersMirrorUpstreamUtilitySignatures() {
        val doc = deletedTextDoc()
        val visited = mutableListOf<Triple<Id, Long, Long>>()
        val visitedWithoutSplits = mutableListOf<Triple<Id, Long, Long>>()
        lateinit var collected: IdSet
        lateinit var collectedViaDeleteSet: IdSet

        doc.transact({ transaction ->
            assertEquals(Id(1, 4), nextID(transaction))
            assertEquals(Id(1, 1), getItemCleanStart(transaction, Id(1, 1)).id)
            assertEquals(Id(1, 1), getItemCleanEnd(transaction, Id(1, 1)).id)
            assertEquals(Id(1, 1), getItemCleanEnd(transaction, doc.store, Id(1, 1)).id)

            iterateStructsByIdSet(transaction, idSet(1, 1, 2)) { struct, offset, length ->
                visited.add(Triple(struct.id, offset, length))
            }
            iterateStructsByIdSetWithoutSplits(transaction, idSet(1, 1, 2)) { struct, offset, length ->
                visitedWithoutSplits.add(Triple(struct.id, offset, length))
            }

            collected = tryGc(transaction, idSet(1, 1, 1))
            collectedViaDeleteSet = tryGcDeleteSet(transaction, doc.deleteSet())
        })

        assertEquals(listOf(Triple(Id(1, 1), 0L, 1L), Triple(Id(1, 2), 0L, 1L)), visited)
        assertEquals(listOf(Triple(Id(1, 1), 0L, 1L), Triple(Id(1, 2), 0L, 1L)), visitedWithoutSplits)
        assertTrue(collected.hasId(Id(1, 1)))
        assertTrue(collectedViaDeleteSet.hasId(Id(1, 2)))
    }

    @Test
    fun cleanItemHelpersSplitPackedDeletedItemsAtRequestedBoundaries() {
        val doc = YDoc(clientId = 7, gc = false)
        assertTrue(
            doc.store.add(
                StoreItem(
                    id = Id(1, 0),
                    origin = null,
                    rightOrigin = null,
                    parent = "body",
                    parentSub = null,
                    content = ItemContent.Deleted(RootKind.Text, length = 5),
                    deleted = true,
                ),
            ),
        )

        doc.transact(block = { transaction ->
            val right = getItemCleanStart(transaction, Id(1, 2))
            val left = getItemCleanEnd(transaction, doc.store, Id(1, 0))

            assertEquals(Id(1, 2), right.id)
            assertEquals(3L, right.length)
            assertEquals(Id(1, 0), left.id)
            assertEquals(1L, left.length)

            assertEquals(listOf(Id(1, 0), Id(1, 1), Id(1, 2)), doc.store.clients.getValue(1).map { it.id })
            assertEquals(listOf(1L, 1L, 3L), doc.store.clients.getValue(1).map { it.length })
        })
        assertEquals(listOf(Id(1, 0)), doc.store.clients.getValue(1).map { it.id })
        assertEquals(listOf(5L), doc.store.clients.getValue(1).map { it.length })
    }

    @Test
    fun upstreamTryGcDeleteSetStoreSignatureCollectsWithoutSyntheticTransaction() {
        val doc = deletedTextDoc()
        val lifecycle = mutableListOf<String>()
        doc.observeBeforeAllTransactions { lifecycle.add("beforeAll") }
        doc.observeBeforeTransactions { lifecycle.add("before") }
        doc.observeBeforeObserverCalls { lifecycle.add("observers") }
        doc.observeAfterTransactions { lifecycle.add("after") }
        doc.observeAfterTransactionCleanup { lifecycle.add("cleanup") }
        doc.observeAfterAllTransactions { lifecycle.add("afterAll") }

        tryGc(doc.deleteSet(), doc.store) { true }

        assertTrue(doc.store.clients.getValue(1).filter { it.deleted }.all { it.content is ContentDeleted })
        assertEquals(
            listOf(Id(1, 0) to 1L, Id(1, 1) to 2L, Id(1, 3) to 1L),
            doc.store.clients.getValue(1).map { item -> item.id to item.length },
        )
        assertEquals(emptyList(), lifecycle)
    }

    @Test
    fun upstreamTryGcAllowsEmptyDeleteSetWithOwnerlessStore() {
        tryGc(DeleteSet.empty(), StructStore()) { error("GC filter must not run") }
    }

    @Test
    fun deletedItemMergeDoesNotCrossAConcurrentLogicalSibling() {
        val doc = YDoc(clientId = 9, gc = false)
        fun deleted(id: Id, origin: Id?): StoreItem = StoreItem(
            id = id,
            origin = origin,
            rightOrigin = null,
            parent = "body",
            parentSub = null,
            content = ItemContent.Deleted(RootKind.Text),
            deleted = true,
        )
        assertTrue(doc.store.add(deleted(Id(2, 0), origin = null)))
        assertTrue(doc.store.add(deleted(Id(2, 1), origin = Id(2, 0))))
        assertTrue(doc.store.add(deleted(Id(1, 0), origin = Id(2, 0))))
        assertEquals(listOf(Id(2, 0), Id(1, 0), Id(2, 1)), doc.store.sequence("body").map { it.id })
        val deleteSet = DeleteSet.empty().also { deletes -> deletes.add(Id(2, 0), 2) }

        tryGc(deleteSet, doc.store) { true }

        assertEquals(listOf(Id(2, 0), Id(2, 1)), doc.store.clients.getValue(2).map { it.id })
    }

    private fun idSet(vararg triples: Long): IdSet {
        require(triples.size % 3 == 0)
        val idSet = createIdSet()
        triples.asList().chunked(3).forEach { (client, clock, len) -> idSet.add(client, clock, len) }
        return idSet
    }

    private fun deletedTextDoc(): YDoc {
        val doc = YDoc(clientId = 1, gc = false)
        val text = doc.getText("body")
        text.insert(0, "abcd")
        text.delete(1, 2)
        return doc
    }

    private fun structAt(doc: YDoc, clock: Long): ItemStruct =
        getItemCleanStart(doc, Id(1, clock))

    private fun assertContentString(doc: YDoc, clock: Long, value: String) {
        assertEquals(value, (structAt(doc, clock).content as ContentString).str)
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
}
