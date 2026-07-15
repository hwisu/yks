package dev.yks

typealias Doc = YDoc
typealias AbstractType = AbstractYType
typealias Type = AbstractYType
typealias YType = AbstractYType
typealias Text = YText
typealias XmlElement = YXmlElementType
typealias XmlText = YXmlTextType
typealias XmlFragment = YXmlFragment
typealias XmlHook = YXmlHook

fun generateNewClientId(): Long = YDoc.generateNewClientId()

fun <T> transact(doc: YDoc, origin: Any? = null, local: Boolean = true, block: () -> T): T =
    doc.transact(origin = origin, local = local, block = block)

fun <T> transact(doc: YDoc, block: (YTransaction) -> T, origin: Any? = null, local: Boolean = true): T =
    doc.transact(block = block, origin = origin, local = local)

fun addChangedTypeToTransaction(transaction: YTransaction, type: AbstractYType, parentSub: String? = null) {
    transaction.addChangedType(type, parentSub)
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

fun getTypeChildren(type: AbstractYType): List<ItemStruct> =
    type.doc.typeChildren(type).map { item -> item.toItemStruct(type.doc) }

fun getTypeChildSummaries(type: AbstractYType): List<YTypeChild> =
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

fun isParentOf(parent: AbstractYType, child: Item?): Boolean {
    var current = child
    while (current != null) {
        if (current.parent == parent.name) return true
        val currentParent = parent.doc.typeForParent(current.parent) ?: return false
        val ownerId = parent.doc.typeRefItemId(currentParent) ?: return false
        current = parent.doc.getItem(ownerId)?.toItemStruct(parent.doc)
    }
    return false
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

fun typeListToArraySnapshot(parent: AbstractYType, snapshot: Snapshot): List<Any?> = when (parent) {
    is YUnopenedRoot -> error("an unopened root has no concrete list type")
    is YArray -> parent.doc.arrayAtSnapshot(parent, snapshot)
    is YXmlTextType -> parent.doc.textArrayAtSnapshot(parent, snapshot)
    is YText -> parent.doc.textArrayAtSnapshot(parent, snapshot)
    is YXmlElementType -> parent.doc.sequenceAtSnapshot(parent, snapshot).map { item ->
        item.content.toXmlChild(parent.doc)
    }
    is YXmlFragment -> parent.doc.xmlFragmentArrayAtSnapshot(parent, snapshot)
    is YXmlHook,
    is YMap -> error("map-backed shared types do not have list content")
}

fun typeArrayToArraySnapshot(parent: YArray, snapshot: Snapshot): List<Any?> =
    typeListToArraySnapshot(parent, snapshot)

fun typeTextToDeltaSnapshot(parent: YText, snapshot: Snapshot): YTextDelta =
    parent.doc.textDeltaAtSnapshot(parent, snapshot)

fun typeTextToStringSnapshot(parent: YText, snapshot: Snapshot): String =
    parent.doc.textStringAtSnapshot(parent, snapshot)

fun typeTextToArraySnapshot(parent: YText, snapshot: Snapshot): List<Any?> =
    parent.doc.textArrayAtSnapshot(parent, snapshot)

fun typeXmlFragmentToJsonSnapshot(parent: YXmlFragment, snapshot: Snapshot): List<Any?> =
    parent.doc.xmlFragmentAtSnapshot(parent, snapshot)

fun typeXmlFragmentToArraySnapshot(parent: YXmlFragment, snapshot: Snapshot): List<Any?> =
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
    var cleaned = 0
    type.doc.transact {
        var activeFormats = type.liveTextFormatItemsForCleanup()
        var baseline = type.renderedTextAttributesForCleanup(activeFormats)
        activeFormats.sortedWith(textFormatCleanupOrder).forEach { candidate ->
            if (activeFormats.none { item -> item.id == candidate.id }) return@forEach
            val withoutCandidate = activeFormats.filterNot { item -> item.id == candidate.id }
            if (type.renderedTextAttributesForCleanup(withoutCandidate) == baseline) {
                type.doc.deleteItemsByIds(listOf(candidate.id), markCleanups = true)
                activeFormats = withoutCandidate
                baseline = type.renderedTextAttributesForCleanup(activeFormats)
                cleaned++
            }
        }
    }
    return cleaned
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
    val type = item?.parent?.let(doc::typeForParent) as? YText ?: return 0
    return cleanupYTextFormatting(type)
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
    val type = start?.parent?.let(doc::typeForParent) as? YText
        ?: curr?.parent?.let(doc::typeForParent) as? YText
        ?: return 0
    return cleanupYTextFormatting(type)
}

fun findRootTypeKey(type: AbstractYType): String {
    check(type.name in type.doc.rootNames()) { "type is not a root type" }
    return type.name
}

fun findTypeInOtherDoc(type: AbstractYType, otherDoc: YDoc): AbstractYType {
    if (type.name in type.doc.rootNames()) {
        val rootKey = findRootTypeKey(type)
        otherDoc.rootType(rootKey)?.let { return it }
        require(rootKey in otherDoc.rootNames()) { "type does not exist in other document" }
        return if (type is YXmlElementType) {
            otherDoc.getXmlElement(rootKey, type.nodeName)
        } else {
            otherDoc.get(rootKey, type.kind)
        }
    }
    val itemId = type.doc.typeRefItemId(type) ?: error("type does not exist in its source document")
    return otherDoc.typeFromItemId(itemId) ?: error("type does not exist in other document")
}

private fun Id.logId(): String = "$client:$clock"

private val textFormatCleanupOrder: Comparator<StoreItem> =
    compareByDescending<StoreItem> { it.id.client }.thenBy { it.id.clock }

private fun YText.liveTextFormatItemsForCleanup(): List<StoreItem> =
    doc.sequence(name)
        .filter { item ->
            !item.deleted && item.content.kind == kind &&
                (item.content is ItemContent.TextFormat || item.content is ItemContent.NativeTextFormat)
        }
        .sortedWith(textFormatCleanupOrder)

private fun YText.renderedTextAttributesForCleanup(
    formatItems: List<StoreItem>,
): List<Map<String, YValue>> {
    val liveFormatIds = formatItems.mapTo(hashSetOf()) { item -> item.id }
    val textItems = mutableListOf<StoreItem>()
    val attributes = mutableListOf<MutableMap<String, YValue>>()
    val activeNative = linkedMapOf<String, YValue>()
    doc.sequence(name).forEach { item ->
        if (item.deleted || item.content.kind != kind) return@forEach
        when (val content = item.content) {
            is ItemContent.NativeTextFormat -> if (item.id in liveFormatIds) {
                activeNative[content.key] = content.value
            }
            else -> if (content.isTextCountableForCleanup()) {
                textItems.add(item)
                val attrs = content.baseTextAttributesForCleanup().toMutableMap()
                attrs.applyTextFormatAttributesForCleanup(activeNative)
                attributes.add(attrs)
            }
        }
    }

    formatItems.filter { it.content is ItemContent.TextFormat }.sortedWith(textFormatCleanupOrder).forEach { formatItem ->
        val format = formatItem.content as? ItemContent.TextFormat ?: return@forEach
        val start = textItems.indexOfFirst { textItem -> textItem.id == format.target }
        if (start < 0) return@forEach
        val end = boundedIntRangeEnd(start, format.length, textItems.size, "text format cleanup")
        for (index in start until end) {
            attributes[index].applyTextFormatAttributesForCleanup(format.attributes)
        }
        if (end < attributes.size) {
            attributes[end].applyTextFormatAttributesForCleanup(format.afterAttributes)
        }
    }

    val terminal = activeNative
        .filterValues { value -> value != YValue.Null }
        .toSortedMap()
    return attributes.map { attrs -> attrs.toSortedMap() } + terminal
}

private fun MutableMap<String, YValue>.applyTextFormatAttributesForCleanup(attributes: Map<String, YValue>) {
    attributes.forEach { (key, value) ->
        if (value == YValue.Null) {
            remove(key)
        } else {
            this[key] = value
        }
    }
}

private fun ItemContent.isTextCountableForCleanup(): Boolean =
    this is ItemContent.Text ||
        this is ItemContent.TextEmbed ||
        (this is ItemContent.XmlType && kind in setOf(RootKind.Text, RootKind.XmlText))

private fun ItemContent.baseTextAttributesForCleanup(): Map<String, YValue> = when (this) {
    is ItemContent.Text -> baseAttributes
    is ItemContent.TextEmbed -> baseAttributes
    is ItemContent.XmlType -> baseAttributes
    else -> emptyMap()
}

private fun ItemContent.logContent(doc: YDoc): String = when (this) {
    is ItemContent.Value -> "Value(${doc.valueToJson(value).logAny()})"
    is ItemContent.Text -> "Text(value=${value.logAny()}, attrs=${attributes.logValues(doc)})"
    is ItemContent.TextEmbed -> "TextEmbed(value=${doc.valueToJson(value).logAny()}, attrs=${attributes.logValues(doc)})"
    is ItemContent.TextFormat -> "TextFormat(target=${target.logId()}, length=$length, attrs=${attributes.logValues(doc)})"
    is ItemContent.NativeTextFormat -> "NativeTextFormat(key=${key.logAny()}, value=${doc.valueToJson(value).logAny()})"
    is ItemContent.MapEntry -> "MapEntry(${doc.valueToJson(value).logAny()})"
    is ItemContent.XmlNode -> "XmlNode(${value.toEventJson().logAny()})"
    is ItemContent.XmlType -> "XmlType(ref=${ref.kind}:${ref.name}, nodeName=${nodeName.logAny()})"
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
