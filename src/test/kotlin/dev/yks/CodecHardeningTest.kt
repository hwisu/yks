package dev.yks

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CodecHardeningTest {
    @Test
    fun rejectsCountsOutsideTheJvmCollectionRangeBeforeAllocation() {
        val stateVector = BinaryEncoder().apply {
            writeVarUInt(Int.MAX_VALUE.toLong() + 1)
        }.toByteArray()

        val error = assertFailsWith<IllegalStateException> { decodeStateVector(stateVector) }

        assertTrue(error.message.orEmpty().contains("exceeds limit"))
    }

    @Test
    fun rejectsStringAndBufferLengthsLargerThanTheAvailableJvmByteArray() {
        val encodedLength = BinaryEncoder().apply {
            writeVarUInt(Int.MAX_VALUE.toLong())
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

    @Test
    fun acceptsValueNestingBeyondTheFormerYksSpecificLimit() {
        val encoded = BinaryEncoder().apply {
            repeat(257) {
                writeByte(7) // YValue.ListValue
                writeVarUInt(1)
            }
            writeByte(0) // YValue.Null
        }.toByteArray()

        var value = readYValue(BinaryDecoder(encoded))
        repeat(257) { value = (value as YValue.ListValue).value.single() }
        assertEquals(YValue.Null, value)
    }

    @Test
    fun strictJsonRejectsRawControlCharactersLikeUpstreamJsonParse() {
        val error = assertFailsWith<IllegalStateException> { parseJsonLiteral("\"\n\"") }

        assertEquals("unescaped control character in JSON string", error.message)
        listOf(
            "\"\\x\"",
            "\"\\uZZZZ\"",
            "[1,]",
            "{\"a\":1,}",
            "01",
            "-01",
            "true false",
            "\u00A0true",
        ).forEach { malformed ->
            assertFailsWith<IllegalStateException>("accepted malformed JSON: $malformed") {
                parseJsonLiteral(malformed)
            }
        }
    }
}
