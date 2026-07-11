package dev.yks

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class Lib0RleCodecTest {
    @Test
    fun byteRleMatchesLib0() {
        val encoder = Lib0ByteRleEncoder()
        listOf(1, 1, 1, 7, 7).forEach(encoder::write)
        val encoded = encoder.toByteArray()
        assertContentEquals(hex("010207"), encoded)

        val decoder = Lib0ByteRleDecoder(encoded)
        assertEquals(listOf(1, 1, 1, 7, 7), List(5) { decoder.read() })
    }

    @Test
    fun uintOptRleMatchesLib0IncludingNegativeZero() {
        val encoder = Lib0UintOptRleEncoder()
        listOf(1L, 2L, 3L, 3L, 3L, 0L, 0L).forEach(encoder::write)
        val encoded = encoder.toByteArray()
        assertContentEquals(hex("010243014000"), encoded)

        val decoder = Lib0UintOptRleDecoder(encoded)
        assertEquals(listOf(1L, 2L, 3L, 3L, 3L, 0L, 0L), List(7) { decoder.read() })
    }

    @Test
    fun intDiffOptRleMatchesLib0() {
        val encoder = Lib0IntDiffOptRleEncoder()
        listOf(1L, 2L, 3L, 2L, 1L, 0L).forEach(encoder::write)
        val encoded = encoder.toByteArray()
        assertContentEquals(hex("03014101"), encoded)

        val decoder = Lib0IntDiffOptRleDecoder(encoded)
        assertEquals(listOf(1L, 2L, 3L, 2L, 1L, 0L), List(6) { decoder.read() })
    }

    @Test
    fun stringStreamMatchesLib0Utf16Lengths() {
        val encoder = Lib0StringEncoder()
        listOf("a", "😀", "bc", "").forEach(encoder::write)
        val encoded = encoder.toByteArray()
        assertContentEquals(hex("0761f09f9880626301420000"), encoded)

        val decoder = Lib0StringDecoder(encoded)
        assertEquals(listOf("a", "😀", "bc", ""), List(4) { decoder.read() })
    }

    private fun hex(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
