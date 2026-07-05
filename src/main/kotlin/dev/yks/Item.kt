package dev.yks

internal data class StoreItem(
    val id: Id,
    val origin: Id?,
    val rightOrigin: Id?,
    val parent: String,
    val parentSub: String?,
    val content: ItemContent,
    var deleted: Boolean = false,
) {
    val length: Long get() = 1
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
    ) : ItemContent() {
        override val kind: RootKind = RootKind.Text

        init {
            require(value.length == 1) { "text items store exactly one UTF-16 code unit" }
        }
    }

    data class TextEmbed(
        val value: YValue,
        val attributes: Map<String, YValue> = emptyMap(),
        val baseAttributes: Map<String, YValue> = attributes,
    ) : ItemContent() {
        override val kind: RootKind = RootKind.Text
    }

    data class TextFormat(
        val target: Id,
        val length: Long,
        val attributes: Map<String, YValue>,
        val afterAttributes: Map<String, YValue>,
        val beforeAttributes: List<Map<String, YValue>> = emptyList(),
    ) : ItemContent() {
        override val kind: RootKind = RootKind.Text

        init {
            require(length > 0) { "text format length must be positive" }
        }
    }

    data class MapEntry(val value: YValue) : ItemContent() {
        override val kind: RootKind = RootKind.Map
    }

    data class XmlNode(val value: YXmlNodeValue) : ItemContent() {
        override val kind: RootKind = RootKind.XmlFragment
    }

    data class Deleted(override val kind: RootKind) : ItemContent()
}

internal fun ItemContent.isCountable(): Boolean = when (this) {
    is ItemContent.TextFormat,
    is ItemContent.Deleted -> false
    else -> true
}
