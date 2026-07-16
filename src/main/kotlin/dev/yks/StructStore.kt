package dev.yks

data class StructStoreIndex(
    val structs: List<AbstractStruct>,
    val index: Int,
)

data class PendingStructs(
    val missing: Map<Long, Long>,
    val update: ByteArray,
)

internal data class StructStoreSnapshot(
    val originalClientItems: MutableMap<Long, List<StoreItem>> = linkedMapOf(),
    val originalDeletedStates: MutableMap<StoreItem, Boolean> = java.util.IdentityHashMap(),
    val skips: IdSet,
    val version: Long,
    val sequenceBuildCount: Int,
)

class StructStore(private val owner: YDoc? = null) {
    private val clientItems: MutableMap<Long, MutableList<StoreItem>> = linkedMapOf()
    private val structViewOwner: YDoc by lazy(LazyThreadSafetyMode.NONE) { owner ?: YDoc() }
    private var allItemsCache: List<StoreItem>? = null
    private var deleteSetCache: DeleteSet? = null
    private var parentItemIdsCache: Map<String, Id>? = null
    private val sequenceCache: MutableMap<String, IndexedSequence> = mutableMapOf()
    private val mapEntriesCache: MutableMap<Pair<String, String>, List<StoreItem>> = mutableMapOf()
    private val mapOrderCache: MutableMap<Pair<String, String>, List<StoreItem>> = mutableMapOf()
    private val visibleLengths: MutableMap<String, LongArray> = mutableMapOf()
    private val visibleNativeTextFormats: MutableMap<String, MutableSet<Id>> = mutableMapOf()
    private val visibleTextCache: MutableMap<Pair<String, RootKind>, String> = mutableMapOf()
    private var lookupHintClient: Long = -1
    private var lookupHintIndex: Int = -1
    private var activeSnapshot: StructStoreSnapshot? = null

    internal val ownerDoc: YDoc? get() = owner
    internal var version: Long = 0
        private set
    internal var sequenceBuildCount: Int = 0
        private set

    val clients: Map<Long, List<ItemStruct>>
        get() {
            owner?.ensureThreadAccess()
            val viewOwner = structViewOwner
            return clientItems.mapValues { (_, structs) ->
                structs.map { item -> item.toItemStruct(viewOwner) }
            }
        }

    val ds: IdSet
        get() {
            owner?.ensureThreadAccess()
            return deleteSet().toIdSet()
        }

    var pendingStructs: PendingStructs?
        get() {
            owner?.ensureThreadAccess()
            return owner?.pendingStructsView()
        }
        set(value) {
            owner?.ensureThreadAccess()
            owner?.setPendingStructsView(value)
        }

    var pendingDs: ByteArray?
        get() {
            owner?.ensureThreadAccess()
            return owner?.pendingDeleteSetUpdate()
        }
        set(value) {
            owner?.ensureThreadAccess()
            owner?.setPendingDeleteSetUpdate(value)
        }

    val skips: IdSet = createIdSet()

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
        sequenceCache.clear()
        mapEntriesCache.clear()
        mapOrderCache.clear()
        visibleLengths.clear()
        visibleNativeTextFormats.clear()
        visibleTextCache.clear()
        clearLookupHint()
        clientItems.values.forEach { items -> items.forEach(::addDerivedState) }
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

    fun get(id: Id): AbstractStruct {
        owner?.ensureThreadAccess()
        val item = getStoreItem(id) ?: error("struct not found: $id")
        return item.toItemStruct(structViewOwner)
    }

    fun getItem(id: Id): ItemStruct = get(id) as ItemStruct

