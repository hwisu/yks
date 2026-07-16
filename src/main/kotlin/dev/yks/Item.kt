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

internal val ItemContent.clockLength: Long
    get() = when (this) {
        is ItemContent.Text -> value.length.toLong()
        is ItemContent.Deleted -> length
        else -> 1
    }

internal fun StoreItem.logicalUnits(): List<StoreItem> {
    val text = content as? ItemContent.Text ?: return listOf(this)
    if (text.value.length == 1) return listOf(this)
    return text.value.mapIndexed { offset, char ->
        copy(
            id = Id(id.client, checkedClockAdd(id.clock, offset.toLong(), "text unit clock")),
            origin = if (offset == 0) {
                origin
            } else {
                Id(id.client, checkedClockAdd(id.clock, offset.toLong() - 1, "text unit origin"))
            },
            content = text.copy(value = char.toString()),
        )
    }
}

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
        else -> error("clock range splits unsupported store item at $id")
    }
    return copy(
        id = Id(id.client, startClock),
        origin = if (isGc || startClock == id.clock) origin else Id(id.client, startClock - 1),
        content = slicedContent,
    )
}
