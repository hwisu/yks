package dev.yks

import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class Lib0CodecTest {
    @Test
    fun signedVarIntsMatchLib0GoldenBytes() {
        val fixtures = linkedMapOf(
            0L to "00",
            -1L to "41",
            1L to "01",
            -63L to "7f",
            63L to "3f",
            -64L to "c001",
            64L to "8001",
            -127L to "ff01",
            127L to "bf01",
            -128L to "c002",
            128L to "8002",
        )

        fixtures.forEach { (value, hex) ->
            val encoder = BinaryEncoder().also { it.writeLib0VarInt(value) }
            assertContentEquals(hex.hexBytes(), encoder.toByteArray(), "value=$value")
            assertEquals(value, BinaryDecoder(hex.hexBytes()).readLib0VarInt())
        }
    }

    @Test
    fun anyValuesMatchLib0GoldenBytes() {
        val fixtures = listOf(
            Lib0Undefined to "7f",
            null to "7e",
            false to "79",
            true to "78",
            0 to "7d00",
            -1 to "7d41",
            2_147_483_647 to "7dbfffffff0f",
            Int.MIN_VALUE to "7ccf000000",
            2_147_483_648L to "7c4f000000",
            1.25 to "7c3fa00000",
            1.1 to "7b3ff199999999999a",
            Double.NaN to "7b7ff8000000000000",
            Double.POSITIVE_INFINITY to "7c7f800000",
            "é" to "7702c3a9",
            byteArrayOf(0, 1, 255.toByte()) to "74030001ff",
            listOf(null, 1, "x") to "75037e7d01770178",
            linkedMapOf("b" to 1, "a" to true) to "760201627d01016178",
            BigInteger.valueOf(Long.MAX_VALUE) to "7a7fffffffffffffff",
            BigInteger.valueOf(Long.MIN_VALUE) to "7a8000000000000000",
        )

        fixtures.forEach { (value, hex) ->
            val encoder = BinaryEncoder().also { writeLib0Any(it, value) }
            assertContentEquals(hex.hexBytes(), encoder.toByteArray(), "value=$value")
        }
    }

    @Test
    fun anyDecoderKeepsUndefinedDistinctFromNull() {
        assertSame(Lib0Undefined, readLib0Any(BinaryDecoder("7f".hexBytes())))
        assertEquals(null, readLib0Any(BinaryDecoder("7e".hexBytes())))
    }

    @Test
    fun objectIntegerKeysFollowJavaScriptObjectKeysOrder() {
        val value = linkedMapOf("10" to 10, "2" to 2, "a" to 1, "01" to 1)
        val encoder = BinaryEncoder().also { writeLib0Any(it, value) }

        assertContentEquals(
            "760401327d020231307d0a01617d010230317d01".hexBytes(),
            encoder.toByteArray(),
        )
    }

    @Test
    fun stringsReplaceLoneSurrogatesLikeTextEncoderAndRejectMalformedUtf8() {
        val encoded = BinaryEncoder().also { it.writeString("\uD800") }.toByteArray()
        assertContentEquals("03efbfbd".hexBytes(), encoded)
        assertEquals("�", BinaryDecoder(encoded).readString())
        assertFailsWith<IllegalStateException> { BinaryDecoder("01ff".hexBytes()).readString() }
    }
}

private fun String.hexBytes(): ByteArray {
    require(length % 2 == 0)
    return chunked(2).map { byte -> byte.toInt(16).toByte() }.toByteArray()
}
