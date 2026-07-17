package dev.yks

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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
    fun sequenceObserversPreserveMultipleDisjointEditSpansLikeUpstream() {
        val doc = YDoc(clientId = 1, gc = false)
        val array = doc.getArray("array")
        val text = doc.getText("text")
        val xml = doc.getXmlFragment("xml")
        array.push("a", "b", "c")
        text.insert(0, "abc")
        xml.push(YXmlText("a"), YXmlText("b"), YXmlText("c"))
        var arrayEvent: YEvent? = null
        var textEvent: YEvent? = null
        var xmlEvent: YEvent? = null
        array.observe { event -> arrayEvent = event }
        text.observe { event -> textEvent = event }
        xml.observe { event -> xmlEvent = event }

        doc.transact {
            array.insert(0, listOf("x"))
            array.push("y")
            text.insert(0, "x")
            text.insert(text.length, "y")
            xml.insert(0, listOf(YXmlText("x")))
            xml.push(YXmlText("y"))
        }

        assertEquals(
            listOf(
                YArrayDeltaOp(insert = listOf("x")),
                YArrayDeltaOp(retain = 3),
                YArrayDeltaOp(insert = listOf("y")),
            ),
            arrayEvent?.arrayDelta,
        )
        assertEquals(
            YTextDelta().insert("x").retain(3).insert("y"),
            textEvent?.textDelta,
        )
        assertEquals(
            listOf(
                YArrayDeltaOp(insert = listOf("x")),
                YArrayDeltaOp(retain = 3),
                YArrayDeltaOp(insert = listOf("y")),
            ),
            xmlEvent?.arrayDelta,
        )
    }

    @Test
    fun mapObserverCapturesOnlyChangedKeysWithoutLosingMultiKeyBeforeValues() {
        val doc = YDoc(clientId = 1, gc = false)
        val map = doc.getMap("map")
        map.set("updated", "before")
        map.set("deleted", "gone")
        var observed: YEvent? = null
        map.observe { event -> observed = event }

        doc.transact {
            map.set("updated", "after")
            map.delete("deleted")
            map.set("added", "new")
        }

        val event = assertNotNull(observed)
        assertEquals(setOf("updated", "deleted", "added"), event.keysChanged)
        assertEquals(YMapChange(YMapChangeAction.Update, "before", "after"), event.mapChanges["updated"])
        assertEquals(YMapChange(YMapChangeAction.Delete, "gone", null), event.mapChanges["deleted"])
        assertEquals(YMapChange(YMapChangeAction.Add, null, "new"), event.mapChanges["added"])
    }

    @Test
    fun replayedFullDeleteSetOnlyReportsNewlyDeletedTextLikeUpstream() {
        val source = YDoc(clientId = 1, gc = false)
        val sourceText = source.getText("body")
        sourceText.insert(0, "abc")
        val target = YDoc(clientId = 2, gc = false)
        target.applyUpdate(source.encodeStateAsUpdate())
        val deltas = mutableListOf<YTextDelta>()
        target.getText("body").observe { event -> deltas.add(event.textDelta) }

        sourceText.delete(1)
        target.applyUpdate(source.encodeStateAsUpdate())
        sourceText.delete(1)
        target.applyUpdate(source.encodeStateAsUpdate())

        assertEquals(
            listOf(
                YTextDelta().retain(1).delete(1),
                YTextDelta().retain(1).delete(1),
            ),
            deltas,
        )
    }

    @Test
    fun adjacentSameFormatInsertsInOneTransactionMergeLikeUpstream() {
        val doc = YDoc(clientId = 1, gc = false)
        val text = doc.getText("body")
        val bold = mapOf("bold" to true)
        text.insert(0, "a", bold)
        lateinit var delta: YTextDelta
        text.observe { event -> delta = event.textDelta }

        doc.transact {
            text.insert(text.length, "x", bold)
            text.insert(text.length, "y", bold)
        }

        assertEquals(YTextDelta().retain(1).insert("xy", bold), delta)
    }

    @Test
    fun beforeObserverCallsMutationIsIncludedBeforeEventAndDeduplicatedInCleanupBatch() {
        val doc = YDoc(clientId = 1, gc = false)
        val array = doc.getArray("items")
        val deltas = mutableListOf<List<YArrayDeltaOp>>()
        val updates = mutableListOf<ByteArray>()
        var mutateOnce = true
        doc.observeBeforeObserverCalls {
            if (mutateOnce) {
                mutateOnce = false
                array.push("y")
            }
        }
        array.observe { event -> deltas.add(event.arrayDelta) }
        doc.observeUpdates { update, _ -> updates.add(update) }

        array.push("x")

        assertEquals(
            listOf(
                listOf(YArrayDeltaOp(insert = listOf("x", "y"))),
                emptyList(),
            ),
            deltas,
        )
        assertEquals(2, updates.size)
        val replay = YDoc(clientId = 2)
        replay.applyUpdate(updates[0])
        assertEquals(listOf("x", "y"), replay.getArray("items").toArray())
        replay.applyUpdate(updates[1])
        assertEquals(listOf("x", "y"), replay.getArray("items").toArray())
    }

    @Test
    fun beforeObserverCallsCoverageDoesNotHideMutationOfAnotherType() {
        val doc = YDoc(clientId = 1, gc = false)
        val first = doc.getArray("first")
        val second = doc.getArray("second")
        val firstDeltas = mutableListOf<List<YArrayDeltaOp>>()
        val secondDeltas = mutableListOf<List<YArrayDeltaOp>>()
        var mutateOnce = true
        doc.observeBeforeObserverCalls {
            if (mutateOnce) {
                mutateOnce = false
                second.push("y")
            }
        }
        first.observe { event -> firstDeltas.add(event.arrayDelta) }
        second.observe { event -> secondDeltas.add(event.arrayDelta) }

        first.push("x")

        assertEquals(listOf(listOf(YArrayDeltaOp(insert = listOf("x")))), firstDeltas)
        assertEquals(listOf(listOf(YArrayDeltaOp(insert = listOf("y")))), secondDeltas)
    }

    @Test
    fun queuedPrependAndMiddleInsertRepeatBecauseCleanupCannotMergeThem() {
        val prependDoc = YDoc(clientId = 1, gc = false)
        val prependArray = prependDoc.getArray("items")
        val prependDeltas = mutableListOf<List<YArrayDeltaOp>>()
        var prependOnce = true
        prependDoc.observeBeforeObserverCalls {
            if (prependOnce) {
                prependOnce = false
                prependArray.insert(0, "y")
            }
        }
        prependArray.observe { event -> prependDeltas.add(event.arrayDelta) }

        prependArray.push("x")

        assertEquals(
            listOf(
                listOf(YArrayDeltaOp(insert = listOf("y", "x"))),
                listOf(YArrayDeltaOp(insert = listOf("y"))),
            ),
            prependDeltas,
        )

        val middleDoc = YDoc(clientId = 1, gc = false)
        val middleArray = middleDoc.getArray("items")
        middleArray.push("a", "b")
        val middleDeltas = mutableListOf<List<YArrayDeltaOp>>()
        var middleOnce = true
        middleDoc.observeBeforeObserverCalls {
            if (middleOnce) {
                middleOnce = false
                middleArray.insert(1, "y")
            }
        }
        middleArray.observe { event -> middleDeltas.add(event.arrayDelta) }

        middleArray.push("x")

        assertEquals(
            listOf(
                listOf(
                    YArrayDeltaOp(retain = 1),
                    YArrayDeltaOp(insert = listOf("y")),
                    YArrayDeltaOp(retain = 1),
                    YArrayDeltaOp(insert = listOf("x")),
                ),
                listOf(YArrayDeltaOp(retain = 1), YArrayDeltaOp(insert = listOf("y"))),
            ),
            middleDeltas,
        )
    }

    @Test
    fun queuedNonMergeableBinaryAndContentTypeRemainAddedInTheirOwnEvent() {
        val binaryDoc = YDoc(clientId = 1, gc = false)
        val binaryArray = binaryDoc.getArray("items")
        val binaryDeltas = mutableListOf<List<YArrayDeltaOp>>()
        val binary = byteArrayOf(1)
        var binaryOnce = true
        binaryDoc.observeBeforeObserverCalls {
            if (binaryOnce) {
                binaryOnce = false
                binaryArray.push("y", binary, "z")
            }
        }
        binaryArray.observe { event -> binaryDeltas.add(event.arrayDelta) }

        binaryArray.push("x")

        assertEquals(2, binaryDeltas.size)
        assertEquals(2, binaryDeltas[1][0].retain)
        assertEquals(2, binaryDeltas[1][1].insert?.size)
        assertTrue(binaryDeltas[1][1].insert?.get(0) is ByteArray)
        assertEquals("z", binaryDeltas[1][1].insert?.get(1))

        val typeDoc = YDoc(clientId = 1, gc = false)
        val typeArray = typeDoc.getArray("items")
        val typeDeltas = mutableListOf<List<YArrayDeltaOp>>()
        val child = YArray()
        var typeOnce = true
        typeDoc.observeBeforeObserverCalls {
            if (typeOnce) {
                typeOnce = false
                typeArray.push(child)
            }
        }
        typeArray.observe { event -> typeDeltas.add(event.arrayDelta) }

        typeArray.push("x")

        assertEquals(2, typeDeltas.size)
        assertEquals(1, typeDeltas[1][0].retain)
        assertEquals(1, typeDeltas[1][1].insert?.size)
        assertTrue(typeDeltas[1][1].insert?.single() is YArray)
    }

    @Test
    fun queuedDeleteIsHiddenOnlyWhenTheDeletedItemsVirtuallyMerge() {
        fun capture(values: List<Any?>): List<List<YArrayDeltaOp>> {
            val doc = YDoc(clientId = 1, gc = false)
            val array = doc.getArray("items")
            array.push(values)
            val deltas = mutableListOf<List<YArrayDeltaOp>>()
            var deleteOnce = true
            doc.observeBeforeObserverCalls {
                if (deleteOnce) {
                    deleteOnce = false
                    array.delete(0)
                }
            }
            array.observe { event -> deltas.add(event.arrayDelta) }

            array.delete(0)
            return deltas
        }

        assertEquals(
            listOf(listOf(YArrayDeltaOp(delete = 1)), emptyList()),
            capture(listOf("a", "b")),
        )
        assertEquals(
            listOf(listOf(YArrayDeltaOp(delete = 1)), listOf(YArrayDeltaOp(delete = 1))),
            capture(listOf(byteArrayOf(1), byteArrayOf(2))),
        )
    }

    @Test
    fun deleteSplitCandidateMergesOnlyItsOwnReachableSequenceDuringCleanup() {
        val sameDoc = YDoc(clientId = 1, gc = false)
        val sameArray = sameDoc.getArray("same")
        sameArray.push("a", "b")
        val sameDeltas = mutableListOf<List<YArrayDeltaOp>>()
        var appendOnce = true
        sameDoc.observeBeforeObserverCalls {
            if (appendOnce) {
                appendOnce = false
                sameArray.push("y")
            }
        }
        sameArray.observe { event -> sameDeltas.add(event.arrayDelta) }

        sameArray.delete(0)

        assertEquals(
            listOf(
                listOf(
                    YArrayDeltaOp(delete = 1),
                    YArrayDeltaOp(retain = 1),
                    YArrayDeltaOp(insert = listOf("y")),
                ),
                emptyList(),
            ),
            sameDeltas,
        )

        val crossDoc = YDoc(clientId = 1, gc = false)
        val deletedFrom = crossDoc.getArray("deletedFrom")
        val appendedTo = crossDoc.getArray("appendedTo")
        deletedFrom.push("a", "b")
        appendedTo.push("x")
        val crossDeltas = mutableListOf<List<YArrayDeltaOp>>()
        var crossOnce = true
        crossDoc.observeBeforeObserverCalls {
            if (crossOnce) {
                crossOnce = false
                appendedTo.push("y")
            }
        }
        appendedTo.observe { event -> crossDeltas.add(event.arrayDelta) }

        deletedFrom.delete(0)

        assertEquals(
            listOf(listOf(YArrayDeltaOp(retain = 1), YArrayDeltaOp(insert = listOf("y")))),
            crossDeltas,
        )
    }

    @Test
    fun newStructCleanupScanDoesNotMergeOldDeletedBoundaryBeforeItsStartClock() {
        val doc = YDoc(clientId = 1, gc = false)
        val old = doc.getArray("old")
        val trigger = doc.getArray("trigger")
        old.push("a", "b")
        old.delete(0)
        val oldDeltas = mutableListOf<List<YArrayDeltaOp>>()
        var deleteOnce = true
        doc.observeBeforeObserverCalls {
            if (deleteOnce) {
                deleteOnce = false
                old.delete(0)
            }
        }
        old.observe { event -> oldDeltas.add(event.arrayDelta) }

        trigger.push("x")

        assertEquals(listOf(listOf(YArrayDeltaOp(delete = 1))), oldDeltas)
    }

    @Test
    fun beforeObserverFailureSkipsObserversButAlwaysRunsCleanupUpdatesAndSubdocs() {
        val doc = YDoc(clientId = 1, gc = false)
        val array = doc.getArray("items")
        val original = IllegalStateException("before")
        val cleanupFailure = IllegalArgumentException("cleanup")
        val updateFailure = IllegalArgumentException("update")
        val subdocFailure = IllegalArgumentException("subdocs")
        val seen = mutableListOf<String>()
        lateinit var updatePayload: ByteArray
        doc.observeBeforeObserverCalls {
            seen.add("before")
            throw original
        }
        array.observe { seen.add("type") }
        doc.observeAfterTransactions { seen.add("afterTransaction") }
        doc.observeAfterTransactionCleanup {
            seen.add("cleanup")
            throw cleanupFailure
        }
        doc.observeUpdates { update, _ ->
            seen.add("update")
            updatePayload = update.copyOf()
            throw updateFailure
        }
        doc.observeSubdocs {
            seen.add("subdocs")
            throw subdocFailure
        }

        val thrown = assertFailsWith<IllegalStateException> {
            array.push(YDoc(guid = "nested"))
        }

        assertSame(original, thrown)
        assertEquals(listOf("before", "cleanup", "update", "subdocs"), seen)
        assertEquals(
            listOf(cleanupFailure, updateFailure, subdocFailure),
            thrown.suppressed.toList(),
        )
        val replay = YDoc(clientId = 2)
        replay.applyUpdate(updatePayload)
        assertEquals(1, replay.getArray("items").length)
        assertEquals(setOf("nested"), replay.getSubdocs().mapTo(linkedSetOf()) { it.guid })
    }

    @Test
    fun emptyOuterTransactionDoesNotRepeatNestedBeforeObserverUpdate() {
        val doc = YDoc(clientId = 1, gc = false)
        val array = doc.getArray("items")
        val updates = mutableListOf<ByteArray>()
        var mutateOnce = true
        doc.observeBeforeObserverCalls {
            if (mutateOnce) {
                mutateOnce = false
                array.push("y")
            }
        }
        doc.observeUpdates { update, _ -> updates.add(update) }

        doc.transact { }

        assertEquals(1, updates.size)
        val replay = YDoc(clientId = 2)
        replay.applyUpdate(updates.single())
        assertEquals(listOf("y"), replay.getArray("items").toArray())
    }

    @Test
    fun observerMutationIsIncludedInCurrentUpdateAndDeduplicatedFromNextEvent() {
        val doc = YDoc(clientId = 1, gc = false)
        val array = doc.getArray("items")
        val deltas = mutableListOf<List<YArrayDeltaOp>>()
        val updates = mutableListOf<ByteArray>()
        var mutateOnce = true
        array.observe { event ->
            deltas.add(event.arrayDelta)
            if (mutateOnce) {
                mutateOnce = false
                array.push("y")
            }
        }
        doc.observeUpdates { update, _ -> updates.add(update) }

        array.push("x")

        assertEquals(
            listOf(
                listOf(YArrayDeltaOp(insert = listOf("x"))),
                emptyList(),
            ),
            deltas,
        )
        assertEquals(2, updates.size)
        val replay = YDoc(clientId = 2)
        replay.applyUpdate(updates[0])
        assertEquals(listOf("x", "y"), replay.getArray("items").toArray())
        replay.applyUpdate(updates[1])
        assertEquals(listOf("x", "y"), replay.getArray("items").toArray())
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
