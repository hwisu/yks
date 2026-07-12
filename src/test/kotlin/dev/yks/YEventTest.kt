package dev.yks

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class YEventTest {
    @Test
    fun arrayObserverReceivesRetainDeleteInsertDelta() {
        val doc = YDoc(clientId = 1, gc = false)
        val array = doc.getArray("items")
        val events = mutableListOf<YEvent>()
        array.observe { events.add(it) }

        array.push(listOf("a", null, "c"))
        array.delete(1)
        array.insert(1, listOf("b"))

        assertEquals(listOf(YArrayDeltaOp(insert = listOf("a", null, "c"))), events[0].arrayDelta)
        assertEquals(listOf(YArrayDeltaOp(retain = 1), YArrayDeltaOp(delete = 1)), events[1].arrayDelta)
        assertEquals(listOf(YArrayDeltaOp(retain = 1), YArrayDeltaOp(insert = listOf("b"))), events[2].arrayDelta)
    }

    @Test
    fun eventExposesTransactionCurrentTargetChildListChangedAndGenericDelta() {
        val doc = YDoc(clientId = 1)
        val array = doc.getArray("items")
        val events = mutableListOf<YEvent>()
        array.observe { events.add(it) }

        doc.transact("origin") {
            array.push("a")
        }

        val event = events.single()
        assertSame(array, event.target)
        assertSame(array, event.currentTarget)
        assertEquals("origin", event.transaction?.origin)
        assertTrue(event.childListChanged)
        assertEquals(listOf(YArrayDeltaOp(insert = listOf("a"))), event.delta)
        assertEquals(event.delta, event.getDelta())
    }

    @Test
    fun eventDeltaDeepRecursivelyRendersInsertedSharedTypes() {
        val doc = YDoc(clientId = 1)
        val array = doc.getArray("items")
        val nestedText = doc.createText()
        val events = mutableListOf<YEvent>()
        nestedText.insert(0, "hi", mapOf("bold" to true))
        array.observe { event -> events.add(event) }

        array.push(mapOf("child" to nestedText))

        val event = events.single()
        val expectedDeepDelta = listOf(YArrayDeltaOp(insert = listOf(
            mapOf("child" to YTextDeepDelta(delta = YTextDelta().insert("hi", mapOf("bold" to true)))),
        )))
        assertEquals(listOf(YArrayDeltaOp(insert = listOf(mapOf("child" to nestedText)))), event.delta)
        assertEquals(expectedDeepDelta, event.deltaDeep)
        assertEquals(expectedDeepDelta, event.getDelta(deep = true))
    }

    @Test
    fun eventDeepDeltaUsesTargetActiveRendererByDefault() {
        val doc = YDoc(clientId = 1)
        val array = doc.getArray("items")
        val nestedText = doc.createText()
        val renderer = object : BaseRenderer() {
            override val attributed: IdSet = createIdSet().also { it.add(1, 1, 1) }

            override fun readContent(
                contents: MutableList<AttributedContent>,
                client: Long,
                clock: Long,
                deleted: Boolean,
                content: AbstractContent,
                renderBehavior: Int,
            ) {
                if (client == 1L && clock == 1L) return
                super.readContent(contents, client, clock, deleted, content, renderBehavior)
            }
        }
        val events = mutableListOf<YEvent>()
        nestedText.insert(0, "ab")
        array.useRenderer(renderer)
        array.observe { event -> events.add(event) }

        array.push(mapOf("child" to nestedText))

        val event = events.single()
        assertEquals(
            listOf(YArrayDeltaOp(insert = listOf(
                mapOf("child" to YTextDeepDelta(delta = YTextDelta().insert("b"))),
            ))),
            event.deltaDeep,
        )
        assertEquals(
            listOf(YArrayDeltaOp(insert = listOf(
                mapOf("child" to YTextDeepDelta(delta = YTextDelta().insert("ab"))),
            ))),
            event.getDelta(deep = true, renderer = baseRenderer),
        )
    }

    @Test
    fun applyDeltaPreservesOriginOnSharedTypeEventsAndTransactions() {
        val doc = YDoc(clientId = 1)
        val array = doc.getArray("array")
        val text = doc.getText("text")
        val map = doc.getMap("map")
        val xml = doc.getXmlFragment("xml")
        val eventOrigins = mutableListOf<Pair<String, Any?>>()
        val transactionOrigins = mutableListOf<Any?>()
        array.observe { event -> eventOrigins.add("array" to event.origin) }
        text.observe { event -> eventOrigins.add("text" to event.origin) }
        map.observe { event -> eventOrigins.add("map" to event.origin) }
        xml.observe { event -> eventOrigins.add("xml" to event.origin) }
        doc.observeAfterTransactions { event -> transactionOrigins.add(event.origin) }

        array.applyDelta(listOf(YArrayDeltaOp(insert = listOf("a"))), origin = "array-origin")
        text.applyDelta(YTextDelta().insert("t"), origin = "text-origin")
        map.applyDelta(YMapDelta().setAttr("key", "value"), origin = "map-origin")
        xml.applyDelta(listOf(YArrayDeltaOp(insert = listOf(YXmlText("x")))), origin = "xml-origin")

        assertEquals(
            listOf<Any?>("array-origin", "text-origin", "map-origin", "xml-origin"),
            transactionOrigins,
        )
        assertEquals(
            listOf<Pair<String, Any?>>(
                "array" to "array-origin",
                "text" to "text-origin",
                "map" to "map-origin",
                "xml" to "xml-origin",
            ),
            eventOrigins,
        )
    }

    @Test
    fun textObserverReceivesTextDeltaWithAttributes() {
        val doc = YDoc(clientId = 1)
        val text = doc.getText("body")
        val events = mutableListOf<YEvent>()
        text.observe { events.add(it) }

        text.insert(0, "abc", mapOf("bold" to true))
        text.format(1, 1, mapOf("bold" to null, "italic" to true))

        assertEquals(YTextDelta().insert("abc", mapOf("bold" to true)), events[0].textDelta)
        assertEquals(
            YTextDelta()
                .retain(1)
                .retain(1, mapOf("bold" to null, "italic" to true)),
            events[1].textDelta,
        )
    }

    @Test
    fun textEventDeltaDeepDoesNotReplayExistingFormatsOnPlainInsert() {
        val doc = YDoc(clientId = 1)
        val text = doc.getText("body")
        val events = mutableListOf<YEvent>()
        text.insert(0, "hi", mapOf("bold" to true))
        text.observe { events.add(it) }

        text.insert(2, "!")

        assertEquals(
            YTextDelta()
                .retain(2)
                .insert("!"),
            events.single().deltaDeep,
        )
    }

    @Test
    fun textFormatObserverUsesRetainDeltaWithNullRemovedAttributes() {
        val doc = YDoc(clientId = 1)
        val text = doc.getText("body")
        val events = mutableListOf<YTextDelta>()
        text.observe { event -> events.add(event.textDelta) }

        text.insert(0, "yzb")
        text.format(1, 2, mapOf("bold" to true))
        events.clear()

        text.format(0, 2, mapOf("bold" to null))

        assertEquals(
            YTextDelta()
                .retain(1)
                .retain(1, mapOf("bold" to null)),
            events.single(),
        )
    }

    @Test
    fun mapObserverReceivesKeysChangedAndChangeActions() {
        val doc = YDoc(clientId = 1)
        val map = doc.getMap("meta")
        val events = mutableListOf<YEvent>()
        map.observe { events.add(it) }

        map.set("title", "old")
        map.set("title", "new")
        map.delete("title")

        assertEquals(setOf("title"), events[0].keysChanged)
        assertEquals(YMapChange(YMapChangeAction.Add, null, "old"), events[0].mapChanges["title"])
        assertEquals(YMapChange(YMapChangeAction.Update, "old", "new"), events[1].mapChanges["title"])
        assertEquals(YMapChange(YMapChangeAction.Delete, "new", null), events[2].mapChanges["title"])
    }

    @Test
    fun mapObserverReportsEqualValueReplacementAsUpdate() {
        val doc = YDoc(clientId = 1)
        val map = doc.getMap("meta")
        val events = mutableListOf<YEvent>()
        map.observe { events.add(it) }

        map.set("title", "same")
        map.set("title", "same")

        val event = events[1]
        assertEquals(setOf("title"), event.keysChanged)
        assertEquals(YMapChange(YMapChangeAction.Update, "same", "same"), event.mapChanges["title"])
        assertEquals(YMapDelta().setAttr("title", "same", previousValue = "same"), event.mapDelta)
    }

    @Test
    fun mapEventExposesChangedNameAndVisibleValueForLocalPrimitiveSet() {
        val doc = YDoc(clientId = 1)
        val map = doc.getMap("meta")
        val events = mutableListOf<YEvent>()
        map.observe { event -> events.add(event) }

        map.setAttr("stuff", 2)

        val event = events.single()
        assertEquals("stuff", event.name)
        assertEquals((event.target as YMap).get(event.name!!), event.value)
    }

    @Test
    fun mapEventExposesChangedNameAndVisibleValueForRemotePrimitiveSet() {
        val local = YDoc(clientId = 1)
        val remote = YDoc(clientId = 2)
        val map = local.getMap("meta")
        val events = mutableListOf<YEvent>()
        map.observe { event -> events.add(event) }

        remote.getMap("meta").setAttr("stuff", 2)
        local.applyUpdate(remote.encodeStateAsUpdate())

        val event = events.single()
        assertEquals("stuff", event.name)
        assertEquals((event.target as YMap).get(event.name!!), event.value)
    }

    @Test
    fun mapAttributeEventsUseMapDeltaAndDoNotMarkChildListChanged() {
        val doc = YDoc(clientId = 1)
        val map = doc.getMap("meta")
        val events = mutableListOf<YEvent>()
        map.observe { events.add(it) }

        map.set("title", "hello")

        val event = events.single()
        assertFalse(event.childListChanged)
        assertEquals(YMapDelta().setAttr("title", "hello"), event.delta)
        assertEquals(event.delta, event.getDelta())
    }

    @Test
    fun throwingTypeObserversCompleteTransactionAndRethrowLikeUpstream() {
        val doc = YDoc(clientId = 1)
        val map = doc.getMap("map")
        var updateCalled = false
        var throwingObserverCalled = false
        var throwingDeepObserverCalled = false

        doc.observeUpdates { _, _ -> updateCalled = true }
        map.observe {
            throwingObserverCalled = true
            error("Failure")
        }
        map.observeDeep {
            throwingDeepObserverCalled = true
            error("Failure")
        }

        assertFailsWith<IllegalStateException> {
            map.setAttr("y", "2")
        }

        assertTrue(updateCalled)
        assertTrue(throwingObserverCalled)
        assertTrue(throwingDeepObserverCalled)
        assertEquals("2", map.getAttr("y"))

        updateCalled = false
        throwingObserverCalled = false
        throwingDeepObserverCalled = false

        assertFailsWith<IllegalStateException> {
            map.setAttr("z", "3")
        }

        assertTrue(updateCalled)
        assertTrue(throwingObserverCalled)
        assertTrue(throwingDeepObserverCalled)
        assertEquals("3", map.getAttr("z"))
    }

    @Test
    fun observerReceivesRemoteOriginAndDelta() {
        val local = YDoc(clientId = 1)
        val remote = YDoc(clientId = 2)
        val text = local.getText("body")
        val events = mutableListOf<YEvent>()
        text.observe { events.add(it) }

        remote.getText("body").insert(0, "hi")
        local.applyUpdate(remote.encodeStateAsUpdate(), origin = "remote-sync")

        assertEquals("remote-sync", events.single().origin)
        assertEquals(YTextDelta().insert("hi"), events.single().textDelta)
        assertTrue(events.single().adds(2, 0))
        assertTrue(events.single().adds(2, 1))
    }

    @Test
    fun eventAddsAndDeletesExposeTransactionIdSets() {
        val doc = YDoc(clientId = 1)
        val array = doc.getArray("items")
        val events = mutableListOf<YEvent>()
        array.observe { events.add(it) }

        doc.transact {
            array.insert(0, listOf("a", "b"))
            array.delete(0)
        }
        val event = events.single()
        val decodedStructs = decodeUpdate(event.update).structs
        val addedStruct = event.transaction!!.addedStructs.first { it.id == Id(1, 0) }
        val deletedStruct = event.transaction!!.deletedStructs.single()

        assertTrue(event.adds(Id(1, 0)))
        assertTrue(event.adds(1, 1))
        assertTrue(event.adds(addedStruct))
        assertTrue(event.adds(decodedStructs.first { it.id == Id(1, 0) }))
        assertTrue(event.deletes(Id(1, 0)))
        assertTrue(event.deletes(deletedStruct))
        assertTrue(event.deletes(decodedStructs.first { it.id == Id(1, 0) }))
        assertFalse(event.deletes(1, 1))
        assertTrue(event.deletes(addedStruct))
        assertFalse(event.adds(1, 2))
        assertTrue(event.insertSet.has(1, 0))
        assertTrue(event.deleteSet.contains(Id(1, 0)))
    }

    @Test
    fun eventDeepDeltaOmitsUnclaimedContentInsertedAndDeletedInSameTransaction() {
        val doc = YDoc(clientId = 1)
        val array = doc.getArray("items")
        val events = mutableListOf<YEvent>()
        array.observe { events.add(it) }

        doc.transact {
            array.insert(0, listOf("a", "b"))
            array.delete(0)
        }

        val event = events.single()
        assertEquals(listOf(YArrayDeltaOp(insert = listOf("b"))), event.delta)
        assertEquals(listOf(YArrayDeltaOp(insert = listOf("b"))), event.deltaDeep)
        assertEquals(listOf(YArrayDeltaOp(insert = listOf("b"))), event.getDelta(deep = true, renderer = baseRenderer))
    }

    @Test
    fun eventDeepDeltaRendersClaimedContentInsertedAndDeletedInSameTransaction() {
        val doc = YDoc(clientId = 1, gc = false)
        val array = doc.getArray("items")
        val renderer = TwosetRenderer(
            inserts = createIdMap(),
            deletes = createIdMap().also { ids -> ids.add(1, 0, 1, emptyList()) },
        )
        val events = mutableListOf<YEvent>()
        array.useRenderer(renderer)
        array.observe { events.add(it) }

        doc.transact {
            array.insert(0, listOf("a", "b"))
            array.delete(0)
        }

        val event = events.single()
        assertEquals(listOf(YArrayDeltaOp(insert = listOf("b"))), event.delta)
        assertEquals(
            listOf(
                YArrayDeltaOp(insert = listOf("a"), attributes = mapOf("delete" to emptyList<String>())),
                YArrayDeltaOp(insert = listOf("b")),
            ),
            event.deltaDeep,
        )
        assertEquals(listOf(YArrayDeltaOp(insert = listOf("b"))), event.getDelta(deep = true, renderer = baseRenderer))
    }

    @Test
    fun observeCallbackCanReceiveTransactionLikeUpstream() {
        val doc = YDoc(clientId = 1)
        val array = doc.getArray("items")
        val seen = mutableListOf<Pair<YEvent, YTransactionEvent?>>()
        val listener: (YEvent, YTransactionEvent?) -> Unit = { event, transaction -> seen.add(event to transaction) }

        array.observe(listener)
        doc.transact("direct-origin") {
            array.push("a")
        }
        array.unobserve(listener)
        array.push("b")

        val (event, transaction) = seen.single()
        assertSame(event.transaction, transaction)
        assertEquals("direct-origin", transaction?.origin)
        assertTrue(transaction?.adds(1, 0) == true)
        assertEquals(listOf(YArrayDeltaOp(insert = listOf("a"))), event.arrayDelta)
    }

    @Test
    fun textObserverReportsContentAndAttrsChangedInSameTransaction() {
        val doc = YDoc(clientId = 1)
        val text = doc.getText("body")
        val events = mutableListOf<YEvent>()
        text.observe { events.add(it) }

        doc.transact {
            text.insert(0, "hi")
            text.setAttr("lang", "en")
        }

        val event = events.single()
        assertEquals(YTextDelta().insert("hi"), event.textDelta)
        assertEquals(setOf("lang"), event.keysChanged)
        assertEquals(YMapChange(YMapChangeAction.Add, null, "en"), event.mapChanges["lang"])
        assertEquals(YMapDelta().setAttr("lang", "en"), event.mapDelta)
    }

    @Test
    fun arrayObserverReportsContentAndAttrsChangedInSameTransaction() {
        val doc = YDoc(clientId = 1)
        val array = doc.getArray("items")
        val events = mutableListOf<YEvent>()
        array.observe { events.add(it) }

        doc.transact {
            array.push("a")
            array.setAttr("role", "list")
        }

        val event = events.single()
        assertEquals(listOf(YArrayDeltaOp(insert = listOf("a"))), event.arrayDelta)
        assertEquals(setOf("role"), event.keysChanged)
        assertEquals(YMapChange(YMapChangeAction.Add, null, "list"), event.mapChanges["role"])
    }

    @Test
    fun xmlFragmentObserverReceivesChildDelta() {
        val doc = YDoc(clientId = 1)
        val xml = doc.getXmlFragment("xml")
        val events = mutableListOf<YEvent>()
        xml.observe { events.add(it) }

        xml.push(listOf(YXmlElement("p").also { it.push(listOf(YXmlText("hello"))) }))
        xml.delete(0)

        assertEquals(
            listOf(
                YArrayDeltaOp(
                    insert = listOf(
                        mapOf(
                            "nodeName" to "p",
                            "attributes" to emptyMap<String, Any?>(),
                            "children" to listOf("hello"),
                        ),
                    ),
                ),
            ),
            events[0].arrayDelta,
        )
        assertEquals(listOf(YArrayDeltaOp(delete = 1)), events[1].arrayDelta)
    }

    @Test
    fun xmlElementObserverDeltaDeepPreservesLiveTextChildFormatting() {
        val doc = YDoc(clientId = 1)
        val element = doc.createXmlElement("p")
        val text = doc.createText()
        val events = mutableListOf<YEvent>()
        text.insert(0, "hi", mapOf("bold" to true))
        doc.getXmlFragment("root").push(element)
        element.observe { events.add(it) }

        element.push(text)

        val event = events.single()
        assertEquals(listOf(YArrayDeltaOp(insert = listOf(text))), event.delta)
        assertEquals(
            listOf(YTextDeepDelta(delta = YTextDelta().insert("hi", mapOf("bold" to true)))),
            event.deltaDeep,
        )
        assertTrue(event.childListChanged)
    }

    @Test
    fun xmlElementDeepObserverDeltaDeepPreservesNestedLiveTextFormattingChanges() {
        val doc = YDoc(clientId = 1)
        val element = doc.createXmlElement("p")
        val text = doc.createText()
        val events = mutableListOf<YEvent>()
        text.insert(0, "hi")
        element.push(text)
        doc.getXmlFragment("root").push(element)
        element.observeDeep { events.add(it) }

        text.format(0, 2, mapOf("bold" to true))

        val event = events.single()
        val nestedEvent = event.deepEvents.single()
        val expectedTextDelta = YTextDelta().retain(2, mapOf("bold" to true))
        assertSame(element, event.target)
        assertSame(text, event.changedTarget)
        assertEquals(listOf(0), event.path)
        assertSame(text, nestedEvent.target)
        assertEquals(expectedTextDelta, nestedEvent.textDelta)
        assertEquals(
            listOf(YTextDeepDelta(delta = expectedTextDelta)),
            event.deltaDeep,
        )
    }

    @Test
    fun xmlTextObserverUsesTextDeltasAndDeepDeltas() {
        val doc = YDoc(clientId = 1)
        val text = doc.createXmlText()
        val deltas = mutableListOf<Any>()
        val deepDeltas = mutableListOf<Any>()
        doc.getXmlFragment("root").push(text)
        text.observe { event ->
            deltas.add(event.delta)
            deepDeltas.add(event.deltaDeep)
        }

        text.insert(0, "hi")
        text.format(0, 2, mapOf("bold" to true))

        assertEquals(YTextDelta().insert("hi"), deltas[0])
        assertEquals(YTextDelta().retain(2, mapOf("bold" to true)), deltas[1])
        assertEquals(YTextDelta().insert("hi"), deepDeltas[0])
        assertEquals(YTextDelta().retain(2, mapOf("bold" to true)), deepDeltas[1])
    }

    @Test
    fun deepObserverEventsExposeCurrentTargetAndNestedTransaction() {
        val doc = YDoc(clientId = 1)
        val root = doc.getArray("root")
        val nested = doc.createMap()
        root.push(nested)
        val events = mutableListOf<YEvent>()
        root.observeDeep { events.add(it) }

        doc.transact("deep-origin") {
            nested.set("title", "hello")
        }

        val event = events.single()
        val nestedEvent = event.deepEvents.single()
        assertSame(root, event.target)
        assertSame(root, event.currentTarget)
        assertSame(nested, event.changedTarget)
        assertEquals(listOf(0), event.path)
        assertEquals("deep-origin", event.transaction?.origin)
        assertSame(nested, nestedEvent.target)
        assertSame(root, nestedEvent.currentTarget)
        assertEquals(YMapDelta().setAttr("title", "hello"), nestedEvent.delta)
    }

    @Test
    fun nestedTypeAddedAndMutatedInSameTransactionDoesNotFireDirectNestedEvent() {
        val doc = YDoc(clientId = 1)
        val root = doc.getArray("root")
        val nested = doc.createMap()
        val rootEvents = mutableListOf<YEvent>()
        val nestedEvents = mutableListOf<YEvent>()
        val transactions = mutableListOf<YTransactionEvent>()
        root.observe { event -> rootEvents.add(event) }
        nested.observe { event -> nestedEvents.add(event) }
        doc.observeAfterTransactions { event -> transactions.add(event) }

        doc.transact("insert-nested") {
            root.push(nested)
            nested.setAttr("title", "hello")
        }

        val transaction = transactions.single()
        val rootEvent = rootEvents.single()
        assertEquals(emptyList(), nestedEvents)
        assertEquals(setOf("root"), transaction.changedParents)
        assertEquals(setOf(root), transaction.changedTypes)
        assertEquals(listOf(YArrayDeltaOp(insert = listOf(nested))), rootEvent.arrayDelta)
        assertTrue(rootEvent.childListChanged)

        nested.setAttr("title", "next")

        assertEquals(YMapDelta().setAttr("title", "next", "hello"), nestedEvents.single().mapDelta)
    }

    @Test
    fun observeDeepCallbackCanReceiveTransactionLikeUpstream() {
        val doc = YDoc(clientId = 1)
        val root = doc.getMap("root")
        val nested = doc.createMap()
        root.setAttr("child", nested)
        val seen = mutableListOf<Pair<YEvent, YTransactionEvent?>>()
        val listener: (YEvent, YTransactionEvent?) -> Unit = { event, transaction -> seen.add(event to transaction) }

        root.observeDeep(listener)
        doc.transact("deep-origin") {
            nested.setAttr("k", "v")
        }
        root.unobserveDeep(listener)
        nested.setAttr("ignored", true)

        val (event, transaction) = seen.single()
        assertSame(event.transaction, transaction)
        assertEquals("deep-origin", transaction?.origin)
        assertSame(root, event.target)
        assertSame(nested, event.changedTarget)
        assertEquals(listOf("child"), event.path)
        assertEquals(YMapDelta().setAttr("k", "v"), event.deepEvents.single().mapDelta)
    }

    @Test
    fun observerTriggeredTransactionsAreQueuedUntilCurrentObserverReturns() {
        val doc = YDoc(clientId = 1)
        val array = doc.getArray("items")
        val values = mutableListOf<Int>()

        array.observe {
            if (array.length == 1) {
                array.insert(1, listOf(1))
                values.add(0)
            } else {
                values.add(1)
            }
        }

        array.insert(0, listOf(0))

        assertEquals(listOf(0, 1), values)
        assertEquals(listOf(0L, 1L), array.toArray())
    }

    @Test
    fun observerExceptionsDoNotPreventDeepObserversOrUpdateListeners() {
        val doc = YDoc(clientId = 1)
        val map = doc.getMap("map")
        var directObserverCalled = false
        var deepObserverCalled = false
        var updateObserverCalled = false

        doc.observeUpdates { _, _ -> updateObserverCalled = true }
        map.observe {
            directObserverCalled = true
            error("direct failure")
        }
        map.observeDeep {
            deepObserverCalled = true
            error("deep failure")
        }

        val error = assertFailsWith<IllegalStateException> {
            map.setAttr("key", "value")
        }

        assertEquals("direct failure", error.message)
        assertTrue(error.suppressed.any { it.message == "deep failure" })
        assertTrue(directObserverCalled)
        assertTrue(deepObserverCalled)
        assertTrue(updateObserverCalled)
        assertEquals("value", map.getAttr("key"))
    }
}
