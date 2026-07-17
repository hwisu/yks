package dev.yks

private const val INFO_CONTENT_MASK = 0x1f
private const val INFO_HAS_PARENT_SUB = 0x20
private const val INFO_HAS_RIGHT_ORIGIN = 0x40
private const val INFO_HAS_ORIGIN = 0x80
internal const val YJS_MAX_SAFE_INTEGER: Long = 9_007_199_254_740_991L

/**
 * Thrown when an upstream-compatible update API cannot represent the requested state as a
 * genuine Yjs update. Call the corresponding `*Lossless` API when the private YKS envelope is
 * an acceptable transport.
 */
class UnsupportedYjsStandardUpdateException internal constructor(
    val format: String,
    detail: String = "update cannot be represented as a genuine Yjs $format update",
) : IllegalArgumentException(
    "$detail; use the corresponding *Lossless API",
)

internal fun requireStandardYjsUpdateInput(update: ByteArray, format: String) {
    if (update.hasLegacyMagic()) {
        throw UnsupportedYjsStandardUpdateException(
            format,
            "private YKS input is not accepted by a standard Yjs $format update API",
        )
    }
}

/** Byte-exact implementation of the uncompressed Yjs update V1 envelope. */
internal object UpdateCodec {
    fun encode(update: DocumentUpdate): ByteArray = BinaryEncoder()
        .also { encoder -> write(encoder, update) }
        .toByteArray()

    fun encodeLossless(update: DocumentUpdate): ByteArray = BinaryEncoder()
        .also { encoder -> writeLossless(encoder, update) }
        .toByteArray()

    fun encodeV2(update: DocumentUpdate): ByteArray {
        update.requireStandardUpdate(isV2 = true)
        return encodeV2Standard(update)
    }

    fun encodeV2Lossless(update: DocumentUpdate): ByteArray {
        if (!update.canEncodeStandard(isV2 = true, preservePrivateMetadata = true)) {
            return LegacyUpdateCodec.encode(update)
        }
        return encodeV2Standard(update)
    }

    private fun encodeV2Standard(update: DocumentUpdate): ByteArray {
        val encoder = UpdateEncoderV2()
        encoder.forceV2Envelope()
        val itemsByClient = update.items
            .groupBy { item -> item.id.client }
            .mapValues { (_, items) -> items.sortedBy { item -> item.id.clock } }
            .filterValues { items -> items.isNotEmpty() }
        val parentItems = update.parentItemIds + update.items.mapNotNull { item ->
            item.content.directTypeRef()?.name?.let { name -> name to item.id }
        }.toMap()

        encoder.restEncoder.writeVarUInt(itemsByClient.size.toLong())
        itemsByClient.toSortedMap(compareByDescending { it }).forEach { (client, items) ->
            val structs = items.withClockSkips()
            encoder.restEncoder.writeVarUInt(structs.size.toLong())
            encoder.writeClient(client)
            encoder.restEncoder.writeVarUInt(structs.first().clock)
            structs.forEach { struct ->
                when (struct) {
                    is EncodedStruct.Skip -> {
                        encoder.writeInfo(structSkipRefNumber)
                        encoder.restEncoder.writeVarUInt(struct.length)
                    }
                    is EncodedStruct.Item -> if (struct.item.isGc) {
                        encoder.writeInfo(structGCRefNumber)
                        encoder.writeLen(struct.item.length)
                    } else {
                        writeItemV2(encoder, struct.item, parentItems)
                    }
                    is EncodedStruct.PackedText -> writePackedTextV2(encoder, struct, parentItems)
                }
            }
        }
        writeDeleteSetV2(encoder, update.deleteSet)
        return encoder.toByteArray()
    }

    fun write(encoder: BinaryEncoder, update: DocumentUpdate): BinaryEncoder {
        update.requireStandardUpdate(isV2 = false)
        return writeV1(encoder, update)
    }

    fun writeLossless(encoder: BinaryEncoder, update: DocumentUpdate): BinaryEncoder =
        if (update.canEncodeStandard(isV2 = false, preservePrivateMetadata = true)) writeV1(encoder, update)
        else LegacyUpdateCodec.write(encoder, update)

    private fun writeV1(encoder: BinaryEncoder, update: DocumentUpdate): BinaryEncoder {
        if (update.items.size == 1) {
            val item = update.items.single()
            val directTypeRef = item.content.directTypeRef()
            val parentItems = if (directTypeRef == null) {
                update.parentItemIds
            } else {
                update.parentItemIds + (directTypeRef.name to item.id)
            }
            encoder.writeVarUInt(1)
            encoder.writeVarUInt(1)
            encoder.writeVarUInt(item.id.client)
            encoder.writeVarUInt(item.id.clock)
            if (item.isGc) {
                encoder.writeByte(structGCRefNumber)
                encoder.writeVarUInt(item.length)
            } else {
                writeItem(encoder, item, parentItems)
            }
            writeDeleteSet(encoder, update.deleteSet)
            return encoder
        }

        val itemsByClient = update.items
            .groupBy { item -> item.id.client }
            .mapValues { (_, items) -> items.sortedBy { item -> item.id.clock } }
            .filterValues { items -> items.isNotEmpty() }
        val parentItems = update.parentItemIds + update.items.mapNotNull { item ->
            item.content.directTypeRef()?.name?.let { name -> name to item.id }
        }.toMap()

        encoder.writeVarUInt(itemsByClient.size.toLong())
        itemsByClient.toSortedMap(compareByDescending { it }).forEach { (client, items) ->
            val structs = items.withClockSkips()
            encoder.writeVarUInt(structs.size.toLong())
            encoder.writeVarUInt(client)
            encoder.writeVarUInt(structs.first().clock)
            structs.forEach { struct ->
                when (struct) {
                    is EncodedStruct.Skip -> {
                        encoder.writeByte(structSkipRefNumber)
                        encoder.writeVarUInt(struct.length)
                    }
                    is EncodedStruct.Item -> if (struct.item.isGc) {
                        encoder.writeByte(structGCRefNumber)
                        encoder.writeVarUInt(struct.item.length)
                    } else {
                        writeItem(encoder, struct.item, parentItems)
                    }
                    is EncodedStruct.PackedText -> writePackedText(encoder, struct, parentItems)
                }
            }
        }
        writeDeleteSet(encoder, update.deleteSet)
        return encoder
    }

    fun decode(
        bytes: ByteArray,
        maxStructs: Int = MAX_DECODED_COLLECTION_SIZE,
        maxDeleteRanges: Int = MAX_DECODED_COLLECTION_SIZE,
    ): DocumentUpdate = decodeBoundary("Yjs update V1") {
        val budget = DecodedUpdateBudget(maxStructs, maxDeleteRanges)
        if (bytes.hasLegacyMagic()) LegacyUpdateCodec.decode(bytes, maxStructs, maxDeleteRanges)
        else decodeV1(BinaryDecoder(bytes), budget = budget)
    }

    fun parseMeta(bytes: ByteArray): UpdateMeta = decodeBoundary("Yjs update V1 metadata") {
        if (bytes.hasLegacyMagic()) return@decodeBoundary LegacyUpdateCodec.decode(bytes).toUpdateMeta()
        val from = linkedMapOf<Long, Long>()
        val to = linkedMapOf<Long, Long>()
        decodeV1(BinaryDecoder(bytes), from, to)
        UpdateMeta(from, to)
    }

    fun decode(
        decoder: BinaryDecoder,
        maxStructs: Int = MAX_DECODED_COLLECTION_SIZE,
        maxDeleteRanges: Int = MAX_DECODED_COLLECTION_SIZE,
    ): DocumentUpdate = decode(decoder.readRemainingBytes(), maxStructs, maxDeleteRanges)

    fun decodeV2(
        bytes: ByteArray,
        maxStructs: Int = MAX_DECODED_COLLECTION_SIZE,
        maxDeleteRanges: Int = MAX_DECODED_COLLECTION_SIZE,
    ): DocumentUpdate = decodeBoundary("Yjs update V2") {
        if (bytes.isNotEmpty() && bytes[0] != 0.toByte()) {
            return@decodeBoundary decode(bytes, maxStructs, maxDeleteRanges)
        }
        decodeZeroPrefixedV2OrV1(
            decodeV2 = {
                decodeV2(
                    UpdateDecoderV2(bytes),
                    maxStructs = maxStructs,
                    maxDeleteRanges = maxDeleteRanges,
                )
            },
            decodeV1 = { decode(bytes, maxStructs, maxDeleteRanges) },
        )
    }

    fun parseMetaV2(bytes: ByteArray): UpdateMeta = decodeBoundary("Yjs update V2 metadata") {
        if (bytes.isNotEmpty() && bytes[0] != 0.toByte()) return@decodeBoundary parseMeta(bytes)
        decodeZeroPrefixedV2OrV1(
            decodeV2 = {
                val from = linkedMapOf<Long, Long>()
                val to = linkedMapOf<Long, Long>()
                decodeV2(UpdateDecoderV2(bytes), from, to)
                UpdateMeta(from, to)
            },
            decodeV1 = { parseMeta(bytes) },
        )
    }

