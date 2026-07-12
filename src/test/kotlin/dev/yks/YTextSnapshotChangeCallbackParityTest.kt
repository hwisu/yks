package dev.yks

import kotlin.test.Test
import kotlin.test.assertEquals

class YTextSnapshotChangeCallbackParityTest {
    @Test
    fun customChangeCallbackRunsOnceForAContiguousInsertedString() {
        val doc = YDoc(clientId = 7, gc = false)
        val text = doc.getText("body")
        val before = snapshot(doc)
        text.insert(0, "abc")
        val after = snapshot(doc)
        val calls = mutableListOf<Pair<String, Id>>()

        val delta = text.toDelta(after, before) { change, id ->
            calls.add(change to id)
            mapOf("type" to change, "clock" to id.clock)
        }

        assertEquals(listOf("added" to Id(7, 0)), calls)
        assertEquals(
            YTextDelta().insert(
                "abc",
                mapOf("ychange" to mapOf("type" to "added", "clock" to 0L)),
            ),
            delta,
        )
    }
}
