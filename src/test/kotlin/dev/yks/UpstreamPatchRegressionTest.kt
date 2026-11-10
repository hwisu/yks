package dev.yks

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Public-behavior regressions from Yrs 0.28.1, also checked against the Yjs oracles. */
class UpstreamPatchRegressionTest {
    @Test
    fun pendingUpdatesReplayInEveryDeliveryOrderInBothFormats() {
        for (v2 in listOf(false, true)) {
            val source = YDoc(clientId = 1, gc = false)
            val a = source.getText("a")
            val b = source.getText("b")
            val updates = listOf<() -> Unit>(
                { a.insert(0, "A") },
                { a.insert(1, "B") },
                { b.insert(0, "C") },
            ).map { edit ->
                val before = source.encodeStateVector()
                edit()
                if (v2) source.encodeStateAsUpdateV2(before) else source.encodeStateAsUpdate(before)
            }
            for (order in listOf(listOf(0, 1, 2), listOf(0, 2, 1), listOf(1, 0, 2), listOf(1, 2, 0), listOf(2, 0, 1), listOf(2, 1, 0))) {
                val target = YDoc(clientId = 2, gc = false)
                target.getText("a")
                target.getText("b")
                order.forEach { if (v2) applyUpdateV2(target, updates[it]) else applyUpdate(target, updates[it]) }
                assertEquals("AB", target.getText("a").toString(), "v2=$v2 order=$order")
                assertEquals("C", target.getText("b").toString())
                assertEquals(source.stateVector(), target.stateVector())
                assertNull(target.store.pendingStructs)
                assertNull(target.store.pendingDs)
            }
        }
    }

    @Test
    fun undoRedoPreservesOffsetsInsideRestoredText() {
        val doc = YDoc(clientId = 1)
        val text = doc.getText("body")
        val undo = UndoManager(text)
        text.insert(0, "hello world")
        val positions = (0..text.length).map { createRelativePositionFromTypeIndex(text, it) }
        repeat(2) {
            undo.undo()
            assertEquals("", text.toString())
            undo.redo()
            assertEquals("hello world", text.toString())
            positions.forEachIndexed { index, position ->
                assertEquals(index, createAbsolutePositionFromRelativePosition(position, doc)?.index)
            }
        }
    }

    @Test
    fun selectiveHistoryClearPreservesTheOtherStackAndEmitsOnlyActualChanges() {
        for (clearUndo in listOf(false, true)) {
            val doc = YDoc(clientId = 1)
            val text = doc.getText("body")
            val undo = UndoManager(text)
            text.insert(0, "a")
            undo.stopCapturing()
            text.insert(1, "b")
            undo.undo()
            val events = mutableListOf<Pair<Boolean?, Boolean?>>()
            undo.on("stack-cleared") { events.add(it.undoStackCleared to it.redoStackCleared) }
            repeat(2) { undo.clear(clearUndoStack = clearUndo, clearRedoStack = !clearUndo) }
            assertEquals(if (clearUndo) 0 else 1, undo.undoStackSize)
            assertEquals(if (clearUndo) 1 else 0, undo.redoStackSize)
            assertEquals(listOf<Pair<Boolean?, Boolean?>>(clearUndo to !clearUndo), events)
            if (clearUndo) undo.redo() else undo.undo()
            assertEquals(if (clearUndo) "ab" else "", text.toString())
        }
    }
}