    /**
     * A genuine V2 update starts with feature flag zero, but a V1 update with no structs does as
     * well. Prefer V2 for this API and retry as V1 when the V2 envelope is structurally invalid.
     * This keeps empty and delete-only V1 updates usable on the compatibility path without
     * changing the interpretation of any valid V2 envelope.
     */
    private inline fun <T> decodeZeroPrefixedV2OrV1(
        decodeV2: () -> T,
        decodeV1: () -> T,
    ): T = try {
        decodeV2()
    } catch (limitError: YksUpdateLimitException) {
        throw limitError
    } catch (v2Error: RuntimeException) {
        try {
            decodeV1()
        } catch (v1Error: RuntimeException) {
            v2Error.addSuppressed(v1Error)
            throw v2Error
        }
    }

    fun decodeV2(
        decoder: UpdateDecoderV2,
        metaFrom: MutableMap<Long, Long>? = null,
        metaTo: MutableMap<Long, Long>? = null,
        maxStructs: Int = MAX_DECODED_COLLECTION_SIZE,
        maxDeleteRanges: Int = MAX_DECODED_COLLECTION_SIZE,
    ): DocumentUpdate = decodeBoundary("Yjs update V2") {
        if (decoder.usesLegacyRest) {
            return@decodeBoundary decode(decoder.restDecoder, maxStructs, maxDeleteRanges)
        }
        val budget = DecodedUpdateBudget(maxStructs, maxDeleteRanges)
        val structs = mutableListOf<DecodedWireItem>()
        repeat(decoder.restDecoder.readVarUInt().toDecodedCount()) {
            val numberOfStructs = decoder.restDecoder.readVarUInt().toDecodedCount()
            budget.structs.consume(numberOfStructs)
            val client = decoder.readClient()
            var clock = decoder.restDecoder.readVarUInt()
            recordMetaStart(metaFrom, client, clock)
            repeat(numberOfStructs) {
                val info = decoder.readInfo()
                when (info and INFO_CONTENT_MASK) {
                    structGCRefNumber -> {
                        val length = decoder.readLen()
                        structs.add(
                            DecodedWireItem(
                                Id(client, clock),
                                null,
                                null,
                                ParentReference.Root(gcParentName(client)),
                                null,
                                WireContent.Deleted(length),
                                isGc = true,
                            ),
                        )
                        clock = checkedClockAdd(clock, length)
                    }
                    structSkipRefNumber -> clock = checkedClockAdd(clock, decoder.restDecoder.readVarUInt())
                    else -> {
                        val id = Id(client, clock)
                        val origin = if ((info and INFO_HAS_ORIGIN) != 0) decoder.readLeftID() else null
                        val rightOrigin = if ((info and INFO_HAS_RIGHT_ORIGIN) != 0) decoder.readRightID() else null
                        val parent = if (origin == null && rightOrigin == null) {
                            if (decoder.readParentInfo()) {
                                ParentReference.Root(decoder.readString())
                            } else {
                                ParentReference.Nested(decoder.readLeftID())
                            }
                        } else {
                            ParentReference.Inherit(origin ?: checkNotNull(rightOrigin))
                        }
                        val parentSub = if (
                            origin == null && rightOrigin == null && (info and INFO_HAS_PARENT_SUB) != 0
                        ) decoder.readString() else null
                        val content = readWireContentV2(decoder, info and INFO_CONTENT_MASK, id)
                        structs.add(DecodedWireItem(id, origin, rightOrigin, parent, parentSub, content))
                        clock = checkedClockAdd(clock, content.length)
                    }
                }
            }
            recordMetaEnd(metaTo, client, clock)
        }
        val deleteSet = DeleteSet.empty()
        repeat(decoder.restDecoder.readVarUInt().toDecodedCount()) {
            decoder.resetDsCurVal()
            val client = decoder.restDecoder.readVarUInt()
            val rangeCount = decoder.restDecoder.readVarUInt().toDecodedCount()
            budget.deleteRanges.consume(rangeCount)
            repeat(rangeCount) {
                deleteSet.add(Id(client, decoder.readDsClock()), decoder.readDsLen())
            }
        }
        check(!decoder.hasRemaining()) { "V2 update has trailing rest-stream bytes" }
        DocumentUpdate(structs.toStoreItems(), deleteSet)
    }

    private fun decodeV1(
        decoder: BinaryDecoder,
        metaFrom: MutableMap<Long, Long>? = null,
        metaTo: MutableMap<Long, Long>? = null,
        budget: DecodedUpdateBudget = DecodedUpdateBudget(
            MAX_DECODED_COLLECTION_SIZE,
            MAX_DECODED_COLLECTION_SIZE,
        ),
    ): DocumentUpdate {
        val structs = mutableListOf<DecodedWireItem>()
        repeat(decoder.readVarUInt().toDecodedCount()) {
            val numberOfStructs = decoder.readVarUInt().toDecodedCount()
            budget.structs.consume(numberOfStructs)
            val client = decoder.readVarUInt()
            var clock = decoder.readVarUInt()
            recordMetaStart(metaFrom, client, clock)
            repeat(numberOfStructs) {
                val info = decoder.readByte()
                when (info and INFO_CONTENT_MASK) {
                    structGCRefNumber -> {
                        val length = decoder.readVarUInt()
                        structs.add(
                            DecodedWireItem(
                                id = Id(client, clock),
                                origin = null,
                                rightOrigin = null,
                                parent = ParentReference.Root(gcParentName(client)),
                                parentSub = null,
                                content = WireContent.Deleted(length),
                                isGc = true,
                            ),
                        )
                        clock = checkedClockAdd(clock, length)
                    }
                    structSkipRefNumber -> clock = checkedClockAdd(clock, decoder.readVarUInt())
                    else -> {
                        val id = Id(client, clock)
                        val origin = if ((info and INFO_HAS_ORIGIN) != 0) decoder.readId() else null
                        val rightOrigin = if ((info and INFO_HAS_RIGHT_ORIGIN) != 0) decoder.readId() else null
                        val parent = if (origin == null && rightOrigin == null) {
                            if (decoder.readVarUInt() == 1L) {
                                ParentReference.Root(decoder.readString())
                            } else {
                                ParentReference.Nested(decoder.readId())
                            }
                        } else {
                            ParentReference.Inherit(origin ?: checkNotNull(rightOrigin))
                        }
                        val parentSub = if (
                            origin == null && rightOrigin == null && (info and INFO_HAS_PARENT_SUB) != 0
                        ) {
                            decoder.readString()
                        } else {
                            null
                        }
                        val content = readWireContent(decoder, info and INFO_CONTENT_MASK, id)
                        structs.add(DecodedWireItem(id, origin, rightOrigin, parent, parentSub, content))
                        clock = checkedClockAdd(clock, content.length)
                    }
                }
            }
            recordMetaEnd(metaTo, client, clock)
        }
        val deleteSet = readDeleteSet(decoder, budget.deleteRanges)
        check(!decoder.hasRemaining()) { "update has trailing bytes" }
        return DocumentUpdate(structs.toStoreItems(), deleteSet)
    }

    private fun recordMetaStart(meta: MutableMap<Long, Long>?, client: Long, clock: Long) {
        if (meta == null) return
        meta[client] = minOf(meta[client] ?: clock, clock)
    }

    private fun recordMetaEnd(meta: MutableMap<Long, Long>?, client: Long, clock: Long) {
        if (meta == null) return
        meta[client] = maxOf(meta[client] ?: clock, clock)
    }

    private fun writeItem(
        encoder: BinaryEncoder,
        item: StoreItem,
        parentItems: Map<String, Id>,
    ) {
        val ref = item.content.yjsContentRef()
        val info = ref or
            (if (item.origin != null) INFO_HAS_ORIGIN else 0) or
            (if (item.rightOrigin != null) INFO_HAS_RIGHT_ORIGIN else 0) or
            (if (item.parentSub != null) INFO_HAS_PARENT_SUB else 0)
        encoder.writeByte(info)
        item.origin?.let(encoder::writeId)
        item.rightOrigin?.let(encoder::writeId)
        if (item.origin == null && item.rightOrigin == null) {
            val parentItem = (item.unresolvedParent as? UnresolvedYjsParent.Nested)?.id
                ?: parentItems[item.parent]
            encoder.writeVarUInt(if (parentItem == null) 1 else 0)
            if (parentItem == null) {
                encoder.writeString(item.parent)
            } else {
                encoder.writeId(parentItem)
            }
            item.parentSub?.let(encoder::writeString)
        }
        writeContent(encoder, item.content)
    }

