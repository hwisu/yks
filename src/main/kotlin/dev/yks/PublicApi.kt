package dev.yks

public typealias Doc = YDoc
public typealias AbstractType = AbstractYType
public typealias Type = AbstractYType
public typealias YType = AbstractYType
public typealias Text = YText
public typealias XmlElement = YXmlElementType
public typealias XmlText = YXmlTextType
public typealias XmlFragment = YXmlFragment
public typealias XmlHook = YXmlHook

public fun generateNewClientId(): Long = YDoc.generateNewClientId()

public fun <T> transact(doc: YDoc, origin: Any? = null, local: Boolean = true, block: () -> T): T =
    doc.transact(origin = origin, local = local, block = block)

public fun <T> transact(doc: YDoc, block: (YTransaction) -> T, origin: Any? = null, local: Boolean = true): T =
    doc.transact(block = block, origin = origin, local = local)

public fun addChangedTypeToTransaction(transaction: YTransaction, type: AbstractYType, parentSub: String? = null) {
    transaction.addChangedType(type, parentSub)
}

public fun deleteText(type: YText, index: Int, length: Int = 1, origin: Any? = null) {
    type.deleteText(index, length, origin)
}

public fun getPathTo(
    parent: AbstractYType,
    child: AbstractYType,
    renderer: AbstractRenderer = baseRenderer,
): List<Any> = parent.getPathTo(child, renderer)

public data class YTypeChild(
    val id: Id,
    val origin: Id?,
    val rightOrigin: Id?,
    val parent: String,
    val parentSub: String?,
    val kind: RootKind,
    val deleted: Boolean,
    val length: Long,
)

public fun getTypeChildren(type: AbstractYType): List<ItemStruct> =
    type.doc.typeChildren(type).map { item -> item.toItemStruct(type.doc) }

public fun getTypeChildSummaries(type: AbstractYType): List<YTypeChild> =
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

public fun getTypeChildTypes(type: AbstractYType): List<AbstractYType> = type.doc.directNestedChildTypes(type)

