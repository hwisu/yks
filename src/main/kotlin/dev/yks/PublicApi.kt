package dev.yks

typealias Doc = YDoc
typealias Type = AbstractYType
typealias YType = AbstractYType

fun generateNewClientId(): Long = YDoc.generateNewClientId()

fun <T> transact(doc: YDoc, origin: Any? = null, local: Boolean = true, block: () -> T): T =
    doc.transact(origin = origin, local = local, block = block)

fun <T> transact(doc: YDoc, block: (YTransaction) -> T, origin: Any? = null, local: Boolean = true): T =
    doc.transact(block = block, origin = origin, local = local)

@Suppress("UNUSED_PARAMETER")
fun addChangedTypeToTransaction(transaction: YTransaction, type: AbstractYType, parentSub: String? = null) {
    transaction.addChangedType(type)
}

fun deleteText(type: YText, index: Int, length: Int = 1, origin: Any? = null) {
    type.deleteText(index, length, origin)
}

fun getPathTo(
    parent: AbstractYType,
    child: AbstractYType,
    renderer: AbstractRenderer = baseRenderer,
): List<Any> = parent.getPathTo(child, renderer)

data class YTypeChild(
    val id: Id,
    val origin: Id?,
    val rightOrigin: Id?,
    val parent: String,
    val parentSub: String?,
    val kind: RootKind,
    val deleted: Boolean,
    val length: Long,
)

fun getTypeChildren(type: AbstractYType): List<YTypeChild> =
    type.doc.typeChildren(type).map { item ->
        YTypeChild(
            id = item.id,
            origin = item.origin,
            rightOrigin = item.rightOrigin,
            parent = item.parent,
            parentSub = item.parentSub,
            kind = item.content.kind,
            deleted = item.deleted,
            length = item.length,
        )
    }

fun getTypeChildTypes(type: AbstractYType): List<AbstractYType> = type.doc.directNestedChildTypes(type)

fun logType(type: AbstractYType): String {
    val children = type.doc.typeChildren(type)
    val childSummary = children.joinToString(
        prefix = "Children[",
        postfix = "]",
    ) { item ->
        "${item.id.logId()}:${item.content.kind}" +
            "(parent=${item.parent}, parentSub=${item.parentSub}, deleted=${item.deleted}, length=${item.length})"
    }
    val contentSummary = children
        .filterNot { it.deleted }
        .joinToString(
            prefix = "ChildrenContent[",
            postfix = "]",
        ) { item -> item.content.logContent(type.doc) }
    return "$childSummary\n$contentSummary"
}

fun isParentOf(parent: AbstractYType, child: AbstractYType): Boolean {
    require(parent.doc === child.doc) { "types must belong to the same document" }
    return parent.doc.pathBetween(parent.name, child.name) != null
}

fun computeModifiedFromItems(doc: YDoc, items: IdSet): Map<AbstractYType, Set<String?>> {
    val modified = linkedMapOf<AbstractYType, MutableSet<String?>>()
    iterateStructsByIdSetWithoutSplits(doc.store, items) { struct, _, _ ->
        var item: ItemStruct? = struct
        while (item != null) {
            val parent = doc.typeForParent(item.parent) ?: break
            val changes = modified.getOrPut(parent) { linkedSetOf() }
            if (!changes.add(item.parentSub)) break
            item = doc.typeRefItemId(parent)
                ?.let(doc::getItem)
                ?.toItemStruct(doc)
        }
    }
    return modified.mapValues { (_, changes) -> changes.toSet() }
}

fun typeMapGetSnapshot(parent: YMap, key: String, snapshot: Snapshot): Any? =
    parent.doc.mapValueAtSnapshot(parent, key, snapshot)?.let(parent.doc::valueToAny)

fun typeMapGetAllSnapshot(parent: YMap, snapshot: Snapshot): Map<String, Any?> =
    parent.doc.mapAtSnapshot(parent, snapshot).mapValues { (_, value) -> parent.doc.valueToAny(value) }

fun typeArrayToArraySnapshot(parent: YArray, snapshot: Snapshot): List<Any?> =
    parent.doc.arrayAtSnapshot(parent, snapshot)

fun typeTextToDeltaSnapshot(parent: YText, snapshot: Snapshot): YTextDelta =
    parent.doc.textDeltaAtSnapshot(parent, snapshot)

fun typeTextToStringSnapshot(parent: YText, snapshot: Snapshot): String =
    parent.doc.textStringAtSnapshot(parent, snapshot)

fun typeTextToArraySnapshot(parent: YText, snapshot: Snapshot): List<Any?> =
    parent.doc.textArrayAtSnapshot(parent, snapshot)