    private fun writeItemV2(
        encoder: UpdateEncoderV2,
        item: StoreItem,
        parentItems: Map<String, Id>,
    ) {
        val ref = item.content.yjsContentRef()
        val info = ref or
            (if (item.origin != null) INFO_HAS_ORIGIN else 0) or
            (if (item.rightOrigin != null) INFO_HAS_RIGHT_ORIGIN else 0) or
            (if (item.parentSub != null) INFO_HAS_PARENT_SUB else 0)
        encoder.writeInfo(info)
        item.origin?.let(encoder::writeLeftID)
        item.rightOrigin?.let(encoder::writeRightID)
        if (item.origin == null && item.rightOrigin == null) {
            val parentItem = (item.unresolvedParent as? UnresolvedYjsParent.Nested)?.id
                ?: parentItems[item.parent]
            encoder.writeParentInfo(parentItem == null)
            if (parentItem == null) encoder.writeString(item.parent) else encoder.writeLeftID(parentItem)
            item.parentSub?.let(encoder::writeString)
        }
        writeContentV2(encoder, item.content)
    }

    private fun writePackedText(
        encoder: BinaryEncoder,
        packed: EncodedStruct.PackedText,
        parentItems: Map<String, Id>,
    ) {
        val item = packed.first
        val info = contentStringRefNumber or
            (if (item.origin != null) INFO_HAS_ORIGIN else 0) or
            (if (item.rightOrigin != null) INFO_HAS_RIGHT_ORIGIN else 0) or
            (if (item.parentSub != null) INFO_HAS_PARENT_SUB else 0)
        encoder.writeByte(info)
        item.origin?.let(encoder::writeId)
        item.rightOrigin?.let(encoder::writeId)
        if (item.origin == null && item.rightOrigin == null) {
            val parentItem = (item.unresolvedParent as? UnresolvedYjsParent.Nested)?.id
                ?: parentItems[item.parent]
            encoder.writeVarUInt(if (parentItem == null) 1 else 0)
            if (parentItem == null) encoder.writeString(item.parent) else encoder.writeId(parentItem)
            item.parentSub?.let(encoder::writeString)
        }
        encoder.writeString(packed.value)
    }

    private fun writePackedTextV2(
        encoder: UpdateEncoderV2,
        packed: EncodedStruct.PackedText,
        parentItems: Map<String, Id>,
    ) {
        val item = packed.first
        val info = contentStringRefNumber or
            (if (item.origin != null) INFO_HAS_ORIGIN else 0) or
            (if (item.rightOrigin != null) INFO_HAS_RIGHT_ORIGIN else 0) or
            (if (item.parentSub != null) INFO_HAS_PARENT_SUB else 0)
        encoder.writeInfo(info)
        item.origin?.let(encoder::writeLeftID)
        item.rightOrigin?.let(encoder::writeRightID)
        if (item.origin == null && item.rightOrigin == null) {
            val parentItem = (item.unresolvedParent as? UnresolvedYjsParent.Nested)?.id
                ?: parentItems[item.parent]
            encoder.writeParentInfo(parentItem == null)
            if (parentItem == null) encoder.writeString(item.parent) else encoder.writeLeftID(parentItem)
            item.parentSub?.let(encoder::writeString)
        }
        encoder.writeString(packed.value)
    }

    private fun writeContent(encoder: BinaryEncoder, content: ItemContent) {
        when (content) {
            is ItemContent.Text -> encoder.writeString(content.value)
            is ItemContent.TextEmbed -> if (content.value is YValue.SubdocRef) {
                writeValueContent(encoder, content.value)
            } else {
                encoder.writeString(toJsonLiteral(content.value.toAny()))
            }
            is ItemContent.TextFormat -> {
                encoder.writeString("__yks_text_format")
                encoder.writeString(toJsonLiteral(content.toWireValue()))
            }
            is ItemContent.NativeTextFormat -> {
                encoder.writeString(content.key)
                encoder.writeString(toJsonLiteral(content.value.toAny()))
            }
            is ItemContent.Value -> writeValueContent(encoder, content.value)
            is ItemContent.ArrayValues -> {
                encoder.writeVarUInt(content.values.size.toLong())
                content.values.forEach { value -> writeLib0Any(encoder, value.toAny()) }
            }
            is ItemContent.MapEntry -> writeValueContent(encoder, content.value)
            is ItemContent.MapEntries -> {
                encoder.writeVarUInt(content.values.size.toLong())
                content.values.forEach { value -> writeLib0Any(encoder, value.toAny()) }
            }
            is ItemContent.XmlNode -> {
                encoder.writeVarUInt(1)
                writeLib0Any(encoder, content.value.toEventJson())
            }
            is ItemContent.XmlType -> writeTypeContent(encoder, content.ref.kind, content.nodeName)
            is ItemContent.Deleted -> encoder.writeVarUInt(content.length)
        }
    }

    private fun writeContentV2(encoder: UpdateEncoderV2, content: ItemContent) {
        when (content) {
            is ItemContent.Text -> encoder.writeString(content.value)
            is ItemContent.TextEmbed -> if (content.value is YValue.SubdocRef) {
                writeValueContentV2(encoder, content.value)
            } else {
                encoder.writeJSON(content.value.toAny())
            }
            is ItemContent.TextFormat -> {
                encoder.writeKey("__yks_text_format")
                encoder.writeJSON(content.toWireValue())
            }
            is ItemContent.NativeTextFormat -> {
                encoder.writeKey(content.key)
                encoder.writeJSON(content.value.toAny())
            }
            is ItemContent.Value -> writeValueContentV2(encoder, content.value)
            is ItemContent.ArrayValues -> {
                encoder.writeLen(content.values.size.toLong())
                content.values.forEach { value -> encoder.writeAny(value.toAny()) }
            }
            is ItemContent.MapEntry -> writeValueContentV2(encoder, content.value)
            is ItemContent.MapEntries -> {
                encoder.writeLen(content.values.size.toLong())
                content.values.forEach { value -> encoder.writeAny(value.toAny()) }
            }
            is ItemContent.XmlNode -> {
                encoder.writeLen(1)
                encoder.writeAny(content.value.toEventJson())
            }
            is ItemContent.XmlType -> writeTypeContentV2(encoder, content.ref.kind, content.nodeName)
            is ItemContent.Deleted -> encoder.writeLen(content.length)
        }
    }

    private fun writeValueContentV2(encoder: UpdateEncoderV2, value: YValue) {
        when (value) {
            is YValue.BinaryValue -> encoder.writeBuf(value.bytes())
            is YValue.TypeRef -> writeTypeContentV2(encoder, value.kind, value.name)
            is YValue.SubdocRef -> {
                encoder.writeString(value.guid)
                val options = linkedMapOf<String, Any?>()
                if (!value.gc) options["gc"] = false
                if (value.autoLoad) options["autoLoad"] = true
                if (value.meta != YValue.Null) options["meta"] = value.meta.toAny()
                encoder.writeAny(options)
            }
            else -> {
                encoder.writeLen(1)
                encoder.writeAny(value.toAny())
            }
        }
    }

    private fun writeTypeContentV2(encoder: UpdateEncoderV2, kind: RootKind, nodeName: String = "") {
        encoder.writeTypeRef(kind.typeRefId())
        if (kind == RootKind.XmlElement || kind == RootKind.XmlHook) encoder.writeKey(nodeName)
    }

    private fun writeValueContent(encoder: BinaryEncoder, value: YValue) {
        when (value) {
            is YValue.BinaryValue -> encoder.writeBytes(value.bytes())
            is YValue.TypeRef -> writeTypeContent(encoder, value.kind, value.name)
            is YValue.SubdocRef -> {
                encoder.writeString(value.guid)
                val options = linkedMapOf<String, Any?>()
                if (!value.gc) options["gc"] = false
                if (value.autoLoad) options["autoLoad"] = true
                if (value.meta != YValue.Null) options["meta"] = value.meta.toAny()
                writeLib0Any(encoder, options)
            }
            else -> {
                encoder.writeVarUInt(1)
                writeLib0Any(encoder, value.toAny())
            }
        }
    }

    private fun writeTypeContent(encoder: BinaryEncoder, kind: RootKind, nodeName: String = "") {
        encoder.writeVarUInt(kind.typeRefId().toLong())
        if (kind == RootKind.XmlElement || kind == RootKind.XmlHook) {
            encoder.writeString(nodeName)
        }
    }

