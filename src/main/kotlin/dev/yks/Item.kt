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
    val length: Long get() = (content as? ItemContent.Deleted)?.length ?: 1
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
            require(value.length == 1) { "text items store exactly one UTF-16 code unit" }
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
    ) : ItemContent() {
        init {
            require(kind == RootKind.XmlFragment || kind == RootKind.XmlElement || kind == RootKind.XmlHook) {
                "XML type content must belong to an XML sequence"
            }
            require(ref.kind == RootKind.Text || ref.kind == RootKind.XmlElement || ref.kind == RootKind.XmlHook || ref.kind == RootKind.XmlText) {
                "XML sequence type children must be XML node or text refs"
            }
        }
    }

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
