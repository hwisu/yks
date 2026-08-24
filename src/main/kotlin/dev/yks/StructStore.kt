package dev.yks

public data class StructStoreIndex(
    val structs: List<AbstractStruct>,
    val index: Int,
)

public data class PendingStructs(
    val missing: Map<Long, Long>,
    val update: ByteArray,
)

/**
 * Mutation-aware linked sequence cursor.
 *
 * The closures intentionally hide [IndexedSequence]'s private node type while retaining a direct
 * node pointer between advances. A seek is needed only after inserting a new item; ordinary scans
 * follow right links without repeated ID hash lookups.
 */
internal class SequenceCursor internal constructor(
    private val currentProvider: () -> StoreItem?,
    private val previousProvider: () -> StoreItem?,
    private val advanceAction: () -> Unit,
    private val advanceToNextUndeletedAction: () -> Unit,
    private val seekAction: (StoreItem) -> Unit,
) {
    val current: StoreItem? get() = currentProvider()
    val previous: StoreItem? get() = previousProvider()

    fun advance() {
        advanceAction()
    }

    fun advanceToNextUndeleted() {
        advanceToNextUndeletedAction()
    }

    fun seek(item: StoreItem) {
        seekAction(item)
    }
}

internal data class StructStoreSnapshot(
    val originalClientItems: MutableMap<Long, List<StoreItem>> = linkedMapOf(),
    val originalDeletedStates: MutableMap<StoreItem, Boolean> = java.util.IdentityHashMap(),
    val skips: IdSet,
    val version: Long,
    val sequenceBuildCount: Int,
)

/**
 * Parent-local structural membership with a zero-node append path.
 *
 * Remote Yjs updates are normally append-only here and already have authoritative ID lookup in the
 * client store. A LinkedHashMap duplicated one hash entry per struct. Build the positional ID map
 * only when a split/merge actually needs removal, and use tombstones so subsequent edits stay O(1).
 */
private class ParentItemIndex {
    private val items = arrayListOf<StoreItem?>()
    private var positions: MutableMap<Id, Int>? = null
    private var liveSize = 0
    private var tombstones = 0

    val isNotEmpty: Boolean get() = liveSize != 0

    fun add(item: StoreItem) {
        val indexed = positions
        if (indexed != null) {
            indexed[item.id]?.let { index ->
                if (items[index] == null) {
                    liveSize++
                    tombstones--
                }
                items[index] = item
                return
            }
        }
        items.add(item)
        indexed?.set(item.id, items.lastIndex)
        liveSize++
    }

    fun remove(id: Id) {
        val indexed = positions ?: buildPositions().also { positions = it }
        val index = indexed.remove(id) ?: return
        if (items[index] != null) {
            items[index] = null
            liveSize--
            tombstones++
        }
        compactIfSparse()
    }

    fun replaceAll(replacements: Map<Id, StoreItem>) {
        if (replacements.isEmpty()) return
        val indexed = positions
        if (indexed == null) {
            items.indices.forEach { index ->
                val item = items[index] ?: return@forEach
                replacements[item.id]?.let { replacement -> items[index] = replacement }
            }
        } else {
            replacements.forEach { (id, replacement) ->
                indexed[id]?.let { index -> items[index] = replacement }
            }
        }
    }

    fun toList(): List<StoreItem> = items.filterNotNull()

    fun visibleSequenceItems(): List<StoreItem> =
        items.mapNotNull { item ->
            item?.takeIf { candidate ->
                candidate.parentSub == null && !candidate.deleted && candidate.countable
            }
        }

    fun first(sequenceOnly: Boolean): StoreItem? =
        items.firstNotNullOfOrNull { item ->
            item?.takeIf { candidate -> !sequenceOnly || candidate.parentSub == null }
        }

    private fun buildPositions(): MutableMap<Id, Int> = HashMap<Id, Int>(items.size * 4 / 3 + 1).also { result ->
        items.forEachIndexed { index, item -> item?.let { result[it.id] = index } }
    }

    private fun compactIfSparse() {
        if (tombstones < 64 || tombstones * 2 < items.size) return
        val compacted = items.filterNotNull()
        items.clear()
        items.addAll(compacted)
        tombstones = 0
        positions = buildPositions()
    }
}

/**
 * Two-level (parent, key) table.
 *
 * A `Pair` key allocated on every probe and rehashed both strings; nesting reuses one parent
 * lookup for all of its keys and lets a parent-wide invalidation drop a single entry. Parents
 * with one key stay in a compact singleton bucket and only allocate an inner map on the second
 * key, avoiding one `HashMap` per nested single-key map. Map-heavy documents probe these caches
 * once per struct, so the composite key showed up as `Pair.hashCode` and `Pair.equals` at the top
 * of integration profiles.
 */
private class ParentKeyTable<V> {
    private class Bucket<V>(
        var singleKey: String,
        var singleValue: V,
        var multiple: HashMap<String, V>? = null,
    )

    private val byParent = HashMap<String, Bucket<V>>()

    val isEmpty: Boolean get() = byParent.isEmpty()

    operator fun get(parent: String, key: String): V? {
        val bucket = byParent[parent] ?: return null
        val multiple = bucket.multiple
        return if (multiple == null) {
            bucket.singleValue.takeIf { bucket.singleKey == key }
        } else {
            multiple[key]
        }
    }

    fun contains(parent: String, key: String): Boolean {
        val bucket = byParent[parent] ?: return false
        return bucket.multiple?.containsKey(key) ?: (bucket.singleKey == key)
    }

    fun put(parent: String, key: String, value: V) {
        val bucket = byParent[parent]
        if (bucket == null) {
            byParent[parent] = Bucket(key, value)
            return
        }
        val multiple = bucket.multiple
        when {
            multiple != null -> multiple[key] = value
            bucket.singleKey == key -> bucket.singleValue = value
            else -> bucket.multiple = HashMap<String, V>(4).also { values ->
                values[bucket.singleKey] = bucket.singleValue
                values[key] = value
            }
        }
    }

    fun remove(parent: String, key: String) {
        val bucket = byParent[parent] ?: return
        val multiple = bucket.multiple
        if (multiple == null) {
            if (bucket.singleKey == key) byParent.remove(parent)
            return
        }
        multiple.remove(key)
        when (multiple.size) {
            0 -> byParent.remove(parent)
            1 -> {
                val remaining = multiple.entries.first()
                bucket.singleKey = remaining.key
                bucket.singleValue = remaining.value
                bucket.multiple = null
            }
        }
    }

    fun clear() {
        byParent.clear()
    }

    /** Only valid for tables whose values are never null; [mapCurrentCache] uses [contains] instead. */
    inline fun getOrPut(parent: String, key: String, compute: () -> V & Any): V & Any =
        get(parent, key) ?: compute().also { value -> put(parent, key, value) }
}

public class StructStore(private val owner: YDoc? = null) {
    private val clientItems: MutableMap<Long, MutableList<StoreItem>> = linkedMapOf()
    private val structViewOwner: YDoc by lazy(LazyThreadSafetyMode.NONE) { owner ?: YDoc() }
    private var allItemsCache: List<StoreItem>? = null
    private var deleteSetCache: DeleteSet? = null
    private var parentItemIdsCache: Map<String, Id>? = null
    private val itemsByParent: MutableMap<String, ParentItemIndex> = mutableMapOf()
    private val mapItemsByParentKey = ParentKeyTable<LinkedHashMap<Id, StoreItem>>()
    private val mapKeysByParent: MutableMap<String, LinkedHashSet<String>> = mutableMapOf()
    // Standard Yjs ContentType names are derived from the owner ID and therefore have exactly one
    // owner. Keep that overwhelmingly common case as one map entry instead of allocating a
    // LinkedHashMap plus entry per nested type. The secondary table preserves deterministic
    // behavior for private lossless inputs that happen to contain duplicate owner names.
    private val nestedOwnerByName: MutableMap<String, StoreItem> = mutableMapOf()
    private val additionalNestedOwnersByName:
        MutableMap<String, MutableMap<Id, StoreItem>> = mutableMapOf()
    private val sequenceCache: MutableMap<String, IndexedSequence> = mutableMapOf()
    private val mapEntriesCache = ParentKeyTable<List<StoreItem>>()
    private val mapOrderCache = ParentKeyTable<List<StoreItem>>()
    private val mapOrderPositions = ParentKeyTable<Map<Id, Int>>()
    // A cached absence is a real answer here, so reads probe membership rather than nullness.
    private val mapCurrentCache = ParentKeyTable<StoreItem?>()
    private val visibleLengths: MutableMap<String, LongArray> = mutableMapOf()
    private val visibleNativeTextFormats: MutableMap<String, MutableSet<Id>> = mutableMapOf()
    private val visibleLegacyTextFormats: MutableMap<String, MutableSet<Id>> = mutableMapOf()
    private val renderedTextAttributes: MutableMap<String, Map<Id, Map<String, YValue>>> = mutableMapOf()
    private val visibleTextCache: MutableMap<String, Array<String?>> = mutableMapOf()
    private var lookupHintClient: Long = -1
    private var lookupHintIndex: Int = -1
    private var activeSnapshot: StructStoreSnapshot? = null
    private var stateVectorCacheVersion: Long = Long.MIN_VALUE
    private var stateVectorCache: StateVector = emptyMap()

    internal val ownerDoc: YDoc? get() = owner
    internal var version: Long = 0
        private set
    internal var sequenceBuildCount: Int = 0
        private set

    public val clients: Map<Long, List<ItemStruct>>
        get() {
            owner?.ensureThreadAccess()
            val viewOwner = structViewOwner
            return clientItems.mapValues { (_, structs) ->
                structs.map { item -> item.toItemStruct(viewOwner) }
            }
        }

    public val ds: IdSet
        get() {
            owner?.ensureThreadAccess()
            return deleteSet().toIdSet()
        }

    public var pendingStructs: PendingStructs?
        get() {
            owner?.ensureThreadAccess()
            return owner?.pendingStructsView()
        }
        set(value) {
            owner?.ensureThreadAccess()
            owner?.setPendingStructsView(value)
        }

    public var pendingDs: ByteArray?
        get() {
            owner?.ensureThreadAccess()
            return owner?.pendingDeleteSetUpdate()
        }
        set(value) {
            owner?.ensureThreadAccess()
            owner?.setPendingDeleteSetUpdate(value)
        }

    public val skips: IdSet = createIdSet()

    internal fun captureSnapshot(): StructStoreSnapshot {
        check(activeSnapshot == null) { "a StructStore rollback checkpoint is already active" }
        return StructStoreSnapshot(
            skips = skips.copy(),
            version = version,
            sequenceBuildCount = sequenceBuildCount,
        ).also { snapshot -> activeSnapshot = snapshot }
    }

