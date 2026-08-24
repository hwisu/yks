package dev.yks

import java.math.BigInteger

public sealed interface YValue {
    public fun toAny(): Any?

    /** Distinct from [Null], matching JavaScript/lib0 `undefined`. */
    public data object Undefined : YValue {
        override fun toAny(): Any = this
    }

    public data object Null : YValue {
        override fun toAny(): Any? = null
    }

    public data class Bool(val value: Boolean) : YValue {
        override fun toAny(): Any = value
    }

    public data class LongNumber(val value: Long) : YValue {
        override fun toAny(): Any = value
    }

    public data class DoubleNumber(val value: Double) : YValue {
        override fun toAny(): Any = value
    }

    /** A signed integer carried by lib0's 64-bit bigint representation. */
    public data class BigIntNumber(val value: BigInteger) : YValue {
        override fun toAny(): Any = value
    }

    public data class StringValue(val value: String) : YValue {
        override fun toAny(): Any = value
    }

    public class BinaryValue(value: ByteArray) : YValue {
        private val value: ByteArray = value.copyOf()

        override fun toAny(): Any = value.copyOf()

        public fun bytes(): ByteArray = value.copyOf()

        override fun equals(other: Any?): Boolean = other is BinaryValue && value.contentEquals(other.value)

        override fun hashCode(): Int = value.contentHashCode()

        override fun toString(): String = "BinaryValue(${value.size} bytes)"
    }

    public data class ListValue(val value: List<YValue>) : YValue {
        override fun toAny(): Any = value.map { it.toAny() }
    }

    public data class MapValue(val value: Map<String, YValue>) : YValue {
        override fun toAny(): Any = value.mapValues { (_, nested) -> nested.toAny() }
    }

    public data class TypeRef(val kind: RootKind, val name: String) : YValue {
        override fun toAny(): Any = mapOf("kind" to kind.name, "name" to name)
    }

    public data class SubdocRef(
        val guid: String,
        val gc: Boolean,
        val shouldLoad: Boolean,
        val autoLoad: Boolean,
        val instanceId: String,
        val collectionId: String?,
        val meta: YValue,
        val isSuggestionDoc: Boolean,
    ) : YValue {
        override fun toAny(): Any = mapOf(
            "guid" to guid,
            "gc" to gc,
            "shouldLoad" to shouldLoad,
            "autoLoad" to autoLoad,
            "instanceId" to instanceId,
            "collectionId" to collectionId,
            "meta" to meta.toAny(),
            "isSuggestionDoc" to isSuggestionDoc,
        )
    }

    public companion object {
        public fun from(value: Any?): YValue = when (value) {
            null -> Null
            Lib0Undefined -> Undefined
            is YValue -> value.copyForStorage()
            is AbstractYType -> TypeRef(value.kind, value.name)
            is YDoc -> SubdocRef(
                guid = value.guid,
                gc = value.gc,
                shouldLoad = value.shouldLoad,
                autoLoad = value.autoLoad,
                instanceId = value.subdocInstanceId,
                collectionId = value.collectionId,
                meta = from(value.meta),
                isSuggestionDoc = value.isSuggestionDoc,
            )
            is Boolean -> Bool(value)
            is Byte -> LongNumber(value.toLong())
            is Short -> LongNumber(value.toLong())
            is Int -> LongNumber(value.toLong())
            is Long -> LongNumber(value)
            is BigInteger -> BigIntNumber(value)
            is Float -> DoubleNumber(value.toDouble())
            is Double -> DoubleNumber(value)
            is String -> StringValue(value)
            is ByteArray -> BinaryValue(value)
            is List<*> -> ListValue(value.map(::from))
            is Array<*> -> ListValue(value.map(::from))
            is Map<*, *> -> MapValue(value.entries.associate { (key, nested) ->
                require(key is String) { "YValue map keys must be strings" }
                key to from(nested)
            })
            else -> error("unsupported YValue type: ${value::class.qualifiedName}")
        }
    }
}

/** Detaches stored document values from mutable collections supplied through the public YValue API. */
internal fun YValue.copyForStorage(): YValue = when (this) {
    is YValue.BinaryValue -> YValue.BinaryValue(bytes())
    is YValue.ListValue -> YValue.ListValue(value.map(YValue::copyForStorage))
    is YValue.MapValue -> YValue.MapValue(
        value.mapValuesTo(linkedMapOf()) { (_, nested) -> nested.copyForStorage() },
    )
    is YValue.SubdocRef -> copy(meta = meta.copyForStorage())
    else -> this
}

