package dev.yks

internal sealed interface UnresolvedYjsParent {
    val id: Id

    data class Nested(override val id: Id) : UnresolvedYjsParent

    data class Inherit(override val id: Id) : UnresolvedYjsParent
}

internal data class StoreItem(
    val id: Id,
    val origin: Id?,
    val rightOrigin: Id?,
    val parent: String,
    val parentSub: String?,
    val content: ItemContent,
    var deleted: Boolean = false,
    val requiresClockContinuity: Boolean = false,
    val isGc: Boolean = false,
    val unresolvedParent: UnresolvedYjsParent? = null,
    /** Item.countable is structural metadata and survives replacing content during GC. */
    val countable: Boolean = content.isCountable(),
) {
    val length: Long get() = content.clockLength

    val lastId: Id get() = Id(id.client, checkedClockAdd(id.clock, length - 1, "item last clock"))
}

internal sealed class ItemContent {
    abstract val kind: RootKind

    data class Value(val value: YValue) : ItemContent() {
        override val kind: RootKind = RootKind.Array
    }

    /** Packed Yjs ContentAny values for an array sequence. */
    data class ArrayValues(val values: List<YValue>) : ItemContent() {
        init {
            require(values.size > 1) { "packed array values must contain at least two values" }
        }

        override val kind: RootKind = RootKind.Array
    }

    data class Text(
        val value: String,
        val attributes: Map<String, YValue> = emptyMap(),
        val baseAttributes: Map<String, YValue> = attributes,
        override val kind: RootKind = RootKind.Text,
    ) : ItemContent() {
        init {
            require(value.isNotEmpty()) { "text items must not be empty" }
            require(kind == RootKind.Text || kind == RootKind.XmlText) { "text content must belong to a text sequence" }
        }
    }

    data class TextEmbed(
        val value: YValue,
        val attributes: Map<String, YValue> = emptyMap(),
        val baseAttributes: Map<String, YValue> = attributes,
        override val kind: RootKind = RootKind.Text,
    ) : ItemContent() {
        init {
            require(kind == RootKind.Text || kind == RootKind.XmlText) { "text embed content must belong to a text sequence" }
        }
    }

    data class TextFormat(
        val target: Id,
        val length: Long,
        val attributes: Map<String, YValue>,
        val afterAttributes: Map<String, YValue>,
        val beforeAttributes: List<Map<String, YValue>> = emptyList(),
        override val kind: RootKind = RootKind.Text,
    ) : ItemContent() {
        init {
            require(length > 0) { "text format length must be positive" }
            require(kind == RootKind.Text || kind == RootKind.XmlText) {
                "text format content must belong to a text sequence"
            }
        }
    }

    /** A native Yjs ContentFormat marker. Its value applies until the next marker with the same key. */
    data class NativeTextFormat(
        val key: String,
        val value: YValue,
        override val kind: RootKind = RootKind.Text,
    ) : ItemContent() {
        init {
            require(kind == RootKind.Text || kind == RootKind.XmlText) {
                "native text format content must belong to a text sequence"
            }
        }
    }

    data class MapEntry(val value: YValue) : ItemContent() {
        override val kind: RootKind = RootKind.Map
    }

    /** Packed Yjs ContentAny history for a map key. The last value is the current map value. */
    data class MapEntries(val values: List<YValue>) : ItemContent() {
        init {
            require(values.size > 1) { "packed map entries must contain at least two values" }
        }

        override val kind: RootKind = RootKind.Map
    }

    data class XmlNode(
        val value: YXmlNodeValue,
        override val kind: RootKind = RootKind.XmlFragment,
    ) : ItemContent() {
        init {
            require(kind == RootKind.XmlFragment || kind == RootKind.XmlElement || kind == RootKind.XmlHook) {
                "XML node content must belong to an XML sequence"
            }
        }
    }

    data class XmlType(
        val ref: YValue.TypeRef,
        val nodeName: String,
        override val kind: RootKind = RootKind.XmlFragment,
        val attributes: Map<String, YValue> = emptyMap(),
        val baseAttributes: Map<String, YValue> = attributes,
    ) : ItemContent()

    data class Deleted(
        override val kind: RootKind,
        val length: Long = 1,
    ) : ItemContent() {
        init {
            require(length > 0) { "deleted content length must be positive" }
        }
    }
}

internal fun ItemContent.isCountable(): Boolean = when (this) {
    is ItemContent.TextFormat,
    is ItemContent.NativeTextFormat,
    is ItemContent.Deleted -> false
    else -> true
}

internal fun ItemContent.storedTextAttributes(): Map<String, YValue> = when (this) {
    is ItemContent.Text -> attributes
    is ItemContent.TextEmbed -> attributes
    is ItemContent.XmlType -> attributes
    else -> emptyMap()
}

internal fun ItemContent.storedBaseTextAttributes(): Map<String, YValue> = when (this) {
    is ItemContent.Text -> baseAttributes
    is ItemContent.TextEmbed -> baseAttributes
    is ItemContent.XmlType -> baseAttributes
    else -> emptyMap()
}

internal fun ItemContent.withRenderedTextAttributes(attributes: Map<String, YValue>): ItemContent? = when (this) {
    is ItemContent.Text -> copy(attributes = attributes)
    is ItemContent.TextEmbed -> copy(attributes = attributes)
    is ItemContent.XmlType -> copy(attributes = attributes)
    else -> null
}

