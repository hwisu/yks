package dev.yks

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RendererTest {
    @Test
    fun attributionJsonSchemaValidatesUpstreamAttributionShape() {
        val attribution = mapOf(
            "insert" to listOf("alice"),
            "insertAt" to 1,
            "delete" to listOf("bob"),
            "deleteAt" to 2.0,
            "format" to mapOf("bold" to listOf("carol")),
            "formatAt" to 3L,
        )

        assertEquals(
            setOf("insert", "insertAt", "delete", "deleteAt", "format", "formatAt"),
            attributionJsonSchema.fields.keys,
        )
        assertTrue(attributionJsonSchema.check(attribution))
        assertTrue(attributionJsonSchema.check(emptyMap<String, Any?>()))
        assertFalse(attributionJsonSchema.check(mapOf("insert" to listOf(1))))
        assertFalse(attributionJsonSchema.check(mapOf("insertAt" to "1")))
        assertFalse(attributionJsonSchema.check(mapOf("format" to mapOf("bold" to listOf(1)))))
        assertFalse(attributionJsonSchema.check(mapOf("unknown" to true)))
    }

    @Test
    fun baseRendererReadsVisibleContentAndComputesVisibleLength() {
        val visible = mutableListOf<AttributedContent>()
        val hidden = mutableListOf<AttributedContent>()
        val forced = mutableListOf<AttributedContent>()

        baseRenderer.readContent(visible, 1, 0, deleted = false, ContentString("hi"), renderBehavior = 0)
        baseRenderer.readContent(hidden, 1, 0, deleted = true, ContentString("x"), renderBehavior = 0)
        baseRenderer.readContent(forced, 1, 0, deleted = true, ContentString("x"), renderBehavior = 2)

        assertEquals(rendererType, baseRenderer.type)
        assertEquals(rendererType, baseRenderer.`$type`)
        assertEquals(rendererType, `$renderer`)
        assertEquals(listOf("hi"), visible.map { (it.content as ContentString).str })
        assertFalse(visible.single().render)
        assertEquals(emptyList(), hidden)
        assertTrue(forced.single().render)
        assertEquals(
            2L,
            baseRenderer.contentLength(
                ItemStruct(Id(1, 0), 2, deleted = false, null, null, "body", null, RootKind.Text, ContentString("hi")),
            ),
        )
        assertEquals(
            0L,
            baseRenderer.contentLength(
                ItemStruct(Id(1, 0), 2, deleted = true, null, null, "body", null, RootKind.Text, ContentString("hi")),
            ),
        )
    }

    @Test
    fun rendererContentLengthUsesGenericFastPathUnlessRendererClaimsItem() {
        val visible = item(Id(1, 0), length = 2, deleted = false, content = ContentString("hi"))
        val deleted = visible.copy(deleted = true)
        val format = item(Id(1, 2), deleted = false, content = ContentFormat("bold", true))
        val deletes = createIdMap().also { map -> map.add(1, 0, 1, emptyList()) }
        val renderer = TwosetRenderer(createIdMap(), deletes)

        assertFalse(baseRenderer.hasItem(visible))
        assertEquals(2L, rendererContentLength(null, visible))
        assertEquals(2L, rendererContentLength(baseRenderer, visible))
        assertEquals(0L, rendererContentLength(null, deleted))
        assertEquals(0L, rendererContentLength(null, format))

        assertTrue(renderer.hasItem(deleted))
        assertTrue(renderer.attributed.intersects(1, 0, 1))
        assertEquals(1L, rendererContentLength(renderer, deleted))

        renderer.deletes = createIdMap()

        assertFalse(renderer.hasItem(deleted))
        assertEquals(0L, rendererContentLength(renderer, deleted))
    }

    @Test
    fun twosetRendererSplitsContentAndAppliesInsertAndDeleteAttributions() {
        val author = createContentAttribute("author", "local")
        val inserts = createIdMap().also { map -> map.add(1, 0, 1, listOf(author)) }
        val deletes = createIdMap().also { map -> map.add(1, 1, 1, listOf(author)) }
        val renderer = TwosetRenderer(inserts, deletes)
        val inserted = mutableListOf<AttributedContent>()
        val deleted = mutableListOf<AttributedContent>()

        assertEquals(`$renderer`, renderer.`$type`)
        renderer.readContent(inserted, 1, 0, deleted = false, ContentString("ab"), renderBehavior = 0)
        renderer.readContent(deleted, 1, 0, deleted = true, ContentString("ab"), renderBehavior = 0)

        assertEquals(listOf("a", "b"), inserted.map { (it.content as ContentString).str })
        assertEquals(listOf(listOf(author), null), inserted.map { it.attrs })
        assertEquals(listOf("b"), deleted.map { (it.content as ContentString).str })
        assertEquals(listOf(listOf(author)), deleted.map { it.attrs })
        assertEquals(
            1L,
            renderer.contentLength(
                ItemStruct(Id(1, 0), 2, deleted = true, null, null, "body", null, RootKind.Text, ContentString("ab")),
            ),
        )
    }

    @Test
    fun twosetRendererModeThreeMarksFreshContentAndSkipsUnattributedDeletedContent() {
        val deletes = createIdMap().also { map -> map.add(1, 1, 1, emptyList()) }
        val renderer = TwosetRenderer(createIdMap(), deletes)
        val rendered = mutableListOf<AttributedContent>()

        renderer.readContent(rendered, 1, 0, deleted = true, ContentString("ab"), renderBehavior = 3)

        assertEquals(listOf("b"), rendered.map { (it.content as ContentString).str })
        assertTrue(rendered.single().fresh)
        assertTrue(rendered.single().render)
    }

    @Test
    fun diffRendererTracksInsertedAndDeletedRangesAndCanAcceptAllChanges() {
        val prev = YDoc(clientId = 1, gc = false)
        val prevText = prev.getText("body")
        prevText.insert(0, "ab")
        val next = cloneDoc(prev, YDocOptions(clientId = 2, gc = false))
        val nextText = next.getText("body")
        nextText.delete(0)
        nextText.insert(1, "c")

        val renderer = createDiffRenderer(prev, next)
        val deletedStruct = getTypeStructs(nextText).single { it.id == Id(1, 0) }
        val insertedStruct = getTypeStructs(nextText).single { it.id == Id(2, 0) }
        val deleted = mutableListOf<AttributedContent>()
        val inserted = mutableListOf<AttributedContent>()

        renderer.readContent(deleted, 1, 0, deleted = true, deletedStruct.content, renderBehavior = 0)
        renderer.readContent(inserted, 2, 0, deleted = false, insertedStruct.content, renderBehavior = 0)

        assertTrue(renderer.deletes.has(1, 0))
        assertTrue(renderer.inserts.has(2, 0))
        assertEquals("a", (deleted.single().content as ContentString).str)
        assertEquals(emptyList(), deleted.single().attrs)
        assertEquals("c", (inserted.single().content as ContentString).str)
        assertEquals(1L, renderer.contentLength(deletedStruct))

        renderer.acceptAllChanges()

        assertEquals("bc", prevText.toString())
        assertTrue(renderer.inserts.isEmpty())
        assertTrue(renderer.deletes.isEmpty())
    }

    @Test
    fun diffRendererRecoversGcCollectedDeletedContentFromPrevDoc() {
        val prev = YDoc(clientId = 1, gc = false)
        prev.getText("body").insert(0, "abc")
        val next = cloneDoc(prev, YDocOptions(clientId = 2, gc = false))
        val nextText = next.getText("body")
        nextText.delete(1)
        gcIdSet(next, createDeleteSetFromDoc(next))
        val renderer = createDiffRenderer(prev, next)
        val deletedStruct = getTypeStructs(nextText).single { it.id == Id(1, 1) }
        val rendered = mutableListOf<AttributedContent>()

        assertTrue(deletedStruct.content is ContentDeleted)

        renderer.readContent(rendered, 1, 1, deleted = true, deletedStruct.content, renderBehavior = 0)

        assertEquals(1L, renderer.contentLength(deletedStruct))
        assertEquals("b", (rendered.single().content as ContentString).str)
        assertTrue(rendered.single().deleted)
        assertEquals(emptyList(), rendered.single().attrs)
    }

    @Test
    fun diffRendererRelaysLosslessOnlyXmlStateInternally() {
        val prev = YDoc(clientId = 1, gc = false)
        val next = YDoc(clientId = 2, gc = false)
        val renderer = createDiffRenderer(prev, next)
        renderer.suggestionMode = false

        next.getXmlFragment("xml").push(YXmlElement("p").also { it.push(YXmlText("private")) })

        assertEquals("<p>private</p>", prev.getXmlFragment("xml").toString())
        renderer.destroy()
    }

    @Test
    fun diffRendererRejectAllChangesRemovesSuggestedInsertsFromNextDoc() {
        val prev = YDoc(clientId = 1)
        prev.getText("body").insert(0, "a")
        val next = cloneDoc(prev).also { it.clientId = 2 }
        val nextText = next.getText("body")
        nextText.insert(1, "b")
        val renderer = createDiffRenderer(prev, next)

        assertEquals("ab", nextText.toString())
        assertTrue(renderer.inserts.has(2, 0))

        renderer.rejectAllChanges()

        assertEquals("a", nextText.toString())
        assertEquals("a", prev.getText("body").toString())
        assertTrue(renderer.inserts.isEmpty())
        assertTrue(renderer.deletes.isEmpty())
    }

    @Test
    fun diffRendererRejectAllChangesRestoresSuggestedDeletesInNextDoc() {
        val prev = YDoc(clientId = 1, gc = false)
        prev.getText("body").insert(0, "ab")
        val next = cloneDoc(prev, YDocOptions(clientId = 2, gc = false))
        val nextText = next.getText("body")
        nextText.delete(0)
        val renderer = createDiffRenderer(prev, next)

        assertEquals("b", nextText.toString())
        assertTrue(renderer.deletes.has(1, 0))

        renderer.rejectAllChanges()

        assertEquals("ab", nextText.toString())
        assertEquals("ab", prev.getText("body").toString())
        assertTrue(renderer.inserts.isEmpty())
        assertTrue(renderer.deletes.isEmpty())
    }

    @Test
    fun diffRendererRejectChangesRemovesOnlySelectedSuggestedInsert() {
        val prev = YDoc(clientId = 1)
        prev.getText("body").insert(0, "a")
        val next = cloneDoc(prev).also { it.clientId = 2 }
        val nextText = next.getText("body")
        nextText.insert(1, "bc")
        val renderer = createDiffRenderer(prev, next)

        renderer.rejectChanges(Id(2, 0))

        assertEquals("a", prev.getText("body").toString())
        assertEquals("ac", nextText.toString())
        assertFalse(renderer.inserts.has(2, 0))
        assertTrue(renderer.inserts.has(2, 1))
        assertTrue(renderer.deletes.isEmpty())
    }

    @Test
    fun diffRendererRejectChangesRestoresOnlySelectedSuggestedDelete() {
        val prev = YDoc(clientId = 1, gc = false)
        prev.getText("body").insert(0, "abc")
        val next = cloneDoc(prev, YDocOptions(clientId = 2, gc = false))
        val nextText = next.getText("body")
        nextText.delete(1, 2)
        val renderer = createDiffRenderer(prev, next)

        renderer.rejectChanges(Id(1, 1))

        assertEquals("abc", prev.getText("body").toString())
        assertEquals("ab", nextText.toString())
        assertFalse(renderer.deletes.has(1, 1))
        assertTrue(renderer.deletes.has(1, 2))
    }

    @Test
    fun diffRendererAcceptChangesIncludesNestedContentForSuggestedTypeInsert() {
        val prev = YDoc(clientId = 1)
        val next = cloneDoc(prev).also { it.clientId = 2 }
        val nextRoot = next.getArray("nodes")
        val nested = next.createText()
        nested.insert(0, "hi")
        nextRoot.push(nested)
        val renderer = createDiffRenderer(prev, next)
        val rootItem = getTypeStructs(nextRoot).single()

        renderer.acceptChanges(rootItem.id)

        val prevRoot = prev.getArray("nodes")
        val acceptedNested = prevRoot.get(0) as YText
        assertEquals("hi", acceptedNested.toString())
        assertTrue(renderer.inserts.isEmpty())
        assertTrue(renderer.deletes.isEmpty())
    }

    @Test
    fun diffRendererTracksNextDocChangesAfterConstruction() {
        val prev = YDoc(clientId = 1)
        prev.getText("body").insert(0, "a")
        val next = cloneDoc(prev).also { it.clientId = 2 }
        val renderer = createDiffRenderer(prev, next)

        assertTrue(renderer.inserts.isEmpty())

        next.getText("body").insert(1, "b")

        assertTrue(renderer.inserts.has(2, 0))
        assertTrue(renderer.hasItem(insertedItem(2, 0)))
    }

    @Test
    fun diffRendererEvictsPrevDocChangesAndEmitsChange() {
        val prev = YDoc(clientId = 1)
        val next = YDoc(clientId = 2)
        next.getText("body").insert(0, "a")
        val renderer = createDiffRenderer(prev, next)
        val events = mutableListOf<RendererEvent>()

        renderer.on("change") { event -> events.add(event) }
        prev.applyUpdate(next.encodeStateAsUpdate(), origin = "accept")

        assertTrue(renderer.inserts.isEmpty())
        assertFalse(renderer.hasItem(insertedItem(2, 0)))
        assertEquals(1, events.size)
        assertTrue(events.single().idSet!!.has(2, 0))
        assertEquals("accept", events.single().origin)
        assertFalse(events.single().local)
    }

    @Test
    fun diffRendererDestroyDetachesDocSubscriptions() {
        val prev = YDoc(clientId = 1)
        val next = cloneDoc(prev).also { it.clientId = 2 }
        val renderer = createDiffRenderer(prev, next)

        renderer.destroy()
        next.getText("body").insert(0, "a")

        assertTrue(renderer.inserts.isEmpty())
        assertFalse(renderer.hasItem(insertedItem(2, 0)))
    }

    @Test
    fun diffRendererDestroysWhenPrevDocIsDestroyed() {
        val prev = YDoc(clientId = 1)
        val next = cloneDoc(prev).also { it.clientId = 2 }
        val renderer = createDiffRenderer(prev, next)
        val events = mutableListOf<RendererEvent>()

        renderer.on("change") { event -> events.add(event) }
        prev.destroy()
        renderer.emit("change")
        next.getText("body").insert(0, "a")

        assertEquals(emptyList(), events)
        assertTrue(renderer.inserts.isEmpty())
        assertFalse(renderer.hasItem(insertedItem(2, 0)))
    }

    @Test
    fun diffRendererDestroysWhenNextDocIsDestroyed() {
        val prev = YDoc(clientId = 1)
        val next = cloneDoc(prev).also { it.clientId = 2 }
        val renderer = createDiffRenderer(prev, next)
        val events = mutableListOf<RendererEvent>()

        renderer.on("change") { event -> events.add(event) }
        next.destroy()
        renderer.emit("change")
        prev.getText("body").insert(0, "a")

        assertEquals(emptyList(), events)
        assertEquals("", next.getText("body").toString())
        assertTrue(renderer.inserts.isEmpty())
    }

    @Test
    fun diffRendererFlowsPrevDocUpdatesIntoNextDoc() {
        val prev = YDoc(clientId = 1)
        val next = YDoc(clientId = 2)
        val renderer = createDiffRenderer(prev, next)

        prev.getText("body").insert(0, "a")

        assertEquals("a", next.getText("body").toString())
        assertTrue(renderer.inserts.isEmpty())
        assertFalse(renderer.hasItem(insertedItem(1, 0)))
    }

    @Test
    fun diffRendererAcceptsNextDocUpdatesWhenSuggestionModeDisabled() {
        val prev = YDoc(clientId = 1)
        val next = YDoc(clientId = 2)
        val renderer = createDiffRenderer(prev, next)

        renderer.suggestionMode = false
        next.getText("body").insert(0, "a")

        assertEquals("a", prev.getText("body").toString())
        assertTrue(renderer.inserts.isEmpty())
        assertFalse(renderer.hasItem(insertedItem(2, 0)))
    }

    @Test
    fun diffRendererSuggestionOriginsFilterAcceptedNextDocUpdates() {
        val prev = YDoc(clientId = 1)
        val next = YDoc(clientId = 2)
        val allowedOrigin = Any()
        val blockedOrigin = Any()
        val renderer = createDiffRenderer(prev, next)

        renderer.suggestionMode = false
        renderer.suggestionOrigins = listOf(allowedOrigin)
        next.transact({ next.getText("body").insert(0, "a") }, origin = blockedOrigin)

        assertEquals("", prev.getText("body").toString())
        assertTrue(renderer.inserts.has(2, 0))
        assertTrue(renderer.hasItem(insertedItem(2, 0)))
    }

    @Test
    fun diffRendererAcceptingModeDeletesRenderedAttributedDeletesFromPrevDoc() {
        val prev = YDoc(clientId = 1, gc = false)
        val prevText = prev.getText("body")
        prevText.insert(0, "abc")
        val next = cloneDoc(prev, YDocOptions(clientId = 2, gc = false))
        val nextText = next.getText("body")
        val renderer = createDiffRenderer(prev, next)
        nextText.useRenderer(renderer)

        nextText.delete(1)
        assertEquals("abc", prevText.toString())
        assertEquals("ac", nextText.toString())
        assertTrue(renderer.deletes.has(1, 1))

        renderer.suggestionMode = false
        nextText.applyDelta(YTextDelta().retain(1).delete(1))

        assertEquals("ac", prevText.toString())
        assertEquals("ac", nextText.toString())
        assertTrue(renderer.deletes.isEmpty())
        assertFalse(renderer.hasItem(item(Id(1, 1), deleted = true, content = ContentString("b"))))
    }

    @Test
    fun diffRendererSuggestionOriginsFilterAttributedDeleteAcceptance() {
        val prev = YDoc(clientId = 1, gc = false)
        val prevText = prev.getText("body")
        prevText.insert(0, "abc")
        val next = cloneDoc(prev, YDocOptions(clientId = 2, gc = false))
        val nextText = next.getText("body")
        val allowedOrigin = Any()
        val blockedOrigin = Any()
        val renderer = createDiffRenderer(prev, next)
        nextText.useRenderer(renderer)
        nextText.delete(1)

        renderer.suggestionMode = false
        renderer.suggestionOrigins = listOf(allowedOrigin)
        nextText.applyDelta(YTextDelta().retain(1).delete(1), origin = blockedOrigin)

        assertEquals("abc", prevText.toString())
        assertTrue(renderer.deletes.has(1, 1))
        assertTrue(renderer.hasItem(item(Id(1, 1), deleted = true, content = ContentString("b"))))
    }

    @Test
    fun rendererEventChannelsMirrorObservableChangeHooks() {
        val prev = YDoc(clientId = 1)
        prev.getText("body").insert(0, "ab")
        val next = cloneDoc(prev).also { it.clientId = 2 }
        next.getText("body").insert(2, "c")
        val renderer = createDiffRenderer(prev, next)
        val events = mutableListOf<RendererEvent>()
        val onceEvents = mutableListOf<RendererEvent>()
        val listener: (RendererEvent) -> Unit = { events.add(it) }

        renderer.on("change", listener)
        renderer.once("change") { event -> onceEvents.add(event) }
        renderer.emit(
            "change",
            RendererEvent(
                name = "change",
                renderer = renderer,
                idSet = createIdSet().also { ids -> ids.add(2, 0, 1) },
                origin = renderer,
                local = true,
            ),
        )

        assertEquals(1, events.size)
        assertEquals(1, onceEvents.size)
        assertSame(renderer, events.single().renderer)
        assertEquals("change", events.single().name)
        assertTrue(events.single().idSet!!.has(2, 0))
        assertSame(renderer, events.single().origin)
        assertTrue(events.single().local)

        renderer.emit("change", RendererEvent("different", baseRenderer))
        assertEquals("change", events.last().name)
        assertSame(renderer, events.last().renderer)

        renderer.off("change", listener)
        renderer.emit("change")
        assertEquals(2, events.size)

        renderer.on("change", listener)
        renderer.destroy()
        renderer.emit("change")
        assertEquals(2, events.size)
    }

    @Test
    fun snapshotRendererRendersDeletedSnapshotContentWhenAttributedBySnapshotDiff() {
        val doc = YDoc(clientId = 1, gc = false)
        val text = doc.getText("body")
        text.insert(0, "abc")
        val beforeDelete = snapshot(doc)
        text.delete(1)
        val afterDelete = snapshot(doc)
        val renderer = createSnapshotRenderer(beforeDelete, afterDelete)
        val deletedStruct = getTypeStructs(text).single { it.id == Id(1, 1) }
        val rendered = mutableListOf<AttributedContent>()

        renderer.readContent(rendered, 1, 1, deleted = true, deletedStruct.content, renderBehavior = 0)

        assertEquals(1L, renderer.contentLength(deletedStruct))
        assertEquals("b", (rendered.single().content as ContentString).str)
        assertTrue(rendered.single().deleted)
        assertEquals(emptyList(), rendered.single().attrs)
    }

    @Test
    fun snapshotRendererPreservesEmptyAttributionForSnapshotTextDiff() {
        val doc = YDoc(clientId = 1, gc = false)
        val text = doc.getText("body")
        text.insert(0, "abcd")
        val beforeEdit = snapshot(doc)
        text.applyDelta(YTextDelta().retain(1).insert("x").delete(1))
        val afterEdit = snapshot(doc)

        val delta = text.toDeltaDeep(createSnapshotRenderer(beforeEdit, afterEdit)).delta

        assertEquals(
            YTextDelta()
                .insert("a")
                .insert("x", mapOf("insert" to emptyList<String>()))
                .insert("b", mapOf("delete" to emptyList<String>()))
                .insert("cd"),
            delta,
        )
    }

    @Test
    fun snapshotRendererClaimsFutureItemsSoTheyCanBeHiddenByReadContent() {
        val doc = YDoc(clientId = 1, gc = false)
        val text = doc.getText("body")
        text.insert(0, "a")
        val beforeFuture = snapshot(doc)
        text.insert(1, "b")
        val future = getTypeStructs(text).single { it.id == Id(1, 1) }
        val renderer = createSnapshotRenderer(beforeFuture)
        val rendered = mutableListOf<AttributedContent>()

        assertTrue(renderer.hasItem(future))

        renderer.readContent(rendered, 1, 1, deleted = false, future.content, renderBehavior = 0)

        assertEquals(emptyList(), rendered)
    }

    private fun item(
        id: Id,
        length: Long = 1,
        deleted: Boolean = false,
        content: AbstractContent,
    ): ItemStruct =
        ItemStruct(id, length, deleted, null, null, "body", null, RootKind.Text, content)

    private fun insertedItem(client: Long, clock: Long): ItemStruct =
        item(Id(client, clock), content = ContentString("x"))
}
