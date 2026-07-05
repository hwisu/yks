package dev.yks

const val structGCRefNumber: Int = 0
const val structSkipRefNumber: Int = 10

open class AbstractStruct(
    private val initialId: Id,
    private var initialLength: Long,
) {
    init {
        require(initialLength > 0) { "struct length must be positive" }
    }

    open val id: Id get() = initialId
    open var length: Long
        get() = initialLength
        set(value) {
            require(value > 0) { "struct length must be positive" }
            initialLength = value
        }
    open val deleted: Boolean get() = false
    open val isItem: Boolean get() = false
    open val ref: Int? get() = null
    val end: Long get() = id.clock + length

    open fun mergeWith(right: AbstractStruct): Boolean = false

    open fun splice(diff: Long): AbstractStruct {
        error("struct cannot be spliced")
    }
}

class GC(
    override val id: Id,
    override var length: Long,
) : AbstractStruct(id, length) {
    override val deleted: Boolean get() = true
    override val ref: Int get() = structGCRefNumber

    override fun mergeWith(right: AbstractStruct): Boolean {
        if (right !is GC || right.id.client != id.client || right.id.clock != end) return false
        length += right.length
        return true
    }

    override fun splice(diff: Long): GC {
        require(diff in 1 until length) { "diff must split the struct" }
        val right = GC(Id(id.client, id.clock + diff), length - diff)
        length = diff
        return right
    }
}

class Skip(
    override val id: Id,
    override var length: Long,
) : AbstractStruct(id, length) {
    override val ref: Int get() = structSkipRefNumber

    override fun mergeWith(right: AbstractStruct): Boolean {
        if (right !is Skip || right.id.client != id.client || right.id.clock != end) return false
        length += right.length
        return true
    }

    override fun splice(diff: Long): Skip {
        require(diff in 1 until length) { "diff must split the struct" }
        val right = Skip(Id(id.client, id.clock + diff), length - diff)
        length = diff
        return right
    }
}

data class ItemStruct(
    override val id: Id,
    override var length: Long,
    override val deleted: Boolean,
    val origin: Id?,
    val rightOrigin: Id?,
    val parent: String,
    val parentSub: String?,
    val kind: RootKind,
    val content: AbstractContent,
) : AbstractStruct(id, length) {
    override val isItem: Boolean get() = true
}

typealias Item = ItemStruct

fun addStructToIdSet(idSet: IdSet, struct: AbstractStruct) {
    idSet.add(struct.id, struct.length)
}

data class FollowRedoneResult(
    val item: ItemStruct,
    val diff: Long,
)

fun followRedone(doc: YDoc, id: Id): FollowRedoneResult {
    val followedId = doc.followRedone(id)
    val item = doc.getItem(followedId)?.toItemStruct(doc) ?: error("struct not found: $followedId")
    return FollowRedoneResult(item, followedId.clock - item.id.clock)
}

fun keepItem(doc: YDoc, item: Item?, keep: Boolean = true): IdSet {
    val kept = createIdSet()
    var current = item
    while (current != null) {
        doc.setItemKeep(current.id, keep)
        kept.add(current.id, current.length)
        val parentType = doc.typeForParent(current.parent) ?: break
        val parentItemId = doc.typeRefItemId(parentType) ?: break
        current = doc.getItem(parentItemId)?.toItemStruct(doc)
    }
    return kept
}

fun findIndexSS(structs: List<AbstractStruct>, clock: Long): Int {
    require(clock >= 0) { "clock must be non-negative" }
    require(structs.isNotEmpty()) { "structs must not be empty" }
    var left = 0
    var right = structs.lastIndex
    while (left <= right) {
        val mid = (left + right) ushr 1
        val struct = structs[mid]
        when {
            clock < struct.id.clock -> right = mid - 1
            clock >= struct.end -> left = mid + 1
            else -> return mid
        }
    }
    error("clock $clock is not covered by structs")
}

