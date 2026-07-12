package dev.yks

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GarbageCollectionDeleteSetTest {
    @Test
    fun automaticGcRunsAfterTransactionObserversAndBeforeCleanupObservers() {
        val doc = YDoc(clientId = 1, gc = true)
        val text = doc.getText("body")
        text.insert(0, "x")
        val phases = mutableListOf<String>()

        doc.observeAfterTransactions { event ->
            if (!event.deleteSet.isEmpty) {
                phases.add("after")
                assertIs<ContentString>(doc.store.getItem(Id(1, 0)).content)
            }
        }
        doc.observeAfterTransactionCleanup { event ->
            if (!event.deleteSet.isEmpty) {
                phases.add("cleanup")
                assertIs<ContentDeleted>(doc.store.getItem(Id(1, 0)).content)
            }
        }

        text.delete(0, 1)

        assertEquals(listOf("after", "cleanup"), phases)
        assertIs<ContentDeleted>(doc.store.getItem(Id(1, 0)).content)
    }

    @Test
    fun automaticGcRespectsGcFilterAndKeep() {
        val filtered = YDoc(clientId = 1, gc = true, gcFilter = { false })
        filtered.getText("body").apply {
            insert(0, "f")
            delete(0, 1)
        }
        assertIs<ContentString>(filtered.store.getItem(Id(1, 0)).content)

        val kept = YDoc(clientId = 2, gc = true)
        val keptText = kept.getText("body")
        keptText.insert(0, "k")
        keepItem(kept, kept.store.getItem(Id(2, 0)))
        keptText.delete(0, 1)

        assertIs<ContentString>(kept.store.getItem(Id(2, 0)).content)
    }

    @Test
    fun gcDoesNotDestroyTheTransactionUpdateNeededByRelays() {
        val source = YDoc(clientId = 1, gc = true)
        val updates = mutableListOf<ByteArray>()
        source.observeUpdates { update, _ -> updates.add(update) }

        source.transact {
            source.getText("body").apply {
                insert(0, "x")
                delete(0, 1)
            }
        }

        val update = updates.single()
        val target = YDoc(clientId = 2, gc = true)
        target.applyUpdate(update)

        assertEquals("", target.getText("body").toString())
        assertEquals(source.stateVector(), target.stateVector())
        assertTrue(target.deleteSet().contains(Id(1, 0)))
        assertIs<ContentDeleted>(target.store.getItem(Id(1, 0)).content)
    }

    @Test
    fun idSetIterationVisitsLargeRangeStructsOnceInsteadOfEnumeratingClocks() {
        val length = (1L shl 32) + 1
        val update = BinaryEncoder().apply {
            writeVarUInt(1) // clients
            writeVarUInt(1) // structs
            writeVarUInt(1) // client
            writeVarUInt(0) // clock
            writeByte(structGCRefNumber)
            writeVarUInt(length)
            writeVarUInt(0) // delete-set clients
        }.toByteArray()
        val doc = YDoc(clientId = 2, gc = false)
        doc.applyUpdate(update)
        val selected = createIdSet().also { it.add(1, 0, length) }
        val visited = mutableListOf<Pair<Id, Long>>()

        iterateStructsByIdSet(doc, selected) { struct, _, coveredLength ->
            visited.add(struct.id to coveredLength)
        }

        assertEquals(listOf(Id(1, 0) to length), visited)
    }

    @Test
    fun deleteSetPublicHelpersNormalizeCompareMergeAndIterate() {
        val left = createDeleteSet().also {
            it.add(Id(1, 0), 2)
            it.add(Id(1, 4), 1)
        }
        val right = createDeleteSet().also {
            it.add(Id(1, 2), 2)
            it.add(Id(2, 1), 1)
        }
        val merged = mergeDeleteSets(listOf(left, right))
        val expected = createDeleteSet().also {
            it.add(Id(1, 0), 5)
            it.add(Id(2, 1), 1)
        }

        assertTrue(equalDeleteSets(expected, merged))
        assertTrue(isDeleted(merged, Id(1, 3)))
        assertTrue(isDeleted(merged, 2, 1))
        assertFalse(isDeleted(merged, Id(2, 2)))

        val doc = YDoc(clientId = 7, gc = false)
        val text = doc.getText("body")
        text.insert(0, "abc")
        text.delete(1, 2)
        val fromStore = createDeleteSetFromStructStore(doc.store)
        val visited = mutableListOf<Id>()

        doc.transact(block = { transaction: YTransaction ->
            iterateDeletedStructs(transaction, fromStore) { struct -> visited.add(struct.id) }
        })

        assertEquals(listOf(Id(7, 1), Id(7, 2)), visited)
        assertTrue(equalDeleteSets(doc.deleteSet(), fromStore))
    }
}