fun typeXmlFragmentToJsonSnapshot(parent: YXmlFragment, snapshot: Snapshot): List<Any?> =
    parent.doc.xmlFragmentAtSnapshot(parent, snapshot)

fun typeXmlFragmentToArraySnapshot(parent: YXmlFragment, snapshot: Snapshot): List<YXmlNode> =
    parent.doc.xmlFragmentArrayAtSnapshot(parent, snapshot)

fun typeXmlFragmentToStringSnapshot(
    parent: YXmlFragment,
    snapshot: Snapshot,
    forceTag: Boolean = false,
): String = parent.doc.xmlFragmentStringAtSnapshot(parent, snapshot, forceTag = forceTag)

fun typeXmlFragmentToDeltaSnapshot(parent: YXmlFragment, snapshot: Snapshot): List<YArrayDeltaOp> =
    parent.doc.xmlFragmentDeltaAtSnapshot(parent, snapshot)

fun cleanupYTextFormatting(type: YText): Int {
    if (!type.doc.cleanupFormatting) return 0
    return 0
}

fun cleanupYTextAfterTransaction(transaction: YTransaction): Int =
    transaction.changedTypes.filterIsInstance<YText>().sumOf(::cleanupYTextFormatting)

fun cleanupYTextAfterTransaction(transaction: YTransactionEvent): Int =
    transaction.changedTypes.filterIsInstance<YText>().sumOf(::cleanupYTextFormatting)

fun cleanupContextlessFormattingGap(transaction: YTransaction, item: Item?): Int =
    cleanupContextlessFormattingGap(transaction.doc, item)

fun cleanupContextlessFormattingGap(transaction: YTransactionEvent, item: Item?): Int =
    cleanupContextlessFormattingGap(transaction.doc, item)

@Suppress("UNUSED_PARAMETER")
fun cleanupContextlessFormattingGap(doc: YDoc, item: Item?): Int {
    if (!doc.cleanupFormatting) return 0
    return 0
}

fun cleanupFormattingGap(
    transaction: YTransaction,
    start: Item?,
    curr: Item?,
    startFormats: Map<String, Any?>,
    currFormats: MutableMap<String, Any?>,
): Int = cleanupFormattingGap(transaction.doc, start, curr, startFormats, currFormats)

fun cleanupFormattingGap(
    transaction: YTransactionEvent,
    start: Item?,
    curr: Item?,
    startFormats: Map<String, Any?>,
    currFormats: MutableMap<String, Any?>,
): Int = cleanupFormattingGap(transaction.doc, start, curr, startFormats, currFormats)

@Suppress("UNUSED_PARAMETER")
fun cleanupFormattingGap(
    doc: YDoc,
    start: Item?,
    curr: Item?,
    startFormats: Map<String, Any?> = emptyMap(),
    currFormats: MutableMap<String, Any?> = linkedMapOf(),
): Int {
    if (!doc.cleanupFormatting) return 0
    return 0
}

fun findRootTypeKey(type: AbstractYType): String {
    check(type.name in type.doc.rootNames()) { "type is not a root type" }
    return type.name
}

private fun Id.logId(): String = "$client:$clock"

private fun ItemContent.logContent(doc: YDoc): String = when (this) {
    is ItemContent.Value -> "Value(${doc.valueToJson(value).logAny()})"
    is ItemContent.Text -> "Text(value=${value.logAny()}, attrs=${attributes.logValues(doc)})"
    is ItemContent.TextEmbed -> "TextEmbed(value=${doc.valueToJson(value).logAny()}, attrs=${attributes.logValues(doc)})"
    is ItemContent.TextFormat -> "TextFormat(target=${target.logId()}, length=$length, attrs=${attributes.logValues(doc)})"
    is ItemContent.MapEntry -> "MapEntry(${doc.valueToJson(value).logAny()})"
    is ItemContent.XmlNode -> "XmlNode(${value.toEventJson().logAny()})"
    is ItemContent.Deleted -> "Deleted(kind=$kind)"
}

private fun Map<String, YValue>.logValues(doc: YDoc): String =
    toSortedMap().mapValues { (_, value) -> doc.valueToJson(value) }.logAny()

private fun Any?.logAny(): String = when (this) {
    null -> "null"
    is String -> "\"$this\""
    is Map<*, *> -> entries.joinToString(prefix = "{", postfix = "}") { (key, value) ->
        "${key.logAny()}=${value.logAny()}"
    }
    is List<*> -> joinToString(prefix = "[", postfix = "]") { value -> value.logAny() }
    is ByteArray -> "ByteArray(${size})"
    else -> toString()
}