fun findIndexCleanStart(structs: MutableList<AbstractStruct>, clock: Long): Int {
    val index = findIndexSS(structs, clock)
    val struct = structs[index]
    if (struct.id.clock < clock) {
        structs.add(index + 1, splitStruct(struct, clock - struct.id.clock))
        return index + 1
    }
    return index
}

fun splitStruct(leftStruct: AbstractStruct, diff: Long): AbstractStruct {
    require(diff > 0 && diff < leftStruct.length) { "diff must split the struct" }
    return when (leftStruct) {
        is GC -> leftStruct.splice(diff)
        is Skip -> leftStruct.splice(diff)
        is ItemStruct -> {
            val originalLength = leftStruct.length
            val rightContent = leftStruct.content.splice(diff)
            leftStruct.length = diff
            leftStruct.copy(
                id = Id(leftStruct.id.client, leftStruct.id.clock + diff),
                length = originalLength - diff,
                origin = Id(leftStruct.id.client, leftStruct.id.clock + diff - 1),
                content = rightContent,
            )
        }
        else -> leftStruct.splice(diff)
    }
}

fun iterateStructs(
    structs: MutableList<AbstractStruct>,
    clockStart: Long,
    len: Long,
    action: (AbstractStruct) -> Unit,
) {
    require(len >= 0) { "len must be non-negative" }
    if (len == 0L) return
    val clockEnd = clockStart + len
    var index = findIndexCleanStart(structs, clockStart)
    do {
        val struct = structs[index++]
        if (clockEnd < struct.end) {
            findIndexCleanStart(structs, clockEnd)
        }
        action(struct)
    } while (index < structs.size && structs[index].id.clock < clockEnd)
}

fun iterateStructsWithoutSplits(
    structs: List<AbstractStruct>,
    clockStart: Long,
    len: Long,
    action: (struct: AbstractStruct, offset: Long, length: Long) -> Unit,
) {
    require(len >= 0) { "len must be non-negative" }
    if (len == 0L) return
    val clockEnd = clockStart + len
    var index = findIndexSS(structs, clockStart)
    while (index < structs.size) {
        val struct = structs[index]
        if (struct.id.clock >= clockEnd) break
        val offset = if (struct.id.clock < clockStart) clockStart - struct.id.clock else 0
        val covered = minOf(struct.end, clockEnd) - struct.id.clock
        action(struct, offset, covered)
        if (index + 1 == structs.size) break
        index++
    }
}

fun tryToMergeWithLefts(structs: MutableList<AbstractStruct>, pos: Int): Int {
    require(pos in 0 until structs.size) { "pos is out of bounds" }
    var i = pos
    while (i > 0) {
        val right = structs[i]
        val left = structs[i - 1]
        if (left.deleted != right.deleted || left::class != right::class || !left.mergeWith(right)) {
            break
        }
        structs.removeAt(i)
        i--
    }
    return pos - i
}

fun replaceStruct(
    structs: MutableList<AbstractStruct>,
    struct: AbstractStruct,
    newStruct: AbstractStruct,
): AbstractStruct {
    require(newStruct.id == struct.id) { "new struct must keep the replaced struct id" }
    require(newStruct.length == struct.length) { "new struct must keep the replaced struct length" }
    val index = findIndexSS(structs, struct.id.clock)
    require(structs[index].id == struct.id) { "replacement must start at an existing struct boundary" }
    structs[index] = newStruct
    return newStruct
}

fun replaceStruct(
    blocks: BlockSet,
    struct: AbstractStruct,
    newStruct: AbstractStruct,
): AbstractStruct {
    val refs = blocks.clients[struct.id.client]?.refs ?: error("struct client is not present in block set")
    return replaceStruct(refs, struct, newStruct)
}