    private fun readWireContent(decoder: BinaryDecoder, ref: Int, id: Id): WireContent = when (ref) {
        contentDeletedRefNumber -> WireContent.Deleted(decoder.readVarUInt())
        contentJSONRefNumber -> WireContent.Json(
            List(decoder.readVarUInt().toDecodedCount()) {
                when (val json = decoder.readString()) {
                    "undefined" -> null
                    else -> parseJsonLiteral(json)
                }
            },
        )
        contentBinaryRefNumber -> WireContent.Binary(decoder.readBytes())
        contentStringRefNumber -> WireContent.StringContent(decoder.readString())
        contentEmbedRefNumber -> WireContent.Embed(parseJsonLiteral(decoder.readString()))
        contentFormatRefNumber -> WireContent.Format(decoder.readString(), parseJsonLiteral(decoder.readString()))
        contentTypeRefNumber -> {
            val kind = rootKindFromTypeRefId(decoder.readVarUInt().toDecodedCount())
            val nodeName = if (kind == RootKind.XmlElement || kind == RootKind.XmlHook) decoder.readString() else ""
            WireContent.Type(kind, nestedTypeName(id), nodeName)
        }
        contentAnyRefNumber -> WireContent.AnyContent(
            List(decoder.readVarUInt().toDecodedCount()) { readLib0Any(decoder) },
        )
        contentDocRefNumber -> {
            val guid = decoder.readString()
            WireContent.Doc(
                guid = guid,
                options = readLib0Any(decoder),
                instanceId = "__yjs_subdoc__:" + id.client + ":" + id.clock,
            )
        }
        else -> error("unknown Yjs item content ref: $ref")
    }

    private fun readWireContentV2(decoder: UpdateDecoderV2, ref: Int, id: Id): WireContent = when (ref) {
        contentDeletedRefNumber -> WireContent.Deleted(decoder.readLen())
        contentJSONRefNumber -> WireContent.Json(
            List(decoder.readLen().toDecodedCount()) {
                when (val json = decoder.readString()) {
                    "undefined" -> null
                    else -> parseJsonLiteral(json)
                }
            },
        )
        contentBinaryRefNumber -> WireContent.Binary(decoder.readBuf())
        contentStringRefNumber -> WireContent.StringContent(decoder.readString())
        contentEmbedRefNumber -> WireContent.Embed(decoder.readJSON())
        contentFormatRefNumber -> WireContent.Format(decoder.readKey(), decoder.readJSON())
        contentTypeRefNumber -> {
            val kind = rootKindFromTypeRefId(decoder.readTypeRef())
            val nodeName = if (kind == RootKind.XmlElement || kind == RootKind.XmlHook) decoder.readKey() else ""
            WireContent.Type(kind, nestedTypeName(id), nodeName)
        }
        contentAnyRefNumber -> WireContent.AnyContent(List(decoder.readLen().toDecodedCount()) { decoder.readAny() })
        contentDocRefNumber -> WireContent.Doc(
            decoder.readString(),
            decoder.readAny(),
            "__yjs_subdoc__:" + id.client + ":" + id.clock,
        )
        else -> error("unknown Yjs V2 item content ref: $ref")
    }

    private fun writeDeleteSet(encoder: BinaryEncoder, deleteSet: DeleteSet) {
        val clients = deleteSet.clients.filterValues { ranges -> ranges.isNotEmpty() }
        encoder.writeVarUInt(clients.size.toLong())
        clients.toSortedMap(compareByDescending { it }).forEach { (client, ranges) ->
            encoder.writeVarUInt(client)
            encoder.writeVarUInt(ranges.size.toLong())
            ranges.sortedBy { range -> range.clock }.forEach { range ->
                encoder.writeVarUInt(range.clock)
                encoder.writeVarUInt(range.length)
            }
        }
    }

    private fun readDeleteSet(decoder: BinaryDecoder, rangeBudget: DecodedCountBudget): DeleteSet {
        val deleteSet = DeleteSet.empty()
        repeat(decoder.readVarUInt().toDecodedCount()) {
            val client = decoder.readVarUInt()
            val rangeCount = decoder.readVarUInt().toDecodedCount()
            rangeBudget.consume(rangeCount)
            repeat(rangeCount) {
                deleteSet.add(Id(client, decoder.readVarUInt()), decoder.readVarUInt())
            }
        }
        return deleteSet
    }

    private fun writeDeleteSetV2(encoder: UpdateEncoderV2, deleteSet: DeleteSet) {
        val clients = deleteSet.clients.filterValues { ranges -> ranges.isNotEmpty() }
        encoder.restEncoder.writeVarUInt(clients.size.toLong())
        clients.toSortedMap(compareByDescending { it }).forEach { (client, ranges) ->
            encoder.resetIdSetCurVal()
            encoder.restEncoder.writeVarUInt(client)
            encoder.restEncoder.writeVarUInt(ranges.size.toLong())
            ranges.sortedBy { range -> range.clock }.forEach { range ->
                encoder.writeIdSetClock(range.clock)
                encoder.writeIdSetLen(range.length)
            }
        }
    }
}

private fun DocumentUpdate.canEncodeStandard(
    isV2: Boolean,
    preservePrivateMetadata: Boolean = false,
): Boolean =
    allowV1 &&
        isSupportedStandardUpdate(isV2) &&
        (
            !preservePrivateMetadata ||
                (hasStandardClockContinuitySemantics() && hasStandardContentMetadata())
        )

/**
 * Standard text-like content has no field for YKS' rendered/base text attributes. Rendered
 * attributes are still standard-representable when matching native ContentFormat markers are
 * present in the same update. A range/id-set selection can omit those markers, in which case a
 * lossless writer must retain the item metadata in a private envelope.
 */
private fun DocumentUpdate.hasStandardContentMetadata(): Boolean {
    fun metadata(content: ItemContent): Pair<Map<String, YValue>, Map<String, YValue>>? = when (content) {
        is ItemContent.Text -> content.attributes to content.baseAttributes
        is ItemContent.TextEmbed -> content.attributes to content.baseAttributes
        is ItemContent.XmlType -> content.attributes to content.baseAttributes
        else -> null
    }

    // Only text sequences can reconstruct rendered attributes from ContentFormat markers.
    // Deleted content does not participate in that reconstruction and private base attributes
    // have no standard-wire representation at all.
    if (items.any { item ->
            val (attributes, baseAttributes) = metadata(item.content) ?: return@any false
            baseAttributes.isNotEmpty() ||
                (item.content.kind !in setOf(RootKind.Text, RootKind.XmlText) && attributes.isNotEmpty()) ||
                (item.deleted && attributes.isNotEmpty())
        }
    ) return false

    val orderingStore = StructStore()
    items.forEach { item -> check(orderingStore.add(item)) { "duplicate item while checking text metadata: ${item.id}" } }
    val textParents = items.asSequence()
        .filter { item ->
            item.parentSub == null &&
                item.content.kind in setOf(RootKind.Text, RootKind.XmlText) &&
                (item.content is ItemContent.Text ||
                    item.content is ItemContent.TextEmbed ||
                    item.content is ItemContent.XmlType ||
                    item.content is ItemContent.NativeTextFormat)
        }
        .map { item -> item.parent }
        .toSet()

    return textParents.all { parent ->
        val activeByKind = linkedMapOf<RootKind, MutableMap<String, YValue>>()
        orderingStore.sequence(parent).all itemLoop@{ item ->
            val content = item.content
            if (item.deleted) return@itemLoop true
            when (content) {
                is ItemContent.NativeTextFormat -> {
                    val active = activeByKind.getOrPut(content.kind) { linkedMapOf() }
                    if (content.value == YValue.Null) active.remove(content.key) else active[content.key] = content.value
                    true
                }
                is ItemContent.Text,
                is ItemContent.TextEmbed,
                is ItemContent.XmlType -> {
                    val (attributes, _) = checkNotNull(metadata(content))
                    attributes == activeByKind[content.kind].orEmpty()
                }
                else -> true
            }
        }
    }
}

/**
 * Standard Yjs wire always requires the receiver to own every earlier client clock. A local
 * item marked as not requiring continuity is therefore losslessly representable only while
 * there is no omitted clock before it. Strict APIs may intentionally adopt standard Yjs
 * incremental semantics; *Lossless APIs must retain the Kotlin metadata via YKS when needed.
 */
private fun DocumentUpdate.hasStandardClockContinuitySemantics(): Boolean =
    items.groupBy { item -> item.id.client }.values.all { clientItems ->
        var coveredUntil = 0L
        clientItems.sortedBy { item -> item.id.clock }.all { item ->
            val gapChangesSemantics = item.id.clock > coveredUntil && !item.requiresClockContinuity
            coveredUntil = maxOf(coveredUntil, checkedClockAdd(item.id.clock, item.length))
            !gapChangesSemantics
        }
    }

private fun DocumentUpdate.requireStandardUpdate(isV2: Boolean) {
    if (!canEncodeStandard(isV2)) {
        throw UnsupportedYjsStandardUpdateException(if (isV2) "V2" else "V1")
    }
}

