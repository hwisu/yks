package dev.yks

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DeepDeltaTest {
    @Test
    fun arrayDeepDeltaRecursivelyRendersNestedSharedTypesInValuesAndAttrs() {
        val doc = YDoc(clientId = 1)
        val root = doc.getArray("root")
        val nestedMap = doc.createMap()
        val mapText = doc.createText()
        val headerText = doc.createText()
        val childText = doc.createText()

        mapText.insert(0, "hello", mapOf("bold" to true))
        headerText.insert(0, "hello", mapOf("bold" to true))
        childText.insert(0, "hello", mapOf("bold" to true))
        nestedMap.setAttr("body", mapText)
        root.setAttr("header", headerText)
        root.push(listOf("lead", mapOf("child" to nestedMap), childText))

        assertEquals(
            YArrayDeepDelta(
                attrs = mapOf(
                    "header" to YTextDeepDelta(
                        delta = YTextDelta().insert("hello", mapOf("bold" to true)),
                    ),
                ),
                delta = listOf(YArrayDeltaOp(insert = listOf(
                    "lead",
                    mapOf("child" to YMapDeepDelta(
                        attrs = mapOf(
                            "body" to YTextDeepDelta(
                                delta = YTextDelta().insert("hello", mapOf("bold" to true)),
                            ),
                        ),
                    )),
                    YTextDeepDelta(delta = YTextDelta().insert("hello", mapOf("bold" to true))),
                ))),
            ),
            root.toDeltaDeep(),
        )
    }

    @Test
    fun typeDeltaExposesCachedDeepDeltaAndClearCacheForSharedTypes() {
        val doc = YDoc(clientId = 1)
        val root = doc.getArray("root")
        val nestedText = doc.createText()
        nestedText.insert(0, "hello")
        root.push(nestedText)

        assertEquals(
            YArrayDeepDelta(delta = listOf(YArrayDeltaOp(insert = listOf(
                YTextDeepDelta(delta = YTextDelta().insert("hello")),
            )))),
            root.delta,
        )

        nestedText.insert(nestedText.length, "!")

        assertEquals(
            YArrayDeepDelta(delta = listOf(YArrayDeltaOp(insert = listOf(
                YTextDeepDelta(delta = YTextDelta().insert("hello!")),
            )))),
            root.delta,
        )

        root.clearCache()

        assertEquals(root.toDeltaDeep(), root.delta)
    }

    @Test
    fun useRendererChangesDefaultDeepDeltaAndClearsCache() {
        val doc = YDoc(clientId = 1)
        val text = doc.getText("body")
        val renderer = object : BaseRenderer() {
            override val attributed: IdSet = idSet(1, 0, 1)

            override fun readContent(
                contents: MutableList<AttributedContent>,
                client: Long,
                clock: Long,
                deleted: Boolean,
                content: AbstractContent,
                renderBehavior: Int,
            ) {
                if (client == 1L && clock == 0L) return
                super.readContent(contents, client, clock, deleted, content, renderBehavior)
            }
        }
        text.insert(0, "ab")

        assertEquals(YTextDeepDelta(delta = YTextDelta().insert("ab")), text.delta)
        assertSame(text, text.useRenderer(renderer))

        assertEquals(YTextDeepDelta(delta = YTextDelta().insert("b")), text.delta)
        assertEquals(YTextDeepDelta(delta = YTextDelta().insert("b")), text.toDeltaDeep())
        assertEquals(YTextDeepDelta(delta = YTextDelta().insert("ab")), text.toDeltaDeep(baseRenderer))
    }

    @Test
    fun unclaimedRendererItemsUseGenericFastPath() {
        val doc = YDoc(clientId = 1)
        val text = doc.getText("body")
        val renderer = object : BaseRenderer() {
            override fun readContent(
                contents: MutableList<AttributedContent>,
                client: Long,
                clock: Long,
                deleted: Boolean,
                content: AbstractContent,
                renderBehavior: Int,
            ) {
                // This would hide the whole item if the renderer were consulted.
            }
        }
        text.insert(0, "ab")

        text.useRenderer(renderer)

        assertEquals(YTextDeepDelta(delta = YTextDelta().insert("ab")), text.delta)
    }

    @Test
    fun useRendererEmitsDeltaEventWhenRenderedStateChanges() {
        val doc = YDoc(clientId = 1)
        val text = doc.getText("body")
        val renderer = object : BaseRenderer() {
            override val attributed: IdSet = idSet(1, 0, 1)

            override fun readContent(
                contents: MutableList<AttributedContent>,
                client: Long,
                clock: Long,
                deleted: Boolean,
                content: AbstractContent,
                renderBehavior: Int,
            ) {
                if (client == 1L && clock == 0L) return
                super.readContent(contents, client, clock, deleted, content, renderBehavior)
            }
        }
        val events = mutableListOf<YTypeEvent>()
        text.insert(0, "ab")
        text.on("delta") { event -> events.add(event) }

        text.useRenderer(renderer)
        text.useRenderer(renderer)

        assertEquals(1, events.size)
        assertSame(text, events.single().target)
        assertEquals(null, events.single().origin)
        assertEquals(null, events.single().transaction)
        assertEquals(null, events.single().yEvent)
        assertEquals(YTextDeepDelta(delta = YTextDelta().insert("b")), events.single().delta)
    }

    @Test
    fun activeRendererChangeEventsRefreshMaintainedDeltaAndEmitFocusedRetainChange() {
        val doc = YDoc(clientId = 1)
        val text = doc.getText("body")
        val author = createContentAttribute("insert", "alice")
        val renderer = TwosetRenderer(
            inserts = createIdMap().also { ids -> ids.add(1, 0, 1, listOf(author)) },
            deletes = createIdMap(),
        )
        val events = mutableListOf<YTypeEvent>()
        text.insert(0, "a")
        text.useRenderer(renderer)
        text.on("delta") { event -> events.add(event) }

        assertEquals(
            YTextDeepDelta(delta = YTextDelta().insert("a", mapOf("insert" to listOf("alice")))),
            text.delta,
        )

        renderer.inserts = createIdMap()
        renderer.emit(RendererEvent(name = "change", renderer = renderer, idSet = idSet(1, 0, 1), origin = "accept"))

        assertEquals(YTextDeepDelta(delta = YTextDelta().insert("a")), text.delta)
        assertEquals(1, events.size)
        assertSame(text, events.single().target)
        assertEquals("accept", events.single().origin)
        assertEquals(YTextDeepDelta(delta = YTextDelta().retain(1)), events.single().delta)
        assertEquals(null, events.single().transaction)
        assertEquals(null, events.single().yEvent)
    }

    @Test
    fun typeDestroyDetachesActiveRendererAndClearsRenderedDeltaCache() {
        val doc = YDoc(clientId = 1)
        val text = doc.getText("body")
        val author = createContentAttribute("insert", "alice")
        val renderer = TwosetRenderer(
            inserts = createIdMap().also { ids -> ids.add(1, 0, 1, listOf(author)) },
            deletes = createIdMap(),
        )
        text.insert(0, "a")
        text.useRenderer(renderer)
        assertEquals(
            YTextDeepDelta(delta = YTextDelta().insert("a", mapOf("insert" to listOf("alice")))),
            text.delta,
        )

        text.destroy()

        assertEquals(YTextDeepDelta(delta = YTextDelta().insert("a")), text.delta)
        assertEquals(YTextDeepDelta(delta = YTextDelta().insert("a")), text.toDeltaDeep())
    }

    @Test
    fun textDeepDeltaRecursivelyRendersEmbedsAndFormattingAttrs() {
        val doc = YDoc(clientId = 1)
        val text = doc.getText("body")
        val nestedMap = doc.createMap()
        val attrArray = doc.createArray()

        nestedMap.setAttr("name", "Ada")
        attrArray.push("tag")
        text.setAttr("ref", attrArray)
        text.insert(0, "a")
        text.insertEmbed(1, mapOf("node" to nestedMap), mapOf("style" to attrArray))

        assertEquals(
            YTextDeepDelta(
                attrs = mapOf(
                    "ref" to YArrayDeepDelta(delta = listOf(YArrayDeltaOp(insert = listOf("tag")))),
                ),
                delta = YTextDelta()
                    .insert("a")
                    .insertEmbed(
                        mapOf("node" to YMapDeepDelta(attrs = mapOf("name" to "Ada"))),
                        mapOf("style" to YArrayDeepDelta(delta = listOf(YArrayDeltaOp(insert = listOf("tag"))))),
                    ),
            ),
            text.toDeltaDeep(),
        )
    }

    @Test
    fun xmlDeepDeltaRecursivelyRendersElementsChildrenAndAttrs() {
        val doc = YDoc(clientId = 1)
        val fragment = doc.getXmlFragment("xml")
        val paragraph = YXmlElement("p").also { element ->
            element.setAttrs(mapOf("class" to "intro", "order" to 1))
            element.push(YXmlText("hello"), YXmlElement("br"))
        }

        fragment.setAttr("kind", "doc")
        fragment.push(paragraph)

        assertEquals(
            YXmlFragmentDeepDelta(
                attrs = mapOf("kind" to "doc"),
                delta = listOf(YArrayDeltaOp(insert = listOf(
                    YXmlElementDeepDelta(
                        nodeName = "p",
                        attrs = mapOf("class" to "intro", "order" to 1L),
                        children = listOf(
                            "hello",
                            YXmlElementDeepDelta(nodeName = "br"),
                        ),
                    ),
                ))),
            ),
            fragment.toDeltaDeep(),
        )
    }

    @Test
    fun xmlFragmentDeepDeltaPreservesFormattedStaticTextLikeUpstream() {
        val source = YDoc(clientId = 1)
        val target = YDoc(clientId = 2)
        val sourceXml = source.getXmlFragment("xml")
        val emphasized = mapOf<String, Any?>("em" to emptyMap<String, Any?>())
        val emphasizedStrong = mapOf<String, Any?>(
            "em" to emptyMap<String, Any?>(),
            "strong" to emptyMap<String, Any?>(),
        )
        val formattedDelta = listOf(
            YArrayDeltaOp(insert = listOf("A"), attributes = emphasizedStrong),
            YArrayDeltaOp(insert = listOf("B"), attributes = emphasized),
            YArrayDeltaOp(insert = listOf("C"), attributes = emphasizedStrong),
        )
        val expected = YXmlFragmentDeepDelta(delta = formattedDelta)

        sourceXml.applyDelta(formattedDelta)
        target.getXmlFragment("xml").applyDeltaDeep(sourceXml.toDeltaDeep())

        assertEquals(expected, sourceXml.toDeltaDeep())
        assertEquals(formattedDelta, target.getXmlFragment("xml").toDelta())
        assertEquals(expected, target.getXmlFragment("xml").toDeltaDeep())
    }

    @Test
    fun xmlElementDeepDeltaPreservesFormattedStaticTextLikeUpstream() {
        val source = YDoc(clientId = 1)
        val target = YDoc(clientId = 2)
        val sourceXml = source.getXmlFragment("xml")
        val emphasized = mapOf<String, Any?>("em" to emptyMap<String, Any?>())
        val emphasizedStrong = mapOf<String, Any?>(
            "em" to emptyMap<String, Any?>(),
            "strong" to emptyMap<String, Any?>(),
        )
        val formattedDelta = listOf(
            YArrayDeltaOp(insert = listOf("A"), attributes = emphasizedStrong),
            YArrayDeltaOp(insert = listOf("B"), attributes = emphasized),
            YArrayDeltaOp(insert = listOf("C"), attributes = emphasizedStrong),
        )
        val expectedParagraph = YXmlElementDeepDelta(
            nodeName = "p",
            children = listOf(
                YAttributeDelta("A", emphasizedStrong),
                YAttributeDelta("B", emphasized),
                YAttributeDelta("C", emphasizedStrong),
            ),
        )
        val paragraph = YXmlElement("p").also { it.applyDelta(formattedDelta) }

        sourceXml.push(paragraph)
        target.getXmlFragment("xml").applyDeltaDeep(sourceXml.toDeltaDeep())

        val targetParagraph = target.getXmlFragment("xml").getType(0) as YXmlElementType
        assertEquals(expectedParagraph, paragraph.toDeltaDeep())
        assertEquals(
            YXmlFragmentDeepDelta(delta = listOf(YArrayDeltaOp(insert = listOf(expectedParagraph)))),
            sourceXml.toDeltaDeep(),
        )
        assertEquals(formattedDelta, targetParagraph.toDelta())
        assertEquals(expectedParagraph, targetParagraph.toDeltaDeep())
    }

    @Test
    fun arrayDeepDeltaCanBeAppliedIntoAnotherDocument() {
        val source = YDoc(clientId = 1)
        val target = YDoc(clientId = 2)
        val sourceRoot = source.getArray("root")
        val mapText = source.createText()
        val childText = source.createText()
        val nestedMap = source.createMap()
        mapText.insert(0, "hello")
        childText.insert(0, "hello")
        nestedMap.setAttr("body", mapText)
        sourceRoot.setAttr("meta", nestedMap)
        sourceRoot.push(mapOf("child" to childText))

        val targetRoot = target.getArray("root")
        targetRoot.applyDeltaDeep(sourceRoot.toDeltaDeep())

        assertEquals(sourceRoot.toJson(), targetRoot.toJson())
        assertEquals(sourceRoot.toDeltaDeep(), targetRoot.toDeltaDeep())
        val targetChild = (targetRoot.get(0) as Map<*, *>)["child"] as YText
        targetChild.insert(targetChild.length, "!")
        assertEquals("hello", childText.toString())
        assertEquals("hello!", targetChild.toString())
    }

    @Test
    fun textDeepDeltaCanBeAppliedIntoAnotherDocument() {
        val source = YDoc(clientId = 1)
        val target = YDoc(clientId = 2)
        val sourceText = source.getText("body")
        val attrMap = source.createMap()
        val embedMap = source.createMap()
        attrMap.setAttr("name", "Ada")
        embedMap.setAttr("name", "Ada")
        sourceText.setAttr("meta", attrMap)
        sourceText.insert(0, "Hi ")
        sourceText.insertEmbed(3, mapOf("mention" to embedMap))

        val targetText = target.getText("body")
        targetText.applyDeltaDeep(sourceText.toDeltaDeep())

        assertEquals(sourceText.toDeltaDeep(), targetText.toDeltaDeep())
    }

    @Test
    fun xmlDeepDeltaCanBeAppliedIntoAnotherDocument() {
        val source = YDoc(clientId = 1)
        val target = YDoc(clientId = 2)
        val sourceXml = source.getXmlFragment("xml")
        sourceXml.setAttr("kind", "doc")
        sourceXml.push(YXmlElement("p").also { element ->
            element.setAttr("class", "intro")
            element.push(YXmlText("hello"))
        })

        val targetXml = target.getXmlFragment("xml")
        targetXml.applyDeltaDeep(sourceXml.toDeltaDeep())

        assertEquals(sourceXml.toString(), targetXml.toString())
        assertEquals(sourceXml.getAttrs(), targetXml.getAttrs())
        assertEquals(
            YXmlElement("p").also {
                it.setAttr("class", "intro")
                it.push(YXmlText("hello"))
            }.toDeltaDeep(),
            (targetXml.get(0) as YXmlElementType).toDeltaDeep(),
        )
    }

    @Test
    fun liveXmlDeepDeltaAppliesAsLiveNestedXmlAndTextTypes() {
        val source = YDoc(clientId = 1)
        val target = YDoc(clientId = 2)
        val sourceXml = source.getXmlFragment("xml")
        val sourceParagraph = source.createXmlElement("p").setAttrs(mapOf("class" to "intro"))
        val sourceText = source.createText()
        sourceText.insert(0, "hello")
        sourceText.format(0, 5, mapOf("bold" to true))
        sourceParagraph.push(sourceText)
        sourceXml.push(sourceParagraph)

        val targetXml = target.getXmlFragment("xml")
        targetXml.applyDeltaDeep(sourceXml.toDeltaDeep())
        val targetParagraph = targetXml.getType(0) as YXmlElementType
        val targetText = targetParagraph.getType(0) as YText
        targetText.insert(targetText.length, "!")

        assertTrue(targetText is YXmlTextType)
        assertEquals("<p class=\"intro\">hello</p>", sourceXml.toString())
        assertEquals("<p class=\"intro\"><bold>hello</bold>!</p>", targetXml.toString())
        assertEquals(
            YTextDelta().insert("hello", mapOf("bold" to true)).insert("!"),
            targetText.toDelta(),
        )
        assertEquals(
            YTextDelta().insert("hello", mapOf("bold" to true)),
            sourceText.toDelta(),
        )
    }

    @Test
    fun liveXmlElementApplyDeltaDeepRebuildsAttributesAndLiveChildren() {
        val doc = YDoc(clientId = 1)
        val element = doc.createXmlElement("p")
        element.setAttr("stale", true)
        element.push(YXmlText("old"))

        element.applyDeltaDeep(
            YXmlElementDeepDelta(
                nodeName = "p",
                attrs = mapOf("class" to "intro"),
                children = listOf(
                    YTextDeepDelta(delta = YTextDelta().insert("hello", mapOf("bold" to true))),
                    YXmlElementDeepDelta(nodeName = "br"),
                ),
            ),
        )

        val text = element.getType(0) as YText

        assertEquals("<p class=\"intro\"><bold>hello</bold><br></br></p>", element.toString())
        assertEquals(YTextDelta().insert("hello", mapOf("bold" to true)), text.toDelta())
        assertTrue(element.getType(1) is YXmlElementType)
        assertFalse(element.hasAttr("stale"))
    }

    @Test
    fun documentDeepDeltaCanBeAppliedIntoAnotherDocument() {
        val source = YDoc(clientId = 1)
        val target = YDoc(clientId = 2)
        val itemText = source.createText()
        val mapText = source.createText()
        itemText.insert(0, "nested")
        mapText.insert(0, "nested")
        source.getArray("items").push(listOf("a", itemText))
        source.getMap("meta").setAttrs(mapOf("title" to "doc", "body" to mapText))
        source.getText("body").insert(0, "hello", mapOf("bold" to true))
        source.getXmlFragment("xml").push(YXmlElement("p").also { it.push(YXmlText("hello")) })

        target.applyDeltaDeep(source.toDeltaDeep())

        assertEquals(source.toDeltaDeep(), target.toDeltaDeep())
        assertEquals(source.toJson(), target.toJson())

        val targetNested = target.getArray("items").get(1) as YText
        targetNested.insert(targetNested.length, "!")
        assertEquals("nested", itemText.toString())
        assertEquals("nested!", targetNested.toString())
    }

    @Test
    fun applyDeltaDeepPreservesOriginOnDocumentsAndSharedTypes() {
        val source = YDoc(clientId = 1)
        source.getArray("items").push("a")
        val target = YDoc(clientId = 2)
        val origins = mutableListOf<Any?>()
        target.observeAfterTransactions { event -> origins.add(event.origin) }

        target.applyDeltaDeep(source.toDeltaDeep(), origin = "doc-deep")
        target.getArray("items").applyDeltaDeep(
            YArrayDeepDelta(delta = listOf(YArrayDeltaOp(insert = listOf("b")))),
            origin = "array-deep",
        )

        assertEquals(listOf<Any?>("doc-deep", "array-deep"), origins)
        assertEquals(listOf("b"), target.getArray("items").toArray())
    }

    @Test
    fun documentDeepDeltaReplacesExistingRootStateForIncludedRoots() {
        val source = YDoc(clientId = 1)
        val target = YDoc(clientId = 2)
        source.getArray("items").setAttr("role", "source")
        source.getArray("items").push("fresh")
        target.getArray("items").setAttr("role", "stale")
        target.getArray("items").push("old")

        target.applyDeltaDeep(source.toDeltaDeep())

        assertEquals(source.getArray("items").toDeltaDeep(), target.getArray("items").toDeltaDeep())
    }

    @Test
    fun diffDocsToDeltaIncludesOnlyChangedRoots() {
        val previous = YDoc(clientId = 1)
        previous.getArray("items").push("a")
        previous.getMap("meta").setAttr("title", "same")
        val next = cloneDoc(previous).also { it.clientId = 2 }

        next.getArray("items").push("b")

        val diff = diffDocsToDelta(previous, next)

        assertEquals(setOf("items"), diff.roots.keys)
        assertEquals(
            YArrayDeepDelta(delta = listOf(
                YArrayDeltaOp(retain = 1),
                YArrayDeltaOp(insert = listOf("b"), attributes = mapOf("insert" to emptyList<String>())),
            )),
            diff.roots["items"],
        )
    }

    @Test
    fun diffDocsToDeltaUsesRendererAttributions() {
        val previous = YDoc(clientId = 1)
        val next = cloneDoc(previous).also { it.clientId = 2 }
        val author = createContentAttribute("insert", "alice")
        val attributions = Attributions(
            inserts = createIdMap().also { ids -> ids.add(2, 0, 1, listOf(author)) },
        )

        next.getText("body").insert(0, "a")
        val diff = diffDocsToDelta(
            previous,
            next,
            renderer = createDiffRenderer(previous, next, DiffRendererOptions(attrs = attributions)),
        )

        assertEquals(
            YTextDeepDelta(delta = YTextDelta().insert("a", mapOf("insert" to listOf("alice")))),
            diff.roots["body"],
        )
    }

    @Test
    fun diffDocsToDeltaRendersOnlyModifiedAttrsAndNestedChanges() {
        val previous = YDoc(clientId = 1)
        previous.getText("text").insert(0, "hello")
        previous.getArray("array").setAttr("unchanged", "keep")
        previous.getArray("array").push(listOf(1, 2, 3))
        val nested = previous.createArray()
        previous.getMap("map").setAttr("k", 42)
        previous.getMap("map").setAttr("nested", nested)
        val next = cloneDoc(previous).also { it.clientId = 2 }

        next.getText("text").insert(5, " world")
        next.getArray("array").insert(1, listOf("x"))
        next.getMap("map").setAttr("newk", 42)
        (next.getMap("map").getAttr("nested") as YArray).insert(0, listOf(1))

        val diff = diffDocsToDelta(previous, next)

        assertEquals(
            YTextDeepDelta(
                delta = YTextDelta()
                    .retain(5)
                    .insert(" world", mapOf("insert" to emptyList<String>())),
            ),
            diff.roots["text"],
        )
        assertEquals(
            YArrayDeepDelta(
                delta = listOf(
                    YArrayDeltaOp(retain = 1),
                    YArrayDeltaOp(insert = listOf("x"), attributes = mapOf("insert" to emptyList<String>())),
                    YArrayDeltaOp(retain = 2),
                ),
            ),
            diff.roots["array"],
        )
        assertEquals(
            YMapDeepDelta(
                attrs = mapOf(
                    "nested" to YArrayDeepDelta(delta = listOf(
                        YArrayDeltaOp(insert = listOf(1L), attributes = mapOf("insert" to emptyList<String>())),
                    )),
                    "newk" to YAttributeDelta(42L, mapOf("insert" to emptyList<String>())),
                ),
            ),
            diff.roots["map"],
        )
    }

    @Test
    fun diffDocsToDeltaRendersTypeAttrInsertAttributions() {
        val previous = YDoc(clientId = 1)
        val next = cloneDoc(previous).also { it.clientId = 2 }
        val author = createContentAttribute("insert", "alice")
        val attributions = Attributions(
            inserts = createIdMap().also { ids -> ids.add(2, 0, 1, listOf(author)) },
        )

        next.getMap("meta").setAttr("title", "hello")
        val diff = diffDocsToDelta(
            previous,
            next,
            renderer = createDiffRenderer(previous, next, DiffRendererOptions(attrs = attributions)),
        )

        assertEquals(
            YMapDeepDelta(attrs = mapOf(
                "title" to YAttributeDelta("hello", mapOf("insert" to listOf("alice"))),
            )),
            diff.roots["meta"],
        )
    }

    @Test
    fun diffDocsToDeltaRendersDeletedXmlTypeAttrsWithDeleteAttribution() {
        val previous = YDoc(clientId = 1, gc = false)
        previous.getXmlFragment("xml").setAttr("id", "C")
        val next = cloneDoc(previous).also { it.clientId = 2; it.gc = false }

        next.getXmlFragment("xml").deleteAttr("id")
        val diff = diffDocsToDelta(previous, next)

        assertEquals(
            YXmlFragmentDeepDelta(attrs = mapOf(
                "id" to YAttributeDelta("C", mapOf("delete" to emptyList<String>())),
            )),
            diff.roots["xml"],
        )
    }

    @Test
    fun diffRendererXmlCascadeDeleteDoesNotEmitStructuralDeleteOps() {
        val previous = YDoc(clientId = 1, gc = false)
        val child = previous.createXmlElement("item")
        child.setAttr("id", "C")
        child.push(YXmlText("hello"))
        previous.getXmlFragment("frag").push(child)
        val next = cloneDoc(previous, YDocOptions(clientId = 2, gc = false))

        next.getXmlFragment("frag").delete(0)
        val rendered = next.getXmlFragment("frag").toDeltaDeep(createDiffRenderer(previous, next))

        assertEquals(
            YXmlFragmentDeepDelta(delta = listOf(
                YArrayDeltaOp(
                    insert = listOf(
                        YXmlElementDeepDelta(
                            nodeName = "item",
                            attrs = mapOf("id" to YAttributeDelta("C", mapOf("delete" to emptyList<String>()))),
                            children = listOf("hello"),
                        ),
                    ),
                    attributes = mapOf("delete" to emptyList<String>()),
                ),
            )),
            rendered,
        )
        assertFalse(rendered.hasDeepDeleteOp())
    }

    @Test
    fun mapDeepDeltaRendersAttributedContentLikeUpstream() {
        val doc = YDoc(clientId = 1, gc = false)
        val map = doc.getMap("")
        var renderer: AbstractRenderer = baseRenderer
        doc.observeAfterTransactions { event ->
            renderer = TwosetRenderer(
                inserts = createIdMapFromIdSet(event.insertSet, emptyList()),
                deletes = createIdMapFromIdSet(event.deleteIdSet, emptyList()),
            )
        }

        map.setAttr("test", 42)
        assertEquals(
            YMapDeepDelta(attrs = mapOf(
                "test" to YAttributeDelta(42L, mapOf("insert" to emptyList<String>())),
            )),
            map.toDeltaDeep(renderer),
        )

        map.setAttr("test", "fourtytwo")
        assertEquals(
            YMapDeepDelta(attrs = mapOf(
                "test" to YAttributeDelta("fourtytwo", mapOf("insert" to emptyList<String>())),
            )),
            map.toDeltaDeep(renderer),
        )

        map.deleteAttr("test")
        assertEquals(
            YMapDeepDelta(attrs = mapOf(
                "test" to YAttributeDelta("fourtytwo", mapOf("delete" to emptyList<String>())),
            )),
            map.toDeltaDeep(renderer),
        )
    }

    @Test
    fun textDeepDeltaRendersAttributedInsertDeleteAndFormatContentLikeUpstream() {
        val doc = YDoc(clientId = 1, gc = false)
        val text = doc.getText("")
        text.insert(0, "Hello World!")
        var renderer: AbstractRenderer = baseRenderer
        doc.observeAfterTransactions { event ->
            renderer = TwosetRenderer(
                inserts = createIdMapFromIdSet(event.insertSet, emptyList()),
                deletes = createIdMapFromIdSet(event.deleteIdSet, emptyList()),
            )
        }

        text.applyDelta(
            YTextDelta()
                .retain(4, mapOf("italic" to true))
                .retain(2)
                .delete(5)
                .insert("attributions"),
        )

        assertEquals(
            YTextDeepDelta(
                delta = YTextDelta()
                    .insert("Hell", mapOf("italic" to true, "format" to mapOf("italic" to emptyList<String>())))
                    .insert("o ")
                    .insert("World", mapOf("delete" to emptyList<String>()))
                    .insert("attributions", mapOf("insert" to emptyList<String>()))
                    .insert("!"),
            ),
            text.toDeltaDeep(renderer),
        )

        text.applyDelta(YTextDelta().retain(5, mapOf("italic" to null)))

        assertEquals(
            YTextDeepDelta(
                delta = YTextDelta()
                    .insert("Hell", mapOf("format" to mapOf("italic" to emptyList<String>())))
                    .insert("o attributions!"),
            ),
            text.toDeltaDeep(renderer),
        )
    }

    @Test
    fun textDeepDeltaRendersNamedAttributionsFromDiffSetsLikeUpstream() {
        val previous = YDoc(clientId = 0, gc = false)
        previous.getText("").insert(0, "Hello World!")
        val next = YDoc(clientId = 1, gc = false)
        next.applyUpdate(previous.encodeStateAsUpdate())
        val text = next.getText("")

        text.applyDelta(
            YTextDelta()
                .retain(4, mapOf("italic" to true))
                .retain(2)
                .delete(5)
                .insert("attributions"),
        )

        val insertDiff = diffIdSet(
            createInsertSetFromDoc(next, filterDeleted = false),
            createInsertSetFromDoc(previous, filterDeleted = false),
        )
        val deleteDiff = diffIdSet(createDeleteSetFromDoc(next), createDeleteSetFromDoc(previous))
        val attributions = Attributions(
            inserts = createIdMapFromIdSet(insertDiff, listOf(createContentAttribute("insert", "Bob"))),
            deletes = createIdMapFromIdSet(deleteDiff, listOf(createContentAttribute("delete", "Bob"))),
        )
        val renderer = TwosetRenderer(attributions.inserts, attributions.deletes)

        assertEquals(
            YTextDeepDelta(
                delta = YTextDelta()
                    .insert("Hell", mapOf("italic" to true, "format" to mapOf("italic" to listOf("Bob"))))
                    .insert("o ")
                    .insert("World", mapOf("delete" to listOf("Bob")))
                    .insert("attributions", mapOf("insert" to listOf("Bob")))
                    .insert("!"),
            ),
            text.toDeltaDeep(renderer),
        )
    }

    @Test
    fun applyDeltaDeepUnwrapsAttributedTypeAttrs() {
        val doc = YDoc(clientId = 1)

        doc.applyDeltaDeep(
            YDocDeepDelta(
                roots = mapOf(
                    "meta" to YMapDeepDelta(attrs = mapOf(
                        "title" to YAttributeDelta("hello", mapOf("insert" to listOf("alice"))),
                    )),
                ),
            ),
        )

        assertEquals("hello", doc.getMap("meta").getAttr("title"))
    }

    @Test
    fun diffDocsToDeltaRendersArraySequenceAttributions() {
        val previous = YDoc(clientId = 1)
        val next = cloneDoc(previous).also { it.clientId = 2 }
        val author = createContentAttribute("insert", "alice")
        val attributions = Attributions(
            inserts = createIdMap().also { ids -> ids.add(2, 0, 1, listOf(author)) },
        )

        next.getArray("items").push("a")
        val diff = diffDocsToDelta(
            previous,
            next,
            renderer = createDiffRenderer(previous, next, DiffRendererOptions(attrs = attributions)),
        )

        assertEquals(
            YArrayDeepDelta(delta = listOf(
                YArrayDeltaOp(insert = listOf("a"), attributes = mapOf("insert" to listOf("alice"))),
            )),
            diff.roots["items"],
        )
    }

    @Test
    fun diffDocsToDeltaRendersArraySequenceDeleteAttributions() {
        val previous = YDoc(clientId = 1, gc = false)
        previous.getArray("items").push("a")
        val next = cloneDoc(previous).also { it.clientId = 2; it.gc = false }

        next.getArray("items").delete(0)
        val diff = diffDocsToDelta(previous, next)

        assertEquals(
            YArrayDeepDelta(delta = listOf(
                YArrayDeltaOp(retain = 1, attributes = mapOf("delete" to emptyList<String>())),
            )),
            diff.roots["items"],
        )
    }

    @Test
    fun diffDocsToDeltaRendersXmlSequenceAttributions() {
        val previous = YDoc(clientId = 1)
        val next = cloneDoc(previous).also { it.clientId = 2 }
        val author = createContentAttribute("insert", "alice")
        val attributions = Attributions(
            inserts = createIdMap().also { ids -> ids.add(2, 0, 1, listOf(author)) },
        )

        next.getXmlFragment("xml").push(YXmlText("x"))
        val diff = diffDocsToDelta(
            previous,
            next,
            renderer = createDiffRenderer(previous, next, DiffRendererOptions(attrs = attributions)),
        )

        assertEquals(
            YXmlFragmentDeepDelta(delta = listOf(
                YArrayDeltaOp(insert = listOf("x"), attributes = mapOf("insert" to listOf("alice"))),
            )),
            diff.roots["xml"],
        )
    }

    private fun idSet(vararg triples: Long): IdSet {
        require(triples.size % 3 == 0)
        val ids = createIdSet()
        triples.asList().chunked(3).forEach { (client, clock, len) -> ids.add(client, clock, len) }
        return ids
    }

    private fun Any?.hasDeepDeleteOp(): Boolean = when (this) {
        is YDocDeepDelta -> roots.values.any { it.hasDeepDeleteOp() }
        is YArrayDeepDelta -> attrs.values.any { it.hasDeepDeleteOp() } || delta.any { it.hasDeepDeleteOp() }
        is YTextDeepDelta -> attrs.values.any { it.hasDeepDeleteOp() } || delta.ops.any { it.hasDeepDeleteOp() }
        is YMapDeepDelta -> attrs.values.any { it.hasDeepDeleteOp() }
        is YXmlFragmentDeepDelta -> attrs.values.any { it.hasDeepDeleteOp() } || delta.any { it.hasDeepDeleteOp() }
        is YXmlElementDeepDelta -> attrs.values.any { it.hasDeepDeleteOp() } || children.any { it.hasDeepDeleteOp() }
        is YAttributeDelta -> value.hasDeepDeleteOp() || attributes.values.any { it.hasDeepDeleteOp() }
        is YArrayDeltaOp -> delete != null ||
            attributes.values.any { it.hasDeepDeleteOp() } ||
            insert.orEmpty().any { it.hasDeepDeleteOp() }
        is YTextDeltaOp -> delete != null ||
            attributes.values.any { it.hasDeepDeleteOp() } ||
            insert.hasDeepDeleteOp()
        is Map<*, *> -> values.any { it.hasDeepDeleteOp() }
        is Iterable<*> -> any { it.hasDeepDeleteOp() }
        is Array<*> -> any { it.hasDeepDeleteOp() }
        else -> false
    }
}
