package dev.yks

/** Clock bounds carried by the struct section of an update. Delete sets are not included. */
public data class UpdateMeta(
    val from: StateVector,
    val to: StateVector,
)

public fun parseUpdateMeta(update: ByteArray): UpdateMeta =
    UpdateCodec.parseMeta(update)

public fun parseUpdateMetaV2(update: ByteArray): UpdateMeta =
    UpdateCodec.parseMetaV2(update)

internal fun DocumentUpdate.toUpdateMeta(): UpdateMeta {
    val from = linkedMapOf<Long, Long>()
    val to = linkedMapOf<Long, Long>()
    items.forEach { item ->
        from[item.id.client] = minOf(from[item.id.client] ?: item.id.clock, item.id.clock)
        val end = checkedClockAdd(item.id.clock, item.length)
        to[item.id.client] = maxOf(to[item.id.client] ?: end, end)
    }
    return UpdateMeta(from.toSortedMap(), to.toSortedMap())
}
