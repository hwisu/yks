package dev.yks

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class YTypeAccessorParityTest {
    @Test
    fun mapExposesUpstreamStyleAttributeAccessors() {
        val doc = YDoc(clientId = 1)
        val map = doc.getMap("map")
        map.setAttrs(mapOf("stuff" to "c0", "otherstuff" to "c1"))
        map.setAttribute("alias", "ok")

        assertEquals(mapOf("alias" to "ok", "otherstuff" to "c1", "stuff" to "c0"), map.getAttrs())
        assertTrue(map.hasAttr("stuff"))
        assertTrue(map.hasAttribute("alias"))
        assertFalse(map.hasAttr("missing"))
        assertEquals("ok", map.getAttribute("alias"))
        assertEquals(setOf("alias", "otherstuff", "stuff"), map.attrKeys())
        assertEquals(3, map.attrSize)

        map.deleteAttr("stuff")
        map.removeAttribute("alias")

        assertEquals(mapOf("otherstuff" to "c1"), map.getAttrs())
        assertFalse(map.hasAttr("stuff"))
        assertFalse(map.hasAttribute("alias"))
    }

    @Test
    fun mapAndSharedAttributesAllowEmptyStringKeysLikeUpstream() {
        val left = YDoc(clientId = 1)
        val right = YDoc(clientId = 2)
        val map = left.getMap("map")
        val text = left.getText("text")
        val array = left.getArray("array")
        val xml = left.getXmlFragment("xml")
        val events = mutableListOf<YEvent>()
        map.observe { events.add(it) }

        assertEquals("map-value", map.set("", "map-value"))
        assertEquals("text-value", text.setAttr("", "text-value"))
        assertEquals(1L, array.setAttribute("", 1))
        assertEquals("xml-value", xml.setAttr("", "xml-value"))
        typeMapSet(text, "", "helper-value")

        right.applyUpdate(left.encodeStateAsUpdateLossless())

        assertEquals("map-value", map.get(""))
        assertEquals("helper-value", text.getAttr(""))
        assertEquals(1L, array.getAttribute(""))
        assertEquals("xml-value", xml.getAttr(""))
        assertEquals("map-value", right.getMap("map").get(""))
        assertEquals("helper-value", right.getText("text").getAttr(""))
        assertEquals(1L, right.getArray("array").getAttribute(""))
        assertEquals("xml-value", right.getXmlFragment("xml").getAttr(""))
        assertEquals(setOf(""), events.single().keysChanged)
        assertEquals(YMapChangeAction.Add, events.single().mapChanges[""]?.action)

        map.delete("")
        text.deleteAttr("")

        assertFalse(map.has(""))
        assertFalse(text.hasAttr(""))
    }

    @Test
    fun clearAttrsClearsVisibleMapKeysInSingleEvent() {
        val doc = YDoc(clientId = 1)
        val map = doc.getMap("map")
        val events = mutableListOf<YEvent>()
        map.observe { events.add(it) }
        map.setAttrs(mapOf("stuff" to 4, "otherstuff" to "value"))
        events.clear()

        map.clearAttrs()

        assertEquals(emptyMap(), map.getAttrs())
        assertEquals(setOf("otherstuff", "stuff"), events.single().keysChanged)
        assertEquals(YMapChangeAction.Delete, events.single().mapChanges["stuff"]?.action)
        assertEquals(YMapChangeAction.Delete, events.single().mapChanges["otherstuff"]?.action)
    }

    @Test
    fun sequenceTypesExposeSharedAttributeAccessors() {
        val left = YDoc(clientId = 1)
        val text = left.getText("text")
        val array = left.getArray("array")
        val xml = left.getXmlFragment("xml")
        val events = mutableListOf<YEvent>()
        text.observe { events.add(it) }

        text.setAttr("test", 42)
        array.setAttrs(mapOf("role" to "list"))
        xml.setAttribute("lang", "en")

        assertEquals(42L, text.getAttr("test"))
        assertEquals(mapOf("test" to 42L), text.getAttrs())
        assertEquals(1, text.attrSize)
        assertEquals(setOf("test"), events.single().keysChanged)
        assertEquals(YMapChangeAction.Add, events.single().mapChanges["test"]?.action)
        assertTrue(array.hasAttr("role"))
        assertEquals("list", array.getAttribute("role"))
        assertEquals(setOf("role"), array.attrKeys())
        assertEquals(listOf("role=list"), array.mapAttrs { value, key -> "$key=$value" })
        assertEquals("en", xml.getAttr("lang"))
        assertEquals(listOf("lang"), xml.attrEntries().map { it.key })

        val right = YDoc(clientId = 2)
        right.applyUpdate(left.encodeStateAsUpdateLossless())

        assertEquals(42L, right.getText("text").getAttr("test"))
        assertEquals("list", right.getArray("array").getAttr("role"))
        assertEquals("en", right.getXmlFragment("xml").getAttribute("lang"))

        text.deleteAttr("test")
        array.clearAttrs()
        xml.removeAttribute("lang")

        assertFalse(text.hasAttr("test"))
        assertEquals(0, array.attrSize)
        assertFalse(xml.hasAttribute("lang"))
    }

    @Test
    fun arrayDeleteZeroLengthAndVarargInsertMatchYjsEdges() {
        val doc = YDoc(clientId = 1)
        val array = doc.getArray("array")

        array.delete(0, 0)
        assertFailsWith<IllegalArgumentException> {
            array.delete(1, 1)
        }

        array.insert(0, "A")
        array.delete(1, 0)
        array.insert(1, "B", "C")

        assertEquals(listOf("A", "B", "C"), array.toArray())
    }

    @Test
    fun genericListHelpersCoverArrayTextAndXmlFragments() {
        val doc = YDoc(clientId = 1)
        val array = doc.getArray("array")
        val text = doc.getText("text")
        val xml = doc.getXmlFragment("xml")

        typeListPushGenerics(array, listOf("a", "c"))
        typeListInsertGenericsAfter(array, getTypeStructs(array).first(), listOf("b"))
        typeListDelete(array, 0, 1)

        assertEquals(2, typeListLength(array))
        assertEquals("b", typeListGet(array, 0))
        assertNull(typeListGet(array, -1))
        assertNull(typeListGet(array, 2))
        assertEquals(listOf("b"), typeListSlice(array, 0, -1))
        assertEquals(listOf("c"), typeListSlice(array, -1, array.length))
        assertEquals(emptyList(), typeListSlice(array, 2, 1))
        assertEquals(listOf("b", "c"), array.toArray())

        typeListInsertGenerics(text, 0, listOf("h", "i"))
        typeListPushGenerics(text, listOf(mapOf("emoji" to "wave")))

        assertEquals("h", typeListGet(text, 0))
        assertNull(typeListGet(text, text.length))
        assertEquals(listOf("i", mapOf("emoji" to "wave")), typeListSlice(text, 1, text.length))
        assertEquals(listOf("h", "i"), typeListSlice(text, 0, -1))

        typeListPushGenerics(xml, listOf(YXmlElement("a"), "tail"))
        typeListInsertGenerics(xml, 1, listOf(YXmlElement("b")))

        assertEquals(3, typeListLength(xml))
        assertEquals("<b></b>", typeListGet(xml, 1).toString())
        assertNull(typeListGet(xml, 3))
        assertEquals("<b></b>tail", typeListSlice(xml, 1, xml.length).joinToString(separator = ""))
        assertEquals(emptyList(), typeListSlice(xml, xml.length, 1))
    }

    @Test
    fun genericListInsertAfterAcceptsDeletedReferenceItems() {
        val doc = YDoc(clientId = 1)
        val array = doc.getArray("array")
        array.push("a", "b", "c")
        val deletedReference = getTypeStructs(array)[1]

        array.delete(1)
        typeListInsertGenericsAfter(array, deletedReference, listOf("B"))

        assertEquals(listOf("a", "B", "c"), array.toArray())
    }

    @Test
    fun emptyPublicListInsertsNoopBeforeBoundsValidation() {
        val doc = YDoc(clientId = 1)
        val array = doc.getArray("array")
        val text = doc.getText("text")
        val xml = doc.getXmlFragment("xml")
        val element = YXmlElement("p")
        val transactions = mutableListOf<YTransactionEvent>()
        doc.observeAfterTransactions { transaction -> transactions.add(transaction) }

        array.insert(0, emptyList())
        array.insert(1, emptyList())
        text.insert(0, "")
        text.insert(1, "")
        text.insert(0, emptyList())
        text.insert(1, emptyList())
        text.insertText(0, "")
        text.insertText(1, "")
        xml.insert(0, emptyList())
        xml.insert(1, emptyList())
        element.insert(1, emptyList())
        typeListInsertGenerics(array, 0, emptyList())
        typeListInsertGenerics(text, 0, emptyList())
        typeListInsertGenerics(xml, 0, emptyList())

        assertTrue(transactions.isEmpty())
        assertEquals(emptyList(), array.toArray())
        assertEquals("", text.toString())
        assertEquals("", xml.toString())
        assertEquals("<p></p>", element.toString())

        assertFailsWith<IllegalArgumentException> { typeListInsertGenerics(array, 1, emptyList()) }
        assertFailsWith<IllegalArgumentException> { typeListInsertGenerics(text, 1, emptyList()) }
        assertFailsWith<IllegalArgumentException> { typeListInsertGenerics(xml, 1, emptyList()) }
    }

    @Test
    fun concreteListInsertClampsNegativeIndexesAndRejectsPositiveOverflow() {
        val doc = YDoc(clientId = 1)
        val array = doc.getArray("array")
        val text = doc.getText("text")
        val xml = doc.getXmlFragment("xml")
        val element = YXmlElement("p")
        val origins = mutableListOf<Any?>()
        doc.observeAfterTransactions { transaction -> origins.add(transaction.origin) }

        array.push("a")
        text.insert(0, "a")
        xml.push(YXmlText("a"))
        element.push(YXmlText("a"))
        origins.clear()

        array.insert(-1, listOf("x"))
        text.insert(-1, "x")
        text.insertText(-1, "y", origin = "insert-text")
        text.insertEmbed(-1, mapOf("emoji" to "wave"), origin = "insert-embed")
        xml.insert(-1, listOf(YXmlText("x")))
        element.insert(-1, listOf(YXmlText("x")))

        assertEquals(listOf("x", "a"), array.toArray())
        assertEquals("yxa", text.toString())
        assertEquals("xa", xml.toString())
        assertEquals("<p>xa</p>", element.toString())
        assertEquals(listOf<Any?>(null, null, "insert-text", "insert-embed", null), origins)

        assertFailsWith<IllegalArgumentException> { array.insert(3, listOf("overflow")) }
        assertFailsWith<IllegalArgumentException> { text.insert(5, "overflow") }
        assertFailsWith<IllegalArgumentException> { text.insertText(5, "overflow") }
        assertFailsWith<IllegalArgumentException> { text.insertEmbed(5, "overflow") }
        assertFailsWith<IllegalArgumentException> { xml.insert(3, listOf(YXmlText("overflow"))) }
        assertFailsWith<IllegalArgumentException> { element.insert(3, listOf(YXmlText("overflow"))) }
    }

    @Test
    fun concreteListGetReturnsNullForOutOfRangeIndexes() {
        val doc = YDoc(clientId = 1)
        val array = doc.getArray("array")
        val text = doc.getText("text")
        val xml = doc.getXmlFragment("xml")
        val element = YXmlElement("p")

        array.push("a")
        text.insert(0, "a")
        xml.push(YXmlText("a"))
        element.push(YXmlText("a"))

        assertEquals("a", array.get(0))
        assertEquals("a", text.get(0))
        assertEquals("a", (xml.get(0) as YXmlText).toJson())
        assertEquals("a", element.get(0)?.toJson())

        assertNull(array.get(-1))
        assertNull(array.get(1))
        assertNull(text.get(-1))
        assertNull(text.get(1))
        assertNull(xml.get(-1))
        assertNull(xml.get(1))
        assertNull(element.get(-1))
        assertNull(element.get(1))
    }

    @Test
    fun concreteListSliceReturnsEmptyForReversedRanges() {
        val doc = YDoc(clientId = 1)
        val array = doc.getArray("array")
        val text = doc.getText("text")
        val xml = doc.getXmlFragment("xml")
        val element = YXmlElement("p")

        array.push("a", "b", "c")
        text.insert(0, "abc")
        xml.push(YXmlText("a"), YXmlText("b"), YXmlText("c"))
        element.push(YXmlText("a"), YXmlText("b"), YXmlText("c"))

        assertEquals(emptyList(), array.slice(2, 1))
        assertEquals(emptyList(), array.slice(1, 1))
        assertEquals(emptyList(), text.slice(2, 1))
        assertEquals(emptyList(), text.slice(1, 1))
        assertEquals(emptyList(), xml.slice(2, 1))
        assertEquals(emptyList(), xml.slice(1, 1))
        assertEquals(emptyList(), element.slice(2, 1))
        assertEquals(emptyList(), element.slice(1, 1))
    }

    @Test
    fun listDeleteMatchesYjsPublicRetainDeleteEdges() {
        val doc = YDoc(clientId = 1)
        val array = doc.getArray("array")
        val text = doc.getText("text")
        val xml = doc.getXmlFragment("xml")
        val transactions = mutableListOf<YTransactionEvent>()
        doc.observeAfterTransactions { transaction -> transactions.add(transaction) }

        array.push("a", "b")
        text.insert(0, "ab")
        xml.push(YXmlText("a"), YXmlText("b"))
        transactions.clear()

        array.delete(-1, 1)
        text.delete(-1, 1)
        xml.delete(-1, 1)

        assertEquals(listOf("b"), array.toArray())
        assertEquals("b", text.toString())
        assertEquals("b", xml.toString())

        array.delete(0, -1)
        text.delete(0, -1)
        xml.delete(0, -1)

        assertEquals(listOf("b"), array.toArray())
        assertEquals("b", text.toString())
        assertEquals("b", xml.toString())

        array.delete(0, 99)
        text.delete(0, 99)
        xml.delete(0, 99)

        assertEquals(emptyList(), array.toArray())
        assertEquals("", text.toString())
        assertEquals("", xml.toString())

        val eventsAfterDeletes = transactions.size
        array.delete(0, 0)
        text.delete(0, 0)
        xml.delete(0, 0)

        assertEquals(eventsAfterDeletes, transactions.size)
        assertFailsWith<IllegalArgumentException> { array.delete(1, 0) }
        assertFailsWith<IllegalArgumentException> { text.delete(1, 0) }
        assertFailsWith<IllegalArgumentException> { xml.delete(1, 0) }

        val aliasText = YDoc(clientId = 2).getText("text")
        val aliasOrigins = mutableListOf<Any?>()
        aliasText.doc.observeAfterTransactions { transaction -> aliasOrigins.add(transaction.origin) }
        aliasText.insert(0, "ab")
        aliasOrigins.clear()

        aliasText.deleteText(-1, 1, origin = "alias-delete")

        assertEquals("b", aliasText.toString())
        assertEquals(listOf<Any?>("alias-delete"), aliasOrigins)

        aliasText.deleteText(0, -1, origin = "alias-noop")

        assertEquals("b", aliasText.toString())
        assertEquals(listOf<Any?>("alias-delete"), aliasOrigins)
        assertFailsWith<IllegalArgumentException> { aliasText.deleteText(2, 0, origin = "alias-overflow") }
    }

    @Test
    fun applyDeltaDeletesTruncateAtVisibleEnd() {
        val doc = YDoc(clientId = 1)
        val array = doc.getArray("array")
        val text = doc.getText("text")
        val xml = doc.getXmlFragment("xml")

        array.push("a", "b")
        text.insert(0, "ab")
        xml.push(YXmlText("a"), YXmlText("b"))

        array.applyDelta(listOf(YArrayDeltaOp(retain = 1), YArrayDeltaOp(delete = 99)))
        text.applyDelta(YTextDelta().retain(1).delete(99))
        xml.applyDelta(listOf(YArrayDeltaOp(retain = 1), YArrayDeltaOp(delete = 99)))

        assertEquals(listOf("a"), array.toArray())
        assertEquals("a", text.toString())
        assertEquals("a", xml.toString())

        assertFailsWith<IllegalArgumentException> {
            array.applyDelta(listOf(YArrayDeltaOp(retain = 99), YArrayDeltaOp(insert = listOf("x"))))
        }
        assertFailsWith<IllegalArgumentException> {
            text.applyDelta(YTextDelta().retain(99).insert("x"))
        }
        assertFailsWith<IllegalArgumentException> {
            xml.applyDelta(listOf(YArrayDeltaOp(retain = 99), YArrayDeltaOp(insert = listOf(YXmlText("x")))))
        }
    }

    @Test
    fun searchMarkersResolveSequencePositionsAndTrackIndexChanges() {
        val doc = YDoc(clientId = 1)
        val array = doc.getArray("array")
        val text = doc.getText("text")
        val xml = doc.getXmlFragment("xml")

        array.push("a", "b", "c")
        text.insert(0, listOf("h", "i", mapOf("emoji" to "wave")))
        xml.push(YXmlElement("a"), YXmlElement("b"), YXmlText("tail"))

        assertNull(findMarker(array, 0))

        val arrayMarker = findMarker(array, 1)
        val textMarker = findMarker(text, 2)
        val xmlMarker = findMarker(xml, 10)

        assertEquals(1, arrayMarker?.index)
        assertEquals(getTypeStructs(array)[1].id, arrayMarker?.p?.id)
        assertEquals(2, textMarker?.index)
        assertEquals(getTypeStructs(text)[2].id, textMarker?.p?.id)
        assertEquals(2, xmlMarker?.index)
        assertEquals(getTypeStructs(xml)[2].id, xmlMarker?.p?.id)

        val shifted = ArraySearchMarker(arrayMarker!!.p, 1)
        val coveredByDelete = ArraySearchMarker(arrayMarker.p, 5)
        val markers = mutableListOf(shifted, coveredByDelete)

        updateMarkerChanges(markers, index = 1, len = 2)
        assertEquals(3, shifted.index)

        updateMarkerChanges(markers, index = 2, len = -4)
        assertEquals(2, shifted.index)
        assertEquals(3, coveredByDelete.index)
    }

    @Test
    fun sequenceCallbacksAcceptUpstreamValueThenIndexShape() {
        val doc = YDoc(clientId = 1)
        val array = doc.getArray("array")
        val text = doc.getText("text")
        val seenArray = mutableListOf<String>()
        val seenText = mutableListOf<String>()
        val seenArrayWithType = mutableListOf<String>()
        val seenTextWithType = mutableListOf<String>()

        array.push("a", "b")
        text.insert(0, listOf("h", "i", mapOf("emoji" to "wave")))

        assertEquals(listOf("a@0", "b@1"), array.map { value, index -> "$value@$index" })
        array.forEach { value, index -> seenArray.add("$value@$index") }
        assertEquals(listOf("a@0", "b@1"), seenArray)
        assertEquals(listOf("a@0:true", "b@1:true"), array.map { value, index, type ->
            "$value@$index:${type === array}"
        })
        array.forEach { value, index, type -> seenArrayWithType.add("$value@$index:${type === array}") }
        assertEquals(listOf("a@0:true", "b@1:true"), seenArrayWithType)

        assertEquals(listOf("h@0", "i@1", "{emoji=wave}@2"), text.map { value, index -> "$value@$index" })
        text.forEach { value, index -> seenText.add("$value@$index") }
        assertEquals(listOf("h@0", "i@1", "{emoji=wave}@2"), seenText)
        assertEquals(listOf("h@0:true", "i@1:true", "{emoji=wave}@2:true"), text.map { value, index, type ->
            "$value@$index:${type === text}"
        })
        text.forEach { value, index, type -> seenTextWithType.add("$value@$index:${type === text}") }
        assertEquals(listOf("h@0:true", "i@1:true", "{emoji=wave}@2:true"), seenTextWithType)
    }

    @Test
    fun genericMapHelpersCoverSharedAttributesAndMapTypes() {
        val doc = YDoc(clientId = 1, gc = false)
        val array = doc.getArray("array")
        val map = doc.getMap("map")
        val beforeAttrs = snapshot(doc)

        typeMapSet(array, "kind", "list")
        val withKind = snapshot(doc)
        typeMapSet(array, "count", 1)
        typeMapSet(map, "title", "hello")

        assertEquals("list", typeMapGet(array, "kind"))
        assertTrue(typeMapHas(array, "count"))
        assertEquals(mapOf("count" to 1L, "kind" to "list"), typeMapGetAll(array))
        assertEquals(listOf("kind=list", "count=1"), buildList {
            val iterator = createMapIterator(array)
            while (iterator.hasNext()) {
                val entry = iterator.next()
                add("${entry.key}=${entry.value}")
            }
        })
        assertEquals("hello", typeMapGet(map, "title"))
        assertEquals(null, typeMapGetSnapshot(array, "kind", beforeAttrs))
        assertEquals("list", typeMapGetSnapshot(array, "kind", withKind))
        assertEquals(
            YMapDelta().setAttr("kind", "list").setAttr("count", 1L),
            typeMapGetDelta(array),
        )
        assertEquals(
            YMapDelta().setAttr("kind", "list"),
            typeMapGetDelta(YMapDelta(), array, setOf(null, "kind", "missing")),
        )
        assertEquals(
            YMapDelta().deleteAttr("missing"),
            typeMapGetDelta(YMapDelta(), array, setOf("missing"), itemsToRender = createIdSet()),
        )

        typeMapDelete(array, "kind")

        assertFalse(typeMapHas(array, "kind"))
        assertEquals(mapOf("kind" to "list"), typeMapGetAllSnapshot(array, withKind))
    }

    @Test
    fun attributeCallbacksAcceptUpstreamValueKeyTypeShape() {
        val doc = YDoc(clientId = 1)
        val array = doc.getArray("array").setAttrs(mapOf("kind" to "list"))
        val text = doc.getText("text").setAttrs(mapOf("role" to "body"))
        val map = doc.getMap("map").setAttrs(mapOf("title" to "hello"))
        val seen = mutableListOf<String>()

        assertEquals(listOf("kind=list:true"), array.mapAttrs { value, key, type -> "$key=$value:${type === array}" })
        assertEquals(listOf("role=body:true"), text.mapAttrs { value, key, type -> "$key=$value:${type === text}" })
        assertEquals(listOf("title=hello:true"), map.mapAttrs { value, key, type -> "$key=$value:${type === map}" })

        array.forEachAttr { value, key, type ->
            assertSame(array, type)
            seen.add("array:$key=$value")
        }
        text.forEachAttr { value, key, type ->
            assertSame(text, type)
            seen.add("text:$key=$value")
        }
        map.forEachAttr { value, key, type ->
            assertSame(map, type)
            seen.add("map:$key=$value")
        }
        map.forEach { value, key, type ->
            assertSame(map, type)
            seen.add("forEach:$key=$value")
        }

        assertEquals(
            listOf("array:kind=list", "text:role=body", "map:title=hello", "forEach:title=hello"),
            seen,
        )
    }

    @Test
    fun visibilityAndFormatEqualityHelpersMirrorYjsUtilitySemantics() {
        val doc = YDoc(clientId = 1)
        val text = doc.getText("text")
        text.insert(0, "ab")
        val beforeDelete = snapshot(doc)
        val first = getTypeStructs(text).first()

        text.delete(0)
        val deletedFirst = getTypeStructs(text).first { it.id == first.id }

        assertTrue(isVisible(first))
        assertFalse(isVisible(deletedFirst))
        assertTrue(isVisible(deletedFirst, beforeDelete))
        assertTrue(equalFormats(mapOf("marks" to listOf("bold"), "enabled" to true), mapOf("enabled" to true, "marks" to listOf("bold"))))
        assertFalse(equalFormats(mapOf("enabled" to true), mapOf("enabled" to false)))
    }
}