private fun DocumentUpdate.isSupportedStandardUpdate(isV2: Boolean): Boolean {
    if (!deleteSet.hasYjsSafeRanges()) return false
    if (parentItemIds.values.any { id -> !id.isYjsSafeId() }) return false
    if (items.any { item -> !item.hasYjsSafeClocks() }) return false
    if (items.isEmpty()) return true
    if (items.size == 1) {
        val item = items.single()
        val parentKind = parentKinds[item.parent]
        if (
            parentKind != null &&
            item.hasValidStandaloneTextSurrogates() &&
            item.content.isSupportedStandardContent(isV2) &&
            item.hasCompatibleKnownParentKind(parentKind) &&
            item.hasResolvableV1Parent(parentItemIds)
        ) {
            return true
        }
    }
    val index = StandardUpdateIndex(items)
    val parentItems = parentItemIds + items.mapNotNull { item ->
        item.content.directTypeRef()?.name?.let { name -> name to item.id }
    }.toMap()
    val parentKinds = this.parentKinds + items.mapNotNull { item ->
        item.content.directTypeRef()?.let { ref -> ref.name to ref.kind }
    }.toMap()
    return index.itemsByClient.values.all { sorted ->
        if (!sorted.hasValidTextSurrogatePairs()) return@all false
        val startClock = sorted.first().id.clock
        var previousEnd = startClock
        if (sorted.any { item ->
                val overlapsPrevious = item.id.clock < previousEnd
                previousEnd = checkedClockAdd(item.id.clock, item.length)
                overlapsPrevious
            }
        ) return@all false
        sorted.all { item ->
            item.content.isSupportedStandardContent(isV2) &&
                item.hasCompatibleV1ParentKind(index, parentKinds) &&
                item.hasResolvableV1Parent(parentItems) &&
                item.hasConsistentInheritedMetadata(index)
        }
    }
}

private fun StoreItem.hasCompatibleKnownParentKind(parentKind: RootKind): Boolean = when (content) {
    is ItemContent.MapEntry -> when {
        content.value is YValue.SubdocRef && parentKind !in setOf(RootKind.Map, RootKind.XmlHook) -> false
        parentSub != null && parentKind == RootKind.XmlFragment -> false
        parentSub != null -> true
        else -> parentKind == RootKind.Map || parentKind == RootKind.XmlHook
    }
    is ItemContent.MapEntries -> when {
        parentSub != null && parentKind == RootKind.XmlFragment -> false
        parentSub != null -> true
        else -> parentKind == RootKind.Map || parentKind == RootKind.XmlHook
    }
    is ItemContent.ArrayValues -> parentSub == null && parentKind == RootKind.Array
    is ItemContent.Value -> parentKind == RootKind.Array
    is ItemContent.Text,
    is ItemContent.TextEmbed,
    is ItemContent.NativeTextFormat -> parentKind == content.kind
    is ItemContent.XmlType -> parentKind == content.kind
    is ItemContent.Deleted -> true
    else -> false
}

private fun StoreItem.hasValidStandaloneTextSurrogates(): Boolean {
    val value = (content as? ItemContent.Text)?.value ?: return true
    var index = 0
    while (index < value.length) {
        val char = value[index]
        when {
            char.isHighSurrogate() -> {
                if (index + 1 >= value.length || !value[index + 1].isLowSurrogate()) return false
                index += 2
            }
            char.isLowSurrogate() -> return false
            else -> index++
        }
    }
    return true
}

private class StandardUpdateIndex(items: List<StoreItem>) {
    data class ParentProfile(
        val allMapEntries: Boolean,
        val allArraySequence: Boolean,
        val sequenceEmpty: Boolean,
        val textLikeKind: RootKind?,
        val singleSequenceKind: RootKind?,
    )

    val itemsByClient: Map<Long, List<StoreItem>> = items
        .groupBy { item -> item.id.client }
        .mapValues { (_, clientItems) -> clientItems.sortedBy { item -> item.id.clock } }

    val parentProfiles: Map<String, ParentProfile> = items.groupBy { item -> item.parent }
        .mapValues { (_, parentItems) ->
            val sequence = parentItems.filter { item -> item.parentSub == null }
            val firstKind = sequence.firstOrNull()?.content?.kind
            val singleSequenceKind = firstKind?.takeIf { kind ->
                sequence.all { item -> item.content.kind == kind }
            }
            val textLikeKind = singleSequenceKind?.takeIf { kind ->
                sequence.all { item ->
                    item.content.kind == kind &&
                        (item.content is ItemContent.Text ||
                            item.content is ItemContent.TextEmbed ||
                            item.content is ItemContent.XmlType ||
                            item.content is ItemContent.NativeTextFormat)
                }
            }
            ParentProfile(
                allMapEntries = parentItems.all { item ->
                    item.content is ItemContent.MapEntry || item.content is ItemContent.MapEntries
                },
                allArraySequence = sequence.all { item -> item.content.kind == RootKind.Array },
                sequenceEmpty = sequence.isEmpty(),
                textLikeKind = textLikeKind,
                singleSequenceKind = singleSequenceKind,
            )
        }

    fun containing(id: Id): StoreItem? {
        val clientItems = itemsByClient[id.client] ?: return null
        var low = 0
        var high = clientItems.lastIndex
        var candidate: StoreItem? = null
        while (low <= high) {
            val middle = (low + high) ushr 1
            val item = clientItems[middle]
            if (item.id.clock <= id.clock) {
                candidate = item
                low = middle + 1
            } else {
                high = middle - 1
            }
        }
        return candidate?.takeIf { item -> item.containsId(id) }
    }
}

internal fun Long.isYjsSafeVarUint(): Boolean = this in 0..YJS_MAX_SAFE_INTEGER

internal fun Id.isYjsSafeId(): Boolean = client.isYjsSafeVarUint() && clock.isYjsSafeVarUint()

private fun StoreItem.hasYjsSafeClocks(): Boolean =
    id.isYjsSafeId() &&
        length.isYjsSafeVarUint() &&
        runCatching { checkedClockAdd(id.clock, length).isYjsSafeVarUint() }.getOrDefault(false) &&
        (origin?.isYjsSafeId() != false) &&
        (rightOrigin?.isYjsSafeId() != false) &&
        (unresolvedParent?.id?.isYjsSafeId() != false)

private fun DeleteSet.hasYjsSafeRanges(): Boolean = clients.all { (client, ranges) ->
    client.isYjsSafeVarUint() && ranges.all { range ->
        range.clock.isYjsSafeVarUint() &&
            range.length.isYjsSafeVarUint() &&
            range.end.isYjsSafeVarUint()
    }
}

private fun StoreItem.hasResolvableV1Parent(parentItems: Map<String, Id>): Boolean {
    val parentItemId = parentItems[parent]
    if (parentItemId == null) {
        return unresolvedParent != null || !parent.startsWith("__yks_nested__:")
    }
    // A nested type must be introduced before content can target it on Yjs wire.
    return parentItemId.client != id.client || parentItemId.clock < id.clock
}

private fun StoreItem.hasConsistentInheritedMetadata(index: StandardUpdateIndex): Boolean {
    val anchor = origin ?: rightOrigin ?: return true
    val anchorItem = index.containing(anchor) ?: return true
    if (unresolvedParent is UnresolvedYjsParent.Inherit) return true
    return anchorItem.parent == parent && anchorItem.parentSub == parentSub
}

private fun List<StoreItem>.containsId(id: Id): Boolean = any { item -> item.containsId(id) }

private fun StoreItem.containsId(id: Id): Boolean =
    id.client == this.id.client &&
        id.clock >= this.id.clock &&
        id.clock < checkedClockAdd(this.id.clock, length)

private fun StoreItem.hasCompatibleV1ParentKind(
    index: StandardUpdateIndex,
    parentKinds: Map<String, RootKind>,
): Boolean {
    val nestedKind = parentKinds[parent]
    val profile = index.parentProfiles[parent]
    return when (content) {
        is ItemContent.MapEntry -> when {
            content.value is YValue.SubdocRef && nestedKind != null &&
                nestedKind !in setOf(RootKind.Map, RootKind.XmlHook) -> false
            parentSub != null && nestedKind == RootKind.XmlFragment -> false
            parentSub != null -> true
            else -> nestedKind?.let { kind -> kind == RootKind.Map || kind == RootKind.XmlHook }
                ?: (profile?.allMapEntries == true)
        }
        is ItemContent.MapEntries -> when {
            parentSub != null && nestedKind == RootKind.XmlFragment -> false
            parentSub != null -> true
            else -> nestedKind?.let { kind -> kind == RootKind.Map || kind == RootKind.XmlHook }
                ?: (profile?.allMapEntries == true)
        }
        is ItemContent.ArrayValues -> nestedKind?.let { kind -> kind == RootKind.Array }
            ?: (profile?.allArraySequence == true)
        is ItemContent.Value -> nestedKind?.let { kind -> kind == RootKind.Array }
            ?: (profile?.allArraySequence == true)
        is ItemContent.Text,
        is ItemContent.TextEmbed,
        is ItemContent.NativeTextFormat -> nestedKind?.let { kind -> kind == content.kind }
            ?: (profile?.sequenceEmpty == true || profile?.textLikeKind == content.kind)
        is ItemContent.XmlType -> {
            val xmlSequenceKinds = setOf(
                RootKind.Array,
                RootKind.Map,
                RootKind.Text,
                RootKind.XmlFragment,
                RootKind.XmlElement,
                RootKind.XmlHook,
                RootKind.XmlText,
            )
            content.kind in xmlSequenceKinds &&
                (nestedKind?.let { kind -> kind == content.kind }
                    ?: (profile?.sequenceEmpty == true || profile?.singleSequenceKind == content.kind))
        }
        is ItemContent.Deleted -> true
        else -> false
    }
}

