package dev.yks

private var globalSearchMarkerTimestamp = 0L

data class ArraySearchMarker(
    var p: Item,
    var index: Int,
) {
    var timestamp: Long = nextSearchMarkerTimestamp()
}

class ItemTextListPosition(
    var left: Item?,
    var right: Item?,
    var index: Int,
    val currentFormats: MutableMap<String, Any?> = linkedMapOf(),
    val renderer: AbstractRenderer = baseRenderer,
    val type: AbstractYType? = null,
) {
    fun forward(): ItemTextListPosition {
        val owner = type ?: error("type is required to move an ItemTextListPosition")
        return forward(owner)
    }

    fun forward(type: AbstractYType): ItemTextListPosition {
        val item = right ?: error("right item is required to move an ItemTextListPosition")
        if (!item.deleted && item.content is ContentFormat) {
            updateCurrentFormats(currentFormats, item.content)
        } else {
            index = checkedClockAdd(
                index.toLong(),
                rendererContentLength(renderer, item),
                "text list position index",
            ).toNonNegativeInt("text list position index")
        }
        left = item
        right = logicalTypeStructs(type).nextAfter(item)
        return this
    }

    fun formatText(parent: YText, length: Int, formats: Map<String, Any?>, origin: Any? = null): ItemTextListPosition {
        parent.formatText(index, length, formats, origin)
        formats.forEach { (key, value) ->
            if (value == null) {
                currentFormats.remove(key)
            } else {
                currentFormats[key] = value
            }
        }
        return this
    }
}

private fun nextSearchMarkerTimestamp(): Long = globalSearchMarkerTimestamp++

fun equalFormats(left: Any?, right: Any?): Boolean = yTypeHelperValuesEqual(left, right)

fun createItemTextListPosition(
    type: AbstractYType,
    index: Int,
    currentFormats: MutableMap<String, Any?> = linkedMapOf(),
    renderer: AbstractRenderer = baseRenderer,
): ItemTextListPosition {
    require(index >= 0) { "index must be non-negative" }
    val visible = logicalTypeStructs(type).filter { item -> !item.deleted && item.countable }
    require(index <= visible.size) { "index is out of bounds" }
    return ItemTextListPosition(
        left = visible.getOrNull(index - 1),
        right = visible.getOrNull(index),
        index = index,
        currentFormats = currentFormats,
        renderer = renderer,
        type = type,
    )
}

fun findMarker(type: AbstractYType, index: Int): ArraySearchMarker? {
    require(index >= 0) { "index must be non-negative" }
    if (index == 0) return null

    val visible = logicalTypeStructs(type)
        .filter { item -> !item.deleted && item.countable }
    if (visible.isEmpty()) return null

    val markerIndex = minOf(index, visible.size - 1)
    return ArraySearchMarker(visible[markerIndex], markerIndex)
}

fun updateMarkerChanges(searchMarker: MutableList<ArraySearchMarker>, index: Int, len: Int) {
    require(index >= 0) { "index must be non-negative" }
    searchMarker.forEach { marker ->
        if (index < marker.index || (len > 0 && index == marker.index)) {
            marker.index = maxOf(index, marker.index + len)
        }
    }
}

fun callTypeObservers(type: AbstractYType, event: YEvent) {
    callTypeObservers(type, event.transaction, event)
}

fun callTypeObservers(type: AbstractYType, transaction: YTransactionEvent?, event: YEvent) {
    require(event.target.doc === type.doc) { "event target must belong to the same document" }
    val directEvent = event.copy(
        target = type,
        currentTarget = type,
        changedTarget = type,
        transaction = transaction ?: event.transaction,
        path = emptyList(),
    )
    type.emit(directEvent)
    emitDeepTypeObserverEvents(type, directEvent)
}

fun insertContent(
    parent: YText,
    index: Int,
    content: AbstractContent,
    formats: Map<String, Any?> = emptyMap(),
    origin: Any? = null,
): ItemTextListPosition =
    insertContent(parent, createItemTextListPosition(parent, index), content, formats, origin)