    internal fun releaseSnapshot(snapshot: StructStoreSnapshot) {
        check(activeSnapshot === snapshot) { "StructStore rollback checkpoint is not active" }
        activeSnapshot = null
    }

    internal fun restoreSnapshot(snapshot: StructStoreSnapshot) {
        check(activeSnapshot === snapshot) { "StructStore rollback checkpoint is not active" }
        activeSnapshot = null
        snapshot.originalDeletedStates.forEach { (item, deleted) -> item.deleted = deleted }
        snapshot.originalClientItems.forEach { (client, items) ->
            if (items.isEmpty()) {
                clientItems.remove(client)
            } else {
                clientItems[client] = items.toMutableList()
            }
        }
        skips.replaceWith(snapshot.skips)
        allItemsCache = null
        deleteSetCache = null
        parentItemIdsCache = null
        itemsByParent.clear()
        mapItemsByParentKey.clear()
        mapKeysByParent.clear()
        nestedOwnerByName.clear()
        additionalNestedOwnersByName.clear()
        sequenceCache.clear()
        mapEntriesCache.clear()
        mapOrderCache.clear()
        mapOrderPositions.clear()
        mapCurrentCache.clear()
        visibleLengths.clear()
        visibleNativeTextFormats.clear()
        visibleLegacyTextFormats.clear()
        renderedTextAttributes.clear()
        visibleTextCache.clear()
        clearLookupHint()
        clientItems.values.forEach { items ->
            items.forEach { item ->
                addStructuralIndex(item)
                addDerivedState(item)
            }
        }
        version = snapshot.version
        sequenceBuildCount = snapshot.sequenceBuildCount
    }

    private fun captureClientBeforeMutation(client: Long) {
        activeSnapshot?.originalClientItems?.putIfAbsent(client, clientItems[client]?.toList().orEmpty())
    }

    private fun captureDeletedBeforeMutation(item: StoreItem) {
        activeSnapshot?.originalDeletedStates?.putIfAbsent(item, item.deleted)
    }

    private fun findContainingIndex(client: Long, clock: Long, structs: List<StoreItem>): Int {
        if (lookupHintClient == client && lookupHintIndex >= 0 && lookupHintIndex < structs.size) {
            val index = lookupHintIndex
            val hinted = structs[index]
            if (clock >= hinted.id.clock && clock < hinted.endClock()) return index

            val adjacentIndex = if (clock >= hinted.endClock()) index + 1 else index - 1
            if (adjacentIndex >= 0 && adjacentIndex < structs.size) {
                val adjacent = structs[adjacentIndex]
                if (clock >= adjacent.id.clock && clock < adjacent.endClock()) {
                    lookupHintIndex = adjacentIndex
                    return adjacentIndex
                }
            }
        }

        return structs.findContainingIndex(clock).also { index ->
            if (index >= 0) {
                lookupHintClient = client
                lookupHintIndex = index
            }
        }
    }

    private fun clearLookupHint(client: Long? = null) {
        if (client == null || lookupHintClient == client) {
            lookupHintClient = -1
            lookupHintIndex = -1
        }
    }

