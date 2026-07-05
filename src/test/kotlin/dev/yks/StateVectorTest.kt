package dev.yks

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class StateVectorTest {
    @Test
    fun lowLevelStateVectorBinaryHelpersRoundTrip() {
        val stateVector = mapOf(3L to 9L, 1L to 2L)
        val encoder = BinaryEncoder()

        assertEquals(encoder, writeStateVector(encoder, stateVector))
        val decoder = BinaryDecoder(encoder.toByteArray())

        assertEquals(stateVector, readStateVector(decoder))
        assertFalse(decoder.hasRemaining())
        assertContentEquals(encoder.toByteArray(), encodeStateVector(stateVector))
    }

    @Test
    fun stateVectorHelpersWorkWithYjsShapedEncoders() {
        val stateVector = mapOf(7L to 1L, 2L to 5L)
        val v1Encoder = IdSetEncoderV1()
        val v2Encoder = IdSetEncoderV2()

        assertEquals(v1Encoder, writeStateVector(v1Encoder, stateVector))
        assertEquals(v2Encoder, writeStateVector(v2Encoder, stateVector))

        assertEquals(stateVector, readStateVector(IdSetDecoderV1(v1Encoder.toByteArray())))
        assertEquals(stateVector, readStateVector(IdSetDecoderV2(v2Encoder.toByteArray())))
        assertEquals(stateVector, decodeStateVectorV2(encodeStateVectorV2(stateVector)))
    }

    @Test
    fun documentStateVectorHelpersWriteCurrentDocumentState() {
        val doc = YDoc(clientId = 1)
        doc.getText("body").insert(0, "ab")
        doc.clientId = 4
        doc.getArray("items").push("x")

        val binaryEncoder = BinaryEncoder()
        val updateEncoder = IdSetEncoderV1()

        writeDocumentStateVector(binaryEncoder, doc)
        writeDocumentStateVector(updateEncoder, doc)

        val expected = mapOf(4L to 1L, 1L to 2L)
        assertEquals(expected, readStateVector(BinaryDecoder(binaryEncoder.toByteArray())))
        assertEquals(expected, readStateVector(IdSetDecoderV1(updateEncoder.toByteArray())))
        assertContentEquals(updateEncoder.toByteArray(), encodeStateVector(doc))
        assertEquals(expected, decodeStateVector(encodeStateVectorV2(doc)))
    }
}
