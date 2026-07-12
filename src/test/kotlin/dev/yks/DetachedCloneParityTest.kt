package dev.yks

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DetachedCloneParityTest {
    @Test
    fun arrayMapAndTextNoArgClonesStayDetachedAndDeepCopyTheirGraphs() {
        val source = YDoc(clientId = 1)
        val bytes = byteArrayOf(1, 2, 3)

        val array = source.getArray("items")
        val nestedMap = source.createMap().also { map ->
            map.setAttr(
                "payload",
                linkedMapOf(
                    "bytes" to bytes,
                    "list" to listOf(linkedMapOf("value" to "source")),
                ),
            )
        }
        val nestedText = source.createText().also { text ->
            text.insert(0, "hi", mapOf("bold" to true))
        }
        array.setAttr("metadata", mapOf("bytes" to bytes, "tags" to listOf("a", "b")))
        array.push(nestedMap, nestedText)

        val map = source.getMap("map")
        val nestedArray = source.createArray().also { it.push("one") }
        map.setAttr("items", nestedArray)

        val text = source.getText("text")
        val embeddedArray = source.createArray().also { it.push("embed") }
        text.insert(0, "abc", mapOf("italic" to true))
        text.insertEmbed(3, mapOf("child" to embeddedArray, "bytes" to bytes), mapOf("alt" to "source"))
        text.setAttr("config", listOf(mapOf("bytes" to bytes)))

        val sourceRoots = source.rootNames()
        val arrayClone = array.clone()
        val mapClone = map.clone()
        val textClone = text.clone()
        val fromClone = YArray.from(emptyList()).also { it.push("factory") }

        listOf(arrayClone, mapClone, textClone, fromClone).forEach { clone ->
            assertTrue(clone.isPreliminary)
            assertTrue(clone.doc !== source)
        }
        assertEquals(sourceRoots, source.rootNames())
        assertNotSame(nestedMap, arrayClone.preliminaryList[0])
        assertNotSame(nestedText, arrayClone.preliminaryList[1])
        val sourceMetadata = array.getAttr("metadata") as Map<*, *>
        val clonedMetadata = arrayClone.preliminaryMap["metadata"] as Map<*, *>
        assertNotSame(sourceMetadata["bytes"], clonedMetadata["bytes"])
        assertContentEquals(sourceMetadata["bytes"] as ByteArray, clonedMetadata["bytes"] as ByteArray)

        val target = YDoc(clientId = 2)
        val holder = target.getArray("holder")
        holder.push(arrayClone, mapClone, textClone, fromClone)

        assertSame(arrayClone, holder.get(0))
        assertSame(mapClone, holder.get(1))
        assertSame(textClone, holder.get(2))
        assertSame(fromClone, holder.get(3))
        assertEquals(listOf("factory"), fromClone.toArray())
        listOf(arrayClone, mapClone, textClone, fromClone).forEach { clone ->
            assertTrue(!clone.isPreliminary)
            assertSame(target, clone.doc)
        }

        val clonedNestedMap = arrayClone.get(0) as YMap
        val clonedNestedText = arrayClone.get(1) as YText
        val clonedNestedArray = mapClone.get("items") as YArray
        val clonedEmbed = textClone.toDelta().ops.last().insert as Map<*, *>
        val clonedEmbeddedArray = clonedEmbed["child"] as YArray
        assertNotSame(nestedMap, clonedNestedMap)
        assertNotSame(nestedText, clonedNestedText)
        assertNotSame(nestedArray, clonedNestedArray)
        assertNotSame(embeddedArray, clonedEmbeddedArray)
        assertEquals(YTextDelta().insert("hi", mapOf("bold" to true)), clonedNestedText.toDelta())
        assertContentEquals(bytes, clonedEmbed["bytes"] as ByteArray)

        clonedNestedMap.setAttr("clone-only", true)
        clonedNestedText.insert(2, "!")
        clonedNestedArray.push("two")
        clonedEmbeddedArray.push("copy")
        assertEquals(null, nestedMap.get("clone-only"))
        assertEquals("hi", nestedText.toString())
        assertEquals(listOf("one"), nestedArray.toArray())
        assertEquals(listOf("embed"), embeddedArray.toArray())
    }

    @Test
    fun liveXmlNoArgClonesPreserveConcreteTypesAndIntegrateByIdentity() {
        val source = YDoc(clientId = 11)
        val fragment = source.getXmlFragment("xml")
        fragment.setAttr("role", mapOf("name" to "source"))
        val element = source.createXmlElement("section")
        element.setAttr("data", listOf(mapOf("bytes" to byteArrayOf(4, 5))))
        val xmlText = source.createXmlText()
        xmlText.insert(0, "hello", mapOf("bold" to true))
        xmlText.setAttr("lang", "en")
        val hook = source.createXmlHook("widget")
        hook.setAttrs(mapOf("enabled" to true, "items" to listOf("a", "b")))
        element.push(xmlText, hook)
        fragment.push(element)

        val sourceRoots = source.rootNames()
        val fragmentClone = fragment.clone()
        val elementClone = element.clone()
        val xmlTextClone = xmlText.clone()
        val hookClone = hook.clone()

        listOf(fragmentClone, elementClone, xmlTextClone, hookClone).forEach { clone ->
            assertTrue(clone.isPreliminary)
            assertTrue(clone.doc !== source)
        }
        assertEquals(sourceRoots, source.rootNames())
        assertEquals("section", elementClone.nodeName)
        assertEquals("widget", hookClone.hookName)

        val target = YDoc(clientId = 12)
        val holder = target.getArray("holder")
        holder.push(fragmentClone, elementClone, xmlTextClone, hookClone)

        assertSame(fragmentClone, holder.get(0))
        assertSame(elementClone, holder.get(1))
        assertSame(xmlTextClone, holder.get(2))
        assertSame(hookClone, holder.get(3))
        val nestedElementClone = fragmentClone.getType(0) as YXmlElementType
        val nestedTextClone = nestedElementClone.getType(0) as YXmlTextType
        val nestedHookClone = nestedElementClone.getType(1) as YXmlHook
        assertNotSame(element, nestedElementClone)
        assertNotSame(xmlText, nestedTextClone)
        assertNotSame(hook, nestedHookClone)
        assertEquals("section", nestedElementClone.nodeName)
        assertEquals("widget", nestedHookClone.hookName)
        assertEquals(YTextDelta().insert("hello", mapOf("bold" to true)), nestedTextClone.toDelta())
        assertEquals("en", nestedTextClone.getAttr("lang"))
        assertEquals(mapOf("enabled" to true, "items" to listOf("a", "b")), nestedHookClone.toMap())

        nestedTextClone.insert(5, "!")
        nestedHookClone.setAttr("enabled", false)
        assertEquals(YTextDelta().insert("hello", mapOf("bold" to true)), xmlText.toDelta())
        assertEquals(true, hook.get("enabled"))
    }
}