    internal fun add(item: StoreItem): Boolean {
        owner?.ensureThreadAccess()
        captureClientBeforeMutation(item.id.client)
        val structs = clientItems.getOrPut(item.id.client) { mutableListOf() }
        if (structs.isEmpty() || structs.last().endClock() <= item.id.clock) {
            structs.add(item)
            recordAdded(item)
            return true
        }
        val itemEnd = item.endClock()
        var low = 0
        var high = structs.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (structs[middle].id.clock < item.id.clock) low = middle + 1 else high = middle
        }
        val insertionIndex = low
        val left = structs.getOrNull(insertionIndex - 1)
        val right = structs.getOrNull(insertionIndex)
        if (
            (left != null && item.id.clock < left.endClock()) ||
            (right != null && right.id.clock < itemEnd)
        ) return false
        clearLookupHint(item.id.client)
        structs.add(insertionIndex, item)
        recordAdded(item)
        return true
    }

    public fun get(id: Id): AbstractStruct {
        owner?.ensureThreadAccess()
        val item = getStoreItem(id) ?: error("struct not found: $id")
        return item.toItemStruct(structViewOwner)
    }

    public fun getItem(id: Id): ItemStruct = get(id) as ItemStruct

    public fun getIndex(id: Id): StructStoreIndex {
        owner?.ensureThreadAccess()
        val storeItems = clientItems[id.client].orEmpty()
        require(storeItems.isNotEmpty()) { "structs must not be empty" }
        val index = findContainingIndex(id.client, id.clock, storeItems)
        if (index < 0) error("clock ${id.clock} is not covered by structs")
        val viewOwner = structViewOwner
        return StructStoreIndex(storeItems.map { item -> item.toItemStruct(viewOwner) }, index)
    }

    internal fun getStoreItem(id: Id): StoreItem? =
        clientItems[id.client]?.let { structs ->
            structs.getOrNull(findContainingIndex(id.client, id.clock, structs))
        }

    internal fun getStoreItemCleanStart(
        id: Id,
        onSplit: (StoreItem) -> Unit = {},
    ): StoreItem {
        val structs = clientItems[id.client] ?: error("struct not found: $id")
        val index = findContainingIndex(id.client, id.clock, structs)
        if (index < 0) error("struct not found: $id")
        val item = structs[index]
        if (item.id.clock == id.clock || item.isGc) return item
        return splitStoreItem(structs, index, id.clock - item.id.clock).also(onSplit)
    }

    internal fun getStoreItemCleanEnd(
        id: Id,
        onSplit: (StoreItem) -> Unit = {},
    ): StoreItem {
        val structs = clientItems[id.client] ?: error("struct not found: $id")
        val index = findContainingIndex(id.client, id.clock, structs)
        if (index < 0) error("struct not found: $id")
        val item = structs[index]
        val splitOffset = id.clock - item.id.clock + 1
        if (splitOffset == item.length || item.isGc) return item
        splitStoreItem(structs, index, splitOffset).also(onSplit)
        return structs[index]
    }

    private fun splitStoreItem(
        structs: MutableList<StoreItem>,
        index: Int,
        diff: Long,
    ): StoreItem {
        val item = structs[index]
        captureClientBeforeMutation(item.id.client)
        clearLookupHint(item.id.client)
        require(diff in 1 until item.length) { "diff must split the store item" }
        val (leftContent, rightContent) = when (val content = item.content) {
            is ItemContent.Deleted ->
                content.copy(length = diff) to content.copy(length = item.length - diff)
            is ItemContent.Text -> {
                val splitIndex = diff.toNonNegativeInt("text split offset")
                var leftValue = content.value.substring(0, splitIndex)
                var rightValue = content.value.substring(splitIndex)
                if (leftValue.last().isHighSurrogate()) {
                    leftValue = leftValue.dropLast(1) + '\uFFFD'
                    rightValue = "\uFFFD" + rightValue.drop(1)
                }
                content.copy(value = leftValue) to content.copy(value = rightValue)
            }
            is ItemContent.MapEntries -> {
                val splitIndex = diff.toNonNegativeInt("packed map split offset")
                fun mapContent(values: List<YValue>): ItemContent =
                    if (values.size == 1) ItemContent.MapEntry(values.single()) else content.copy(values = values)
                mapContent(content.values.subList(0, splitIndex).toList()) to
                    mapContent(content.values.subList(splitIndex, content.values.size).toList())
            }
            is ItemContent.ArrayValues -> {
                val splitIndex = diff.toNonNegativeInt("packed array split offset")
                fun arrayContent(values: List<YValue>): ItemContent =
                    if (values.size == 1) ItemContent.Value(values.single()) else content.copy(values = values)
                arrayContent(content.values.subList(0, splitIndex).toList()) to
                    arrayContent(content.values.subList(splitIndex, content.values.size).toList())
            }
            else -> error("only packed text, values, or deleted store items can require splitting")
        }
        val left = item.copy(content = leftContent)
        val right = item.copy(
            id = Id(item.id.client, checkedClockAdd(item.id.clock, diff, "split item clock")),
            origin = Id(item.id.client, checkedClockAdd(item.id.clock, diff - 1, "split item origin")),
            content = rightContent,
        )
        structs[index] = left
        structs.add(index + 1, right)
        recordReplacement(item, listOf(left, right))
        return right
    }

    internal fun splitAtDeleteSetBoundaries(
        deleteSet: DeleteSet,
        onSplit: (StoreItem) -> Unit = {},
    ) {
        deleteSet.clients.forEach { (client, ranges) ->
            ranges.forEach { range ->
                getStoreItem(Id(client, range.clock))
                    ?.takeIf { item -> !item.isGc && item.id.clock < range.clock }
                    ?.let { getStoreItemCleanStart(Id(client, range.clock), onSplit) }
                val lastClock = range.end - 1
                getStoreItem(Id(client, lastClock))
                    ?.takeIf { item -> !item.isGc && lastClock < item.endClock() - 1 }
                    ?.let { getStoreItemCleanEnd(Id(client, lastClock), onSplit) }
            }
        }
    }

    /** Merge compatible deleted-item fragments touched by a delete-set, from right to left. */
    internal fun mergeDeletedItems(deleteSet: DeleteSet): Int {
        var merged = 0
        deleteSet.clients.forEach { (client, ranges) ->
            val structs = clientItems[client] ?: return@forEach
            if (structs.size < 2 || ranges.isEmpty()) return@forEach
            ranges.asReversed().forEach { range ->
                var index = structs.findFirstStartingAtOrAfter(range.end)
                if (index >= structs.size || structs[index].id.clock > range.end) index--
                while (index > 0 && structs[index].id.clock >= range.clock) {
                    if (mergeDeletedItemWithLeft(structs, index)) merged++
                    index--
                }
            }
        }
        return merged
    }

    /** Re-merge temporary transaction splits in reverse split order, as upstream Yjs does. */
    internal fun mergeSplitCandidates(candidates: List<Id>): Int {
        var merged = 0
        for (candidateIndex in candidates.lastIndex downTo 0) {
            val candidate = candidates[candidateIndex]
            val structs = clientItems[candidate.client] ?: continue
            val index = structs.findStartIndex(candidate.clock)
            if (index > 0 && mergeDeletedItemWithLeft(structs, index)) {
                merged++
            }
        }
        return merged
    }

    private fun mergeDeletedItemWithLeft(
        structs: MutableList<StoreItem>,
        rightIndex: Int,
    ): Boolean {
        if (rightIndex !in 1 until structs.size) return false
        val left = structs[rightIndex - 1]
        val right = structs[rightIndex]
        val leftContent = left.content as? ItemContent.Deleted ?: return false
        val rightContent = right.content as? ItemContent.Deleted ?: return false
        val logicallyAdjacent = if (left.parentSub == null) {
            areSequenceAdjacent(left, right)
        } else if (
            left.id.client == right.id.client &&
            left.endClock() == right.id.clock &&
            right.origin == left.lastId &&
            left.parent == right.parent &&
            left.parentSub == right.parentSub
        ) {
            // A same-client origin chain has an unambiguous map order. Avoid rebuilding the full
            // history for the key on every local replacement; concurrent chains retain the
            // general CRDT order check below.
            true
        } else {
            val logicalOrder = owner?.mapItemOrder(left.parent, left.parentSub) ?: return false
            val logicalLeftIndex = cachedMapItemIndex(
                left.parent,
                left.parentSub,
                left.id,
            ) { logicalOrder }
            logicalLeftIndex >= 0 && logicalOrder.getOrNull(logicalLeftIndex + 1)?.id == right.id
        }
        if (
            left.id.client != right.id.client ||
            left.endClock() != right.id.clock ||
            !logicallyAdjacent ||
            right.origin != Id(left.id.client, right.id.clock - 1) ||
            left.rightOrigin != right.rightOrigin ||
            left.deleted != right.deleted ||
            left.isGc != right.isGc ||
            leftContent.kind != rightContent.kind ||
            left.parent != right.parent ||
            left.parentSub != right.parentSub ||
            left.requiresClockContinuity != right.requiresClockContinuity ||
            left.unresolvedParent != right.unresolvedParent ||
            left.countable != right.countable
        ) return false

        captureClientBeforeMutation(left.id.client)
        clearLookupHint(left.id.client)
        val mergedLength = checkedClockAdd(left.length, right.length, "merged deleted item length")
        val merged = left.copy(content = leftContent.copy(length = mergedLength))
        structs[rightIndex - 1] = merged
        structs.removeAt(rightIndex)
        recordReplacement(left, listOf(merged), additionallyRemoved = listOf(right))
        return true
    }

    internal fun contains(id: Id): Boolean = getStoreItem(id) != null

    internal fun collectItemContent(id: Id): StoreItem? {
        val structs = clientItems[id.client] ?: return null
        val index = findContainingIndex(id.client, id.clock, structs)
        if (index < 0) return null
        val item = structs[index]
        if (!item.deleted) return null
        if (item.content is ItemContent.Deleted) return null
        captureClientBeforeMutation(id.client)
        val collected = item.copy(content = ItemContent.Deleted(item.content.kind, item.length))
        structs[index] = collected
        recordReplacement(item, listOf(collected))
        return collected
    }

    internal fun replaceContent(id: Id, content: ItemContent): StoreItem? {
        val structs = clientItems[id.client] ?: return null
        val index = structs.findStartIndex(id.clock)
        if (index < 0) return null
        captureClientBeforeMutation(id.client)
        val previous = structs[index]
        val updated = previous.copy(content = content)
        structs[index] = updated
        recordReplacement(previous, listOf(updated))
        return updated
    }

    /**
     * Replaces content whose structural shape is unchanged.
     *
     * Native Yjs formatting changes only rendered attributes. Updating these in one pass avoids
     * detaching and reinserting every sequence node while preserving transaction rollback state.
     */
    internal fun replaceEquivalentContents(contents: Map<Id, ItemContent>): List<StoreItem> {
        if (contents.isEmpty()) return emptyList()
        val updatedItems = linkedMapOf<Id, StoreItem>()
        val affectedParents = linkedSetOf<String>()
        val affectedMapKeys = linkedSetOf<Pair<String, String>>()
        contents.keys.mapTo(linkedSetOf()) { id -> id.client }.forEach clientLoop@{ client ->
            val structs = clientItems[client] ?: return@clientLoop
            captureClientBeforeMutation(client)
            structs.indices.forEach structLoop@{ index ->
                val previous = structs[index]
                val nextContent = contents[previous.id] ?: return@structLoop
                if (previous.content == nextContent) return@structLoop
                require(previous.content.kind == nextContent.kind) {
                    "equivalent content replacement cannot change kind"
                }
                require(previous.length == nextContent.clockLength) {
                    "equivalent content replacement cannot change clock length"
                }
                val updated = previous.copy(content = nextContent)
                structs[index] = updated
                updatedItems[updated.id] = updated
                affectedParents.add(updated.parent)
                updated.parentSub?.let { key -> affectedMapKeys.add(updated.parent to key) }
            }
        }
        if (updatedItems.isEmpty()) return emptyList()

        allItemsCache = null
        version++
        affectedParents.forEach { parent ->
            itemsByParent[parent]?.replaceAll(updatedItems)
            sequenceCache[parent]?.replaceItems(updatedItems)
            visibleTextCache.remove(parent)
        }
        affectedMapKeys.forEach { (parent, key) ->
            mapItemsByParentKey[parent, key]?.replaceAll { id, item -> updatedItems[id] ?: item }
            mapEntriesCache.remove(parent, key)
            mapOrderCache.remove(parent, key)
            mapOrderPositions.remove(parent, key)
            mapCurrentCache.remove(parent, key)
        }
        return updatedItems.values.toList()
    }

    public fun getClock(client: Long): Long {
        owner?.ensureThreadAccess()
        val structs = clientItems[client] ?: return 0
        return structs.lastOrNull()?.endClock() ?: 0
    }

    public fun stateVector(): StateVector {
        owner?.ensureThreadAccess()
        if (skips.isEmpty() && stateVectorCacheVersion == version) return stateVectorCache
        val state = java.util.TreeMap<Long, Long>()
        clientItems.forEach { (client, structs) ->
            val clock = structs.lastOrNull()?.endClock() ?: 0
            if (structs.isNotEmpty()) state[client] = clock
        }
        skips.clients.forEach { (client, ranges) ->
            ranges.minOfOrNull { range -> range.clock }?.let { clock -> state[client] = clock }
        }
        val snapshot = java.util.Collections.unmodifiableMap(state)
        if (skips.isEmpty()) {
            stateVectorCacheVersion = version
            stateVectorCache = snapshot
        }
        return snapshot
    }

    public fun integrityCheck() {
        owner?.ensureThreadAccess()
        clientItems.values.forEach { structs ->
            for (index in 1 until structs.size) {
                val left = structs[index - 1]
                val right = structs[index]
                check(left.endClock() == right.id.clock) {
                    "StructStore failed integrity check"
                }
            }
        }
    }

    internal fun allItems(): List<StoreItem> {
        owner?.ensureThreadAccess()
        return allItemsCache ?: buildList(
            clientItems.values.sumOf { structs -> structs.size },
        ) {
            clientItems.keys.sorted().forEach { client -> addAll(clientItems.getValue(client)) }
        }.also { items -> allItemsCache = items }
    }

    internal fun itemsForClient(client: Long): List<StoreItem> = clientItems[client].orEmpty()

    internal fun itemsForParent(parent: String): List<StoreItem> =
        itemsByParent[parent]?.toList().orEmpty()

    internal fun visibleSequenceItemsForParent(parent: String): List<StoreItem> =
        itemsByParent[parent]?.visibleSequenceItems().orEmpty()

    internal fun hasParent(parent: String): Boolean = itemsByParent[parent]?.isNotEmpty == true

    internal fun parentNames(): Set<String> = itemsByParent.keys

    internal fun firstItemForParent(parent: String, sequenceOnly: Boolean = false): StoreItem? {
        return itemsByParent[parent]?.first(sequenceOnly)
    }

    internal fun mapKeysForParent(parent: String): Set<String> =
        mapKeysByParent[parent].orEmpty()

    internal fun firstOwnerForNested(name: String): StoreItem? {
        return nestedOwnerByName[name]
    }

    internal fun hasNestedOwner(name: String): Boolean = name in nestedOwnerByName

    internal fun nestedOwnerKinds(): Map<String, RootKind> = nestedOwnerByName.mapNotNull { (name, owner) ->
        owner.content.directTypeRef()?.kind?.let { kind -> name to kind }
    }.toMap()

    internal fun firstItemEndingAfter(client: Long, clock: Long): Int =
        clientItems[client]?.findFirstEndingAfter(clock) ?: 0

    internal fun parentItemIds(): Map<String, Id> = parentItemIdsCache ?: allItems().mapNotNull { item ->
        item.content.directTypeRef()?.name?.let { name -> name to item.id }
    }.toMap().also { ids -> parentItemIdsCache = ids }

    internal fun parentKinds(): Map<String, RootKind> = owner?.knownParentKinds().orEmpty()

    internal fun itemsSince(stateVector: StateVector): List<StoreItem> {
        if (stateVector.isEmpty()) return allItems()
        return buildList {
            clientItems.keys.sorted().forEach { client ->
                val structs = clientItems.getValue(client)
                val targetClock = stateVector[client] ?: 0
                var index = structs.findFirstEndingAfter(targetClock)
                while (index < structs.size) {
                    val item = structs[index++]
                    when {
                        item.id.clock >= targetClock -> add(item)
                        item.content is ItemContent.Deleted ||
                            item.content is ItemContent.Text ||
                            item.content is ItemContent.ArrayValues ||
                            item.content is ItemContent.MapEntries -> {
                            val remaining = item.endClock() - targetClock
                            val content = when (val current = item.content) {
                                is ItemContent.Deleted -> current.copy(length = remaining)
                                is ItemContent.Text -> {
                                    val offset = (targetClock - item.id.clock)
                                        .toNonNegativeInt("text state-vector offset")
                                    current.copy(value = ContentString(current.value).splice(offset.toLong()).str)
                                }
                                is ItemContent.MapEntries -> {
                                    val offset = (targetClock - item.id.clock)
                                        .toNonNegativeInt("packed map state-vector offset")
                                    val values = current.values.drop(offset)
                                    if (values.size == 1) ItemContent.MapEntry(values.single())
                                    else current.copy(values = values)
                                }
                                is ItemContent.ArrayValues -> {
                                    val offset = (targetClock - item.id.clock)
                                        .toNonNegativeInt("packed array state-vector offset")
                                    val values = current.values.drop(offset)
                                    if (values.size == 1) ItemContent.Value(values.single())
                                    else current.copy(values = values)
                                }
                                else -> error("unreachable packed content")
                            }
                            add(
                                item.copy(
                                    id = Id(item.id.client, targetClock),
                                    origin = if (item.isGc) null else Id(item.id.client, targetClock - 1),
                                    content = content,
                                ),
                            )
                        }
                        else -> error("state vector splits unsupported store item at ${item.id}:$targetClock")
                    }
                }
            }
        }
    }

    internal fun itemsStartingIn(deleteSet: DeleteSet): List<StoreItem> = buildList {
        deleteSet.clients.keys.sorted().forEach { client ->
            val structs = clientItems[client] ?: return@forEach
            val ranges = deleteSet.rangesFor(client)
            if (structs.isEmpty() || ranges.isEmpty()) return@forEach

            var structIndex = structs.findFirstStartingAtOrAfter(ranges.first().clock)
            var rangeIndex = 0
            while (structIndex < structs.size && rangeIndex < ranges.size) {
                val item = structs[structIndex]
                val range = ranges[rangeIndex]
                when {
                    item.id.clock < range.clock -> structIndex++
                    item.id.clock >= range.end -> rangeIndex++
                    else -> {
                        add(item)
                        structIndex++
                    }
                }
            }
        }
    }

    internal fun itemsOverlapping(deleteSet: DeleteSet): List<StoreItem> = buildList {
        deleteSet.clients.keys.sorted().forEach { client ->
            val structs = clientItems[client] ?: return@forEach
            val ranges = deleteSet.rangesFor(client)
            if (structs.isEmpty() || ranges.isEmpty()) return@forEach

            var structIndex = structs.findFirstEndingAfter(ranges.first().clock)
            var rangeIndex = 0
            while (structIndex < structs.size && rangeIndex < ranges.size) {
                val item = structs[structIndex]
                val range = ranges[rangeIndex]
                when {
                    item.endClock() <= range.clock -> structIndex++
                    item.id.clock >= range.end -> rangeIndex++
                    else -> {
                        add(item)
                        structIndex++
                    }
                }
            }
        }
    }

    internal fun markDeleted(deleteSet: DeleteSet): Boolean = markDeleted(itemsStartingIn(deleteSet))

    internal fun markDeleted(items: Iterable<StoreItem>): Boolean {
        var changed = false
        val changedSequenceIds = linkedMapOf<String, MutableList<Id>>()
        val removedVisibleLengths = linkedMapOf<String, LongArray>()
        items.forEach { item ->
            if (!item.deleted) {
                captureDeletedBeforeMutation(item)
                if (item.parentSub == null && item.countable) {
                    val lengths = removedVisibleLengths.getOrPut(item.parent) {
                        LongArray(RootKind.entries.size)
                    }
                    lengths[item.content.kind.ordinal] = checkedClockAdd(
                        lengths[item.content.kind.ordinal],
                        item.length,
                        "removed visible length",
                    )
                }
                removeDerivedState(item, notifyOwner = false)
                item.deleted = true
                if (item.parentSub == null && sequenceCache[item.parent] != null) {
                    changedSequenceIds.getOrPut(item.parent) { mutableListOf() }.add(item.id)
                }
                deleteSetCache = null
                version++
                changed = true
            }
        }
        removedVisibleLengths.forEach { (parent, lengths) ->
            lengths.forEachIndexed { kindIndex, length ->
                if (length > 0) {
                    owner?.adjustOpenedTypeLength(parent, RootKind.entries[kindIndex], -length)
                }
            }
        }
        changedSequenceIds.forEach { (parent, ids) -> sequenceCache[parent]?.refreshAll(ids) }
        return changed
    }

    public fun deleteSet(): DeleteSet {
        owner?.ensureThreadAccess()
        val cached = deleteSetCache ?: DeleteSet.empty().also { deleteSet ->
            allItems().forEach { item ->
                if (item.deleted) deleteSet.add(item.id, item.length)
            }
        }.also { deleteSet -> deleteSetCache = deleteSet }
        return cached.copy()
    }

    internal fun sequence(parent: String): List<StoreItem> {
        owner?.ensureThreadAccess()
        return sequenceIndex(parent).snapshot()
    }

    /**
     * A large remote batch is cheaper to index once after integration than to rebalance the
     * existing treap for every struct. Visible lengths remain incremental; positional and
     * rendered caches rebuild lazily on their next read.
     */
    internal fun prepareBulkSequenceIntegration(items: List<StoreItem>, threshold: Int = 32) {
        if (sequenceCache.isEmpty()) return
        val counts = mutableMapOf<String, Int>()
        items.forEach { item ->
            if (item.parentSub == null) {
                counts[item.parent] = (counts[item.parent] ?: 0) + 1
            }
        }
        counts.forEach { (parent, count) ->
            if (count < threshold) return@forEach
            sequenceCache.remove(parent)
            renderedTextAttributes.remove(parent)
            visibleTextCache.remove(parent)
        }
    }

    internal fun visibleLength(parent: String, kind: RootKind): Long {
        owner?.ensureThreadAccess()
        return visibleLengths[parent]?.get(kind.ordinal) ?: 0L
    }

    internal fun totalVisibleLength(parent: String): Long {
        owner?.ensureThreadAccess()
        return visibleLengths[parent]?.fold(0L) { total, length ->
            checkedClockAdd(total, length, "total visible length")
        } ?: 0L
    }

    internal fun visibleSequenceItemAt(
        parent: String,
        kind: RootKind,
        index: Long,
    ): Pair<StoreItem, Long>? = sequenceIndex(parent).visibleItemAt(kind, index)

    internal fun visibleSequenceItemAt(parent: String, index: Long): Pair<StoreItem, Long>? =
        sequenceIndex(parent).visibleItemAt(index)

    internal fun sequenceAnchors(parent: String, kind: RootKind, index: Long): Pair<StoreItem?, StoreItem?> =
        sequenceIndex(parent).anchorsAt(kind, index)

    internal fun sequenceAnchors(parent: String, index: Long): Pair<StoreItem?, StoreItem?> =
        sequenceIndex(parent).anchorsAt(index)

    internal fun visibleSequenceIndexAfter(parent: String, kind: RootKind, id: Id): Long? =
        sequenceIndex(parent).visibleIndexAfter(kind, id)

    internal fun visibleItemsInRange(parent: String, index: Long, length: Long): List<StoreItem> =
        sequenceIndex(parent).visibleItemsInRange(index, length)

    internal fun hasVisibleNativeTextFormat(parent: String): Boolean {
        owner?.ensureThreadAccess()
        return visibleNativeTextFormats[parent]?.isNotEmpty() == true
    }

    internal fun hasVisibleLegacyTextFormat(parent: String): Boolean {
        owner?.ensureThreadAccess()
        return visibleLegacyTextFormats[parent]?.isNotEmpty() == true
    }

    /**
     * Resolves native Yjs ContentFormat markers lazily, like Y.Text's linked-list readers.
     *
     * Standard updates keep formatting in marker structs. Caching the rendered attributes avoids
     * rewriting every countable struct during update integration while retaining O(1) lookups for
     * Kotlin APIs that need the attributes of a particular logical text item.
     */
    internal fun renderedTextAttributes(item: StoreItem): Map<String, YValue> {
        owner?.ensureThreadAccess()
        if (!hasVisibleNativeTextFormat(item.parent)) return item.content.storedTextAttributes()
        val containing = getStoreItem(item.id) ?: item
        return renderedTextAttributes.getOrPut(item.parent) {
            buildRenderedTextAttributes(item.parent)
        }[containing.id] ?: item.content.storedTextAttributes()
    }

    internal fun visibleText(parent: String, kind: RootKind): String {
        owner?.ensureThreadAccess()
        val byKind = visibleTextCache.getOrPut(parent) { arrayOfNulls(RootKind.entries.size) }
        byKind[kind.ordinal]?.let { return it }
        val text = buildString(visibleLength(parent, kind).toNonNegativeInt("visible text length")) {
            sequenceIndex(parent).forEach { item ->
                if (!item.deleted && item.content.kind == kind) {
                    (item.content as? ItemContent.Text)?.let { content -> append(content.value) }
                }
            }
        }
        byKind[kind.ordinal] = text
        return text
    }

    internal fun lastSequenceItem(parent: String, kind: RootKind): StoreItem? {
        owner?.ensureThreadAccess()
        return sequenceIndex(parent).last { item -> item.content.kind == kind }
    }

    internal fun lastSequenceItem(parent: String): StoreItem? = sequenceIndex(parent).last { true }

    internal fun areSequenceAdjacent(left: StoreItem, right: StoreItem): Boolean =
        left.parentSub == null &&
            left.parent == right.parent &&
            sequenceIndex(left.parent).areAdjacent(left.id, right.id)

    internal fun sequenceNeighbors(item: StoreItem): Pair<StoreItem?, StoreItem?> =
        if (item.parentSub == null) sequenceIndex(item.parent).neighbors(item.id) else null to null

    internal fun visibleSequenceNeighbors(item: StoreItem): Pair<StoreItem?, StoreItem?> =
        if (item.parentSub == null) sequenceIndex(item.parent).visibleNeighbors(item.id) else null to null

    internal fun firstSequenceItem(parent: String): StoreItem? = sequenceIndex(parent).first()

    internal fun firstVisibleSequenceItem(parent: String, kind: RootKind): StoreItem? =
        sequenceIndex(parent).firstVisible(kind)

    internal fun firstVisibleSequenceItem(parent: String): StoreItem? = sequenceIndex(parent).firstVisible()

    internal fun sequenceCursor(parent: String): SequenceCursor = sequenceIndex(parent).cursor()

    internal fun sequenceCursorAtFirstUndeleted(parent: String): SequenceCursor =
        sequenceIndex(parent).cursorAtFirstUndeleted()

    internal fun sequenceCursorAtVisiblePrefix(parent: String, kind: RootKind, index: Long): SequenceCursor? =
        sequenceIndex(parent).cursorAtVisiblePrefix(kind, index)

    private fun sequenceIndex(parent: String): IndexedSequence =
        sequenceCache.getOrPut(parent) {
            IndexedSequence(buildSequence(parent), ::getStoreItem)
        }

    /** Index-walking counterpart to `zipWithNext { }.all { }`, without the intermediate pair list. */
    private inline fun List<StoreItem>.allAdjacentPairs(predicate: (StoreItem, StoreItem) -> Boolean): Boolean {
        for (index in 1 until size) {
            if (!predicate(this[index - 1], this[index])) return false
        }
        return true
    }

    private fun buildSequence(parent: String): List<StoreItem> {
        sequenceBuildCount++
        val indexed = itemsForParent(parent).filter { it.parentSub == null }
        val items = if (indexed.allAdjacentPairs { left, right -> left.id <= right.id }) {
            indexed
        } else {
            indexed.sortedBy { item -> item.id }
        }
        if (items.size < 2) return items
        if (
            items.allAdjacentPairs { left, right ->
                left.id.client == right.id.client &&
                    right.origin == left.lastId &&
                    left.rightOrigin == right.rightOrigin
            }
        ) {
            return items
        }
        if (
            items.allAdjacentPairs { previous, next ->
                previous.id.client == next.id.client &&
                    next.origin == null &&
                    next.rightOrigin == previous.id
            }
        ) {
            return items.asReversed()
        }

        // Rebuild the linked sequence using the same conflict scan as Item.integrate in Yjs.
        // A simple origin-child traversal is insufficient: an item's rightOrigin may constrain
        // it relative to items from a different origin subtree.
        val nodes = items.map(::SequenceNode)
        val nodesByClient = nodes.groupBy { node -> node.item.id.client }

        fun findNode(id: Id?): SequenceNode? {
            if (id == null) return null
            val clientNodes = nodesByClient[id.client] ?: return null
            var low = 0
            var high = clientNodes.lastIndex
            var candidate: SequenceNode? = null
            while (low <= high) {
                val middle = (low + high) ushr 1
                val node = clientNodes[middle]
                if (node.item.id.clock <= id.clock) {
                    candidate = node
                    low = middle + 1
                } else {
                    high = middle - 1
                }
            }
            return candidate?.takeIf { node -> node.item.contains(id) }
        }

        val dependencies = nodes.associateWith { linkedSetOf<SequenceNode>() }
        val dependents = nodes.associateWith { linkedSetOf<SequenceNode>() }
        fun dependsOn(node: SequenceNode, dependency: SequenceNode?) {
            if (dependency == null || dependency === node || !dependencies.getValue(node).add(dependency)) return
            dependents.getValue(dependency).add(node)
        }
        nodesByClient.values.forEach { clientNodes ->
            for (index in 1 until clientNodes.size) dependsOn(clientNodes[index], clientNodes[index - 1])
        }
        nodes.forEach { node ->
            dependsOn(node, findNode(node.item.origin))
            dependsOn(node, findNode(node.item.rightOrigin))
        }

        val remaining = nodes.toMutableSet()
        val integrated = hashSetOf<SequenceNode>()
        val indegrees = dependencies.mapValuesTo(mutableMapOf()) { (_, values) -> values.size }
        val ready = java.util.PriorityQueue(compareBy<SequenceNode> { node -> node.item.id })
        nodes.filterTo(ready) { node -> indegrees.getValue(node) == 0 }
        var start: SequenceNode? = null

        while (remaining.isNotEmpty()) {
            var node = ready.poll()
            while (node != null && node !in remaining) node = ready.poll()
            if (node == null) {
                // Valid Yjs updates are acyclic. Keep malformed/private legacy data deterministic
                // instead of looping forever if it contains a dependency cycle.
                node = remaining.minBy { candidate -> candidate.item.id }
            }

            val item = node.item
            var left = findNode(item.origin)?.takeIf { anchor -> anchor in integrated }
            val right = findNode(item.rightOrigin)?.takeIf { anchor -> anchor in integrated }
            var other = if (left != null) left.right else start
            val conflictingItems = hashSetOf<SequenceNode>()
            val itemsBeforeOrigin = hashSetOf<SequenceNode>()
            val scanned = hashSetOf<SequenceNode>()

            while (other != null && other !== right) {
                check(scanned.add(other)) { "cycle while integrating ${item.id} in $parent" }
                itemsBeforeOrigin.add(other)
                conflictingItems.add(other)
                when {
                    compareIDs(item.origin, other.item.origin) -> {
                        if (other.item.id.client < item.id.client) {
                            left = other
                            conflictingItems.clear()
                        } else if (compareIDs(item.rightOrigin, other.item.rightOrigin)) {
                            break
                        }
                    }
                    other.item.origin != null && findNode(other.item.origin) in itemsBeforeOrigin -> {
                        if (findNode(other.item.origin) !in conflictingItems) {
                            left = other
                            conflictingItems.clear()
                        }
                    }
                    else -> break
                }
                other = other.right
            }

            val actualRight = if (left != null) left.right else start
            node.left = left
            node.right = actualRight
            if (left == null) start = node else left.right = node
            actualRight?.left = node

            remaining.remove(node)
            integrated.add(node)
            dependents.getValue(node).forEach { dependent ->
                val next = indegrees.getValue(dependent) - 1
                indegrees[dependent] = next
                if (next == 0 && dependent in remaining) ready.add(dependent)
            }
        }

        return buildList(items.size) {
            val seen = hashSetOf<SequenceNode>()
            var current = start
            while (current != null && seen.add(current)) {
                add(current.item)
                current = current.right
            }
        }
    }

    internal fun mapEntries(parent: String, key: String): List<StoreItem> =
        mapEntriesCache.getOrPut(parent, key) {
            val items = mapItemsByParentKey[parent, key]?.values.orEmpty()
            if (items.size < 2) {
                items.toList()
            } else {
                // compareBy/thenBy would box both clocks on every comparison.
                items.sortedWith { left, right ->
                    left.id.clock.compareTo(right.id.clock).takeIf { order -> order != 0 }
                        ?: left.id.client.compareTo(right.id.client)
                }
            }
        }

    internal fun cachedMapOrder(parent: String, key: String, compute: () -> List<StoreItem>): List<StoreItem> =
        mapOrderCache.getOrPut(parent, key, compute)

    internal fun cachedMapItemIndex(
        parent: String,
        key: String,
        id: Id,
        computeOrder: () -> List<StoreItem>,
    ): Int {
        val order = mapOrderCache.getOrPut(parent, key, computeOrder)
        val positions = mapOrderPositions.getOrPut(parent, key) {
            order.mapIndexed { index, item -> item.id to index }.toMap()
        }
        return positions[id] ?: -1
    }

    internal fun cachedCurrentMapItem(parent: String, key: String, compute: () -> StoreItem?): StoreItem? {
        // A cached null is a real answer, so distinguish it from a miss without a second lookup.
        mapCurrentCache[parent, key]?.let { return it }
        if (mapCurrentCache.contains(parent, key)) return null
        return compute().also { current -> mapCurrentCache.put(parent, key, current) }
    }

    internal fun cacheCurrentMapItem(parent: String, key: String, item: StoreItem?) {
        mapCurrentCache.put(parent, key, item)
    }

    private fun invalidateNonSequenceCaches(item: StoreItem) {
        allItemsCache = null
        deleteSetCache = null
        if (item.parentSub == null) {
            if (renderedTextAttributes.isNotEmpty()) renderedTextAttributes.remove(item.parent)
            return
        }
        // A bulk remote apply adds thousands of map structs before anything reads a map order.
        // Skipping the composite key while the caches are cold keeps that path allocation-free.
        if (
            mapEntriesCache.isEmpty &&
            mapOrderCache.isEmpty &&
            mapOrderPositions.isEmpty &&
            mapCurrentCache.isEmpty
        ) return
        val key = item.parentSub
        mapEntriesCache.remove(item.parent, key)
        mapOrderCache.remove(item.parent, key)
        mapOrderPositions.remove(item.parent, key)
        mapCurrentCache.remove(item.parent, key)
    }

    private fun recordAdded(item: StoreItem) {
        invalidateNonSequenceCaches(item)
        if (item.content.directTypeRef() != null) parentItemIdsCache = null
        version++
        addStructuralIndex(item)
        addDerivedState(item)
        if (item.parentSub == null) {
            sequenceCache[item.parent]?.integrate(item)
        }
    }

    private fun recordReplacement(
        previous: StoreItem,
        replacements: List<StoreItem>,
        additionallyRemoved: List<StoreItem> = emptyList(),
    ) {
        invalidateNonSequenceCaches(previous)
        if (
            previous.content.directTypeRef() != null ||
            additionallyRemoved.any { item -> item.content.directTypeRef() != null } ||
            replacements.any { item -> item.content.directTypeRef() != null }
        ) {
            parentItemIdsCache = null
        }
        version++
        removeStructuralIndex(previous)
        additionallyRemoved.forEach(::removeStructuralIndex)
        replacements.forEach(::addStructuralIndex)
        removeDerivedState(previous)
        additionallyRemoved.forEach(::removeDerivedState)
        replacements.forEach(::addDerivedState)
        sequenceCache[previous.parent]?.replace(previous, replacements, additionallyRemoved)
    }

    private fun addStructuralIndex(item: StoreItem) {
        itemsByParent.getOrPut(item.parent, ::ParentItemIndex).add(item)
        item.content.directTypeRef()?.name?.let { name ->
            val existing = nestedOwnerByName[name]
            if (existing == null) {
                nestedOwnerByName[name] = item
            } else if (existing.id != item.id) {
                val owners = additionalNestedOwnersByName.getOrPut(name) {
                    hashMapOf(existing.id to existing)
                }
                owners[item.id] = item
                if (item.id < existing.id) nestedOwnerByName[name] = item
            } else {
                nestedOwnerByName[name] = item
                additionalNestedOwnersByName[name]?.set(item.id, item)
            }
        }
        item.parentSub?.let { key ->
            mapItemsByParentKey.getOrPut(item.parent, key) { linkedMapOf() }[item.id] = item
            mapKeysByParent.getOrPut(item.parent) { linkedSetOf() }.add(key)
        }
    }

    private fun removeStructuralIndex(item: StoreItem) {
        itemsByParent[item.parent]?.let { items ->
            items.remove(item.id)
            if (!items.isNotEmpty) itemsByParent.remove(item.parent)
        }
        item.content.directTypeRef()?.name?.let { name ->
            val owners = additionalNestedOwnersByName[name]
            if (owners != null) {
                owners.remove(item.id)
                when (owners.size) {
                    0 -> {
                        additionalNestedOwnersByName.remove(name)
                        nestedOwnerByName.remove(name)
                    }
                    1 -> {
                        additionalNestedOwnersByName.remove(name)
                        nestedOwnerByName[name] = owners.values.first()
                    }
                    else -> nestedOwnerByName[name] = owners.values.minBy { owner -> owner.id }
                }
            } else if (nestedOwnerByName[name]?.id == item.id) {
                nestedOwnerByName.remove(name)
            }
        }
        item.parentSub?.let { key ->
            mapItemsByParentKey[item.parent, key]?.let { items ->
                items.remove(item.id)
                if (items.isEmpty()) {
                    mapItemsByParentKey.remove(item.parent, key)
                    mapKeysByParent[item.parent]?.let { keys ->
                        keys.remove(key)
                        if (keys.isEmpty()) mapKeysByParent.remove(item.parent)
                    }
                }
            }
        }
    }

    private fun addDerivedState(item: StoreItem) {
        if (item.parentSub == null) {
            if (visibleTextCache.isNotEmpty()) visibleTextCache.remove(item.parent)
            if (renderedTextAttributes.isNotEmpty()) renderedTextAttributes.remove(item.parent)
        }
        if (item.parentSub != null || item.deleted) return
        if (item.countable) {
            val lengths = visibleLengths.getOrPut(item.parent) { LongArray(RootKind.entries.size) }
            val kindIndex = item.content.kind.ordinal
            lengths[kindIndex] = checkedClockAdd(lengths[kindIndex], item.length, "visible sequence length")
            owner?.adjustOpenedTypeLength(item.parent, item.content.kind, item.length)
        }
        if (item.content is ItemContent.NativeTextFormat) {
            visibleNativeTextFormats.getOrPut(item.parent) { linkedSetOf() }.add(item.id)
        }
        if (item.content is ItemContent.TextFormat) {
            visibleLegacyTextFormats.getOrPut(item.parent) { linkedSetOf() }.add(item.id)
        }
    }

    private fun removeDerivedState(item: StoreItem, notifyOwner: Boolean = true) {
        if (item.parentSub == null) {
            if (visibleTextCache.isNotEmpty()) visibleTextCache.remove(item.parent)
            if (renderedTextAttributes.isNotEmpty()) renderedTextAttributes.remove(item.parent)
        }
        if (item.parentSub != null || item.deleted) return
        if (item.countable) {
            val lengths = checkNotNull(visibleLengths[item.parent]) { "missing visible length for ${item.parent}" }
            val kindIndex = item.content.kind.ordinal
            val next = lengths[kindIndex] - item.length
            check(next >= 0) { "visible sequence length underflow for ${item.parent}:${item.content.kind}" }
            lengths[kindIndex] = next
            if (notifyOwner) owner?.adjustOpenedTypeLength(item.parent, item.content.kind, -item.length)
            if (lengths.all { length -> length == 0L }) visibleLengths.remove(item.parent)
        }
        if (item.content is ItemContent.NativeTextFormat) {
            visibleNativeTextFormats[item.parent]?.let { ids ->
                ids.remove(item.id)
                if (ids.isEmpty()) visibleNativeTextFormats.remove(item.parent)
            }
        }
        if (item.content is ItemContent.TextFormat) {
            visibleLegacyTextFormats[item.parent]?.let { ids ->
                ids.remove(item.id)
                if (ids.isEmpty()) visibleLegacyTextFormats.remove(item.parent)
            }
        }
    }

    private fun buildRenderedTextAttributes(parent: String): Map<Id, Map<String, YValue>> {
        val active = linkedMapOf<String, YValue>()
        var activeWithoutNulls: Map<String, YValue> = emptyMap()
        return buildMap {
            sequenceIndex(parent).forEach { item ->
                if (item.deleted) return@forEach
                when (val content = item.content) {
                    is ItemContent.NativeTextFormat -> {
                        active[content.key] = content.value
                        activeWithoutNulls = active
                            .filterValues { value -> value != YValue.Null }
                            .toSortedMap()
                    }
                    is ItemContent.Text,
                    is ItemContent.TextEmbed,
                    is ItemContent.XmlType -> {
                        val stored = content.storedTextAttributes()
                        val attributes = if (stored.isEmpty()) {
                            activeWithoutNulls
                        } else {
                            stored.toMutableMap().also { values ->
                                active.forEach { (key, value) ->
                                    if (value == YValue.Null) values.remove(key) else values[key] = value
                                }
                            }.toSortedMap()
                        }
                        put(item.id, attributes)
                    }
                    else -> if (item.countable) {
                        // @y/y 14's unified Type applies ContentFormat markers to every child
                        // kind, including ContentAny/JSON/Binary. Legacy projections ignore the
                        // metadata but retain the standard marker structs on the wire.
                        put(item.id, activeWithoutNulls)
                    }
                }
            }
        }
    }

    internal fun mergeCompatibleItems(ids: List<Id>): StoreItem? {
        if (ids.size < 2 || ids.any { id -> id.client != ids.first().client }) return null
        val structs = clientItems[ids.first().client] ?: return null
        val firstIndex = structs.findStartIndex(ids.first().clock)
        if (firstIndex < 0 || firstIndex + ids.size > structs.size) return null
        ids.forEachIndexed { offset, id ->
            if (structs[firstIndex + offset].id != id) return null
        }
        return mergeCompatibleItemsAt(ids.first().client, firstIndex, ids.size)
    }

    /**
     * Merges a known contiguous client-store range without materializing an ID list.
     *
     * Transaction cleanup already located and validated logical adjacency. Keeping the physical
     * range as indices removes the dominant per-update `mergeIds`, `subList().toList()`, and
     * `items.map(::id)` allocations from the unobserved standard-update path.
     */
    internal fun mergeCompatibleItemsAt(client: Long, firstIndex: Int, itemCount: Int): StoreItem? {
        if (itemCount < 2) return null
        val structs = clientItems[client] ?: return null
        if (firstIndex < 0 || firstIndex >= structs.size || itemCount > structs.size - firstIndex) return null
        val endIndex = firstIndex + itemCount
        val firstItem = structs[firstIndex]
        val firstContent = firstItem.content
        if (
            firstContent !is ItemContent.Text &&
            firstContent !is ItemContent.Value &&
            firstContent !is ItemContent.ArrayValues &&
            firstContent !is ItemContent.MapEntry &&
            firstContent !is ItemContent.MapEntries
        ) return null
        for (index in firstIndex + 1 until endIndex) {
            val left = structs[index - 1]
            val right = structs[index]
            if (
                left.endClock() != right.id.clock ||
                right.origin != left.lastId ||
                firstItem.rightOrigin != right.rightOrigin ||
                firstItem.parent != right.parent ||
                firstItem.parentSub != right.parentSub ||
                firstItem.deleted != right.deleted ||
                firstItem.requiresClockContinuity != right.requiresClockContinuity ||
                firstItem.isGc != right.isGc ||
                firstItem.unresolvedParent != right.unresolvedParent ||
                firstItem.countable != right.countable
            ) return null
            when (firstContent) {
                is ItemContent.Text -> {
                    val content = right.content as? ItemContent.Text ?: return null
                    if (
                        firstContent.kind != content.kind ||
                        firstContent.attributes != content.attributes ||
                        firstContent.baseAttributes != content.baseAttributes
                    ) return null
                }
                is ItemContent.MapEntry,
                is ItemContent.MapEntries -> if (
                    right.content !is ItemContent.MapEntry &&
                    right.content !is ItemContent.MapEntries
                ) return null
                is ItemContent.Value,
                is ItemContent.ArrayValues -> if (
                    right.content !is ItemContent.Value &&
                    right.content !is ItemContent.ArrayValues
                ) return null
                else -> return null
            }
        }

        captureClientBeforeMutation(firstItem.id.client)
        clearLookupHint(firstItem.id.client)
        val mergedContent = when (firstContent) {
            is ItemContent.Text -> {
                var mergedValueLength = 0L
                for (index in firstIndex until endIndex) {
                    mergedValueLength = checkedClockAdd(
                        mergedValueLength,
                        structs[index].length,
                        "merged text length",
                    )
                }
                firstContent.copy(
                    value = buildString(mergedValueLength.toNonNegativeInt("merged text length")) {
                        for (index in firstIndex until endIndex) {
                            append((structs[index].content as ItemContent.Text).value)
                        }
                    },
                )
            }
            is ItemContent.MapEntry,
            is ItemContent.MapEntries -> {
                val values = ArrayList<YValue>()
                for (index in firstIndex until endIndex) {
                    when (val content = structs[index].content) {
                        is ItemContent.MapEntry -> values.add(content.value)
                        is ItemContent.MapEntries -> values.addAll(content.values)
                        else -> error("incompatible packed map content")
                    }
                }
                ItemContent.MapEntries(values)
            }
            is ItemContent.Value,
            is ItemContent.ArrayValues -> {
                val values = ArrayList<YValue>()
                for (index in firstIndex until endIndex) {
                    when (val content = structs[index].content) {
                        is ItemContent.Value -> values.add(content.value)
                        is ItemContent.ArrayValues -> values.addAll(content.values)
                        else -> error("incompatible packed array content")
                    }
                }
                ItemContent.ArrayValues(values)
            }
            else -> return null
        }
        val removed = if (itemCount == 2) {
            listOf(structs[firstIndex + 1])
        } else {
            ArrayList<StoreItem>(itemCount - 1).also { items ->
                for (index in firstIndex + 1 until endIndex) items.add(structs[index])
            }
        }
        val merged = firstItem.copy(content = mergedContent)
        structs[firstIndex] = merged
        structs.subList(firstIndex + 1, endIndex).clear()
        recordReplacement(firstItem, listOf(merged), additionallyRemoved = removed)
        return merged
    }

    private fun StoreItem.contains(id: Id): Boolean =
        id.client == this.id.client && id.clock >= this.id.clock && id.clock < endClock()
}