    fun getIndex(id: Id): StructStoreIndex {
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
            else -> error("only packed text or deleted store items can require splitting")
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
        candidates.asReversed().forEach { candidate ->
            val structs = clientItems[candidate.client] ?: return@forEach
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
        } else {
            val logicalOrder = owner?.mapItemOrder(left.parent, left.parentSub) ?: return false
            val logicalLeftIndex = logicalOrder.indexOfFirst { item -> item.id == left.id }
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

    fun getClock(client: Long): Long {
        owner?.ensureThreadAccess()
        val structs = clientItems[client] ?: return 0
        return structs.lastOrNull()?.endClock() ?: 0
    }

    fun stateVector(): StateVector {
        owner?.ensureThreadAccess()
        val state = linkedMapOf<Long, Long>()
        clientItems.forEach { (client, structs) ->
            val clock = structs.lastOrNull()?.endClock() ?: 0
            if (clock > 0) state[client] = clock
        }
        skips.clients.forEach { (client, ranges) ->
            ranges.minOfOrNull { range -> range.clock }?.let { clock -> state[client] = clock }
        }
        return state.toSortedMap()
    }

    fun integrityCheck() {
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
                        item.content is ItemContent.Deleted || item.content is ItemContent.Text -> {
                            val remaining = item.endClock() - targetClock
                            val content = when (val current = item.content) {
                                is ItemContent.Deleted -> current.copy(length = remaining)
                                is ItemContent.Text -> {
                                    val offset = (targetClock - item.id.clock)
                                        .toNonNegativeInt("text state-vector offset")
                                    current.copy(value = ContentString(current.value).splice(offset.toLong()).str)
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
        items.forEach { item ->
            if (!item.deleted) {
                captureDeletedBeforeMutation(item)
                removeDerivedState(item)
                item.deleted = true
                if (item.parentSub == null) sequenceCache[item.parent]?.refresh(item.id)
                deleteSetCache = null
                version++
                changed = true
            }
        }
        return changed
    }

    fun deleteSet(): DeleteSet {
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

    internal fun visibleItemsInRange(parent: String, index: Long, length: Long): List<StoreItem> =
        sequenceIndex(parent).visibleItemsInRange(index, length)

    internal fun hasVisibleNativeTextFormat(parent: String): Boolean {
        owner?.ensureThreadAccess()
        return visibleNativeTextFormats[parent]?.isNotEmpty() == true
    }

    internal fun visibleText(parent: String, kind: RootKind): String {
        owner?.ensureThreadAccess()
        return visibleTextCache.getOrPut(parent to kind) {
            buildString(visibleLength(parent, kind).toNonNegativeInt("visible text length")) {
                sequenceIndex(parent).forEach { item ->
                    if (!item.deleted && item.content.kind == kind) {
                        (item.content as? ItemContent.Text)?.let { content -> append(content.value) }
                    }
                }
            }
        }
    }

    internal fun lastSequenceItem(parent: String, kind: RootKind): StoreItem? {
        owner?.ensureThreadAccess()
        return sequenceIndex(parent).last { item -> item.content.kind == kind }
    }

    internal fun areSequenceAdjacent(left: StoreItem, right: StoreItem): Boolean =
        left.parentSub == null &&
            left.parent == right.parent &&
            sequenceIndex(left.parent).areAdjacent(left.id, right.id)

    internal fun sequenceNeighbors(item: StoreItem): Pair<StoreItem?, StoreItem?> =
        if (item.parentSub == null) sequenceIndex(item.parent).neighbors(item.id) else null to null

    private fun sequenceIndex(parent: String): IndexedSequence =
        sequenceCache.getOrPut(parent) {
            IndexedSequence(buildSequence(parent), ::getStoreItem)
        }

    private fun buildSequence(parent: String): List<StoreItem> {
        sequenceBuildCount++
        val items = allItems().filter { it.parent == parent && it.parentSub == null }
        if (items.size < 2) return items

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
        mapEntriesCache.getOrPut(parent to key) {
            allItems()
                .filter { it.parent == parent && it.parentSub == key }
                .sortedWith(compareBy<StoreItem> { it.id.clock }.thenBy { it.id.client })
        }

    internal fun cachedMapOrder(parent: String, key: String, compute: () -> List<StoreItem>): List<StoreItem> =
        mapOrderCache.getOrPut(parent to key, compute)

    private fun invalidateNonSequenceCaches(item: StoreItem) {
        allItemsCache = null
        deleteSetCache = null
        parentItemIdsCache = null
        item.parentSub?.let { key ->
            val cacheKey = item.parent to key
            mapEntriesCache.remove(cacheKey)
            mapOrderCache.remove(cacheKey)
        }
    }

    private fun recordAdded(item: StoreItem) {
        invalidateNonSequenceCaches(item)
        version++
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
        version++
        removeDerivedState(previous)
        additionallyRemoved.forEach(::removeDerivedState)
        replacements.forEach(::addDerivedState)
        sequenceCache[previous.parent]?.replace(previous, replacements, additionallyRemoved)
    }

    private fun addDerivedState(item: StoreItem) {
        if (item.parentSub == null) visibleTextCache.remove(item.parent to item.content.kind)
        if (item.parentSub != null || item.deleted) return
        if (item.countable) {
            val lengths = visibleLengths.getOrPut(item.parent) { LongArray(RootKind.entries.size) }
            val kindIndex = item.content.kind.ordinal
            lengths[kindIndex] = checkedClockAdd(lengths[kindIndex], item.length, "visible sequence length")
        }
        if (item.content is ItemContent.NativeTextFormat) {
            visibleNativeTextFormats.getOrPut(item.parent) { linkedSetOf() }.add(item.id)
        }
    }

    private fun removeDerivedState(item: StoreItem) {
        if (item.parentSub == null) visibleTextCache.remove(item.parent to item.content.kind)
        if (item.parentSub != null || item.deleted) return
        if (item.countable) {
            val lengths = checkNotNull(visibleLengths[item.parent]) { "missing visible length for ${item.parent}" }
            val kindIndex = item.content.kind.ordinal
            val next = lengths[kindIndex] - item.length
            check(next >= 0) { "visible sequence length underflow for ${item.parent}:${item.content.kind}" }
            lengths[kindIndex] = next
            if (lengths.all { length -> length == 0L }) visibleLengths.remove(item.parent)
        }
        if (item.content is ItemContent.NativeTextFormat) {
            visibleNativeTextFormats[item.parent]?.let { ids ->
                ids.remove(item.id)
                if (ids.isEmpty()) visibleNativeTextFormats.remove(item.parent)
            }
        }
    }

    internal fun mergeTextItems(ids: List<Id>): StoreItem? {
        if (ids.size < 2 || ids.any { id -> id.client != ids.first().client }) return null
        val structs = clientItems[ids.first().client] ?: return null
        val firstIndex = structs.findStartIndex(ids.first().clock)
        if (firstIndex < 0 || firstIndex + ids.size > structs.size) return null
        val items = structs.subList(firstIndex, firstIndex + ids.size).toList()
        if (items.map(StoreItem::id) != ids) return null
        val firstItem = items.first()
        val firstContent = firstItem.content as? ItemContent.Text ?: return null
        for (index in 1 until items.size) {
            val left = items[index - 1]
            val right = items[index]
            val content = right.content as? ItemContent.Text ?: return null
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
                firstItem.countable != right.countable ||
                firstContent.kind != content.kind ||
                firstContent.attributes != content.attributes ||
                firstContent.baseAttributes != content.baseAttributes
            ) return null
        }

        captureClientBeforeMutation(firstItem.id.client)
        clearLookupHint(firstItem.id.client)
        val mergedValueLength = items.sumOf(StoreItem::length).toNonNegativeInt("merged text length")
        val mergedValue = buildString(mergedValueLength) {
            items.forEach { item -> append((item.content as ItemContent.Text).value) }
        }
        val merged = firstItem.copy(content = firstContent.copy(value = mergedValue))
        structs[firstIndex] = merged
        structs.subList(firstIndex + 1, firstIndex + items.size).clear()
        recordReplacement(firstItem, listOf(merged), additionallyRemoved = items.drop(1))
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
        val treeVisibleLengths: LongArray = LongArray(RootKind.entries.size)
    }

    private val nodesByStart = hashMapOf<Id, Node>()
    private var first: Node? = null
    private var last: Node? = null
    private var treeRoot: Node? = null
    private var materialized: List<StoreItem>? = null
    private var priorityState: Long = 0x6A09E667F3BCC909L

    init {
        items.forEach(::append)
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

    fun refresh(id: Id) {
        var current = nodesByStart[id]
        while (current != null) {
            recalculate(current)
            current = current.treeParent
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
        var left = find(item.origin)
        val right = find(item.rightOrigin)
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

        insertAfter(left, newNode(item))
        materialized = null
    }

    fun replace(previous: StoreItem, replacements: List<StoreItem>, additionallyRemoved: List<StoreItem>) {
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
        var current = treeRoot
        var precedingLength = 0L
        while (current != null) {
            val leftLength = visibleLength(current.treeLeft, kind)
            val itemStart = checkedClockAdd(precedingLength, leftLength, "sequence position")
            val itemLength = current.item.visibleLength(kind)
            if (index < itemStart) {
                current = current.treeLeft
            } else if (itemLength > 0 && index < checkedClockAdd(itemStart, itemLength, "sequence item end")) {
                return current.item to itemStart
            } else {
                precedingLength = checkedClockAdd(itemStart, itemLength, "sequence position")
                current = current.treeRight
            }
        }
        return null
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

    private fun recalculate(node: Node) {
        node.treeSize = 1 + (node.treeLeft?.treeSize ?: 0) + (node.treeRight?.treeSize ?: 0)
        RootKind.entries.forEach { kind ->
            var length = visibleLengthForKind(node.treeLeft, kind)
            if (!node.item.deleted && node.item.countable && node.item.content.kind == kind) {
                length = checkedClockAdd(length, node.item.length, "sequence node length")
            }
            node.treeVisibleLengths[kind.ordinal] = checkedClockAdd(
                length,
                visibleLengthForKind(node.treeRight, kind),
                "sequence subtree length",
            )
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
