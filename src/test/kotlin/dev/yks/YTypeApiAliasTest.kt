package dev.yks

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class YTypeApiAliasTest {
    @Test
    fun attributionItemsConvertToUpstreamAttributionShape() {
        val attrs = listOf(
            createContentAttribute("insert", "alice"),
            createContentAttribute("delete", "bob"),
            createContentAttribute("insertAt", 5),
            createContentAttribute("color", "red"),
            createContentAttribute("_internal", "hidden"),
        )

        assertNull(createAttributionFromAttributionItems(null, deleted = false))
        assertEquals("alice", attrs.first().`val`)
        assertEquals(
            linkedMapOf(
                "insert" to listOf("alice"),
                "insertAt" to 5L,
                "color" to "red",
            ),
            createAttributionFromAttributionItems(attrs, deleted = false),
        )
        assertEquals(
            linkedMapOf(
                "delete" to listOf("bob"),
                "insertAt" to 5L,
                "color" to "red",
            ),
            createAttributionFromAttributionItems(attrs, deleted = true),
        )
        assertEquals(
            linkedMapOf("insert" to emptyList<Any?>()),
            createAttributionFromAttributionItems(emptyList(), deleted = false),
        )
    }

    @Test
    fun docAndTypesExposeContentJsonAndUpstreamToJsonShapes() {
        val doc = YDoc(clientId = 1)
        val array = doc.getArray("array")
        val map = doc.getMap("map")
        val text = doc.getText("text")
        val xml = doc.getXmlFragment("xml")
        val arrayNested = doc.createMap()
        val mapNested = doc.createMap()

        arrayNested.setAttr("name", "Ada")
        mapNested.setAttr("name", "Ada")
        array.push("a", "b", arrayNested)
        array.setAttr("kind", "list")
        map.setAttrs(mapOf("title" to "hello", "count" to 2, "profile" to mapNested))
        text.insert(0, "hi")
        xml.setAttr("lang", "en")
        xml.push(YXmlElement("p").also { it.push(YXmlText("hello")) })

        assertEquals(listOf("a", "b", mapOf("name" to "Ada")), array.toJson())
        assertEquals(mapOf("count" to 2L, "profile" to mapOf("name" to "Ada"), "title" to "hello"), map.toJson())
        assertEquals(text.toJson(), text.toJSON())
        assertEquals(listOf(mapOf("nodeName" to "p", "attributes" to emptyMap<String, Any?>(), "children" to listOf("hello"))), xml.toJson())

        val nestedJson = mapOf("name" to "Ada")
        assertEquals(listOf("a", "b", nestedJson), array.toJSON())
        assertEquals(
            mapOf(
                "count" to 2L,
                "profile" to nestedJson,
                "title" to "hello",
            ),
            map.toJSON(),
        )
        assertEquals("<xml lang=\"en\"><p>hello</p></xml>", xml.toJSON())
        assertEquals(
            mapOf(
                "array" to array.toJSON(),
                "map" to map.toJSON(),
                "text" to "hi",
                "xml" to xml.toJSON(),
            ),
            doc.toJSON(),
        )
    }

    @Test
    fun arrayIsIterableAndSupportsClearAndFactoryFromDelta() {
        val doc = YDoc(clientId = 1)
        val array = YArray.from(
            listOf(YArrayDeltaOp(insert = listOf("a", "b"))),
            doc,
            "items",
        )
        array.unshift("start")
        array.push("end")

        assertEquals(listOf("start", "a", "b", "end"), array.toList())
        assertEquals(listOf("0:start", "1:a", "2:b", "3:end"), buildList {
            array.forEachIndexed { index, value -> add("$index:$value") }
        })

        array.clear()
        assertEquals(emptyList(), array.toArray())
        assertEquals(0, array.length)
    }

    @Test
    fun arrayFactoryFromValuesMatchesUpstreamArrayFrom() {
        val doc = YDoc(clientId = 1)
        val externalNested = YMap(mapOf("kind" to "external"))
        val values = listOf<Any?>("a", 1, mapOf("flag" to true), null, externalNested)

        val array = YArray.from(values, doc, "items")
        val nested = array.get(4) as YMap

        assertEquals(listOf("a", 1L, mapOf("flag" to true), null, nested), array.toArray())
        assertEquals(mapOf("kind" to "external"), nested.toMap())
        assertTrue(nested.doc === doc)
        assertSame(externalNested, nested)

        val remote = createDocFromUpdate(doc.encodeStateAsUpdate())
        assertEquals(listOf("a", 1L, mapOf("flag" to true), null, mapOf("kind" to "external")), remote.getArray("items").toJson())
    }

    @Test
    fun mapAttributeAliasesIterateInStableKeyOrder() {
        val doc = YDoc(clientId = 1)
        val map = doc.getMap("meta")
        val returned = map.setAttr("title", "hello")
        map.setAttrs(mapOf("enabled" to true, "count" to 2))

        assertEquals("hello", returned)
        assertEquals(3, map.attrSize)
        assertEquals(setOf("count", "enabled", "title"), map.attrKeys())
        assertEquals(listOf("hello", true, 2L), map.attrValues().toList())
        assertEquals(
            listOf("title=hello", "enabled=true", "count=2"),
            map.attrEntries().map { "${it.key}=${it.value}" },
        )
        assertEquals(
            listOf("title:hello", "enabled:true", "count:2"),
            buildList { map.forEach { value, key -> add("$key:$value") } },
        )
        assertEquals(
            listOf("title", "enabled", "count"),
            map.mapAttrs { _, key -> key },
        )
        assertEquals(
            listOf("title", "enabled", "count"),
            map.map { it.key },
        )
        assertEquals(
            listOf("title:hello", "enabled:true", "count:2"),
            buildList { map.forEachAttr { value, key -> add("$key:$value") } },
        )
        map.setAttr("remove", "me")
        map.deleteAttribute("remove")
        assertFalse(map.hasAttr("remove"))
    }

    @Test
    fun rootSharedTypesExposeDeleteAttributeAlias() {
        val doc = YDoc(clientId = 1)
        val array = doc.getArray("array")
        val text = doc.getText("text")

        array.setAttr("tag", "remove")
        text.setAttr("tag", "remove")
        array.setAttrs(mapOf("kind" to "list", "count" to 1))
        text.setAttrs(mapOf("style" to "plain", "lang" to "en"))
        array.deleteAttribute("tag")
        text.deleteAttribute("tag")

        assertFalse(array.hasAttr("tag"))
        assertFalse(text.hasAttr("tag"))
        assertEquals(
            listOf("count:1", "kind:list"),
            buildList { array.forEachAttr { value, key -> add("$key:$value") } },
        )
        assertEquals(
            listOf("lang:en", "style:plain"),
            buildList { text.forEachAttr { value, key -> add("$key:$value") } },
        )
    }

    @Test
    fun mapAndTextFactoriesConvergeThroughUpdates() {
        val left = YDoc(clientId = 1)
        val right = YDoc(clientId = 2)
        YMap.from(
            YMapDelta().setAttr("title", "hello").setAttr("count", 2),
            left,
            "meta",
        )
        YText.from(
            YTextDelta().insert("hello", mapOf("bold" to true)),
            left,
            "body",
        )

        right.applyUpdate(left.encodeStateAsUpdate())

        assertEquals(mapOf("count" to 2L, "title" to "hello"), right.getMap("meta").toMap())
        assertEquals(YTextDelta().insert("hello", mapOf("bold" to true)), right.getText("body").toDelta())
    }

    @Test
    fun genericTypeAliasesExposeFactoryFromDelta() {
        val doc = YDoc(clientId = 1)
        val map = Type.from(YMapDelta().setAttr("number", 1).setAttr("string", "hello"), doc, "meta")
        val array = YType.from(listOf(YArrayDeltaOp(insert = listOf(0, 1, 2))), doc, "items")
        val text = Type.from(YTextDelta().insert("hi"), doc, "body")

        assertEquals(mapOf("number" to 1L, "string" to "hello"), map.toMap())
        assertEquals(listOf(0L, 1L, 2L), array.toArray())
        assertEquals("hi", text.toString())
    }

    @Test
    fun genericTypeAliasesExposeFactoryFromDeepDelta() {
        val source = YDoc(clientId = 1)
        val array = source.getArray("items")
        val text = source.getText("body")
        val map = source.getMap("meta")
        val xml = source.getXmlFragment("xml")
        val nestedMap = source.createMap()
        val nestedText = source.createText()
        val embedArray = source.createArray()
        val mapText = source.createText()
        val mapArray = source.createArray()

        nestedMap.setAttrs(mapOf("label" to "nested"))
        nestedText.insert(0, "child", mapOf("italic" to true))
        embedArray.push("x", "y")
        mapText.insert(0, "mapped")
        mapArray.push("one", "two")
        array.setAttr("kind", "mixed")
        array.push("plain", nestedMap, nestedText)
        text.setAttr("lang", "en")
        text.insert(0, "hi", mapOf("bold" to true))
        text.insertEmbed(text.length, mapOf("items" to embedArray), mapOf("kind" to "embed"))
        map.setAttrs(mapOf("body" to mapText, "items" to mapArray))
        xml.setAttr("role", "doc")
        xml.push(YXmlElement("p").also { it.push(YXmlText("hello", mapOf("bold" to true))) })

        val target = YDoc(clientId = 2)
        val arrayCopy = YType.from(array.toDeltaDeep(), target, "items")
        val textCopy = Type.from(text.toDeltaDeep(), target, "body")
        val mapCopy = Type.from(map.toDeltaDeep(), target, "meta")
        val xmlCopy = YType.from(xml.toDeltaDeep(), target, "xml")

        assertEquals(array.toDeltaDeep(), arrayCopy.toDeltaDeep())
        assertEquals(text.toDeltaDeep(), textCopy.toDeltaDeep())
        assertEquals(map.toDeltaDeep(), mapCopy.toDeltaDeep())
        assertEquals(xml.toDeltaDeep(), xmlCopy.toDeltaDeep())

        val remote = YDoc(clientId = 3)
        remote.applyUpdateLossless(target.encodeStateAsUpdateLossless())

        assertEquals(arrayCopy.toDeltaDeep(), remote.getArray("items").toDeltaDeep())
        assertEquals(textCopy.toDeltaDeep(), remote.getText("body").toDeltaDeep())
        assertEquals(mapCopy.toDeltaDeep(), remote.getMap("meta").toDeltaDeep())
        assertEquals(xmlCopy.toDeltaDeep(), remote.getXmlFragment("xml").toDeltaDeep())
    }

    @Test
    fun standaloneSharedTypesCanBeInsertedIntoDocumentsLikeUpstreamConstructedTypes() {
        val source = YDoc(clientId = 1)
        val target = YDoc(clientId = 2)
        val root = source.getMap("root")

        root.setAttr("m1", YMap(listOf("number" to 1, "string" to "hello")))
        root.setAttr("m2", YMap(mapOf("object" to mapOf("x" to 1), "boolean" to true)))
        root.setAttr("array", YArray("a", "b"))
        root.setAttr("text", YText("hi", mapOf("bold" to true)))
        root.setAttr("xml", YXmlFragment(YXmlElement("p").also { it.push(YXmlText("ok")) }))

        val insertedMap = root.getAttr("m1") as YMap
        val insertedArray = root.getAttr("array") as YArray
        val insertedText = root.getAttr("text") as YText
        val insertedXml = root.getAttr("xml") as YXmlFragment
        insertedMap.setAttr("after", "insert")
        insertedArray.push("c")
        insertedText.insert(insertedText.length, "!")
        insertedXml.push(YXmlText("tail"))

        target.applyUpdateLossless(source.encodeStateAsUpdateLossless())
        val remoteRoot = target.getMap("root")

        assertEquals(mapOf("after" to "insert", "number" to 1L, "string" to "hello"), (remoteRoot.getAttr("m1") as YMap).toMap())
        assertEquals(mapOf("boolean" to true, "object" to mapOf("x" to 1L)), (remoteRoot.getAttr("m2") as YMap).toMap())
        assertEquals(listOf("a", "b", "c"), (remoteRoot.getAttr("array") as YArray).toArray())
        assertEquals(YTextDelta().insert("hi", mapOf("bold" to true)).insert("!"), (remoteRoot.getAttr("text") as YText).toDelta())
        assertEquals("<p>ok</p>tail", (remoteRoot.getAttr("xml") as YXmlFragment).toString())
    }

    @Test
    fun standaloneSharedTypesInsidePlainValuesAreAdoptedBeforeStorage() {
        val doc = YDoc(clientId = 1)
        val root = doc.getMap("root")

        root.setAttr(
            "bundle",
            mapOf(
                "body" to YText("hello"),
                "items" to listOf(YArray("a", "b")),
            ),
        )

        val bundle = root.getAttr("bundle") as Map<*, *>
        val body = bundle["body"] as YText
        val items = (bundle["items"] as List<*>).single() as YArray
        body.insert(body.length, "!")
        items.push("c")

        assertEquals("hello!", body.toString())
        assertEquals(listOf("a", "b", "c"), items.toArray())
    }

    @Test
    fun unobserveRemovesListenerWithoutClosingSubscription() {
        val doc = YDoc(clientId = 1)
        val array = doc.getArray("items")
        var calls = 0
        val listener: (YEvent) -> Unit = { calls++ }

        array.observe(listener)
        array.push("a")
        array.unobserve(listener)
        array.push("b")

        assertEquals(1, calls)
    }

    @Test
    fun typeDestroyEventCanBeObservedAndIsOnlyEmittedOnce() {
        val doc = YDoc(clientId = 1)
        val array = doc.getArray("items")
        val seen = mutableListOf<String>()

        array.on("destroy") { event -> seen.add("${event.name}:${event.target.name}") }

        array.destroy()
        array.destroy()

        assertTrue(array.isDestroyed)
        assertEquals(listOf("destroy:items"), seen)
    }

    @Test
    fun typeEventChannelsSupportOnceAndPublicEmit() {
        val doc = YDoc(clientId = 1)
        val array = doc.getArray("items")
        val other = doc.getArray("other")
        val seen = mutableListOf<String>()

        array.once("custom") { event -> seen.add("${event.name}:${event.target.name}:${event.origin}") }
        array.emit("custom", YTypeEvent(name = "ignored", target = other, origin = "first"))
        array.emit("custom", YTypeEvent(name = "custom", target = array, origin = "second"))

        assertEquals(listOf("custom:items:first"), seen)

        val direct = mutableListOf<YTypeEvent>()
        array.on("manual") { event -> direct.add(event) }
        array.emit(YTypeEvent(name = "manual", target = other, origin = "direct"))

        assertEquals(listOf("manual"), direct.map { it.name })
        assertSame(array, direct.single().target)
        assertEquals("direct", direct.single().origin)
    }

    @Test
    fun typeDeltaEventCarriesDirectYEventDeltaAndOrigin() {
        val doc = YDoc(clientId = 1)
        val array = doc.getArray("items")
        val deltas = mutableListOf<YTypeEvent>()

        array.on("delta") { event -> deltas.add(event) }
        doc.transact(origin = "direct") {
            array.push("a")
        }

        val event = deltas.single()
        assertEquals("delta", event.name)
        assertSame(array, event.target)
        assertEquals("direct", event.origin)
        assertEquals("direct", event.transaction?.origin)
        assertEquals(listOf(YArrayDeltaOp(insert = listOf("a"))), event.delta)
        assertEquals(listOf(YArrayDeltaOp(insert = listOf("a"))), event.yEvent?.arrayDelta)
        assertTrue(event.yEvent?.childListChanged == true)
    }

    @Test
    fun typeDeltaEventBubblesNestedChangesToAncestors() {
        val doc = YDoc(clientId = 1)
        val root = doc.getArray("root")
        val nested = doc.createMap()
        val deltas = mutableListOf<YTypeEvent>()
        root.push(nested)

        root.on("delta") { event -> deltas.add(event) }
        doc.transact(origin = "nested") {
            nested.setAttr("title", "hello")
        }

        val event = deltas.single()
        val nestedEvent = event.yEvent?.deepEvents?.single()
        assertSame(root, event.target)
        assertEquals("nested", event.origin)
        assertEquals(listOf(0), event.yEvent?.path)
        assertSame(nested, event.yEvent?.changedTarget)
        assertSame(nested, nestedEvent?.target)
        assertEquals(YMapDelta().setAttr("title", "hello"), nestedEvent?.delta)
    }

    @Test
    fun documentDestroyDestroysTopLevelTypesButNotNestedTypes() {
        val doc = YDoc(clientId = 1)
        val root = doc.getArray("root")
        val nested = doc.createMap()
        val seen = mutableListOf<String>()
        root.on("destroy") { event -> seen.add(event.target.name) }
        nested.on("destroy") { event -> seen.add(event.target.name) }

        root.push(nested)
        doc.destroy()

        assertTrue(doc.isDestroyed)
        assertTrue(root.isDestroyed)
        assertFalse(nested.isDestroyed)
        assertEquals(listOf("root"), seen)
    }
}