/**
 * Mutable linked sequence used only for a materialized parent.
 *
 * Yjs keeps left/right item links and resolves anchors through the struct store. Mirroring that
 * shape avoids shifting a Kotlin [MutableList] and rescanning it for every integrated struct.
 * A read-only list is materialized lazily for algorithms that genuinely need indexed traversal.
 */
private class IndexedSequence(
    items: List<StoreItem>,
    private val resolveStoreItem: (Id) -> StoreItem?,
) {
    private class Node(var item: StoreItem, val priority: Long) {
        var left: Node? = null
        var right: Node? = null
        var treeLeft: Node? = null
        var treeRight: Node? = null
        var treeParent: Node? = null
        var treeSize: Int = 1
        var treeUndeletedCount: Int = 0
        val treeVisibleLengths: LongArray = LongArray(RootKind.entries.size)
    }

    private val nodesByStart = hashMapOf<Id, Node>()
    private var first: Node? = null
    private var last: Node? = null
    private var treeRoot: Node? = null
    private var materialized: List<StoreItem>? = null
    private val firstVisibleByKind: Array<Node?> = arrayOfNulls(RootKind.entries.size)
    private val firstVisibleComputed: BooleanArray = BooleanArray(RootKind.entries.size)
    private var positionHintKind: RootKind? = null
    private var positionHintIndex: Long = -1
    private var positionHintItemStart: Long = -1
    private var positionHintNode: Node? = null
    private var priorityState: Long = 0x6A09E667F3BCC909L
    private var usedKindMask: Int = 0

    init {
        items.forEach { item ->
            includeKind(item)
            append(item)
        }
        materialized = items.toList()
    }

    fun snapshot(): List<StoreItem> = materialized ?: buildList(nodesByStart.size) {
        val seen = hashSetOf<Node>()
        var current = first
        while (current != null && seen.add(current)) {
            add(current.item)
            current = current.right
        }
    }.also { materialized = it }

    fun first(): StoreItem? = first?.item

    fun firstVisible(kind: RootKind): StoreItem? {
        val kindIndex = kind.ordinal
        if (!firstVisibleComputed[kindIndex]) {
            var candidate = first
            while (candidate != null && candidate.item.visibleLength(kind) == 0L) {
                candidate = candidate.right
            }
            firstVisibleByKind[kindIndex] = candidate
            firstVisibleComputed[kindIndex] = true
        }
        return firstVisibleByKind[kindIndex]?.item
    }

    fun firstVisible(): StoreItem? = visibleItemAt(0)?.first

    fun cursor(): SequenceCursor = cursorFrom(first)

    /**
     * Starts after the deleted structural prefix in O(log n).
     *
     * Native text formatting ignores deleted markers but still needs the immediate predecessor as
     * its insertion origin. Keeping an undeleted subtree count mirrors the linked Yjs traversal
     * without rescanning tens of thousands of historical format markers on every edit.
     */
    fun cursorAtFirstUndeleted(): SequenceCursor = cursorFrom(firstUndeletedNode())

    /**
     * Positions the cursor where an undeleted walk consuming [index] visible [kind] units stops.
     *
     * The linked walk skips deleted structs and never consumes anything after the requested
     * prefix, so the stopping node is the first undeleted successor of the node holding the last
     * consumed unit. Both are reachable through the subtree length index in O(log n), which keeps
     * a mid-document edit off the linear scan Yjs avoids with its search markers. Returns null
     * when [index] is past the visible length, which the caller reports as an out-of-bounds index.
     */
    fun cursorAtVisiblePrefix(kind: RootKind, index: Long): SequenceCursor? {
        if (index <= 0L) return cursorAtFirstUndeleted()
        val (node, itemStart) = nodeCoveringVisibleUnit(index - 1, kind) ?: return null
        check(checkedClockAdd(itemStart, node.item.visibleLength(kind), "sequence item end") == index) {
            "text format start must split packed content"
        }
        return cursorFrom(nextUndeletedNode(node))
    }

    private fun cursorFrom(start: Node?): SequenceCursor {
        var current = start
        return SequenceCursor(
            currentProvider = { current?.item },
            previousProvider = { current?.left?.item ?: if (current == null) last?.item else null },
            advanceAction = { current = current?.right },
            advanceToNextUndeletedAction = {
                current = current?.let(::nextUndeletedNode)
            },
            seekAction = { item -> current = nodesByStart[item.id] },
        )
    }

    /** Subtree descent for the node holding visible unit [index], without touching the position hint. */
    private fun nodeCoveringVisibleUnit(index: Long, kind: RootKind): Pair<Node, Long>? {
        var current = treeRoot
        var precedingLength = 0L
        while (current != null) {
            val itemStart = checkedClockAdd(
                precedingLength,
                visibleLengthForKind(current.treeLeft, kind),
                "sequence position",
            )
            val itemLength = current.item.visibleLength(kind)
            current = when {
                index < itemStart -> current.treeLeft
                itemLength > 0 && index < checkedClockAdd(itemStart, itemLength, "sequence item end") ->
                    return current to itemStart
                else -> {
                    precedingLength = checkedClockAdd(itemStart, itemLength, "sequence position")
                    current.treeRight
                }
            }
        }
        return null
    }

    private fun firstUndeletedNode(): Node? {
        return firstUndeletedNode(treeRoot)
    }

    private fun firstUndeletedNode(start: Node?): Node? {
        var current = start
        while (current != null) {
            when {
                (current.treeLeft?.treeUndeletedCount ?: 0) > 0 -> current = current.treeLeft
                !current.item.deleted -> return current
                else -> current = current.treeRight
            }
        }
        return null
    }

    private fun nextUndeletedNode(node: Node): Node? {
        if ((node.treeRight?.treeUndeletedCount ?: 0) > 0) {
            return firstUndeletedNode(node.treeRight)
        }
        var child = node
        var parent = child.treeParent
        while (parent != null) {
            if (child === parent.treeLeft) {
                if (!parent.item.deleted) return parent
                if ((parent.treeRight?.treeUndeletedCount ?: 0) > 0) {
                    return firstUndeletedNode(parent.treeRight)
                }
            }
            child = parent
            parent = parent.treeParent
        }
        return null
    }

    private fun previousUndeletedNode(node: Node): Node? {
        if ((node.treeLeft?.treeUndeletedCount ?: 0) > 0) {
            return lastUndeletedNode(node.treeLeft)
        }
        var child = node
        var parent = child.treeParent
        while (parent != null) {
            if (child === parent.treeRight) {
                if (!parent.item.deleted) return parent
                if ((parent.treeLeft?.treeUndeletedCount ?: 0) > 0) {
                    return lastUndeletedNode(parent.treeLeft)
                }
            }
            child = parent
            parent = parent.treeParent
        }
        return null
    }

    private fun lastUndeletedNode(start: Node?): Node? {
        var current = start
        while (current != null) {
            when {
                (current.treeRight?.treeUndeletedCount ?: 0) > 0 -> current = current.treeRight
                !current.item.deleted -> return current
                else -> current = current.treeLeft
            }
        }
        return null
    }

    fun last(predicate: (StoreItem) -> Boolean): StoreItem? {
        var current = last
        while (current != null) {
            if (predicate(current.item)) return current.item
            current = current.left
        }
        return null
    }

    fun forEach(action: (StoreItem) -> Unit) {
        var current = first
        while (current != null) {
            action(current.item)
            current = current.right
        }
    }

    fun areAdjacent(leftId: Id, rightId: Id): Boolean =
        nodesByStart[leftId]?.right?.item?.id == rightId

    fun neighbors(id: Id): Pair<StoreItem?, StoreItem?> {
        val node = nodesByStart[id] ?: return null to null
        return node.left?.item to node.right?.item
    }

    fun visibleNeighbors(id: Id): Pair<StoreItem?, StoreItem?> {
        val node = nodesByStart[id] ?: return null to null
        return previousUndeletedNode(node)?.item to nextUndeletedNode(node)?.item
    }

    fun refresh(id: Id) {
        invalidateVisibleEdges()
        var current = nodesByStart[id]
        if (current != null && current === positionHintNode) {
            val kind = positionHintKind
            var next = current.right
            while (next != null && (kind == null || next.item.visibleLength(kind) == 0L)) next = next.right
            positionHintNode = next
        } else {
            invalidatePositionHint()
        }
        while (current != null) {
            recalculate(current)
            current = current.treeParent
        }
    }

    fun refreshAll(ids: List<Id>) {
        if (ids.isEmpty()) return
        if (ids.size == 1) {
            refresh(ids.single())
            return
        }
        invalidateVisibleEdges()
        invalidatePositionHint()
        if (ids.size <= 8) {
            ids.forEach { id ->
                var current = nodesByStart[id]
                while (current != null) {
                    recalculate(current)
                    current = current.treeParent
                }
            }
        } else {
            recalculateSubtree(treeRoot)
        }
    }

    fun visibleItemAt(kind: RootKind, index: Long): Pair<StoreItem, Long>? = visibleItemAt(index, kind)

    fun visibleItemAt(index: Long): Pair<StoreItem, Long>? = visibleItemAt(index, kind = null)

    fun anchorsAt(kind: RootKind, index: Long): Pair<StoreItem?, StoreItem?> {
        val totalLength = visibleLengthForKind(treeRoot, kind)
        require(index in 0..totalLength) { "sequence index is out of bounds" }
        val right = if (index == totalLength) null else visibleItemAt(kind, index)?.first
        var leftNode = if (right == null) last else nodesByStart[right.id]?.left
        while (leftNode != null && leftNode.item.content.kind != kind) leftNode = leftNode.left
        return leftNode?.item to right
    }

    fun anchorsAt(index: Long): Pair<StoreItem?, StoreItem?> {
        val totalLength = visibleLength(treeRoot, kind = null)
        require(index in 0..totalLength) { "sequence index is out of bounds" }
        val right = if (index == totalLength) null else visibleItemAt(index)?.first
        val leftNode = if (right == null) last else nodesByStart[right.id]?.left
        return leftNode?.item to right
    }

    fun visibleIndexAfter(kind: RootKind, id: Id): Long? {
        val containing = resolveStoreItem(id) ?: return null
        val node = nodesByStart[containing.id] ?: return null
        var index = visibleLengthForKind(node.treeLeft, kind)
        var current = node
        var parent = current.treeParent
        while (parent != null) {
            if (current === parent.treeRight) {
                index = checkedClockAdd(
                    index,
                    visibleLengthForKind(parent.treeLeft, kind),
                    "sequence item prefix",
                )
                index = checkedClockAdd(index, parent.item.visibleLength(kind), "sequence item prefix")
            }
            current = parent
            parent = current.treeParent
        }
        if (node.item.visibleLength(kind) > 0L) {
            val interiorEnd = checkedClockAdd(id.clock - containing.id.clock, 1, "sequence interior end")
                .coerceAtMost(containing.length)
            index = checkedClockAdd(index, interiorEnd, "sequence index after item")
        }
        return index
    }

    fun visibleItemsInRange(index: Long, length: Long): List<StoreItem> {
        if (length <= 0) return emptyList()
        val firstVisible = visibleItemAt(index)?.first ?: return emptyList()
        var current = nodesByStart[firstVisible.id]
        var remaining = length
        return buildList {
            while (current != null && remaining > 0) {
                val item = current.item
                if (!item.deleted && item.countable) {
                    add(item)
                    remaining -= item.length
                }
                current = current.right
            }
        }
    }

    fun integrate(item: StoreItem) {
        invalidateVisibleEdges()
        includeKind(item)
        val hintedNode = positionHintNode
        val hintedKind = positionHintKind
        var left = find(item.origin)
        val right = find(item.rightOrigin)
        val immediateRight = if (left == null) first else left.right
        if (immediateRight === right) {
            insertWithHint(left, item, hintedNode, hintedKind)
            return
        }
        var other = if (left == null) first else left.right
        val conflictingItems = hashSetOf<Node>()
        val itemsBeforeOrigin = hashSetOf<Node>()

        while (other != null && other !== right) {
            itemsBeforeOrigin.add(other)
            conflictingItems.add(other)
            when {
                compareIDs(item.origin, other.item.origin) -> {
                    if (other.item.id.client < item.id.client) {
                        left = other
                        conflictingItems.clear()
                    } else if (compareIDs(item.rightOrigin, other.item.rightOrigin)) {
                        break
                    }
                }
                other.item.origin != null -> {
                    val otherOrigin = find(other.item.origin)
                    if (otherOrigin !in itemsBeforeOrigin) break
                    if (otherOrigin !in conflictingItems) {
                        left = other
                        conflictingItems.clear()
                    }
                }
                else -> break
            }
            other = other.right
        }

        insertWithHint(left, item, hintedNode, hintedKind)
    }

    private fun insertWithHint(
        left: Node?,
        item: StoreItem,
        hintedNode: Node?,
        hintedKind: RootKind?,
    ) {
        val inserted = newNode(item)
        insertAfter(left, inserted)
        when {
            hintedKind == null -> invalidatePositionHint()
            item.visibleLength(hintedKind) == 0L -> Unit
            inserted.right === hintedNode -> {
                positionHintNode = inserted
                positionHintItemStart = positionHintIndex
            }
            else -> invalidatePositionHint()
        }
        materialized = null
    }

    fun replace(previous: StoreItem, replacements: List<StoreItem>, additionallyRemoved: List<StoreItem>) {
        invalidateVisibleEdges()
        invalidatePositionHint()
        replacements.forEach(::includeKind)
        val previousNode = nodesByStart[previous.id] ?: return
        val removedIds = additionallyRemoved.mapTo(hashSetOf()) { item -> item.id }
        val insertionLeft = previousNode.left
        var insertionRight = previousNode.right
        while (insertionRight?.item?.id in removedIds) insertionRight = insertionRight?.right

        detach(previousNode)
        additionallyRemoved.forEach { removed -> nodesByStart[removed.id]?.let(::detach) }

        var left = insertionLeft
        var treeIndex = if (insertionLeft == null) 0 else treeRank(insertionLeft) + 1
        replacements.forEach { replacement ->
            val node = newNode(replacement)
            nodesByStart[replacement.id] = node
            node.left = left
            node.right = insertionRight
            if (left == null) first = node else left.right = node
            insertionRight?.left = node
            if (insertionRight == null) last = node
            left = node
            insertTreeAt(treeIndex++, node)
        }
        if (replacements.isEmpty()) {
            if (insertionLeft == null) first = insertionRight else insertionLeft.right = insertionRight
            insertionRight?.left = insertionLeft
            if (insertionRight == null) last = insertionLeft
        }
        materialized = null
    }

    fun replaceItems(replacements: Map<Id, StoreItem>) {
        var changed = false
        replacements.forEach { (id, replacement) ->
            nodesByStart[id]?.let { node ->
                includeKind(replacement)
                node.item = replacement
                changed = true
            }
        }
        if (changed) materialized = null
    }

    private fun append(item: StoreItem) {
        val node = newNode(item)
        nodesByStart[item.id] = node
        node.left = last
        if (last == null) first = node else last?.right = node
        last = node
        insertTreeAt(treeRoot?.treeSize ?: 0, node)
    }

    private fun find(id: Id?): Node? {
        if (id == null) return null
        val containing = resolveStoreItem(id) ?: return null
        return nodesByStart[containing.id]
    }

    private fun insertAfter(left: Node?, node: Node) {
        val right = if (left == null) first else left.right
        node.left = left
        node.right = right
        if (left == null) first = node else left.right = node
        right?.left = node
        if (right == null) last = node
        nodesByStart[node.item.id] = node
        insertTreeAt(if (left == null) 0 else treeRank(left) + 1, node)
    }

    private fun detach(node: Node) {
        removeTreeNode(node)
        val left = node.left
        val right = node.right
        if (left == null) first = right else left.right = right
        if (right == null) last = left else right.left = left
        nodesByStart.remove(node.item.id)
        node.left = null
        node.right = null
    }

    private fun newNode(item: StoreItem): Node = Node(item, nextPriority()).also(::recalculate)

    private fun nextPriority(): Long {
        priorityState += -0x61C8864680B583EBL
        var mixed = priorityState
        mixed = (mixed xor (mixed ushr 30)) * -0x40A7B892E31B1A47L
        mixed = (mixed xor (mixed ushr 27)) * -0x6B2FB644ECCEEE15L
        return mixed xor (mixed ushr 31)
    }

    private fun visibleItemAt(
        index: Long,
        kind: RootKind?,
    ): Pair<StoreItem, Long>? {
        require(index >= 0) { "sequence index is out of bounds" }
        if (kind != null && positionHintKind == kind && positionHintIndex == index) {
            return positionHintNode?.item?.let { item -> item to positionHintItemStart }
        }
        if (index == 0L && kind != null) {
            return firstVisible(kind)?.let { item -> item to 0L }
        }
        var current = treeRoot
        var precedingLength = 0L
        while (current != null) {
            val leftLength = visibleLength(current.treeLeft, kind)
            val itemStart = checkedClockAdd(precedingLength, leftLength, "sequence position")
            val itemLength = current.item.visibleLength(kind)
            if (index < itemStart) {
                current = current.treeLeft
            } else if (itemLength > 0 && index < checkedClockAdd(itemStart, itemLength, "sequence item end")) {
                if (kind != null) {
                    positionHintKind = kind
                    positionHintIndex = index
                    positionHintItemStart = itemStart
                    positionHintNode = current
                }
                return current.item to itemStart
            } else {
                precedingLength = checkedClockAdd(itemStart, itemLength, "sequence position")
                current = current.treeRight
            }
        }
        return null
    }

    private fun invalidateVisibleEdges() {
        firstVisibleComputed.fill(false)
        firstVisibleByKind.fill(null)
    }

    private fun invalidatePositionHint() {
        positionHintKind = null
        positionHintIndex = -1
        positionHintItemStart = -1
        positionHintNode = null
    }

    private fun recalculateSubtree(node: Node?) {
        if (node == null) return
        recalculateSubtree(node.treeLeft)
        recalculateSubtree(node.treeRight)
        recalculate(node)
    }

    private fun visibleLengthForKind(node: Node?, kind: RootKind): Long =
        node?.treeVisibleLengths?.get(kind.ordinal) ?: 0L

    private fun visibleLength(node: Node?, kind: RootKind?): Long = if (kind == null) {
        node?.treeVisibleLengths?.fold(0L) { total, length ->
            checkedClockAdd(total, length, "sequence subtree length")
        } ?: 0L
    } else {
        visibleLengthForKind(node, kind)
    }

    private fun StoreItem.visibleLength(kind: RootKind?): Long =
        if (!deleted && countable && (kind == null || content.kind == kind)) length else 0L

    private fun includeKind(item: StoreItem) {
        usedKindMask = usedKindMask or (1 shl item.content.kind.ordinal)
    }

    private fun recalculate(node: Node) {
        node.treeSize = 1 + (node.treeLeft?.treeSize ?: 0) + (node.treeRight?.treeSize ?: 0)
        node.treeUndeletedCount =
            (node.treeLeft?.treeUndeletedCount ?: 0) +
            (if (node.item.deleted) 0 else 1) +
            (node.treeRight?.treeUndeletedCount ?: 0)
        var remainingKinds = usedKindMask
        while (remainingKinds != 0) {
            val kindIndex = Integer.numberOfTrailingZeros(remainingKinds)
            val kind = RootKind.entries[kindIndex]
            var length = node.treeLeft?.treeVisibleLengths?.get(kindIndex) ?: 0L
            if (!node.item.deleted && node.item.countable && node.item.content.kind.ordinal == kindIndex) {
                length = checkedClockAdd(length, node.item.length, "sequence node length")
            }
            node.treeVisibleLengths[kindIndex] = checkedClockAdd(
                length,
                node.treeRight?.treeVisibleLengths?.get(kindIndex) ?: 0L,
                "sequence subtree length",
            )
            remainingKinds = remainingKinds and (remainingKinds - 1)
        }
    }

    private fun setTreeLeft(parent: Node, child: Node?) {
        parent.treeLeft = child
        child?.treeParent = parent
    }

    private fun setTreeRight(parent: Node, child: Node?) {
        parent.treeRight = child
        child?.treeParent = parent
    }

    private fun mergeTrees(left: Node?, right: Node?): Node? = when {
        left == null -> right?.also { it.treeParent = null }
        right == null -> left.also { it.treeParent = null }
        left.priority >= right.priority -> {
            setTreeRight(left, mergeTrees(left.treeRight, right))
            recalculate(left)
            left.treeParent = null
            left
        }
        else -> {
            setTreeLeft(right, mergeTrees(left, right.treeLeft))
            recalculate(right)
            right.treeParent = null
            right
        }
    }

    /** Splits into the first [count] nodes and the remaining nodes. */
    private fun splitTree(root: Node?, count: Int): Pair<Node?, Node?> {
        if (root == null) return null to null
        val leftSize = root.treeLeft?.treeSize ?: 0
        return if (count <= leftSize) {
            val (left, middle) = splitTree(root.treeLeft, count)
            setTreeLeft(root, middle)
            recalculate(root)
            root.treeParent = null
            left?.treeParent = null
            left to root
        } else {
            val (middle, right) = splitTree(root.treeRight, count - leftSize - 1)
            setTreeRight(root, middle)
            recalculate(root)
            root.treeParent = null
            right?.treeParent = null
            root to right
        }
    }

    private fun insertTreeAt(index: Int, node: Node) {
        val (left, right) = splitTree(treeRoot, index)
        treeRoot = mergeTrees(mergeTrees(left, node), right)
        treeRoot?.treeParent = null
    }

    private fun treeRank(node: Node): Int {
        var rank = node.treeLeft?.treeSize ?: 0
        var current = node
        while (current.treeParent != null) {
            val parent = checkNotNull(current.treeParent)
            if (current === parent.treeRight) rank += 1 + (parent.treeLeft?.treeSize ?: 0)
            current = parent
        }
        return rank
    }

    private fun removeTreeNode(node: Node) {
        val rank = treeRank(node)
        val (left, remainder) = splitTree(treeRoot, rank)
        val (_, right) = splitTree(remainder, 1)
        treeRoot = mergeTrees(left, right)
        treeRoot?.treeParent = null
        node.treeLeft = null
        node.treeRight = null
        node.treeParent = null
        node.treeSize = 1
    }
}

