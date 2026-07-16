package dev.yks

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StructStoreTest {
    @Test
    fun longTextUsesOnePackedStructAndNearPayloadSizedStandardUpdate() {
        val doc = YDoc(clientId = 1)
        val text = doc.getText("body")
        val payload = "x".repeat(20_000)

        text.insert(0, payload)

        val structs = doc.store.clients.getValue(1)
        val update = doc.encodeStateAsUpdate()
        assertEquals(1, structs.size)
        assertEquals(20_000L, structs.single().length)
        assertEquals(payload, (structs.single().content as ContentString).str)
        assertTrue(update.size < payload.encodeToByteArray().size + 128)
        assertEquals(payload, createDocFromUpdate(update).getText("body").toString())
    }

    @Test
    fun editingPackedTextSplitsOnlyAtEditBoundaries() {
        val doc = YDoc(clientId = 1)
        val text = doc.getText("body")
        text.insert(0, "a".repeat(10_000) + "b".repeat(10_000))

        text.insert(10_000, "Z")
        text.delete(9_999, 3)

        assertEquals("a".repeat(9_999) + "b".repeat(9_999), text.toString())
        assertTrue(doc.store.clients.getValue(1).size < 10)
        assertEquals(text.toString(), createDocFromUpdate(doc.encodeStateAsUpdate()).getText("body").toString())
    }

    @Test
    fun materializedSequenceIsMaintainedIncrementallyAcrossEdits() {
        val doc = YDoc(clientId = 1)
        val text = doc.getText("body")
        text.insert(0, "seed")
        text.toString()
        val buildsAfterMaterialization = doc.store.sequenceBuildCount

        repeat(200) {
            text.insert(text.length, "x")
            text.toString()
        }

        assertEquals("seed" + "x".repeat(200), text.toString())
        assertEquals(buildsAfterMaterialization, doc.store.sequenceBuildCount)
    }

    @Test
    fun sequenceMatchesYjsGlobalConflictOrdering() {
        val updates = listOf(
            "AQEKAAgBAWEBdwJvYQA=",
            "AQEeAAgBAWEBdwJvYwA=",
            "AAIeAQABCgEAAQ==",
            "AQEKAYgeAAF3BGJhc2UCHgEAAQoBAAE=",
            "AQEeAUgKAAF3AWMCHgEAAQoBAAE=",
        ).map(Base64.getDecoder()::decode)
        val doc = YDoc(clientId = 99, gc = false)
        val array = doc.getArray("a")

        updates.forEach(doc::applyUpdate)

        assertEquals(listOf("c", "base"), array.toList())
        assertNull(doc.store.pendingStructs)
    }

    @Test
    fun getStateVectorExposesRawDocumentStateVector() {
        val doc = YDoc(clientId = 1)
        doc.getText("body").insert(0, "ab")
        doc.clientId = 2
        doc.getArray("items").push("x")

        val stateVector = getStateVector(doc)

        assertEquals(mapOf(1L to 2L, 2L to 1L), stateVector)
        assertEquals(stateVector, decodeStateVector(doc.encodeStateVector()))
        assertEquals(stateVector, decodeStateVector(encodeStateVector(doc)))
    }

    @Test
    fun getStateVectorIncludesPendingSkipRangesLikeUpstreamStructStore() {
        val store = StructStore()

        store.skips.add(7, 5, 2)
        store.skips.add(4, 0, 3)

        assertEquals(mapOf(4L to 0L, 7L to 5L), getStateVector(store))
    }

    @Test
    fun documentStoreExposesClientStructViewsAndDeleteSet() {
        val doc = YDoc(clientId = 1, gc = false)
        val text = doc.getText("body")
        text.insert(0, "ab")
        text.delete(1)

        val clientStructs = doc.store.clients.getValue(1)

        assertEquals(listOf(Id(1, 0), Id(1, 1)), clientStructs.map { it.id })
        assertEquals(listOf(false, true), clientStructs.map { it.deleted })
        assertEquals("a", (clientStructs[0].content as ContentString).str)
        assertEquals("b", (clientStructs[1].content as ContentString).str)
        assertEquals(2L, doc.store.getClock(1))
        assertEquals(mapOf(1L to 2L), getStateVector(doc.store))
        assertTrue(doc.store.ds.hasId(Id(1, 1)))
        assertFalse(doc.store.ds.hasId(Id(1, 0)))
    }

    @Test
    fun documentStoreSupportsYjsStyleStructLookupMethods() {
        val doc = YDoc(clientId = 1, gc = false)
        val text = doc.getText("body")
        text.insert(0, "abc")
        text.delete(1)

        val genericStruct = doc.store.get(Id(1, 1))
        val item = doc.store.getItem(Id(1, 1))
        val indexed = doc.store.getIndex(Id(1, 1))

        assertEquals(item, genericStruct)
        assertEquals(Id(1, 1), item.id)
        assertEquals(true, item.deleted)
        assertEquals("b", (item.content as ContentString).str)
        assertEquals(1, indexed.index)
        assertEquals(listOf(Id(1, 0), Id(1, 1), Id(1, 2)), indexed.structs.map { it.id })
        assertEquals(item, indexed.structs[indexed.index])
    }

    @Test
    fun idSetHelpersAcceptStructStoreLikeUpstream() {
        val doc = YDoc(clientId = 1)
        val text = doc.getText("body")
        text.insert(0, "abc")
        text.delete(1)

        val allInserts = createInsertSetFromStructStore(doc.store)
        val visibleInserts = createInsertSetFromStructStore(doc.store, filterDeleted = true)
        val deletes = createDeleteSetFromStructStore(doc.store)

        assertEquals(createInsertSetFromStructStore(doc).ranges(), allInserts.ranges())
        assertEquals(createInsertSetFromStructStore(doc, filterDeleted = true).ranges(), visibleInserts.ranges())
        assertEquals(createDeleteSetFromStructStore(doc).ranges(), deletes.ranges())
        assertTrue(allInserts.has(1, 1))
        assertFalse(visibleInserts.has(1, 1))
        assertTrue(deletes.hasId(Id(1, 1)))
    }

    @Test
    fun documentStoreExposesPendingStructUpdates() {
        val source = YDoc(clientId = 1)
        val target = YDoc(clientId = 2)
        val text = source.getText("body")
        text.insert(0, "a")
        val firstUpdate = source.encodeStateAsUpdate()
        text.insert(1, "b")
        val secondUpdate = source.encodeStateAsUpdate(encodeStateVector(mapOf(1L to 1L)))

        target.applyUpdate(secondUpdate)

        val pending = assertNotNull(target.store.pendingStructs)
        assertEquals(mapOf(1L to 0L), pending.missing)
        assertEquals(listOf(Id(1, 1)), decodeUpdate(pending.update).structs.map { it.id })
        assertNull(target.store.pendingDs)
        assertTrue(target.store.skips.isEmpty())

        target.applyUpdate(firstUpdate)

        assertEquals("ab", target.getText("body").toString())
        assertNull(target.store.pendingStructs)
    }

    @Test
    fun documentStoreExposesPendingDeleteSetUpdates() {
        val source = YDoc(clientId = 1)
        val target = YDoc(clientId = 2)
        val text = source.getText("body")
        text.insert(0, "a")
        val insertUpdate = source.encodeStateAsUpdate()
        val stateAfterInsert = source.encodeStateVector()
        text.delete(0)
        val deleteUpdate = source.encodeStateAsUpdate(stateAfterInsert)

        target.applyUpdate(deleteUpdate)

        val pendingDs = assertNotNull(target.store.pendingDs)
        assertTrue(decodeUpdate(pendingDs).deleteSet.contains(Id(1, 0)))
        assertFalse(target.store.ds.hasId(Id(1, 0)))

        target.applyUpdate(insertUpdate)

        assertEquals("", target.getText("body").toString())
        assertTrue(target.store.ds.hasId(Id(1, 0)))
        assertNull(target.store.pendingDs)
    }

    @Test
    fun pendingDeleteInsidePackedTextAppliesAfterTheTextArrives() {
        val source = YDoc(clientId = 1, gc = false)
        val text = source.getText("body")
        text.insert(0, "A😀BC")
        val base = source.encodeStateAsUpdate()
        val insertedState = source.encodeStateVector()
        text.delete(3, 1)
        val deletion = source.encodeStateAsUpdate(insertedState)
        val target = YDoc(clientId = 2, gc = false)

        target.applyUpdate(deletion)
        assertNotNull(target.store.pendingDs)
        target.applyUpdate(base)

        assertEquals("A😀C", target.getText("body").toString())
        assertNull(target.store.pendingDs)
    }

    @Test
    fun documentStorePendingFieldsCanBeCleared() {
        val source = YDoc(clientId = 1)
        val targetWithPendingStruct = YDoc(clientId = 2)
        val text = source.getText("body")
        text.insert(0, "a")
        val firstUpdate = source.encodeStateAsUpdate()
        text.insert(1, "b")
        val secondUpdate = source.encodeStateAsUpdate(encodeStateVector(mapOf(1L to 1L)))

        targetWithPendingStruct.applyUpdate(secondUpdate)
        assertNotNull(targetWithPendingStruct.store.pendingStructs)
        targetWithPendingStruct.store.pendingStructs = null
        targetWithPendingStruct.applyUpdate(firstUpdate)

        assertNull(targetWithPendingStruct.store.pendingStructs)
        assertEquals("a", targetWithPendingStruct.getText("body").toString())

        val targetWithPendingDelete = YDoc(clientId = 3)
        val stateAfterSecondInsert = source.encodeStateVector()
        text.delete(0)
        val deleteUpdate = source.encodeStateAsUpdate(stateAfterSecondInsert)

        targetWithPendingDelete.applyUpdate(deleteUpdate)
        assertNotNull(targetWithPendingDelete.store.pendingDs)
        targetWithPendingDelete.store.pendingDs = null
        targetWithPendingDelete.applyUpdate(firstUpdate)
        targetWithPendingDelete.applyUpdate(secondUpdate)

        assertNull(targetWithPendingDelete.store.pendingDs)
        assertEquals("ab", targetWithPendingDelete.getText("body").toString())
    }

    @Test
    fun integrityCheckPassesForContiguousDocumentStructStore() {
        val doc = YDoc(clientId = 1)
        val text = doc.getText("body")
        text.insert(0, "abc")
        text.delete(1)

        integrityCheck(doc)
        integrityCheck(doc.store)
    }

    @Test
    fun internalStructStoreIntegrityCheckDetectsClockGaps() {
        val store = StructStore()
        store.add(storeItem(1, 0, "a"))
        store.add(storeItem(1, 2, "c"))

        val thrown = assertFailsWith<IllegalStateException> {
            store.integrityCheck()
        }

        assertEquals("StructStore failed integrity check", thrown.message)
    }

    @Test
    fun deleteSetTraversalScalesWithSortedClientRanges() {
        val store = StructStore()
        repeat(2_000) { clock -> assertTrue(store.add(storeItem(1, clock.toLong(), "a"))) }
        repeat(100) { clock -> assertTrue(store.add(storeItem(2, clock.toLong(), "b"))) }
        assertTrue(
            store.add(
                StoreItem(
                    id = Id(3, 0),
                    origin = null,
                    rightOrigin = null,
                    parent = "body",
                    parentSub = null,
                    content = ItemContent.Deleted(RootKind.Text, length = 1_000),
                    deleted = true,
                ),
            ),
        )

        val sparse = DeleteSet.empty()
        repeat(1_000) { index -> sparse.add(Id(1, index * 2L)) }
        sparse.add(Id(2, 50), 10)
        val selected = store.itemsStartingIn(sparse)

        assertEquals(1_010, selected.size)
        assertEquals(Id(1, 0), selected.first().id)
        assertEquals(Id(1, 1_998), selected[999].id)
        assertEquals(Id(2, 50), selected[1_000].id)
        assertEquals(Id(2, 59), selected.last().id)
        assertTrue(store.markDeleted(sparse))
        assertTrue(selected.all { item -> item.deleted })
        assertFalse(store.markDeleted(sparse))

        val packedMiddle = DeleteSet.empty().also { it.add(Id(3, 500)) }
        assertTrue(store.itemsStartingIn(packedMiddle).isEmpty())
        assertEquals(listOf(Id(3, 0)), store.itemsOverlapping(packedMiddle).map { item -> item.id })
    }

    private fun storeItem(client: Long, clock: Long, text: String): StoreItem =
        StoreItem(
            id = Id(client, clock),
            origin = null,
            rightOrigin = null,
            parent = "body",
            parentSub = null,
            content = ItemContent.Text(text, emptyMap()),
        )
}
