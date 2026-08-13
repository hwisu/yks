package dev.yks

public const val structGCRefNumber: Int = 0
public const val structSkipRefNumber: Int = 10

public open class AbstractStruct(
    private val initialId: Id,
    private var initialLength: Long,
) {
    init {
        require(initialLength >= 0) { "struct length must be non-negative" }
    }

    public open val id: Id get() = initialId
    public open var length: Long
        get() = initialLength
        set(value) {
            require(value >= 0) { "struct length must be non-negative" }
            initialLength = value
        }
    public open val deleted: Boolean get() = false
    public open val isItem: Boolean get() = false
    public open val ref: Int? get() = null
    public val end: Long get() = checkedClockAdd(id.clock, length, "struct end")

    public open fun mergeWith(right: AbstractStruct): Boolean = false

    public open fun splice(diff: Long): AbstractStruct {
        error("struct cannot be spliced")
    }
}

public class GC(
    override val id: Id,
    override var length: Long,
) : AbstractStruct(id, length) {
    override val deleted: Boolean get() = true
    override val ref: Int get() = structGCRefNumber

    override fun mergeWith(right: AbstractStruct): Boolean {
        if (right !is GC || right.id.client != id.client || right.id.clock != end) return false
        length = checkedClockAdd(length, right.length, "merged GC length")
        return true
    }

    override fun splice(diff: Long): GC {
        require(diff in 1 until length) { "diff must split the struct" }
        val right = GC(Id(id.client, checkedClockAdd(id.clock, diff, "split GC clock")), length - diff)
        length = diff
        return right
    }
}

public class Skip(
    override val id: Id,
    override var length: Long,
) : AbstractStruct(id, length) {
    override val deleted: Boolean get() = true
    override val ref: Int get() = structSkipRefNumber

    override fun mergeWith(right: AbstractStruct): Boolean {
        if (right !is Skip || right.id.client != id.client || right.id.clock != end) return false
        length = checkedClockAdd(length, right.length, "merged Skip length")
        return true
    }

    override fun splice(diff: Long): Skip {
        require(diff in 1 until length) { "diff must split the struct" }
        val right = Skip(Id(id.client, checkedClockAdd(id.clock, diff, "split Skip clock")), length - diff)
        length = diff
        return right
    }
}

public data class ItemStruct(
    override val id: Id,
    override var length: Long,
    override val deleted: Boolean,
    val origin: Id?,
    val rightOrigin: Id?,
    val parent: String,
    val parentSub: String?,
    val kind: RootKind,
    val content: AbstractContent,
    val countable: Boolean = content.isCountable(),
) : AbstractStruct(id, length) {
    @Transient
    internal var ownerDoc: YDoc? = null

    override val isItem: Boolean get() = true

    /** Current structural neighbor, matching Yjs Item.left. */
    public val left: Item? get() = ownerDoc?.itemLinks(id)?.first

    /** Current structural neighbor, matching Yjs Item.right. */
    public val right: Item? get() = ownerDoc?.itemLinks(id)?.second

    /** Previous non-deleted structural neighbor. */
    public val prev: Item? get() = ownerDoc?.visibleItemNeighbor(id, previous = true)

    /** Next non-deleted structural neighbor. */
    public val next: Item? get() = ownerDoc?.visibleItemNeighbor(id, previous = false)

    public val lastId: Id
        get() {
            require(length > 0) { "zero-length malformed Item has no non-negative lastId" }
            return Id(id.client, checkedClockAdd(id.clock, length - 1, "item last id"))
        }

    public val redone: Id? get() = ownerDoc?.directRedone(id)

    public var keep: Boolean
        get() = ownerDoc?.isItemKept(id) == true
        set(value) {
            checkNotNull(ownerDoc) { "detached Item cannot change keep" }.setItemKeep(id, value)
        }

    public val parentType: AbstractYType? get() = ownerDoc?.typeForParent(parent)
}

