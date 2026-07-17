package dev.yks

public data class RelativePosition(
    val type: Id? = null,
    val tname: String? = null,
    val item: Id? = null,
    val assoc: Int = 0,
)

public data class AbsolutePosition(
    val type: AbstractYType,
    val index: Int,
    val assoc: Int = 0,
)

public fun createAbsolutePosition(type: AbstractYType, index: Int, assoc: Int = 0): AbsolutePosition {
    return AbsolutePosition(type, index, assoc)
}

public fun createRelativePosition(type: AbstractYType, item: Id? = null, assoc: Int = 0): RelativePosition {
    val typeId = type.doc.typeRefItemId(type)
    return if (typeId == null) {
        RelativePosition(tname = type.name, item = item, assoc = assoc)
    } else {
        RelativePosition(type = typeId, item = item, assoc = assoc)
    }
}

public fun createRelativePositionFromTypeIndex(
    type: AbstractYType,
    index: Int,
    assoc: Int = 0,
    renderer: AbstractRenderer = baseRenderer,
): RelativePosition {
    require(index >= 0) { "index must be non-negative" }
    val items = type.doc.relativePositionSequence(type, renderer)

    var remaining = index
    if (assoc < 0) {
        if (index == 0) {
            return createRelativePositionForTypeEndpoint(type, assoc)
        }
        remaining--
    }

    items.forEachIndexed { itemIndex, item ->
        val length = rendererContentLength(renderer, item.toItemStruct(type.doc))
            .toNonNegativeInt("relative position item length")
        if (length > remaining) {
            return createRelativePosition(
                type,
                item = Id(item.id.client, checkedClockAdd(item.id.clock, remaining.toLong(), "relative position clock")),
                assoc = assoc,
            )
        }
        remaining -= length
        if (assoc < 0 && itemIndex == items.lastIndex) {
            return createRelativePosition(
                type,
                item = Id(
                    item.id.client,
                    checkedClockAdd(item.id.clock, item.length - 1, "relative position clock"),
                ),
                assoc = assoc,
            )
        }
    }
    return createRelativePositionForTypeEndpoint(type, assoc)
}

private fun createRelativePositionForTypeEndpoint(type: AbstractYType, assoc: Int): RelativePosition {
    return createRelativePosition(type, item = null, assoc = assoc)
}

public fun createAbsolutePositionFromRelativePosition(
    relativePosition: RelativePosition,
    doc: YDoc,
    followUndoneDeletions: Boolean = true,
    renderer: AbstractRenderer = baseRenderer,
): AbsolutePosition? {
    val itemId = relativePosition.item
    if (itemId != null) {
        val originalItem = doc.getItem(itemId) ?: return null
        val anchorId = when {
            followUndoneDeletions -> doc.followRedone(itemId)
            originalItem.deleted -> doc.redoneRangeEnd(itemId) ?: itemId
            else -> itemId
        }
        val item = doc.getItem(anchorId) ?: return null
        val type = doc.typeForParent(item.parent) ?: doc.getOrNull(item.parent) ?: return null
        if (renderer === baseRenderer) {
            val visibleKind = if (type is YUnopenedRoot) item.content.kind else type.kind
            val indexAfter = doc.visibleSequenceIndexAfter(type.name, visibleKind, anchorId) ?: return null
            val includeAnchor = !item.deleted && (
                relativePosition.assoc < 0 ||
                    (!followUndoneDeletions && originalItem.deleted && anchorId != itemId)
                )
            val index = if (includeAnchor || item.deleted) indexAfter else indexAfter - 1
            return AbsolutePosition(type, index.coerceAtLeast(0), relativePosition.assoc)
        }
        val ordered = doc.relativePositionSequence(type, renderer)
        val rawIndex = ordered.indexOfFirst { candidate ->
            anchorId.client == candidate.id.client &&
                anchorId.clock >= candidate.id.clock &&
                anchorId.clock < checkedClockAdd(candidate.id.clock, candidate.length, "relative position item end")
        }
        if (rawIndex < 0) return null
        val anchorUnit = ordered[rawIndex]
        val visibleBefore = ordered.asSequence()
            .take(rawIndex)
            .fold(0L) { length, before ->
                checkedClockAdd(length, rendererContentLength(renderer, before.toItemStruct(doc)), "relative position index")
            }
        val prefixLength = if (anchorId.clock > anchorUnit.id.clock) {
            val prefix = anchorUnit.clockRangeView(anchorUnit.id.clock, anchorId.clock)
            rendererContentLength(renderer, prefix.toItemStruct(doc))
        } else {
            0L
        }
        val includeAnchor = !anchorUnit.deleted && (
            relativePosition.assoc < 0 ||
                (!followUndoneDeletions && originalItem.deleted && anchorId != itemId)
            )
        val anchorLength = if (includeAnchor) {
            val unitEnd = minOf(anchorUnit.clockEnd(), checkedClockAdd(anchorId.clock, 1, "relative position unit end"))
            rendererContentLength(renderer, anchorUnit.clockRangeView(anchorId.clock, unitEnd).toItemStruct(doc))
        } else {
            0L
        }
        val absoluteIndex = checkedClockAdd(
            checkedClockAdd(visibleBefore, prefixLength, "relative position prefix"),
            anchorLength,
            "relative position index",
        )
            .toNonNegativeInt("relative position index")
        return AbsolutePosition(type, absoluteIndex, relativePosition.assoc)
    }

    val type = when {
        relativePosition.tname != null ->
            doc.typeForParent(relativePosition.tname) ?: doc.getOrNull(relativePosition.tname)
        relativePosition.type != null -> {
            val typeId = if (followUndoneDeletions) doc.followRedone(relativePosition.type) else relativePosition.type
            doc.typeFromItemId(typeId)
        }
        else -> error("unexpected relative position")
    } ?: return null
    val index = if (relativePosition.assoc < 0) {
        0
    } else if (renderer === baseRenderer && type !is YUnopenedRoot) {
        doc.visibleLength(type.name, type.kind).toNonNegativeInt("relative position index")
    } else {
        doc.relativePositionSequence(type, renderer)
            .fold(0L) { length, item ->
                checkedClockAdd(length, rendererContentLength(renderer, item.toItemStruct(doc)), "relative position index")
            }
            .toNonNegativeInt("relative position index")
    }
    return AbsolutePosition(type, index, relativePosition.assoc)
}

