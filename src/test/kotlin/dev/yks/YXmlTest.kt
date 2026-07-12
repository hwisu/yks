package dev.yks

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class YXmlTest {
    @Test
    fun xmlFragmentRendersElementsTextAndAttributes() {
        val doc = YDoc(clientId = 1)
        val fragment = doc.getXmlFragment("xml")
        val paragraph = YXmlElement("p")
        paragraph.setAttr("class", "intro")
        paragraph.push(listOf(YXmlText("Hello <world> & friends")))

        fragment.push(listOf(paragraph, YXmlText("tail")))

        assertEquals(2, fragment.length)
        assertEquals("<p class=\"intro\">Hello <world> & friends</p>tail", fragment.toString())
        assertEquals(
            listOf(
                mapOf(
                    "nodeName" to "p",
                    "attributes" to mapOf("class" to "intro"),
                    "children" to listOf("Hello <world> & friends"),
                ),
                "tail",
            ),
            fragment.toJson(),
        )
        assertEquals(
            mapOf(
                "name" to "p",
                "children" to listOf("Hello <world> & friends"),
                "attrs" to mapOf("class" to "intro"),
            ),
            paragraph.toJSON(),
        )
        assertEquals("tail", YXmlText("tail").toJSON())
    }

    @Test
    fun xmlTextStringRenderingMatchesUpstreamFormattingTags() {
        val text = YXmlText(
            "abc",
            mapOf("strong" to mapOf("level" to "1"), "em" to true),
        )

        assertEquals("<em><strong level=\"1\">abc</strong></em>", text.toString())
        assertEquals("<p></p>", YXmlElement("p").toString())
    }

    @Test
    fun xmlStringRenderingSupportsForceTagAndFragmentAttributes() {
        val doc = YDoc(clientId = 1)
        val fragment = doc.getXmlFragment("xml")
        val paragraph = YXmlElement("p")
        fragment.push(paragraph)

        assertEquals("<p></p>", paragraph.toString(forceTag = true))
        assertEquals("<p></p>", fragment.toString())
        assertEquals("<xml><p></p></xml>", fragment.toString(forceTag = true))

        fragment.setAttrs(mapOf("title" to "A&B\"", "lang" to "en", "count" to 2, "enabled" to true))

        assertEquals("<xml count=\"2\" enabled=\"true\" lang=\"en\" title=\"A&B\"\"><p></p></xml>", fragment.toString())
        assertEquals(
            "<xml count=\"2\" enabled=\"true\" lang=\"en\" title=\"A&B\"\"><p></p></xml>",
            fragment.toString(forceTag = true),
        )
    }

    @Test
    fun xmlElementRenderingLowercasesTagsAndUsesJavaScriptAttributeCoercion() {
        val static = YXmlElement("DiV").setAttrs(
            mapOf(
                "array" to listOf("a", null, true),
                "bytes" to byteArrayOf(1, -1),
                "data" to mapOf("id" to 1),
                "nil" to null,
                "zero" to -0.0,
            ),
        )
        val doc = YDoc(clientId = 1)
        val fragment = doc.getXmlFragment("xml")
        val live = doc.createXmlElement("SECTION").setAttrs(mapOf("count" to 2.0, "enabled" to true))
        fragment.push(live)

        assertTrue(static.hasAttribute("nil"))
        assertEquals(
            "<div array=\"a,,true\" bytes=\"1,255\" data=\"[object Object]\" nil=\"null\" zero=\"0\"></div>",
            static.toString(),
        )
        assertEquals("<section count=\"2\" enabled=\"true\"></section>", live.toString())
        assertEquals("<section count=\"2\" enabled=\"true\"></section>", live.toJSON())
        assertEquals("<section count=\"2\" enabled=\"true\"></section>", fragment.toJSON())
    }

    @Test
    fun emptyXmlFragmentsCanRenderForcedTags() {
        val fragment = YDoc(clientId = 1).getXmlFragment("xml")
        val emptyName = YXmlFragment.from(emptyList(), YDoc(clientId = 1), name = "")

        assertEquals("", fragment.toString())
        assertEquals("<xml />", fragment.toString(forceTag = true))
        assertEquals("< />", emptyName.toString(forceTag = true))
    }

    @Test
    fun xmlElementAttributesAndChildrenAreDefensiveCopies() {
        val element = YXmlElement("a")
        element.setAttribute("href", "https://example.com?a=1&b=2")
        element.setAttrs(mapOf("title" to "Example", "count" to 2))
        element.push(listOf(YXmlText("link")))

        val child = element.get(0) as YXmlText
        assertEquals("link", child.toJson())
        assertTrue(element.hasAttribute("href"))
        assertEquals("https://example.com?a=1&b=2", element.getAttribute("href"))
        assertEquals(element.getAttrs(), element.getAttributes())
        assertEquals(3, element.attrSize)
        assertEquals(setOf("count", "href", "title"), element.attrKeys())
        assertEquals(listOf(2L, "https://example.com?a=1&b=2", "Example"), element.attrValues().toList())
        assertEquals(
            listOf("count:2", "href:https://example.com?a=1&b=2", "title:Example"),
            element.mapAttrs { value, key -> "$key:$value" },
        )
        assertEquals(
            listOf("count:2", "href:https://example.com?a=1&b=2", "title:Example"),
            buildList { element.forEachAttr { value, key -> add("$key:$value") } },
        )

        element.removeAttribute("href")
        assertFalse(element.hasAttr("href"))
        element.setAttr("title", "example")
        element.deleteAttribute("title")
        assertFalse(element.hasAttr("title"))
        element.clearAttrs()
        assertEquals(0, element.attrSize)
    }

    @Test
    fun staticXmlElementsExposeFirstChildAndTreeWalker() {
        val element = YXmlElement("section")
        val paragraph = YXmlElement("p").also { it.push(YXmlText("body")) }
        element.push(YXmlText("head"), paragraph, YXmlElement("footer"))

        assertEquals("head", element.firstChild?.toJson())
        assertEquals(
            listOf("head", "p", "body", "footer"),
            element.createTreeWalker().map { node ->
                when (node) {
                    is YXmlElement -> node.nodeName
                    is YXmlText -> node.toJson()
                }
            }.toList(),
        )
        assertEquals(listOf("p", "footer"), element.createTreeWalker { it is YXmlElement }.map {
            (it as YXmlElement).nodeName
        }.toList())
    }

    @Test
    fun xmlElementSliceReturnsDefensiveCopies() {
        val element = YXmlElement("div")
        element.push(listOf(YXmlElement("a"), YXmlElement("b"), YXmlElement("c")))

        val sliced = element.slice(1)
        (sliced[0] as YXmlElement).setAttr("changed", true)

        assertEquals("<b changed=\"true\"></b><c></c>", sliced.joinToString(separator = ""))
        assertEquals("<div><a></a><b></b><c></c></div>", element.toString())
        assertEquals("<a></a><b></b>", element.slice(0, -1).joinToString(separator = ""))
    }

    @Test
    fun xmlElementPushUnshiftAndClearMirrorListHelpers() {
        val element = YXmlElement("div")

        element.push(YXmlElement("b"), YXmlElement("c"))
        element.unshift(listOf(YXmlElement("a")))

        assertEquals("<div><a></a><b></b><c></c></div>", element.toString())

        element.clear()
        assertEquals("<div></div>", element.toString())
    }

    @Test
    fun xmlChangesConvergeThroughUpdates() {
        val left = YDoc(clientId = 1)
        val right = YDoc(clientId = 2)
        val fragment = left.getXmlFragment("xml")
        val paragraph = YXmlElement("p")
        paragraph.setAttr("id", "one")
        paragraph.push(listOf(YXmlText("hello")))
        fragment.push(listOf(paragraph))

        right.applyUpdate(left.encodeStateAsUpdate())
        assertEquals(left.getXmlFragment("xml").toString(), right.getXmlFragment("xml").toString())

        left.getXmlFragment("xml").push(listOf(YXmlElement("br")))
        right.applyUpdate(left.encodeStateAsUpdate(right.encodeStateVector()))

        assertEquals("<p id=\"one\">hello</p><br></br>", right.getXmlFragment("xml").toString())
        assertEquals(left.toJson(), right.toJson())
    }

    @Test
    fun xmlFragmentDeleteAndUndo() {
        val doc = YDoc(clientId = 1)
        val fragment = doc.getXmlFragment("xml")
        fragment.push(listOf(YXmlElement("a"), YXmlElement("b"), YXmlElement("c")))
        val undoManager = UndoManager(fragment, UndoManagerOptions(captureTimeoutMillis = 0))

        fragment.delete(1)
        assertEquals("<a></a><c></c>", fragment.toString())

        undoManager.undo()
        assertEquals("<a></a><b></b><c></c>", fragment.toString())

        undoManager.redo()
        assertEquals("<a></a><c></c>", fragment.toString())
    }

    @Test
    fun xmlFragmentSliceAndDeleteAttributeAlias() {
        val doc = YDoc(clientId = 1)
        val fragment = doc.getXmlFragment("xml")
        fragment.push(listOf(YXmlElement("a"), YXmlElement("b"), YXmlElement("c")))
        fragment.setAttr("lang", "en")

        fragment.deleteAttribute("lang")

        assertFalse(fragment.hasAttr("lang"))
        assertEquals("<b></b><c></c>", fragment.slice(1).joinToString(separator = ""))
        assertEquals("<a></a><b></b>", fragment.slice(0, -1).joinToString(separator = ""))
        fragment.setAttrs(mapOf("kind" to "doc", "version" to 1))
        assertEquals(
            listOf("kind:doc", "version:1"),
            buildList { fragment.forEachAttr { value, key -> add("$key:$value") } },
        )
    }

    @Test
    fun xmlFragmentDeltaSupportsRetainDeleteInsertAndFactories() {
        val doc = YDoc(clientId = 1)
        val fragment = YXmlFragment.from(
            listOf(YArrayDeltaOp(insert = listOf(YXmlElement("a"), YXmlElement("b"), YXmlElement("d")))),
            doc,
            "xml",
        )

        fragment.applyDelta(
            listOf(
                YArrayDeltaOp(retain = 1),
                YArrayDeltaOp(delete = 1),
                YArrayDeltaOp(insert = listOf(YXmlElement("B"), "c")),
            ),
        )

        assertEquals("<a></a><b></b>c<d></d>", fragment.toString())
        assertEquals("<a></a><b></b>c<d></d>", fragment.toDelta().single().insert!!.joinToString(separator = ""))
    }

    @Test
    fun xmlFragmentApplyDeltaPreservesTextFormattingLikeUpstream() {
        val doc = YDoc(clientId = 1)
        val fragment = doc.getXmlFragment("xml")
        val emphasized = mapOf<String, Any?>("em" to emptyMap<String, Any?>())
        val emphasizedStrong = mapOf<String, Any?>(
            "em" to emptyMap<String, Any?>(),
            "strong" to emptyMap<String, Any?>(),
        )
        val delta = listOf(
            YArrayDeltaOp(insert = listOf("A"), attributes = emphasizedStrong),
            YArrayDeltaOp(insert = listOf("B"), attributes = emphasized),
            YArrayDeltaOp(insert = listOf("C"), attributes = emphasizedStrong),
        )

        fragment.applyDelta(delta)
        val remote = createDocFromUpdate(doc.encodeStateAsUpdate())

        assertEquals("<em><strong>A</strong></em><em>B</em><em><strong>C</strong></em>", fragment.toString())
        assertEquals(delta, fragment.toDelta())
        assertEquals(delta, remote.getXmlFragment("xml").toDelta())
    }

    @Test
    fun xmlFragmentApplyDeltaUsesActiveRendererForIndexes() {
        val doc = YDoc(clientId = 1)
        val fragment = doc.getXmlFragment("xml")
        val renderer = object : BaseRenderer() {
            override val attributed: IdSet = createIdSet().also { it.add(1, 0, 1) }

            override fun contentLength(item: ItemStruct): Long =
                if (item.id == Id(1, 0)) 0 else super.contentLength(item)
        }
        fragment.push(YXmlElement("a"), YXmlElement("b"), YXmlElement("c"))
        fragment.useRenderer(renderer)

        fragment.applyDelta(listOf(
            YArrayDeltaOp(retain = 1),
            YArrayDeltaOp(insert = listOf(YXmlElement("x"))),
            YArrayDeltaOp(delete = 1),
        ))

        assertEquals("<a></a><b></b><x></x>", fragment.toString())
    }

    @Test
    fun xmlFragmentApplyDeltaRendererArgumentOverridesActiveRenderer() {
        val doc = YDoc(clientId = 1)
        val fragment = doc.getXmlFragment("xml")
        val renderer = object : BaseRenderer() {
            override val attributed: IdSet = createIdSet().also { it.add(1, 0, 1) }

            override fun contentLength(item: ItemStruct): Long =
                if (item.id == Id(1, 0)) 0 else super.contentLength(item)
        }
        fragment.push(YXmlElement("a"), YXmlElement("b"))
        fragment.useRenderer(renderer)

        fragment.applyDelta(
            listOf(YArrayDeltaOp(retain = 1), YArrayDeltaOp(insert = listOf(YXmlElement("x")))),
            renderer = baseRenderer,
        )

        assertEquals("<a></a><x></x><b></b>", fragment.toString())
    }

    @Test
    fun xmlFragmentPushAndUnshiftConvergeThroughUpdates() {
        val left = YDoc(clientId = 1)
        val right = YDoc(clientId = 2)
        val fragment = left.getXmlFragment("xml")

        fragment.push(YXmlElement("b"), YXmlElement("c"))
        fragment.unshift(listOf(YXmlElement("a")))
        right.applyUpdate(left.encodeStateAsUpdate())

        assertEquals("<a></a><b></b><c></c>", fragment.toString())
        assertEquals(fragment.toString(), right.getXmlFragment("xml").toString())
    }

    @Test
    fun xmlFragmentDeltaAcceptsJsonLikeElementMaps() {
        val doc = YDoc(clientId = 1)
        val fragment = doc.getXmlFragment("xml")

        fragment.applyDelta(listOf(YArrayDeltaOp(insert = listOf(
            mapOf(
                "nodeName" to "p",
                "attributes" to mapOf("class" to "intro"),
                "children" to listOf("hello"),
            ),
        ))))

        assertEquals("<p class=\"intro\">hello</p>", fragment.toString())
    }

    @Test
    fun xmlDeltaConvergesThroughUpdatesAndUndo() {
        val left = YDoc(clientId = 1)
        val right = YDoc(clientId = 2)
        val fragment = left.getXmlFragment("xml")
        val undoManager = UndoManager(fragment, UndoManagerOptions(captureTimeoutMillis = 0))

        fragment.applyDelta(listOf(YArrayDeltaOp(insert = listOf(YXmlElement("a"), YXmlElement("b")))))
        fragment.applyDelta(listOf(YArrayDeltaOp(retain = 1), YArrayDeltaOp(insert = listOf("tail"))))
        right.applyUpdate(left.encodeStateAsUpdate())

        assertEquals("<a></a>tail<b></b>", right.getXmlFragment("xml").toString())

        undoManager.undo()
        assertEquals("<a></a><b></b>", fragment.toString())

        undoManager.redo()
        assertEquals("<a></a>tail<b></b>", fragment.toString())
    }

    @Test
    fun xmlElementDeltaAndIterationUseDefensiveCopies() {
        val element = YXmlElement.from(
            "section",
            listOf(YArrayDeltaOp(insert = listOf("hello", YXmlElement("br"), "world"))),
        )
        val seen = mutableListOf<String>()

        element.forEachIndexed { index, node -> seen.add("$index:${node}") }
        val mapped = element.map { it.toJson() }
        (element.toArray()[1] as YXmlElement).setAttr("changed", true)

        assertEquals(listOf("0:hello", "1:<br></br>", "2:world"), seen)
        assertEquals(listOf("hello", mapOf("nodeName" to "br", "attributes" to emptyMap<String, Any?>(), "children" to emptyList<Any?>()), "world"), mapped)
        assertEquals("<section>hello<br></br>world</section>", element.toString())
    }

    @Test
    fun xmlCallbacksAcceptUpstreamValueThenIndexAndValueKeyTypeShapes() {
        val doc = YDoc(clientId = 1)
        val fragment = doc.getXmlFragment("xml")
        val element = YXmlElement("section").setAttrs(mapOf("role" to "main"))
        val fragmentSeen = mutableListOf<String>()
        val elementSeen = mutableListOf<String>()
        val fragmentSeenWithType = mutableListOf<String>()
        val elementSeenWithType = mutableListOf<String>()
        val attrSeen = mutableListOf<String>()

        element.push(YXmlText("hello"), YXmlElement("br"))
        fragment.setAttrs(mapOf("lang" to "en"))
        fragment.push(element, YXmlText("tail"))

        assertEquals(listOf("<section role=\"main\">hello<br></br></section>@0", "tail@1"), fragment.map { node, index -> "$node@$index" })
        assertEquals(listOf("hello@0", "<br></br>@1"), element.map { node, index -> "$node@$index" })
        assertEquals(listOf("<section role=\"main\">hello<br></br></section>@0:true", "tail@1:true"), fragment.map { node, index, type ->
            "$node@$index:${type === fragment}"
        })
        assertEquals(listOf("hello@0:true", "<br></br>@1:true"), element.map { node, index, type ->
            "$node@$index:${type === element}"
        })

        fragment.forEach { node, index -> fragmentSeen.add("$node@$index") }
        element.forEach { node, index -> elementSeen.add("$node@$index") }
        fragment.forEach { node, index, type -> fragmentSeenWithType.add("$node@$index:${type === fragment}") }
        element.forEach { node, index, type -> elementSeenWithType.add("$node@$index:${type === element}") }
        fragment.forEachAttr { value, key, type ->
            assertTrue(type === fragment)
            attrSeen.add("fragment:$key=$value")
        }
        element.forEachAttr { value, key, type ->
            assertTrue(type === element)
            attrSeen.add("element:$key=$value")
        }

        assertEquals(listOf("<section role=\"main\">hello<br></br></section>@0", "tail@1"), fragmentSeen)
        assertEquals(listOf("hello@0", "<br></br>@1"), elementSeen)
        assertEquals(listOf("<section role=\"main\">hello<br></br></section>@0:true", "tail@1:true"), fragmentSeenWithType)
        assertEquals(listOf("hello@0:true", "<br></br>@1:true"), elementSeenWithType)
        assertEquals(listOf("fragment:lang=en", "element:role=main"), attrSeen)
        assertEquals(listOf("fragment:lang=en"), fragment.mapAttrs { value, key, type -> "fragment:$key=$value:${type === fragment}" }.map { it.removeSuffix(":true") })
        assertEquals(listOf("element:role=main"), element.mapAttrs { value, key, type -> "element:$key=$value:${type === element}" }.map { it.removeSuffix(":true") })
    }

    @Test
    fun xmlRootsParticipateInCloneAndCreateDocFromUpdate() {
        val source = YDoc(clientId = 1)
        source.getXmlFragment("xml").push(listOf(YXmlElement("root").also { it.push(listOf(YXmlText("x"))) }))

        val clone = cloneDoc(source)
        val fromUpdate = createDocFromUpdate(source.encodeStateAsUpdate())

        assertEquals(mapOf("xml" to listOf(mapOf("nodeName" to "root", "attributes" to emptyMap<String, Any?>(), "children" to listOf("x")))), clone.toJson())
        assertEquals(source.toJson(), fromUpdate.toJson())
    }

    @Test
    fun liveXmlElementChildrenRenderAndSyncWithStableNodeNames() {
        val left = YDoc(clientId = 1)
        val right = YDoc(clientId = 2)
        val fragment = left.getXmlFragment("xml")
        val first = left.createXmlElement("p").setAttrs(mapOf("id" to "one"))
        val second = left.createXmlElement("p").setAttrs(mapOf("id" to "two"))

        fragment.push(first, second)
        right.applyUpdate(left.encodeStateAsUpdate())

        val remoteFragment = right.getXmlFragment("xml")
        val remoteFirst = remoteFragment.getType(0) as YXmlElementType
        val remoteSecond = remoteFragment.getType(1) as YXmlElementType

        assertTrue(fragment.getType(0) === first)
        assertEquals("<p id=\"one\"></p><p id=\"two\"></p>", remoteFragment.toString())
        assertEquals("p", remoteFirst.nodeName)
        assertEquals("p", remoteSecond.nodeName)
        assertFalse(remoteFirst.name == remoteSecond.name)

        remoteSecond.setAttr("id", "remote-two")
        left.applyUpdate(right.encodeStateAsUpdate(left.encodeStateVector()))

        assertEquals("<p id=\"one\"></p><p id=\"remote-two\"></p>", fragment.toString())
    }

    @Test
    fun liveXmlElementChildListsRenderAndSyncNestedTextAndElements() {
        val left = YDoc(clientId = 1)
        val right = YDoc(clientId = 2)
        val fragment = left.getXmlFragment("xml")
        val paragraph = left.createXmlElement("p")
        val text = left.createText()
        val span = left.createXmlElement("span").setAttrs(mapOf("class" to "mark"))
        text.insert(0, "hello")

        paragraph.push(text, span)
        fragment.push(paragraph)

        assertTrue(paragraph.getType(0) === text)
        assertEquals("<p>hello<span class=\"mark\"></span></p>", fragment.toString())

        right.applyUpdate(left.encodeStateAsUpdate())
        val remoteParagraph = right.getXmlFragment("xml").getType(0) as YXmlElementType
        val remoteText = remoteParagraph.getType(0) as YText

        assertEquals("<p>hello<span class=\"mark\"></span></p>", right.getXmlFragment("xml").toString())
        remoteText.insert(remoteText.length, "!")
        left.applyUpdate(right.encodeStateAsUpdate(left.encodeStateVector()))

        assertEquals("<p>hello!<span class=\"mark\"></span></p>", fragment.toString())
    }

    @Test
    fun liveXmlTextChildrenRenderSyncAndPreserveFormatting() {
        val left = YDoc(clientId = 1)
        val right = YDoc(clientId = 2)
        val fragment = left.getXmlFragment("xml")
        val text = left.createXmlText()
        text.insert(0, "hello", mapOf("bold" to true))

        fragment.push(text)
        right.applyUpdate(left.encodeStateAsUpdate())
        val remoteText = right.getXmlFragment("xml").getType(0) as YXmlTextType

        assertTrue(fragment.getType(0) === text)
        assertEquals("<bold>hello</bold>", fragment.toString())
        assertEquals("<bold>hello</bold>", right.getXmlFragment("xml").toString())
        assertEquals(YTextDelta().insert("hello", mapOf("bold" to true)), remoteText.toDelta())

        remoteText.insert(remoteText.length, "!")
        left.applyUpdate(right.encodeStateAsUpdate(left.encodeStateVector()))

        assertEquals("<bold>hello</bold>!", fragment.toString())
        assertEquals(YTextDelta().insert("hello", mapOf("bold" to true)).insert("!"), text.toDelta())
    }

    @Test
    fun liveXmlFragmentAccessorsExposeLiveTypesAndStaticNodes() {
        val doc = YDoc(clientId = 1)
        val fragment = doc.getXmlFragment("xml")
        val liveElement = doc.createXmlElement("p").setAttrs(mapOf("id" to "live"))
        val liveText = doc.createXmlText()
        liveText.insert(0, "body")

        fragment.push(listOf(YXmlText("head")))
        fragment.push(liveElement, liveText)

        assertEquals("head", (fragment.get(0) as YXmlText).toJson())
        assertNull(fragment.getType(0))
        assertTrue(fragment.get(1) === liveElement)
        assertTrue(fragment.get(2) === liveText)
        assertTrue(fragment.getType(1) === liveElement)
        assertTrue(fragment.getType(2) === liveText)

        val arrayValues = fragment.toArray()
        val listValues = fragment.toList()
        val slicedValues = fragment.slice(1, 3)
        val iteratedValues = fragment.iterator().asSequence().toList()
        val deltaValues = fragment.toDelta().single().insert!!

        assertEquals("head", (arrayValues[0] as YXmlText).toJson())
        assertTrue(arrayValues[1] === liveElement)
        assertTrue(listValues[2] === liveText)
        assertTrue(slicedValues[0] === liveElement)
        assertTrue(slicedValues[1] === liveText)
        assertTrue(iteratedValues[1] === liveElement)
        assertTrue(deltaValues[1] === liveElement)
        assertTrue(deltaValues[2] === liveText)

        assertEquals(listOf("static@0", "element@1", "text@2"), fragment.map { value, index ->
            "${xmlAccessorLabel(value, liveElement, liveText)}@$index"
        })
        assertEquals(listOf("static@0:true", "element@1:true", "text@2:true"), fragment.map { value, index, type ->
            "${xmlAccessorLabel(value, liveElement, liveText)}@$index:${type === fragment}"
        })

        val seen = mutableListOf<String>()
        val seenWithType = mutableListOf<String>()
        fragment.forEach { value -> seen.add(xmlAccessorLabel(value, liveElement, liveText)) }
        fragment.forEach { value, index, type ->
            seenWithType.add("${xmlAccessorLabel(value, liveElement, liveText)}@$index:${type === fragment}")
        }
        assertEquals(listOf("static", "element", "text"), seen)
        assertEquals(listOf("static@0:true", "element@1:true", "text@2:true"), seenWithType)
    }

    @Test
    fun liveXmlTraversalAccessorsExposeFirstChildSiblingsAndTreeWalker() {
        val doc = YDoc(clientId = 1)
        val fragment = doc.getXmlFragment("xml")
        val section = doc.createXmlElement("section").setAttrs(mapOf("id" to "main"))
        val liveText = doc.createXmlText()
        val span = doc.createXmlElement("span")

        liveText.insert(0, "body")
        section.push(YXmlText("prefix"))
        section.push(liveText, span)
        fragment.setAttrs(mapOf("lang" to "en"))
        fragment.push(YXmlText("head"))
        fragment.push(section)
        fragment.push(YXmlElement("footer"))

        assertEquals(fragment.getAttrs(), fragment.getAttributes())
        assertEquals(section.getAttrs(), section.getAttributes())
        assertEquals("head", (fragment.firstChild as YXmlText).toJson())
        assertEquals("prefix", (section.firstChild as YXmlText).toJson())
        assertTrue(section.prevSibling is YXmlText)
        assertTrue(section.nextSibling is YXmlElement)
        assertTrue(liveText.prevSibling is YXmlText)
        assertTrue(liveText.nextSibling === span)
        assertTrue(span.prevSibling === liveText)
        assertNull(span.nextSibling)

        assertEquals(
            listOf("head", "section", "prefix", "text:body", "span", "footer"),
            fragment.createTreeWalker().map { node ->
                when (node) {
                    is YXmlElementType -> node.nodeName
                    is YXmlTextType -> "text:${node}"
                    is YXmlElement -> node.nodeName
                    is YXmlText -> node.toJson()
                    else -> "unknown"
                }
            }.toList(),
        )
        assertEquals(
            listOf("section", "span"),
            fragment.createTreeWalker { it is YXmlElementType }.map { (it as YXmlElementType).nodeName }.toList(),
        )
        assertEquals(listOf("prefix@0:true", "text:body@1:true", "span@2:true"), section.map { node, index, type ->
            val label = when (node) {
                is YXmlText -> node.toJson()
                is YXmlTextType -> "text:${node}"
                is YXmlElementType -> node.nodeName
                else -> "unknown"
            }
            "$label@$index:${type === section}"
        })
    }

    @Test
    fun liveXmlElementAccessorsExposeLiveTypesAndStaticNodes() {
        val doc = YDoc(clientId = 1)
        val element = doc.createXmlElement("section")
        val liveText = doc.createXmlText()
        val liveSpan = doc.createXmlElement("span")
        liveText.insert(0, "body")

        element.push(listOf(YXmlText("head")))
        element.push(liveText, liveSpan)

        assertEquals("head", (element.get(0) as YXmlText).toJson())
        assertNull(element.getType(0))
        assertTrue(element.get(1) === liveText)
        assertTrue(element.get(2) === liveSpan)
        assertTrue(element.getType(1) === liveText)
        assertTrue(element.getType(2) === liveSpan)

        val arrayValues = element.toArray()
        val listValues = element.toList()
        val slicedValues = element.slice(1, 3)
        val iteratedValues = element.iterator().asSequence().toList()
        val deltaValues = element.toDelta().single().insert!!

        assertEquals("head", (arrayValues[0] as YXmlText).toJson())
        assertTrue(arrayValues[1] === liveText)
        assertTrue(listValues[2] === liveSpan)
        assertTrue(slicedValues[0] === liveText)
        assertTrue(slicedValues[1] === liveSpan)
        assertTrue(iteratedValues[1] === liveText)
        assertTrue(deltaValues[1] === liveText)
        assertTrue(deltaValues[2] === liveSpan)
    }

    private fun xmlAccessorLabel(value: Any?, liveElement: YXmlElementType, liveText: YXmlTextType): String = when {
        value === liveElement -> "element"
        value === liveText -> "text"
        value is YXmlText -> "static"
        else -> "unknown"
    }
}
