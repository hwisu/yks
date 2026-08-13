package dev.yks

public class BlockRange(
    public var refs: MutableList<AbstractStruct>,
    public var i: Int = 0,
)

public class BlockSet(
    public val clients: MutableMap<Long, BlockRange> = linkedMapOf(),
) {
    public fun toIdSet(): IdSet {
        val inserts = createIdSet()
        clients.toSortedMap().forEach { (client, range) ->
            var lastClock = 0L
            var lastLen = 0L
            range.refs.forEach { block ->
                if (block is Skip) return@forEach
                if (lastClock + lastLen == block.id.clock) {
                    lastLen += block.length
                } else {
                    inserts.add(client, lastClock, lastLen)
                    lastClock = block.id.clock
                    lastLen = block.length
                }
            }
            inserts.add(client, lastClock, lastLen)
        }
        return inserts
    }

    public fun exclude(exclude: IdSet) {
        val clientIds = if (clients.size < exclude.clients.size) clients.keys.toList() else exclude.clients.keys.toList()
        clientIds.forEach { client ->
            val ranges = exclude.ranges(client)
            val structs = clients[client]?.refs ?: return@forEach
            if (ranges.isEmpty() || structs.isEmpty()) return@forEach
            ranges.forEach { range ->
                if (structs.isEmpty()) return@forEach
                val firstStruct = structs.first()
                val lastStruct = structs.last()
                if (range.clock >= lastStruct.end || range.end <= firstStruct.id.clock) return@forEach
                val startIndex = if (range.clock > firstStruct.id.clock) {
                    splitBlockRangeAt(structs, range.clock)
                } else {
                    0
                }
                val endIndex = if (range.end < structs.last().end) {
                    splitBlockRangeAt(structs, range.end)
                } else {
                    structs.size
                }
                if (startIndex < endIndex) {
                    structs[startIndex] = Skip(Id(client, range.clock), range.len)
                    if (endIndex - startIndex > 1) {
                        structs.subList(startIndex + 1, endIndex).clear()
                    }
                }
            }
        }
    }

    public fun insertInto(inserts: BlockSet) {
        inserts.clients.forEach { (client, newRange) ->
            val range = clients[client]
            if (range == null) {
                clients[client] = newRange
                return@forEach
            }
            if (range.refs.isEmpty()) {
                range.refs = newRange.refs
                return@forEach
            }
            if (newRange.refs.isEmpty()) {
                return@forEach
            }
            val localIsLeft = range.refs.first().id.clock < newRange.refs.first().id.clock
            val leftRefs = if (localIsLeft) range.refs else newRange.refs
            val rightRefs = if (localIsLeft) newRange.refs else range.refs
            val lastLeft = leftRefs.last()
            val firstRight = rightRefs.first()
            val gapSize = firstRight.id.clock - lastLeft.end
            range.refs = if (gapSize >= 0) {
                leftRefs.apply {
                    if (gapSize > 0) add(Skip(Id(client, lastLeft.end), gapSize))
                    addAll(rightRefs)
                }
            } else {
                mergeOverlappingBlockRefs(client, leftRefs, rightRefs)
            }
        }
        inserts.clients.clear()
    }
}

public fun readBlockSet(decoder: UpdateDecoderV1): BlockSet {
    val clients = linkedMapOf<Long, BlockRange>()
    repeat(decoder.restDecoder.readVarUInt().toDecodedCount()) {
        val client = decoder.readClient()
        val refs = buildDecodedList(decoder.restDecoder.readVarUInt().toDecodedCount()) {
            readBlockSetStruct(decoder)
        }
        clients[client] = BlockRange(refs)
    }
    return BlockSet(clients)
}

public fun readBlockSet(decoder: UpdateDecoderV2): BlockSet {
    val clients = linkedMapOf<Long, BlockRange>()
    repeat(decoder.restDecoder.readVarUInt().toDecodedCount()) {
        val client = decoder.readClient()
        val refs = buildDecodedList(decoder.restDecoder.readVarUInt().toDecodedCount()) {
            readBlockSetStruct(decoder)
        }
        clients[client] = BlockRange(refs)
    }
    return BlockSet(clients)
}

public fun writeBlockSet(encoder: UpdateEncoderV1, blocks: BlockSet) {
    encoder.restEncoder.writeVarUInt(blocks.clients.size.toLong())
    blocks.clients.toSortedMap(compareByDescending { it }).forEach { (client, range) ->
        encoder.writeClient(client)
        encoder.restEncoder.writeVarUInt(range.refs.size.toLong())
        range.refs.forEach { struct -> writeBlockSetStruct(encoder, struct) }
    }
}

