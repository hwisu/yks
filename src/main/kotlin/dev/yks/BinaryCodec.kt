package dev.yks

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

// These are representation limits, not policy limits. A standard Yjs update must not be
// rejected merely because it crosses a YKS-specific safety threshold. Applications that accept
// untrusted updates can opt in to smaller, document-level limits through YUpdateLimits.
internal const val MAX_DECODED_COLLECTION_SIZE: Int = Int.MAX_VALUE
internal const val MAX_DECODED_BINARY_SIZE: Int = Int.MAX_VALUE
internal const val MAX_DECODED_NESTING_DEPTH: Int = Int.MAX_VALUE
internal const val MAX_DECODED_VALUE_NODES: Int = Int.MAX_VALUE
internal const val MAX_DECODED_TOTAL_PAYLOAD_SIZE: Long = Long.MAX_VALUE

internal class DecodeBudget {
    private var depth = 0
    private var nodes = 0
    private var payloadBytes = 0L

    fun consumeNode() {
        check(nodes < MAX_DECODED_VALUE_NODES) {
            "decoded value node count exceeds limit $MAX_DECODED_VALUE_NODES"
        }
        nodes++
    }

    fun consumePayloadBytes(length: Int) {
        check(length >= 0) { "decoded payload length must be non-negative: $length" }
        payloadBytes = checkedClockAdd(payloadBytes, length.toLong(), "decoded payload size")
        check(payloadBytes <= MAX_DECODED_TOTAL_PAYLOAD_SIZE) {
            "decoded payload size exceeds limit $MAX_DECODED_TOTAL_PAYLOAD_SIZE: $payloadBytes"
        }
    }

    fun <T> nested(block: () -> T): T {
        check(depth < MAX_DECODED_NESTING_DEPTH) {
            "decoded value nesting exceeds limit $MAX_DECODED_NESTING_DEPTH"
        }
        depth++
        return try {
            block()
        } finally {
            depth--
        }
    }
}

internal fun Long.toDecodedCount(
    label: String = "decoded count",
    maximum: Int = MAX_DECODED_COLLECTION_SIZE,
): Int {
    check(this >= 0 && this <= maximum.toLong()) { "$label exceeds limit $maximum: $this" }
    return toInt()
}

/** Build a decoded collection without trusting an untrusted wire count as an allocation size. */
internal inline fun <T> buildDecodedList(count: Int, read: (Int) -> T): MutableList<T> {
    val values = ArrayList<T>()
    repeat(count) { index -> values.add(read(index)) }
    return values
}

internal fun checkedClockAdd(left: Long, right: Long, label: String = "clock"): Long {
    check(left >= 0 && right >= 0) { "$label operands must be non-negative: $left + $right" }
    return try {
        Math.addExact(left, right)
    } catch (_: ArithmeticException) {
        error("$label overflow: $left + $right")
    }
}

internal fun Long.toNonNegativeInt(label: String): Int {
    check(this in 0..Int.MAX_VALUE.toLong()) { "$label exceeds Int range: $this" }
    return toInt()
}

internal fun Long.toIntExact(label: String): Int {
    check(this in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "$label exceeds Int range: $this" }
    return toInt()
}

internal fun boundedIntRangeEnd(start: Int, length: Long, size: Int, label: String): Int {
    check(start in 0..size) { "$label start is out of bounds: $start" }
    check(length >= 0) { "$label length must be non-negative: $length" }
    val boundedLength = minOf(length, (size - start).toLong()).toInt()
    return start + boundedLength
}

private const val ENCODER_INITIAL_CAPACITY: Int = 64
private const val ENCODER_MAX_CAPACITY: Int = Int.MAX_VALUE - 8

/**
 * Append-only byte sink for the Yjs wire format.
 *
 * Every varint digit lands here one byte at a time, and a V2 update fans out over roughly a dozen
 * concurrent sub-streams, so the sink is deliberately a plain growable array: `ByteArrayOutputStream`
 * would take an uncontended monitor per byte and copy again on every `toByteArray`.
 */
public class BinaryEncoder {
    private var buffer = ByteArray(ENCODER_INITIAL_CAPACITY)
    private var size = 0

    private fun ensureCapacity(additional: Int) {
        val required = size + additional
        if (required in 0..buffer.size) return
        check(required > 0) { "encoded output exceeds the maximum array size" }
        var capacity = buffer.size
        while (capacity < required) {
            capacity = if (capacity > ENCODER_MAX_CAPACITY / 2) ENCODER_MAX_CAPACITY else capacity shl 1
        }
        buffer = buffer.copyOf(capacity)
    }

    public fun writeByte(value: Int) {
        if (size == buffer.size) ensureCapacity(1)
        buffer[size++] = (value and 0xff).toByte()
    }

    public fun writeBoolean(value: Boolean) {
        writeByte(if (value) 1 else 0)
    }

    public fun writeVarUInt(value: Long) {
        require(value >= 0) { "varuint cannot encode negative values: $value" }
        var current = value
        while (current >= 0x80) {
            writeByte(((current and 0x7f) or 0x80).toInt())
            current = current ushr 7
        }
        writeByte(current.toInt())
    }

