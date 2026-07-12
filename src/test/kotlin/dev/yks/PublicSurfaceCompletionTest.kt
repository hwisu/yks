package dev.yks

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PublicSurfaceCompletionTest {
    @Test
    fun connectorAliasesAndStructuralHelpersExposeUpstreamShapes() {
        val doc = YDoc(clientId = 1)
        val root = doc.getArray("root")
        val nested = YMap(mapOf("ok" to true))
        root.push(nested)

        val abstractType: AbstractType = root
        val text: Text = YText()
        val element: XmlElement = XmlElement()
        val xmlText: XmlText = XmlText("hello")
        val fragment: XmlFragment = YXmlFragment()
        val hook: XmlHook = YXmlHook("widget")
        assertSame(root, abstractType)
        assertIs<YText>(text)
        assertEquals("UNDEFINED", element.nodeName)
        doc.getXmlFragment("aliases").push(xmlText)
        assertEquals("hello", xmlText.toString())
        assertIs<YXmlFragment>(fragment)
        assertEquals("widget", hook.hookName)

        val children = getTypeChildren(root)
        val owner = children.single()
        assertIs<ContentType>(owner.content)
        assertSame(nested, owner.content.getContent().single())
        assertEquals(owner, getItem(doc.store, owner.id))
        assertTrue(isParentOf(root, owner))
        assertEquals(owner.id, getTypeChildSummaries(root).single().id)

        val connector = AbstractConnector(doc, awareness = mapOf("client" to 1))
        val events = mutableListOf<Any?>()
        val removed: (Any?) -> Unit = { value -> events.add("removed:$value") }
        connector.on("status", removed)
        connector.off("status", removed)
        connector.once("status") { value -> events.add(value) }
        connector.emit("status", "connected")
        connector.emit("status", "ignored")
        assertEquals(listOf<Any?>("connected"), events)
    }

    @Test
    fun genericSnapshotAndRawEventAliasesMatchTheCommonSurface() {
        val doc = YDoc(clientId = 1, gc = false)
        val array = doc.getArray("items")
        val text = doc.getText("body")
        val xml = doc.getXmlFragment("xml")
        array.push("a")
        text.insert(0, "x")
        val paragraph = YXmlElementType("p")
        xml.push(paragraph)
        val before = snapshot(doc)

        lateinit var raw: YEvent
        array.observe { event -> raw = event }
        array.push("b")
        text.insert(1, "y")
        xml.push(YXmlElementType("aside"))

        assertEquals(listOf("a"), typeListToArraySnapshot(array, before))
        assertEquals(listOf("x"), typeListToArraySnapshot(text, before))
        assertSame(paragraph, typeListToArraySnapshot(xml, before).single())
        assertTrue(raw.keys.isEmpty())
        assertFalse(raw.changes.added.isEmpty())
        assertEquals(raw.arrayDelta, raw.changes.delta)
    }

    @Test
    fun xmlSelectorsAndInsertAfterPreserveLiveChildIdentity() {
        val fragment = YXmlFragment()
        val section = YXmlElementType("section")
        val paragraph = YXmlElementType("p")
        val emphasis = YXmlElementType("em")
        paragraph.push(emphasis)
        fragment.push(section, paragraph)
        val doc = YDoc(clientId = 1)
        doc.getArray("root").push(fragment)

        val aside = YXmlElementType("aside")
        fragment.insertAfter(section, listOf(aside))

        assertSame(emphasis, fragment.querySelector("EM"))
        assertEquals(listOf(paragraph), fragment.querySelectorAll("p"))

        assertEquals(listOf(section, aside, paragraph), fragment.toArray())
        val childItem = getTypeChildren(fragment)[1]
        val footer = YXmlElementType("footer")
        fragment.insertAfter(childItem, listOf(footer))
        assertEquals(listOf(section, aside, footer, paragraph), fragment.toArray())
    }

    @Test
    fun textAttributeSanitizeAndSnapshotDeltaOverloadsMatchYjs() {
        val doc = YDoc(clientId = 1, gc = false)
        val text = doc.getText("body")
        text.setAttribute("block", "paragraph")
        assertEquals(mapOf("block" to "paragraph"), text.getAttributes())

        text.applyDelta(YTextDelta().insert("line\n"), sanitize = false)
        assertEquals("line", text.toString())
        val before = snapshot(doc)
        text.insert(text.length, "!")
        val after = snapshot(doc)

        assertEquals(
            YTextDelta().insert("line").insert("!", mapOf("ychange" to mapOf("type" to "added"))),
            text.toDelta(after, before),
        )

        val preserved = doc.createText()
        doc.getArray("holder").push(preserved)
        preserved.applyDelta(YTextDelta().insert("line\n"), sanitize = true)
        assertEquals("line\n", preserved.toString())
        assertFalse(ObfuscatorOptions(yxml = false).yxml)
    }
}
