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

    @Test
    fun skipStructsContributeToV1AndV2ClockBounds() {
        val v1 = BinaryEncoder().also { encoder ->
            encoder.writeVarUInt(1)
            encoder.writeVarUInt(2)
            encoder.writeVarUInt(7)
            encoder.writeVarUInt(3)
            encoder.writeByte(structSkipRefNumber)
            encoder.writeVarUInt(5)
            encoder.writeByte(structGCRefNumber)
            encoder.writeVarUInt(2)
            encoder.writeVarUInt(0)
        }.toByteArray()
        val v2 = UpdateEncoderV2().also { encoder ->
            encoder.restEncoder.writeVarUInt(1)
            encoder.restEncoder.writeVarUInt(2)
            encoder.writeClient(7)
            encoder.restEncoder.writeVarUInt(3)
            encoder.writeInfo(structSkipRefNumber)
            encoder.restEncoder.writeVarUInt(5)
            encoder.writeInfo(structGCRefNumber)
            encoder.writeLen(2)
            encoder.restEncoder.writeVarUInt(0)
        }.toByteArray()

        val expected = UpdateMeta(from = mapOf(7L to 3L), to = mapOf(7L to 10L))
        assertEquals(expected, parseUpdateMeta(v1))
        assertEquals(expected, parseUpdateMetaV2(v2))
    }
}
