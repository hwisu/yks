package dev.yks

internal class Lib0ByteRleEncoder {
    private val encoder = BinaryEncoder()
    private var state: Int? = null
    private var count = 0L

    fun write(value: Int) {
        require(value in 0..255) { "RLE byte must be unsigned" }
        if (state == value) {
            count++
        } else {
            if (count > 0) encoder.writeVarUInt(count - 1)
            encoder.writeByte(value)
            state = value
            count = 1
        }
    }

    fun toByteArray(): ByteArray = encoder.toByteArray()
}

internal class Lib0ByteRleDecoder(bytes: ByteArray) {
    private val decoder = BinaryDecoder(bytes)
    private var state = 0
    private var count = 0L

    fun read(): Int {
        if (count == 0L) {
            state = decoder.readByte()
            count = if (decoder.hasRemaining()) decoder.readVarUInt() + 1 else Long.MAX_VALUE
        }
        count--
        return state
    }
}

internal class Lib0UintOptRleEncoder {
    private val encoder = BinaryEncoder()
    private var state = 0L
    private var count = 0L

    fun write(value: Long) {
        require(value >= 0) { "optimized uint RLE cannot encode negative values" }
        if (state == value) {
            count++
        } else {
            flush()
            state = value
            count = 1
        }
    }

    private fun flush() {
        if (count == 0L) return
        encoder.writeLib0VarInt(state, forceNegative = count > 1)
        if (count > 1) encoder.writeVarUInt(count - 2)
    }

    fun toByteArray(): ByteArray {
        flush()
        count = 0
        return encoder.toByteArray()
    }
}

internal class Lib0UintOptRleDecoder(bytes: ByteArray) {
    private val decoder = BinaryDecoder(bytes)
    private var state = 0L
    private var count = 0L

    fun read(): Long {
        if (count == 0L) {
            val (value, negative) = decoder.readLib0VarIntWithSign()
            state = kotlin.math.abs(value)
            count = if (negative) decoder.readVarUInt() + 2 else 1
        }
        count--
        return state
    }
}

internal class Lib0IntDiffOptRleEncoder {
    private val encoder = BinaryEncoder()
    private var state = 0L
    private var count = 0L
    private var diff = 0L

    fun write(value: Long) {
        val nextDiff = value - state
        if (diff == nextDiff) {
            state = value
            count++
        } else {
            flush()
            count = 1
            diff = nextDiff
            state = value
        }
    }

    private fun flush() {
        if (count == 0L) return
        encoder.writeLib0VarInt(diff * 2 + if (count == 1L) 0 else 1)
        if (count > 1) encoder.writeVarUInt(count - 2)
    }

    fun toByteArray(): ByteArray {
        flush()
        count = 0
        return encoder.toByteArray()
    }
}

internal class Lib0IntDiffOptRleDecoder(bytes: ByteArray) {
    private val decoder = BinaryDecoder(bytes)
    private var state = 0L
    private var count = 0L
    private var diff = 0L

    fun read(): Long {
        if (count == 0L) {
            val encoded = decoder.readLib0VarInt()
            val hasCount = (encoded and 1L) != 0L
            diff = Math.floorDiv(encoded, 2L)
            count = if (hasCount) decoder.readVarUInt() + 2 else 1
        }
        state += diff
        count--
        return state
    }
}

internal class Lib0StringEncoder {
    private val values = StringBuilder()
    private val lengths = Lib0UintOptRleEncoder()

    fun write(value: String) {
        values.append(value)
        lengths.write(value.length.toLong())
    }

    fun toByteArray(): ByteArray = BinaryEncoder().also { encoder ->
        encoder.writeString(values.toString())
        encoder.writeRawBytes(lengths.toByteArray())
    }.toByteArray()
}

internal class Lib0StringDecoder(bytes: ByteArray) {
    private val lengths: Lib0UintOptRleDecoder
    private val value: String
    private var offset = 0

    init {
        val decoder = BinaryDecoder(bytes)
        value = decoder.readString()
        lengths = Lib0UintOptRleDecoder(decoder.readRemainingBytes())
    }

    fun read(): String {
        val length = lengths.read()
        check(length <= (value.length - offset).toLong()) { "invalid string stream length" }
        val end = offset + length.toNonNegativeInt("string stream length")
        return value.substring(offset, end).also { offset = end }
    }
}