public fun writeBlockSet(encoder: UpdateEncoderV2, blocks: BlockSet) {
    encoder.restEncoder.writeVarUInt(blocks.clients.size.toLong())
    blocks.clients.toSortedMap(compareByDescending { it }).forEach { (client, range) ->
        encoder.writeClient(client)
        encoder.restEncoder.writeVarUInt(range.refs.size.toLong())
        range.refs.forEach { struct -> writeBlockSetStruct(encoder, struct) }
    }
}

public fun sliceStruct(left: AbstractStruct, diff: Long): AbstractStruct {
    require(diff >= 0) { "diff must be non-negative" }
    require(diff < left.length) { "diff must be smaller than struct length" }
    val id = Id(left.id.client, checkedClockAdd(left.id.clock, diff, "sliced struct clock"))
    val length = left.length - diff
    return when (left) {
        is GC -> GC(id, length)
        is Skip -> Skip(id, length)
        is ItemStruct -> {
            val content = left.content.copy()
            val rightContent = if (diff == 0L) content else content.splice(diff)
            left.copy(
                id = id,
                length = length,
                origin = if (diff == 0L) left.origin else Id(left.id.client, id.clock - 1),
                content = rightContent,
            )
        }
        else -> left.splice(diff)
    }
}

private fun splitBlockRangeAt(structs: MutableList<AbstractStruct>, clock: Long): Int {
    val index = findIndexSS(structs, clock)
    val struct = structs[index]
    if (struct.id.clock == clock) return index
    val right = splitStruct(struct, clock - struct.id.clock)
    structs.add(index + 1, right)
    return index + 1
}

private fun mergeOverlappingBlockRefs(
    client: Long,
    leftRefs: List<AbstractStruct>,
    rightRefs: List<AbstractStruct>,
): MutableList<AbstractStruct> {
    val allRefs = leftRefs + rightRefs
    if (allRefs.isEmpty()) return mutableListOf()
    val result = mutableListOf<AbstractStruct>()
    var nextExpectedClock = allRefs.minOf { it.id.clock }
    val candidates = allRefs
        .withIndex()
        .asSequence()
        .filterNot { indexed -> indexed.value is Skip }
        .sortedWith(compareBy<IndexedValue<AbstractStruct>> { indexed -> indexed.value.id.clock }.thenBy { it.index })
        .map { indexed -> indexed.value }
        .toList()
    candidates.forEach { block ->
        if (block.end <= nextExpectedClock) return@forEach
        if (block.id.clock > nextExpectedClock) {
            result.add(Skip(Id(client, nextExpectedClock), block.id.clock - nextExpectedClock))
            nextExpectedClock = block.id.clock
        }
        val nextBlock = if (block.id.clock < nextExpectedClock) {
            sliceStruct(block, nextExpectedClock - block.id.clock)
        } else {
            sliceStruct(block, 0)
        }
        result.add(nextBlock)
        nextExpectedClock = nextBlock.end
    }
    return result
}

private fun readBlockSetStruct(decoder: UpdateDecoderV1): AbstractStruct {
    val id = Id(decoder.readClient(), decoder.restDecoder.readVarUInt())
    val length = decoder.restDecoder.readVarUInt()
    return when (val info = decoder.readInfo()) {
        structGCRefNumber -> GC(id, length)
        structSkipRefNumber -> Skip(id, length)
        else -> readBlockSetItem(decoder, id, length, info)
    }
}

private fun readBlockSetStruct(decoder: UpdateDecoderV2): AbstractStruct {
    val id = Id(decoder.readClient(), decoder.restDecoder.readVarUInt())
    val length = decoder.restDecoder.readVarUInt()
    return when (val info = decoder.readInfo()) {
        structGCRefNumber -> GC(id, length)
        structSkipRefNumber -> Skip(id, length)
        else -> readBlockSetItem(decoder, id, length, info)
    }
}

private fun readBlockSetItem(
    decoder: UpdateDecoderV1,
    id: Id,
    length: Long,
    info: Int,
): ItemStruct = ItemStruct(
    id = id,
    length = length,
    deleted = decoder.restDecoder.readBoolean(),
    origin = decoder.readNullableBlockSetId(),
    rightOrigin = decoder.readNullableBlockSetId(),
    parent = decoder.readString(),
    parentSub = decoder.readNullableBlockSetString(),
    kind = readBlockSetRootKind(decoder.restDecoder),
    content = readItemContent(decoder, info),
)