private fun ItemContent.isSupportedStandardContent(isV2: Boolean): Boolean = when (this) {
    is ItemContent.Value -> kind == RootKind.Array && value.isSupportedAnyValue(topLevel = true)
    is ItemContent.ArrayValues -> values.all { value -> value.isSupportedAnyValue(topLevel = true) }
    is ItemContent.MapEntry -> value.isSupportedAnyValue(topLevel = true)
    is ItemContent.MapEntries -> values.all { value -> value.isSupportedAnyValue(topLevel = true) }
    is ItemContent.Text ->
        kind in setOf(RootKind.Text, RootKind.XmlText) &&
            baseAttributes.isEmpty()
    is ItemContent.TextEmbed ->
        kind in setOf(RootKind.Text, RootKind.XmlText) &&
            baseAttributes.isEmpty() &&
            if (value is YValue.SubdocRef) value.isSupportedAnyValue(topLevel = true)
            else if (isV2) value.isSupportedAnyValue(topLevel = false)
            else value.isSupportedJsonValue()
    is ItemContent.TextFormat -> false
    is ItemContent.NativeTextFormat ->
        kind in setOf(RootKind.Text, RootKind.XmlText) &&
            if (isV2) value.isSupportedAnyValue(topLevel = false) else value.isSupportedJsonValue()
    is ItemContent.XmlNode -> false
    is ItemContent.XmlType ->
        kind in setOf(
            RootKind.Array,
            RootKind.Map,
            RootKind.Text,
            RootKind.XmlFragment,
            RootKind.XmlElement,
            RootKind.XmlHook,
            RootKind.XmlText,
        ) && baseAttributes.isEmpty()
    is ItemContent.Deleted -> true
}

private fun YValue.isSupportedAnyValue(topLevel: Boolean): Boolean = when (this) {
    YValue.Undefined,
    YValue.Null,
    is YValue.Bool,
    is YValue.StringValue,
    is YValue.BinaryValue -> true
    is YValue.LongNumber -> value in -YJS_MAX_SAFE_INTEGER..YJS_MAX_SAFE_INTEGER
    is YValue.DoubleNumber -> true
    is YValue.BigIntNumber -> runCatching { value.longValueExact() }.isSuccess
    is YValue.ListValue -> value.all { nested -> nested.isSupportedAnyValue(topLevel = false) }
    is YValue.MapValue -> value.values.all { nested -> nested.isSupportedAnyValue(topLevel = false) }
    is YValue.TypeRef -> topLevel && kind in setOf(RootKind.Array, RootKind.Map, RootKind.Text)
    is YValue.SubdocRef ->
        topLevel &&
            collectionId == null &&
            !isSuggestionDoc &&
            meta.isSupportedAnyValue(topLevel = false)
}

private fun YValue.isSupportedJsonValue(): Boolean = when (this) {
    YValue.Undefined,
    is YValue.BigIntNumber,
    is YValue.BinaryValue,
    is YValue.TypeRef,
    is YValue.SubdocRef -> false
    YValue.Null,
    is YValue.Bool,
    is YValue.StringValue -> true
    is YValue.LongNumber -> value in -YJS_MAX_SAFE_INTEGER..YJS_MAX_SAFE_INTEGER
    is YValue.DoubleNumber -> value.isFinite()
    is YValue.ListValue -> value.all(YValue::isSupportedJsonValue)
    is YValue.MapValue -> value.values.all(YValue::isSupportedJsonValue)
}

private sealed interface EncodedStruct {
    val clock: Long
    val length: Long

    data class Item(val item: StoreItem) : EncodedStruct {
        override val clock: Long get() = item.id.clock
        override val length: Long get() = item.length
    }

    data class PackedText(
        val first: StoreItem,
        val second: StoreItem,
    ) : EncodedStruct {
        override val clock: Long get() = first.id.clock
        override val length: Long get() = checkedClockAdd(first.length, second.length, "packed text length")
        val value: String get() = (first.content as ItemContent.Text).value + (second.content as ItemContent.Text).value
    }

    data class Skip(override val clock: Long, override val length: Long) : EncodedStruct
}

private sealed interface ParentReference {
    data class Root(val name: String) : ParentReference
    data class Nested(val id: Id) : ParentReference
    data class Inherit(val id: Id) : ParentReference
}

private data class DecodedWireItem(
    val id: Id,
    val origin: Id?,
    val rightOrigin: Id?,
    val parent: ParentReference,
    val parentSub: String?,
    val content: WireContent,
    val isGc: Boolean = false,
) {
    var resolvedParent: String? = null
    var resolvedParentSub: String? = null
    var isParentSubResolved: Boolean = false
    var resolvedKind: RootKind? = null
    var resolvedUnresolvedParent: UnresolvedYjsParent? = null
    var isUnresolvedParentResolved: Boolean = false
    var parentResolutionMark: Int = 0
    var parentSubResolutionMark: Int = 0
    var kindResolutionMark: Int = 0
    var unresolvedParentResolutionMark: Int = 0
}

private sealed interface WireContent {
    val length: Long

    data class Deleted(override val length: Long) : WireContent
    data class Json(val values: List<Any?>) : WireContent { override val length: Long get() = values.size.toLong() }
    data class Binary(val value: ByteArray) : WireContent { override val length: Long = 1 }
    data class StringContent(val value: String) : WireContent { override val length: Long get() = value.length.toLong() }
    data class Embed(val value: Any?) : WireContent { override val length: Long = 1 }
    data class Format(val key: String, val value: Any?) : WireContent { override val length: Long = 1 }
    data class Type(val kind: RootKind, val name: String, val nodeName: String) : WireContent { override val length: Long = 1 }
    data class AnyContent(val values: List<Any?>) : WireContent { override val length: Long get() = values.size.toLong() }
    data class Doc(val guid: String, val options: Any?, val instanceId: String) : WireContent {
        override val length: Long = 1
    }
}

private fun List<StoreItem>.withClockSkips(): List<EncodedStruct> {
    val result = mutableListOf<EncodedStruct>()
    var nextClock = first().id.clock
    var index = 0
    while (index < size) {
        val item = this[index]
        if (item.id.clock > nextClock) {
            result.add(EncodedStruct.Skip(nextClock, item.id.clock - nextClock))
        }
        if (item.id.clock >= nextClock) {
            val next = getOrNull(index + 1)
            val encoded = if (next != null && item.canPackTextWith(next)) {
                index++
                EncodedStruct.PackedText(item, next)
            } else {
                EncodedStruct.Item(item)
            }
            result.add(encoded)
            nextClock = checkedClockAdd(encoded.clock, encoded.length)
        }
        index++
    }
    return result
}

private fun List<StoreItem>.hasValidTextSurrogatePairs(): Boolean {
    var highSurrogateItem: StoreItem? = null
    for (item in this) {
        val text = (item.content as? ItemContent.Text)?.value ?: continue
        for (char in text) {
            val pendingItem = highSurrogateItem
            if (pendingItem != null) {
                if (!char.isLowSurrogate()) return false
                if (pendingItem !== item && !pendingItem.canPackTextWith(item)) return false
                highSurrogateItem = null
            } else {
                when {
                    char.isHighSurrogate() -> highSurrogateItem = item
                    char.isLowSurrogate() -> return false
                }
            }
        }
    }
    return highSurrogateItem == null
}

private fun StoreItem.canPackTextWith(right: StoreItem): Boolean {
    val leftContent = content as? ItemContent.Text ?: return false
    val rightContent = right.content as? ItemContent.Text ?: return false
    return right.id.client == id.client &&
        right.id.clock == checkedClockAdd(id.clock, length, "packed text right clock") &&
        right.origin == lastId &&
        right.rightOrigin == rightOrigin &&
        right.parent == parent &&
        right.parentSub == parentSub &&
        right.deleted == deleted &&
        right.isGc == isGc &&
        right.requiresClockContinuity == requiresClockContinuity &&
        right.unresolvedParent == unresolvedParent &&
        rightContent.kind == leftContent.kind &&
        rightContent.attributes == leftContent.attributes &&
        rightContent.baseAttributes == leftContent.baseAttributes
}