fun tryMerge(ds: IdSet, blocks: BlockSet): Int {
    var merged = 0
    ds.clients.forEach { (client, ranges) ->
        val structs = blocks.clients[client]?.refs ?: return@forEach
        ranges.asReversed().forEach { range ->
            if (range.len == 0L || structs.size < 2) return@forEach
            val first = structs.firstOrNull() ?: return@forEach
            val last = structs.last()
            if (range.end <= first.id.clock || range.clock >= last.end) return@forEach
            val lastDeletedClock = minOf(range.end - 1, last.end - 1)
            var index = minOf(structs.lastIndex, findIndexSS(structs, lastDeletedClock) + 1)
            while (index > 0 && index < structs.size && structs[index].id.clock >= range.clock) {
                val mergedWithLeft = tryToMergeWithLefts(structs, index)
                merged += mergedWithLeft
                index -= 1 + mergedWithLeft
            }
        }
    }
    return merged
}

fun updateCurrentFormats(currentFormats: MutableMap<String, Any?>, format: ContentFormat) {
    if (format.value == null) {
        currentFormats.remove(format.key)
    } else {
        currentFormats[format.key] = format.value
    }
}

fun createInsertSliceFromStructs(
    structs: List<AbstractStruct>,
    filterDeleted: Boolean = false,
): List<IdRange> {
    val idItems = mutableListOf<IdRange>()
    var index = 0
    while (index < structs.size) {
        val struct = structs[index]
        if (!(filterDeleted && struct.deleted)) {
            val client = struct.id.client
            val clock = struct.id.clock
            var len = struct.length
            while (index + 1 < structs.size) {
                val next = structs[index + 1]
                if (next.id.client != client || next.id.clock != clock + len || (filterDeleted && next.deleted)) {
                    break
                }
                len += next.length
                index++
            }
            idItems.add(IdRange(clock, len))
        }
        index++
    }
    return idItems
}

fun _createInsertSliceFromStructs(
    structs: List<AbstractStruct>,
    filterDeleted: Boolean = false,
): List<IdRange> = createInsertSliceFromStructs(structs, filterDeleted)

fun nextID(doc: YDoc): Id = doc.nextId()

fun nextID(transaction: YTransaction): Id = nextID(transaction.doc)

fun getTypeStructs(type: AbstractYType): List<ItemStruct> =
    type.doc.typeChildren(type).map { item -> item.toItemStruct(type.doc) }

fun getItemCleanStart(doc: YDoc, id: Id): ItemStruct =
    doc.getItem(id)?.toItemStruct(doc) ?: error("struct not found: $id")

fun getItemCleanStart(transaction: YTransaction, id: Id): ItemStruct =
    getItemCleanStart(transaction.doc, id)

fun getItemCleanEnd(doc: YDoc, id: Id): ItemStruct = getItemCleanStart(doc, id)

fun getItemCleanEnd(transaction: YTransaction, id: Id): ItemStruct =
    getItemCleanEnd(transaction.doc, id)

fun getItemCleanEnd(transaction: YTransaction, store: StructStore, id: Id): ItemStruct {
    require(transaction.doc.store === store) { "store must belong to the transaction document" }
    return getItemCleanEnd(transaction.doc, id)
}

fun iterateStructsByIdSet(
    doc: YDoc,
    idSet: IdSet,
    action: (struct: ItemStruct, offset: Long, length: Long) -> Unit,
) {
    idSet.ranges().forEach { (client, range) ->
        var clock = range.clock
        while (clock < range.end) {
            doc.getItem(Id(client, clock))?.let { item ->
                action(item.toItemStruct(doc), 0, item.length)
            }
            clock++
        }
    }
}

fun iterateStructsByIdSet(
    transaction: YTransaction,
    idSet: IdSet,
    action: (struct: ItemStruct, offset: Long, length: Long) -> Unit,
) {
    iterateStructsByIdSet(transaction.doc, idSet, action)
}

fun iterateStructsByIdSetWithoutSplits(
    doc: YDoc,
    idSet: IdSet,
    action: (struct: ItemStruct, offset: Long, length: Long) -> Unit,
) {
    iterateStructsByIdSetWithoutSplits(doc.store, idSet, action)
}