private fun readBlockSetItem(
    decoder: UpdateDecoderV2,
    id: Id,
    length: Long,
    info: Int,
): ItemStruct = ItemStruct(
    id = id,
    length = length,
    deleted = decoder.restDecoder.readBoolean(),
    origin = decoder.readNullableBlockSetId(),
    rightOrigin = decoder.readNullableBlockSetId(),
    parent = decoder.readString(),
    parentSub = decoder.readNullableBlockSetString(),
    kind = readBlockSetRootKind(decoder.restDecoder),
    content = readItemContent(decoder, info),
)

private fun writeBlockSetStruct(encoder: UpdateEncoderV1, struct: AbstractStruct) {
    encoder.writeClient(struct.id.client)
    encoder.restEncoder.writeVarUInt(struct.id.clock)
    encoder.restEncoder.writeVarUInt(struct.length)
    when (struct) {
        is GC -> encoder.writeInfo(structGCRefNumber)
        is Skip -> encoder.writeInfo(structSkipRefNumber)
        is ItemStruct -> writeBlockSetItem(encoder, struct)
        else -> error("unsupported block-set struct: ${struct::class.qualifiedName}")
    }
}

private fun writeBlockSetStruct(encoder: UpdateEncoderV2, struct: AbstractStruct) {
    encoder.writeClient(struct.id.client)
    encoder.restEncoder.writeVarUInt(struct.id.clock)
    encoder.restEncoder.writeVarUInt(struct.length)
    when (struct) {
        is GC -> encoder.writeInfo(structGCRefNumber)
        is Skip -> encoder.writeInfo(structSkipRefNumber)
        is ItemStruct -> writeBlockSetItem(encoder, struct)
        else -> error("unsupported block-set struct: ${struct::class.qualifiedName}")
    }
}

private fun writeBlockSetItem(encoder: UpdateEncoderV1, item: ItemStruct) {
    encoder.writeInfo(item.content.getRef())
    encoder.restEncoder.writeBoolean(item.deleted)
    encoder.writeNullableBlockSetId(item.origin)
    encoder.writeNullableBlockSetId(item.rightOrigin)
    encoder.writeString(item.parent)
    encoder.writeNullableBlockSetString(item.parentSub)
    writeBlockSetRootKind(encoder.restEncoder, item.kind)
    writeItemContent(encoder, item.content)
}

private fun writeBlockSetItem(encoder: UpdateEncoderV2, item: ItemStruct) {
    encoder.writeInfo(item.content.getRef())
    encoder.restEncoder.writeBoolean(item.deleted)
    encoder.writeNullableBlockSetId(item.origin)
    encoder.writeNullableBlockSetId(item.rightOrigin)
    encoder.writeString(item.parent)
    encoder.writeNullableBlockSetString(item.parentSub)
    writeBlockSetRootKind(encoder.restEncoder, item.kind)
    writeItemContent(encoder, item.content)
}

private fun UpdateEncoderV1.writeNullableBlockSetId(id: Id?) {
    restEncoder.writeBoolean(id != null)
    if (id != null) {
        writeLeftID(id)
    }
}

private fun UpdateEncoderV2.writeNullableBlockSetId(id: Id?) {
    restEncoder.writeBoolean(id != null)
    if (id != null) {
        writeLeftID(id)
    }
}

private fun UpdateDecoderV1.readNullableBlockSetId(): Id? =
    if (restDecoder.readBoolean()) readLeftID() else null

private fun UpdateDecoderV2.readNullableBlockSetId(): Id? =
    if (restDecoder.readBoolean()) readLeftID() else null

private fun UpdateEncoderV1.writeNullableBlockSetString(value: String?) {
    restEncoder.writeBoolean(value != null)
    if (value != null) {
        writeString(value)
    }
}

private fun UpdateEncoderV2.writeNullableBlockSetString(value: String?) {
    restEncoder.writeBoolean(value != null)
    if (value != null) {
        writeString(value)
    }
}

private fun UpdateDecoderV1.readNullableBlockSetString(): String? =
    if (restDecoder.readBoolean()) readString() else null

private fun UpdateDecoderV2.readNullableBlockSetString(): String? =
    if (restDecoder.readBoolean()) readString() else null

private fun writeBlockSetRootKind(encoder: BinaryEncoder, kind: RootKind) {
    encoder.writeVarUInt(kind.ordinal.toLong())
}

private fun readBlockSetRootKind(decoder: BinaryDecoder): RootKind {
    val ordinal = decoder.readVarUInt().toDecodedCount()
    return enumValues<RootKind>().getOrNull(ordinal) ?: error("unknown root kind ordinal: $ordinal")
}