fun insertContent(
    parent: YText,
    currPos: ItemTextListPosition,
    content: AbstractContent,
    formats: Map<String, Any?> = emptyMap(),
    origin: Any? = null,
): ItemTextListPosition {
    require(currPos.index in 0..parent.length) { "position index is out of bounds" }
    val insertedLength = insertTextContent(parent, currPos.index, content, formats, origin)
    currPos.moveTo(parent, currPos.index + insertedLength, formats)
    return currPos
}

fun insertContentHelper(
    parent: YText,
    index: Int,
    insert: Any?,
    formats: Map<String, Any?> = emptyMap(),
    origin: Any? = null,
): ItemTextListPosition =
    insertContentHelper(parent, createItemTextListPosition(parent, index), insert, formats, origin)

fun insertContentHelper(
    parent: YText,
    currPos: ItemTextListPosition,
    insert: Any?,
    formats: Map<String, Any?> = emptyMap(),
    origin: Any? = null,
): ItemTextListPosition {
    when (insert) {
        is String -> insertContent(parent, currPos, ContentString(insert), formats, origin)
        is Char -> insertContent(parent, currPos, ContentString(insert.toString()), formats, origin)
        is ByteArray -> insertContent(parent, currPos, ContentBinary(insert), formats, origin)
        is AbstractYType -> insertContent(parent, currPos, ContentType(insert), formats, origin)
        is YDoc -> insertContent(parent, currPos, createContentDocFromDoc(insert), formats, origin)
        is Iterable<*> -> parent.doc.transact(origin = origin) {
            val values = insert.toList()
            parent.insert(currPos.index, values, formats)
            currPos.moveTo(parent, currPos.index + values.textInsertLengthForPosition(), formats)
        }
        is Array<*> -> insertContentHelper(parent, currPos, insert.toList(), formats, origin)
        else -> insertContent(parent, currPos, ContentEmbed(insert), formats, origin)
    }
    return currPos
}

fun typeListLength(type: AbstractYType): Int = when (type) {
    is YUnopenedRoot -> error("open root '${type.name}' with a concrete getter first")
    is YArray -> type.length
    is YText -> type.length
    is YXmlFragment -> type.length
    is YXmlElementType -> type.length
    is YMap -> error("YMap is not a list type")
}

fun typeListSlice(type: AbstractYType, start: Int = 0, end: Int = typeListLength(type)): List<Any?> {
    val values = typeListValues(type)
    val normalizedStart = normalizeTypeListSliceIndex(start, values.size)
    val normalizedEnd = normalizeTypeListSliceIndex(end, values.size)
    if (normalizedEnd <= normalizedStart) return emptyList()
    return values.subList(normalizedStart, normalizedEnd)
}

fun typeListGet(type: AbstractYType, index: Int): Any? {
    if (index < 0) return null
    return typeListValues(type).getOrNull(index)
}

fun typeListInsertGenerics(type: AbstractYType, index: Int, content: List<Any?>) {
    require(index >= 0) { "index must be non-negative" }
    require(index <= typeListLength(type)) { "insert index is out of bounds" }
    if (content.isEmpty()) return
    when (type) {
        is YUnopenedRoot -> error("open root '${type.name}' with a concrete getter first")
        is YArray -> type.insert(index, content)
        is YText -> type.insert(index, content)
        is YXmlFragment -> {
            val ops = if (index == 0) {
                listOf(YArrayDeltaOp(insert = content))
            } else {
                listOf(
                    YArrayDeltaOp(retain = index),
                    YArrayDeltaOp(insert = content),
                )
            }
            type.applyDelta(ops)
        }
        is YXmlElementType -> {
            val ops = if (index == 0) {
                listOf(YArrayDeltaOp(insert = content))
            } else {
                listOf(
                    YArrayDeltaOp(retain = index),
                    YArrayDeltaOp(insert = content),
                )
            }
            type.applyDelta(ops)
        }
        is YMap -> error("YMap is not a list type")
    }
}

