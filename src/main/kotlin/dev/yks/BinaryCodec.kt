package dev.yks

import java.io.ByteArrayOutputStream

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

    fun writeString(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeVarUInt(bytes.size.toLong())
        out.write(bytes)
    }

    fun writeBytes(value: ByteArray) {
        writeVarUInt(value.size.toLong())
        out.write(value)
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

    fun readString(): String {
        val length = readVarUInt().toInt()
        check(length >= 0 && offset + length <= bytes.size) { "invalid string length: $length" }
        val value = bytes.copyOfRange(offset, offset + length).toString(Charsets.UTF_8)
        offset += length
        return value
    }

    fun readBytes(): ByteArray {
        val length = readVarUInt().toInt()
        check(length >= 0 && offset + length <= bytes.size) { "invalid byte array length: $length" }
        val value = bytes.copyOfRange(offset, offset + length)
        offset += length
        return value
    }
}
