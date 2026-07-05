package dev.yks

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class BinaryCodecTest {
    @Test
    fun varUIntRoundTripsBoundaryValues() {
        val values = listOf(0L, 1L, 127L, 128L, 16_384L, Int.MAX_VALUE.toLong(), Long.MAX_VALUE)
        val encoder = BinaryEncoder()
        values.forEach(encoder::writeVarUInt)
        val decoder = BinaryDecoder(encoder.toByteArray())
        assertEquals(values, values.map { decoder.readVarUInt() })
    }

    @Test
    fun yValueRoundTripsNestedJsonLikeValues() {
        val original = YValue.from(
            mapOf(
                "null" to null,
                "bool" to true,
                "long" to -42L,
                "double" to 1.25,
                "string" to "hello",
                "bytes" to byteArrayOf(1, 2, 3),
                "list" to listOf("a", 2),
            ),
        )
        val encoder = BinaryEncoder()
        writeYValue(encoder, original)
        val decoded = readYValue(BinaryDecoder(encoder.toByteArray()))
        assertEquals(original, decoded)
        assertContentEquals(byteArrayOf(1, 2, 3), ((decoded as YValue.MapValue).value.getValue("bytes") as YValue.BinaryValue).bytes())
    }
}

