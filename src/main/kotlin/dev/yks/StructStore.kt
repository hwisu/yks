package dev.yks

data class StructStoreIndex(
    val structs: List<AbstractStruct>,
    val index: Int,
)

data class PendingStructs(
    val missing: Map<Long, Long>,
    val update: ByteArray,
)

class StructStore(private val owner: YDoc? = null) {
    private val clientItems: MutableMap<Long, MutableList<StoreItem>> = linkedMapOf()

    internal val ownerDoc: YDoc? get() = owner

    val clients: Map<Long, List<ItemStruct>>
        get() = clientItems.mapValues { (_, structs) ->
            structs.map { item -> item.toItemStruct(owner ?: YDoc()) }
        }

    val ds: IdSet
        get() = deleteSet().toIdSet()

    var pendingStructs: PendingStructs?
        get() = owner?.pendingStructsView()
        set(value) {
            owner?.setPendingStructsView(value)
        }

    var pendingDs: ByteArray?
        get() = owner?.pendingDeleteSetUpdate()
        set(value) {
            owner?.setPendingDeleteSetUpdate(value)
        }

    val skips: IdSet = createIdSet()

    internal fun add(item: StoreItem): Boolean {
        val structs = clientItems.getOrPut(item.id.client) { mutableListOf() }
        if (structs.isEmpty() || structs.last().endClock() <= item.id.clock) {
            structs.add(item)
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
        structs.add(insertionIndex, item)
        return true
    }

    fun get(id: Id): AbstractStruct {
        val item = getStoreItem(id) ?: error("struct not found: $id")
        return item.toItemStruct(owner ?: YDoc())
    }

    fun getItem(id: Id): ItemStruct = get(id) as ItemStruct

    fun getIndex(id: Id): StructStoreIndex {
        val structs = clients[id.client].orEmpty()
        val index = findIndexSS(structs, id.clock)
        return StructStoreIndex(structs, index)
    }

    internal fun getStoreItem(id: Id): StoreItem? =
        clientItems[id.client]?.firstOrNull { id.clock >= it.id.clock && id.clock < it.endClock() }

    internal fun getStoreItemCleanStart(
        id: Id,
        onSplit: (StoreItem) -> Unit = {},
    ): StoreItem {
        val structs = clientItems[id.client] ?: error("struct not found: $id")
        val index = structs.indexOfFirst { item -> id.clock >= item.id.clock && id.clock < item.endClock() }
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
        val index = structs.indexOfFirst { item -> id.clock >= item.id.clock && id.clock < item.endClock() }
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
        require(diff in 1 until item.length) { "diff must split the store item" }
        val content = item.content as? ItemContent.Deleted
            ?: error("only packed deleted store items can require splitting")
        val left = item.copy(content = content.copy(length = diff))
        val right = item.copy(
            id = Id(item.id.client, checkedClockAdd(item.id.clock, diff, "split item clock")),
            origin = Id(item.id.client, checkedClockAdd(item.id.clock, diff - 1, "split item origin")),
            content = content.copy(length = item.length - diff),
        )
        structs[index] = left
        structs.add(index + 1, right)
        return right
    }

    /** Merge compatible deleted-item fragments touched by a delete-set, from right to left. */
    internal fun mergeDeletedItems(deleteSet: DeleteSet): Int {
        var merged = 0
        deleteSet.clients.forEach { (client, ranges) ->
            val structs = clientItems[client] ?: return@forEach
            if (structs.size < 2 || ranges.isEmpty()) return@forEach
            var index = structs.lastIndex
            while (index > 0) {
                val boundary = structs[index].id.clock
                val touchesDeleteRange = ranges.any { range ->
                    boundary >= range.clock && boundary <= range.end
                }
                if (touchesDeleteRange && mergeDeletedItemWithLeft(structs, index)) {
                    merged++
                }
                index--
            }
        }
        return merged
    }

    /** Re-merge temporary transaction splits in reverse split order, as upstream Yjs does. */
    internal fun mergeSplitCandidates(candidates: List<Id>): Int {
        var merged = 0
        candidates.asReversed().forEach { candidate ->
            val structs = clientItems[candidate.client] ?: return@forEach
            val index = structs.indexOfFirst { item -> item.id == candidate }
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
        val logicalOrder = if (left.parentSub == null) {
            owner?.sequence(left.parent)
        } else {
            owner?.mapItemOrder(left.parent, left.parentSub)
        } ?: return false
        val logicalLeftIndex = logicalOrder.indexOfFirst { item -> item.id == left.id }
        val logicallyAdjacent = logicalLeftIndex >= 0 && logicalOrder.getOrNull(logicalLeftIndex + 1)?.id == right.id
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

        val mergedLength = checkedClockAdd(left.length, right.length, "merged deleted item length")
        structs[rightIndex - 1] = left.copy(content = leftContent.copy(length = mergedLength))
        structs.removeAt(rightIndex)
        return true
    }

    internal fun contains(id: Id): Boolean = getStoreItem(id) != null

    internal fun collectItemContent(id: Id): StoreItem? {
        val structs = clientItems[id.client] ?: return null
        val index = structs.indexOfFirst { item -> id.clock >= item.id.clock && id.clock < item.endClock() }
        if (index < 0) return null
        val item = structs[index]
        if (!item.deleted) return null
        if (item.content is ItemContent.Deleted) return null
        val collected = item.copy(content = ItemContent.Deleted(item.content.kind, item.length))
        structs[index] = collected
        return collected
    }

    internal fun replaceContent(id: Id, content: ItemContent): StoreItem? {
        val structs = clientItems[id.client] ?: return null
        val index = structs.indexOfFirst { item -> item.id == id }
        if (index < 0) return null
        val updated = structs[index].copy(content = content)
        structs[index] = updated
        return updated
    }

    fun getClock(client: Long): Long {
        val structs = clientItems[client] ?: return 0
        return structs.maxOfOrNull(StoreItem::endClock) ?: 0
    }

    fun stateVector(): StateVector {
        val state = linkedMapOf<Long, Long>()
        clientItems.forEach { (client, structs) ->
            val clock = structs.maxOfOrNull(StoreItem::endClock) ?: 0
            if (clock > 0) state[client] = clock
        }
        skips.clients.forEach { (client, ranges) ->
            ranges.minOfOrNull { range -> range.clock }?.let { clock -> state[client] = clock }
        }
        return state.toSortedMap()
    }

    fun integrityCheck() {
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

    internal fun allItems(): List<StoreItem> =
        clientItems.values.flatten().sortedWith(compareBy<StoreItem> { it.id.client }.thenBy { it.id.clock })

    internal fun parentItemIds(): Map<String, Id> = allItems().mapNotNull { item ->
        item.content.directTypeRef()?.name?.let { name -> name to item.id }
    }.toMap()

    internal fun parentKinds(): Map<String, RootKind> = owner?.knownParentKinds().orEmpty()

    internal fun itemsSince(stateVector: StateVector): List<StoreItem> = allItems().mapNotNull { item ->
        val targetClock = stateVector[item.id.client] ?: 0
        when {
            item.endClock() <= targetClock -> null
            item.id.clock >= targetClock -> item
            item.content is ItemContent.Deleted -> {
                val remaining = item.endClock() - targetClock
                item.copy(
                    id = Id(item.id.client, targetClock),
                    origin = if (item.isGc) null else Id(item.id.client, targetClock - 1),
                    content = item.content.copy(length = remaining),
                )
            }
            else -> error("state vector splits unsupported store item at ${item.id}:$targetClock")
        }
    }

    internal fun markDeleted(deleteSet: DeleteSet): Boolean {
        var changed = false
        deleteSet.clients.forEach { (client, ranges) ->
            clientItems[client]?.forEach { item ->
                if (!item.deleted && ranges.any { it.contains(item.id.clock) }) {
                    item.deleted = true
                    changed = true
                }
            }
        }
        return changed
    }

    fun deleteSet(): DeleteSet {
        val deleteSet = DeleteSet.empty()
        allItems().forEach { item ->
            if (item.deleted) {
                deleteSet.add(item.id, item.length)
            }
        }
        return deleteSet
    }

    internal fun sequence(parent: String): List<StoreItem> {
        val items = allItems().filter { it.parent == parent && it.parentSub == null }
        if (items.size < 2) return items

        // Rebuild the linked sequence using the same conflict scan as Item.integrate in Yjs.
        // A simple origin-child traversal is insufficient: an item's rightOrigin may constrain
        // it relative to items from a different origin subtree.
        val nodes = items.map(::SequenceNode)
        val nodesByClient = nodes.groupBy { node -> node.item.id.client }
            .mapValues { (_, clientNodes) -> clientNodes.sortedBy { node -> node.item.id.clock } }

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

    internal fun mapEntries(parent: String, key: String): List<StoreItem> = allItems()
        .filter { it.parent == parent && it.parentSub == key }
        .sortedWith(compareBy<StoreItem> { it.id.clock }.thenBy { it.id.client })

    private fun StoreItem.contains(id: Id): Boolean =
        id.client == this.id.client && id.clock >= this.id.clock && id.clock < endClock()
}

private class SequenceNode(val item: StoreItem) {
    var left: SequenceNode? = null
    var right: SequenceNode? = null
}

private fun StoreItem.endClock(): Long = checkedClockAdd(id.clock, length, "store item end")
