package dev.yks

data class RelativePosition(
    val type: Id? = null,
    val tname: String? = null,
    val item: Id? = null,
    val assoc: Int = 0,
)

data class AbsolutePosition(
    val type: AbstractYType,
    val index: Int,
    val assoc: Int = 0,
)

fun createAbsolutePosition(type: AbstractYType, index: Int, assoc: Int = 0): AbsolutePosition {
    return AbsolutePosition(type, index, assoc)
}

fun createRelativePosition(type: AbstractYType, item: Id? = null, assoc: Int = 0): RelativePosition {
    val typeId = type.doc.typeRefItemId(type)
    return if (typeId == null) {
        RelativePosition(tname = type.name, item = item, assoc = assoc)
    } else {
        RelativePosition(type = typeId, item = item, assoc = assoc)
    }
}

fun createRelativePositionFromTypeIndex(
    type: AbstractYType,
    index: Int,
    assoc: Int = 0,
    renderer: AbstractRenderer = baseRenderer,
): RelativePosition {
    require(index >= 0) { "index must be non-negative" }
    val items = type.doc.sequence(type.name).filter { it.content.kind == type.kind && it.countable }

    var remaining = index
    if (assoc < 0) {
        if (index == 0) {
            return createRelativePositionForTypeEndpoint(type, assoc)
        }
        remaining--
    }

    items.forEachIndexed { itemIndex, item ->
        val length = rendererContentLength(renderer, item.toItemStruct(type.doc)).toInt()
        if (length > remaining) {
            return createRelativePosition(type, item = Id(item.id.client, item.id.clock + remaining), assoc = assoc)
        }
        remaining -= length
        if (assoc < 0 && itemIndex == items.lastIndex) {
            return createRelativePosition(type, item = Id(item.id.client, item.id.clock + item.length - 1), assoc = assoc)
        }
    }
    return createRelativePositionForTypeEndpoint(type, assoc)
}

private fun createRelativePositionForTypeEndpoint(type: AbstractYType, assoc: Int): RelativePosition {
    return createRelativePosition(type, item = null, assoc = assoc)
}

fun createAbsolutePositionFromRelativePosition(
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
        val type = doc.typeForParent(item.parent) ?: return null
        val ordered = doc.sequence(item.parent).filter { it.content.kind == type.kind && it.countable }
        val rawIndex = ordered.indexOfFirst { it.id == anchorId }
        if (rawIndex < 0) return null
        val visibleBefore = ordered
            .take(rawIndex)
            .sumOf { before -> rendererContentLength(renderer, before.toItemStruct(doc)) }
        val includeAnchor = !item.deleted && (
            relativePosition.assoc < 0 ||
                (!followUndoneDeletions && originalItem.deleted && anchorId != itemId)
            )
        val anchorLength = if (includeAnchor) rendererContentLength(renderer, item.toItemStruct(doc)) else 0
        return AbsolutePosition(type, (visibleBefore + anchorLength).toInt(), relativePosition.assoc)
    }

    val type = when {
        relativePosition.tname != null -> doc.typeForParent(relativePosition.tname)
        relativePosition.type != null -> {
            val typeId = if (followUndoneDeletions) doc.followRedone(relativePosition.type) else relativePosition.type
            doc.typeFromItemId(typeId)
        }
        else -> error("unexpected relative position")
    } ?: return null
    val index = if (relativePosition.assoc < 0) {
        0
    } else {
            doc.sequence(type.name)
            .filter { it.content.kind == type.kind && it.countable }
            .sumOf { item -> rendererContentLength(renderer, item.toItemStruct(doc)) }
            .toInt()
    }
    return AbsolutePosition(type, index, relativePosition.assoc)
}

fun encodeRelativePosition(relativePosition: RelativePosition): ByteArray {
    val encoder = BinaryEncoder()
    writeRelativePosition(encoder, relativePosition)
    return encoder.toByteArray()
}

fun writeRelativePosition(encoder: BinaryEncoder, relativePosition: RelativePosition): BinaryEncoder {
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
    encoder.writeVarInt(relativePosition.assoc.toLong())
    return encoder
}

fun writeRelativePosition(encoder: IdSetEncoderV1, relativePosition: RelativePosition): IdSetEncoderV1 {
    writeRelativePosition(encoder.restEncoder, relativePosition)
    return encoder
}

fun decodeRelativePosition(bytes: ByteArray): RelativePosition {
    val decoder = BinaryDecoder(bytes)
    val relativePosition = readRelativePosition(decoder)
    check(!decoder.hasRemaining()) { "relative position has trailing bytes" }
    return relativePosition
}

fun readRelativePosition(decoder: BinaryDecoder): RelativePosition {
    val relativePosition = when (val tag = decoder.readVarUInt()) {
        0L -> RelativePosition(item = readID(decoder), assoc = 0)
        1L -> RelativePosition(tname = decoder.readString(), assoc = 0)
        2L -> RelativePosition(type = readID(decoder), assoc = 0)
        else -> error("unknown relative position tag: $tag")
    }
    return if (decoder.hasRemaining()) {
        relativePosition.copy(assoc = decoder.readVarInt().toInt())
    } else {
        relativePosition
    }
}

fun readRelativePosition(decoder: IdSetDecoderV1): RelativePosition =
    readRelativePosition(decoder.restDecoder)

fun relativePositionToJSON(relativePosition: RelativePosition): Map<String, Any?> = buildMap {
    relativePosition.type?.let { put("type", idToJSON(it)) }
    relativePosition.tname?.takeIf { it.isNotEmpty() }?.let { put("tname", it) }
    relativePosition.item?.let { put("item", idToJSON(it)) }
    put("assoc", relativePosition.assoc)
}

fun createRelativePositionFromJSON(json: Map<String, Any?>): RelativePosition = RelativePosition(
    type = (json["type"] as? Map<*, *>)?.let(::idFromJSON),
    tname = json["tname"] as? String,
    item = (json["item"] as? Map<*, *>)?.let(::idFromJSON),
    assoc = (json["assoc"] as? Number)?.toInt() ?: 0,
)

fun compareRelativePositions(left: RelativePosition?, right: RelativePosition?): Boolean = left == right

private fun idToJSON(id: Id): Map<String, Long> = mapOf("client" to id.client, "clock" to id.clock)

private fun idFromJSON(json: Map<*, *>): Id {
    val client = json["client"] as? Number ?: error("id json is missing numeric client")
    val clock = json["clock"] as? Number ?: error("id json is missing numeric clock")
    return Id(client.toLong(), clock.toLong())
}
