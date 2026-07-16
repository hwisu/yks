package dev.yks

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
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

    @Test
    fun rejectsDeeplyNestedLib0ValuesBeforeTheJvmStackOverflows() {
        val update = BinaryEncoder().apply {
            writeVarUInt(1) // clients
            writeVarUInt(1) // structs
            writeVarUInt(1) // client
            writeVarUInt(0) // start clock
            writeByte(contentAnyRefNumber)
            writeVarUInt(1) // root parent
            writeString("values")
            writeVarUInt(1) // ContentAny length
            repeat(MAX_DECODED_NESTING_DEPTH + 1) {
                writeByte(117) // lib0 array
                writeVarUInt(1)
            }
            writeByte(126) // lib0 null
            writeVarUInt(0) // empty delete set
        }.toByteArray()

        val error = assertFailsWith<IllegalStateException> { applyUpdate(YDoc(clientId = 2), update) }

        assertTrue(error.message.orEmpty().contains("nesting exceeds limit"))
    }

    @Test
    fun rejectsDeeplyNestedLegacyValuesBeforeTheJvmStackOverflows() {
        val encoded = BinaryEncoder().apply {
            repeat(MAX_DECODED_NESTING_DEPTH + 1) {
                writeByte(7) // YValue.ListValue
                writeVarUInt(1)
            }
            writeByte(0) // YValue.Null
        }.toByteArray()

        val error = assertFailsWith<IllegalStateException> { readYValue(BinaryDecoder(encoded)) }

        assertTrue(error.message.orEmpty().contains("nesting exceeds limit"))
    }

    @Test
    fun rejectsAggregateDecodedPayloadBeyondTheGlobalBudget() {
        val budget = DecodeBudget()
        budget.consumePayloadBytes(MAX_DECODED_TOTAL_PAYLOAD_SIZE.toInt())

        val error = assertFailsWith<IllegalStateException> { budget.consumePayloadBytes(1) }

        assertTrue(error.message.orEmpty().contains("payload size exceeds limit"))
    }

    @Test
    fun rejectsMoreThanTheGlobalDecodedValueNodeBudget() {
        val budget = DecodeBudget()
        repeat(MAX_DECODED_VALUE_NODES) { budget.consumeNode() }

        val error = assertFailsWith<IllegalStateException> { budget.consumeNode() }

        assertTrue(error.message.orEmpty().contains("node count exceeds limit"))
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
