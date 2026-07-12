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
        val doc = YDoc(clientId = 1)
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
        val doc = YDoc(clientId = 1)
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