fun iterateStructsByIdSetWithoutSplits(
    transaction: YTransaction,
    idSet: IdSet,
    action: (struct: ItemStruct, offset: Long, length: Long) -> Unit,
) {
    iterateStructsByIdSetWithoutSplits(transaction.doc.store, idSet, action)
}

fun iterateStructsByIdSetWithoutSplits(
    store: StructStore,
    idSet: IdSet,
    action: (struct: ItemStruct, offset: Long, length: Long) -> Unit,
) {
    idSet.ranges().forEach { (client, range) ->
        val structs = store.clients[client].orEmpty()
        if (structs.isEmpty()) return@forEach
        val nextClock = structs.last().end
        if (range.clock >= nextClock) return@forEach
        iterateStructsWithoutSplits(structs, range.clock, minOf(range.len, nextClock - range.clock)) { struct, offset, length ->
            action(struct as ItemStruct, offset, length)
        }
    }
}

fun gcIdSet(
    doc: YDoc,
    idSet: IdSet,
    gcFilter: (AbstractStruct) -> Boolean = doc.gcFilter,
): IdSet {
    val collected = createIdSet()
    doc.transact {
        iterateStructsByIdSet(doc, idSet) { struct, _, _ ->
            if (
                struct.deleted &&
                struct.content !is ContentDeleted &&
                !doc.isItemKept(struct.id) &&
                gcFilter(struct)
            ) {
                doc.store.getStoreItem(struct.id)?.let { item ->
                    collectDeletedItemContent(doc, item, collected)
                }
            }
        }
    }
    return collected
}

private fun collectDeletedItemContent(
    doc: YDoc,
    item: StoreItem,
    collected: IdSet,
) {
    if (!item.deleted || item.content is ItemContent.Deleted) return
    item.content.directTypeRef()?.let { ref ->
        doc.store.allItems()
            .filter { child -> child.parent == ref.name }
            .forEach { child -> collectDeletedItemContent(doc, child, collected) }
    }
    if (doc.store.collectItemContent(item.id) != null) {
        collected.add(item.id, item.length)
    }
}

fun tryGc(
    doc: YDoc,
    idSet: IdSet,
    gcFilter: (AbstractStruct) -> Boolean = doc.gcFilter,
): IdSet = gcIdSet(doc, idSet, gcFilter)

fun tryGc(
    transaction: YTransaction,
    idSet: IdSet,
    gcFilter: (AbstractStruct) -> Boolean = transaction.doc.gcFilter,
): IdSet = gcIdSet(transaction.doc, idSet, gcFilter)

fun tryGcDeleteSet(
    doc: YDoc,
    idSet: IdSet,
    gcFilter: (AbstractStruct) -> Boolean = doc.gcFilter,
): IdSet = gcIdSet(doc, idSet, gcFilter)

fun tryGcDeleteSet(
    transaction: YTransaction,
    idSet: IdSet,
    gcFilter: (AbstractStruct) -> Boolean = transaction.doc.gcFilter,
): IdSet = gcIdSet(transaction.doc, idSet, gcFilter)

fun tryGcDeleteSet(
    doc: YDoc,
    deleteSet: DeleteSet,
    gcFilter: (AbstractStruct) -> Boolean = doc.gcFilter,
): IdSet = tryGcDeleteSet(doc, deleteSet.toIdSet(), gcFilter)

fun tryGcDeleteSet(
    transaction: YTransaction,
    deleteSet: DeleteSet,
    gcFilter: (AbstractStruct) -> Boolean = transaction.doc.gcFilter,
): IdSet = tryGcDeleteSet(transaction, deleteSet.toIdSet(), gcFilter)

internal fun StoreItem.toItemStruct(doc: YDoc): ItemStruct =
    ItemStruct(
        id = id,
        length = length,
        deleted = deleted,
        origin = origin,
        rightOrigin = rightOrigin,
        parent = parent,
        parentSub = parentSub,
        kind = content.kind,
        content = content.toContent(doc),
    )
