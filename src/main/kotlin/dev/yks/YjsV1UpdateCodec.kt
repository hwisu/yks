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
public class UnsupportedYjsStandardUpdateException internal constructor(
    public val format: String,
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

    /** Decode only the genuine V1 envelope accepted by Yjs' `applyUpdate`. */
    fun decodeStandard(
        bytes: ByteArray,
        maxStructs: Int = MAX_DECODED_COLLECTION_SIZE,
        maxDeleteRanges: Int = MAX_DECODED_COLLECTION_SIZE,
    ): DocumentUpdate = decodeBoundary("Yjs update V1") {
        requireStandardYjsUpdateInput(bytes, "V1")
        decodeV1(
            BinaryDecoder(bytes),
            budget = DecodedUpdateBudget(maxStructs, maxDeleteRanges),
        )
    }

    fun parseMeta(bytes: ByteArray): UpdateMeta = decodeBoundary("Yjs update V1 metadata") {
        if (bytes.hasLegacyMagic()) return@decodeBoundary LegacyUpdateCodec.decode(bytes).toUpdateMeta()
        val from = linkedMapOf<Long, Long>()
        val to = linkedMapOf<Long, Long>()
        decodeV1(BinaryDecoder(bytes), from, to)
        UpdateMeta(from, to)
    }

    fun parseStandardMeta(bytes: ByteArray): UpdateMeta = decodeBoundary("Yjs update V1 metadata") {
        requireStandardYjsUpdateInput(bytes, "V1")
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

    /** Decode only the genuine V2 envelope accepted by Yjs' `applyUpdateV2`. */
    fun decodeStandardV2(
        bytes: ByteArray,
        maxStructs: Int = MAX_DECODED_COLLECTION_SIZE,
        maxDeleteRanges: Int = MAX_DECODED_COLLECTION_SIZE,
    ): DocumentUpdate = decodeBoundary("Yjs update V2") {
        requireStandardYjsUpdateInput(bytes, "V2")
        val decoder = UpdateDecoderV2(bytes)
        check(!decoder.usesLegacyRest) { "expected a Yjs V2 update envelope" }
        decodeV2(decoder, maxStructs = maxStructs, maxDeleteRanges = maxDeleteRanges)
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

    fun parseStandardMetaV2(bytes: ByteArray): UpdateMeta = decodeBoundary("Yjs update V2 metadata") {
        requireStandardYjsUpdateInput(bytes, "V2")
        val decoder = UpdateDecoderV2(bytes)
        check(!decoder.usesLegacyRest) { "expected a Yjs V2 update envelope" }
        val from = linkedMapOf<Long, Long>()
        val to = linkedMapOf<Long, Long>()
        decodeV2(decoder, from, to)
        UpdateMeta(from, to)
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
        val decoded = decodeWireV2(
            decoder,
            metaFrom,
            metaTo,
            DecodedUpdateBudget(maxStructs, maxDeleteRanges),
        )
        DocumentUpdate(decoded.items.toStoreItems(), decoded.deleteSet)
    }

    private fun decodeWireV2(
        decoder: UpdateDecoderV2,
        metaFrom: MutableMap<Long, Long>? = null,
        metaTo: MutableMap<Long, Long>? = null,
        budget: DecodedUpdateBudget,
    ): WireDocumentUpdate {
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
                    structSkipRefNumber -> {
                        val length = decoder.restDecoder.readVarUInt()
                        structs.add(
                            DecodedWireItem(
                                Id(client, clock),
                                null,
                                null,
                                ParentReference.Root(skipParentName(client)),
                                null,
                                WireContent.Deleted(length),
                                isSkip = true,
                            ),
                        )
                        clock = checkedClockAdd(clock, length)
                    }
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
        return WireDocumentUpdate(structs, deleteSet)
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
        val decoded = decodeWireV1(decoder, metaFrom, metaTo, budget)
        return DocumentUpdate(decoded.items.toStoreItems(), decoded.deleteSet)
    }

    /**
     * Merge genuine V1 inputs without first projecting their wire inheritance into YKS'
     * richer semantic item model. Upstream Yjs merges lazy wire structs for the same reason:
     * an incremental XML/text item may inherit formatting and parent data from an earlier
     * update even though their union remains a perfectly valid standard update.
     */
    fun mergeStandardV1(updates: List<ByteArray>): ByteArray = decodeBoundary("merged Yjs update V1") {
        if (updates.size == 1) {
            decodeStandardWireV1(updates.single())
            return@decodeBoundary updates.single().copyOf()
        }
        val decoded = updates.map(::decodeStandardWireV1)
        val deleteSet = DeleteSet.empty()
        decoded.forEach { update -> deleteSet.addAll(update.deleteSet) }
        encodeWireV1(mergeWireItems(decoded.flatMap(WireDocumentUpdate::items)), deleteSet)
    }

    fun mergeStandardV2(updates: List<ByteArray>): ByteArray = decodeBoundary("merged Yjs update V2") {
        val decoded = updates.map(::decodeStandardWireV2)
        val deleteSet = DeleteSet.empty()
        decoded.forEach { update -> deleteSet.addAll(update.deleteSet) }
        encodeWireV2(mergeWireItems(decoded.flatMap(WireDocumentUpdate::items)), deleteSet)
    }

    fun normalizeStandardV1(update: ByteArray): ByteArray = decodeBoundary("converted Yjs update V1") {
        decodeStandardWireV1(update).let { decoded -> encodeWireV1(decoded.items, decoded.deleteSet) }
    }

    fun convertStandardV1ToV2(update: ByteArray): ByteArray = decodeBoundary("converted Yjs update V2") {
        decodeStandardWireV1(update).let { decoded -> encodeWireV2(decoded.items, decoded.deleteSet) }
    }

    fun convertStandardV2ToV1(update: ByteArray): ByteArray = decodeBoundary("converted Yjs update V1") {
        decodeStandardWireV2(update).let { decoded -> encodeWireV1(decoded.items, decoded.deleteSet) }
    }

    fun diffStandardV1(update: ByteArray, stateVector: StateVector): ByteArray =
        decodeBoundary("diffed Yjs update V1") {
            val decoded = decodeStandardWireV1(update)
            encodeWireV1(decoded.items.afterStateVector(stateVector), decoded.deleteSet)
        }

    fun diffStandardV2(update: ByteArray, stateVector: StateVector): ByteArray =
        decodeBoundary("diffed Yjs update V2") {
            val decoded = decodeStandardWireV2(update)
            encodeWireV2(decoded.items.afterStateVector(stateVector), decoded.deleteSet)
        }

    fun intersectStandardV1(update: ByteArray, contentIds: ContentIds): ByteArray =
        decodeBoundary("intersected Yjs update V1") {
            val decoded = decodeStandardWireV1(update)
            if (decoded.isFullySelectedBy(contentIds)) {
                return@decodeBoundary encodeWireV1(decoded.items, decoded.deleteSet)
            }
            encodeWireV1(
                decoded.items.intersectClockRanges(contentIds.inserts),
                intersectSets(decoded.deleteSet.toIdSet(), contentIds.deletes).toDeleteSet(),
            )
        }

    fun intersectStandardV2(update: ByteArray, contentIds: ContentIds): ByteArray =
        decodeBoundary("intersected Yjs update V2") {
            val decoded = decodeStandardWireV2(update)
            if (decoded.isFullySelectedBy(contentIds)) {
                return@decodeBoundary encodeWireV2(decoded.items, decoded.deleteSet)
            }
            encodeWireV2(
                decoded.items.intersectClockRanges(contentIds.inserts),
                intersectSets(decoded.deleteSet.toIdSet(), contentIds.deletes).toDeleteSet(),
            )
        }

    fun obfuscateStandardV1(update: ByteArray, options: ObfuscatorOptions): ByteArray =
        decodeBoundary("obfuscated Yjs update V1") {
            val decoded = decodeStandardWireV1(update)
            val obfuscator = WireUpdateObfuscator(options)
            encodeWireV1(decoded.items.map(obfuscator::obfuscate), decoded.deleteSet)
        }

    fun obfuscateStandardV2(update: ByteArray, options: ObfuscatorOptions): ByteArray =
        decodeBoundary("obfuscated Yjs update V2") {
            val decoded = decodeStandardWireV2(update)
            val obfuscator = WireUpdateObfuscator(options)
            encodeWireV2(decoded.items.map(obfuscator::obfuscate), decoded.deleteSet)
        }

    fun contentIdsStandardV1(update: ByteArray): ContentIds = decodeBoundary("Yjs update V1 content ids") {
        decodeStandardWireV1(update).toContentIds()
    }

    fun contentIdsStandardV2(update: ByteArray): ContentIds = decodeBoundary("Yjs update V2 content ids") {
        decodeStandardWireV2(update).toContentIds()
    }

    private fun decodeStandardWireV1(update: ByteArray): WireDocumentUpdate {
        requireStandardYjsUpdateInput(update, "V1")
        return decodeWireV1(
            decoder = BinaryDecoder(update),
            budget = DecodedUpdateBudget(MAX_DECODED_COLLECTION_SIZE, MAX_DECODED_COLLECTION_SIZE),
        )
    }

    private fun decodeStandardWireV2(update: ByteArray): WireDocumentUpdate {
        requireStandardYjsUpdateInput(update, "V2")
        return decodeWireV2(
            decoder = UpdateDecoderV2(update),
            budget = DecodedUpdateBudget(MAX_DECODED_COLLECTION_SIZE, MAX_DECODED_COLLECTION_SIZE),
        )
    }

    private fun decodeWireV1(
        decoder: BinaryDecoder,
        metaFrom: MutableMap<Long, Long>? = null,
        metaTo: MutableMap<Long, Long>? = null,
        budget: DecodedUpdateBudget,
    ): WireDocumentUpdate {
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
                    structSkipRefNumber -> {
                        val length = decoder.readVarUInt()
                        structs.add(
                            DecodedWireItem(
                                id = Id(client, clock),
                                origin = null,
                                rightOrigin = null,
                                parent = ParentReference.Root(skipParentName(client)),
                                parentSub = null,
                                content = WireContent.Deleted(length),
                                isSkip = true,
                            ),
                        )
                        clock = checkedClockAdd(clock, length)
                    }
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
        return WireDocumentUpdate(structs, deleteSet)
    }

    private fun encodeWireV1(items: List<DecodedWireItem>, deleteSet: DeleteSet): ByteArray {
        val encoder = BinaryEncoder()
        val itemsByClient = items.groupBy { item -> item.id.client }
        encoder.writeVarUInt(itemsByClient.size.toLong())
        itemsByClient.toSortedMap(compareByDescending { it }).forEach { (client, clientItems) ->
            val sorted = clientItems.sortedBy { item -> item.id.clock }
            var nextClock = sorted.first().id.clock
            var structCount = sorted.size
            sorted.forEach { item ->
                if (item.id.clock > nextClock) structCount++
                nextClock = checkedClockAdd(item.id.clock, item.content.length, "merged wire item end")
            }
            encoder.writeVarUInt(structCount.toLong())
            encoder.writeVarUInt(client)
            encoder.writeVarUInt(sorted.first().id.clock)
            nextClock = sorted.first().id.clock
            sorted.forEach { item ->
                if (item.id.clock > nextClock) {
                    encoder.writeByte(structSkipRefNumber)
                    encoder.writeVarUInt(item.id.clock - nextClock)
                }
                writeWireItem(encoder, item)
                nextClock = checkedClockAdd(item.id.clock, item.content.length, "merged wire item end")
            }
        }
        writeDeleteSet(encoder, deleteSet)
        return encoder.toByteArray()
    }

    private fun encodeWireV2(items: List<DecodedWireItem>, deleteSet: DeleteSet): ByteArray {
        val encoder = UpdateEncoderV2()
        encoder.forceV2Envelope()
        val itemsByClient = items.groupBy { item -> item.id.client }
        encoder.restEncoder.writeVarUInt(itemsByClient.size.toLong())
        itemsByClient.toSortedMap(compareByDescending { it }).forEach { (client, clientItems) ->
            val sorted = clientItems.sortedBy { item -> item.id.clock }
            var nextClock = sorted.first().id.clock
            var structCount = sorted.size
            sorted.forEach { item ->
                if (item.id.clock > nextClock) structCount++
                nextClock = checkedClockAdd(item.id.clock, item.content.length, "merged V2 wire item end")
            }
            encoder.restEncoder.writeVarUInt(structCount.toLong())
            encoder.writeClient(client)
            encoder.restEncoder.writeVarUInt(sorted.first().id.clock)
            nextClock = sorted.first().id.clock
            sorted.forEach { item ->
                if (item.id.clock > nextClock) {
                    encoder.writeInfo(structSkipRefNumber)
                    encoder.restEncoder.writeVarUInt(item.id.clock - nextClock)
                }
                writeWireItemV2(encoder, item)
                nextClock = checkedClockAdd(item.id.clock, item.content.length, "merged V2 wire item end")
            }
        }
        writeDeleteSetV2(encoder, deleteSet)
        return encoder.toByteArray()
    }

    private fun writeWireItem(encoder: BinaryEncoder, item: DecodedWireItem) {
        if (item.isSkip) {
            encoder.writeByte(structSkipRefNumber)
            encoder.writeVarUInt(item.content.length)
            return
        }
        if (item.isGc) {
            encoder.writeByte(structGCRefNumber)
            encoder.writeVarUInt(item.content.length)
            return
        }
        val info = item.content.refNumber() or
            (if (item.origin != null) INFO_HAS_ORIGIN else 0) or
            (if (item.rightOrigin != null) INFO_HAS_RIGHT_ORIGIN else 0) or
            (if (item.parentSub != null) INFO_HAS_PARENT_SUB else 0)
        encoder.writeByte(info)
        item.origin?.let(encoder::writeId)
        item.rightOrigin?.let(encoder::writeId)
        if (item.origin == null && item.rightOrigin == null) {
            when (val parent = item.parent) {
                is ParentReference.Root -> {
                    encoder.writeVarUInt(1)
                    encoder.writeString(parent.name)
                }
                is ParentReference.Nested -> {
                    encoder.writeVarUInt(0)
                    encoder.writeId(parent.id)
                }
                is ParentReference.Inherit -> error("wire item without origins cannot inherit its parent")
            }
            item.parentSub?.let(encoder::writeString)
        }
        writeWireContent(encoder, item.content)
    }

    private fun writeWireItemV2(encoder: UpdateEncoderV2, item: DecodedWireItem) {
        if (item.isSkip) {
            encoder.writeInfo(structSkipRefNumber)
            encoder.restEncoder.writeVarUInt(item.content.length)
            return
        }
        if (item.isGc) {
            encoder.writeInfo(structGCRefNumber)
            encoder.writeLen(item.content.length)
            return
        }
        val info = item.content.refNumber() or
            (if (item.origin != null) INFO_HAS_ORIGIN else 0) or
            (if (item.rightOrigin != null) INFO_HAS_RIGHT_ORIGIN else 0) or
            (if (item.parentSub != null) INFO_HAS_PARENT_SUB else 0)
        encoder.writeInfo(info)
        item.origin?.let(encoder::writeLeftID)
        item.rightOrigin?.let(encoder::writeRightID)
        if (item.origin == null && item.rightOrigin == null) {
            when (val parent = item.parent) {
                is ParentReference.Root -> {
                    encoder.writeParentInfo(true)
                    encoder.writeString(parent.name)
                }
                is ParentReference.Nested -> {
                    encoder.writeParentInfo(false)
                    encoder.writeLeftID(parent.id)
                }
                is ParentReference.Inherit -> error("V2 wire item without origins cannot inherit its parent")
            }
            item.parentSub?.let(encoder::writeString)
        }
        writeWireContentV2(encoder, item.content)
    }

    private fun writeWireContent(encoder: BinaryEncoder, content: WireContent) {
        when (content) {
            is WireContent.Deleted -> encoder.writeVarUInt(content.length)
            is WireContent.Json -> {
                encoder.writeVarUInt(content.values.size.toLong())
                content.values.forEach { value ->
                    encoder.writeString(if (value === WireJsonUndefined) "undefined" else toJsonLiteral(value))
                }
            }
            is WireContent.Binary -> encoder.writeBytes(content.value)
            is WireContent.StringContent -> encoder.writeString(content.value)
            is WireContent.Embed -> encoder.writeString(toJsonLiteral(content.value))
            is WireContent.Format -> {
                encoder.writeString(content.key)
                encoder.writeString(toJsonLiteral(content.value))
            }
            is WireContent.Type -> writeTypeContent(encoder, content.kind, content.nodeName)
            is WireContent.AnyContent -> {
                encoder.writeVarUInt(content.values.size.toLong())
                content.values.forEach { value -> writeLib0Any(encoder, value) }
            }
            is WireContent.Doc -> {
                encoder.writeString(content.guid)
                writeLib0Any(encoder, content.options)
            }
        }
    }

    private fun writeWireContentV2(encoder: UpdateEncoderV2, content: WireContent) {
        when (content) {
            is WireContent.Deleted -> encoder.writeLen(content.length)
            is WireContent.Json -> {
                encoder.writeLen(content.values.size.toLong())
                content.values.forEach { value ->
                    encoder.writeString(if (value === WireJsonUndefined) "undefined" else toJsonLiteral(value))
                }
            }
            is WireContent.Binary -> encoder.writeBuf(content.value)
            is WireContent.StringContent -> encoder.writeString(content.value)
            is WireContent.Embed -> encoder.writeJSON(content.value)
            is WireContent.Format -> {
                encoder.writeKey(content.key)
                encoder.writeJSON(content.value)
            }
            is WireContent.Type -> {
                encoder.writeTypeRef(content.kind.typeRefId())
                if (content.kind == RootKind.XmlElement || content.kind == RootKind.XmlHook) {
                    encoder.writeKey(content.nodeName)
                }
            }
            is WireContent.AnyContent -> {
                encoder.writeLen(content.values.size.toLong())
                content.values.forEach(encoder::writeAny)
            }
            is WireContent.Doc -> {
                encoder.writeString(content.guid)
                encoder.writeAny(content.options)
            }
        }
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
            buildDecodedList(decoder.readVarUInt().toDecodedCount()) {
                when (val json = decoder.readString()) {
                    "undefined" -> WireJsonUndefined
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
            buildDecodedList(decoder.readVarUInt().toDecodedCount()) { readLib0Any(decoder) },
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
            buildDecodedList(decoder.readLen().toDecodedCount()) {
                when (val json = decoder.readString()) {
                    "undefined" -> WireJsonUndefined
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
        contentAnyRefNumber -> WireContent.AnyContent(
            buildDecodedList(decoder.readLen().toDecodedCount()) { decoder.readAny() },
        )
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
    is ItemContent.ArrayValues -> parentSub == null && parentKind == content.kind
    is ItemContent.Value -> parentSub == null && parentKind == content.kind
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
    // Yjs does not encode a constructor for a named top-level shared type. Historical
    // documents can therefore contain structs written through different accessors under the
    // same root name (for example an old Y.Array followed by a Y.XmlFragment). The receiving
    // application decides which view to materialize. Nested types do have an explicit owner
    // ContentType and must continue to match that owner's kind.
    val untypedRoot = nestedKind == null &&
        !parent.startsWith("__yks_nested__:") &&
        !parent.startsWith("__yks_yjs_nested__:")
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
        is ItemContent.ArrayValues -> nestedKind?.let { kind -> kind == content.kind }
            ?: (untypedRoot || profile?.singleSequenceKind == content.kind)
        is ItemContent.Value -> nestedKind?.let { kind -> kind == content.kind }
            ?: (untypedRoot || profile?.singleSequenceKind == content.kind)
        is ItemContent.Text,
        is ItemContent.TextEmbed,
        is ItemContent.NativeTextFormat -> nestedKind?.let { kind -> kind == content.kind }
            ?: (untypedRoot || profile?.sequenceEmpty == true || profile?.textLikeKind == content.kind)
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
                    ?: (
                        untypedRoot ||
                            profile?.sequenceEmpty == true ||
                            profile?.singleSequenceKind == content.kind
                        ))
        }
        is ItemContent.Deleted -> true
        else -> false
    }
}

private fun ItemContent.isSupportedStandardContent(isV2: Boolean): Boolean = when (this) {
    is ItemContent.Value ->
        kind !in setOf(RootKind.Map, RootKind.XmlHook) && value.isSupportedAnyValue(topLevel = true)
    is ItemContent.ArrayValues ->
        kind !in setOf(RootKind.Map, RootKind.XmlHook) && values.all { value -> value.isSupportedAnyValue(topLevel = true) }
    is ItemContent.MapEntry -> value.isSupportedAnyValue(topLevel = true)
    is ItemContent.MapEntries -> values.all { value -> value.isSupportedAnyValue(topLevel = true) }
    is ItemContent.Text ->
        kind in setOf(RootKind.Array, RootKind.Text, RootKind.XmlText, RootKind.XmlFragment, RootKind.XmlElement) &&
            baseAttributes.isEmpty()
    is ItemContent.TextEmbed ->
        kind !in setOf(RootKind.Map, RootKind.XmlHook) &&
            baseAttributes.isEmpty() &&
            if (value is YValue.SubdocRef) value.isSupportedAnyValue(topLevel = true)
            else if (isV2) value.isSupportedAnyValue(topLevel = false)
            else value.isSupportedJsonValue()
    is ItemContent.TextFormat -> false
    is ItemContent.NativeTextFormat ->
        kind !in setOf(RootKind.Map, RootKind.XmlHook) &&
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
    val isSkip: Boolean = false,
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

private data class WireDocumentUpdate(
    val items: List<DecodedWireItem>,
    val deleteSet: DeleteSet,
)

private fun WireDocumentUpdate.toContentIds(): ContentIds = createContentIds(
    inserts = createIdSet().also { ids ->
        items.filterNot(DecodedWireItem::isSkip).forEach { item -> ids.add(item.id, item.content.length) }
    },
    deletes = deleteSet.toIdSet(),
)

private fun WireDocumentUpdate.isFullySelectedBy(contentIds: ContentIds): Boolean =
    toContentIds().let { available ->
        equalIdSets(available.inserts, intersectSets(available.inserts, contentIds.inserts)) &&
            equalIdSets(available.deletes, intersectSets(available.deletes, contentIds.deletes))
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

private object WireJsonUndefined

private fun WireContent.refNumber(): Int = when (this) {
    is WireContent.Deleted -> contentDeletedRefNumber
    is WireContent.Json -> contentJSONRefNumber
    is WireContent.Binary -> contentBinaryRefNumber
    is WireContent.StringContent -> contentStringRefNumber
    is WireContent.Embed -> contentEmbedRefNumber
    is WireContent.Format -> contentFormatRefNumber
    is WireContent.Type -> contentTypeRefNumber
    is WireContent.AnyContent -> contentAnyRefNumber
    is WireContent.Doc -> contentDocRefNumber
}

/** Keep the first wire representation for duplicate clocks and retain uncovered tails. */
private fun mergeWireItems(items: List<DecodedWireItem>): List<DecodedWireItem> = items
    .filterNot(DecodedWireItem::isSkip)
    .groupBy { item -> item.id.client }
    .toSortedMap(compareByDescending { client -> client })
    .values
    .flatMap { clientItems ->
        val merged = mutableListOf<DecodedWireItem>()
        clientItems.sortedBy { item -> item.id.clock }.forEach { incoming ->
            val incomingEnd = checkedClockAdd(incoming.id.clock, incoming.content.length, "incoming wire item end")
            val coveredUntil = merged.lastOrNull()?.let { item ->
                checkedClockAdd(item.id.clock, item.content.length, "merged wire item end")
            } ?: incoming.id.clock
            when {
                incomingEnd <= coveredUntil -> Unit
                incoming.id.clock < coveredUntil -> merged.add(incoming.sliceFromClock(coveredUntil))
                else -> merged.add(incoming)
            }
        }
        merged
    }

private fun DecodedWireItem.sliceFromClock(startClock: Long): DecodedWireItem {
    val endClock = checkedClockAdd(id.clock, content.length, "wire item end")
    require(startClock in (id.clock + 1)..<endClock) { "wire slice must retain a non-empty tail" }
    val offset = startClock - id.clock
    if (isGc) {
        return copy(
            id = Id(id.client, startClock),
            content = WireContent.Deleted(endClock - startClock),
        )
    }
    val slicedOrigin = Id(id.client, startClock - 1)
    return copy(
        id = Id(id.client, startClock),
        origin = slicedOrigin,
        parent = ParentReference.Inherit(slicedOrigin),
        parentSub = null,
        content = content.sliceFrom(offset),
    )
}

private fun DecodedWireItem.sliceClocks(startClock: Long, endClock: Long): DecodedWireItem {
    val itemEnd = checkedClockAdd(id.clock, content.length, "wire item end")
    require(startClock >= id.clock && endClock <= itemEnd && startClock < endClock) {
        "wire slice must be a non-empty subset of the item"
    }
    if (startClock == id.clock && endClock == itemEnd) return this
    if (isGc) {
        return copy(
            id = Id(id.client, startClock),
            content = WireContent.Deleted(endClock - startClock),
        )
    }
    val offset = startClock - id.clock
    val slicedOrigin = if (offset == 0L) origin else Id(id.client, startClock - 1)
    return copy(
        id = Id(id.client, startClock),
        origin = slicedOrigin,
        parent = if (offset == 0L) parent else ParentReference.Inherit(checkNotNull(slicedOrigin)),
        parentSub = if (offset == 0L) parentSub else null,
        content = content.sliceRange(offset, endClock - id.clock),
    )
}

private fun List<DecodedWireItem>.afterStateVector(stateVector: StateVector): List<DecodedWireItem> =
    filterNot(DecodedWireItem::isSkip).mapNotNull { item ->
        val targetClock = stateVector[item.id.client] ?: 0
        val endClock = checkedClockAdd(item.id.clock, item.content.length, "wire item end")
        when {
            endClock <= targetClock -> null
            item.id.clock >= targetClock -> item
            else -> item.sliceFromClock(targetClock)
        }
    }

private fun List<DecodedWireItem>.intersectClockRanges(idSet: IdSet): List<DecodedWireItem> = flatMap { item ->
    if (item.isSkip) return@flatMap emptyList()
    idSet.slice(item.id.client, item.id.clock, item.content.length)
        .filter { range -> range.exists && range.len > 0 }
        .map { range ->
            item.sliceClocks(range.clock, checkedClockAdd(range.clock, range.len, "wire intersection end"))
        }
}

private fun WireContent.sliceFrom(offset: Long): WireContent = when (this) {
    is WireContent.Deleted -> copy(length = length - offset)
    is WireContent.StringContent -> copy(value = value.substring(offset.toNonNegativeInt("wire text slice")))
    is WireContent.Json -> copy(values = values.drop(offset.toNonNegativeInt("wire JSON slice")))
    is WireContent.AnyContent -> copy(values = values.drop(offset.toNonNegativeInt("wire any slice")))
    else -> error("wire content ${this::class.simpleName} cannot be sliced")
}

private fun WireContent.sliceRange(from: Long, until: Long): WireContent {
    require(from >= 0 && until in (from + 1)..length) { "invalid wire content slice" }
    if (from == 0L && until == length) return this
    val startIndex = from.toNonNegativeInt("wire content slice start")
    val endIndex = until.toNonNegativeInt("wire content slice end")
    return when (this) {
        is WireContent.Deleted -> copy(length = until - from)
        is WireContent.StringContent -> copy(value = value.substring(startIndex, endIndex))
        is WireContent.Json -> copy(values = values.subList(startIndex, endIndex))
        is WireContent.AnyContent -> copy(values = values.subList(startIndex, endIndex))
        else -> error("wire content ${this::class.simpleName} cannot be sliced")
    }
}

private class WireUpdateObfuscator(
    private val options: ObfuscatorOptions,
) {
    private val parentSubCache = linkedMapOf<String, String>()
    private val nodeNameCache = linkedMapOf<String, String>()
    private val formattingKeyCache = linkedMapOf<String, String>()
    private val formattingValueCache = linkedMapOf<Any?, Any?>(null to null)
    private var nextId = 0

    fun obfuscate(item: DecodedWireItem): DecodedWireItem {
        if (item.isGc || item.isSkip) return item
        val token = nextId++
        val content = when (val value = item.content) {
            is WireContent.Deleted -> value
            is WireContent.Type -> if (options.yxml) {
                value.copy(nodeName = nodeNameCache.getOrPut(value.nodeName) {
                    (if (value.kind == RootKind.XmlHook) "hook-" else "node-") + token
                })
            } else value
            is WireContent.AnyContent -> value.copy(values = List(value.values.size) { token.toLong() })
            is WireContent.Binary -> value.copy(value = byteArrayOf(token.toByte()))
            is WireContent.Doc -> if (options.subdocs) {
                value.copy(guid = token.toString(), options = emptyMap<String, Any?>())
            } else value
            is WireContent.Embed -> value.copy(value = emptyMap<String, Any?>())
            is WireContent.Format -> if (options.formatting) {
                value.copy(
                    key = formattingKeyCache.getOrPut(value.key) { token.toString() },
                    value = if (value.value == null) null else {
                        formattingValueCache.getOrPut(value.value) { mapOf("i" to token.toLong()) }
                    },
                )
            } else value
            is WireContent.Json -> value.copy(values = List(value.values.size) { token.toLong() })
            is WireContent.StringContent -> value.copy(
                value = (token % 10).toString().repeat(value.value.length),
            )
        }
        return item.copy(
            parentSub = item.parentSub?.let { key -> parentSubCache.getOrPut(key) { token.toString() } },
            content = content,
        )
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
    if (any(DecodedWireItem::isSkip)) return filterNot(DecodedWireItem::isSkip).toStoreItems()
    val firstClient = firstOrNull()?.id?.client
    var isSingleSortedClient = firstClient != null
    var singleIndex = 0
    while (isSingleSortedClient && singleIndex < size) {
        val item = this[singleIndex]
        if (
            item.id.client != firstClient ||
            (singleIndex > 0 && this[singleIndex - 1].id.clock > item.id.clock)
        ) {
            isSingleSortedClient = false
        }
        singleIndex++
    }
    val singleClientItems = takeIf { isSingleSortedClient }
    val itemsByClient = linkedMapOf<Long, MutableList<DecodedWireItem>>()
    if (singleClientItems == null) {
        for (item in this) itemsByClient.getOrPut(item.id.client) { mutableListOf() }.add(item)
    }
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
    fun denseUnitClockStart(items: List<DecodedWireItem>): Long? {
        val firstClock = items.firstOrNull()?.id?.clock ?: return null
        var index = 0
        while (index < items.size) {
            val item = items[index]
            if (item.content.length != 1L || item.id.clock != checkedClockAdd(firstClock, index.toLong())) {
                return null
            }
            index++
        }
        return firstClock
    }
    val singleDenseUnitClockStart = singleClientItems?.let(::denseUnitClockStart)
    val denseUnitClockStarts = itemsByClient.mapValues { (_, items) ->
        denseUnitClockStart(items)
    }
    if (singleClientItems != null && singleDenseUnitClockStart != null) {
        singleClientItems.toDenseStoreItems(singleDenseUnitClockStart)?.let { return it }
    }

    var cachedClient = -1L
    var cachedClientItems: List<DecodedWireItem> = emptyList()
    var cachedDenseUnitClockStart: Long? = null
    fun containing(id: Id): DecodedWireItem? {
        if (singleClientItems != null) {
            if (id.client != firstClient) return null
            singleDenseUnitClockStart?.let { firstClock ->
                val index = id.clock - firstClock
                if (index >= 0 && index < singleClientItems.size.toLong()) {
                    return singleClientItems[index.toInt()]
                }
                return null
            }
            var low = 0
            var high = singleClientItems.lastIndex
            var candidate: DecodedWireItem? = null
            while (low <= high) {
                val middle = (low + high) ushr 1
                val item = singleClientItems[middle]
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
            candidate.resolvedKind = if (
                parentSub != null &&
                candidate.content !is WireContent.StringContent &&
                candidate.content !is WireContent.Embed &&
                candidate.content !is WireContent.Format
            ) {
                ownerKind?.takeIf { it == RootKind.XmlHook } ?: RootKind.Map
            } else {
                ownerKind?.takeIf {
                    it == RootKind.XmlText || it == RootKind.XmlFragment || it == RootKind.XmlElement
                }
                    ?: candidate.content.definitiveSequenceKindOrNull()
                    ?: if (parentSub != null) {
                    ownerKind?.takeIf { it == RootKind.XmlHook } ?: RootKind.Map
                } else {
                    ownerKind?.takeIf { it == RootKind.XmlText }
                    ?: ownerKind
                    ?: candidate.content.inferRootKind(parentSub)
                }
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

/**
 * Resolves the common single-client, unit-clock, backward-anchor update in one forward pass.
 *
 * Future anchors, GC structs, and packed content deliberately fall back to the general resolver.
 */
private fun List<DecodedWireItem>.toDenseStoreItems(firstClock: Long): List<StoreItem>? {
    val client = firstOrNull()?.id?.client ?: return emptyList()
    val parents = arrayOfNulls<String>(size)
    val parentSubs = arrayOfNulls<String>(size)
    val kinds = arrayOfNulls<RootKind>(size)
    val result = ArrayList<StoreItem>(size)

    fun anchorIndex(id: Id): Int? {
        if (id.client != client) return null
        val index = id.clock - firstClock
        return index.toInt().takeIf { index >= 0 && index < size && index.toLong() == id.clock - firstClock }
    }

    for (index in indices) {
        val item = this[index]
        if (item.isGc || item.content is WireContent.Deleted || item.content.length != 1L) return null
        val inheritedIndex = when (val reference = item.parent) {
            is ParentReference.Inherit -> anchorIndex(reference.id)?.takeIf { anchor -> anchor < index }
                ?: return null
            else -> null
        }
        val parent = when (val reference = item.parent) {
            is ParentReference.Root -> reference.name
            is ParentReference.Nested -> {
                val ownerIndex = anchorIndex(reference.id)?.takeIf { owner -> owner < index } ?: return null
                (this[ownerIndex].content as? WireContent.Type)?.name ?: return null
            }
            is ParentReference.Inherit -> parents[checkNotNull(inheritedIndex)] ?: return null
        }
        val parentSub = when (item.parent) {
            is ParentReference.Root,
            is ParentReference.Nested -> item.parentSub
            is ParentReference.Inherit -> parentSubs[checkNotNull(inheritedIndex)]
        }
        val ownerKind = when (val reference = item.parent) {
            is ParentReference.Nested -> {
                val ownerIndex = anchorIndex(reference.id)?.takeIf { owner -> owner < index } ?: return null
                (this[ownerIndex].content as? WireContent.Type)?.kind
            }
            is ParentReference.Inherit -> kinds[checkNotNull(inheritedIndex)]
            is ParentReference.Root -> null
        }
        val kind = if (
            parentSub != null &&
            item.content !is WireContent.StringContent &&
            item.content !is WireContent.Embed &&
            item.content !is WireContent.Format
        ) {
            ownerKind?.takeIf { it == RootKind.XmlHook } ?: RootKind.Map
        } else {
            ownerKind?.takeIf {
                it == RootKind.XmlText || it == RootKind.XmlFragment || it == RootKind.XmlElement
            }
                ?: item.content.definitiveSequenceKindOrNull()
                ?: if (parentSub != null) {
                ownerKind?.takeIf { it == RootKind.XmlHook } ?: RootKind.Map
            } else {
                ownerKind?.takeIf { it == RootKind.XmlText }
                ?: ownerKind
                ?: item.content.inferRootKind(parentSub)
            }
        }
        val content = item.content.toSingleItemContentOrNull(kind) ?: return null
        parents[index] = parent
        parentSubs[index] = parentSub
        kinds[index] = kind
        result.add(
            StoreItem(
                id = item.id,
                origin = item.origin,
                rightOrigin = item.rightOrigin,
                parent = parent,
                parentSub = parentSub,
                content = content,
                deleted = false,
                requiresClockContinuity = true,
                isGc = false,
                unresolvedParent = null,
            ),
        )
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
        val packedValues = values.map { value -> YValue.from(value.toSemanticJsonValue()) }
        when (kind) {
            RootKind.Array,
            RootKind.Text,
            RootKind.XmlText,
            RootKind.XmlFragment,
            RootKind.XmlElement -> ItemContent.ArrayValues(packedValues, kind)
            RootKind.Map -> ItemContent.MapEntries(packedValues)
            RootKind.XmlHook -> ItemContent.MapEntries(packedValues)
        }
    } else {
        null
    }
    is WireContent.AnyContent -> if (values.size > 1) {
        val packedValues = values.map(YValue::from)
        when (kind) {
            RootKind.Array,
            RootKind.Text,
            RootKind.XmlText,
            RootKind.XmlFragment,
            RootKind.XmlElement -> ItemContent.ArrayValues(packedValues, kind)
            RootKind.Map -> ItemContent.MapEntries(packedValues)
            RootKind.XmlHook -> ItemContent.MapEntries(packedValues)
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
    is WireContent.Json -> values.map { value -> value.toSemanticJsonValue().toSequenceContent(kind) }
    is WireContent.AnyContent -> values.map { value -> value.toSequenceContent(kind) }
    else -> error("wire content ${this::class.simpleName} has a single item representation")
}

private fun Any?.toSemanticJsonValue(): Any? = if (this === WireJsonUndefined) null else this

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
        RootKind.XmlElement -> ItemContent.Value(value, kind)
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

private fun skipParentName(client: Long): String = "__yjs_skip__:$client"

internal fun ByteArray.hasLegacyMagic(): Boolean =
    size >= 4 && this[0] == 'Y'.code.toByte() && this[1] == 'K'.code.toByte() &&
        this[2] == 'S'.code.toByte() && this[3] in setOf(1.toByte(), 2.toByte(), 3.toByte(), 4.toByte(), 5.toByte())
