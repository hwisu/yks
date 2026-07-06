package dev.yks

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SubdocTest {
    @Test
    fun documentOptionsExposeYjsDocMetadataFields() {
        val doc = YDoc(
            guid = "doc",
            collectionId = "collection",
            gc = false,
            meta = mapOf("owner" to "local"),
            isSuggestionDoc = true,
        )

        assertEquals("collection", doc.collectionId)
        assertEquals("collection", doc.collectionid)
        assertEquals(doc.clientId, doc.clientID)
        doc.clientID = 42
        assertEquals(42, doc.clientId)
        assertFalse(doc.gc)
        assertEquals(mapOf("owner" to "local"), doc.meta)
        assertTrue(doc.isSuggestionDoc)
        assertFalse(doc.cleanupFormatting)

        doc.guid = "renamed"
        doc.collectionid = "next-collection"
        doc.meta = mapOf("owner" to "remote")
        doc.autoLoad = true
        doc.isSuggestionDoc = false
        doc.cleanupFormatting = true

        assertEquals("renamed", doc.guid)
        assertEquals("next-collection", doc.collectionId)
        assertEquals("next-collection", doc.collectionid)
        assertEquals(mapOf("owner" to "remote"), doc.meta)
        assertTrue(doc.autoLoad)
        assertFalse(doc.isSuggestionDoc)
        assertTrue(doc.cleanupFormatting)
    }

    @Test
    fun documentCanBeConstructedFromOptionsObjectThroughYjsAlias() {
        val options = YDocOptions(
            clientId = 7,
            guid = "doc",
            collectionId = "collection",
            gc = false,
            meta = mapOf("owner" to "local"),
            shouldLoad = false,
            autoLoad = true,
            isSuggestionDoc = true,
        )

        val doc = Doc(options)
        val fromOptions = options.toDoc()

        assertEquals("collection", options.collectionid)
        assertEquals(7, doc.clientID)
        assertEquals("doc", doc.guid)
        assertEquals("collection", doc.collectionid)
        assertFalse(doc.gc)
        assertEquals(mapOf("owner" to "local"), doc.meta)
        assertFalse(doc.shouldLoad)
        assertTrue(doc.autoLoad)
        assertFalse(doc.cleanupFormatting)
        assertNotSame(doc, fromOptions)
        assertEquals(doc.clientID, fromOptions.clientID)
        assertEquals(doc.guid, fromOptions.guid)
        assertEquals(doc.collectionid, fromOptions.collectionid)
        assertEquals(doc.gc, fromOptions.gc)
        assertEquals(doc.meta, fromOptions.meta)
        assertEquals(doc.shouldLoad, fromOptions.shouldLoad)
        assertEquals(doc.autoLoad, fromOptions.autoLoad)
        assertEquals(doc.cleanupFormatting, fromOptions.cleanupFormatting)
    }

    @Test
    fun subdocSetAndLoadEmitEvents() {
        val doc = YDoc(clientId = 1)
        val events = mutableListOf<YSubdocEvent>()
        val transactions = mutableListOf<YTransactionEvent>()
        val map = doc.getMap("subdocs")
        doc.observeSubdocs { events.add(it) }
        doc.observeAfterTransactions { transactions.add(it) }

        val loadedSubdoc = YDoc(guid = "a")
        map.setAttr("a", loadedSubdoc)

        assertEquals(listOf(listOf("a")), events.map { it.added.map(YDoc::guid) })
        assertEquals(listOf(listOf("a")), events.map { it.loaded.map(YDoc::guid) })
        assertEquals(setOf(loadedSubdoc), transactions.single().subdocsAdded)
        assertEquals(setOf(loadedSubdoc), transactions.single().subdocsLoaded)
        assertEquals(emptySet(), transactions.single().subdocsRemoved)
        assertSame(loadedSubdoc, map.getAttr("a"))
        assertEquals(setOf("a"), doc.getSubdocGuids())

        events.clear()
        val unloadedSubdoc = YDoc(guid = "b", shouldLoad = false)
        map.setAttr("b", unloadedSubdoc)

        assertEquals(listOf("b"), events.single().added.map(YDoc::guid))
        assertEquals(emptyList(), events.single().loaded)
        assertFalse(unloadedSubdoc.shouldLoad)
        assertFalse(unloadedSubdoc.isLoaded)

        events.clear()
        transactions.clear()
        unloadedSubdoc.load()

        assertTrue(unloadedSubdoc.shouldLoad)
        assertFalse(unloadedSubdoc.isLoaded)
        assertEquals(listOf("b"), events.single().loaded.map(YDoc::guid))
        assertEquals(setOf(unloadedSubdoc), transactions.single().subdocsLoaded)
        assertEquals(emptySet(), transactions.single().subdocsAdded)
        assertEquals(emptySet(), transactions.single().subdocsRemoved)
        assertTrue(transactions.single().insertSet.isEmpty())
        assertTrue(transactions.single().deleteSet.isEmpty)
    }

    @Test
    fun addedSubdocsInheritParentClientAndMissingCollectionId() {
        val doc = YDoc(clientId = 7, collectionId = "workspace")
        val inherited = YDoc(clientId = 99, guid = "inherited", collectionId = null, shouldLoad = false)
        val explicit = YDoc(clientId = 100, guid = "explicit", collectionId = "own", shouldLoad = false)

        doc.getMap("subdocs").setAttr("inherited", inherited)
        doc.getMap("subdocs").setAttr("explicit", explicit)

        assertEquals(7, inherited.clientID)
        assertEquals("workspace", inherited.collectionid)
        assertEquals(7, explicit.clientID)
        assertEquals("own", explicit.collectionid)
    }

    @Test
    fun remoteAddedSubdocsInheritReceivingParentClientAndMissingCollectionId() {
        val source = YDoc(clientId = 1, collectionId = "source-workspace")
        val target = YDoc(clientId = 8, collectionId = "target-workspace")
        source.getArray("items").push(YDoc(guid = "remote", collectionId = null, shouldLoad = false))

        target.applyUpdate(source.encodeStateAsUpdate())
        val remote = target.getArray("items").get(0) as YDoc

        assertEquals(8, remote.clientID)
        assertEquals("target-workspace", remote.collectionid)
    }

    @Test
    fun subdocRefsRoundTripThroughUpdatesAsUnloadedRemoteSubdocs() {
        val source = YDoc(clientId = 1)
        val target = YDoc(clientId = 2)
        val map = source.getMap("subdocs")
        map.setAttr("a", YDoc(guid = "a").also { it.load() })
        map.setAttr("b", YDoc(guid = "a", shouldLoad = false))
        map.setAttr("c", YDoc(guid = "c").also { it.load() })

        val events = mutableListOf<YSubdocEvent>()
        target.observeSubdocs { events.add(it) }
        target.applyUpdate(source.encodeStateAsUpdate())

        assertEquals(listOf("a", "a", "c"), events.single().added.map(YDoc::guid))
        assertEquals(emptyList(), events.single().loaded)
        assertEquals(setOf("a", "c"), target.getSubdocGuids())

        val remoteA = target.getMap("subdocs").getAttr("a") as YDoc
        assertFalse(remoteA.shouldLoad)
        assertFalse(remoteA.isLoaded)

        events.clear()
        remoteA.load()

        assertEquals(listOf("a"), events.single().loaded.map(YDoc::guid))
    }

    @Test
    fun subdocOptionsRoundTripThroughUpdates() {
        val source = YDoc(clientId = 1)
        val target = YDoc(clientId = 2)
        val subdoc = YDoc(
            guid = "sub",
            collectionId = "workspace",
            gc = false,
            meta = mapOf("role" to "reference", "order" to 1),
            shouldLoad = true,
            autoLoad = true,
            isSuggestionDoc = true,
        )
        source.getMap("subdocs").setAttr("sub", subdoc)

        target.applyUpdate(source.encodeStateAsUpdate())
        val remote = target.getMap("subdocs").getAttr("sub") as YDoc

        assertEquals("sub", remote.guid)
        assertEquals("workspace", remote.collectionId)
        assertFalse(remote.gc)
        assertEquals(mapOf("order" to 1L, "role" to "reference"), remote.meta)
        assertTrue(remote.autoLoad)
        assertTrue(remote.isSuggestionDoc)
        assertFalse(remote.cleanupFormatting)
        assertTrue(remote.shouldLoad)
        assertFalse(remote.isLoaded)
    }

    @Test
    fun remoteAutoLoadSubdocsAreReportedAsLoadedOnApplyUpdate() {
        val source = YDoc(clientId = 1)
        val target = YDoc(clientId = 2)
        val events = mutableListOf<YSubdocEvent>()
        source.getArray("").push(YDoc(guid = "autoload", autoLoad = true))
        target.observeSubdocs { events.add(it) }

        target.applyUpdate(source.encodeStateAsUpdate())

        val remote = target.getArray("").get(0) as YDoc
        assertTrue(remote.shouldLoad)
        assertTrue(remote.autoLoad)
        assertEquals(listOf("autoload"), events.single().added.map(YDoc::guid))
        assertEquals(listOf("autoload"), events.single().loaded.map(YDoc::guid))
        assertSame(remote, events.single().added.single())
        assertSame(remote, events.single().loaded.single())
    }

    @Test
    fun autoloadSubdocDestroyAndRemoteApplyUpdateMirrorUpstreamEdgeCase() {
        val source = YDoc(clientId = 1)
        val array = source.getArray("")
        val sourceEvents = mutableListOf<YSubdocEvent>()
        source.observeSubdocs { events -> sourceEvents.add(events) }
        val subdoc = YDoc(guid = "autoload", autoLoad = true)

        array.insert(0, listOf(subdoc))

        assertTrue(subdoc.shouldLoad)
        assertTrue(subdoc.autoLoad)
        assertSame(subdoc, sourceEvents.single().added.single())
        assertSame(subdoc, sourceEvents.single().loaded.single())

        sourceEvents.clear()
        subdoc.destroy()
        val replacement = array.get(0) as YDoc

        assertNotSame(subdoc, replacement)
        assertSame(replacement, sourceEvents.single().added.single())
        assertFalse(replacement.isLoaded)

        sourceEvents.clear()
        replacement.load()

        assertSame(replacement, sourceEvents.single().loaded.single())

        val target = YDoc(clientId = 2)
        val targetEvents = mutableListOf<YSubdocEvent>()
        target.observeSubdocs { events -> targetEvents.add(events) }

        target.applyUpdate(source.encodeStateAsUpdate())

        val remote = target.getArray("").get(0) as YDoc
        assertTrue(remote.shouldLoad)
        assertTrue(remote.autoLoad)
        assertSame(remote, targetEvents.single().added.single())
        assertSame(remote, targetEvents.single().loaded.single())
    }

    @Test
    fun subdocReferencesCanBeUndoneAndRedoneLikeUpstream() {
        val doc = YDoc(clientId = 1)
        val array = doc.getArray("")
        val undoManager = UndoManager(array)
        val subdoc = YDoc(guid = "sub")

        array.insert(0, listOf(subdoc))

        assertEquals(1, array.length)
        undoManager.undo()
        assertEquals(0, array.length)

        undoManager.redo()

        assertEquals(1, array.length)
        assertEquals("sub", (array.get(0) as YDoc).guid)
    }

    @Test
    fun mutableSubdocOptionsAreCapturedWhenReferencesAreInserted() {
        val source = YDoc(clientId = 1)
        val target = YDoc(clientId = 2)
        val subdoc = YDoc(
            guid = "initial",
            collectionId = "old",
            meta = mapOf("role" to "draft"),
            autoLoad = false,
            isSuggestionDoc = false,
        )

        subdoc.guid = "updated"
        subdoc.collectionid = "workspace"
        subdoc.meta = mapOf("role" to "reference", "order" to 1)
        subdoc.autoLoad = true
        subdoc.isSuggestionDoc = true
        source.getMap("subdocs").setAttr("sub", subdoc)

        target.applyUpdate(source.encodeStateAsUpdate())
        val remote = target.getMap("subdocs").getAttr("sub") as YDoc

        assertEquals("updated", remote.guid)
        assertEquals("workspace", remote.collectionId)
        assertEquals(mapOf("order" to 1L, "role" to "reference"), remote.meta)
        assertTrue(remote.autoLoad)
        assertTrue(remote.isSuggestionDoc)
        assertFalse(remote.cleanupFormatting)
    }

    @Test
    fun deletingSubdocReferenceEmitsRemovedEvent() {
        val doc = YDoc(clientId = 1)
        val map = doc.getMap("subdocs")
        val subdoc = YDoc(guid = "a")
        map.setAttr("a", subdoc)
        val events = mutableListOf<YSubdocEvent>()
        doc.observeSubdocs { events.add(it) }

        map.deleteAttr("a")

        assertEquals(listOf("a"), events.single().removed.map(YDoc::guid))
        assertEquals(emptySet(), doc.subdocs)
        assertEquals(emptySet(), doc.getSubdocGuids())
    }

    @Test
    fun subdocsCanBeStoredInArraysAndPlainNestedValues() {
        val doc = YDoc(clientId = 1)
        val array = doc.getArray("items")
        val nestedSubdoc = YDoc(guid = "nested", shouldLoad = false)
        val arraySubdoc = YDoc(guid = "array")

        array.push(listOf(mapOf("child" to nestedSubdoc), arraySubdoc))

        val first = array.get(0) as Map<*, *>
        assertSame(nestedSubdoc, first["child"])
        assertSame(arraySubdoc, array.get(1))
        assertEquals(setOf("array", "nested"), doc.getSubdocGuids())
        assertEquals(listOf(mapOf("child" to mapOf("guid" to "nested")), mapOf("guid" to "array")), array.toJson())
    }

    @Test
    fun getSubdocsReturnsASetOfVisibleSubdocInstances() {
        val doc = YDoc(clientId = 1)
        val sharedSubdoc = YDoc(guid = "shared")

        doc.getArray("items").push(sharedSubdoc)
        doc.getMap("subdocs").setAttr("again", sharedSubdoc)

        assertEquals(setOf(sharedSubdoc), doc.subdocs)
        assertEquals(doc.getSubdocs(), doc.subdocs)
        assertSame(sharedSubdoc, doc.subdocs.single())
        assertEquals(setOf("shared"), doc.getSubdocGuids())
    }

    @Test
    fun destroyingVisibleSubdocMaterializesFreshUnloadedSubdoc() {
        val doc = YDoc(clientId = 1)
        val array = doc.getArray("items")
        val subdoc = YDoc(guid = "a")
        val events = mutableListOf<YSubdocEvent>()
        val transactions = mutableListOf<YTransactionEvent>()
        doc.observeSubdocs { events.add(it) }
        doc.observeAfterTransactions { transactions.add(it) }

        array.insert(0, listOf(subdoc))
        events.clear()
        transactions.clear()
        subdoc.destroy()

        val replacement = array.get(0) as YDoc

        assertNotSame(subdoc, replacement)
        assertTrue(subdoc.isDestroyed)
        assertFalse(replacement.shouldLoad)
        assertFalse(replacement.isLoaded)
        assertEquals(listOf("a"), events.single().removed.map(YDoc::guid))
        assertSame(subdoc, events.single().removed.single())
        assertSame(replacement, events.single().added.single())
        assertSame(subdoc, transactions.single().subdocsRemoved.single())
        assertSame(replacement, transactions.single().subdocsAdded.single())
        assertEquals(emptySet(), transactions.single().subdocsLoaded)
        assertTrue(transactions.single().insertSet.isEmpty())
        assertTrue(transactions.single().deleteSet.isEmpty)

        events.clear()
        replacement.load()

        assertEquals(listOf("a"), events.single().loaded.map(YDoc::guid))
    }

    @Test
    fun destroyingSubdocEmitsParentTransactionBeforeSubdocDestroyEvent() {
        val doc = YDoc(clientId = 1)
        val map = doc.getMap("subdocs")
        val subdoc = YDoc(guid = "a")
        val order = mutableListOf<String>()

        doc.observeAfterTransactions { transaction ->
            if (subdoc in transaction.subdocsRemoved) {
                order.add("parent-afterTransaction")
            }
        }
        doc.observeSubdocs { event ->
            if (subdoc in event.removed) {
                order.add("parent-subdocs")
            }
        }
        subdoc.on("destroy") {
            order.add("subdoc-destroy")
        }

        map.setAttr("a", subdoc)
        order.clear()

        subdoc.destroy()

        assertEquals(
            listOf("parent-afterTransaction", "parent-subdocs", "subdoc-destroy"),
            order,
        )
    }

    @Test
    fun destroyingMapSubdocReplacesReferenceWithoutRemovingGuid() {
        val doc = YDoc(clientId = 1)
        val map = doc.getMap("subdocs")
        val subdoc = YDoc(guid = "a")
        map.setAttr("a", subdoc)
        val events = mutableListOf<YSubdocEvent>()
        doc.observeSubdocs { events.add(it) }

        subdoc.destroy()

        val replacement = map.getAttr("a") as YDoc

        assertNotSame(subdoc, replacement)
        assertEquals(setOf("a"), doc.getSubdocGuids())
        assertEquals(listOf("a"), events.single().added.map(YDoc::guid))
        assertEquals(listOf("a"), events.single().removed.map(YDoc::guid))
        assertFalse(replacement.isLoaded)
    }

    @Test
    fun destroyingParentDocumentDestroysVisibleSubdocs() {
        val doc = YDoc(clientId = 1)
        val mapSubdoc = YDoc(guid = "map-child")
        val arraySubdoc = YDoc(guid = "array-child", shouldLoad = false)
        val destroyed = mutableListOf<String>()
        mapSubdoc.on("destroy") { event -> destroyed.add(event.name + ":" + mapSubdoc.guid) }
        arraySubdoc.on("destroy") { event -> destroyed.add(event.name + ":" + arraySubdoc.guid) }

        doc.getMap("subdocs").setAttr("map", mapSubdoc)
        doc.getArray("items").push(arraySubdoc)

        doc.destroy()

        assertTrue(doc.isDestroyed)
        assertTrue(mapSubdoc.isDestroyed)
        assertTrue(arraySubdoc.isDestroyed)
        assertEquals(listOf("destroy:array-child", "destroy:map-child"), destroyed.sorted())
    }
}
