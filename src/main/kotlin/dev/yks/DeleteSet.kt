package dev.yks

public data class DeleteRange(val clock: Long, val length: Long) {
    init {
        require(clock >= 0) { "clock must be non-negative" }
        require(length > 0) { "length must be positive" }
    }

    val end: Long = checkedClockAdd(clock, length, "delete range end")
    val len: Long get() = length

    public fun contains(clock: Long): Boolean = clock >= this.clock && clock < end
}

public class DeleteSet internal constructor(
    clients: Map<Long, List<DeleteRange>> = emptyMap(),
) {
    private val clientRanges: MutableMap<Long, MutableList<DeleteRange>> = linkedMapOf()

    init {
        clients.forEach { (client, ranges) ->
            require(client >= 0) { "client must be non-negative" }
            ranges.forEach { range -> add(Id(client, range.clock), range.length) }
        }
    }

    public val clients: Map<Long, List<DeleteRange>>
        get() = clientRanges.mapValues { (_, ranges) -> ranges.toList() }

    public val isEmpty: Boolean get() = clientRanges.values.all { it.isEmpty() }

    public fun add(id: Id, length: Long = 1) {
        require(length > 0) { "length must be positive" }
        clientRanges.getOrPut(id.client) { mutableListOf() }.addAndMerge(DeleteRange(id.clock, length))
    }

    public fun addAll(other: DeleteSet) {
        other.clients.forEach { (client, ranges) ->
            ranges.toList().forEach { range -> add(Id(client, range.clock), range.length) }
        }
    }

    public fun contains(id: Id): Boolean = clientRanges[id.client]?.containsClock(id.clock) == true

    public fun hasId(id: Id): Boolean = contains(id)

    public fun rangesFor(client: Long): List<DeleteRange> = clientRanges[client]?.toList().orEmpty()

    internal fun rangesForTraversal(client: Long): List<DeleteRange> = clientRanges[client].orEmpty()

    public fun ranges(): List<Pair<Long, IdRange>> = clientRanges
        .toSortedMap()
        .flatMap { (client, ranges) -> ranges.map { range -> client to IdRange(range.clock, range.length) } }

    public fun toIdSet(): IdSet {
        val idSet = createIdSet()
        clientRanges.forEach { (client, ranges) ->
            ranges.forEach { range -> idSet.add(client, range.clock, range.length) }
        }
        return idSet
    }

    internal fun copy(): DeleteSet {
        val copied = linkedMapOf<Long, MutableList<DeleteRange>>()
        clientRanges.forEach { (client, ranges) -> copied[client] = ranges.toMutableList() }
        return DeleteSet(copied)
    }

    internal fun structurallyEquals(other: DeleteSet): Boolean {
        val left = clientRanges.filterValues { it.isNotEmpty() }
        val right = other.clientRanges.filterValues { it.isNotEmpty() }
        return left == right
    }

    internal fun clear() {
        clientRanges.clear()
    }

    internal fun rangeCount(): Int = clientRanges.values.sumOf { ranges -> ranges.size }

    public companion object {
        public fun empty(): DeleteSet = DeleteSet()
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

public fun createDeleteSet(): DeleteSet = DeleteSet.empty()

public fun createDeleteSetFromStructStore(store: StructStore): DeleteSet = store.deleteSet()

public fun createDeleteSetFromStructStore(doc: YDoc): DeleteSet = createDeleteSetFromStructStore(doc.store)

public fun equalDeleteSets(left: DeleteSet, right: DeleteSet): Boolean = left.structurallyEquals(right)

public fun mergeDeleteSets(deleteSets: List<DeleteSet>): DeleteSet = createDeleteSet().also { merged ->
    deleteSets.forEach(merged::addAll)
}

public fun isDeleted(deleteSet: DeleteSet, id: Id): Boolean = deleteSet.contains(id)

public fun isDeleted(deleteSet: DeleteSet, client: Long, clock: Long): Boolean =
    isDeleted(deleteSet, Id(client, clock))

public fun IdSet.toDeleteSet(): DeleteSet {
    val deleteSet = createDeleteSet()
    ranges().forEach { (client, range) ->
        deleteSet.add(Id(client, range.clock), range.len)
    }
    return deleteSet
}
