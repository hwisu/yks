package dev.yks

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class YDocTest {
    @Test
    fun defaultGettersAndGenericGetMatchUpstreamRootCreation() {
        val genericDoc = YDoc(clientId = 1)
        val undecided = genericDoc["undecided"]

        assertIs<YUnopenedRoot>(undecided)
        assertSame(undecided, genericDoc.share["undecided"])
        assertEquals(emptyMap(), genericDoc.toJSON())
        assertSame(genericDoc.getText("undecided"), genericDoc.share["undecided"])

        assertEquals("", YDoc(clientId = 2).getArray().name)
        assertEquals("", YDoc(clientId = 3).getMap().name)
        assertEquals("", YDoc(clientId = 4).getText().name)
        assertEquals("", YDoc(clientId = 5).getXmlFragment().name)
        val xmlElement = YDoc(clientId = 6).getXmlElement()
        assertEquals("", xmlElement.name)
        assertEquals("UNDEFINED", xmlElement.nodeName)
    }

    @Test
    fun rootEmptinessMatchesYjsStructuralStartAndMapSemantics() {
        val document = YDoc(clientId = 1)
        assertTrue(document.isRootEmpty("missing"))
        assertTrue(document.isRootEmpty("body"))

        val body = document.getText("body")
        body.insert(0, "x")
        assertFalse(document.isRootEmpty("body"))
        body.delete(0, 1)
        assertFalse(document.isRootEmpty("body"))

        val metadata = document.getMap("metadata")
        assertTrue(document.isRootEmpty("metadata"))
        metadata.set("ready", true)
        assertFalse(document.isRootEmpty("metadata"))
        metadata.delete("ready")
        assertFalse(document.isRootEmpty("metadata"))

        val remote = YDoc(clientId = 2)
        remote.getText("remote").insert(0, "content")
        val unopened = createDocFromUpdate(remote.encodeStateAsUpdate())
        assertIs<YUnopenedRoot>(unopened.getOrNull("remote"))
        assertFalse(unopened.isRootEmpty("remote"))
    }

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
    fun reverseOrderedDependencyChainSurvivesMultiplePendingCompactionPasses() {
        val source = YDoc(clientId = 1)
        val target = YDoc(clientId = 2)
        val array = source.getArray("items")
        val updates = mutableListOf<ByteArray>()
        source.observeUpdates { update, _ -> updates.add(update.copyOf()) }
        array.push("a")
        array.push("b")
        array.push("c")

        updates.asReversed().forEach(target::applyUpdate)

        assertEquals(listOf("a", "b", "c"), target.getArray("items").toList())
        assertNull(target.store.pendingStructs)
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
    fun emptyTransactionEmitsFullLifecycleWithoutAnUpdateLikeUpstream() {
        val doc = YDoc(clientId = 1)
        val order = mutableListOf<String>()
        var updateCount = 0

        doc.observeBeforeAllTransactions { order.add("beforeAllTransactions") }
        doc.observeBeforeTransactions { order.add("beforeTransaction") }
        doc.observeBeforeObserverCalls { order.add("beforeObserverCalls") }
        doc.observeAfterTransactions { order.add("afterTransaction") }
        doc.observeAfterTransactionCleanup { order.add("afterTransactionCleanup") }
        doc.observeAfterAllTransactions { order.add("afterAllTransactions") }
        doc.observeUpdates { _, _ -> updateCount++ }

        doc.transact { }

        assertEquals(
            listOf(
                "beforeAllTransactions",
                "beforeTransaction",
                "beforeObserverCalls",
                "afterTransaction",
                "afterTransactionCleanup",
                "afterAllTransactions",
            ),
            order,
        )
        assertEquals(0, updateCount)
    }

    @Test
    fun transactionEventsExposeDocMetaStructViewsAndIdSetAliases() {
        val doc = YDoc(clientId = 1)
        val text = doc.getText("body")
        val events = mutableListOf<TransactionEvent>()

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
        assertEquals(listOf(Id(1, 0)), event.addedStructs.map { it.id })
        assertEquals(listOf(2L), event.addedStructs.map { it.length })
        assertEquals(listOf(false), event.addedStructs.map { it.deleted })
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
    fun cachedFullUpdatesAreDefensiveAndInvalidateAfterMutation() {
        val doc = YDoc(clientId = 1)
        val text = doc.getText("body")
        text.insert(0, "a")

        val firstV1 = doc.encodeStateAsUpdate()
        val expectedFirstV1 = firstV1.copyOf()
        firstV1.fill(0)
        assertContentEquals(expectedFirstV1, doc.encodeStateAsUpdate())

        val firstV2 = doc.encodeStateAsUpdateV2()
        val expectedFirstV2 = firstV2.copyOf()
        firstV2.fill(0)
        assertContentEquals(expectedFirstV2, doc.encodeStateAsUpdateV2())

        text.insert(1, "b")
        val updatedV1 = doc.encodeStateAsUpdate()
        val updatedV2 = doc.encodeStateAsUpdateV2()
        assertFalse(expectedFirstV1.contentEquals(updatedV1))
        assertFalse(expectedFirstV2.contentEquals(updatedV2))
        assertEquals("ab", createDocFromUpdate(updatedV1).getText("body").toString())
        assertEquals("ab", createDocFromUpdateV2(updatedV2).getText("body").toString())
    }

    @Test
    fun storedYValuesDetachMutableInputsBeforeCachingFullUpdates() {
        val list = mutableListOf<YValue>(YValue.StringValue("original"))
        val map = linkedMapOf<String, YValue>("items" to YValue.ListValue(list))
        val binary = byteArrayOf(1, 2)
        val doc = YDoc(clientId = 1)
        val values = doc.getMap("values")
        values.set("nested", YValue.MapValue(map))
        values.set("binary", YValue.BinaryValue(binary))

        val cachedV1 = doc.encodeStateAsUpdate()
        val cachedV2 = doc.encodeStateAsUpdateV2()
        list[0] = YValue.StringValue("mutated")
        map["extra"] = YValue.Bool(true)
        binary[0] = 9

        assertEquals(mapOf("items" to listOf("original")), values.get("nested"))
        assertContentEquals(byteArrayOf(1, 2), values.get("binary") as ByteArray)
        assertContentEquals(cachedV1, doc.encodeStateAsUpdate())
        assertContentEquals(cachedV2, doc.encodeStateAsUpdateV2())
    }

    @Test
    fun cachedFullV2UpdateIsNeverUsedForAnExplicitStateVector() {
        val doc = YDoc(clientId = 1)
        doc.getText("body").insert(0, "content")
        val full = doc.encodeStateAsUpdateV2()
        val currentState = doc.encodeStateVector()

        val incremental = doc.encodeStateAsUpdateV2(currentState)

        assertFalse(full.contentEquals(incremental))
        assertTrue(decodeUpdateV2(incremental).structs.isEmpty())
        assertTrue(decodeUpdateV2(incremental).deleteSet.isEmpty)
    }

    @Test
    fun cachedFullV2UpdateIsBypassedWhileStructsArePending() {
        val source = YDoc(clientId = 1)
        val text = source.getText("body")
        text.insert(0, "a")
        val first = source.encodeStateAsUpdateV2()
        val stateAfterFirst = source.encodeStateVector()
        text.insert(1, "b")
        val second = source.encodeStateAsUpdateV2(stateAfterFirst)
        val target = YDoc(clientId = 2)
        val cachedEmpty = target.encodeStateAsUpdateV2()

        target.applyUpdateV2(second)

        assertTrue(target.store.pendingStructs != null)
        assertFalse(cachedEmpty.contentEquals(target.encodeStateAsUpdateV2()))
        target.applyUpdateV2(first)
        assertEquals("ab", target.getText("body").toString())
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
