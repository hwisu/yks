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
        val children = linkedMapOf<Id?, MutableList<StoreItem>>()
        items.forEach { item -> children.getOrPut(item.origin) { mutableListOf() }.add(item) }
        children.values.forEach { sortSiblings(it) }

        val ordered = mutableListOf<StoreItem>()
        val stack = ArrayDeque<StoreItem>()
        children[null].orEmpty().asReversed().forEach(stack::addLast)
        while (stack.isNotEmpty()) {
            val child = stack.removeLast()
            ordered.add(child)
            val nestedChildren = children[child.id].orEmpty()
            for (index in nestedChildren.lastIndex downTo 0) {
                stack.addLast(nestedChildren[index])
            }
        }
        return ordered
    }

    internal fun mapEntries(parent: String, key: String): List<StoreItem> = allItems()
        .filter { it.parent == parent && it.parentSub == key }
        .sortedWith(compareBy<StoreItem> { it.id.clock }.thenBy { it.id.client })

    private fun sortSiblings(items: MutableList<StoreItem>) {
        val byId = items.associateBy { it.id }
        val outgoing = items.associateWith { mutableSetOf<StoreItem>() }
        val incoming = items.associateWith { 0 }.toMutableMap()

        for (item in items) {
            val right = item.rightOrigin?.let(byId::get)
            if (right != null && outgoing.getValue(item).add(right)) {
                incoming[right] = incoming.getValue(right) + 1
            }
        }

        val ready = items.filter { incoming.getValue(it) == 0 }.sortedBy { it.id }.toMutableList()
        val sorted = mutableListOf<StoreItem>()
        while (ready.isNotEmpty()) {
            val next = ready.removeAt(0)
            sorted.add(next)
            for (target in outgoing.getValue(next).sortedBy { it.id }) {
                incoming[target] = incoming.getValue(target) - 1
                if (incoming.getValue(target) == 0) {
                    ready.add(target)
                    ready.sortBy { it.id }
                }
            }
        }

        if (sorted.size != items.size) {
            items.sortBy { it.id }
        } else {
            items.clear()
            items.addAll(sorted)
        }
    }
}