    public fun writeVarInt(value: Long) {
        var current = (value shl 1) xor (value shr 63)
        // Zig-zag encoding spans the full unsigned 64-bit domain. Values whose encoded
        // high bit is set are negative only as a Kotlin Long representation, not as a
        // varuint payload, so emit their raw bits with unsigned shifts.
        while ((current and -0x80L) != 0L) {
            writeByte(((current and 0x7f) or 0x80).toInt())
            current = current ushr 7
        }
        writeByte(current.toInt())
    }

    public fun writeLib0VarInt(value: Long, forceNegative: Boolean = false) {
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

    public fun writeString(value: String): Unit = writeCharSequence(value)

    /**
     * Writes UTF-8 with JavaScript's replacement behavior for lone surrogates.
     *
     * `TextEncoder` emits U+FFFD per unpaired surrogate half, which `String.toByteArray` does not
     * reproduce (it substitutes `?`). Encoding by hand keeps that contract while skipping the
     * per-call [java.nio.charset.CharsetEncoder] and its intermediate buffers.
     */
    internal fun writeCharSequence(value: CharSequence) {
        val length = value.length
        var index = 0
        while (index < length && value[index].code < 0x80) index++
        val ascii = index == length
        val byteLength = if (ascii) length else utf8Length(value)
        writeVarUInt(byteLength.toLong())
        ensureCapacity(byteLength)
        var target = size
        if (ascii) {
            for (position in 0 until length) buffer[target++] = value[position].code.toByte()
            size = target
            return
        }
        index = 0
        while (index < length) {
            val code = value[index].code
            when {
                code < 0x80 -> buffer[target++] = code.toByte()
                code < 0x800 -> {
                    buffer[target++] = (0xc0 or (code ushr 6)).toByte()
                    buffer[target++] = (0x80 or (code and 0x3f)).toByte()
                }
                else -> {
                    val paired = codePointAt(value, index, length)
                    if (paired > 0xffff) {
                        buffer[target++] = (0xf0 or (paired ushr 18)).toByte()
                        buffer[target++] = (0x80 or ((paired ushr 12) and 0x3f)).toByte()
                        buffer[target++] = (0x80 or ((paired ushr 6) and 0x3f)).toByte()
                        buffer[target++] = (0x80 or (paired and 0x3f)).toByte()
                        index++
                    } else {
                        // A lone surrogate half is malformed input; lib0 replaces it with U+FFFD.
                        val encoded = if (paired < 0) 0xfffd else paired
                        buffer[target++] = (0xe0 or (encoded ushr 12)).toByte()
                        buffer[target++] = (0x80 or ((encoded ushr 6) and 0x3f)).toByte()
                        buffer[target++] = (0x80 or (encoded and 0x3f)).toByte()
                    }
                }
            }
            index++
        }
        size = target
    }

    public fun writeBytes(value: ByteArray) {
        writeVarUInt(value.size.toLong())
        writeRawBytes(value)
    }

    public fun writeRawBytes(value: ByteArray) {
        ensureCapacity(value.size)
        value.copyInto(buffer, size)
        size += value.size
    }

    public fun writeFloat32(value: Float) {
        val bits = java.lang.Float.floatToRawIntBits(value)
        repeat(Int.SIZE_BYTES) { index ->
            writeByte((bits ushr ((Int.SIZE_BYTES - index - 1) * 8)) and 0xff)
        }
    }

    public fun writeFloat64(value: Double) {
        val bits = java.lang.Double.doubleToRawLongBits(value)
        repeat(Long.SIZE_BYTES) { index ->
            writeByte(((bits ushr ((Long.SIZE_BYTES - index - 1) * 8)) and 0xff).toInt())
        }
    }

    public fun writeInt64(value: Long) {
        repeat(Long.SIZE_BYTES) { index ->
            writeByte(((value ushr ((Long.SIZE_BYTES - index - 1) * 8)) and 0xff).toInt())
        }
    }

    public fun toByteArray(): ByteArray = buffer.copyOf(size)

    private companion object {
        /** Returns the full code point at [index], or -1 for an unpaired surrogate half. */
        fun codePointAt(value: CharSequence, index: Int, length: Int): Int {
            val high = value[index]
            if (!high.isHighSurrogate()) return if (high.isLowSurrogate()) -1 else high.code
            val next = index + 1
            if (next >= length) return -1
            val low = value[next]
            if (!low.isLowSurrogate()) return -1
            return Character.toCodePoint(high, low)
        }

        fun utf8Length(value: CharSequence): Int {
            var total = 0
            var index = 0
            val length = value.length
            while (index < length) {
                val code = value[index].code
                total += when {
                    code < 0x80 -> 1
                    code < 0x800 -> 2
                    else -> {
                        val paired = codePointAt(value, index, length)
                        if (paired > 0xffff) {
                            index++
                            4
                        } else {
                            3
                        }
                    }
                }
                index++
            }
            return total
        }
    }
}

public class BinaryDecoder private constructor(
    private val bytes: ByteArray,
    private val start: Int,
    private val limit: Int,
) {
    public constructor(bytes: ByteArray) : this(bytes, 0, bytes.size)

    internal val decodeBudget: DecodeBudget = DecodeBudget()

    private var offset = start

    public fun hasRemaining(): Boolean = offset < limit

    public fun readByte(): Int {
        check(offset < limit) { "unexpected end of input" }
        return bytes[offset++].toInt() and 0xff
    }

    public fun readBoolean(): Boolean = when (val value = readByte()) {
        0 -> false
        1 -> true
        else -> error("invalid boolean byte: $value")
    }

    public fun readVarUInt(): Long {
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

    public fun readVarInt(): Long {
        val value = readVarUInt()
        return (value ushr 1) xor -(value and 1)
    }

    public fun readLib0VarInt(): Long = readLib0VarIntWithSign().first

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

    /** lib0 coerces an out-of-view first varint byte from `undefined` to zero. */
    internal fun readLib0VarIntWithSignOrZero(): Pair<Long, Boolean> {
        var byte = readByteOrZero()
        var result = (byte and 0x3f).toLong()
        var multiplier = 64L
        val sign = if ((byte and 0x40) != 0) -1 else 1
        while ((byte and 0x80) != 0 && offset < limit) {
            byte = readByte()
            result += (byte and 0x7f) * multiplier
            check(multiplier <= Long.MAX_VALUE / 128) { "varint is too large" }
            multiplier *= 128
        }
        check((byte and 0x80) == 0) { "unexpected end of input" }
        return sign * result to (sign < 0)
    }

    internal fun readByteOrZero(): Int =
        if (offset < limit) bytes[offset++].toInt() and 0xff else 0.also { offset++ }

    /**
     * lib0's readUint8Array constructs a new typed-array view without checking the current view's
     * end. It can therefore read a declared string from the shared backing buffer even when the
     * substream itself ends immediately after the length prefix.
     */
    internal fun readLib0StringFromBacking(): String {
        val length = readVarUInt().toDecodedCount(
            "string byte length",
            minOf(MAX_DECODED_BINARY_SIZE, bytes.size - offset),
        )
        check(length <= bytes.size - offset) { "invalid string length: $length" }
        decodeBudget.consumePayloadBytes(length)
        return decodeUtf8(offset, length).also { offset += length }
    }

    public fun readString(): String {
        val length = readVarUInt().toDecodedCount("string byte length", minOf(MAX_DECODED_BINARY_SIZE, limit - offset))
        check(length <= limit - offset) { "invalid string length: $length" }
        decodeBudget.consumePayloadBytes(length)
        var ascii = true
        for (index in offset until offset + length) {
            if (bytes[index] < 0) {
                ascii = false
                break
            }
        }
        val value = if (ascii) {
            String(bytes, offset, length, Charsets.US_ASCII)
        } else {
            decodeUtf8(offset, length)
        }
        offset += length
        return value
    }

    private fun decodeUtf8(offset: Int, length: Int): String {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes, offset, length)).toString()
        } catch (error: java.nio.charset.CharacterCodingException) {
            throw IllegalStateException("invalid UTF-8 string", error)
        }
    }

    public fun readBytes(): ByteArray {
        val length = readVarUInt().toDecodedCount("byte array length", minOf(MAX_DECODED_BINARY_SIZE, limit - offset))
        check(length <= limit - offset) { "invalid byte array length: $length" }
        decodeBudget.consumePayloadBytes(length)
        val value = bytes.copyOfRange(offset, offset + length)
        offset += length
        return value
    }

    public fun readFloat32(): Float {
        var bits = 0
        repeat(Int.SIZE_BYTES) { bits = (bits shl 8) or readByte() }
        return java.lang.Float.intBitsToFloat(bits)
    }

    public fun readFloat64(): Double {
        var bits = 0L
        repeat(Long.SIZE_BYTES) { bits = (bits shl 8) or readByte().toLong() }
        return java.lang.Double.longBitsToDouble(bits)
    }

    public fun readInt64(): Long {
        var value = 0L
        repeat(Long.SIZE_BYTES) { value = (value shl 8) or readByte().toLong() }
        return value
    }

    public fun readRemainingBytes(): ByteArray {
        decodeBudget.consumePayloadBytes(limit - offset)
        val value = bytes.copyOfRange(offset, limit)
        offset = limit
        return value
    }

    internal fun readDecoderView(): BinaryDecoder {
        val length = readVarUInt().toDecodedCount("byte array length", minOf(MAX_DECODED_BINARY_SIZE, limit - offset))
        check(length <= limit - offset) { "invalid byte array length: $length" }
        decodeBudget.consumePayloadBytes(length)
        val view = BinaryDecoder(bytes, offset, offset + length)
        offset += length
        return view
    }

    internal fun readRemainingDecoderView(): BinaryDecoder =
        BinaryDecoder(bytes, offset, limit).also { offset = limit }
}