private fun YDoc.relativePositionSequence(
    type: AbstractYType,
    renderer: AbstractRenderer,
): List<StoreItem> {
    val items = sequence(type.name)
        .filter { item -> item.countable && (type is YUnopenedRoot || item.content.kind == type.kind) }
    return buildList {
        ClockRangeCursor(items).forEachRange(
            boundariesForClient = { client ->
                buildList {
                    renderer.attributed.ranges(client).forEach { range ->
                        add(range.clock)
                        add(range.end)
                    }
                }
            },
        ) { source, startClock, endClock ->
            add(source.clockRangeView(startClock, endClock))
            true
        }
    }
}

public fun encodeRelativePosition(relativePosition: RelativePosition): ByteArray {
    val encoder = BinaryEncoder()
    writeRelativePosition(encoder, relativePosition)
    return encoder.toByteArray()
}

public fun writeRelativePosition(encoder: BinaryEncoder, relativePosition: RelativePosition): BinaryEncoder {
    when {
        relativePosition.item != null -> {
            encoder.writeVarUInt(0)
            writeID(encoder, relativePosition.item)
        }
        relativePosition.tname != null -> {
            encoder.writeVarUInt(1)
            encoder.writeString(relativePosition.tname)
        }
        relativePosition.type != null -> {
            encoder.writeVarUInt(2)
            writeID(encoder, relativePosition.type)
        }
        else -> error("unexpected relative position")
    }
    // Yjs uses lib0's sign-bit varint here, not zig-zag varint.
    encoder.writeLib0VarInt(relativePosition.assoc.toLong())
    return encoder
}

public fun writeRelativePosition(encoder: IdSetEncoderV1, relativePosition: RelativePosition): IdSetEncoderV1 {
    writeRelativePosition(encoder.restEncoder, relativePosition)
    return encoder
}

public fun decodeRelativePosition(bytes: ByteArray): RelativePosition {
    val decoder = BinaryDecoder(bytes)
    val relativePosition = readRelativePosition(decoder)
    check(!decoder.hasRemaining()) { "relative position has trailing bytes" }
    return relativePosition
}

public fun readRelativePosition(decoder: BinaryDecoder): RelativePosition {
    val relativePosition = when (val tag = decoder.readVarUInt()) {
        0L -> RelativePosition(item = readID(decoder), assoc = 0)
        1L -> RelativePosition(tname = decoder.readString(), assoc = 0)
        2L -> RelativePosition(type = readID(decoder), assoc = 0)
        else -> error("unknown relative position tag: $tag")
    }
    return if (decoder.hasRemaining()) {
        relativePosition.copy(assoc = decoder.readLib0VarInt().toIntExact("relative position assoc"))
    } else {
        relativePosition
    }
}

public fun readRelativePosition(decoder: IdSetDecoderV1): RelativePosition =
    readRelativePosition(decoder.restDecoder)

public fun relativePositionToJSON(relativePosition: RelativePosition): Map<String, Any?> = buildMap {
    relativePosition.type?.let { put("type", idToJSON(it)) }
    relativePosition.tname?.takeIf { it.isNotEmpty() }?.let { put("tname", it) }
    relativePosition.item?.let { put("item", idToJSON(it)) }
    put("assoc", relativePosition.assoc)
}

public fun createRelativePositionFromJSON(json: Map<String, Any?>): RelativePosition = RelativePosition(
    type = (json["type"] as? Map<*, *>)?.let(::idFromJSON),
    tname = json["tname"] as? String,
    item = (json["item"] as? Map<*, *>)?.let(::idFromJSON),
    assoc = (json["assoc"] as? Number)?.toInt() ?: 0,
)

public fun compareRelativePositions(left: RelativePosition?, right: RelativePosition?): Boolean = left == right

private fun idToJSON(id: Id): Map<String, Long> = mapOf("client" to id.client, "clock" to id.clock)

private fun idFromJSON(json: Map<*, *>): Id {
    val client = json["client"] as? Number ?: error("id json is missing numeric client")
    val clock = json["clock"] as? Number ?: error("id json is missing numeric clock")
    return Id(client.toLong(), clock.toLong())
}
