package dev.yks

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class UndoManagerTest {
    @Test
    fun undoAndRedoTextInsertion() {
        val doc = YDoc(clientId = 1)
        val text = doc.getText("body")
        val undoManager = UndoManager(text, UndoManagerOptions(captureTimeoutMillis = 0))

        text.insert(0, "abc")

        assertTrue(undoManager.canUndo)
        assertTrue(undoManager.canUndo())
        assertEquals(3, assertNotNull(undoManager.undo()).insertedCount)
        assertEquals("", text.toString())
        assertTrue(undoManager.canRedo)
        assertTrue(undoManager.canRedo())

        undoManager.redo()

        assertEquals("abc", text.toString())
        assertEquals(YTextDelta().insert("abc"), text.toDelta())
    }

    @Test
    fun undoAndRedoTextDeletion() {
        val doc = YDoc(clientId = 1)
        val text = doc.getText("body")
        text.insert(0, "abc")
        val undoManager = UndoManager(text, UndoManagerOptions(captureTimeoutMillis = 0))

        text.delete(1)

        assertEquals("ac", text.toString())
        assertEquals(1, assertNotNull(undoManager.undo()).deletedCount)
        assertEquals("abc", text.toString())

        undoManager.redo()

        assertEquals("ac", text.toString())
    }

    @Test
    fun undoAndRedoTextFormatting() {
        val doc = YDoc(clientId = 1)
        val text = doc.getText("body")
        text.insert(0, "abcd")
        val undoManager = UndoManager(text, UndoManagerOptions(captureTimeoutMillis = 0))

        text.format(1, 2, mapOf("bold" to true))

        assertEquals(YTextDelta().insert("a").insert("bc", mapOf("bold" to true)).insert("d"), text.toDelta())
        undoManager.undo()
        assertEquals(YTextDelta().insert("abcd"), text.toDelta())
        undoManager.redo()
        assertEquals(YTextDelta().insert("a").insert("bc", mapOf("bold" to true)).insert("d"), text.toDelta())
    }

    @Test
    fun undoDeletedTextFormattingSubrangeConvergesLikeUpstream() {
        val doc = YDoc(clientId = 1)
        val peer = YDoc(clientId = 2)
        val text = doc.getText("")
        val peerText = peer.getText("")
        val undoManager = UndoManager(text)

        text.insert(0, "Attack ships on fire off the shoulder of Orion.")
        peer.applyUpdate(doc.encodeStateAsUpdate())

        text.format(13, 7, mapOf("bold" to true))
        undoManager.stopCapturing()
        peer.applyUpdate(doc.encodeStateAsUpdate(peer.encodeStateVector()))

        text.format(16, 4, mapOf("bold" to null))
        undoManager.stopCapturing()
        peer.applyUpdate(doc.encodeStateAsUpdate(peer.encodeStateVector()))

        undoManager.undo()
        peer.applyUpdate(doc.encodeStateAsUpdate(peer.encodeStateVector()))

        val expected = YTextDelta()
            .insert("Attack ships ")
            .insert("on fire", mapOf("bold" to true))
            .insert(" off the shoulder of Orion.")
        assertEquals(expected, text.toDelta())
        assertEquals(expected, peerText.toDelta())
    }

    @Test
    fun undoAndRedoTextEmbedInsertion() {
        val doc = YDoc(clientId = 1)
        val text = doc.getText("body")
        val embed = mapOf("mention" to "Ada", "id" to 7L)
        val undoManager = UndoManager(text, UndoManagerOptions(captureTimeoutMillis = 0))

        text.insertEmbed(0, embed, mapOf("kind" to "person"))

        assertEquals(YTextDelta().insertEmbed(embed, mapOf("kind" to "person")), text.toDelta())
        assertEquals(3, assertNotNull(undoManager.undo()).insertedCount)
        assertEquals(YTextDelta(), text.toDelta())

        undoManager.redo()

        assertEquals(YTextDelta().insertEmbed(embed, mapOf("kind" to "person")), text.toDelta())
    }

    @Test
    fun textScopedUndoCapturesEmbeddedSharedTypeChanges() {
        val doc = YDoc(clientId = 1)
        val text = doc.getText("body")
        val button = doc.createMap()
        button.setAttr("type", "button")
        button.setAttr("test", true)
        text.insert(0, listOf(button))
        val undoManager = UndoManager(text, UndoManagerOptions(captureTimeoutMillis = 60_000))
        undoManager.stopCapturing()

        button.setAttr("type", "paragraph")
        text.delete(0, 1)

        undoManager.undo()

        val restored = text.get(0) as YMap
        assertEquals(mapOf("test" to true, "type" to "button"), restored.getAttrs())
    }

    @Test
    fun undoAndRedoArrayOperations() {
        val doc = YDoc(clientId = 1)
        val array = doc.getArray("items")
        array.push(listOf("a", "c"))
        val undoManager = UndoManager(array, UndoManagerOptions(captureTimeoutMillis = 0))

        array.insert(1, listOf("b"))
        array.delete(0)

        assertEquals(listOf("b", "c"), array.toList())
        undoManager.undo()
        assertEquals(listOf("a", "b", "c"), array.toList())
        undoManager.undo()
        assertEquals(listOf("a", "c"), array.toList())
        undoManager.redo()
        assertEquals(listOf("a", "b", "c"), array.toList())
    }

    @Test
    fun undoMapSetAndDelete() {
        val doc = YDoc(clientId = 1)
        val map = doc.getMap("meta")
        map.set("title", "old")
        map.set("count", 1)
        val undoManager = UndoManager(map, UndoManagerOptions(captureTimeoutMillis = 0))

        map.set("title", "new")
        assertEquals("new", map.get("title"))
        undoManager.undo()
        assertEquals("old", map.get("title"))
        undoManager.redo()
        assertEquals("new", map.get("title"))

        map.delete("count")
        assertEquals(mapOf("title" to "new"), map.toMap())
        undoManager.undo()
        assertEquals(mapOf("count" to 1L, "title" to "new"), map.toMap())
    }

    @Test
    fun consecutiveRedoRestoresNestedMapAttributeStatesLikeUpstream() {
        val doc = YDoc(clientId = 1)
        val root = doc.getMap("")
        val undoManager = UndoManager(root)
        var point = doc.createMap()

        point.setAttr("x", 0)
        point.setAttr("y", 0)
        root.setAttr("a", point)
        undoManager.stopCapturing()

        point.setAttr("x", 100)
        point.setAttr("y", 100)
        undoManager.stopCapturing()

        point.setAttr("x", 200)
        point.setAttr("y", 200)
        undoManager.stopCapturing()

        point.setAttr("x", 300)
        point.setAttr("y", 300)
        undoManager.stopCapturing()

        assertEquals(mapOf("x" to 300L, "y" to 300L), point.toMap())

        undoManager.undo()
        assertEquals(mapOf("x" to 200L, "y" to 200L), point.toMap())
        undoManager.undo()
        assertEquals(mapOf("x" to 100L, "y" to 100L), point.toMap())
        undoManager.undo()
        assertEquals(mapOf("x" to 0L, "y" to 0L), point.toMap())
        undoManager.undo()
        assertEquals(null, root.getAttr("a"))

        undoManager.redo()
        point = assertNotNull(root.getAttr("a") as? YMap)
        assertEquals(mapOf("x" to 0L, "y" to 0L), point.toMap())
        undoManager.redo()
        assertEquals(mapOf("x" to 100L, "y" to 100L), point.toMap())
        undoManager.redo()
        assertEquals(mapOf("x" to 200L, "y" to 200L), point.toMap())
        undoManager.redo()
        assertEquals(mapOf("x" to 300L, "y" to 300L), point.toMap())
    }

    @Test
    fun nestedMapReferenceUndoRedoReplaysHistoricalStatesLikeUpstream() {
        val doc = YDoc(clientId = 1, gc = false)
        val design = doc.getMap("")
        val undoManager = UndoManager(design, UndoManagerOptions(captureTimeoutMillis = 0))
        val text = doc.createMap()

        val blocks1Block = doc.createMap()
        doc.transact {
            blocks1Block.setAttr("text", "Type Something")
            text.setAttr("blocks", blocks1Block)
            design.setAttr("text", text)
        }

        val blocks2Block = doc.createMap()
        doc.transact {
            blocks2Block.setAttr("text", "Something")
            text.setAttr("blocks", blocks2Block)
        }

        val blocks3Block = doc.createMap()
        doc.transact {
            blocks3Block.setAttr("text", "Something Else")
            text.setAttr("blocks", blocks3Block)
        }

        val blocks4Block = doc.createMap()
        doc.transact {
            blocks4Block.setAttr("text", "Final")
            text.setAttr("blocks", blocks4Block)
        }

        assertEquals("Final", designNestedBlockText(design))
        undoManager.undo()
        assertEquals("Something Else", designNestedBlockText(design))
        undoManager.undo()
        assertEquals("Something", designNestedBlockText(design))
        undoManager.undo()
        assertEquals("Type Something", designNestedBlockText(design))
        undoManager.undo()
        assertEquals(null, design.getAttr("text"))

        undoManager.redo()
        assertEquals("Type Something", designNestedBlockText(design))
        undoManager.redo()
        assertEquals("Something", designNestedBlockText(design))
        undoManager.redo()
        assertEquals("Something Else", designNestedBlockText(design))
        undoManager.redo()
        assertEquals("Final", designNestedBlockText(design))
    }

    @Test
    fun undoMapSetDoesNotOverwriteRemoteWinner() {
        val left = YDoc(clientId = 1)
        val right = YDoc(clientId = 2)
        val leftMap = left.getMap("meta")
        val rightMap = right.getMap("meta")

        leftMap.set("title", "old")
        syncDocs(left, right)

        val undoManager = UndoManager(leftMap, UndoManagerOptions(captureTimeoutMillis = 0))
        leftMap.set("title", "local")
        syncDocs(left, right)

        rightMap.set("title", "remote")
        syncDocs(left, right)

        undoManager.undo()
        assertEquals("remote", leftMap.get("title"))

        undoManager.redo()
        assertEquals("remote", leftMap.get("title"))
    }

    @Test
    fun undoMapSetCanIgnoreRemoteAttributeChangesWhenConfigured() {
        val left = YDoc(clientId = 1)
        val right = YDoc(clientId = 2)
        val leftMap = left.getMap("meta")
        val rightMap = right.getMap("meta")
        val undoManager = UndoManager(
            leftMap,
            UndoManagerOptions(captureTimeoutMillis = 0, ignoreRemoteAttributeChanges = true),
        )

        leftMap.setAttr("x", 1)
        syncDocs(left, right)
        rightMap.setAttr("x", 2)
        syncDocs(left, right)
        leftMap.setAttr("x", 3)
        syncDocs(left, right)
        rightMap.setAttr("x", 4)
        syncDocs(left, right)

        undoManager.undo()
        syncDocs(left, right)

        assertEquals(2L, leftMap.getAttr("x"))
        assertEquals(2L, rightMap.getAttr("x"))
    }

    @Test
    fun undoMapSetAcceptsLegacyIgnoreRemoteMapChangesOptionName() {
        val left = YDoc(clientId = 1)
        val right = YDoc(clientId = 2)
        val leftMap = left.getMap("meta")
        val rightMap = right.getMap("meta")
        val undoManager = UndoManager(
            leftMap,
            UndoManagerOptions(captureTimeoutMillis = 0, ignoreRemoteMapChanges = true),
        )

        leftMap.setAttr("x", 1)
        syncDocs(left, right)
        rightMap.setAttr("x", 2)
        syncDocs(left, right)
        leftMap.setAttr("x", 3)
        syncDocs(left, right)
        rightMap.setAttr("x", 4)
        syncDocs(left, right)

        undoManager.undo()
        syncDocs(left, right)

        assertEquals(2L, leftMap.getAttr("x"))
        assertEquals(2L, rightMap.getAttr("x"))
    }

    @Test
    fun undoRestoresRepeatedDeletedMapEntriesLikeUpstream() {
        val doc = YDoc(clientId = 1)
        val map = doc.getMap("")
        val undoManager = UndoManager(map, UndoManagerOptions(captureTimeoutMillis = 0))

        map.setAttr("a", "a")
        map.deleteAttr("a")
        map.setAttr("a", "b")
        map.deleteAttr("a")
        map.setAttr("a", "c")
        map.deleteAttr("a")
        map.setAttr("a", "d")

        assertEquals(mapOf("a" to "d"), map.toMap())
        undoManager.undo()
        assertEquals(emptyMap(), map.toMap())
        undoManager.undo()
        assertEquals(mapOf("a" to "c"), map.toMap())
        undoManager.undo()
        assertEquals(emptyMap(), map.toMap())
        undoManager.undo()
        assertEquals(mapOf("a" to "b"), map.toMap())
        undoManager.undo()
        assertEquals(emptyMap(), map.toMap())
        undoManager.undo()
        assertEquals(mapOf("a" to "a"), map.toMap())
    }

    @Test
    fun undoSkipsNoopStackItemsUntilChangeIsPerformedLikeUpstream() {
        val doc = YDoc(clientId = 1)
        val doc2 = YDoc(clientId = 2)
        doc.observeUpdatesLossless { update, _ -> doc2.applyUpdate(update) }
        doc2.observeUpdatesLossless { update, _ -> doc.applyUpdate(update) }
        val array = doc.getArray("array")
        val array2 = doc2.getArray("array")
        val map = doc.createMap()
        val map2 = doc.createMap()

        map.setAttr("hello", "world")
        array.push(map)
        map2.setAttr("key", "value")
        array.push(map2)

        val undoManager = UndoManager(
            array,
            UndoManagerOptions(trackedOrigins = setOf(doc.clientId)),
        )
        val undoManager2 = UndoManager(
            array2,
            UndoManagerOptions(trackedOrigins = setOf(doc2.clientId)),
        )

        doc.transact(origin = doc.clientId) {
            map2.setAttr("key", "value modified")
        }
        undoManager.stopCapturing()
        doc.transact(origin = doc.clientId) {
            map.setAttr("hello", "world modified")
        }
        doc2.transact(origin = doc2.clientId) {
            array2.delete(0)
        }

        undoManager2.undo()
        undoManager.undo()

        assertEquals("value", map2.getAttr("key"))
        assertEquals("value", (array.get(1) as YMap).getAttr("key"))
    }

    @Test
    fun undoDoesNotRestoreOrphanedDescendantWhenCandidateOwnerCannotBeRestored() {
        val local = YDoc(clientId = 1, gc = false)
        val remote = YDoc(clientId = 2, gc = false)
        val root = local.getArray("root")
        val remoteRoot = remote.getArray("root")
        val parent = local.createMap()
        val child = local.createMap()
        child.set("leaf", "value")
        parent.set("child", child)
        root.push(parent)
        remote.applyUpdate(local.encodeStateAsUpdateLossless(), origin = "sync")
        val undoManager = UndoManager(
            root,
            UndoManagerOptions(captureTimeoutMillis = 0, trackedOrigins = setOf("local")),
        )

        local.transact(origin = "local") {
            parent.delete("child")
        }
        remote.applyUpdate(local.encodeStateAsUpdateLossless(), origin = "sync")
        remoteRoot.delete(0)
        local.applyUpdate(remote.encodeStateAsUpdateLossless(), origin = "sync")

        assertEquals(emptyList(), root.toList())
        assertTrue(undoManager.canUndo)
        assertEquals(null, undoManager.undo())
        assertEquals(emptyMap(), child.toMap())
        assertFalse(undoManager.canRedo)
    }

    @Test
    fun managerRespectsTypeScopeAndCapturesTrackedRemoteUpdatesLikeUpstream() {
        val doc = YDoc(clientId = 1)
        val text = doc.getText("body")
        val map = doc.getMap("meta")
        val undoManager = UndoManager(text, UndoManagerOptions(captureTimeoutMillis = 0))

        map.set("title", "outside")
        assertFalse(undoManager.canUndo)

        val remote = YDoc(clientId = 2)
        remote.getText("body").insert(0, "remote")
        doc.applyUpdate(remote.encodeStateAsUpdate())
        assertTrue(undoManager.canUndo)
        assertEquals("remote", text.toString())

        undoManager.undo()
        assertEquals("", text.toString())

        text.insert(0, "local")
        assertTrue(undoManager.canUndo)
        undoManager.undo()
        assertEquals("", text.toString())
    }

    @Test
    fun documentScopeCapturesEmptyTransactionAndCallsCaptureHookLikeUpstream() {
        val doc = YDoc(clientId = 1)
        var captureCalls = 0
        val undoManager = UndoManager(
            doc,
            UndoManagerOptions(
                captureTimeoutMillis = 0,
                captureTransaction = {
                    captureCalls++
                    true
                },
            ),
        )

        doc.transact { }

        assertEquals(1, captureCalls)
        assertEquals(1, undoManager.undoStack.size)
        assertTrue(undoManager.canUndo)
        assertTrue(undoManager.undoStack.single().isEmpty)
        assertEquals(null, undoManager.undo())
        assertFalse(undoManager.canUndo)
    }

    @Test
    fun trackedOriginsCanMatchOriginClassesLikeUpstream() {
        val doc = YDoc(clientId = 1)
        val text = doc.getText("body")
        val undoManager = UndoManager(
            text,
            UndoManagerOptions(captureTimeoutMillis = 0, trackedOrigins = setOf(Number::class)),
        )

        doc.transact(origin = 42) {
            text.insert(0, "abc")
        }

        assertEquals("abc", text.toString())
        assertTrue(undoManager.canUndo)

        undoManager.undo()

        assertEquals("", text.toString())
    }

    @Test
    fun stackItemEventsExposeMetadataIdsAndCurrentStackItemDuringUndo() {
        val doc = YDoc(clientId = 1)
        val text = doc.getText("body")
        val undoManager = UndoManager(text, UndoManagerOptions(captureTimeoutMillis = 0))
        val addedEvents = mutableListOf<UndoManagerEvent>()
        val poppedEvents = mutableListOf<UndoManagerEvent>()
        var observedUndoMeta: Any? = null

        undoManager.on("stack-item-added") { event ->
            addedEvents.add(event)
            if (event.type == UndoStackItemType.Undo) {
                event.stackItem?.meta?.set("selection", "cursor:1")
            }
        }
        undoManager.on("stack-item-popped") { event -> poppedEvents.add(event) }
        text.observe { event ->
            if (event.origin === undoManager) {
                observedUndoMeta = undoManager.currStackItem?.meta?.get("selection")
            }
        }

        text.insert(0, "a")
        val undoStackItem = assertNotNull(addedEvents.single().stackItem)

        assertEquals(UndoStackItemType.Undo, addedEvents.single().type)
        assertTrue(addedEvents.single().changedParentTypes.contains(text))
        assertTrue(undoStackItem.inserts.has(1, 0))
        assertTrue(undoStackItem.deletes.isEmpty())

        val popped = assertNotNull(undoManager.undo())

        assertEquals("cursor:1", popped.meta["selection"])
        assertEquals("cursor:1", observedUndoMeta)
        assertEquals("", text.toString())
        assertEquals(null, undoManager.currStackItem)
        assertEquals(UndoStackItemType.Undo, poppedEvents.single().type)
        assertTrue(poppedEvents.single().origin === undoManager)
        assertTrue(poppedEvents.single().changedParentTypes.contains(text))
        assertEquals(2, addedEvents.size)
        assertEquals(UndoStackItemType.Redo, addedEvents.last().type)
        assertTrue(addedEvents.last().changedParentTypes.contains(text))
        assertTrue(undoManager.canRedo)
    }

    @Test
    fun currentStackItemAndModeFlagsAreVisibleWhileUndoingAndRedoing() {
        val doc = YDoc(clientId = 1)
        val text = doc.getText("text")
        val undoManager = UndoManager(text, UndoManagerOptions(captureTimeoutMillis = 0))
        var metaUndo: Any? = null
        var metaRedo: Any? = null

        undoManager.on("stack-item-added") { event ->
            event.stackItem?.meta?.set("str", "42")
        }
        text.observe { event ->
            val origin = event.origin as? UndoManager
            when {
                origin === undoManager && origin.undoing -> metaUndo = origin.currStackItem?.meta?.get("str")
                origin === undoManager && origin.redoing -> metaRedo = origin.currStackItem?.meta?.get("str")
            }
        }

        text.insert(0, "abc")
        undoManager.undo()
        undoManager.redo()

        assertEquals("42", metaUndo)
        assertEquals("42", metaRedo)
        assertEquals(null, undoManager.currStackItem)
        assertFalse(undoManager.undoing)
        assertFalse(undoManager.redoing)
    }

    @Test
    fun publicUndoRedoStacksAcceptIdSetStackItems() {
        val doc = YDoc(clientId = 1, gc = false)
        val text = doc.getText("body")
        text.insert(0, "abc")
        val undoManager = UndoManager(text, UndoManagerOptions(captureTimeoutMillis = 0))

        undoManager.undoStack.add(StackItem(idSet(1, 1, 1), createIdSet()))

        assertEquals(1, undoManager.undoStack.size)
        assertTrue(undoManager.canUndo)
        assertTrue(undoManager.canUndo())

        val deleted = assertNotNull(undoManager.undo())

        assertEquals("ac", text.toString())
        assertTrue(deleted.inserts.has(1, 1))
        assertEquals(0, undoManager.undoStack.size)
        assertEquals(1, undoManager.redoStack.size)
        assertTrue(undoManager.canRedo)
        assertTrue(undoManager.canRedo())

        assertNotNull(undoManager.redo())

        assertEquals("abc", text.toString())

        text.delete(1)
        undoManager.clear()
        undoManager.undoStack.add(StackItem(createIdSet(), idSet(1, 1, 1)))

        val restored = assertNotNull(undoManager.undo())

        assertEquals("abc", text.toString())
        assertTrue(restored.deletes.has(1, 1))
    }

    @Test
    fun destroyStopsCapturingTransactions() {
        val doc = YDoc(clientId = 1)
        val text = doc.getText("body")
        val undoManager = UndoManager(text, UndoManagerOptions(captureTimeoutMillis = 0))

        text.insert(0, "a")
        undoManager.destroy()
        text.insert(1, "b")

        assertEquals(1, undoManager.undoStackSize)
        assertEquals("ab", text.toString())
    }

    @Test
    fun stackItemUpdatedEventPreservesMetadataWhenCapturesMerge() {
        val doc = YDoc(clientId = 1)
        val text = doc.getText("body")
        val undoManager = UndoManager(text, UndoManagerOptions(captureTimeoutMillis = 60_000))
        val eventNames = mutableListOf<String>()

        undoManager.on("stack-item-added") { event ->
            eventNames.add("added")
            event.stackItem?.meta?.set("batch", "typing")
        }
        undoManager.on("stack-item-updated") { event ->
            eventNames.add("updated")
            assertEquals("typing", event.stackItem?.meta?.get("batch"))
            assertEquals(UndoStackItemType.Undo, event.type)
        }

        text.insert(0, "a")
        text.insert(1, "b")

        assertEquals(listOf("added", "updated"), eventNames)
        assertEquals(1, undoManager.undoStackSize)
        assertEquals("typing", assertNotNull(undoManager.undo()).meta["batch"])
        assertEquals("", text.toString())
    }

    @Test
    fun upstreamCaptureTimeoutOptionNameControlsCaptureMerging() {
        val doc = YDoc(clientId = 1)
        val array = doc.getArray("items")
        val undoManager = UndoManager(
            array,
            UndoManagerOptions(captureTimeoutMillis = 0, captureTimeout = Long.MAX_VALUE),
        )

        array.push(1)
        array.push(2)

        assertEquals(1, undoManager.undoStackSize)

        undoManager.stopCapturing()
        array.push(3)

        assertEquals(2, undoManager.undoStackSize)

        undoManager.undo()
        assertEquals(listOf(1L, 2L), array.toList())

        undoManager.undo()
        assertEquals(emptyList(), array.toList())
    }

    @Test
    fun publicStackItemConstructorStoresInsertionAndDeletionIdSets() {
        val insertions = idSet(1, 0, 2)
        val deletions = idSet(2, 3, 1)
        val stackItem = StackItem(insertions, deletions)
        stackItem.meta["selection"] = "cursor"

        insertions.add(1, 10, 1)
        stackItem.inserts.add(1, 20, 1)

        assertEquals(2, stackItem.insertedCount)
        assertEquals(1, stackItem.deletedCount)
        assertTrue(stackItem.inserts.has(1, 0))
        assertTrue(stackItem.inserts.has(1, 1))
        assertFalse(stackItem.inserts.has(1, 10))
        assertFalse(stackItem.inserts.has(1, 20))
        assertTrue(stackItem.deletes.has(2, 3))
        assertFalse(stackItem.isEmpty)
        assertEquals("cursor", stackItem.meta["selection"])
        assertTrue(StackItem(createIdSet(), createIdSet()).isEmpty)
    }

    @Test
    fun stackClearedEventReportsWhichStacksWereClearedAndCanUnsubscribe() {
        val doc = YDoc(clientId = 1)
        val text = doc.getText("body")
        val undoManager = UndoManager(text, UndoManagerOptions(captureTimeoutMillis = 0))
        val clearEvents = mutableListOf<UndoManagerEvent>()
        val subscription = undoManager.on("stack-cleared") { event -> clearEvents.add(event) }

        text.insert(0, "a")
        undoManager.undo()
        undoManager.clear(clearUndoStack = false, clearRedoStack = true)

        assertEquals(false, clearEvents.single().undoStackCleared)
        assertEquals(true, clearEvents.single().redoStackCleared)

        subscription.close()
        text.insert(0, "b")
        undoManager.clear()

        assertEquals(1, clearEvents.size)
    }

    @Test
    fun onceListenerOnlyHandlesFirstMatchingStackEvent() {
        val doc = YDoc(clientId = 1)
        val text = doc.getText("body")
        val undoManager = UndoManager(text, UndoManagerOptions(captureTimeoutMillis = 0))
        val seen = mutableListOf<UndoStackItemType?>()

        undoManager.once("stack-item-added") { event -> seen.add(event.type) }

        text.insert(0, "a")
        text.insert(1, "b")

        assertEquals(listOf<UndoStackItemType?>(UndoStackItemType.Undo), seen)
        assertEquals(2, undoManager.undoStackSize)
    }

    @Test
    fun trackedOriginsCanBeChangedAfterConstruction() {
        val doc = YDoc(clientId = 1)
        val text = doc.getText("body")
        val undoManager = UndoManager(
            text,
            UndoManagerOptions(captureTimeoutMillis = 0, trackedOrigins = emptySet()),
        )

        text.insert(0, "ignored")
        doc.transact(origin = "tracked") {
            text.insert(text.length, " still-ignored")
        }

        assertFalse(undoManager.canUndo)

        undoManager.addTrackedOrigin("tracked")
        doc.transact(origin = "tracked") {
            text.insert(text.length, " captured")
        }

        assertTrue(undoManager.canUndo)
        undoManager.clear()
        undoManager.removeTrackedOrigin("tracked")

        doc.transact(origin = "tracked") {
            text.insert(text.length, " ignored-again")
        }

        assertFalse(undoManager.canUndo)
    }

    @Test
    fun deleteFilterCanKeepSelectedInsertedContentDuringUndo() {
        val doc = YDoc(clientId = 1)
        val text = doc.getText("body")
        val undoManager = UndoManager(
            text,
            UndoManagerOptions(
                captureTimeoutMillis = 0,
                deleteFilter = { item -> item.id.clock != 0L },
            ),
        )

        text.insert(0, "ab")

        val undoStackItem = assertNotNull(undoManager.undo())

        assertEquals("a", text.toString())
        assertEquals(2, undoStackItem.insertedCount)
        assertTrue(undoManager.canRedo)

        val redoStackItem = assertNotNull(undoManager.redo())

        assertEquals("ab", text.toString())
        assertEquals(1, redoStackItem.deletedCount)
        assertTrue(redoStackItem.deletes.has(1, 1))
        assertFalse(redoStackItem.deletes.has(1, 0))
    }

    @Test
    fun deleteFilterReceivesContentItemLikeUpstream() {
        val doc = YDoc(clientId = 1)
        val array = doc.getArray("items")
        val undoManager = UndoManager(
            array,
            UndoManagerOptions(
                captureTimeoutMillis = 0,
                deleteFilter = { item ->
                    val contentType = item.content as? ContentType
                    contentType == null || (contentType.type as? YMap)?.attrSize == 0
                },
            ),
        )
        val mapWithAttr = doc.createMap()
        mapWithAttr.setAttr("hi", 1)
        val emptyMap = doc.createMap()

        array.insert(0, listOf(mapWithAttr, emptyMap))

        undoManager.undo()

        assertEquals(1, array.length)
        val remaining = array.get(0) as YMap
        assertSame(mapWithAttr, remaining)
        assertEquals(emptySet(), remaining.attrKeys())
    }

    @Test
    fun scopeCanBeExpandedAfterConstruction() {
        val doc = YDoc(clientId = 1)
        val text = doc.getText("body")
        val map = doc.getMap("meta")
        val array = doc.getArray("items")
        val undoManager = UndoManager(text, UndoManagerOptions(captureTimeoutMillis = 0))

        map.setAttr("outside", "ignored")
        assertFalse(undoManager.canUndo)

        undoManager.addToScope(map)
        map.setAttr("title", "captured")

        assertTrue(undoManager.canUndo)
        undoManager.undo()
        assertEquals(mapOf("outside" to "ignored"), map.toMap())

        undoManager.addToScope(doc)
        array.push("doc-wide")

        assertTrue(undoManager.canUndo)
        undoManager.undo()
        assertEquals(emptyList(), array.toList())
    }

    @Test
    fun emptyTypeScopeCanBeExpandedWhenDocOptionIsProvidedLikeUpstream() {
        val doc = YDoc(clientId = 1)
        val undoManager = UndoManager(
            emptyList<AbstractYType>(),
            UndoManagerOptions(captureTimeoutMillis = 0, doc = doc),
        )
        val array = doc.getArray("")

        array.insert(0, listOf(1))
        assertFalse(undoManager.canUndo)

        undoManager.addToScope(array)
        array.insert(1, listOf(2))

        assertTrue(undoManager.canUndo)
        undoManager.undo()
        assertEquals(listOf(1L), array.toList())

        assertFailsWith<IllegalArgumentException> {
            UndoManager(emptyList<AbstractYType>())
        }
    }

    @Test
    fun rootScopeIncludesNestedTypesInsideMapAndListValues() {
        val doc = YDoc(clientId = 1)
        val root = doc.getMap("root")
        val nested = doc.createMap()
        root.setAttr("payload", mapOf("children" to listOf(nested)))
        val undoManager = UndoManager(root, UndoManagerOptions(captureTimeoutMillis = 0))

        nested.setAttr("title", "captured")

        assertTrue(undoManager.canUndo)
        undoManager.undo()
        assertEquals(emptyMap(), nested.toMap())
    }

    @Test
    fun xmlFragmentScopeIncludesLiveXmlElementAttributeChanges() {
        val doc = YDoc(clientId = 1)
        val fragment = doc.getXmlFragment("xml")
        val element = doc.createXmlElement("p")
        fragment.push(element)
        val undoManager = UndoManager(fragment, UndoManagerOptions(captureTimeoutMillis = 0))

        element.setAttr("class", "lead")

        assertTrue(undoManager.canUndo)
        undoManager.undo()
        assertEquals("<p></p>", fragment.toString())
        assertFalse(element.hasAttr("class"))
    }

    @Test
    fun undoDeletedLiveXmlElementRestoresAttributeStateLikeUpstreamSpecialCase() {
        val origin = "undoable"
        val doc = YDoc(clientId = 1)
        val fragment = doc.getXmlFragment("xml")
        val element = doc.createXmlElement("test")
        element.setAttrs(mapOf("a" to "1", "b" to "2"))
        fragment.push(element)
        val undoManager = UndoManager(
            fragment,
            UndoManagerOptions(captureTimeoutMillis = 0, trackedOrigins = setOf(origin)),
        )

        doc.transact(origin = origin) {
            val liveElement = fragment.getType(0) as YXmlElementType
            liveElement.setAttr("b", "3")
            fragment.delete(0)
        }

        assertEquals("", fragment.toString())

        undoManager.undo()

        val restoredElement = fragment.getType(0) as YXmlElementType
        assertEquals("<test a=\"1\" b=\"2\"></test>", fragment.toString())
        assertEquals("2", restoredElement.getAttr("b"))

        undoManager.redo()

        assertEquals("", fragment.toString())
    }

    @Test
    fun undoXmlRestoresFormattedLiveTextChildInsideLiveElementLikeUpstream() {
        val doc = YDoc(clientId = 1)
        val fragment = doc.getXmlFragment("xml")
        val undoManager = UndoManager(fragment, UndoManagerOptions(captureTimeoutMillis = 0))
        val paragraph = doc.createXmlElement("p")
        val textChild = doc.createText()

        fragment.insertType(0, paragraph)
        textChild.insert(0, "content")
        paragraph.insertType(0, textChild)
        undoManager.stopCapturing()

        textChild.format(3, 4, mapOf("bold" to true))

        val formatted = YXmlFragmentDeepDelta(
            delta = listOf(YArrayDeltaOp(insert = listOf(
                YXmlElementDeepDelta(
                    nodeName = "p",
                    children = listOf(
                        YTextDeepDelta(
                            delta = YTextDelta()
                                .insert("con")
                                .insert("tent", mapOf("bold" to true)),
                        ),
                    ),
                ),
            ))),
        )
        val plain = YXmlFragmentDeepDelta(
            delta = listOf(YArrayDeltaOp(insert = listOf(
                YXmlElementDeepDelta(
                    nodeName = "p",
                    children = listOf(YTextDeepDelta(delta = YTextDelta().insert("content"))),
                ),
            ))),
        )

        assertEquals(formatted, fragment.toDeltaDeep())

        undoManager.undo()
        assertEquals(plain, fragment.toDeltaDeep())

        undoManager.redo()
        assertEquals(formatted, fragment.toDeltaDeep())

        fragment.delete(0)
        assertEquals(YXmlFragmentDeepDelta(), fragment.toDeltaDeep())

        undoManager.undo()
        assertEquals(formatted, fragment.toDeltaDeep())
    }

    @Test
    fun undoContentIdsDeletesSelectedInsertedContent() {
        val doc = YDoc(clientId = 1)
        val text = doc.getText("body")
        text.insert(0, "abc")

        val stackItem = undoContentIds(
            doc,
            createContentIds(inserts = idSet(1, 1, 1)),
        )

        assertEquals("ac", text.toString())
        assertEquals(1, assertNotNull(stackItem).insertedCount)
        assertTrue(stackItem.inserts.has(1, 1))
    }

    @Test
    fun undoContentIdsRestoresSelectedDeletedContent() {
        val doc = YDoc(clientId = 1, gc = false)
        val text = doc.getText("body")
        text.insert(0, "abc")
        text.delete(1)

        val stackItem = undoContentIds(
            doc,
            createContentIds(deletes = idSet(1, 1, 1)),
        )

        assertEquals("abc", text.toString())
        assertEquals(1, assertNotNull(stackItem).deletedCount)
        assertTrue(stackItem.deletes.has(1, 1))
    }

    @Test
    fun redoItemRestoresDeletedItemAndMarksItKept() {
        val doc = YDoc(clientId = 1, gc = false)
        val text = doc.getText("body")
        val origins = mutableListOf<Any?>()
        doc.observeAfterTransactions { event ->
            if (event.addedItemCount > 0 || event.deletedItemCount > 0) {
                origins.add(event.origin)
            }
        }
        text.insert(0, "abc", mapOf("bold" to true))
        text.delete(1)
        val deleted = doc.sequence(text.name).first { item ->
            (item.content as? ItemContent.Text)?.value == "b"
        }.toItemStruct(doc)
        origins.clear()

        var restored: Item? = null
        doc.transact({ transaction ->
            restored = redoItem(transaction, deleted)
        }, origin = "redo-item")

        val restoredItem = assertNotNull(restored)
        assertEquals("abc", text.toString())
        assertEquals(YTextDelta().insert("abc", mapOf("bold" to true)), text.toDelta())
        assertEquals(listOf<Any?>("redo-item"), origins)
        assertEquals(restoredItem.id, doc.followRedone(deleted.id))
        assertEquals(restoredItem.id, assertNotNull(redoItem(doc, deleted)).id)

        text.delete(1)

        assertFalse(gcIdSet(doc, idSet(restoredItem.id.client, restoredItem.id.clock, restoredItem.length)).hasId(restoredItem.id))
    }

    @Test
    fun undoContentIdsIgnoresContentIdsThatAreBothInsertedAndDeleted() {
        val doc = YDoc(clientId = 1)
        val text = doc.getText("body")
        text.insert(0, "x")
        text.delete(0)

        val stackItem = undoContentIds(
            doc,
            createContentIds(
                inserts = idSet(1, 0, 1),
                deletes = idSet(1, 0, 1),
            ),
        )

        assertEquals("", text.toString())
        assertEquals(null, stackItem)
    }

    private fun idSet(vararg triples: Long): IdSet {
        require(triples.size % 3 == 0)
        val idSet = createIdSet()
        triples.asList().chunked(3).forEach { (client, clock, len) -> idSet.add(client, clock, len) }
        return idSet
    }

    private fun designNestedBlockText(design: YMap): Any? {
        val text = design.getAttr("text") as? YMap ?: return null
        val block = text.getAttr("blocks") as? YMap ?: return null
        return block.getAttr("text")
    }

    private fun syncDocs(vararg docs: YDoc) {
        val updates = docs.map { doc -> doc.encodeStateAsUpdate() }
        docs.forEach { target ->
            updates.forEach { update -> target.applyUpdate(update, origin = RemoteSyncOrigin) }
        }
    }

    private object RemoteSyncOrigin
}
