package dev.yks

/** Traverses recursive values on the heap instead of consuming the JVM call stack. */
internal fun <T> foldYValue(
    value: YValue,
    list: (List<T>) -> T,
    map: (Map<String, T>) -> T,
    scalar: (YValue) -> T,
): T {
    if (value !is YValue.ListValue && value !is YValue.MapValue) return scalar(value)
    return DeepRecursiveFunction<YValue, T> { nested ->
        when (nested) {
            is YValue.ListValue -> list(nested.value.map { callRecursive(it) })
            is YValue.MapValue -> map(nested.value.mapValues { (_, child) -> callRecursive(child) })
            else -> scalar(nested)
        }
    }(value)
}

internal fun YValue.scalarValues(): Sequence<YValue> = sequence {
    val pending = ArrayDeque<Iterator<YValue>>()
    pending.addLast(listOf(this@scalarValues).iterator())
    while (pending.isNotEmpty()) {
        val iterator = pending.last()
        if (!iterator.hasNext()) {
            pending.removeLast()
            continue
        }
        when (val value = iterator.next()) {
            is YValue.ListValue -> pending.addLast(value.value.iterator())
            is YValue.MapValue -> pending.addLast(value.value.values.iterator())
            else -> yield(value)
        }
    }
}

internal fun copyNestedContent(value: Any?): Any? {
    if (value is ByteArray) return value.copyOf()
    if (value !is List<*> && value !is Map<*, *>) return value
    return DeepRecursiveFunction<Any?, Any?> { nested ->
        when (nested) {
            is ByteArray -> nested.copyOf()
            is List<*> -> nested.map { callRecursive(it) }
            is Map<*, *> -> nested.entries.associate { (key, child) -> key to callRecursive(child) }
            else -> nested
        }
    }(value)
}
