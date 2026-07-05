package dev.yks

data class DeleteRange(val clock: Long, val length: Long) {
    init {
        require(clock >= 0) { "clock must be non-negative" }
        require(length > 0) { "length must be positive" }
    }

    val end: Long get() = clock + length

    fun contains(clock: Long): Boolean = clock >= this.clock && clock < end
}

class DeleteSet internal constructor(
    internal val clients: MutableMap<Long, MutableList<DeleteRange>> = linkedMapOf(),
) {
    val isEmpty: Boolean get() = clients.values.all { it.isEmpty() }

    fun add(id: Id, length: Long = 1) {
        require(length > 0) { "length must be positive" }
        clients.getOrPut(id.client) { mutableListOf() }.add(DeleteRange(id.clock, length))
        normalize(id.client)
    }

    fun addAll(other: DeleteSet) {
        other.clients.forEach { (client, ranges) ->
            clients.getOrPut(client) { mutableListOf() }.addAll(ranges)
            normalize(client)
        }
    }

    fun contains(id: Id): Boolean = clients[id.client]?.any { it.contains(id.clock) } == true

    fun rangesFor(client: Long): List<DeleteRange> = clients[client].orEmpty()

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

    private fun normalize(client: Long) {
        val ranges = clients[client] ?: return
        if (ranges.isEmpty()) return
        ranges.sortWith(compareBy<DeleteRange> { it.clock }.thenBy { it.length })
        val merged = mutableListOf<DeleteRange>()
        for (range in ranges) {
            val last = merged.lastOrNull()
            if (last == null || range.clock > last.end) {
                merged.add(range)
            } else {
                merged[merged.lastIndex] = DeleteRange(last.clock, maxOf(last.end, range.end) - last.clock)
            }
        }
        clients[client] = merged
    }

    companion object {
        fun empty(): DeleteSet = DeleteSet()
    }
}

fun IdSet.toDeleteSet(): DeleteSet {
    val deleteSet = DeleteSet.empty()
    ranges().forEach { (client, range) ->
        deleteSet.add(Id(client, range.clock), range.len)
    }
    return deleteSet
}
