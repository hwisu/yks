package dev.yks

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class NestedTypeTest {
    @Test
    fun documentOwnedNestedTypesDoNotRenderAsRootsUntilInserted() {
        val doc = YDoc(clientId = 1)
        val nested = doc.createMap()
        nested.setAttr("hidden", "value")

        assertEquals(emptyMap(), doc.toJson())
    }

    @Test
    fun nestedMapStoredInMapConvergesThroughUpdates() {
        val left = YDoc(clientId = 1)
        val right = YDoc(clientId = 2)
        val root = left.getMap("root")
        val nested = left.createMap()

        val returned = root.setAttr("profile", nested)
        nested.setAttr("name", "Ada")

        assertSame(nested, returned)
        assertSame(nested, root.getAttr("profile"))
        assertSame(root, nested.parent)

        right.applyUpdate(left.encodeStateAsUpdate())
        val remoteNested = right.getMap("root").getAttr("profile") as YMap
        assertEquals("Ada", remoteNested.getAttr("name"))
        assertSame(right.getMap("root"), remoteNested.parent)

        remoteNested.setAttr("city", "Seoul")
        left.applyUpdate(right.encodeStateAsUpdate(left.encodeStateVector()))

        assertEquals("Seoul", nested.getAttr("city"))
        assertEquals(
            mapOf(
                "root" to mapOf(
                    "profile" to mapOf(
                        "city" to "Seoul",
                        "name" to "Ada",
                    ),
                ),
            ),
            left.toJson(),
        )
    }

    @Test
    fun sameDocumentNestedTypeCanOnlyBeInsertedOnceLikeUpstreamSharedTypes() {
        val doc = YDoc(clientId = 1)
        val root = doc.getMap("root")
        val nested = doc.createArray()
        nested.push("apple", "banana")

        root.setAttr("food", nested)

        assertFailsWith<IllegalArgumentException> {
            root.setAttr("fruit", nested)
        }
        assertSame(nested, root.getAttr("food"))
        assertNull(root.getAttr("fruit"))
        assertSame(root, nested.parent)
    }

    @Test
    fun failedDuplicateNestedTypeInsidePlainValueDoesNotConsumeFirstInsertion() {
        val doc = YDoc(clientId = 1)
        val root = doc.getMap("root")
        val nested = doc.createText()
        nested.insert(0, "child")

        assertFailsWith<IllegalArgumentException> {
            root.setAttr("bad", listOf(nested, nested))
        }

        assertNull(nested.parent)
        assertNull(root.getAttr("bad"))

        root.setAttr("child", nested)

        assertSame(nested, root.getAttr("child"))
        assertSame(root, nested.parent)
        assertEquals("child", nested.toString())
    }

    @Test
    fun nestedTypesCannotContainThemselves() {
        val doc = YDoc(clientId = 1)
        val map = doc.createMap()
        val array = doc.createArray()
        val text = doc.createText()
        val xml = doc.createXmlElement("p")

        assertFailsWith<IllegalArgumentException> {
            map.setAttr("self", map)
        }
        assertFailsWith<IllegalArgumentException> {
            array.push(array)
        }
        assertFailsWith<IllegalArgumentException> {
            text.insertEmbed(0, text)
        }
        assertFailsWith<IllegalArgumentException> {
            xml.push(xml)
        }

        assertNull(map.parent)
        assertNull(array.parent)
        assertNull(text.parent)
        assertNull(xml.parent)
        assertEquals(emptyMap(), map.toMap())
        assertEquals(emptyList(), array.toArray())
        assertEquals("", text.toString())
        assertEquals("<p></p>", xml.toString())
    }

    @Test
    fun nestedTextAndArrayStoredInArrayRoundTripThroughUpdates() {
        val left = YDoc(clientId = 1)
        val right = YDoc(clientId = 2)
        val root = left.getArray("nodes")
        val text = left.createText()
        val childArray = left.createArray()

        text.insert(0, "hello")
        childArray.push("first", "second")
        root.push(listOf(text, childArray))

        right.applyUpdate(left.encodeStateAsUpdate())

        val remoteRoot = right.getArray("nodes")
        val remoteText = remoteRoot.get(0) as YText
        val remoteArray = remoteRoot.get(1) as YArray

        assertEquals("hello", remoteText.toString())
        assertEquals(listOf("first", "second"), remoteArray.toArray())
        assertEquals(listOf("hello", listOf("first", "second")), remoteRoot.toJson())
    }

    @Test
    fun nestedArrayAttributesSurviveCodecFallback() {
        val source = YDoc(clientId = 1)
        val nested = source.createArray()
        source.getArray("root").push(nested)
        nested.setAttr("label", "child")

        val target = cloneDoc(source)
        val remoteNested = target.getArray("root").get(0) as YArray

        assertEquals("child", remoteNested.getAttr("label"))
    }

    @Test
    fun deletingNestedTypeReferenceHidesItFromJsonWithoutPromotingNestedParent() {
        val doc = YDoc(clientId = 1)
        val root = doc.getMap("root")
        val nested = doc.createMap()
        root.setAttr("child", nested)
        nested.setAttr("value", 1)

        assertSame(root, nested.parent)

        root.deleteAttr("child")

        assertNull(nested.parent)
        assertEquals(emptyMap(), nested.toMap())
        assertEquals(mapOf("root" to emptyMap<String, Any?>()), doc.toJson())
    }

    @Test
    fun deletingDirectNestedTypeReferenceDeletesNestedContentAndSyncs() {
        val doc = YDoc(clientId = 1)
        val peer = YDoc(clientId = 2)
        val root = doc.getArray("root")
        val nested = doc.createMap()
        val child = doc.createText()
        root.push(nested)
        nested.setAttr("body", child)
        child.insert(0, "hi")
        peer.applyUpdate(doc.encodeStateAsUpdate())
        val peerState = peer.encodeStateVector()
        val remoteNested = peer.getArray("root").get(0) as YMap
        val remoteChild = remoteNested.getAttr("body") as YText
        val events = mutableListOf<YTransactionEvent>()
        doc.observeAfterTransactions { event -> events.add(event) }

        root.delete(0)
        peer.applyUpdate(doc.encodeStateAsUpdate(peerState))

        val event = events.single()
        assertTrue(event.deletes(1, 0))
        assertTrue(event.deletes(1, 1))
        assertTrue(event.deletes(1, 2))
        assertTrue(event.deletes(1, 3))
        assertNull(nested.parent)
        assertNull(child.parent)
        assertEquals(emptyMap(), nested.toMap())
        assertEquals("", child.toString())
        assertEquals(emptyList(), root.toArray())
        assertEquals(emptyList(), peer.getArray("root").toArray())
        assertEquals(emptyMap(), remoteNested.toMap())
        assertEquals("", remoteChild.toString())
    }

    @Test
    fun parentPropertyTracksNestedRefsInsidePlainValues() {
        val doc = YDoc(clientId = 1)
        val root = doc.getArray("root")
        val nestedMap = doc.createMap()
        val nestedText = doc.createText()

        assertNull(root.parent)
        assertNull(nestedMap.parent)
        root.push(mapOf("slot" to nestedMap))
        nestedMap.setAttr("body", listOf(nestedText))

        assertSame(root, nestedMap.parent)
        assertSame(nestedMap, nestedText.parent)

        root.delete(0)

        assertNull(nestedMap.parent)
        assertSame(nestedMap, nestedText.parent)
    }

    @Test
    fun nestedTypeObserversReceiveEvents() {
        val doc = YDoc(clientId = 1)
        val root = doc.getMap("root")
        val nested = doc.createMap()
        val keysChanged = mutableListOf<Set<String>>()
        nested.observe { event -> keysChanged.add(event.keysChanged) }

        root.setAttr("nested", nested)
        nested.setAttr("k", "v")

        assertEquals(listOf(setOf("k")), keysChanged)
        assertTrue(root.getAttr("nested") is YMap)
    }

    @Test
    fun observeDeepBubblesNestedMapChangesToVisibleAncestors() {
        val doc = YDoc(clientId = 1)
        val root = doc.getMap("root")
        val nested = doc.createMap()
        root.setAttr("profile", nested)
        val events = mutableListOf<YEvent>()

        root.observeDeep { event -> events.add(event) }
        nested.setAttr("name", "Ada")

        assertEquals(1, events.size)
        val event = events.single()
        assertSame(root, event.target)
        assertSame(nested, event.changedTarget)
        assertEquals(listOf("profile"), event.path)
        assertEquals(1, event.deepEvents.size)
        assertEquals(YMapDelta().setAttr("name", "Ada"), event.deepEvents.single().mapDelta)
    }

    @Test
    fun observeDeepBubblesNestedArrayChangesWithIndexPath() {
        val doc = YDoc(clientId = 1)
        val root = doc.getArray("root")
        val nested = doc.createMap()
        root.push("before", nested)
        val events = mutableListOf<YEvent>()

        root.observeDeep { event -> events.add(event) }
        nested.setAttr("k", "v")

        assertEquals(listOf(1), events.single().path)
        assertSame(nested, events.single().changedTarget)
        assertEquals(YMapDelta().setAttr("k", "v"), events.single().deepEvents.single().mapDelta)
    }

    @Test
    fun observeDeepBubblesTextEmbedSharedTypeChangesWithIndexPath() {
        val doc = YDoc(clientId = 1)
        val root = doc.getText("root")
        val nested = doc.createMap()
        root.insert(0, listOf("x", nested))
        val events = mutableListOf<YEvent>()

        root.observeDeep { event -> events.add(event) }
        nested.setAttr("k", "v")

        assertEquals(listOf(1), events.single().path)
        assertSame(nested, events.single().changedTarget)
        assertEquals(YMapDelta().setAttr("k", "v"), events.single().deepEvents.single().mapDelta)
    }

    @Test
    fun getPathToFindsNestedTypesInsideMapAndListValues() {
        val doc = YDoc(clientId = 1)
        val root = doc.getMap("root")
        val nested = doc.createText()
        root.setAttr("payload", mapOf("children" to listOf("label", nested)))

        nested.insert(0, "child")

        assertEquals(listOf("payload", "children", 1), root.getPathTo(nested))
        assertEquals(listOf(nested), getTypeChildTypes(root))
        assertEquals(
            mapOf("root" to mapOf("payload" to mapOf("children" to listOf("label", "child")))),
            doc.toJson(),
        )
    }

    @Test
    fun getPathToUsesRendererForSequenceIndexes() {
        val doc = YDoc(clientId = 1)
        val root = doc.getArray("root")
        val hidden = doc.createMap()
        val rendered = doc.createMap()
        val renderer = object : BaseRenderer() {
            override val attributed: IdSet = createIdSet().also { it.add(1, 0, 1) }

            override fun contentLength(item: ItemStruct): Long =
                if (item.id == Id(1, 0)) 0 else super.contentLength(item)
        }

        root.push(listOf(hidden, rendered))

        assertEquals(listOf(1), root.getPathTo(rendered))
        assertEquals(listOf(0), root.getPathTo(rendered, renderer))
        assertEquals(listOf(0), getPathTo(root, rendered, renderer))
    }

    @Test
    fun observeDeepBubblesThroughNestedTypeRefsInsideArrayValues() {
        val doc = YDoc(clientId = 1)
        val root = doc.getArray("root")
        val nested = doc.createMap()
        root.push(mapOf("slot" to nested))
        val events = mutableListOf<YEvent>()

        root.observeDeep { event -> events.add(event) }
        nested.setAttr("k", "v")

        assertEquals(listOf(0, "slot"), events.single().path)
        assertSame(nested, events.single().changedTarget)
        assertEquals(YMapDelta().setAttr("k", "v"), events.single().deepEvents.single().mapDelta)
    }

    @Test
    fun observeDeepBubblesThroughNestedTypeRefsInsideTextEmbeds() {
        val doc = YDoc(clientId = 1)
        val root = doc.getText("root")
        val nested = doc.createMap()
        root.insertEmbed(0, mapOf("children" to listOf(nested)))
        val events = mutableListOf<YEvent>()

        root.observeDeep { event -> events.add(event) }
        nested.setAttr("k", "v")

        assertEquals(listOf(0, "children", 0), events.single().path)
        assertSame(nested, events.single().changedTarget)
        assertEquals(YMapDelta().setAttr("k", "v"), events.single().deepEvents.single().mapDelta)
    }

    @Test
    fun observeDeepAggregatesDirectAndNestedChangesInOneTransaction() {
        val doc = YDoc(clientId = 1)
        val root = doc.getMap("root")
        val nested = doc.createMap()
        root.setAttr("child", nested)
        val events = mutableListOf<YEvent>()

        root.observeDeep { event -> events.add(event) }
        doc.transact {
            root.setAttr("title", "hello")
            nested.setAttr("k", "v")
        }

        assertEquals(1, events.size)
        assertSame(root, events.single().target)
        assertEquals(2, events.single().deepEvents.size)
        assertEquals(
            listOf(emptyList(), listOf("child")),
            events.single().deepEvents.map { it.path },
        )
    }

    @Test
    fun unobserveDeepStopsNestedBubblingAndGetPathToUsesVisibleRefs() {
        val doc = YDoc(clientId = 1)
        val root = doc.getMap("root")
        val nested = doc.createMap()
        root.setAttr("child", nested)
        var calls = 0
        val listener: (YEvent) -> Unit = { calls++ }

        assertEquals(listOf("child"), root.getPathTo(nested))

        root.observeDeep(listener)
        nested.setAttr("a", 1)
        root.unobserveDeep(listener)
        nested.setAttr("b", 2)

        assertEquals(1, calls)
    }

    @Test
    fun nestedContentBeforeParentReferenceStillSyncsAsNested() {
        val left = YDoc(clientId = 1)
        val right = YDoc(clientId = 2)
        val root = left.getMap("root")
        val nested = left.createMap()
        nested.setAttr("value", "created-before-parent-ref")
        root.setAttr("child", nested)

        right.applyUpdate(left.encodeStateAsUpdate())

        assertEquals(emptyMap(), right.toJson())
        right.getMap("root")
        assertEquals(
            mapOf("root" to mapOf("child" to mapOf("value" to "created-before-parent-ref"))),
            right.toJson(),
        )
    }
}
