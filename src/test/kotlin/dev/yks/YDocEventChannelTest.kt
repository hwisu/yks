package dev.yks

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class YDocEventChannelTest {
    @Test
    fun syncEventUpdatesStateAndLoadsDocument() {
        val doc = YDoc(clientId = 1, shouldLoad = false)
        val seen = mutableListOf<String>()

        doc.on("load") { event -> seen.add(event.name) }
        doc.on("sync") { event -> seen.add("${event.name}:${event.synced}") }

        doc.sync()

        assertTrue(doc.isSynced)
        assertTrue(doc.isLoaded)
        assertEquals(listOf("load", "sync:true"), seen)

        doc.sync(false)

        assertTrue(!doc.isSynced)
        assertEquals(listOf("load", "sync:true", "sync:false"), seen)
    }

    @Test
    fun publicEmitAndOnceMirrorProviderDrivenLoadAndSyncEvents() {
        val doc = YDoc(clientId = 1)
        val seen = mutableListOf<String>()

        doc.once("load") { event -> seen.add(event.name) }
        doc.once("sync") { event -> seen.add("${event.name}:${event.synced}") }

        doc.emit("sync")

        assertTrue(doc.isLoaded)
        assertTrue(doc.whenLoaded.isDone)
        assertSame(doc, doc.whenLoaded.join())
        assertTrue(doc.isSynced)
        assertTrue(doc.whenSynced.isDone)
        assertSame(doc, doc.whenSynced.join())
        assertEquals(listOf("load", "sync:true"), seen)

        val syncedOnce = doc.whenSynced
        doc.emit("sync", YDocEvent(name = "sync", synced = false))
        assertFalse(doc.isSynced)
        assertFalse(doc.whenSynced.isDone)
        doc.emit("load")
        doc.emit("sync")

        assertEquals(listOf("load", "sync:true"), seen)
        assertTrue(syncedOnce.isDone)
        assertTrue(doc.whenSynced.isDone)
        assertSame(doc, doc.whenSynced.join())
    }

    @Test
    fun loadAndSyncFuturesMirrorYjsStateGates() {
        val alreadyLoaded = YDoc(clientId = 1)
        val doc = YDoc(clientId = 2, shouldLoad = false)

        assertFalse(alreadyLoaded.whenLoaded.isDone)
        assertFalse(alreadyLoaded.isLoaded)
        assertFalse(doc.whenLoaded.isDone)
        assertFalse(doc.whenSynced.isDone)

        doc.load()

        assertTrue(doc.shouldLoad)
        assertFalse(doc.isLoaded)
        assertFalse(doc.whenLoaded.isDone)
        assertFalse(doc.whenSynced.isDone)

        doc.sync()
        val syncedOnce = doc.whenSynced

        assertTrue(syncedOnce.isDone)
        assertSame(doc, syncedOnce.join())

        doc.sync(false)

        assertFalse(doc.whenSynced.isDone)
        assertTrue(syncedOnce.isDone)

        doc.sync()

        assertTrue(doc.whenSynced.isDone)
        assertSame(doc, doc.whenSynced.join())
    }

    @Test
    fun upstreamShapedLoadSyncAndDestroyCallbacksReceivePositionalArguments() {
        val doc = YDoc(clientId = 1)
        val seen = mutableListOf<String>()
        var removedLoadCalls = 0
        var removedSyncCalls = 0
        val removedLoad: (YDoc) -> Unit = { removedLoadCalls++ }
        val removedSync: (Boolean, YDoc) -> Unit = { _, _ -> removedSyncCalls++ }

        doc.onDoc("load") { eventDoc ->
            assertSame(doc, eventDoc)
            seen.add("load")
        }
        doc.onceDoc("destroy") { eventDoc ->
            assertSame(doc, eventDoc)
            seen.add("destroy")
        }
        doc.onSync { synced, eventDoc ->
            assertSame(doc, eventDoc)
            seen.add("sync:$synced")
        }
        doc.onDoc("load", removedLoad)
        doc.offDoc("load", removedLoad)
        doc.onSync(removedSync)
        doc.offSync(removedSync)

        doc.emit("sync", YDocEvent(name = "sync", synced = true))
        doc.emit("sync", YDocEvent(name = "sync", synced = false))
        doc.destroy()
        doc.emit("destroy")

        assertEquals(listOf("load", "sync:true", "sync:false", "destroy"), seen)
        assertEquals(0, removedLoadCalls)
        assertEquals(0, removedSyncCalls)
    }

    @Test
    fun genericDocEventsFollowTransactionLifecycleOrder() {
        val doc = YDoc(clientId = 1)
        val array = doc.getArray("items")
        val seen = mutableListOf<String>()
        val updateSizes = mutableListOf<Int>()
        val afterAllBatchSizes = mutableListOf<Int>()

        doc.on("beforeAllTransactions") { event ->
            seen.add(event.name)
        }
        doc.on("beforeTransaction") { event ->
            seen.add(event.name)
            assertEquals("origin", event.transaction?.origin)
            assertEquals(emptyMap(), event.transaction?.beforeState)
        }
        doc.on("beforeObserverCalls") { event ->
            seen.add(event.name)
            assertEquals(mapOf(1L to 1L), event.transaction?.afterState)
        }
        doc.on("afterTransaction") { event ->
            seen.add(event.name)
            assertTrue(event.transaction?.update?.isNotEmpty() == true)
        }
        doc.on("afterTransactionCleanup") { event ->
            seen.add(event.name)
            assertTrue(event.transaction?.update?.isNotEmpty() == true)
        }
        doc.on("update") { event ->
            seen.add(event.name)
            updateSizes.add(event.update.size)
            assertEquals("origin", event.origin)
            assertTrue(event.transaction?.local == true)
        }
        doc.on("updateV2") { event ->
            seen.add(event.name)
            updateSizes.add(event.update.size)
        }
        doc.on("afterAllTransactions") { event ->
            seen.add(event.name)
            afterAllBatchSizes.add(event.transactions.size)
        }

        doc.transact(origin = "origin") {
            array.push(listOf("x"))
        }

        assertEquals(
            listOf(
                "beforeAllTransactions",
                "beforeTransaction",
                "beforeObserverCalls",
                "afterTransaction",
                "afterTransactionCleanup",
                "update",
                "updateV2",
                "afterAllTransactions",
            ),
            seen,
        )
        assertEquals(2, updateSizes.size)
        assertTrue(updateSizes.all { it > 0 })
        assertEquals(listOf(1), afterAllBatchSizes)
    }

    @Test
    fun upstreamShapedAllTransactionCallbacksReceiveDocAndBatch() {
        val doc = YDoc(clientId = 1)
        val array = doc.getArray("items")
        val seen = mutableListOf<String>()
        var removedCalls = 0
        val removed: (YDoc, List<YTransactionEvent>) -> Unit = { _, _ -> removedCalls++ }

        doc.onDoc("beforeAllTransactions") { eventDoc ->
            assertSame(doc, eventDoc)
            seen.add("beforeAll")
        }
        doc.onceAfterAllTransactions { eventDoc, transactions ->
            assertSame(doc, eventDoc)
            seen.add("afterAll:${transactions.size}:${transactions.single().afterState}")
        }
        doc.onAfterAllTransactions(removed)
        doc.offAfterAllTransactions(removed)

        array.push("a")
        array.push("b")

        assertEquals(
            listOf(
                "beforeAll",
                "afterAll:1:{1=1}",
                "beforeAll",
            ),
            seen,
        )
        assertEquals(0, removedCalls)
    }

    @Test
    fun upstreamShapedDocUpdateCallbacksReceivePositionalArguments() {
        val doc = YDoc(clientId = 1)
        val array = doc.getArray("items")
        val seen = mutableListOf<String>()
        var removedCalls = 0
        val removed: (ByteArray, Any?, YDoc, YTransactionEvent?) -> Unit = { _, _, _, _ -> removedCalls++ }

        val updateSub = doc.on("update") { update, origin, eventDoc, transaction ->
            assertTrue(update.isNotEmpty())
            assertEquals("origin", origin)
            assertSame(doc, eventDoc)
            assertTrue(transaction?.local == true)
            seen.add("update:${transaction?.afterState}")
        }
        doc.once("updateV2") { update, origin, eventDoc, transaction ->
            assertTrue(update.isNotEmpty())
            assertEquals("origin", origin)
            assertSame(doc, eventDoc)
            seen.add("updateV2:${transaction?.afterState}")
        }
        doc.on("update", removed)
        doc.off("update", removed)

        doc.transact(origin = "origin") {
            array.push("a")
        }
        updateSub.close()
        array.push("b")

        assertEquals(
            listOf(
                "update:{1=1}",
                "updateV2:{1=1}",
            ),
            seen,
        )
        assertEquals(0, removedCalls)
    }

    @Test
    fun upstreamShapedTransactionCallbacksReceiveTransactionAndDoc() {
        val doc = YDoc(clientId = 1)
        val array = doc.getArray("items")
        val seen = mutableListOf<String>()
        var removedCalls = 0
        val removed: (YTransactionEvent, YDoc) -> Unit = { _, _ -> removedCalls++ }

        doc.on("beforeTransaction") { transaction, eventDoc ->
            assertSame(doc, eventDoc)
            seen.add("beforeTransaction:${transaction.beforeState}")
        }
        doc.on("beforeObserverCalls") { transaction, eventDoc ->
            assertSame(doc, eventDoc)
            seen.add("beforeObserverCalls:${transaction.afterState}")
        }
        doc.on("afterTransaction") { transaction, eventDoc ->
            assertSame(doc, eventDoc)
            seen.add("afterTransaction:${transaction.afterState}")
        }
        doc.once("afterTransactionCleanup") { transaction, eventDoc ->
            assertSame(doc, eventDoc)
            seen.add("afterTransactionCleanup:${transaction.afterState}")
        }
        doc.on("afterTransaction", removed)
        doc.off("afterTransaction", removed)

        array.push("a")
        array.push("b")

        assertEquals(
            listOf(
                "beforeTransaction:{}",
                "beforeObserverCalls:{1=1}",
                "afterTransaction:{1=1}",
                "afterTransactionCleanup:{1=1}",
                "beforeTransaction:{1=1}",
                "beforeObserverCalls:{1=2}",
                "afterTransaction:{1=2}",
            ),
            seen,
        )
        assertEquals(0, removedCalls)
    }

    @Test
    fun upstreamShapedSubdocCallbacksReceiveEventDocAndTransaction() {
        val doc = YDoc(clientId = 1)
        val map = doc.getMap("subdocs")
        val seen = mutableListOf<String>()
        var removedCalls = 0
        val removed: (YSubdocEvent, YDoc, YTransactionEvent?) -> Unit = { _, _, _ -> removedCalls++ }
        val subdoc = YDoc(guid = "s", shouldLoad = false)

        doc.on("subdocs") { event, eventDoc, transaction ->
            assertSame(doc, eventDoc)
            seen.add(
                "added=${event.added.map(YDoc::guid)} loaded=${event.loaded.map(YDoc::guid)} " +
                    "removed=${event.removed.map(YDoc::guid)} local=${transaction?.local}",
            )
        }
        doc.on("subdocs", removed)
        doc.off("subdocs", removed)

        map.setAttr("s", subdoc)
        subdoc.load()
        subdoc.destroy()

        assertEquals(
            listOf(
                "added=[s] loaded=[] removed=[] local=true",
                "added=[] loaded=[s] removed=[] local=true",
                "added=[s] loaded=[] removed=[s] local=true",
            ),
            seen,
        )
        assertEquals(0, removedCalls)
    }

    @Test
    fun genericDocEventsCanBeRemovedWithSubscriptionOrOff() {
        val doc = YDoc(clientId = 1)
        val array = doc.getArray("items")
        var subscriptionCalls = 0
        var offCalls = 0
        val offListener: (YDocEvent) -> Unit = { offCalls++ }

        val subscription = doc.on("update") { subscriptionCalls++ }
        doc.on("update", offListener)
        doc.off("update", offListener)
        array.push(listOf("a"))
        subscription.close()
        array.push(listOf("b"))

        assertEquals(1, subscriptionCalls)
        assertEquals(0, offCalls)
    }

    @Test
    fun destroyUnregistersDocumentEventHandlersAfterDestroyEvent() {
        val doc = YDoc(clientId = 1)
        val array = doc.getArray("items")
        val seen = mutableListOf<String>()

        doc.on("destroy") { seen.add("destroy") }
        doc.on("custom") { seen.add("custom") }
        doc.on("update") { seen.add("generic-update") }
        doc.observeUpdates { _, _ -> seen.add("update") }
        doc.observeBeforeAllTransactions { seen.add("beforeAllTransactions") }
        doc.observeBeforeTransactions { seen.add("beforeTransaction") }
        doc.observeBeforeObserverCalls { seen.add("beforeObserverCalls") }
        doc.observeAfterTransactions { seen.add("afterTransaction") }
        doc.observeAfterTransactionCleanup { seen.add("afterTransactionCleanup") }
        doc.observeAfterAllTransactions { seen.add("afterAllTransactions") }
        doc.observeSubdocs { seen.add("subdocs") }

        doc.destroy()
        doc.emit("custom")
        array.push(listOf("x"))
        doc.getMap("subdocs").setAttr("sub", YDoc(guid = "sub"))

        assertTrue(doc.isDestroyed)
        assertEquals(listOf("destroy"), seen)
    }

    @Test
    fun genericSubdocLoadAndDestroyEventsExposePayloads() {
        val doc = YDoc(clientId = 1)
        val map = doc.getMap("subdocs")
        val parentEvents = mutableListOf<YDocEvent>()
        val subdocEvents = mutableListOf<String>()
        val subdoc = YDoc(guid = "s", shouldLoad = false)

        doc.on("subdocs") { event -> parentEvents.add(event) }
        subdoc.on("load") { event -> subdocEvents.add(event.name) }
        subdoc.on("destroy") { event -> subdocEvents.add(event.name) }

        map.setAttr("s", subdoc)
        subdoc.load()
        subdoc.destroy()

        assertEquals(3, parentEvents.size)
        assertEquals(listOf("s"), parentEvents[0].subdocs?.added?.map(YDoc::guid))
        assertEquals(emptyList(), parentEvents[0].subdocs?.loaded)
        assertSame(subdoc, parentEvents[0].transaction?.subdocsAdded?.single())
        assertEquals(emptySet(), parentEvents[0].transaction?.subdocsLoaded)
        assertEquals(emptySet(), parentEvents[0].transaction?.subdocsRemoved)
        assertEquals(listOf("s"), parentEvents[1].subdocs?.loaded?.map(YDoc::guid))
        assertSame(subdoc, parentEvents[1].transaction?.subdocsLoaded?.single())
        assertEquals(emptySet(), parentEvents[1].transaction?.subdocsAdded)
        assertEquals(emptySet(), parentEvents[1].transaction?.subdocsRemoved)
        assertSame(subdoc, parentEvents[2].subdocs?.removed?.single())
        assertEquals(listOf("s"), parentEvents[2].subdocs?.added?.map(YDoc::guid))
        assertEquals(listOf("s"), parentEvents[2].transaction?.subdocsAdded?.map(YDoc::guid))
        assertSame(subdoc, parentEvents[2].transaction?.subdocsRemoved?.single())
        assertEquals(emptySet(), parentEvents[2].transaction?.subdocsLoaded)
        assertEquals(listOf("destroy"), subdocEvents)
    }
}