public fun writeYValue(encoder: BinaryEncoder, value: YValue) {
    when (value) {
        YValue.Undefined -> encoder.writeByte(11)
        YValue.Null -> encoder.writeByte(0)
        is YValue.Bool -> encoder.writeByte(if (value.value) 2 else 1)
        is YValue.LongNumber -> {
            encoder.writeByte(3)
            encoder.writeVarInt(value.value)
        }
        is YValue.DoubleNumber -> {
            encoder.writeByte(4)
            java.lang.Double.doubleToRawLongBits(value.value).let { bits ->
                repeat(Long.SIZE_BYTES) { index -> encoder.writeByte(((bits ushr (index * 8)) and 0xff).toInt()) }
            }
        }
        is YValue.BigIntNumber -> {
            encoder.writeByte(12)
            encoder.writeString(value.value.toString())
        }
        is YValue.StringValue -> {
            encoder.writeByte(5)
            encoder.writeString(value.value)
        }
        is YValue.BinaryValue -> {
            encoder.writeByte(6)
            encoder.writeBytes(value.bytes())
        }
        is YValue.ListValue -> {
            encoder.writeByte(7)
            encoder.writeVarUInt(value.value.size.toLong())
            value.value.forEach { writeYValue(encoder, it) }
        }
        is YValue.MapValue -> {
            encoder.writeByte(8)
            encoder.writeVarUInt(value.value.size.toLong())
            value.value.forEach { (key, nested) ->
                encoder.writeString(key)
                writeYValue(encoder, nested)
            }
        }
        is YValue.TypeRef -> {
            encoder.writeByte(9)
            encoder.writeByte(value.kind.ordinal)
            encoder.writeString(value.name)
        }
        is YValue.SubdocRef -> {
            encoder.writeByte(10)
            encoder.writeString(value.guid)
            encoder.writeBoolean(value.gc)
            encoder.writeBoolean(value.autoLoad)
            encoder.writeString(value.instanceId)
            encoder.writeBoolean(value.collectionId != null)
            value.collectionId?.let(encoder::writeString)
            writeYValue(encoder, value.meta)
            encoder.writeBoolean(value.isSuggestionDoc)
        }
    }
}

public fun readYValue(decoder: BinaryDecoder): YValue {
    decoder.decodeBudget.consumeNode()
    return when (val tag = decoder.readByte()) {
        0 -> YValue.Null
        1 -> YValue.Bool(false)
        2 -> YValue.Bool(true)
        3 -> YValue.LongNumber(decoder.readVarInt())
        4 -> {
            var bits = 0L
            repeat(Long.SIZE_BYTES) { index ->
                bits = bits or (decoder.readByte().toLong() shl (index * 8))
            }
            YValue.DoubleNumber(java.lang.Double.longBitsToDouble(bits))
        }
        5 -> YValue.StringValue(decoder.readString())
        6 -> YValue.BinaryValue(decoder.readBytes())
        7 -> decoder.decodeBudget.nested {
            val size = decoder.readVarUInt().toDecodedCount()
            YValue.ListValue(List(size) { readYValue(decoder) })
        }
        8 -> decoder.decodeBudget.nested {
            val size = decoder.readVarUInt().toDecodedCount()
            YValue.MapValue(buildMap {
                repeat(size) {
                    put(decoder.readString(), readYValue(decoder))
                }
            })
        }
        9 -> {
            val ordinal = decoder.readByte()
            val kind = RootKind.entries.getOrNull(ordinal) ?: error("unknown type ref kind: $ordinal")
            YValue.TypeRef(kind, decoder.readString())
        }
        10 -> decoder.decodeBudget.nested {
            YValue.SubdocRef(
                guid = decoder.readString(),
                gc = decoder.readBoolean(),
                shouldLoad = false,
                autoLoad = decoder.readBoolean(),
                instanceId = decoder.readString(),
                collectionId = if (decoder.readBoolean()) decoder.readString() else null,
                meta = readYValue(decoder),
                isSuggestionDoc = decoder.readBoolean(),
            )
        }
        11 -> YValue.Undefined
        12 -> YValue.BigIntNumber(decoder.readString().toBigInteger())
        else -> error("unknown YValue tag: $tag")
    }
}
