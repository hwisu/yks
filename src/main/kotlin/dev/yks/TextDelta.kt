package dev.yks

data class YTextDeltaOp(
    val insert: Any? = null,
    val retain: Int? = null,
    val delete: Int? = null,
    val attributes: Map<String, Any?> = emptyMap(),
) {
    init {
        val opCount = listOf(insert != null, retain != null, delete != null).count { it }
        require(opCount == 1) { "exactly one of insert, retain, or delete must be set" }
        require(insert !is String || insert.isNotEmpty()) { "insert must not be empty" }
        require(retain == null || retain > 0) { "retain must be positive" }
        require(delete == null || delete > 0) { "delete must be positive" }
    }
}

class YTextDelta(ops: List<YTextDeltaOp> = emptyList()) {
    private val mutableOps = ops.toMutableList()

    val ops: List<YTextDeltaOp> get() = mutableOps.toList()

    fun insert(text: String, attributes: Map<String, Any?> = emptyMap()): YTextDelta {
        if (text.isNotEmpty()) {
            append(YTextDeltaOp(insert = text, attributes = normalizePublicAttributes(attributes)))
        }
        return this
    }

    /** Adds a string op without coalescing it with the previous op. */
    internal fun insertSegment(text: String, attributes: Map<String, Any?> = emptyMap()): YTextDelta {
        if (text.isNotEmpty()) {
            mutableOps.add(YTextDeltaOp(insert = text, attributes = normalizePublicAttributes(attributes)))
        }
        return this
    }

    fun insert(values: List<Any?>, attributes: Map<String, Any?> = emptyMap()): YTextDelta {
        if (values.isEmpty()) return this
        val normalized = normalizePublicAttributes(attributes)
        values.forEach { value ->
            when (value) {
                is String -> insert(value, normalized)
                is Char -> insert(value.toString(), normalized)
                null -> error("text embeds must not be null")
                else -> append(YTextDeltaOp(insert = value, attributes = normalized))
            }
        }
        return this
    }

    fun insertEmbed(embed: Any?, attributes: Map<String, Any?> = emptyMap()): YTextDelta {
        require(embed != null) { "embed must not be null" }
        append(YTextDeltaOp(insert = embed, attributes = normalizePublicAttributes(attributes)))
        return this
    }

    fun retain(length: Int, attributes: Map<String, Any?> = emptyMap()): YTextDelta {
        if (length > 0) {
            append(YTextDeltaOp(retain = length, attributes = normalizeRetainAttributes(attributes)))
        }
        return this
    }

    fun delete(length: Int): YTextDelta {
        if (length > 0) {
            append(YTextDeltaOp(delete = length))
        }
        return this
    }

    private fun append(op: YTextDeltaOp) {
        val last = mutableOps.lastOrNull()
        if (last != null && last.attributes == op.attributes) {
            when {
                last.insert is String && op.insert is String -> {
                    mutableOps[mutableOps.lastIndex] = last.copy(insert = last.insert + op.insert)
                    return
                }
                last.retain != null && op.retain != null -> {
                    mutableOps[mutableOps.lastIndex] = last.copy(retain = last.retain + op.retain)
                    return
                }
                last.delete != null && op.delete != null -> {
                    mutableOps[mutableOps.lastIndex] = last.copy(delete = last.delete + op.delete)
                    return
                }
            }
        }
        mutableOps.add(op)
    }

    override fun equals(other: Any?): Boolean = other is YTextDelta && ops == other.ops

    override fun hashCode(): Int = ops.hashCode()

    override fun toString(): String = "YTextDelta($ops)"
}

fun yTextDelta(): YTextDelta = YTextDelta()

internal fun normalizeTextAttributes(attributes: Map<String, Any?>): Map<String, YValue> =
    attributes
        .filterValues { it != null }
        .mapValues { (_, value) -> YValue.from(value) }
        .toSortedMap()

internal fun textAttributesToPublic(attributes: Map<String, YValue>): Map<String, Any?> =
    attributes.mapValues { (_, value) -> value.toAny() }.toSortedMap()

private fun normalizePublicAttributes(attributes: Map<String, Any?>): Map<String, Any?> =
    attributes.filterValues { it != null }.toSortedMap()

private fun normalizeRetainAttributes(attributes: Map<String, Any?>): Map<String, Any?> =
    attributes.toSortedMap()
