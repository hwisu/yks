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
        if (structs.any { it.id.clock == item.id.clock }) {
            return false
        }
        structs.add(item)
        structs.sortBy { it.id.clock }
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
        clientItems[id.client]?.firstOrNull { id.clock >= it.id.clock && id.clock < it.id.clock + it.length }

    internal fun contains(id: Id): Boolean = getStoreItem(id) != null

    internal fun collectItemContent(id: Id): StoreItem? {
        val structs = clientItems[id.client] ?: return null
        val index = structs.indexOfFirst { item -> id.clock >= item.id.clock && id.clock < item.id.clock + item.length }
        if (index < 0) return null
        val item = structs[index]
        if (!item.deleted) return null
        if (item.content is ItemContent.Deleted) return null
        val collected = item.copy(content = ItemContent.Deleted(item.content.kind))
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
        return structs.maxOfOrNull { it.id.clock + it.length } ?: 0
    }

    fun stateVector(): StateVector {
        val state = linkedMapOf<Long, Long>()
        clientItems.forEach { (client, structs) ->
            val clock = structs.maxOfOrNull { it.id.clock + it.length } ?: 0
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
                check(left.id.clock + left.length == right.id.clock) {
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

    internal fun itemsSince(stateVector: StateVector): List<StoreItem> = allItems().filter { item ->
        item.id.clock >= (stateVector[item.id.client] ?: 0)
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
        val itemIds = items.mapTo(hashSetOf()) { item -> item.id }
        val remaining = items.sortedBy { item -> item.id }.toMutableList()
        val ordered = mutableListOf<StoreItem>()
        val integratedIds = hashSetOf<Id>()

        while (remaining.isNotEmpty()) {
            val nextIndex = remaining.indexOfFirst { item ->
                val anchorsIntegrated =
                    (item.origin !in itemIds || item.origin in integratedIds) &&
                        (item.rightOrigin !in itemIds || item.rightOrigin in integratedIds)
                val earlierClientItemsIntegrated = items.none { candidate ->
                    candidate.id.client == item.id.client &&
                        candidate.id.clock < item.id.clock &&
                        candidate.id !in integratedIds
                }
                anchorsIntegrated && earlierClientItemsIntegrated
            }
            // Valid Yjs updates are acyclic. Keep malformed/private legacy data deterministic
            // instead of looping forever if it contains a dependency cycle.
            val item = remaining.removeAt(if (nextIndex >= 0) nextIndex else 0)
            insertSequenceItem(ordered, item)
            integratedIds.add(item.id)
        }
        return ordered
    }

    internal fun mapEntries(parent: String, key: String): List<StoreItem> = allItems()
        .filter { it.parent == parent && it.parentSub == key }
        .sortedWith(compareBy<StoreItem> { it.id.clock }.thenBy { it.id.client })

    private fun insertSequenceItem(ordered: MutableList<StoreItem>, item: StoreItem) {
        var leftIndex = item.origin?.let { origin ->
            ordered.indexOfFirst { existing -> existing.id == origin }.takeIf { index -> index >= 0 }
        } ?: -1
        var scanIndex = leftIndex + 1
        val conflictingItems = hashSetOf<Id>()
        val itemsBeforeOrigin = hashSetOf<Id>()

        while (scanIndex < ordered.size) {
            val other = ordered[scanIndex]
            if (compareIDs(other.id, item.rightOrigin)) break

            itemsBeforeOrigin.add(other.id)
            conflictingItems.add(other.id)
            when {
                compareIDs(item.origin, other.origin) -> {
                    if (other.id.client < item.id.client) {
                        leftIndex = scanIndex
                        conflictingItems.clear()
                    } else if (compareIDs(item.rightOrigin, other.rightOrigin)) {
                        break
                    }
                }
                other.origin != null && other.origin in itemsBeforeOrigin -> {
                    if (other.origin !in conflictingItems) {
                        leftIndex = scanIndex
                        conflictingItems.clear()
                    }
                }
                else -> break
            }
            scanIndex++
        }

        ordered.add(leftIndex + 1, item)
    }
}
