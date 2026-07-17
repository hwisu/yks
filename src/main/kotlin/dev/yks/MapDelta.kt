package dev.yks

public enum class YMapDeltaAction {
    Set,
    Delete,
}

public data class YMapDeltaOp(
    val action: YMapDeltaAction,
    val value: Any? = null,
    val previousValue: Any? = null,
)

public class YMapDelta(ops: Map<String, YMapDeltaOp> = emptyMap()) {
    private val mutableOps = linkedMapOf<String, YMapDeltaOp>()

    init {
        ops.forEach { (key, op) -> mutableOps[key] = op }
    }

    public val ops: Map<String, YMapDeltaOp> get() = mutableOps.toMap()

    public fun setAttr(key: String, value: Any?, previousValue: Any? = null): YMapDelta {
        mutableOps[key] = YMapDeltaOp(YMapDeltaAction.Set, value, previousValue)
        return this
    }

    public fun setAttrs(values: Map<String, Any?>, previousValues: Map<String, Any?> = emptyMap()): YMapDelta {
        values.forEach { (key, value) -> setAttr(key, value, previousValues[key]) }
        return this
    }

    public fun deleteAttr(key: String, previousValue: Any? = null): YMapDelta {
        mutableOps[key] = YMapDeltaOp(YMapDeltaAction.Delete, previousValue = previousValue)
        return this
    }

    public fun isEmpty(): Boolean = mutableOps.isEmpty()

    override fun equals(other: Any?): Boolean = other is YMapDelta && ops == other.ops

    override fun hashCode(): Int = ops.hashCode()

    override fun toString(): String = "YMapDelta($ops)"
}

public fun yMapDelta(): YMapDelta = YMapDelta()
