package dev.yks

private const val LIB0_UNDEFINED = 127
private const val LIB0_NULL = 126
private const val LIB0_INTEGER = 125
private const val LIB0_FLOAT32 = 124
private const val LIB0_FLOAT64 = 123
private const val LIB0_BIGINT = 122
private const val LIB0_FALSE = 121
private const val LIB0_TRUE = 120
private const val LIB0_STRING = 119
private const val LIB0_OBJECT = 118
private const val LIB0_ARRAY = 117
private const val LIB0_BINARY = 116

internal data object Lib0Undefined

internal fun writeLib0Any(encoder: BinaryEncoder, value: Any?) {
    when (value) {
        Lib0Undefined,
        YValue.Undefined -> encoder.writeByte(LIB0_UNDEFINED)
        null -> encoder.writeByte(LIB0_NULL)
        is Boolean -> encoder.writeByte(if (value) LIB0_TRUE else LIB0_FALSE)
        is Byte,
        is Short,
        is Int,
        is Long,
        is Float,
        is Double -> writeLib0Number(encoder, value as Number)
        is java.math.BigInteger -> {
            encoder.writeByte(LIB0_BIGINT)
            encoder.writeInt64(value.longValueExact())
        }
        is YValue.BigIntNumber -> {
            encoder.writeByte(LIB0_BIGINT)
            encoder.writeInt64(value.value.longValueExact())
        }
        is String -> {
            encoder.writeByte(LIB0_STRING)
            encoder.writeString(value)
        }
        is ByteArray -> {
            encoder.writeByte(LIB0_BINARY)
            encoder.writeBytes(value)
        }
        is List<*> -> {
            encoder.writeByte(LIB0_ARRAY)
            encoder.writeVarUInt(value.size.toLong())
            value.forEach { nested -> writeLib0Any(encoder, nested) }
        }
        is Array<*> -> writeLib0Any(encoder, value.toList())
        is Map<*, *> -> {
            encoder.writeByte(LIB0_OBJECT)
            encoder.writeVarUInt(value.size.toLong())
            value.entries.sortedWith(jsObjectEntryComparator).forEach { (key, nested) ->
                require(key is String) { "lib0 object keys must be strings" }
                encoder.writeString(key)
                writeLib0Any(encoder, nested)
            }
        }
        else -> error("unsupported lib0 value: ${value::class.qualifiedName}")
    }
}

private fun writeLib0Number(encoder: BinaryEncoder, value: Number) {
    val number = value.toDouble()
    if (value is Long) {
        require(value in -YJS_MAX_SAFE_INTEGER..YJS_MAX_SAFE_INTEGER) {
            "Long is not exactly representable as a JavaScript number: $value"
        }
    }
    val negativeZero = number == 0.0 && java.lang.Double.doubleToRawLongBits(number) < 0
    if (number.isFinite() && number % 1.0 == 0.0 && kotlin.math.abs(number) <= 0x7fff_ffff.toDouble()) {
        encoder.writeByte(LIB0_INTEGER)
        if (negativeZero) {
            encoder.writeByte(0x40)
        } else {
            encoder.writeLib0VarInt(number.toLong())
        }
        return
    }
    val float = number.toFloat()
    if (!number.isNaN() && float.toDouble() == number) {
        encoder.writeByte(LIB0_FLOAT32)
        encoder.writeFloat32(float)
    } else {
        encoder.writeByte(LIB0_FLOAT64)
        encoder.writeFloat64(number)
    }
}

private val jsObjectEntryComparator = Comparator<Map.Entry<*, *>> { left, right ->
    val leftIndex = (left.key as? String)?.jsArrayIndex()
    val rightIndex = (right.key as? String)?.jsArrayIndex()
    when {
        leftIndex != null && rightIndex != null -> leftIndex.compareTo(rightIndex)
        leftIndex != null -> -1
        rightIndex != null -> 1
        else -> 0
    }
}

private fun String.jsArrayIndex(): Long? {
    if (isEmpty() || (length > 1 && first() == '0') || any { char -> !char.isDigit() }) return null
    val value = toLongOrNull() ?: return null
    return value.takeIf { it in 0..0xffff_fffeL && it.toString() == this }
}

internal fun readLib0Any(decoder: BinaryDecoder): Any? {
    decoder.decodeBudget.consumeNode()
    return when (val tag = decoder.readByte()) {
        LIB0_UNDEFINED -> Lib0Undefined
        LIB0_NULL -> null
        LIB0_INTEGER -> decoder.readLib0VarIntWithSign().let { (value, negative) ->
            if (value == 0L && negative) -0.0 else value
        }
        LIB0_FLOAT32 -> decoder.readFloat32().toDouble()
        LIB0_FLOAT64 -> decoder.readFloat64()
        LIB0_BIGINT -> java.math.BigInteger.valueOf(decoder.readInt64())
        LIB0_FALSE -> false
        LIB0_TRUE -> true
        LIB0_STRING -> decoder.readString()
        LIB0_OBJECT -> decoder.decodeBudget.nested {
            buildMap {
                repeat(decoder.readVarUInt().toDecodedCount()) {
                    put(decoder.readString(), readLib0Any(decoder))
                }
            }
        }
        LIB0_ARRAY -> decoder.decodeBudget.nested {
            buildDecodedList(decoder.readVarUInt().toDecodedCount()) { readLib0Any(decoder) }
        }
        LIB0_BINARY -> decoder.readBytes()
        else -> error("unknown lib0 any tag: $tag")
    }
}