private class SequenceNode(val item: StoreItem) {
    var left: SequenceNode? = null
    var right: SequenceNode? = null
}

private fun List<StoreItem>.findContainingIndex(clock: Long): Int {
    var low = 0
    var high = lastIndex
    while (low <= high) {
        val middle = (low + high) ushr 1
        val item = this[middle]
        when {
            clock < item.id.clock -> high = middle - 1
            clock >= item.endClock() -> low = middle + 1
            else -> return middle
        }
    }
    return -1
}

private fun List<StoreItem>.findStartIndex(clock: Long): Int {
    var low = 0
    var high = lastIndex
    while (low <= high) {
        val middle = (low + high) ushr 1
        when {
            clock < this[middle].id.clock -> high = middle - 1
            clock > this[middle].id.clock -> low = middle + 1
            else -> return middle
        }
    }
    return -1
}

private fun List<StoreItem>.findFirstStartingAtOrAfter(clock: Long): Int {
    var low = 0
    var high = size
    while (low < high) {
        val middle = (low + high) ushr 1
        if (this[middle].id.clock < clock) low = middle + 1 else high = middle
    }
    return low
}

private fun List<StoreItem>.findFirstEndingAfter(clock: Long): Int {
    var low = 0
    var high = size
    while (low < high) {
        val middle = (low + high) ushr 1
        if (this[middle].endClock() <= clock) low = middle + 1 else high = middle
    }
    return low
}

private fun StoreItem.endClock(): Long = checkedClockAdd(id.clock, length, "store item end")
