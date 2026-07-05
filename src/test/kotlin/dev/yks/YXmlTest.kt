package dev.yks

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        assertEquals("<p class=\"intro\">Hello &lt;world&gt; &amp; friends</p>tail", fragment.toString())
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
    fun xmlStringRenderingSupportsForceTagAndFragmentAttributes() {
        val doc = YDoc(clientId = 1)
        val fragment = doc.getXmlFragment("xml")
        val paragraph = YXmlElement("p")
        fragment.push(paragraph)

        assertEquals("<p />", paragraph.toString(forceTag = true))
        assertEquals("<p />", fragment.toString())
        assertEquals("<xml><p /></xml>", fragment.toString(forceTag = true))

        fragment.setAttrs(mapOf("title" to "A&B\"", "lang" to "en", "count" to 2, "enabled" to true))

        assertEquals("<xml count=2 enabled=true lang=\"en\" title=\"A&B\\\"\"><p /></xml>", fragment.toString())
        assertEquals("<xml count=2 enabled=true lang=\"en\" title=\"A&B\\\"\"><p /></xml>", fragment.toString(forceTag = true))
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
    fun xmlElementSliceReturnsDefensiveCopies() {
        val element = YXmlElement("div")
        element.push(listOf(YXmlElement("a"), YXmlElement("b"), YXmlElement("c")))

        val sliced = element.slice(1)
        (sliced[0] as YXmlElement).setAttr("changed", true)

        assertEquals("<b changed=true /><c />", sliced.joinToString(separator = ""))
        assertEquals("<div><a /><b /><c /></div>", element.toString())
        assertEquals("<a /><b />", element.slice(0, -1).joinToString(separator = ""))
    }

    @Test
    fun xmlElementPushUnshiftAndClearMirrorListHelpers() {
        val element = YXmlElement("div")

        element.push(YXmlElement("b"), YXmlElement("c"))
        element.unshift(listOf(YXmlElement("a")))

        assertEquals("<div><a /><b /><c /></div>", element.toString())

        element.clear()
        assertEquals("<div />", element.toString())
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

        assertEquals("<p id=\"one\">hello</p><br />", right.getXmlFragment("xml").toString())
        assertEquals(left.toJson(), right.toJson())
    }

    @Test
    fun xmlFragmentDeleteAndUndo() {
        val doc = YDoc(clientId = 1)
        val fragment = doc.getXmlFragment("xml")
        fragment.push(listOf(YXmlElement("a"), YXmlElement("b"), YXmlElement("c")))
        val undoManager = UndoManager(fragment, UndoManagerOptions(captureTimeoutMillis = 0))

        fragment.delete(1)
        assertEquals("<a /><c />", fragment.toString())

        undoManager.undo()
        assertEquals("<a /><b /><c />", fragment.toString())

        undoManager.redo()
        assertEquals("<a /><c />", fragment.toString())
    }

    @Test
    fun xmlFragmentSliceAndDeleteAttributeAlias() {
        val doc = YDoc(clientId = 1)
        val fragment = doc.getXmlFragment("xml")
        fragment.push(listOf(YXmlElement("a"), YXmlElement("b"), YXmlElement("c")))
        fragment.setAttr("lang", "en")

        fragment.deleteAttribute("lang")

        assertFalse(fragment.hasAttr("lang"))
        assertEquals("<b /><c />", fragment.slice(1).joinToString(separator = ""))
        assertEquals("<a /><b />", fragment.slice(0, -1).joinToString(separator = ""))
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

        assertEquals("<a /><B />c<d />", fragment.toString())
        assertEquals("<a /><B />c<d />", fragment.toDelta().single().insert!!.joinToString(separator = ""))
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

        assertEquals("<a /><b /><x />", fragment.toString())
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

        assertEquals("<a /><x /><b />", fragment.toString())
    }

    @Test
    fun xmlFragmentPushAndUnshiftConvergeThroughUpdates() {
        val left = YDoc(clientId = 1)
        val right = YDoc(clientId = 2)
        val fragment = left.getXmlFragment("xml")

        fragment.push(YXmlElement("b"), YXmlElement("c"))
        fragment.unshift(listOf(YXmlElement("a")))
        right.applyUpdate(left.encodeStateAsUpdate())

        assertEquals("<a /><b /><c />", fragment.toString())
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

        assertEquals("<a />tail<b />", right.getXmlFragment("xml").toString())

        undoManager.undo()
        assertEquals("<a /><b />", fragment.toString())

        undoManager.redo()
        assertEquals("<a />tail<b />", fragment.toString())
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

        assertEquals(listOf("0:hello", "1:<br />", "2:world"), seen)
        assertEquals(listOf("hello", mapOf("nodeName" to "br", "attributes" to emptyMap<String, Any?>(), "children" to emptyList<Any?>()), "world"), mapped)
        assertEquals("<section>hello<br />world</section>", element.toString())
    }

    @Test
    fun xmlCallbacksAcceptUpstreamValueThenIndexAndValueKeyTypeShapes() {
        val doc = YDoc(clientId = 1)
        val fragment = doc.getXmlFragment("xml")
        val element = YXmlElement("section").setAttrs(mapOf("role" to "main"))
        val fragmentSeen = mutableListOf<String>()
        val elementSeen = mutableListOf<String>()
        val attrSeen = mutableListOf<String>()

        element.push(YXmlText("hello"), YXmlElement("br"))
        fragment.setAttrs(mapOf("lang" to "en"))
        fragment.push(element, YXmlText("tail"))

        assertEquals(listOf("<section role=\"main\">hello<br /></section>@0", "tail@1"), fragment.map { node, index -> "$node@$index" })
        assertEquals(listOf("hello@0", "<br />@1"), element.map { node, index -> "$node@$index" })

        fragment.forEach { node, index -> fragmentSeen.add("$node@$index") }
        element.forEach { node, index -> elementSeen.add("$node@$index") }
        fragment.forEachAttr { value, key, type ->
            assertTrue(type === fragment)
            attrSeen.add("fragment:$key=$value")
        }
        element.forEachAttr { value, key, type ->
            assertTrue(type === element)
            attrSeen.add("element:$key=$value")
        }

        assertEquals(listOf("<section role=\"main\">hello<br /></section>@0", "tail@1"), fragmentSeen)
        assertEquals(listOf("hello@0", "<br />@1"), elementSeen)
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
}