public typealias Item = ItemStruct

public fun addStructToIdSet(idSet: IdSet, struct: AbstractStruct) {
    idSet.add(struct.id, struct.length)
}

public data class FollowRedoneResult(
    val item: ItemStruct,
    val diff: Long,
)

public fun followRedone(doc: YDoc, id: Id): FollowRedoneResult {
    val followedId = doc.followRedone(id)
    val item = doc.getItem(followedId)?.toItemStruct(doc) ?: error("struct not found: $followedId")
    return FollowRedoneResult(item, followedId.clock - item.id.clock)
}

public fun keepItem(doc: YDoc, item: Item?, keep: Boolean = true): IdSet {
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

public fun findIndexSS(structs: List<AbstractStruct>, clock: Long): Int {
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

public fun findIndexCleanStart(structs: MutableList<AbstractStruct>, clock: Long): Int {
    val index = findIndexSS(structs, clock)
    val struct = structs[index]
    if (struct.id.clock < clock) {
        structs.add(index + 1, splitStruct(struct, clock - struct.id.clock))
        return index + 1
    }
    return index
}

public fun splitStruct(leftStruct: AbstractStruct, diff: Long): AbstractStruct {
    require(diff > 0 && diff < leftStruct.length) { "diff must split the struct" }
    return when (leftStruct) {
        is GC -> leftStruct.splice(diff)
        is Skip -> leftStruct.splice(diff)
        is ItemStruct -> {
            val originalLength = leftStruct.length
            val rightClock = checkedClockAdd(leftStruct.id.clock, diff, "split item clock")
            val rightContent = leftStruct.content.splice(diff)
            leftStruct.length = diff
            leftStruct.copy(
                id = Id(leftStruct.id.client, rightClock),
                length = originalLength - diff,
                origin = Id(leftStruct.id.client, rightClock - 1),
                content = rightContent,
            )
        }
        else -> leftStruct.splice(diff)
    }
}

public fun iterateStructs(
    structs: MutableList<AbstractStruct>,
    clockStart: Long,
    len: Long,
    action: (AbstractStruct) -> Unit,
) {
    require(clockStart >= 0) { "clockStart must be non-negative" }
    require(len >= 0) { "len must be non-negative" }
    if (len == 0L) return
    val clockEnd = checkedClockAdd(clockStart, len, "struct iteration end")
    var index = findIndexCleanStart(structs, clockStart)
    do {
        val struct = structs[index++]
        if (clockEnd < struct.end) {
            findIndexCleanStart(structs, clockEnd)
        }
        action(struct)
    } while (index < structs.size && structs[index].id.clock < clockEnd)
}

public fun iterateStructsWithoutSplits(
    structs: List<AbstractStruct>,
    clockStart: Long,
    len: Long,
    action: (struct: AbstractStruct, offset: Long, length: Long) -> Unit,
) {
    require(clockStart >= 0) { "clockStart must be non-negative" }
    require(len >= 0) { "len must be non-negative" }
    if (len == 0L) return
    val clockEnd = checkedClockAdd(clockStart, len, "struct iteration end")
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

public fun tryToMergeWithLefts(structs: MutableList<AbstractStruct>, pos: Int): Int {
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

public fun replaceStruct(
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

public fun replaceStruct(
    blocks: BlockSet,
    struct: AbstractStruct,
    newStruct: AbstractStruct,
): AbstractStruct {
    val refs = blocks.clients[struct.id.client]?.refs ?: error("struct client is not present in block set")
    return replaceStruct(refs, struct, newStruct)
}

public fun tryMerge(ds: IdSet, blocks: BlockSet): Int {
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

public fun updateCurrentFormats(currentFormats: MutableMap<String, Any?>, format: ContentFormat) {
    if (format.value == null) {
        currentFormats.remove(format.key)
    } else {
        currentFormats[format.key] = format.value
    }
}

public fun createInsertSliceFromStructs(
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
                if (
                    next.id.client != client ||
                    next.id.clock != checkedClockAdd(clock, len, "insert slice end") ||
                    (filterDeleted && next.deleted)
                ) {
                    break
                }
                len = checkedClockAdd(len, next.length, "insert slice length")
                index++
            }
            idItems.add(IdRange(clock, len))
        }
        index++
    }
    return idItems
}

public fun _createInsertSliceFromStructs(
    structs: List<AbstractStruct>,
    filterDeleted: Boolean = false,
): List<IdRange> = createInsertSliceFromStructs(structs, filterDeleted)

public fun nextID(doc: YDoc): Id = doc.nextId()

public fun nextID(transaction: YTransaction): Id = nextID(transaction.doc)

public fun getTypeStructs(type: AbstractYType): List<ItemStruct> =
    type.doc.typeChildren(type).flatMap { item ->
        item.toItemStruct(type.doc).logicalAnyValueViews()
    }

private fun ItemStruct.logicalAnyValueViews(): List<ItemStruct> {
    val values = when (val value = content) {
        is ContentAny -> value.arr.map { element -> ContentAny(listOf(element)) }
        is ContentJSON -> value.arr.map { element -> ContentJSON(listOf(element)) }
        else -> return listOf(this)
    }
    if (values.size <= 1) return listOf(this)
    return values.mapIndexed { offset, value ->
        val clock = checkedClockAdd(id.clock, offset.toLong(), "logical value item clock")
        copy(
            id = Id(id.client, clock),
            length = 1,
            origin = if (offset == 0) origin else Id(id.client, clock - 1),
            content = value,
        )
    }
}

public fun getItem(store: StructStore, id: Id): ItemStruct = store.getItem(id)

public fun getItemCleanStart(doc: YDoc, id: Id): ItemStruct =
    doc.store.getStoreItemCleanStart(id).toItemStruct(doc)

public fun getItemCleanStart(transaction: YTransaction, id: Id): ItemStruct =
    transaction.doc.store
        .getStoreItemCleanStart(id, transaction::registerSplitMergeCandidate)
        .toItemStruct(transaction.doc)

public fun getItemCleanEnd(doc: YDoc, id: Id): ItemStruct =
    doc.store.getStoreItemCleanEnd(id).toItemStruct(doc)

public fun getItemCleanEnd(transaction: YTransaction, id: Id): ItemStruct =
    transaction.doc.store
        .getStoreItemCleanEnd(id, transaction::registerSplitMergeCandidate)
        .toItemStruct(transaction.doc)

public fun getItemCleanEnd(transaction: YTransaction, store: StructStore, id: Id): ItemStruct {
    require(transaction.doc.store === store) { "store must belong to the transaction document" }
    return store
        .getStoreItemCleanEnd(id, transaction::registerSplitMergeCandidate)
        .toItemStruct(transaction.doc)
}

public fun iterateStructsByIdSet(
    doc: YDoc,
    idSet: IdSet,
    action: (struct: ItemStruct, offset: Long, length: Long) -> Unit,
) {
    doc.splitStoreAtIdSetBoundaries(idSet)
    doc.itemsForIdSet(idSet).forEach { item ->
        action(item.toItemStruct(doc), 0, item.length)
    }
}

public fun iterateStructsByIdSet(
    transaction: YTransaction,
    idSet: IdSet,
    action: (struct: ItemStruct, offset: Long, length: Long) -> Unit,
) {
    iterateStructsByIdSet(transaction.doc, idSet, action)
}

public fun iterateStructsByIdSetWithoutSplits(
    doc: YDoc,
    idSet: IdSet,
    action: (struct: ItemStruct, offset: Long, length: Long) -> Unit,
) {
    iterateStructsByIdSetWithoutSplits(doc.store, idSet, action)
}

public fun iterateStructsByIdSetWithoutSplits(
    transaction: YTransaction,
    idSet: IdSet,
    action: (struct: ItemStruct, offset: Long, length: Long) -> Unit,
) {
    iterateStructsByIdSetWithoutSplits(transaction.doc.store, idSet, action)
}

public fun iterateStructsByIdSetWithoutSplits(
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

public fun iterateDeletedStructs(
    doc: YDoc,
    deleteSet: DeleteSet,
    action: (AbstractStruct) -> Unit,
) {
    iterateStructsByIdSet(doc, deleteSet.toIdSet()) { struct, _, _ -> action(struct) }
}

public fun iterateDeletedStructs(
    transaction: YTransaction,
    deleteSet: DeleteSet,
    action: (AbstractStruct) -> Unit,
) {
    iterateDeletedStructs(transaction.doc, deleteSet, action)
}

public fun gcIdSet(
    doc: YDoc,
    idSet: IdSet,
    gcFilter: (AbstractStruct) -> Boolean = doc.gcFilter,
): IdSet {
    var collected = createIdSet()
    doc.transact {
        collected = collectGarbageNow(doc, idSet, gcFilter)
    }
    return collected
}

internal fun collectGarbageNow(
    doc: YDoc,
    idSet: IdSet,
    gcFilter: (AbstractStruct) -> Boolean = doc.gcFilter,
): IdSet {
    val collected = createIdSet()
    iterateStructsByIdSet(doc, idSet) { selected, _, _ ->
        val item = doc.store.getStoreItem(selected.id) ?: return@iterateStructsByIdSet
        val struct = item.toItemStruct(doc)
        if (
            item.deleted &&
            item.content !is ItemContent.Deleted &&
            !doc.isItemKept(item.id) &&
            gcFilter(struct)
        ) {
            collectDeletedItemContent(doc, item, collected)
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
        doc.store.itemsForParent(ref.name)
            .forEach { child -> collectDeletedItemContent(doc, child, collected) }
    }
    if (doc.store.collectItemContent(item.id) != null) {
        collected.add(item.id, item.length)
    }
}

public fun tryGc(
    doc: YDoc,
    idSet: IdSet,
    gcFilter: (AbstractStruct) -> Boolean = doc.gcFilter,
): IdSet = gcIdSet(doc, idSet, gcFilter)

public fun tryGc(
    transaction: YTransaction,
    idSet: IdSet,
    gcFilter: (AbstractStruct) -> Boolean = transaction.doc.gcFilter,
): IdSet = gcIdSet(transaction.doc, idSet, gcFilter)

public fun tryGc(
    deleteSet: DeleteSet,
    store: StructStore,
    gcFilter: (AbstractStruct) -> Boolean,
) {
    if (deleteSet.isEmpty) return
    val doc = store.ownerDoc ?: error("tryGc requires a document-owned StructStore")
    collectGarbageNow(doc, deleteSet.toIdSet(), gcFilter)
    store.mergeDeletedItems(deleteSet)
}

public fun tryGcDeleteSet(
    doc: YDoc,
    idSet: IdSet,
    gcFilter: (AbstractStruct) -> Boolean = doc.gcFilter,
): IdSet = gcIdSet(doc, idSet, gcFilter)

public fun tryGcDeleteSet(
    transaction: YTransaction,
    idSet: IdSet,
    gcFilter: (AbstractStruct) -> Boolean = transaction.doc.gcFilter,
): IdSet = gcIdSet(transaction.doc, idSet, gcFilter)

public fun tryGcDeleteSet(
    doc: YDoc,
    deleteSet: DeleteSet,
    gcFilter: (AbstractStruct) -> Boolean = doc.gcFilter,
): IdSet = tryGcDeleteSet(doc, deleteSet.toIdSet(), gcFilter)

public fun tryGcDeleteSet(
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
        countable = countable,
    ).also { item -> item.ownerDoc = doc }