fun typeListInsertGenericsAfter(type: AbstractYType, referenceItem: Item?, content: List<Any?>) {
    val index = if (referenceItem == null) {
        0
    } else {
        require(referenceItem.parent == type.name) { "reference item must belong to the parent type" }
        require(referenceItem.kind == type.kind) { "reference item kind does not match the parent type" }
        val structs = getTypeStructs(type)
        val position = structs.indexOfFirst { it.id == referenceItem.id }
        require(position >= 0) { "reference item must belong to the parent type" }
        structs.asSequence()
            .take(position + 1)
            .filter { item -> !item.deleted && item.countable }
            .fold(0L) { length, item -> checkedClockAdd(length, item.length, "list insertion index") }
            .toNonNegativeInt("list insertion index")
    }
    typeListInsertGenerics(type, index, content)
}

fun typeListPushGenerics(type: AbstractYType, content: List<Any?>) {
    typeListInsertGenerics(type, typeListLength(type), content)
}

fun typeListDelete(type: AbstractYType, index: Int, length: Int) {
    when (type) {
        is YUnopenedRoot -> error("open root '${type.name}' with a concrete getter first")
        is YArray -> type.delete(index, length)
        is YText -> type.delete(index, length)
        is YXmlFragment -> type.delete(index, length)
        is YXmlElementType -> type.delete(index, length)
        is YMap -> error("YMap is not a list type")
    }
}

internal const val attributedDeletesMetaKey: String = "attributedDeletes"

internal fun recordRendererAttributedDeletes(
    transaction: YTransaction,
    type: AbstractYType,
    startRendered: Int,
    length: Int,
    renderer: AbstractRenderer,
) {
    if (length <= 0 || renderer === baseRenderer) return
    val deleteIds = collectRendererAttributedDeletedIds(type, startRendered, length, renderer)
    if (deleteIds.isEmpty()) return

    val existing = transaction.meta[attributedDeletesMetaKey]
    val attributedDeletes = if (existing is IdSet) {
        existing
    } else {
        createIdSet().also { transaction.meta[attributedDeletesMetaKey] = it }
    }
    insertIntoIdSet(attributedDeletes, deleteIds)
    transaction.addChangedType(type)
}

internal fun renderedSequenceIndexToVisibleIndex(
    type: AbstractYType,
    index: Int,
    renderer: AbstractRenderer = type.activeRenderer,
    clampToEnd: Boolean = false,
): Int {
    val target = index.coerceAtLeast(0)
    var rendered = 0
    var visible = 0
    type.doc.sequence(type.name)
        .filter { item -> item.content.kind == type.kind && item.countable }
        .flatMap(StoreItem::logicalUnits)
        .forEach { item ->
            val renderedLength = rendererContentLength(renderer, item.toItemStruct(type.doc))
                .toNonNegativeInt("rendered sequence length")
            if (renderedLength > 0 && rendered + renderedLength > target) {
                return visible + (target - rendered)
            }
            rendered = checkedClockAdd(
                rendered.toLong(),
                renderedLength.toLong(),
                "rendered sequence index",
            ).toNonNegativeInt("rendered sequence index")
            if (!item.deleted) {
                visible = checkedClockAdd(visible.toLong(), item.length, "visible sequence index")
                    .toNonNegativeInt("visible sequence index")
            }
        }
    if (clampToEnd && target >= rendered) return visible
    require(target == rendered) { "index is out of bounds" }
    return visible
}

private fun collectRendererAttributedDeletedIds(
    type: AbstractYType,
    startRendered: Int,
    length: Int,
    renderer: AbstractRenderer,
): IdSet {
    val targetStart = startRendered.coerceAtLeast(0).toLong()
    val targetEnd = checkedClockAdd(targetStart, length.toLong(), "rendered delete range end")
    val deleteIds = createIdSet()
    var rendered = 0L
    type.doc.sequence(type.name)
        .filter { item -> item.content.kind == type.kind && item.countable }
        .flatMap(StoreItem::logicalUnits)
        .forEach { item ->
            val struct = item.toItemStruct(type.doc)
            val renderedLength = rendererContentLength(renderer, struct)
            if (renderedLength <= 0) return@forEach
            val itemStart = rendered
            val itemEnd = checkedClockAdd(rendered, renderedLength, "rendered item end")
            if (item.deleted && itemEnd > targetStart && itemStart < targetEnd) {
                val contents = mutableListOf<AttributedContent>()
                renderer.readContent(
                    contents,
                    item.id.client,
                    item.id.clock,
                    deleted = true,
                    content = struct.content,
                    renderBehavior = 0,
                )
                var contentStart = itemStart
                contents.forEach { attributed ->
                    val contentLength = attributed.content.getLength()
                    val contentEnd = checkedClockAdd(contentStart, contentLength, "rendered content end")
                    val overlapStart = maxOf(targetStart, contentStart)
                    val overlapEnd = minOf(targetEnd, contentEnd)
                    if (overlapEnd > overlapStart) {
                        deleteIds.add(
                            item.id.client,
                            checkedClockAdd(
                                attributed.clock,
                                overlapStart - contentStart,
                                "attributed delete clock",
                            ),
                            overlapEnd - overlapStart,
                        )
                    }
                    contentStart = contentEnd
                }
            }
            rendered = itemEnd
            if (rendered >= targetEnd) return@forEach
        }
    return deleteIds
}