internal val ItemContent.clockLength: Long
    get() = when (this) {
        is ItemContent.Text -> value.length.toLong()
        is ItemContent.ArrayValues -> values.size.toLong()
        is ItemContent.MapEntries -> values.size.toLong()
        is ItemContent.Deleted -> length
        else -> 1
    }

/**
 * Traverses packed structs as clock ranges instead of allocating one [StoreItem] per UTF-16 unit.
 *
 * Callers may provide structural boundaries (snapshot clocks, delete ranges, etc.). A packed text
 * item is then sliced only at those boundaries, so work scales with CRDT ranges rather than text
 * length.
 */
internal class ClockRangeCursor(private val items: Iterable<StoreItem>) {
    fun forEachRange(action: (item: StoreItem, startClock: Long, endClock: Long) -> Boolean) {
        items.forEach { item ->
            if (!action(item, item.id.clock, item.clockEnd())) return
        }
    }

    fun forEachRange(
        boundariesForClient: (Long) -> Iterable<Long>,
        action: (item: StoreItem, startClock: Long, endClock: Long) -> Boolean,
    ) {
        val boundariesByClient = mutableMapOf<Long, ClientClockBoundaries>()
        val emptyBoundaryCursor = ClientClockBoundaries(LongArray(0))
        items.forEach { item ->
            val itemStart = item.id.clock
            val itemEnd = item.clockEnd()
            val boundaryCursor = if (item.isGc) {
                emptyBoundaryCursor
            } else {
                boundariesByClient.getOrPut(item.id.client) {
                    ClientClockBoundaries(
                        boundariesForClient(item.id.client)
                            .asSequence()
                            .distinct()
                            .sorted()
                            .toList()
                            .toLongArray(),
                    )
                }
            }
            var start = itemStart
            var boundaryIndex = boundaryCursor.firstIndexAfter(itemStart)
            while (
                boundaryIndex < boundaryCursor.clocks.size &&
                boundaryCursor.clocks[boundaryIndex] < itemEnd
            ) {
                val end = boundaryCursor.clocks[boundaryIndex++]
                if (!action(item, start, end)) return
                start = end
            }
            boundaryCursor.commit(itemStart, boundaryIndex)
            if (!action(item, start, itemEnd)) return
        }
    }
}

private class ClientClockBoundaries(
    val clocks: LongArray,
) {
    private var nextIndex: Int = 0
    private var previousItemStart: Long = -1

    fun firstIndexAfter(clock: Long): Int {
        if (clock < previousItemStart) return clocks.firstIndexAfter(clock)
        var index = nextIndex
        while (index < clocks.size && clocks[index] <= clock) index++
        return index
    }

    fun commit(itemStart: Long, boundaryIndex: Int) {
        previousItemStart = itemStart
        nextIndex = boundaryIndex
    }
}

private fun LongArray.firstIndexAfter(clock: Long): Int {
    var low = 0
    var high = size
    while (low < high) {
        val middle = (low + high) ushr 1
        if (this[middle] <= clock) low = middle + 1 else high = middle
    }
    return low
}

internal fun StoreItem.clockRangeView(startClock: Long, endClock: Long): StoreItem =
    if (startClock == id.clock && endClock == clockEnd()) this else sliceClocks(startClock, endClock)

internal fun StoreItem.clockEnd(): Long = checkedClockAdd(id.clock, length, "item clock end")

internal fun StoreItem.sliceClocks(startClock: Long, endClock: Long): StoreItem {
    val itemEnd = checkedClockAdd(id.clock, length, "item end")
    require(startClock >= id.clock && startClock < itemEnd && endClock > startClock && endClock <= itemEnd) {
        "clock slice must be contained in the item"
    }
    if (startClock == id.clock && endClock == itemEnd) return copy()
    val offset = startClock - id.clock
    val keepLength = endClock - startClock
    val slicedContent = when (val current = content) {
        is ItemContent.Deleted -> current.copy(length = keepLength)
        is ItemContent.Text -> {
            var value = ContentString(current.value)
            if (offset > 0) value = value.splice(offset)
            if (keepLength < value.getLength()) value.splice(keepLength)
            current.copy(value = value.str)
        }
        is ItemContent.MapEntries -> {
            val from = offset.toNonNegativeInt("packed map slice offset")
            val until = checkedClockAdd(offset, keepLength, "packed map slice end")
                .toNonNegativeInt("packed map slice end")
            val values = current.values.subList(from, until)
            if (values.size == 1) ItemContent.MapEntry(values.single()) else current.copy(values = values.toList())
        }
        is ItemContent.ArrayValues -> {
            val from = offset.toNonNegativeInt("packed array slice offset")
            val until = checkedClockAdd(offset, keepLength, "packed array slice end")
                .toNonNegativeInt("packed array slice end")
            val values = current.values.subList(from, until)
            if (values.size == 1) ItemContent.Value(values.single()) else current.copy(values = values.toList())
        }
        else -> error("clock range splits unsupported store item at $id")
    }
    return copy(
        id = Id(id.client, startClock),
        origin = if (isGc || startClock == id.clock) origin else Id(id.client, startClock - 1),
        content = slicedContent,
    )
}
