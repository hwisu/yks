package dev.yks

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class YMapDeltaTest {
    @Test
    fun toDeltaRendersMapAttributesAsSetOps() {
        val doc = YDoc(clientId = 1)
        val map = doc.getMap("meta")
        map.set("title", "hello")
        map.set("count", 2)
        map.set("nullable", null)

        assertEquals(
            YMapDelta()
                .setAttr("count", 2L)
                .setAttr("nullable", null)
                .setAttr("title", "hello"),
            map.toDelta(),
        )
    }

    @Test
    fun applyDeltaSupportsSetUpdateAndDelete() {
        val doc = YDoc(clientId = 1)
        val map = doc.getMap("meta")
        map.set("title", "old")
        map.set("remove", true)

        map.applyDelta(
            YMapDelta()
                .setAttr("title", "new")
                .setAttr("count", 3)
                .deleteAttr("remove"),
        )

        assertEquals(mapOf("count" to 3L, "title" to "new"), map.toMap())
    }

    @Test
    fun mapDeltaSetAttrsMatchesUpstreamBulkBuilder() {
        val doc = YDoc(clientId = 1)
        val map = doc.getMap("meta")

        val delta = YMapDelta().setAttrs(
            linkedMapOf(
                "object" to mapOf("x" to 1),
                "boolean" to true,
            ),
        )
        map.applyDelta(delta)
        val fromDelta = Type.from(delta, doc, "from-delta")

        assertEquals(YMapDelta().setAttr("object", mapOf("x" to 1)).setAttr("boolean", true), delta)
        assertEquals(mapOf("boolean" to true, "object" to mapOf("x" to 1L)), map.toMap())
        assertEquals(mapOf("boolean" to true, "object" to mapOf("x" to 1L)), fromDelta.toMap())
    }

    @Test
    fun mapDeltaSetAttrsCanCarryPreviousValues() {
        val delta = YMapDelta().setAttrs(
            mapOf("title" to "new", "count" to 2),
            previousValues = mapOf("title" to "old"),
        )

        assertEquals(
            YMapDelta()
                .setAttr("title", "new", previousValue = "old")
                .setAttr("count", 2),
            delta,
        )
    }

    @Test
    fun mapDeltaAllowsEmptyStringKeysLikeUpstream() {
        val doc = YDoc(clientId = 1)
        val map = doc.getMap("meta")

        map.applyDelta(YMapDelta().setAttr("", "empty").setAttr("title", "hello"))

        assertEquals(mapOf("" to "empty", "title" to "hello"), map.toMap())
        assertEquals(YMapDelta().setAttr("", "empty").setAttr("title", "hello"), map.toDelta())

        map.applyDelta(YMapDelta().deleteAttr(""))

        assertEquals(mapOf("title" to "hello"), map.toMap())
    }

    @Test
    fun mapObserverReceivesDeltaWithPreviousValues() {
        val doc = YDoc(clientId = 1)
        val map = doc.getMap("meta")
        val events = mutableListOf<YEvent>()
        map.observe { events.add(it) }

        map.set("title", "old")
        map.set("title", "new")
        map.delete("title")

        assertEquals(YMapDelta().setAttr("title", "old"), events[0].mapDelta)
        assertEquals(YMapDelta().setAttr("title", "new", previousValue = "old"), events[1].mapDelta)
        assertEquals(YMapDelta().deleteAttr("title", previousValue = "new"), events[2].mapDelta)
    }

    @Test
    fun mapObserverDeltaUsesTransactionStartAsPreviousValueLikeUpstream() {
        val doc = YDoc(clientId = 1)
        val map = doc.getMap("meta")
        val events = mutableListOf<YEvent>()
        map.observe { events.add(it) }

        map.setAttr("a", 1)
        assertEquals(YMapDelta().setAttr("a", 1L), events.last().mapDelta)

        map.setAttr("a", 2)
        assertEquals(YMapDelta().setAttr("a", 2L, previousValue = 1L), events.last().mapDelta)

        doc.transact {
            map.setAttr("a", 3)
            map.setAttr("a", 4)
        }
        assertEquals(YMapDelta().setAttr("a", 4L, previousValue = 2L), events.last().mapDelta)

        doc.transact {
            map.setAttr("b", 1)
            map.setAttr("b", 2)
        }
        assertEquals(YMapDelta().setAttr("b", 2L), events.last().mapDelta)

        doc.transact {
            map.setAttr("c", 1)
            map.deleteAttr("c")
        }
        assertTrue(events.last().mapDelta.isEmpty())
        assertTrue(events.last().keysChanged.isEmpty())

        doc.transact {
            map.setAttr("d", 1)
            map.setAttr("d", 2)
        }
        assertEquals(YMapDelta().setAttr("d", 2L), events.last().mapDelta)
    }

    @Test
    fun mapDeltaConvergesThroughUpdatesAndUndo() {
        val left = YDoc(clientId = 1)
        val right = YDoc(clientId = 2)
        val map = left.getMap("meta")
        val undoManager = UndoManager(map, UndoManagerOptions(captureTimeoutMillis = 0))

        map.applyDelta(YMapDelta().setAttr("title", "hello").setAttr("enabled", true))
        map.applyDelta(YMapDelta().setAttr("title", "updated").deleteAttr("enabled"))
        right.applyUpdate(left.encodeStateAsUpdate())

        assertEquals(mapOf("title" to "updated"), right.getMap("meta").toMap())

        undoManager.undo()
        assertEquals(mapOf("enabled" to true, "title" to "hello"), map.toMap())

        undoManager.redo()
        assertEquals(mapOf("title" to "updated"), map.toMap())
    }

    @Test
    fun concurrentMapSetConflictUsesCurrentItemOrdering() {
        val users = (0L..2L).map { clientId -> YDoc(clientId = clientId) }
        val maps = users.map { doc -> doc.getMap("map") }

        maps[0].setAttr("stuff", "c0")
        maps[1].setAttr("stuff", "c1")
        maps[1].setAttr("stuff", "c2")
        maps[2].setAttr("stuff", "c3")
        syncAll(users)

        assertEquals(listOf("c3", "c3", "c3"), maps.map { map -> map.getAttr("stuff") })
    }

    @Test
    fun concurrentMapSetThenDeleteHidesLowerPriorityLiveValueLikeUpstream() {
        val users = (0L..2L).map { clientId -> YDoc(clientId = clientId) }
        val maps = users.map { doc -> doc.getMap("map") }

        maps[0].setAttr("stuff", "c0")
        maps[1].setAttr("stuff", "c1")
        maps[1].deleteAttr("stuff")
        syncAll(users)

        maps.forEach { map ->
            assertFalse(map.hasAttr("stuff"))
            assertEquals(null, map.getAttr("stuff"))
        }
    }

    @Test
    fun concurrentMapDeleteConflictHidesLowerPriorityLiveValues() {
        val users = (0L..3L).map { clientId -> YDoc(clientId = clientId) }
        val maps = users.map { doc -> doc.getMap("map") }

        maps[0].setAttr("stuff", "c0")
        maps[1].setAttr("stuff", "c1")
        maps[1].setAttr("stuff", "c2")
        maps[2].setAttr("stuff", "c3")
        syncAll(users)

        maps[0].setAttr("otherstuff", "c0")
        maps[1].setAttr("otherstuff", "c1")
        maps[2].setAttr("otherstuff", "c2")
        maps[3].setAttr("otherstuff", "c3")
        maps[3].clear()
        syncAll(users)

        maps.forEach { map ->
            assertFalse(map.hasAttr("stuff"))
            assertFalse(map.hasAttr("otherstuff"))
            assertEquals(0, map.attrSize)
        }
    }

    private fun syncAll(users: List<YDoc>) {
        val updates = users.map { doc -> doc.encodeStateAsUpdate() }
        users.forEach { target ->
            updates.forEach { update -> target.applyUpdate(update) }
        }
    }
}
