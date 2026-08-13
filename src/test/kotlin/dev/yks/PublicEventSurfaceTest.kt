package dev.yks

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PublicEventSurfaceTest {
    @Test
    fun eventItemsAreStableLinkedViewsOfTheDocumentStore() {
        val doc = YDoc(clientId = 1)
        val array = doc.getArray("items")
        lateinit var event: YArrayEvent
        array.observeTyped { observed -> event = observed }

        array.push(byteArrayOf(1), byteArrayOf(2))

        val firstRead = event.changes.added.sortedBy { item -> item.id.clock }
        val secondRead = event.changes.added.associateBy { item -> item.id }
        assertEquals(2, firstRead.size)
        assertSame(firstRead[0], secondRead[firstRead[0].id])
        assertEquals(firstRead[1].id, firstRead[0].right?.id)
        assertEquals(firstRead[1].id, firstRead[0].next?.id)
        assertEquals(firstRead[0].id, firstRead[1].left?.id)
        assertEquals(firstRead[0].id, firstRead[1].prev?.id)
        assertSame(array, firstRead[0].parentType)
        firstRead[0].keep = true
        assertTrue(firstRead[0].keep)
    }

    @Test
    fun observeTypedAdaptsConcreteSharedTypeEventsWithoutChangingRawObservers() {
        val doc = YDoc(clientId = 1)
        val array = doc.getArray("array")
        val map = doc.getMap("map")
        val text = doc.getText("text")
        val xml = doc.getXmlFragment("xml")
        val arrayEvents = mutableListOf<YArrayEvent>()
        val mapEvents = mutableListOf<YMapEvent>()
        val textEvents = mutableListOf<YTextEvent>()
        val xmlEvents = mutableListOf<YXmlEvent>()
        val rawEvents = mutableListOf<YEvent>()

        array.observeTyped(arrayEvents::add)
        map.observeTyped(mapEvents::add)
        text.observeTyped(textEvents::add)
        xml.observeTyped(xmlEvents::add)
        array.observe(rawEvents::add)

        array.push("a")
        map.set("key", "value")
        text.insert(0, "t")
        xml.push(YXmlText("x"))

        assertSame(array, arrayEvents.single().target)
        assertEquals(listOf(YArrayDeltaOp(insert = listOf("a"))), arrayEvents.single().delta)
        assertEquals(arrayEvents.single().rawEvent, rawEvents.single())
        assertFalse(arrayEvents.single().changes.added.isEmpty())
        assertTrue(arrayEvents.single().changes.deleted.isEmpty())
        assertEquals(arrayEvents.single().delta, arrayEvents.single().changes.delta)
        assertEquals(emptyMap(), arrayEvents.single().changes.keys)
        assertSame(map, mapEvents.single().target)
        assertEquals(setOf("key"), mapEvents.single().keysChanged)
        assertEquals(YMapChangeAction.Add, mapEvents.single().changes.keys.getValue("key").action)
        assertEquals(emptyList(), mapEvents.single().changes.delta)
        assertTrue(mapEvents.single().changes.added.isEmpty())
        assertTrue(mapEvents.single().changes.deleted.isEmpty())
        assertEquals(mapEvents.single().changes.keys, mapEvents.single().mapChanges)
        assertSame(text, textEvents.single().target)
        assertEquals(YTextDelta().insert("t"), textEvents.single().delta)
        assertEquals(textEvents.single().delta, textEvents.single().changes.delta)
        assertTrue(textEvents.single().changes.added.isEmpty())
        assertTrue(textEvents.single().changes.deleted.isEmpty())
        assertSame(xml, xmlEvents.single().target)
        val xmlTarget: YXmlSharedType = xmlEvents.single().target
        assertSame(xml, xmlTarget)
        assertTrue(xmlEvents.single().childListChanged)
        assertEquals("x", xmlEvents.single().delta.single().insert!!.single().toString())
        assertEquals(xmlEvents.single().delta, xmlEvents.single().changes.delta)
    }

    @Test
    fun deepEventListObserverReceivesUpstreamShapeAndTypedOptIn() {
        val doc = YDoc(clientId = 1)
        val root = doc.getMap("root")
        val nestedArray = doc.createArray()
        val nestedMap = doc.createMap()
        root.set("array", nestedArray)
        root.set("map", nestedMap)
        var rawEvents = emptyList<YEvent>()
        var rawTransaction: YTransactionEvent? = null
        var typedEvents = emptyList<YTypedEvent<AbstractYType>>()

        root.observeDeepEvents { events, transaction ->
            rawEvents = events
            rawTransaction = transaction
        }
        root.observeDeepTyped { events, _ -> typedEvents = events }

        doc.transact(origin = "deep-origin") {
            nestedArray.push("value")
            nestedMap.set("key", true)
        }

        assertEquals("deep-origin", rawTransaction?.origin)
        assertEquals(2, rawEvents.size)
        assertEquals(listOf(listOf("array"), listOf("map")), rawEvents.map { it.path })
        assertSame(root, rawEvents[0].currentTarget)
        assertSame(nestedArray, rawEvents[0].target)
        assertSame(nestedMap, rawEvents[1].target)
        assertIs<YArrayEvent>(typedEvents[0])
        assertIs<YMapEvent>(typedEvents[1])
    }

    @Test
    fun xmlEventReportsAttributeChangesForLiveElements() {
        val doc = YDoc(clientId = 1)
        val element = doc.getXmlElement("section", "section")
        val events = mutableListOf<YXmlEvent>()
        element.observeTyped(events::add)

        element.setAttr("role", "main")

        assertEquals(setOf("role"), events.single().attributesChanged)
        val typedTarget: YXmlSharedType = events.single().target
        assertSame(element, typedTarget)
    }

    @Test
    fun xmlTextAndXmlHookUseTheirUpstreamEventFamilies() {
        val doc = YDoc(clientId = 1)
        val fragment = doc.getXmlFragment("xml")
        val xmlText = doc.createXmlText()
        val hook = doc.createXmlHook("widget")
        fragment.push(xmlText, hook)
        val textEvents = mutableListOf<YTextEvent>()
        val mapEvents = mutableListOf<YMapEvent>()

        xmlText.observeTyped(textEvents::add)
        hook.observeTyped(mapEvents::add)
        xmlText.insert(0, "text")
        hook.set("enabled", true)

        assertSame(xmlText, textEvents.single().target)
        assertIs<YTextEvent>(textEvents.single().rawEvent.asTypedEvent())
        assertSame(hook, mapEvents.single().target)
        assertIs<YMapEvent>(mapEvents.single().rawEvent.asTypedEvent())
    }

    @Test
    fun deepEventListObserversCanBeRemovedByListenerOrSubscription() {
        val doc = YDoc(clientId = 1)
        val root = doc.getMap("root")
        val nested = doc.createMap()
        root.set("nested", nested)
        var listenerCalls = 0
        var subscriptionCalls = 0
        val listener: (List<YEvent>, YTransactionEvent?) -> Unit = { _, _ -> listenerCalls++ }
        root.observeDeepEvents(listener)
        val subscription = root.observeDeepEvents { _, _ -> subscriptionCalls++ }

        nested.set("first", true)
        root.unobserveDeepEvents(listener)
        subscription.close()
        nested.set("second", true)

        assertEquals(1, listenerCalls)
        assertEquals(1, subscriptionCalls)
    }

    @Test
    fun commonChangesExcludeContentAddedAndDeletedInTheSameTransaction() {
        val doc = YDoc(clientId = 1)
        val array = doc.getArray("items")
        val events = mutableListOf<YArrayEvent>()
        array.observeTyped(events::add)

        doc.transact {
            array.push("temporary")
            array.delete(0)
        }

        assertTrue(events.single().changes.added.isEmpty())
        assertTrue(events.single().changes.deleted.isEmpty())
    }

    @Test
    fun commonChangesAddedAndDeletedSetsAreScopedToTheEventTarget() {
        val doc = YDoc(clientId = 1)
        val array = doc.getArray("items")
        val text = doc.getText("body")
        val events = mutableListOf<YArrayEvent>()
        array.observeTyped(events::add)

        doc.transact {
            array.push("array-value")
            text.insert(0, "text-value")
        }

        val changes = events.single().changes
        assertEquals(1, changes.added.size)
        assertEquals(Id(1, 0), changes.added.single().id)
        assertTrue(changes.deleted.isEmpty())
    }

    @Test
    fun commonChangesRetainItemAndLegacyIdViewsWithoutAmbiguousCopies() {
        val doc = YDoc(clientId = 1)
        val array = doc.getArray("items")
        lateinit var changes: YEventChanges<List<YArrayDeltaOp>>
        array.observeTyped { event -> changes = event.changes }

        array.push("value")

        val itemView: Set<Item> = changes.added
        val idView: IdSet = changes.added
        assertEquals(Id(1, 0), itemView.single().id)
        assertTrue(idView.has(1, 0))
        assertEquals(itemView, changes.copy().added)
        assertTrue(changes.copy(added = changes.addedIds).addedIds.has(1, 0))
        assertEquals(itemView, changes.copy(added = itemView).added)
    }

    @Test
    fun losingConcurrentMapWriteKeepsChangedKeyButNotAVisibleKeyChange() {
        val winner = YDoc(clientId = 2)
        val loser = YDoc(clientId = 1)
        val winnerMap = winner.getMap("map")
        val loserMap = loser.getMap("map")
        winnerMap.set("key", "winner")
        loserMap.set("key", "loser")
        val events = mutableListOf<YMapEvent>()
        winnerMap.observeTyped(events::add)

        winner.applyUpdate(loser.encodeStateAsUpdate())

        val event = events.single()
        assertEquals("winner", winnerMap.get("key"))
        assertEquals(setOf("key"), event.keysChanged)
        assertTrue(event.changes.keys.isEmpty())
        assertTrue(event.mapChanges.isEmpty())
    }

    @Test
    fun netNoopMutationsRetainChangedSubFlagsWhileChangesStayEmpty() {
        val doc = YDoc(clientId = 1)
        val map = doc.getMap("map")
        val text = doc.getText("text")
        val element = doc.getXmlElement("element", "section")
        val mapEvents = mutableListOf<YMapEvent>()
        val textEvents = mutableListOf<YTextEvent>()
        val xmlEvents = mutableListOf<YXmlEvent>()
        map.observeTyped(mapEvents::add)
        text.observeTyped(textEvents::add)
        element.observeTyped(xmlEvents::add)

        doc.transact {
            map.set("temporary", true)
            map.delete("temporary")
            text.insert(0, "temporary")
            text.delete(0, text.length)
            text.setAttr("temporary", true)
            text.deleteAttr("temporary")
            element.push(YXmlText("temporary"))
            element.delete(0)
            element.setAttr("temporary", true)
            element.deleteAttr("temporary")
        }

        val mapEvent = mapEvents.single()
        assertEquals(setOf("temporary"), mapEvent.keysChanged)
        assertTrue(mapEvent.changes.keys.isEmpty())

        val textEvent = textEvents.single()
        assertTrue(textEvent.childListChanged)
        assertEquals(setOf("temporary"), textEvent.keysChanged)
        assertTrue(textEvent.delta.ops.isEmpty())
        assertTrue(textEvent.changes.keys.isEmpty())

        val xmlEvent = xmlEvents.single()
        assertTrue(xmlEvent.childListChanged)
        assertEquals(setOf("temporary"), xmlEvent.attributesChanged)
        assertTrue(xmlEvent.delta.isEmpty())
        assertTrue(xmlEvent.changes.keys.isEmpty())
    }

    @Test
    fun deepEventArraysAreSortedFromShallowestToDeepestPath() {
        val doc = YDoc(clientId = 1)
        val root = doc.getMap("root")
        val child = doc.createMap()
        val grandchild = doc.createMap()
        root.set("child", child)
        child.set("grandchild", grandchild)
        var events = emptyList<YEvent>()
        var aggregateEvents = emptyList<YEvent>()
        root.observeDeepEvents { observed, _ -> events = observed }
        root.observeDeep { event -> aggregateEvents = event.deepEvents }

        doc.transact {
            grandchild.set("deep", true)
            child.set("shallow", true)
        }

        val expectedPaths = listOf(listOf("child"), listOf("child", "grandchild"))
        assertEquals(expectedPaths, events.map(YEvent::path))
        assertEquals(expectedPaths, aggregateEvents.map(YEvent::path))
    }
}
