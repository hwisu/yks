package dev.yks

import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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

    @Test
    fun growableEncoderPreservesBytesAcrossCapacityBoundaries() {
        listOf(0, 1, 63, 64, 65, 127, 128, 129, 1_024).forEach { size ->
            val expected = ByteArray(size) { index -> (index * 31).toByte() }
            val encoder = BinaryEncoder()

            encoder.writeRawBytes(expected)

            assertContentEquals(expected, encoder.toByteArray(), "size=$size")
        }
    }

    @Test
    fun encoderCapacityGrowthRejectsUnrepresentableArraysWithoutLooping() {
        val maximum = Int.MAX_VALUE - 8

        assertEquals(64, nextEncoderCapacity(64, 64))
        assertEquals(128, nextEncoderCapacity(64, 65))
        assertEquals(maximum, nextEncoderCapacity(1 shl 30, maximum.toLong()))
        assertFailsWith<IllegalStateException> {
            nextEncoderCapacity(maximum, maximum.toLong() + 1L)
        }
    }

    @Test
    fun stringEncodingMatchesJavaScriptReplacementSemanticsForSurrogateEdges() {
        val values = listOf(
            "",
            "ascii",
            "é한",
            "😀",
            "\uD800",
            "\uDC00",
            "\uD800\uD800\uDC00",
            "\uDC00\uD800\uDC00",
        )

        values.forEach { value ->
            val charsetEncoder = Charsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
                .replaceWith(byteArrayOf(0xef.toByte(), 0xbf.toByte(), 0xbd.toByte()))
            val encodedBuffer = charsetEncoder.encode(CharBuffer.wrap(value))
            val expected = ByteArray(encodedBuffer.remaining()).also(encodedBuffer::get)
            val actual = BinaryEncoder().also { encoder -> encoder.writeString(value) }.toByteArray()

            assertEquals(expected.size, actual.first().toInt() and 0xff, "value=$value")
            assertContentEquals(expected, actual.copyOfRange(1, actual.size), "value=$value")
        }
    }
}
