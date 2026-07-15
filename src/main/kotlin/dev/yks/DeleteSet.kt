package dev.yks

data class DeleteRange(val clock: Long, val length: Long) {
    init {
        require(clock >= 0) { "clock must be non-negative" }
        require(length > 0) { "length must be positive" }
    }

    val end: Long = checkedClockAdd(clock, length, "delete range end")
    val len: Long get() = length

    fun contains(clock: Long): Boolean = clock >= this.clock && clock < end
}

class DeleteSet internal constructor(
    val clients: MutableMap<Long, MutableList<DeleteRange>> = linkedMapOf(),
) {
    val isEmpty: Boolean get() = clients.values.all { it.isEmpty() }

    fun add(id: Id, length: Long = 1) {
        require(length > 0) { "length must be positive" }
        clients.getOrPut(id.client) { mutableListOf() }.addAndMerge(DeleteRange(id.clock, length))
    }

    fun addAll(other: DeleteSet) {
        other.clients.forEach { (client, ranges) ->
            ranges.toList().forEach { range -> add(Id(client, range.clock), range.length) }
        }
    }

    fun contains(id: Id): Boolean = clients[id.client]?.containsClock(id.clock) == true

    fun hasId(id: Id): Boolean = contains(id)

    fun rangesFor(client: Long): List<DeleteRange> = clients[client].orEmpty()

    fun ranges(): List<Pair<Long, IdRange>> = clients
        .toSortedMap()
        .flatMap { (client, ranges) -> ranges.map { range -> client to IdRange(range.clock, range.length) } }

    fun toIdSet(): IdSet {
        val idSet = createIdSet()
        clients.forEach { (client, ranges) ->
            ranges.forEach { range -> idSet.add(client, range.clock, range.length) }
        }
        return idSet
    }

    internal fun copy(): DeleteSet {
        val copied = linkedMapOf<Long, MutableList<DeleteRange>>()
        clients.forEach { (client, ranges) -> copied[client] = ranges.toMutableList() }
        return DeleteSet(copied)
    }

    internal fun structurallyEquals(other: DeleteSet): Boolean {
        val left = clients.filterValues { it.isNotEmpty() }
        val right = other.clients.filterValues { it.isNotEmpty() }
        return left == right
    }

    companion object {
        fun empty(): DeleteSet = DeleteSet()
    }
}

private fun MutableList<DeleteRange>.addAndMerge(incoming: DeleteRange) {
    var low = 0
    var high = size
    while (low < high) {
        val middle = (low + high) ushr 1
        if (this[middle].end < incoming.clock) low = middle + 1 else high = middle
    }

    val firstAffected = low
    var mergedStart = incoming.clock
    var mergedEnd = incoming.end
    while (low < size && this[low].clock <= mergedEnd) {
        mergedStart = minOf(mergedStart, this[low].clock)
        mergedEnd = maxOf(mergedEnd, this[low].end)
        low++
    }

    if (firstAffected < low) subList(firstAffected, low).clear()
    add(firstAffected, DeleteRange(mergedStart, mergedEnd - mergedStart))
}

private fun List<DeleteRange>.containsClock(clock: Long): Boolean {
    var low = 0
    var high = lastIndex
    while (low <= high) {
        val middle = (low + high) ushr 1
        val range = this[middle]
        when {
            clock < range.clock -> high = middle - 1
            clock >= range.end -> low = middle + 1
            else -> return true
        }
    }
    return false
}

fun createDeleteSet(): DeleteSet = DeleteSet.empty()

fun createDeleteSetFromStructStore(store: StructStore): DeleteSet = store.deleteSet()

fun createDeleteSetFromStructStore(doc: YDoc): DeleteSet = createDeleteSetFromStructStore(doc.store)

fun equalDeleteSets(left: DeleteSet, right: DeleteSet): Boolean = left.structurallyEquals(right)

fun mergeDeleteSets(deleteSets: List<DeleteSet>): DeleteSet = createDeleteSet().also { merged ->
    deleteSets.forEach(merged::addAll)
}

fun isDeleted(deleteSet: DeleteSet, id: Id): Boolean = deleteSet.contains(id)

fun isDeleted(deleteSet: DeleteSet, client: Long, clock: Long): Boolean =
    isDeleted(deleteSet, Id(client, clock))

fun IdSet.toDeleteSet(): DeleteSet {
    val deleteSet = createDeleteSet()
    ranges().forEach { (client, range) ->
        deleteSet.add(Id(client, range.clock), range.len)
    }
    return deleteSet
}