public fun logType(type: AbstractYType): String {
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

public fun isParentOf(parent: AbstractYType, child: AbstractYType): Boolean {
    require(parent.doc === child.doc) { "types must belong to the same document" }
    return parent.doc.pathBetween(parent.name, child.name) != null
}

public fun isParentOf(parent: AbstractYType, child: Item?): Boolean {
    var current = child
    while (current != null) {
        if (current.parent == parent.name) return true
        val currentParent = parent.doc.typeForParent(current.parent) ?: return false
        val ownerId = parent.doc.typeRefItemId(currentParent) ?: return false
        current = parent.doc.getItem(ownerId)?.toItemStruct(parent.doc)
    }
    return false
}

public fun computeModifiedFromItems(doc: YDoc, items: IdSet): Map<AbstractYType, Set<String?>> {
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

public fun typeMapGetSnapshot(parent: YMap, key: String, snapshot: Snapshot): Any? =
    parent.doc.mapValueAtSnapshot(parent, key, snapshot)?.let(parent.doc::valueToAny)

public fun typeMapGetAllSnapshot(parent: YMap, snapshot: Snapshot): Map<String, Any?> =
    parent.doc.mapAtSnapshot(parent, snapshot).mapValues { (_, value) -> parent.doc.valueToAny(value) }

public fun typeListToArraySnapshot(parent: AbstractYType, snapshot: Snapshot): List<Any?> = when (parent) {
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

public fun typeArrayToArraySnapshot(parent: YArray, snapshot: Snapshot): List<Any?> =
    typeListToArraySnapshot(parent, snapshot)

public fun typeTextToDeltaSnapshot(parent: YText, snapshot: Snapshot): YTextDelta =
    parent.doc.textDeltaAtSnapshot(parent, snapshot)

public fun typeTextToStringSnapshot(parent: YText, snapshot: Snapshot): String =
    parent.doc.textStringAtSnapshot(parent, snapshot)

public fun typeTextToArraySnapshot(parent: YText, snapshot: Snapshot): List<Any?> =
    parent.doc.textArrayAtSnapshot(parent, snapshot)

public fun typeXmlFragmentToJsonSnapshot(parent: YXmlFragment, snapshot: Snapshot): List<Any?> =
    parent.doc.xmlFragmentAtSnapshot(parent, snapshot)

public fun typeXmlFragmentToArraySnapshot(parent: YXmlFragment, snapshot: Snapshot): List<Any?> =
    parent.doc.xmlFragmentArrayAtSnapshot(parent, snapshot)

public fun typeXmlFragmentToStringSnapshot(
    parent: YXmlFragment,
    snapshot: Snapshot,
    forceTag: Boolean = false,
): String = parent.doc.xmlFragmentStringAtSnapshot(parent, snapshot, forceTag = forceTag)

public fun typeXmlFragmentToDeltaSnapshot(parent: YXmlFragment, snapshot: Snapshot): List<YArrayDeltaOp> =
    parent.doc.xmlFragmentDeltaAtSnapshot(parent, snapshot)

public fun cleanupYTextFormatting(type: YText): Int {
    if (!type.doc.cleanupFormatting) return 0
    var cleaned = 0
    type.doc.transact {
        val items = type.doc.sequence(type.name)
        var startIndex = 0
        var startAttributes = linkedMapOf<String, YValue>()
        val currentAttributes = linkedMapOf<String, YValue>()
        items.forEachIndexed { endIndex, item ->
            if (item.deleted) return@forEachIndexed
            val marker = item.content as? ItemContent.NativeTextFormat
            if (marker?.kind == type.kind) {
                currentAttributes.applyNativeTextFormat(marker)
            } else if (item.countable && item.content.kind == type.kind) {
                cleaned += cleanupFormattingGap(
                    type = type,
                    items = items,
                    startIndex = startIndex,
                    currentIndex = endIndex,
                    startAttributes = startAttributes,
                    currentAttributes = currentAttributes,
                )
                startAttributes = LinkedHashMap(currentAttributes)
                startIndex = endIndex
            }
        }
    }
    return cleaned
}

private fun cleanupFormattingGap(
    type: YText,
    items: List<StoreItem>,
    startIndex: Int,
    currentIndex: Int,
    startAttributes: Map<String, YValue>,
    currentAttributes: MutableMap<String, YValue>,
): Int {
    var gapEnd = startIndex
    val endFormats = linkedMapOf<String, StoreItem>()
    while (gapEnd < items.size) {
        val item = items[gapEnd]
        if (!item.deleted && item.countable) break
        val marker = item.content as? ItemContent.NativeTextFormat
        if (!item.deleted && marker?.kind == type.kind) {
            endFormats[marker.key] = item
        }
        gapEnd++
    }

    var cleaned = 0
    var reachedCurrent = false
    var index = startIndex
    while (index < gapEnd) {
        val item = items[index++]
        if (index - 1 == currentIndex) reachedCurrent = true
        if (item.deleted) continue
        val marker = item.content as? ItemContent.NativeTextFormat ?: continue
        if (marker.kind != type.kind) continue
        val startValue = startAttributes[marker.key] ?: YValue.Null
        var deletedMarker = false
        if (endFormats[marker.key]?.id != item.id || startValue == marker.value) {
            type.doc.deleteItemsByIds(listOf(item.id), markCleanups = true)
            cleaned++
            deletedMarker = true
            val currentValue = currentAttributes[marker.key] ?: YValue.Null
            if (!reachedCurrent && currentValue == marker.value && startValue != marker.value) {
                if (startValue == YValue.Null) {
                    currentAttributes.remove(marker.key)
                } else {
                    currentAttributes[marker.key] = startValue
                }
            }
        }
        if (!reachedCurrent && !deletedMarker) {
            currentAttributes.applyNativeTextFormat(marker)
        }
    }
    return cleaned
}

private fun MutableMap<String, YValue>.applyNativeTextFormat(marker: ItemContent.NativeTextFormat) {
    if (marker.value == YValue.Null) remove(marker.key) else this[marker.key] = marker.value
}

public fun cleanupYTextAfterTransaction(transaction: YTransaction): Int =
    transaction.changedTypes.filterIsInstance<YText>().sumOf(::cleanupYTextFormatting)

public fun cleanupYTextAfterTransaction(transaction: YTransactionEvent): Int =
    transaction.changedTypes.filterIsInstance<YText>().sumOf(::cleanupYTextFormatting)

public fun cleanupContextlessFormattingGap(transaction: YTransaction, item: Item?): Int =
    cleanupContextlessFormattingGap(transaction.doc, item)

public fun cleanupContextlessFormattingGap(transaction: YTransactionEvent, item: Item?): Int =
    cleanupContextlessFormattingGap(transaction.doc, item)

@Suppress("UNUSED_PARAMETER")
public fun cleanupContextlessFormattingGap(doc: YDoc, item: Item?): Int {
    if (!doc.cleanupFormatting) return 0
    val type = item?.parent?.let(doc::typeForParent) as? YText ?: return 0
    return cleanupYTextFormatting(type)
}

public fun cleanupFormattingGap(
    transaction: YTransaction,
    start: Item?,
    curr: Item?,
    startFormats: Map<String, Any?>,
    currFormats: MutableMap<String, Any?>,
): Int = cleanupFormattingGap(transaction.doc, start, curr, startFormats, currFormats)

public fun cleanupFormattingGap(
    transaction: YTransactionEvent,
    start: Item?,
    curr: Item?,
    startFormats: Map<String, Any?>,
    currFormats: MutableMap<String, Any?>,
): Int = cleanupFormattingGap(transaction.doc, start, curr, startFormats, currFormats)

@Suppress("UNUSED_PARAMETER")
public fun cleanupFormattingGap(
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

public fun findRootTypeKey(type: AbstractYType): String {
    check(type.name in type.doc.rootNames()) { "type is not a root type" }
    return type.name
}

public fun findTypeInOtherDoc(type: AbstractYType, otherDoc: YDoc): AbstractYType {
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

private fun ItemContent.logContent(doc: YDoc): String = when (this) {
    is ItemContent.Value -> "Value(${doc.valueToJson(value).logAny()})"
    is ItemContent.ArrayValues -> "ArrayValues(${values.map(doc::valueToJson).logAny()})"
    is ItemContent.Text -> "Text(value=${value.logAny()}, attrs=${attributes.logValues(doc)})"
    is ItemContent.TextEmbed -> "TextEmbed(value=${doc.valueToJson(value).logAny()}, attrs=${attributes.logValues(doc)})"
    is ItemContent.TextFormat -> "TextFormat(target=${target.logId()}, length=$length, attrs=${attributes.logValues(doc)})"
    is ItemContent.NativeTextFormat -> "NativeTextFormat(key=${key.logAny()}, value=${doc.valueToJson(value).logAny()})"
    is ItemContent.MapEntry -> "MapEntry(${doc.valueToJson(value).logAny()})"
    is ItemContent.MapEntries -> "MapEntries(${values.map(doc::valueToJson).logAny()})"
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
