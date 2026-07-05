package dev.yks

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class YDocTest {
    @Test
    fun arrayChangesConvergeThroughUpdates() {
        val left = YDoc(clientId = 1)
        val right = YDoc(clientId = 2)
        left.getArray("items").push(listOf("a", "b"))

        right.applyUpdate(left.encodeStateAsUpdate())
        right.getArray("items").insert(1, listOf("x"))

        left.applyUpdate(right.encodeStateAsUpdate(left.encodeStateVector()))

        assertEquals(listOf("a", "x", "b"), left.getArray("items").toList())
        assertEquals(left.getArray("items").toList(), right.getArray("items").toList())
    }

    @Test
    fun arrayPreservesNullValues() {
        val doc = YDoc(clientId = 1)
        doc.getArray("items").push(listOf("a", null, "b"))

        assertEquals(listOf("a", null, "b"), doc.getArray("items").toList())
    }

    @Test
    fun arrayInsertAfterDeletedContentKeepsVisibleOrder() {
        val doc = YDoc(clientId = 1)
        val array = doc.getArray("items")
        array.push(listOf("a", null, "c"))
        array.delete(1)
        array.insert(1, listOf("b"))

        assertEquals(listOf("a", "b", "c"), array.toList())
    }

    @Test
    fun textSupportsInsertBeforeExistingContent() {
        val doc = YDoc(clientId = 1)
        val text = doc.getText("body")
        text.insert(0, "bc")
        text.insert(0, "a")
        text.insert(3, "d")

        assertEquals("abcd", text.toString())
    }

    @Test
    fun deleteSetPropagatesForKnownItems() {
        val left = YDoc(clientId = 1)
        val right = YDoc(clientId = 2)
        left.getArray("items").push(listOf("a", "b", "c"))
        right.applyUpdate(left.encodeStateAsUpdate())

        left.getArray("items").delete(1)
        right.applyUpdate(left.encodeStateAsUpdate(right.encodeStateVector()))

        assertEquals(listOf("a", "c"), left.getArray("items").toList())
        assertEquals(left.getArray("items").toList(), right.getArray("items").toList())
    }

    @Test
    fun mapSetDeleteAndUpdateConverge() {
        val left = YDoc(clientId = 1)
        val right = YDoc(clientId = 2)
        left.getMap("meta").set("title", "old")
        right.applyUpdate(left.encodeStateAsUpdate())

        left.getMap("meta").set("title", "new")
        left.getMap("meta").set("count", 2)
        right.applyUpdate(left.encodeStateAsUpdate(right.encodeStateVector()))

        assertEquals(mapOf("count" to 2L, "title" to "new"), right.getMap("meta").toMap())
        right.getMap("meta").delete("title")
        left.applyUpdate(right.encodeStateAsUpdate(left.encodeStateVector()))
        assertEquals(mapOf("count" to 2L), left.getMap("meta").toMap())
    }

    @Test
    fun outOfOrderUpdatesAreIntegratedWhenDependenciesArrive() {
        val source = YDoc(clientId = 1)
        val target = YDoc(clientId = 2)
        val array = source.getArray("items")
        array.push(listOf("a"))
        val first = source.encodeStateAsUpdate()
        array.push(listOf("b"))
        val second = source.encodeStateAsUpdate(source.encodeStateVector().let { encodeStateVector(mapOf(1L to 1L)) })

        target.applyUpdate(second)
        assertEquals(emptyList(), target.getArray("items").toList())
        target.applyUpdate(first)
        assertEquals(listOf("a", "b"), target.getArray("items").toList())
    }

    @Test
    fun observersReceiveTransactionUpdateAndOrigin() {
        val doc = YDoc(clientId = 1)
        val seenOrigins = mutableListOf<Any?>()
        val seenUpdates = mutableListOf<ByteArray>()
        doc.observeUpdates { update, origin ->
            seenUpdates.add(update)
            seenOrigins.add(origin)
        }

        doc.transact(origin = "local") {
            doc.getText("body").insert(0, "hi")
        }

        assertEquals(listOf<Any?>("local"), seenOrigins)
        assertEquals(1, seenUpdates.size)
        val mirror = YDoc(clientId = 2)
        mirror.applyUpdate(seenUpdates.single())
        assertEquals("hi", mirror.getText("body").toString())
    }

    @Test
    fun transactReturnsBlockResultAndAcceptsExplicitLocalFlag() {
        val doc = YDoc(clientId = 1)
        val locals = mutableListOf<Boolean>()
        doc.observeAfterTransactions { event -> locals.add(event.local) }

        val result = doc.transact(origin = "manual", local = false) {
            doc.getText("body").insert(0, "hi")
            "done:${doc.getText("body").length}"
        }

        assertEquals("done:2", result)
        assertEquals(listOf(false), locals)
        assertEquals("hi", doc.getText("body").toString())
    }

    @Test
    fun transactCallbackReceivesActiveTransactionView() {
        val doc = YDoc(clientId = 1)
        val text = doc.getText("body")
        val events = mutableListOf<YTransactionEvent>()
        lateinit var active: YTransaction
        doc.observeAfterTransactions { event -> events.add(event) }

        val result = doc.transact(
            { transaction ->
                active = transaction
                assertSame(doc, transaction.doc)
                assertEquals("active", transaction.origin)
                assertFalse(transaction.local)
                assertEquals(emptyMap(), transaction.beforeState)
                assertTrue(transaction.insertSet.isEmpty())

                transaction.meta["source"] = "callback"
                text.insert(0, "x")

                assertEquals(1, transaction.addedItemCount)
                assertTrue(transaction.adds(1, 0))
                assertEquals(setOf("body"), transaction.changedParents)
                assertEquals(setOf(text), transaction.changedTypes)

                val nested = doc.transact({ nestedTransaction ->
                    assertSame(transaction.meta, nestedTransaction.meta)
                    nestedTransaction.meta["nested"] = true
                    text.insert(1, "y")
                    nestedTransaction.addedItemCount
                })

                text.delete(1)

                assertEquals(2, nested)
                assertEquals(2, transaction.addedItemCount)
                assertEquals(1, transaction.deletedItemCount)
                assertTrue(transaction.deletes(1, 1))
                "done:${transaction.meta["nested"]}"
            },
            origin = "active",
            local = false,
        )

        assertEquals("done:true", result)
        assertEquals("x", text.toString())
        assertEquals(mapOf(1L to 2L), active.afterState)
        assertTrue(active.update.isNotEmpty())
        assertEquals(1, events.size)
        assertEquals("callback", events.single().meta["source"])
        assertEquals(true, events.single().meta["nested"])
        assertEquals(listOf(false), events.map { it.local })
    }

    @Test
    fun transactionLifecycleObserversReceiveOrderedStateMetadata() {
        val doc = YDoc(clientId = 1)
        val array = doc.getArray("items")
        val order = mutableListOf<String>()
        val lifecycleEvents = mutableListOf<YTransactionEvent>()

        doc.observeBeforeAllTransactions {
            order.add("beforeAllTransactions")
            assertEquals(emptyList(), array.toArray())
        }
        doc.observeBeforeTransactions { event ->
            order.add("beforeTransaction")
            lifecycleEvents.add(event)
            assertEquals("origin", event.origin)
            assertTrue(event.local)
            assertEquals(emptyMap(), event.beforeState)
            assertTrue(event.insertSet.isEmpty())
            assertTrue(event.deleteSet.isEmpty)
            assertEquals(emptyList(), array.toArray())
        }
        doc.observeBeforeObserverCalls { event ->
            order.add("beforeObserverCalls")
            lifecycleEvents.add(event)
            assertEquals(emptyMap(), event.beforeState)
            assertEquals(mapOf(1L to 2L), event.afterState)
            assertEquals(2, event.addedItemCount)
            assertEquals(0, event.deletedItemCount)
            assertTrue(event.adds(1, 0))
            assertTrue(event.adds(Id(1, 1)))
            assertFalse(event.deletes(1, 0))
            assertEquals(setOf("items"), event.changedParents)
            assertEquals(listOf(1L, 2L), array.toArray())
        }
        array.observe {
            order.add("typeObserver")
        }
        doc.observeAfterTransactions { event ->
            order.add("afterTransaction")
            lifecycleEvents.add(event)
            assertEquals(mapOf(1L to 2L), event.afterState)
            assertTrue(event.update.isNotEmpty())
        }
        doc.observeAfterTransactionCleanup { event ->
            order.add("afterTransactionCleanup")
            lifecycleEvents.add(event)
            assertEquals(mapOf(1L to 2L), event.afterState)
            assertTrue(event.update.isNotEmpty())
        }
        doc.observeUpdates { _, _ ->
            order.add("update")
        }

        doc.transact(origin = "origin") {
            array.push(listOf(1, 2))
        }

        assertEquals(
            listOf(
                "beforeAllTransactions",
                "beforeTransaction",
                "beforeObserverCalls",
                "typeObserver",
                "afterTransaction",
                "afterTransactionCleanup",
                "update",
            ),
            order,
        )
        assertEquals(4, lifecycleEvents.size)
    }

    @Test
    fun transactionEventsExposeDocMetaStructViewsAndIdSetAliases() {
        val doc = YDoc(clientId = 1)
        val text = doc.getText("body")
        val events = mutableListOf<Transaction>()

        doc.observeBeforeTransactions { event ->
            assertTrue(event.doc === doc)
            event.meta["phase"] = "before"
        }
        doc.observeBeforeObserverCalls { event ->
            events.add(event)
            assertEquals("before", event.meta["phase"])
            event.meta["observer"] = "seen"
        }
        doc.observeAfterTransactions { event ->
            events.add(event)
            assertEquals("seen", event.meta["observer"])
        }

        doc.transact(origin = "edit") {
            text.insert(0, "ab")
            text.delete(1)
        }

        val event = events.first()
        assertEquals(listOf(Id(1, 0), Id(1, 1)), event.addedStructs.map { it.id })
        assertEquals(listOf(false, true), event.addedStructs.map { it.deleted })
        assertEquals(listOf(Id(1, 1)), event.deletedStructs.map { it.id })
        assertTrue(event.deleteIdSet.has(1, 1))
        assertTrue(event.cleanUps.isEmpty())
        assertEquals(setOf(text), event.changedTypes)
        assertTrue(event.adds(event.addedStructs.first()))
        assertTrue(event.deletes(event.deletedStructs.single()))
        assertEquals("a", text.toString())
    }

    @Test
    fun transactionLifecycleObserversReportRemoteTransactionsAndCanUnsubscribe() {
        val source = YDoc(clientId = 1)
        val target = YDoc(clientId = 2)
        source.getText("body").insert(0, "hi")
        val seen = mutableListOf<Boolean>()
        val afterStates = mutableListOf<StateVector>()

        val beforeSubscription = target.observeBeforeTransactions { event ->
            seen.add(event.local)
            assertEquals(emptyMap(), event.beforeState)
        }
        target.observeAfterTransactions { event ->
            seen.add(event.local)
            afterStates.add(event.afterState)
        }

        target.applyUpdate(source.encodeStateAsUpdate(), origin = "remote")
        beforeSubscription.close()
        source.getText("body").insert(2, "!")
        target.applyUpdate(source.encodeStateAsUpdate(target.encodeStateVector()), origin = "remote")

        assertEquals(listOf(false, false, false), seen)
        assertEquals(listOf(mapOf(1L to 2L), mapOf(1L to 3L)), afterStates)
        assertEquals("hi!", target.getText("body").toString())
    }

    @Test
    fun afterAllTransactionsBatchesObserverTriggeredTransactions() {
        val doc = YDoc(clientId = 1)
        val array = doc.getArray("items")
        val updateMarkers = mutableListOf<String>()
        val afterAllBatches = mutableListOf<List<YTransactionEvent>>()
        var beforeAllCalls = 0

        doc.observeBeforeAllTransactions { beforeAllCalls++ }
        doc.observeUpdates { _, _ -> updateMarkers.add("update") }
        doc.observeAfterAllTransactions { events ->
            updateMarkers.add("afterAll")
            afterAllBatches.add(events)
        }
        array.observe {
            if (array.length == 1) {
                array.insert(1, listOf("queued"))
            }
        }

        array.insert(0, listOf("first"))

        assertEquals(listOf("first", "queued"), array.toArray())
        assertEquals(listOf("update", "update", "afterAll"), updateMarkers)
        assertEquals(1, beforeAllCalls)
        assertEquals(1, afterAllBatches.size)
        assertEquals(listOf(1, 1), afterAllBatches.single().map { it.addedItemCount })
        assertEquals(listOf(mapOf(1L to 1L), mapOf(1L to 2L)), afterAllBatches.single().map { it.afterState })
    }

    @Test
    fun afterAllTransactionsMutationsStartSeparateBatch() {
        val doc = YDoc(clientId = 1)
        val array = doc.getArray("items")
        val batchSizes = mutableListOf<Int>()
        var beforeAllCalls = 0

        doc.observeBeforeAllTransactions { beforeAllCalls++ }
        doc.observeAfterAllTransactions { events ->
            batchSizes.add(events.size)
            if (batchSizes.size == 1) {
                array.push(listOf("second"))
            }
        }

        array.push(listOf("first"))

        assertEquals(listOf("first", "second"), array.toArray())
        assertEquals(2, beforeAllCalls)
        assertEquals(listOf(1, 1), batchSizes)
    }

    @Test
    fun stateVectorEncodingRoundTrips() {
        val encoded = encodeStateVector(mapOf(3L to 9L, 1L to 2L))
        assertEquals(mapOf(3L to 9L, 1L to 2L), decodeStateVector(encoded))
        assertTrue(encoded.isNotEmpty())
        assertFalse(decodeStateVector(ByteArray(0)).isNotEmpty())
    }

    @Test
    fun binaryValuesAreDefensiveCopies() {
        val value = YValue.BinaryValue(byteArrayOf(1, 2))
        val copy = value.toAny() as ByteArray
        copy[0] = 9
        assertContentEquals(byteArrayOf(1, 2), value.bytes())
    }

    @Test
    fun clientIdChangesWhenApplyingUnknownStructsFromSameClient() {
        val source = YDoc(clientId = 0)
        val target = YDoc(clientId = 0)
        source.getArray("items").push(listOf(1, 2))

        target.applyUpdate(source.encodeStateAsUpdate())

        assertNotEquals(source.clientId, target.clientId)
        assertEquals(listOf(1L, 2L), target.getArray("items").toArray())

        target.getArray("items").push(listOf(3))
        source.applyUpdate(target.encodeStateAsUpdate(source.encodeStateVector()))

        assertEquals(listOf(1L, 2L, 3L), source.getArray("items").toArray())
    }

    @Test
    fun clientIdCanBeChangedForFutureLocalStructs() {
        val doc = YDoc(clientId = 1)
        doc.clientId = 7
        doc.getArray("items").push(listOf("x"))

        assertEquals(mapOf(7L to 1L), doc.stateVector())
        assertFailsWith<IllegalArgumentException> {
            doc.clientId = -1
        }
    }
}
