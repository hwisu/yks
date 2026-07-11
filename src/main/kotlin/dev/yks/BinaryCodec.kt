package dev.yks

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

internal const val MAX_DECODED_COLLECTION_SIZE: Int = 1_000_000
internal const val MAX_DECODED_BINARY_SIZE: Int = 64 * 1024 * 1024

internal fun Long.toDecodedCount(
    label: String = "decoded count",
    maximum: Int = MAX_DECODED_COLLECTION_SIZE,
): Int {
    check(this >= 0 && this <= maximum.toLong()) { "$label exceeds limit $maximum: $this" }
    return toInt()
}

internal fun checkedClockAdd(left: Long, right: Long, label: String = "clock"): Long {
    check(left >= 0 && right >= 0) { "$label operands must be non-negative: $left + $right" }
    return try {
        Math.addExact(left, right)
    } catch (_: ArithmeticException) {
        error("$label overflow: $left + $right")
    }
}

class BinaryEncoder {
    private val out = ByteArrayOutputStream()

    fun writeByte(value: Int) {
        out.write(value and 0xff)
    }

    fun writeBoolean(value: Boolean) {
        writeByte(if (value) 1 else 0)
    }

    fun writeVarUInt(value: Long) {
        require(value >= 0) { "varuint cannot encode negative values: $value" }
        var current = value
        while (current >= 0x80) {
            writeByte(((current and 0x7f) or 0x80).toInt())
            current = current ushr 7
        }
        writeByte(current.toInt())
    }

    fun writeVarInt(value: Long) {
        writeVarUInt((value shl 1) xor (value shr 63))
    }

    fun writeLib0VarInt(value: Long, forceNegative: Boolean = false) {
        val negative = value < 0 || forceNegative
        var current = if (value < 0) -value else value
        writeByte(
            (if (current > 0x3f) 0x80 else 0) or
                (if (negative) 0x40 else 0) or
                (current and 0x3f).toInt(),
        )
        current /= 64
        while (current > 0) {
            writeByte((if (current > 0x7f) 0x80 else 0) or (current and 0x7f).toInt())
            current /= 128
        }
    }

    fun writeString(value: String) {
        val encoder = Charsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
            .replaceWith(byteArrayOf(0xef.toByte(), 0xbf.toByte(), 0xbd.toByte()))
        val buffer = encoder.encode(java.nio.CharBuffer.wrap(value))
        val bytes = ByteArray(buffer.remaining()).also(buffer::get)
        writeVarUInt(bytes.size.toLong())
        out.write(bytes)
    }

    fun writeBytes(value: ByteArray) {
        writeVarUInt(value.size.toLong())
        out.write(value)
    }

    fun writeRawBytes(value: ByteArray) {
        out.write(value)
    }

    fun writeFloat32(value: Float) {
        val bits = java.lang.Float.floatToRawIntBits(value)
        repeat(Int.SIZE_BYTES) { index ->
            writeByte((bits ushr ((Int.SIZE_BYTES - index - 1) * 8)) and 0xff)
        }
    }

    fun writeFloat64(value: Double) {
        val bits = java.lang.Double.doubleToRawLongBits(value)
        repeat(Long.SIZE_BYTES) { index ->
            writeByte(((bits ushr ((Long.SIZE_BYTES - index - 1) * 8)) and 0xff).toInt())
        }
    }

    fun writeInt64(value: Long) {
        repeat(Long.SIZE_BYTES) { index ->
            writeByte(((value ushr ((Long.SIZE_BYTES - index - 1) * 8)) and 0xff).toInt())
        }
    }

    fun toByteArray(): ByteArray = out.toByteArray()
}

class BinaryDecoder(private val bytes: ByteArray) {
    private var offset = 0

    fun hasRemaining(): Boolean = offset < bytes.size

    fun readByte(): Int {
        check(offset < bytes.size) { "unexpected end of input" }
        return bytes[offset++].toInt() and 0xff
    }

    fun readBoolean(): Boolean = when (val value = readByte()) {
        0 -> false
        1 -> true
        else -> error("invalid boolean byte: $value")
    }

    fun readVarUInt(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            val byte = readByte()
            result = result or ((byte and 0x7f).toLong() shl shift)
            if ((byte and 0x80) == 0) {
                return result
            }
            shift += 7
            check(shift < 64) { "varuint is too large" }
        }
    }

    fun readVarInt(): Long {
        val value = readVarUInt()
        return (value ushr 1) xor -(value and 1)
    }

    fun readLib0VarInt(): Long = readLib0VarIntWithSign().first

    internal fun readLib0VarIntWithSign(): Pair<Long, Boolean> {
        var byte = readByte()
        var result = (byte and 0x3f).toLong()
        var multiplier = 64L
        val sign = if ((byte and 0x40) != 0) -1 else 1
        while ((byte and 0x80) != 0) {
            byte = readByte()
            result += (byte and 0x7f) * multiplier
            check(multiplier <= Long.MAX_VALUE / 128) { "varint is too large" }
            multiplier *= 128
        }
        return sign * result to (sign < 0)
    }

    fun readString(): String {
        val length = readVarUInt().toDecodedCount("string byte length", minOf(MAX_DECODED_BINARY_SIZE, bytes.size - offset))
        check(length <= bytes.size - offset) { "invalid string length: $length" }
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val value = try {
            decoder.decode(ByteBuffer.wrap(bytes, offset, length)).toString()
        } catch (error: java.nio.charset.CharacterCodingException) {
            throw IllegalStateException("invalid UTF-8 string", error)
        }
        offset += length
        return value
    }

    fun readBytes(): ByteArray {
        val length = readVarUInt().toDecodedCount("byte array length", minOf(MAX_DECODED_BINARY_SIZE, bytes.size - offset))
        check(length <= bytes.size - offset) { "invalid byte array length: $length" }
        val value = bytes.copyOfRange(offset, offset + length)
        offset += length
        return value
    }

    fun readFloat32(): Float {
        var bits = 0
        repeat(Int.SIZE_BYTES) { bits = (bits shl 8) or readByte() }
        return java.lang.Float.intBitsToFloat(bits)
    }

    fun readFloat64(): Double {
        var bits = 0L
        repeat(Long.SIZE_BYTES) { bits = (bits shl 8) or readByte().toLong() }
        return java.lang.Double.longBitsToDouble(bits)
    }

    fun readInt64(): Long {
        var value = 0L
        repeat(Long.SIZE_BYTES) { value = (value shl 8) or readByte().toLong() }
        return value
    }

    fun readRemainingBytes(): ByteArray {
        val value = bytes.copyOfRange(offset, bytes.size)
        offset = bytes.size
        return value
    }
}
