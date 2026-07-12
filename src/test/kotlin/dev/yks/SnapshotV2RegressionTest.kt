package dev.yks

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SnapshotV2RegressionTest {
    @Test
    fun snapshotContainsUpdateV2DecodesGenuineV2StructsAndDeletes() {
        val doc = YDoc(clientId = 1, gc = false)
        val text = doc.getText("body")
        val before = snapshot(doc)

        text.insert(0, "hello")
        val insertV2 = encodeStateAsUpdateV2(doc)
        val afterInsert = snapshot(doc)
        val stateAfterInsert = encodeStateVector(doc)

        text.delete(1, 3)
        val deleteV2 = encodeStateAsUpdateV2(doc, stateAfterInsert)
        val afterDelete = snapshot(doc)

        assertFalse(snapshotContainsUpdateV2(before, insertV2))
        assertTrue(snapshotContainsUpdateV2(afterInsert, insertV2))
        assertFalse(snapshotContainsUpdateV2(afterInsert, deleteV2))
        assertTrue(snapshotContainsUpdateV2(afterDelete, insertV2))
        assertTrue(snapshotContainsUpdateV2(afterDelete, deleteV2))
    }

    @Test
    fun snapshotContainsUpdateContinuesToDecodeV1() {
        val doc = YDoc(clientId = 1, gc = false)
        val before = snapshot(doc)
        doc.getArray("items").push("value")
        val updateV1 = encodeStateAsUpdate(doc)

        assertFalse(snapshotContainsUpdate(before, updateV1))
        assertTrue(snapshotContainsUpdate(snapshot(doc), updateV1))
    }
}
