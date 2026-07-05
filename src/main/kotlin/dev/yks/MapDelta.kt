package dev.yks

enum class YMapDeltaAction {
    Set,
    Delete,
}

data class YMapDeltaOp(
    val action: YMapDeltaAction,
    val value: Any? = null,
    val previousValue: Any? = null,
)

class YMapDelta(ops: Map<String, YMapDeltaOp> = emptyMap()) {
    private val mutableOps = linkedMapOf<String, YMapDeltaOp>()

    init {
        ops.forEach { (key, op) -> mutableOps[key] = op }
    }

    val ops: Map<String, YMapDeltaOp> get() = mutableOps.toMap()

    fun setAttr(key: String, value: Any?, previousValue: Any? = null): YMapDelta {
        mutableOps[key] = YMapDeltaOp(YMapDeltaAction.Set, value, previousValue)
        return this
    }

    fun deleteAttr(key: String, previousValue: Any? = null): YMapDelta {
        mutableOps[key] = YMapDeltaOp(YMapDeltaAction.Delete, previousValue = previousValue)
        return this
    }

    fun isEmpty(): Boolean = mutableOps.isEmpty()

    override fun equals(other: Any?): Boolean = other is YMapDelta && ops == other.ops

    override fun hashCode(): Int = ops.hashCode()

    override fun toString(): String = "YMapDelta($ops)"
}

fun yMapDelta(): YMapDelta = YMapDelta()