private fun List<DecodedWireItem>.toStoreItems(): List<StoreItem> {
    val itemsByClient = linkedMapOf<Long, MutableList<DecodedWireItem>>()
    for (item in this) itemsByClient.getOrPut(item.id.client) { mutableListOf() }.add(item)
    itemsByClient.values.forEach { items ->
        var sorted = true
        var index = 1
        while (index < items.size) {
            if (items[index - 1].id.clock > items[index].id.clock) {
                sorted = false
                break
            }
            index++
        }
        if (!sorted) items.sortBy { item -> item.id.clock }
    }
    val denseUnitClockStarts = itemsByClient.mapValues { (_, items) ->
        val firstClock = items.firstOrNull()?.id?.clock ?: return@mapValues null
        var index = 0
        while (index < items.size) {
            val item = items[index]
            if (item.content.length != 1L || item.id.clock != checkedClockAdd(firstClock, index.toLong())) {
                return@mapValues null
            }
            index++
        }
        firstClock
    }

    var cachedClient = -1L
    var cachedClientItems: List<DecodedWireItem> = emptyList()
    var cachedDenseUnitClockStart: Long? = null
    fun containing(id: Id): DecodedWireItem? {
        if (cachedClient != id.client) {
            cachedClient = id.client
            cachedClientItems = itemsByClient[id.client] ?: emptyList()
            cachedDenseUnitClockStart = denseUnitClockStarts[id.client]
        }
        val items = cachedClientItems
        if (items.isEmpty()) return null
        cachedDenseUnitClockStart?.let { firstClock ->
            val index = id.clock - firstClock
            if (index >= 0 && index < items.size.toLong()) return items[index.toInt()]
            return null
        }
        var low = 0
        var high = items.lastIndex
        var candidate: DecodedWireItem? = null
        while (low <= high) {
            val middle = (low + high) ushr 1
            val item = items[middle]
            if (item.id.clock <= id.clock) {
                candidate = item
                low = middle + 1
            } else {
                high = middle - 1
            }
        }
        return candidate?.takeIf { item ->
            id.clock < checkedClockAdd(item.id.clock, item.content.length)
        }
    }

    val parentPath = ArrayList<DecodedWireItem>()
    var parentResolutionGeneration = 0
    fun resolveParent(item: DecodedWireItem): String {
        item.resolvedParent?.let { return it }
        val generation = ++parentResolutionGeneration
        parentPath.clear()
        var current = item
        var result: String
        while (true) {
            current.resolvedParent?.let { cached ->
                result = cached
                break
            }
            check(current.parentResolutionMark != generation) {
                "cyclic Yjs parent reference at ${current.id}"
            }
            current.parentResolutionMark = generation
            parentPath.add(current)
            when (val reference = current.parent) {
                is ParentReference.Root -> {
                    result = reference.name
                    break
                }
                is ParentReference.Nested -> {
                    result = containing(reference.id)?.content
                        ?.let { content -> (content as? WireContent.Type)?.name }
                        ?: nestedParentAlias(reference.id)
                    break
                }
                is ParentReference.Inherit -> {
                    val anchor = containing(reference.id)
                    if (anchor == null) {
                        result = inheritedParentName(reference.id)
                        break
                    }
                    current = anchor
                }
            }
        }
        parentPath.forEach { candidate -> candidate.resolvedParent = result }
        return result
    }

    val parentSubPath = ArrayList<DecodedWireItem>()
    var parentSubResolutionGeneration = 0
    fun resolveParentSub(item: DecodedWireItem): String? {
        if (item.isParentSubResolved) return item.resolvedParentSub
        val generation = ++parentSubResolutionGeneration
        parentSubPath.clear()
        var current = item
        var result: String?
        while (true) {
            if (current.isParentSubResolved) {
                result = current.resolvedParentSub
                break
            }
            check(current.parentSubResolutionMark != generation) {
                "cyclic Yjs parent-sub reference at ${current.id}"
            }
            current.parentSubResolutionMark = generation
            parentSubPath.add(current)
            when (val reference = current.parent) {
                is ParentReference.Root,
                is ParentReference.Nested -> {
                    result = current.parentSub
                    break
                }
                is ParentReference.Inherit -> {
                    val anchor = containing(reference.id)
                    if (anchor == null) {
                        result = null
                        break
                    }
                    current = anchor
                }
            }
        }
        parentSubPath.forEach { candidate ->
            candidate.resolvedParentSub = result
            candidate.isParentSubResolved = true
        }
        return result
    }

    val kindPath = ArrayList<DecodedWireItem>()
    var kindResolutionGeneration = 0
    fun resolveKind(item: DecodedWireItem): RootKind {
        item.resolvedKind?.let { return it }
        val generation = ++kindResolutionGeneration
        kindPath.clear()
        var current = item
        while (current.resolvedKind == null) {
            check(current.kindResolutionMark != generation) {
                "cyclic Yjs kind reference at ${current.id}"
            }
            current.kindResolutionMark = generation
            kindPath.add(current)
            val reference = current.parent as? ParentReference.Inherit ?: break
            val anchor = containing(reference.id)?.takeUnless { candidate ->
                candidate.content is WireContent.Deleted
            } ?: break
            current = anchor
        }
        var pathIndex = kindPath.lastIndex
        while (pathIndex >= 0) {
            val candidate = kindPath[pathIndex--]
            if (candidate.resolvedKind != null) continue
            val parentSub = resolveParentSub(candidate)
            val ownerKind = when (val reference = candidate.parent) {
                is ParentReference.Nested -> (containing(reference.id)?.content as? WireContent.Type)?.kind
                is ParentReference.Inherit -> containing(reference.id)
                    ?.takeUnless { anchor -> anchor.content is WireContent.Deleted }
                    ?.resolvedKind
                is ParentReference.Root -> null
            }
            candidate.resolvedKind = if (parentSub != null) {
                ownerKind?.takeIf { it == RootKind.XmlHook } ?: RootKind.Map
            } else {
                candidate.content.definitiveSequenceKindOrNull()
                    ?: ownerKind
                    ?: candidate.content.inferRootKind(parentSub)
            }
        }
        return checkNotNull(item.resolvedKind) { "failed to resolve Yjs kind for ${item.id}" }
    }

    fun resolvesToGc(item: DecodedWireItem): Boolean {
        if (item.isGc) return true
        if (item.origin?.let(::containing)?.isGc == true) return true
        if (item.rightOrigin?.let(::containing)?.isGc == true) return true
        val parent = item.parent as? ParentReference.Nested ?: return false
        return containing(parent.id)?.isGc == true
    }

    val unresolvedParentPath = ArrayList<DecodedWireItem>()
    var unresolvedParentResolutionGeneration = 0
    fun unresolvedParent(item: DecodedWireItem): UnresolvedYjsParent? {
        if (item.isUnresolvedParentResolved) return item.resolvedUnresolvedParent
        val generation = ++unresolvedParentResolutionGeneration
        unresolvedParentPath.clear()
        var current = item
        while (!current.isUnresolvedParentResolved) {
            check(current.unresolvedParentResolutionMark != generation) {
                "cyclic Yjs unresolved parent reference at ${current.id}"
            }
            current.unresolvedParentResolutionMark = generation
            unresolvedParentPath.add(current)
            val reference = current.parent as? ParentReference.Inherit ?: break
            current = containing(reference.id) ?: break
        }
        var pathIndex = unresolvedParentPath.lastIndex
        while (pathIndex >= 0) {
            val candidate = unresolvedParentPath[pathIndex--]
            if (candidate.isUnresolvedParentResolved) continue
            candidate.resolvedUnresolvedParent = when (val reference = candidate.parent) {
                is ParentReference.Root -> null
                is ParentReference.Nested -> if (containing(reference.id) == null) {
                    UnresolvedYjsParent.Nested(reference.id)
                } else {
                    null
                }
                is ParentReference.Inherit -> {
                    val anchor = containing(reference.id)
                    if (anchor == null || anchor.resolvedUnresolvedParent != null) {
                        UnresolvedYjsParent.Inherit(reference.id)
                    } else {
                        null
                    }
                }
            }
            candidate.isUnresolvedParentResolved = true
        }
        return item.resolvedUnresolvedParent
    }

    val result = ArrayList<StoreItem>(size)
    for (item in this) {
        val isGc = resolvesToGc(item)
        val parent = if (isGc) gcParentName(item.id.client) else resolveParent(item)
        val parentSub = if (isGc) null else resolveParentSub(item)
        val kind = if (isGc) RootKind.Array else resolveKind(item)
        if (isGc || item.content is WireContent.Deleted) {
            result.add(
                StoreItem(
                    id = item.id,
                    origin = item.origin,
                    rightOrigin = item.rightOrigin,
                    parent = parent,
                    parentSub = parentSub,
                    content = ItemContent.Deleted(kind, item.content.length),
                    deleted = true,
                    requiresClockContinuity = true,
                    isGc = isGc,
                    unresolvedParent = if (isGc) null else unresolvedParent(item),
                ),
            )
            continue
        }
        val unresolvedParent = unresolvedParent(item)
        val singleContent = item.content.toSingleItemContentOrNull(kind)
        if (singleContent != null) {
            check(singleContent.clockLength == item.content.length) {
                "decoded content length ${singleContent.clockLength} does not match ${item.content.length}"
            }
            result.add(
                StoreItem(
                    id = item.id,
                    origin = item.origin,
                    rightOrigin = item.rightOrigin,
                    parent = parent,
                    parentSub = parentSub,
                    content = singleContent,
                    deleted = false,
                    requiresClockContinuity = true,
                    isGc = false,
                    unresolvedParent = unresolvedParent,
                ),
            )
            continue
        }
        val contents = item.content.toMultipleItemContents(kind)
        val contentLength = contents.sumOf { content -> content.clockLength }
        check(contentLength == item.content.length) {
            "decoded content length $contentLength does not match ${item.content.length}"
        }
        var clockOffset = 0L
        for (content in contents) {
            val storeItem = StoreItem(
                id = if (clockOffset == 0L) {
                    item.id
                } else {
                    Id(item.id.client, checkedClockAdd(item.id.clock, clockOffset))
                },
                origin = if (clockOffset == 0L) {
                    item.origin
                } else {
                    Id(item.id.client, checkedClockAdd(item.id.clock, clockOffset - 1))
                },
                rightOrigin = item.rightOrigin,
                parent = parent,
                parentSub = parentSub,
                content = content,
                deleted = false,
                requiresClockContinuity = true,
                isGc = false,
                unresolvedParent = unresolvedParent,
            )
            clockOffset = checkedClockAdd(clockOffset, storeItem.length, "decoded content offset")
            result.add(storeItem)
        }
    }
    return result
}

