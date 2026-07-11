package dev.yks

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CodecHardeningTest {
    @Test
    fun rejectsOversizedDecodedCountsBeforeAllocation() {
        val stateVector = BinaryEncoder().apply {
            writeVarUInt(MAX_DECODED_COLLECTION_SIZE.toLong() + 1)
        }.toByteArray()

        val error = assertFailsWith<IllegalStateException> { decodeStateVector(stateVector) }

        assertTrue(error.message.orEmpty().contains("exceeds limit"))
    }

    @Test
    fun rejectsOversizedStringAndBufferLengthsBeforeAllocation() {
        val encodedLength = BinaryEncoder().apply {
            writeVarUInt(MAX_DECODED_BINARY_SIZE.toLong() + 1)
        }.toByteArray()

        assertFailsWith<IllegalStateException> { BinaryDecoder(encodedLength).readString() }
        assertFailsWith<IllegalStateException> { BinaryDecoder(encodedLength).readBytes() }
    }

    @Test
    fun rejectsClockOverflowInV1StructRanges() {
        val update = BinaryEncoder().apply {
            writeVarUInt(1) // clients
            writeVarUInt(1) // structs
            writeVarUInt(1) // client
            writeVarUInt(Long.MAX_VALUE) // start clock
            writeByte(0) // GC struct
            writeVarUInt(1) // GC length
            writeVarUInt(0) // empty delete set
        }.toByteArray()

        val error = assertFailsWith<IllegalStateException> { UpdateCodec.decode(update) }

        assertTrue(error.message.orEmpty().contains("clock overflow"))
    }
}
