package dev.yks

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame

class YTypeCloneTest {
    @Test
    fun arrayCloneCanBeInsertedIntoAnotherDocWithNestedTypes() {
        val source = YDoc(clientId = 1)
        val root = source.getArray("root")
        val nestedMap = source.createMap()
        val nestedText = source.createText()
        nestedMap.setAttr("title", "source")
        nestedText.insert(0, "hello", mapOf("bold" to true))
        root.setAttr("kind", "source-root")
        root.push("item", nestedMap, nestedText)

        val target = YDoc(clientId = 2)
        val cloned = root.clone(target)
        target.getArray("copies").push(cloned)
        val clonedMap = cloned.get(1) as YMap
        val clonedText = cloned.get(2) as YText
        clonedMap.setAttr("title", "clone")
        clonedText.insert(clonedText.length, "!")
        cloned.setAttr("kind", "cloned-root")

        assertEquals(
            mapOf("root" to listOf("item", mapOf("title" to "source"), "hello")),
            source.toJson(),
        )
        assertEquals(
            mapOf("copies" to listOf(listOf("item", mapOf("title" to "clone"), "hello!"))),
            target.toJson(),
        )
        assertNotSame(nestedMap, clonedMap)
        assertNotSame(nestedText, clonedText)
        assertEquals("source-root", root.getAttr("kind"))
        assertEquals("cloned-root", cloned.getAttr("kind"))
        assertEquals(
            YTextDelta().insert("hello", mapOf("bold" to true)).insert("!"),
            clonedText.toDelta(),
        )
    }

    @Test
    fun mapAndTextCloneAreIndependentNestedTypes() {
        val source = YDoc(clientId = 1)
        val map = source.getMap("meta")
        val nestedArray = source.createArray()
        nestedArray.push("a")
        map.setAttrs(mapOf("items" to nestedArray, "count" to 1))
        val text = source.getText("body")
        text.insert(0, "hi", mapOf("italic" to true))
        text.setAttr("lang", "en")
        text.insertEmbed(text.length, mapOf("image" to "source", "width" to 10L), mapOf("alt" to "source"))

        val target = YDoc(clientId = 2)
        val clonedMap = map.clone(target)
        val clonedText = text.clone(target)
        target.getMap("root").setAttrs(mapOf("meta" to clonedMap, "body" to clonedText))
        (clonedMap.get("items") as YArray).push("b")
        clonedText.format(0, 1, mapOf("italic" to null))
        clonedText.setAttr("lang", "ko")
        clonedText.format(2, 1, mapOf("alt" to "clone"))

        assertEquals(
            mapOf("root" to mapOf("body" to "hi", "meta" to mapOf("count" to 1L, "items" to listOf("a", "b")))),
            target.toJson(),
        )
        assertEquals(mapOf("count" to 1L, "items" to listOf("a")), source.getMap("meta").toJson())
        assertEquals("en", text.getAttr("lang"))
        assertEquals("ko", clonedText.getAttr("lang"))
        assertEquals(
            YTextDelta()
                .insert("h")
                .insert("i", mapOf("italic" to true))
                .insertEmbed(mapOf("image" to "source", "width" to 10L), mapOf("alt" to "clone")),
            clonedText.toDelta(),
        )
    }

    @Test
    fun textCloneRemapsSharedTypesInsideEmbedPayloads() {
        val source = YDoc(clientId = 1)
        val text = source.getText("body")
        val embeddedArray = source.createArray()
        embeddedArray.push("x")
        text.insertEmbed(0, mapOf("node" to embeddedArray), mapOf("label" to "source"))

        val target = YDoc(clientId = 2)
        val clonedText = text.clone(target)
        target.getMap("root").setAttr("body", clonedText)
        val clonedEmbed = clonedText.toDelta().ops.single().insert as Map<*, *>
        val clonedEmbeddedArray = clonedEmbed["node"] as YArray
        clonedEmbeddedArray.push("y")

        assertEquals(listOf("x"), embeddedArray.toArray())
        assertEquals(listOf("x", "y"), clonedEmbeddedArray.toArray())
        assertNotSame(embeddedArray, clonedEmbeddedArray)
    }

    @Test
    fun xmlNodesAndFragmentsCloneIndependently() {
        val source = YDoc(clientId = 1)
        val xml = source.getXmlFragment("xml")
        xml.setAttr("role", "source")
        val element = YXmlElement("p").also {
            it.setAttr("class", "lead")
            it.push(listOf(YXmlText("hello")))
        }
        xml.push(listOf(element))

        val clonedElement = element.clone()
        clonedElement.setAttr("class", "copy")
        clonedElement.push(listOf(YXmlText("!")))
        val target = YDoc(clientId = 2)
        val clonedFragment = xml.clone(target)
        clonedFragment.setAttr("role", "clone")
        target.getMap("root").setAttr("xml", clonedFragment)

        assertEquals("<xml role=\"source\"><p class=\"lead\">hello</p></xml>", xml.toString())
        assertEquals("<p class=\"copy\">hello!</p>", clonedElement.toString())
        assertEquals("source", xml.getAttr("role"))
        assertEquals("clone", clonedFragment.getAttr("role"))
        assertEquals(
            mapOf("root" to mapOf("xml" to listOf(
                mapOf(
                    "nodeName" to "p",
                    "attributes" to mapOf("class" to "lead"),
                    "children" to listOf("hello"),
                ),
            ))),
            target.toJson(),
        )
    }

    @Test
    fun xmlFragmentClonePreservesLiveNestedXmlAndTextChildren() {
        val source = YDoc(clientId = 1)
        val xml = source.getXmlFragment("xml")
        val paragraph = source.createXmlElement("p")
        val text = source.createText()
        text.insert(0, "hello")
        text.format(0, 5, mapOf("bold" to true))
        paragraph.push(text)
        xml.push(paragraph)

        val target = YDoc(clientId = 2)
        val clone = xml.clone(target)
        val clonedParagraph = clone.getType(0) as YXmlElementType
        val clonedText = clonedParagraph.getType(0) as YText
        clonedText.insert(clonedText.length, "!")

        assertEquals("<p>hello</p>", xml.toString())
        assertEquals("<p>hello!</p>", clone.toString())
        assertEquals(
            YTextDelta().insert("hello", mapOf("bold" to true)).insert("!"),
            clonedText.toDelta(),
        )
        assertEquals(
            YTextDelta().insert("hello", mapOf("bold" to true)),
            text.toDelta(),
        )
    }
}
