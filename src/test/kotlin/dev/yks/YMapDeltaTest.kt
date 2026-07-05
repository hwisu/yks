package dev.yks

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

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