fun typeMapSet(parent: AbstractYType, key: String, value: Any?): Any? =
    parent.doc.setTypeAttribute(parent.name, key, value)

fun typeMapDelete(parent: AbstractYType, key: String) {
    parent.doc.deleteTypeAttribute(parent.name, key)
}

fun typeMapGet(parent: AbstractYType, key: String): Any? =
    parent.doc.visibleMapValue(parent.name, key)?.let(parent.doc::valueToAny)

fun typeMapGetAll(parent: AbstractYType): Map<String, Any?> =
    parent.doc.typeAttributes(parent.name)

fun typeMapHas(parent: AbstractYType, key: String): Boolean =
    parent.doc.hasTypeAttribute(parent.name, key)

fun typeMapGetSnapshot(parent: AbstractYType, key: String, snapshot: Snapshot): Any? =
    parent.doc.mapValueAtSnapshot(parent, key, snapshot)?.let(parent.doc::valueToAny)

fun typeMapGetAllSnapshot(parent: AbstractYType, snapshot: Snapshot): Map<String, Any?> =
    parent.doc.mapAtSnapshot(parent, snapshot).mapValues { (_, value) -> parent.doc.valueToAny(value) }

fun typeMapGetDelta(parent: AbstractYType, attrsToRender: Set<String?>? = null): YMapDelta =
    typeMapGetDelta(YMapDelta(), parent, attrsToRender)

@Suppress("UNUSED_PARAMETER")
fun typeMapGetDelta(
    delta: YMapDelta,
    parent: AbstractYType,
    attrsToRender: Set<String?>? = null,
    renderer: AbstractRenderer = baseRenderer,
    deep: Boolean = false,
    modified: Set<AbstractYType>? = null,
    deletedItems: IdSet? = null,
    itemsToRender: IdSet? = null,
    opts: Any? = null,
    optsAll: Any? = null,
): YMapDelta {
    val visible = typeMapGetAll(parent)
    if (attrsToRender == null) {
        visible.toSortedMap().forEach { (key, value) -> delta.setAttr(key, value) }
    } else {
        attrsToRender.filterNotNull().sorted().forEach { key ->
            if (key in visible) {
                delta.setAttr(key, visible[key])
            } else if (itemsToRender != null) {
                delta.deleteAttr(key)
            }
        }
    }
    return delta
}

fun createMapIterator(type: AbstractYType): Iterator<Map.Entry<String, Any?>> =
    typeMapGetAll(type).entries.iterator()

fun isVisible(item: Item, snapshot: Snapshot? = null): Boolean {
    if (snapshot == null) return !item.deleted
    val seenClock = snapshot.sv[item.id.client] ?: return false
    return seenClock > item.id.clock && !snapshot.ds.hasId(item.id)
}

private fun yTypeHelperValuesEqual(left: Any?, right: Any?): Boolean = when {
    left === right -> true
    left is ByteArray && right is ByteArray -> left.contentEquals(right)
    left is List<*> && right is List<*> ->
        left.size == right.size && left.indices.all { index -> yTypeHelperValuesEqual(left[index], right[index]) }
    left is Map<*, *> && right is Map<*, *> ->
        left.keys == right.keys && left.keys.all { key -> yTypeHelperValuesEqual(left[key], right[key]) }
    else -> left == right
}

