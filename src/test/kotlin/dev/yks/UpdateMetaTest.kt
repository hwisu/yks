package dev.yks

import kotlin.test.Test
import kotlin.test.assertEquals

class UpdateMetaTest {
    @Test
    fun parsesV1StructClockBounds() {
        val doc = YDoc(clientId = 7, gc = false)
        val text = doc.getText("body")
        text.insert(0, "ab")

        assertEquals(
            UpdateMeta(from = mapOf(7L to 0L), to = mapOf(7L to 2L)),
            parseUpdateMeta(doc.encodeStateAsUpdate()),
        )

        val state = doc.encodeStateVector()
        text.insert(2, "c")
        assertEquals(
            UpdateMeta(from = mapOf(7L to 2L), to = mapOf(7L to 3L)),
            parseUpdateMeta(doc.encodeStateAsUpdate(state)),
        )
    }

    @Test
    fun parsesGenuineV2StructClockBounds() {
        val doc = YDoc(clientId = 9, gc = false)
        doc.getArray("items").push(listOf("a", "b"))

        assertEquals(
            UpdateMeta(from = mapOf(9L to 0L), to = mapOf(9L to 2L)),
            parseUpdateMetaV2(doc.encodeStateAsUpdateV2()),
        )
    }

    @Test
    fun deleteOnlyUpdatesHaveNoStructClockBounds() {
        val doc = YDoc(clientId = 11, gc = false)
        val text = doc.getText("body")
        text.insert(0, "x")
        val state = doc.encodeStateVector()
        text.delete(0)

        assertEquals(UpdateMeta(emptyMap(), emptyMap()), parseUpdateMeta(doc.encodeStateAsUpdate(state)))
        assertEquals(UpdateMeta(emptyMap(), emptyMap()), parseUpdateMetaV2(doc.encodeStateAsUpdateV2(state)))
    }
}