private fun WireContent.definitiveSequenceKindOrNull(): RootKind? = when (this) {
    is WireContent.StringContent,
    is WireContent.Embed,
    is WireContent.Format -> RootKind.Text
    is WireContent.Json,
    is WireContent.Binary,
    is WireContent.AnyContent -> RootKind.Array
    is WireContent.Deleted,
    is WireContent.Doc,
    is WireContent.Type -> null
}

private fun WireContent.inferRootKind(parentSub: String?): RootKind = when {
    parentSub != null -> RootKind.Map
    this is WireContent.StringContent || this is WireContent.Embed || this is WireContent.Format -> RootKind.Text
    this is WireContent.Type && kind in setOf(RootKind.XmlElement, RootKind.XmlHook, RootKind.XmlText) -> RootKind.XmlFragment
    else -> RootKind.Array
}

private fun WireContent.toSingleItemContentOrNull(kind: RootKind): ItemContent? = when (this) {
    is WireContent.Deleted -> ItemContent.Deleted(kind, length)
    is WireContent.StringContent -> {
        value.length.toLong().toDecodedCount("text UTF-16 length")
        ItemContent.Text(value, kind = kind)
    }
    is WireContent.Embed -> ItemContent.TextEmbed(YValue.from(value), kind = kind)
    is WireContent.Format -> toItemContent(kind)
    is WireContent.Binary -> value.toSequenceContent(kind)
    is WireContent.Json -> if (values.size > 1) {
        val packedValues = values.map(YValue::from)
        when (kind) {
            RootKind.Array -> ItemContent.ArrayValues(packedValues)
            RootKind.Map -> ItemContent.MapEntries(packedValues)
            else -> null
        }
    } else {
        null
    }
    is WireContent.AnyContent -> if (values.size > 1) {
        val packedValues = values.map(YValue::from)
        when (kind) {
            RootKind.Array -> ItemContent.ArrayValues(packedValues)
            RootKind.Map -> ItemContent.MapEntries(packedValues)
            else -> null
        }
    } else {
        null
    }
    is WireContent.Type ->
        if (
            kind == RootKind.XmlFragment ||
            kind == RootKind.XmlElement ||
            kind == RootKind.XmlHook ||
            kind == RootKind.Array ||
            kind == RootKind.Map ||
            kind == RootKind.Text ||
            kind == RootKind.XmlText
        ) {
            ItemContent.XmlType(YValue.TypeRef(this.kind, name), nodeName, kind)
        } else {
            val value = YValue.TypeRef(this.kind, name)
            if (kind == RootKind.Map) ItemContent.MapEntry(value) else ItemContent.Value(value)
        }
    is WireContent.Doc -> options.toSubdocValue(guid, instanceId).toSequenceContent(kind)
}

private fun WireContent.toMultipleItemContents(kind: RootKind): List<ItemContent> = when (this) {
    is WireContent.Json -> values.map { value -> value.toSequenceContent(kind) }
    is WireContent.AnyContent -> values.map { value -> value.toSequenceContent(kind) }
    else -> error("wire content ${this::class.simpleName} has a single item representation")
}

private fun WireContent.Format.toItemContent(kind: RootKind): ItemContent =
    ItemContent.NativeTextFormat(key, YValue.from(value), kind)

private fun Any?.toSequenceContent(kind: RootKind): ItemContent {
    val value = YValue.from(this)
    return when (kind) {
        RootKind.Map,
        RootKind.XmlHook -> ItemContent.MapEntry(value)
        RootKind.Text,
        RootKind.XmlText -> ItemContent.TextEmbed(value, kind = kind)
        RootKind.XmlFragment,
        RootKind.XmlElement -> ItemContent.XmlNode(xmlNodeFromDeltaValue(this).toValue(), kind)
        RootKind.Array -> ItemContent.Value(value)
    }
}

private fun Any?.toSubdocValue(guid: String, instanceId: String): YValue.SubdocRef {
    val options = this as? Map<*, *> ?: error("Yjs subdocument options must be an object")
    val autoLoad = options["autoLoad"] as? Boolean ?: false
    return YValue.SubdocRef(
        guid = guid,
        gc = options["gc"] as? Boolean ?: true,
        shouldLoad = (options["shouldLoad"] as? Boolean ?: false) || autoLoad,
        autoLoad = autoLoad,
        instanceId = instanceId,
        collectionId = options["collectionId"] as? String ?: options["collectionid"] as? String,
        meta = YValue.from(options["meta"]),
        isSuggestionDoc = false,
    )
}

private fun ItemContent.yjsContentRef(): Int = when (this) {
    is ItemContent.Deleted -> contentDeletedRefNumber
    is ItemContent.Text -> contentStringRefNumber
    is ItemContent.TextEmbed -> {
        value.yjsContentRef().takeIf { it == contentDocRefNumber } ?: contentEmbedRefNumber
    }
    is ItemContent.TextFormat -> contentFormatRefNumber
    is ItemContent.NativeTextFormat -> contentFormatRefNumber
    is ItemContent.XmlType -> contentTypeRefNumber
    is ItemContent.XmlNode -> contentAnyRefNumber
    is ItemContent.Value -> value.yjsContentRef()
    is ItemContent.ArrayValues -> contentAnyRefNumber
    is ItemContent.MapEntry -> value.yjsContentRef()
    is ItemContent.MapEntries -> contentAnyRefNumber
}

private fun YValue.yjsContentRef(): Int = when (this) {
    is YValue.BinaryValue -> contentBinaryRefNumber
    is YValue.TypeRef -> contentTypeRefNumber
    is YValue.SubdocRef -> contentDocRefNumber
    else -> contentAnyRefNumber
}

private fun ItemContent.TextFormat.toWireValue(): Map<String, Any?> = mapOf(
    "target" to mapOf("client" to target.client, "clock" to target.clock),
    "length" to length,
    "attributes" to attributes.mapValues { (_, value) -> value.toAny() },
    "afterAttributes" to afterAttributes.mapValues { (_, value) -> value.toAny() },
    "beforeAttributes" to beforeAttributes.map { attributes -> attributes.mapValues { (_, value) -> value.toAny() } },
)

private fun RootKind.typeRefId(): Int = when (this) {
    RootKind.Array -> YArrayRefID
    RootKind.Map -> YMapRefID
    RootKind.Text -> YTextRefID
    RootKind.XmlElement -> YXmlElementRefID
    RootKind.XmlFragment -> YXmlFragmentRefID
    RootKind.XmlHook -> YXmlHookRefID
    RootKind.XmlText -> YXmlTextRefID
}

private fun BinaryEncoder.writeId(id: Id) {
    writeVarUInt(id.client)
    writeVarUInt(id.clock)
}

private fun BinaryDecoder.readId(): Id = Id(readVarUInt(), readVarUInt())

private fun nestedTypeName(id: Id): String = "__yks_yjs_nested__:${id.client}:${id.clock}"
private fun nestedParentAlias(id: Id): String = "__yjs_nested__:${id.client}:${id.clock}"
private fun inheritedParentName(id: Id): String = "__yjs_inherit__:${id.client}:${id.clock}"
private fun gcParentName(client: Long): String = "__yjs_gc__:$client"

private fun ByteArray.hasLegacyMagic(): Boolean =
    size >= 4 && this[0] == 'Y'.code.toByte() && this[1] == 'K'.code.toByte() &&
        this[2] == 'S'.code.toByte() && this[3] in setOf(1.toByte(), 2.toByte(), 3.toByte(), 4.toByte(), 5.toByte())