private fun typeListValues(type: AbstractYType): List<Any?> = when (type) {
    is YUnopenedRoot -> error("open root '${type.name}' with a concrete getter first")
    is YArray -> type.toList()
    is YText -> type.toList()
    is YXmlFragment -> type.toList()
    is YXmlElementType -> type.toList()
    is YMap -> error("YMap is not a list type")
}

private fun normalizeTypeListSliceIndex(index: Int, size: Int): Int {
    val normalized = if (index < 0) size + index else index
    return normalized.coerceIn(0, size)
}

private fun insertTextContent(
    parent: YText,
    index: Int,
    content: AbstractContent,
    formats: Map<String, Any?>,
    origin: Any?,
): Int {
    require(content.isCountable()) { "text insert content must be countable" }
    return when (content) {
        is ContentString -> {
            parent.insertText(index, content.str, formats, origin)
            content.str.length
        }
        is ContentEmbed -> {
            parent.insertEmbed(index, content.embed, formats, origin)
            1
        }
        is ContentBinary -> {
            parent.insertEmbed(index, content.content, formats, origin)
            1
        }
        is ContentType -> {
            parent.insertEmbed(index, content.type, formats, origin)
            1
        }
        is ContentDoc -> {
            parent.insertEmbed(index, content.toYDoc(), formats, origin)
            1
        }
        is ContentAny,
        is ContentJSON -> {
            val values = content.getContent()
            parent.doc.transact(origin = origin) {
                parent.insert(index, values, formats)
            }
            values.textInsertLengthForPosition()
        }
        else -> error("unsupported text insert content: ${content::class.simpleName}")
    }
}

private fun ItemTextListPosition.moveTo(parent: YText, index: Int, formats: Map<String, Any?>) {
    formats.forEach { (key, value) ->
        if (value == null) {
            currentFormats.remove(key)
        } else {
            currentFormats[key] = value
        }
    }
    val next = createItemTextListPosition(parent, index, currentFormats, renderer)
    left = next.left
    right = next.right
    this.index = next.index
}

private fun List<Item>.nextAfter(item: Item): Item? {
    val index = indexOfFirst { candidate -> candidate.id == item.id }
    return if (index >= 0) getOrNull(index + 1) else null
}

private fun logicalTypeStructs(type: AbstractYType): List<Item> = getTypeStructs(type).flatMap { item ->
    val text = item.content as? ContentString ?: return@flatMap listOf(item)
    if (text.str.length == 1) return@flatMap listOf(item)
    text.str.mapIndexed { offset, char ->
        item.copy(
            id = Id(item.id.client, checkedClockAdd(item.id.clock, offset.toLong(), "text unit clock")),
            length = 1,
            origin = if (offset == 0) item.origin else Id(item.id.client, item.id.clock + offset - 1),
            content = ContentString(char.toString()),
        )
    }
}

private fun List<*>.textInsertLengthForPosition(): Int =
    sumOf { value -> if (value is String) value.length else 1 }

private fun emitDeepTypeObserverEvents(type: AbstractYType, directEvent: YEvent) {
    if (type.hasDeepObservers) {
        type.emitDeep(
            directEvent.copy(
                currentTarget = type,
                changedTarget = type,
                path = emptyList(),
                deepEvents = listOf(directEvent),
            ),
        )
    }

    var ancestor = type.doc.parentOf(type)
    while (ancestor != null) {
        ancestor.clearCache()
        val path = type.doc.pathBetween(ancestor.name, type.name).orEmpty()
        val nestedEvent = directEvent.copy(
            currentTarget = ancestor,
            changedTarget = type,
            path = path,
        )
        val deepEvent = YEvent(
            target = ancestor,
            origin = directEvent.origin,
            update = directEvent.update,
            insertSet = directEvent.insertSet,
            deleteSet = directEvent.deleteSet,
            transaction = directEvent.transaction,
            currentTarget = ancestor,
            path = path,
            changedTarget = type,
            deepEvents = listOf(nestedEvent),
        )
        if (ancestor.hasDeepObservers) {
            ancestor.emitDeep(deepEvent)
        }
        if (ancestor.hasDeltaListeners) {
            ancestor.emitDelta(deepEvent)
        }
        ancestor = type.doc.parentOf(ancestor)
    }
}
