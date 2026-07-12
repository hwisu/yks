package dev.yks

data class YDocOptions(
    val clientId: Long = YDoc.generateNewClientId(),
    val guid: String = java.util.UUID.randomUUID().toString(),
    val collectionId: String? = null,
    val gc: Boolean = true,
    val gcFilter: (AbstractStruct) -> Boolean = { true },
    val meta: Any? = null,
    val shouldLoad: Boolean = true,
    val autoLoad: Boolean = false,
    val isSuggestionDoc: Boolean = false,
) {
    val collectionid: String? get() = collectionId

    fun toDoc(): YDoc = YDoc(
        clientId = clientId,
        guid = guid,
        collectionId = collectionId,
        gc = gc,
        gcFilter = gcFilter,
        meta = meta,
        shouldLoad = shouldLoad,
        autoLoad = autoLoad,
        isSuggestionDoc = isSuggestionDoc,
    )
}

fun applyUpdate(doc: YDoc, update: ByteArray, origin: Any? = null) {
    doc.applyUpdate(update, origin)
}

fun applyUpdateV2(doc: YDoc, update: ByteArray, origin: Any? = null) {
    doc.applyUpdate(UpdateCodec.decodeV2(update), origin)
}

fun readUpdate(doc: YDoc, update: ByteArray, origin: Any? = null) {
    applyUpdate(doc, update, origin)
}

fun readUpdate(decoder: BinaryDecoder, doc: YDoc, origin: Any? = null) {
    doc.applyUpdate(UpdateCodec.decode(decoder), origin)
}

fun readUpdate(decoder: UpdateDecoderV1, doc: YDoc, origin: Any? = null) {
    readUpdate(decoder.restDecoder, doc, origin)
}

fun readUpdateV2(doc: YDoc, update: ByteArray, origin: Any? = null) {
    applyUpdateV2(doc, update, origin)
}

fun readUpdateV2(decoder: BinaryDecoder, doc: YDoc, origin: Any? = null) {
    doc.applyUpdate(UpdateCodec.decodeV2(decoder.readRemainingBytes()), origin)
}

fun readUpdateV2(decoder: UpdateDecoderV2, doc: YDoc, origin: Any? = null) {
    doc.applyUpdate(UpdateCodec.decodeV2(decoder), origin)
}

fun encodeStateAsUpdate(doc: YDoc, encodedStateVector: ByteArray = ByteArray(0)): ByteArray =
    doc.encodeStateAsUpdate(encodedStateVector)

fun encodeStateAsUpdateLossless(doc: YDoc, encodedStateVector: ByteArray = ByteArray(0)): ByteArray =
    doc.encodeStateAsUpdateLossless(encodedStateVector)

fun encodeStateAsUpdateV2(doc: YDoc, encodedStateVector: ByteArray = ByteArray(0)): ByteArray =
    doc.encodeStateAsUpdateV2(encodedStateVector)

fun encodeStateAsUpdateV2Lossless(doc: YDoc, encodedStateVector: ByteArray = ByteArray(0)): ByteArray =
    doc.encodeStateAsUpdateV2Lossless(encodedStateVector)

fun encodeStateVector(doc: YDoc): ByteArray = doc.encodeStateVector()

fun writeStateAsUpdate(
    encoder: BinaryEncoder,
    doc: YDoc,
    targetStateVector: StateVector = emptyMap(),
): BinaryEncoder =
    UpdateCodec.write(
        encoder,
        DocumentUpdate(
            doc.store.itemsSince(targetStateVector),
            doc.store.deleteSet(),
            doc.store.parentItemIds(),
            doc.store.parentKinds(),
        ),
    )

fun writeStateAsUpdateLossless(
    encoder: BinaryEncoder,
    doc: YDoc,
    targetStateVector: StateVector = emptyMap(),
): BinaryEncoder =
    UpdateCodec.writeLossless(
        encoder,
        DocumentUpdate(
            doc.store.itemsSince(targetStateVector),
            doc.store.deleteSet(),
            doc.store.parentItemIds(),
            doc.store.parentKinds(),
        ),
    )

fun writeStateAsUpdate(
    encoder: IdSetEncoderV1,
    doc: YDoc,
    targetStateVector: StateVector = emptyMap(),
): IdSetEncoderV1 {
    writeStateAsUpdate(encoder.restEncoder, doc, targetStateVector)
    return encoder
}

fun writeStateAsUpdateLossless(
    encoder: IdSetEncoderV1,
    doc: YDoc,
    targetStateVector: StateVector = emptyMap(),
): IdSetEncoderV1 {
    writeStateAsUpdateLossless(encoder.restEncoder, doc, targetStateVector)
    return encoder
}

fun writeStateAsUpdateV2(
    encoder: BinaryEncoder,
    doc: YDoc,
    targetStateVector: StateVector = emptyMap(),
): BinaryEncoder = encoder.also {
    it.writeRawBytes(doc.encodeStateAsUpdateV2(encodeStateVector(targetStateVector)))
}

fun writeStateAsUpdateV2Lossless(
    encoder: BinaryEncoder,
    doc: YDoc,
    targetStateVector: StateVector = emptyMap(),
): BinaryEncoder = encoder.also {
    it.writeRawBytes(doc.encodeStateAsUpdateV2Lossless(encodeStateVector(targetStateVector)))
}

fun writeStateAsUpdateV2(
    encoder: UpdateEncoderV2,
    doc: YDoc,
    targetStateVector: StateVector = emptyMap(),
): UpdateEncoderV2 = encoder.also {
    it.setEncodedUpdate(doc.encodeStateAsUpdateV2(encodeStateVector(targetStateVector)))
}

fun writeStateAsUpdateV2Lossless(
    encoder: UpdateEncoderV2,
    doc: YDoc,
    targetStateVector: StateVector = emptyMap(),
): UpdateEncoderV2 = encoder.also {
    it.setEncodedUpdate(doc.encodeStateAsUpdateV2Lossless(encodeStateVector(targetStateVector)))
}

fun writeStateAsUpdateV2(
    encoder: IdSetEncoderV1,
    doc: YDoc,
    targetStateVector: StateVector = emptyMap(),
): IdSetEncoderV1 =
    writeStateAsUpdate(encoder, doc, targetStateVector)

fun writeStateAsUpdateV2Lossless(
    encoder: IdSetEncoderV1,
    doc: YDoc,
    targetStateVector: StateVector = emptyMap(),
): IdSetEncoderV1 =
    writeStateAsUpdateLossless(encoder, doc, targetStateVector)

fun writeClientsStructs(
    encoder: BinaryEncoder,
    store: StructStore,
    stateVector: StateVector = emptyMap(),
): BinaryEncoder =
    UpdateCodec.write(
        encoder,
        DocumentUpdate(store.itemsSince(stateVector), DeleteSet.empty(), store.parentItemIds(), store.parentKinds()),
    )

fun writeClientsStructsLossless(
    encoder: BinaryEncoder,
    store: StructStore,
    stateVector: StateVector = emptyMap(),
): BinaryEncoder =
    UpdateCodec.writeLossless(
        encoder,
        DocumentUpdate(store.itemsSince(stateVector), DeleteSet.empty(), store.parentItemIds(), store.parentKinds()),
    )

fun writeClientsStructs(
    encoder: IdSetEncoderV1,
    store: StructStore,
    stateVector: StateVector = emptyMap(),
): IdSetEncoderV1 {
    writeClientsStructs(encoder.restEncoder, store, stateVector)
    return encoder
}

fun writeClientsStructsLossless(
    encoder: IdSetEncoderV1,
    store: StructStore,
    stateVector: StateVector = emptyMap(),
): IdSetEncoderV1 {
    writeClientsStructsLossless(encoder.restEncoder, store, stateVector)
    return encoder
}

fun writeClientsStructs(
    encoder: UpdateEncoderV2,
    store: StructStore,
    stateVector: StateVector = emptyMap(),
): UpdateEncoderV2 = encoder.also {
    it.setEncodedUpdate(
        UpdateCodec.encodeV2(
            DocumentUpdate(
                store.itemsSince(stateVector),
                DeleteSet.empty(),
                store.parentItemIds(),
                store.parentKinds(),
            ),
        ),
    )
}

fun writeClientsStructsLossless(
    encoder: UpdateEncoderV2,
    store: StructStore,
    stateVector: StateVector = emptyMap(),
): UpdateEncoderV2 = encoder.also {
    it.setEncodedUpdate(
        UpdateCodec.encodeV2Lossless(
            DocumentUpdate(
                store.itemsSince(stateVector),
                DeleteSet.empty(),
                store.parentItemIds(),
                store.parentKinds(),
            ),
        ),
    )
}

fun createDocFromUpdate(update: ByteArray, doc: YDoc = YDoc()): YDoc {
    doc.applyUpdate(update)
    return doc
}

fun createDocFromUpdate(update: ByteArray, options: YDocOptions): YDoc =
    createDocFromUpdate(update, options.toDoc())

fun createDocFromUpdateV2(update: ByteArray, doc: YDoc = YDoc()): YDoc = doc.also { applyUpdateV2(it, update) }

fun createDocFromUpdateV2(update: ByteArray, options: YDocOptions): YDoc =
    createDocFromUpdateV2(update, options.toDoc())

internal fun YDoc.concreteRootMetadata(): Map<String, SnapshotRootType> =
    concreteRootTypes().mapValues { (_, type) ->
        SnapshotRootType(
            kind = type.kind,
            xmlElementNodeName = (type as? YXmlElementType)?.nodeName,
        )
    }

internal fun YDoc.preMaterializeRoots(roots: Map<String, SnapshotRootType>): YDoc {
    roots.forEach { (name, root) ->
        when (root.kind) {
            RootKind.Array -> getArray(name)
            RootKind.Map -> getMap(name)
            RootKind.Text -> getText(name)
            RootKind.XmlFragment -> getXmlFragment(name)
            RootKind.XmlElement -> getXmlElement(name, root.xmlElementNodeName ?: name)
            RootKind.XmlHook,
            RootKind.XmlText -> error("XML node type refs cannot be document roots")
        }
    }
    return this
}

internal fun YDoc.preMaterializeRootsFrom(source: YDoc): YDoc {
    if (this === source) return this
    return preMaterializeRoots(source.concreteRootMetadata())
}

private fun cloneDocInto(source: YDoc, target: YDoc): YDoc {
    target.preMaterializeRootsFrom(source)
    return createDocFromUpdate(encodeStateAsUpdateLossless(source), target)
}

fun cloneDoc(doc: YDoc): YDoc = cloneDocInto(doc, YDoc())

fun cloneDoc(doc: YDoc, options: YDocOptions): YDoc =
    cloneDocInto(doc, options.toDoc())

fun writeStructs(doc: YDoc, client: Long, idRanges: List<IdRange>): ByteArray {
    val idSet = createIdSet()
    idRanges.forEach { range -> idSet.add(client, range.clock, range.len) }
    return writeStructsFromIdSet(doc, idSet)
}

fun writeStructsV2(doc: YDoc, client: Long, idRanges: List<IdRange>): ByteArray =
    createIdSet().also { ids -> idRanges.forEach { ids.add(client, it.clock, it.len) } }
        .let { writeStructsFromIdSetV2(doc, it) }

fun writeStructsLossless(doc: YDoc, client: Long, idRanges: List<IdRange>): ByteArray =
    createIdSet().also { ids -> idRanges.forEach { ids.add(client, it.clock, it.len) } }
        .let { writeStructsFromIdSetLossless(doc, it) }

fun writeStructsV2Lossless(doc: YDoc, client: Long, idRanges: List<IdRange>): ByteArray =
    createIdSet().also { ids -> idRanges.forEach { ids.add(client, it.clock, it.len) } }
        .let { writeStructsFromIdSetV2Lossless(doc, it) }

fun writeStructsFromIdSet(doc: YDoc, idSet: IdSet): ByteArray =
    encodeStructsFromIdSet(doc, idSet)

fun writeStructsFromIdSetV2(doc: YDoc, idSet: IdSet): ByteArray =
    encodeStructsFromIdSetV2(doc, idSet)

fun writeStructsFromIdSetLossless(doc: YDoc, idSet: IdSet): ByteArray =
    encodeStructsFromIdSetLossless(doc, idSet)

fun writeStructsFromIdSetV2Lossless(doc: YDoc, idSet: IdSet): ByteArray =
    encodeStructsFromIdSetV2Lossless(doc, idSet)

fun encodeStructsFromIdSet(doc: YDoc, idSet: IdSet): ByteArray =
    UpdateCodec.encode(
        DocumentUpdate(
            doc.itemsForIdSet(idSet),
            DeleteSet.empty(),
            doc.store.parentItemIds(),
            doc.store.parentKinds(),
        ),
    )

fun encodeStructsFromIdSetLossless(doc: YDoc, idSet: IdSet): ByteArray =
    UpdateCodec.encodeLossless(
        DocumentUpdate(
            doc.itemsForIdSet(idSet),
            DeleteSet.empty(),
            doc.store.parentItemIds(),
            doc.store.parentKinds(),
        ),
    )

fun encodeStructsFromIdSetV2(doc: YDoc, idSet: IdSet): ByteArray =
    UpdateCodec.encodeV2(
        DocumentUpdate(
            doc.itemsForIdSet(idSet),
            DeleteSet.empty(),
            doc.store.parentItemIds(),
            doc.store.parentKinds(),
        ),
    )

fun encodeStructsFromIdSetV2Lossless(doc: YDoc, idSet: IdSet): ByteArray =
    UpdateCodec.encodeV2Lossless(
        DocumentUpdate(
            doc.itemsForIdSet(idSet),
            DeleteSet.empty(),
            doc.store.parentItemIds(),
            doc.store.parentKinds(),
        ),
    )

fun writeStructsFromTransaction(transaction: YTransactionEvent): ByteArray =
    encodeStructsFromTransaction(transaction)

fun writeStructsFromTransactionV2(transaction: YTransactionEvent): ByteArray =
    encodeStructsFromTransactionV2(transaction)

fun writeStructsFromTransactionLossless(transaction: YTransactionEvent): ByteArray =
    encodeStructsFromTransactionLossless(transaction)

fun writeStructsFromTransactionV2Lossless(transaction: YTransactionEvent): ByteArray =
    encodeStructsFromTransactionV2Lossless(transaction)

fun encodeStructsFromTransaction(transaction: YTransactionEvent): ByteArray =
    UpdateCodec.encode(
        DocumentUpdate(
            transaction.addedItems.map { it.copy() },
            DeleteSet.empty(),
            transaction.doc.store.parentItemIds(),
            transaction.doc.store.parentKinds(),
        ),
    )

fun encodeStructsFromTransactionV2(transaction: YTransactionEvent): ByteArray =
    UpdateCodec.encodeV2(
        DocumentUpdate(
            transaction.addedItems.map { it.copy() },
            DeleteSet.empty(),
            transaction.doc.store.parentItemIds(),
            transaction.doc.store.parentKinds(),
        ),
    )

fun encodeStructsFromTransactionLossless(transaction: YTransactionEvent): ByteArray =
    UpdateCodec.encodeLossless(
        DocumentUpdate(
            transaction.addedItems.map { it.copy() },
            DeleteSet.empty(),
            transaction.doc.store.parentItemIds(),
            transaction.doc.store.parentKinds(),
        ),
    )

fun encodeStructsFromTransactionV2Lossless(transaction: YTransactionEvent): ByteArray =
    UpdateCodec.encodeV2Lossless(
        DocumentUpdate(
            transaction.addedItems.map { it.copy() },
            DeleteSet.empty(),
            transaction.doc.store.parentItemIds(),
            transaction.doc.store.parentKinds(),
        ),
    )

fun writeUpdateMessageFromTransaction(transaction: YTransactionEvent): ByteArray? =
    encodeUpdateMessageFromTransaction(transaction)

fun writeUpdateMessageFromTransactionV2(transaction: YTransactionEvent): ByteArray? =
    encodeUpdateMessageFromTransactionV2(transaction)

fun writeUpdateMessageFromTransactionLossless(transaction: YTransactionEvent): ByteArray? =
    encodeUpdateMessageFromTransactionLossless(transaction)

fun writeUpdateMessageFromTransactionV2Lossless(transaction: YTransactionEvent): ByteArray? =
    encodeUpdateMessageFromTransactionV2Lossless(transaction)

fun encodeUpdateMessageFromTransaction(transaction: YTransactionEvent): ByteArray? {
    if (transaction.insertSet.isEmpty() && transaction.deleteSet.isEmpty) return null
    return UpdateCodec.encode(
        DocumentUpdate(
            transaction.addedItems.map { it.copy() },
            transaction.deleteSet.copy(),
            transaction.doc.store.parentItemIds(),
            transaction.doc.store.parentKinds(),
        ),
    )
}

fun encodeUpdateMessageFromTransactionLossless(transaction: YTransactionEvent): ByteArray? =
    if (transaction.insertSet.isEmpty() && transaction.deleteSet.isEmpty) null else transaction.update.copyOf()

fun encodeUpdateMessageFromTransactionV2(transaction: YTransactionEvent): ByteArray? =
    if (transaction.insertSet.isEmpty() && transaction.deleteSet.isEmpty) null else UpdateCodec.encodeV2(
        DocumentUpdate(
            transaction.addedItems.map { it.copy() },
            transaction.deleteSet.copy(),
            transaction.doc.store.parentItemIds(),
            transaction.doc.store.parentKinds(),
        ),
    )

fun encodeUpdateMessageFromTransactionV2Lossless(transaction: YTransactionEvent): ByteArray? =
    if (transaction.insertSet.isEmpty() && transaction.deleteSet.isEmpty) null else UpdateCodec.encodeV2Lossless(
        DocumentUpdate(
            transaction.addedItems.map { it.copy() },
            transaction.deleteSet.copy(),
            transaction.doc.store.parentItemIds(),
            transaction.doc.store.parentKinds(),
        ),
    )

data class DecodedUpdateStruct(
    val id: Id,
    val origin: Id?,
    val rightOrigin: Id?,
    val parent: String,
    val parentSub: String?,
    val kind: RootKind,
    val deleted: Boolean,
    val length: Long,
    val content: AbstractContent,
)

data class DecodedUpdate(
    val structs: List<DecodedUpdateStruct>,
    val deleteSet: DeleteSet,
) {
    val ds: DeleteSet get() = deleteSet
}

class LazyStructReader private constructor(
    update: DocumentUpdate,
    val filterSkips: Boolean = false,
) {
    private val structs: List<AbstractStruct> = update.itemsWithDeleteState().map { it.toItemStruct(YDoc()) }
    private var index: Int = 0
    private val decodedDeleteSet: DeleteSet = update.deleteSet.copy()

    var curr: AbstractStruct? = null
        private set

    var done: Boolean = false
        private set

    val deleteSet: DeleteSet get() = decodedDeleteSet.copy()

    val ds: DeleteSet get() = deleteSet

    constructor(update: ByteArray, filterSkips: Boolean = false) :
        this(UpdateCodec.decode(update), filterSkips)

    constructor(decoder: BinaryDecoder, filterSkips: Boolean = false) :
        this(UpdateCodec.decode(decoder), filterSkips)

    constructor(decoder: UpdateDecoderV1, filterSkips: Boolean = false) :
        this(decoder.restDecoder, filterSkips)

    constructor(decoder: UpdateDecoderV2, filterSkips: Boolean = false) :
        this(UpdateCodec.decodeV2(decoder), filterSkips)

    init {
        next()
    }

    fun next(): AbstractStruct? {
        do {
            curr = if (index < structs.size) structs[index++] else null
        } while (filterSkips && curr is Skip)
        done = curr == null
        return curr
    }
}

class LazyStructWriter(
    val encoder: BinaryEncoder = BinaryEncoder(),
) {
    constructor(encoder: IdSetEncoderV1) : this(encoder.restEncoder)

    var currClient: Long = 0
        private set
    var startClock: Long = 0
        private set
    var written: Int = 0
        private set
    val clientStructs: MutableList<DecodedUpdateStruct> = mutableListOf()
    private val items = mutableListOf<StoreItem>()
    private var finished: Boolean = false

    internal fun write(struct: DecodedUpdateStruct, offset: Long, offsetEnd: Long) {
        check(!finished) { "cannot write structs after finishLazyStructWriting" }
        val sliced = struct.slice(offset, offsetEnd)
        if (written == 0 || currClient != sliced.id.client) {
            currClient = sliced.id.client
            startClock = sliced.id.clock
        }
        clientStructs.add(sliced)
        items.addAll(sliced.toStoreItems())
        written++
    }

    internal fun finish(): BinaryEncoder {
        if (!finished) {
            UpdateCodec.write(encoder, DocumentUpdate(items.toList(), DeleteSet.empty()))
            finished = true
        }
        return encoder
    }

    fun toByteArray(): ByteArray {
        finish()
        return encoder.toByteArray()
    }

    fun toUint8Array(): ByteArray = toByteArray()
}

fun writeStructToLazyStructWriter(
    lazyWriter: LazyStructWriter,
    struct: DecodedUpdateStruct,
    offset: Long = 0,
    offsetEnd: Long = 0,
): LazyStructWriter {
    lazyWriter.write(struct, offset, offsetEnd)
    return lazyWriter
}

fun writeStructToLazyStructWriter(
    lazyWriter: LazyStructWriter,
    struct: AbstractStruct,
    offset: Long = 0,
    offsetEnd: Long = 0,
): LazyStructWriter =
    writeStructToLazyStructWriter(lazyWriter, struct.toDecodedUpdateStruct(), offset, offsetEnd)

fun finishLazyStructWriting(lazyWriter: LazyStructWriter): BinaryEncoder = lazyWriter.finish()

fun decodeUpdate(update: ByteArray): DecodedUpdate {
    val decoded = UpdateCodec.decode(update)
    return DecodedUpdate(
        structs = decoded.itemsWithDeleteState().map { it.toDecodedStruct() },
        deleteSet = decoded.deleteSet.copy(),
    )
}

fun decodeUpdateV2(update: ByteArray): DecodedUpdate {
    val decoded = UpdateCodec.decodeV2(update)
    return DecodedUpdate(
        structs = decoded.itemsWithDeleteState().map { it.toDecodedStruct() },
        deleteSet = decoded.deleteSet.copy(),
    )
}

fun logUpdate(update: ByteArray): String = formatDecodedUpdate(decodeUpdate(update))

fun logUpdateV2(update: ByteArray): String = formatDecodedUpdate(decodeUpdateV2(update))

private fun formatDecodedUpdate(decoded: DecodedUpdate): String {
    val structs = decoded.structs.joinToString(
        prefix = "Structs[",
        postfix = "]",
    ) { struct ->
        "${struct.id.client}:${struct.id.clock}:${struct.kind}" +
            "(parent=${struct.parent}, parentSub=${struct.parentSub}, deleted=${struct.deleted})"
    }
    val deletes = decoded.deleteSet.clients.toSortedMap().entries.joinToString(
        prefix = "DeleteSet[",
        postfix = "]",
    ) { (client, ranges) ->
        "$client=${ranges.joinToString(prefix = "[", postfix = "]") { "${it.clock}..${it.end}" }}"
    }
    return "$structs\n$deletes"
}

fun convertUpdateFormat(
    update: ByteArray,
    blockTransformer: (DecodedUpdateStruct) -> DecodedUpdateStruct = { it },
): ByteArray {
    requireStandardYjsUpdateInput(update, "V1")
    val decoded = UpdateCodec.decode(update)
    val transformed = decoded.items.flatMap { item ->
        blockTransformer(item.toDecodedStruct()).toStoreItems(original = item)
    }
    return UpdateCodec.encode(DocumentUpdate(transformed, decoded.deleteSet.copy()))
}

fun convertUpdateFormatLossless(
    update: ByteArray,
    blockTransformer: (DecodedUpdateStruct) -> DecodedUpdateStruct = { it },
): ByteArray {
    val decoded = UpdateCodec.decode(update)
    val transformed = decoded.items.flatMap { item ->
        blockTransformer(item.toDecodedStruct()).toStoreItems(original = item)
    }
    return UpdateCodec.encodeLossless(DocumentUpdate(transformed, decoded.deleteSet.copy()))
}

fun convertUpdateFormatV1ToV2(update: ByteArray): ByteArray {
    requireStandardYjsUpdateInput(update, "V1")
    return UpdateCodec.encodeV2(UpdateCodec.decode(update))
}

fun convertUpdateFormatV1ToV2Lossless(update: ByteArray): ByteArray =
    UpdateCodec.encodeV2Lossless(UpdateCodec.decode(update))

fun convertUpdateFormatV2ToV1(update: ByteArray): ByteArray {
    requireStandardYjsUpdateInput(update, "V2")
    return UpdateCodec.encode(UpdateCodec.decodeV2(update))
}

fun convertUpdateFormatV2ToV1Lossless(update: ByteArray): ByteArray =
    UpdateCodec.encodeLossless(UpdateCodec.decodeV2(update))

data class ObfuscatorOptions(
    val formatting: Boolean = true,
    val subdocs: Boolean = true,
    val name: Boolean = true,
)

fun obfuscateUpdate(update: ByteArray, options: ObfuscatorOptions = ObfuscatorOptions()): ByteArray {
    requireStandardYjsUpdateInput(update, "V1")
    val decoded = UpdateCodec.decode(update)
    val obfuscator = UpdateObfuscator(options)
    val obfuscatedItems = decoded.items.map { item ->
        item.copy(content = obfuscator.obfuscate(item.content))
    }
    return UpdateCodec.encode(DocumentUpdate(obfuscatedItems, decoded.deleteSet.copy()))
}

fun obfuscateUpdateLossless(update: ByteArray, options: ObfuscatorOptions = ObfuscatorOptions()): ByteArray {
    val decoded = UpdateCodec.decode(update)
    val obfuscator = UpdateObfuscator(options)
    val obfuscatedItems = decoded.items.map { item ->
        item.copy(content = obfuscator.obfuscate(item.content))
    }
    return UpdateCodec.encodeLossless(DocumentUpdate(obfuscatedItems, decoded.deleteSet.copy()))
}

fun obfuscateUpdateV2(update: ByteArray, options: ObfuscatorOptions = ObfuscatorOptions()): ByteArray {
    requireStandardYjsUpdateInput(update, "V2")
    return UpdateCodec.decodeV2(update).let { decoded ->
        val obfuscator = UpdateObfuscator(options)
        UpdateCodec.encodeV2(
            DocumentUpdate(
                decoded.items.map { item -> item.copy(content = obfuscator.obfuscate(item.content)) },
                decoded.deleteSet.copy(),
            ),
        )
    }
}


fun obfuscateUpdateV2Lossless(update: ByteArray, options: ObfuscatorOptions = ObfuscatorOptions()): ByteArray =
    UpdateCodec.decodeV2(update).let { decoded ->
        val obfuscator = UpdateObfuscator(options)
        UpdateCodec.encodeV2Lossless(
            DocumentUpdate(
                decoded.items.map { item -> item.copy(content = obfuscator.obfuscate(item.content)) },
                decoded.deleteSet.copy(),
            ),
        )
    }

data class ContentIds(
    val inserts: IdSet,
    val deletes: IdSet,
)

data class ContentMap(
    val inserts: IdMap,
    val deletes: IdMap,
)

fun createContentIds(
    inserts: IdSet = createIdSet(),
    deletes: IdSet = createIdSet(),
): ContentIds = ContentIds(inserts, deletes)

fun createInsertSetFromDoc(doc: YDoc, filterDeleted: Boolean = false): IdSet =
    createInsertIdSet(UpdateCodec.decode(doc.encodeStateAsUpdateLossless()).itemsWithDeleteState(), filterDeleted)

fun createDeleteSetFromDoc(doc: YDoc): IdSet = doc.deleteSet().toIdSet()

fun createInsertSetFromStructStore(doc: YDoc, filterDeleted: Boolean = false): IdSet =
    createInsertSetFromDoc(doc, filterDeleted)

fun createInsertSetFromStructStore(store: StructStore, filterDeleted: Boolean = false): IdSet =
    createInsertIdSet(store.allItems(), filterDeleted)

fun createContentIdsFromDoc(doc: YDoc): ContentIds = createContentIdsFromUpdate(doc.encodeStateAsUpdateLossless())

fun createContentIdsFromDocDiff(left: YDoc, right: YDoc): ContentIds =
    excludeContentIds(createContentIdsFromDoc(left), createContentIdsFromDoc(right))

fun excludeContentIds(contentIds: ContentIds, excludedContentIds: ContentIds): ContentIds =
    createContentIds(
        inserts = diffIdSet(contentIds.inserts, excludedContentIds.inserts),
        deletes = diffIdSet(contentIds.deletes, excludedContentIds.deletes),
    )

fun mergeContentIds(contentIds: List<ContentIds>): ContentIds =
    createContentIds(
        inserts = mergeIdSets(contentIds.map { it.inserts }),
        deletes = mergeIdSets(contentIds.map { it.deletes }),
    )

fun intersectContentIds(left: ContentIds, right: ContentIds): ContentIds =
    createContentIds(
        inserts = intersectSets(left.inserts, right.inserts),
        deletes = intersectSets(left.deletes, right.deletes),
    )

fun intersectContentIds(left: ContentIds, right: ContentMap): ContentIds =
    createContentIds(
        inserts = intersectSets(left.inserts, createIdSetFromIdMap(right.inserts)),
        deletes = intersectSets(left.deletes, createIdSetFromIdMap(right.deletes)),
    )

fun writeContentIds(encoder: BinaryEncoder, contentIds: ContentIds) {
    writeContentIdSet(encoder, contentIds.inserts)
    writeContentIdSet(encoder, contentIds.deletes)
}

fun writeContentIds(encoder: IdSetEncoderV1, contentIds: ContentIds) {
    writeIdSet(encoder, contentIds.inserts)
    writeIdSet(encoder, contentIds.deletes)
}

fun encodeContentIds(contentIds: ContentIds): ByteArray {
    val encoder = IdSetEncoderV2()
    writeContentIds(encoder, contentIds)
    return encoder.toUint8Array()
}

fun readContentIds(decoder: BinaryDecoder): ContentIds =
    createContentIds(
        inserts = readContentIdSet(decoder),
        deletes = readContentIdSet(decoder),
    )

fun readContentIds(decoder: IdSetDecoderV1): ContentIds =
    createContentIds(
        inserts = readIdSet(decoder),
        deletes = readIdSet(decoder),
    )

fun decodeContentIds(bytes: ByteArray): ContentIds {
    val decoder = IdSetDecoderV2(bytes)
    val contentIds = readContentIds(decoder)
    check(!decoder.hasRemaining()) { "ContentIds has trailing bytes" }
    return contentIds
}

fun createContentMap(
    inserts: IdMap = createIdMap(),
    deletes: IdMap = createIdMap(),
): ContentMap = ContentMap(inserts, deletes)

fun createContentIdsFromContentMap(contentMap: ContentMap): ContentIds =
    createContentIds(
        inserts = createIdSetFromIdMap(contentMap.inserts),
        deletes = createIdSetFromIdMap(contentMap.deletes),
    )

fun createContentMapFromContentIds(
    contentIds: ContentIds,
    insertAttrs: List<ContentAttribute>,
    deleteAttrs: List<ContentAttribute> = insertAttrs,
): ContentMap = createContentMap(
    inserts = createIdMapFromIdSet(contentIds.inserts, insertAttrs),
    deletes = createIdMapFromIdSet(contentIds.deletes, deleteAttrs),
)

fun excludeContentMap(contentMap: ContentMap, excludedContentIds: ContentIds): ContentMap =
    createContentMap(
        inserts = diffIdMap(contentMap.inserts, excludedContentIds.inserts),
        deletes = diffIdMap(contentMap.deletes, excludedContentIds.deletes),
    )

fun excludeContentMap(contentMap: ContentMap, excludedContentMap: ContentMap): ContentMap =
    createContentMap(
        inserts = diffIdMap(contentMap.inserts, excludedContentMap.inserts),
        deletes = diffIdMap(contentMap.deletes, excludedContentMap.deletes),
    )

fun mergeContentMaps(contentMaps: List<ContentMap>): ContentMap =
    createContentMap(
        inserts = mergeIdMaps(contentMaps.map { it.inserts }),
        deletes = mergeIdMaps(contentMaps.map { it.deletes }),
    )

fun intersectContentMap(left: ContentMap, right: ContentIds): ContentMap =
    createContentMap(
        inserts = intersectIdMapWithIdSet(left.inserts, right.inserts),
        deletes = intersectIdMapWithIdSet(left.deletes, right.deletes),
    )

fun intersectContentMap(left: ContentMap, right: ContentMap): ContentMap =
    createContentMap(
        inserts = intersectMaps(left.inserts, right.inserts),
        deletes = intersectMaps(left.deletes, right.deletes),
    )

fun filterContentMap(
    contentMap: ContentMap,
    insertPredicate: (List<ContentAttribute>) -> Boolean,
    deletePredicate: (List<ContentAttribute>) -> Boolean = insertPredicate,
): ContentMap = createContentMap(
    inserts = filterIdMap(contentMap.inserts, insertPredicate),
    deletes = filterIdMap(contentMap.deletes, deletePredicate),
)

fun writeContentMap(encoder: BinaryEncoder, contentMap: ContentMap) {
    writeContentIdMap(encoder, contentMap.inserts)
    writeContentIdMap(encoder, contentMap.deletes)
}

fun writeContentMap(encoder: IdSetEncoderV1, contentMap: ContentMap) {
    writeIdMap(encoder, contentMap.inserts)
    writeIdMap(encoder, contentMap.deletes)
}

fun encodeContentMap(contentMap: ContentMap): ByteArray {
    val encoder = IdSetEncoderV2()
    writeContentMap(encoder, contentMap)
    return encoder.toUint8Array()
}

fun readContentMap(decoder: BinaryDecoder): ContentMap =
    createContentMap(
        inserts = readContentIdMap(decoder),
        deletes = readContentIdMap(decoder),
    )

fun readContentMap(decoder: IdSetDecoderV1): ContentMap =
    createContentMap(
        inserts = readIdMap(decoder),
        deletes = readIdMap(decoder),
    )

fun decodeContentMap(bytes: ByteArray): ContentMap {
    val decoder = IdSetDecoderV2(bytes)
    val contentMap = readContentMap(decoder)
    check(!decoder.hasRemaining()) { "ContentMap has trailing bytes" }
    return contentMap
}

fun createContentIdsFromUpdate(update: ByteArray): ContentIds {
    val decoded = UpdateCodec.decode(update)
    return createContentIds(
        inserts = createInsertIdSet(decoded.itemsWithDeleteState()),
        deletes = decoded.deleteSet.toIdSet(),
    )
}

private fun DocumentUpdate.itemsWithDeleteState(): List<StoreItem> = items.map { item ->
    if (item.deleted || !deleteSet.contains(item.id)) item else item.copy(deleted = true)
}

fun createContentIdsFromUpdateV2(update: ByteArray): ContentIds {
    val decoded = UpdateCodec.decodeV2(update)
    return createContentIds(
        inserts = createInsertIdSet(decoded.itemsWithDeleteState()),
        deletes = decoded.deleteSet.toIdSet(),
    )
}

fun intersectUpdateWithContentIds(update: ByteArray, contentIds: ContentIds): ByteArray {
    requireStandardYjsUpdateInput(update, "V1")
    val decoded = UpdateCodec.decode(update)
    val filteredItems = decoded.items
        .filter { contentIds.inserts.hasId(it.id) }
        .map { item -> item.copy(requiresClockContinuity = false) }
    val filteredDeletes = decoded.deleteSet.toIdSet()
        .let { intersectSets(it, contentIds.deletes) }
        .toDeleteSet()
    if (filteredItems.size == decoded.items.size && filteredDeletes.structurallyEquals(decoded.deleteSet)) {
        return UpdateCodec.encode(decoded)
    }
    return UpdateCodec.encode(DocumentUpdate(filteredItems, filteredDeletes))
}

fun intersectUpdateWithContentIdsLossless(update: ByteArray, contentIds: ContentIds): ByteArray {
    val decoded = UpdateCodec.decode(update)
    val filteredItems = decoded.items
        .filter { contentIds.inserts.hasId(it.id) }
        .map { item -> item.copy(requiresClockContinuity = false) }
    val filteredDeletes = decoded.deleteSet.toIdSet()
        .let { intersectSets(it, contentIds.deletes) }
        .toDeleteSet()
    if (filteredItems.size == decoded.items.size && filteredDeletes.structurallyEquals(decoded.deleteSet)) return update
    return UpdateCodec.encodeLossless(DocumentUpdate(filteredItems, filteredDeletes))
}

fun intersectUpdateWithContentIdsV2(update: ByteArray, contentIds: ContentIds): ByteArray {
    requireStandardYjsUpdateInput(update, "V2")
    return UpdateCodec.decodeV2(update).let { decoded ->
        val filteredItems = decoded.items.filter { contentIds.inserts.hasId(it.id) }
        val filteredDeletes = intersectSets(decoded.deleteSet.toIdSet(), contentIds.deletes).toDeleteSet()
        if (filteredItems.size == decoded.items.size && filteredDeletes.structurallyEquals(decoded.deleteSet)) {
            UpdateCodec.encodeV2(decoded)
        } else UpdateCodec.encodeV2(DocumentUpdate(filteredItems, filteredDeletes))
    }
}

fun intersectUpdateWithContentIdsV2Lossless(update: ByteArray, contentIds: ContentIds): ByteArray =
    UpdateCodec.decodeV2(update).let { decoded ->
        val filteredItems = decoded.items.filter { contentIds.inserts.hasId(it.id) }
        val filteredDeletes = intersectSets(decoded.deleteSet.toIdSet(), contentIds.deletes).toDeleteSet()
        if (filteredItems.size == decoded.items.size && filteredDeletes.structurallyEquals(decoded.deleteSet)) update
        else UpdateCodec.encodeV2Lossless(DocumentUpdate(filteredItems, filteredDeletes))
    }

fun mergeUpdates(updates: List<ByteArray>): ByteArray {
    updates.forEach { update -> requireStandardYjsUpdateInput(update, "V1") }
    if (updates.size == 1) return UpdateCodec.encode(UpdateCodec.decode(updates.single()))
    return mergeDecodedUpdates(updates.map(UpdateCodec::decode), UpdateCodec::encode)
}

fun mergeUpdatesLossless(updates: List<ByteArray>): ByteArray {
    if (updates.size == 1) return updates.single()
    return mergeDecodedUpdates(updates.map(UpdateCodec::decode), UpdateCodec::encodeLossless)
}

fun mergeUpdatesV2(updates: List<ByteArray>): ByteArray {
    updates.forEach { update -> requireStandardYjsUpdateInput(update, "V2") }
    if (updates.size == 1) return UpdateCodec.encodeV2(UpdateCodec.decodeV2(updates.single()))
    return mergeDecodedUpdates(updates.map(UpdateCodec::decodeV2), UpdateCodec::encodeV2)
}

fun mergeUpdatesV2Lossless(updates: List<ByteArray>): ByteArray {
    if (updates.size == 1) return UpdateCodec.encodeV2Lossless(UpdateCodec.decodeV2(updates.single()))
    return mergeDecodedUpdates(updates.map(UpdateCodec::decodeV2), UpdateCodec::encodeV2Lossless)
}

fun diffUpdate(update: ByteArray, encodedStateVector: ByteArray): ByteArray {
    requireStandardYjsUpdateInput(update, "V1")
    val stateVector = decodeStateVector(encodedStateVector)
    val decoded = UpdateCodec.decode(update)
    val filtered = decoded.items.mapNotNull { item -> item.sliceFromClock(stateVector[item.id.client] ?: 0) }
    return UpdateCodec.encode(DocumentUpdate(filtered, decoded.deleteSet))
}

fun diffUpdateLossless(update: ByteArray, encodedStateVector: ByteArray): ByteArray {
    val stateVector = decodeStateVector(encodedStateVector)
    val decoded = UpdateCodec.decode(update)
    val filtered = decoded.items.mapNotNull { item -> item.sliceFromClock(stateVector[item.id.client] ?: 0) }
    return UpdateCodec.encodeLossless(DocumentUpdate(filtered, decoded.deleteSet))
}

fun diffUpdateV2(update: ByteArray, encodedStateVector: ByteArray): ByteArray {
    requireStandardYjsUpdateInput(update, "V2")
    val stateVector = decodeStateVector(encodedStateVector)
    val decoded = UpdateCodec.decodeV2(update)
    val filtered = decoded.items.mapNotNull { item -> item.sliceFromClock(stateVector[item.id.client] ?: 0) }
    return UpdateCodec.encodeV2(DocumentUpdate(filtered, decoded.deleteSet))
}

fun diffUpdateV2Lossless(update: ByteArray, encodedStateVector: ByteArray): ByteArray {
    val stateVector = decodeStateVector(encodedStateVector)
    val decoded = UpdateCodec.decodeV2(update)
    val filtered = decoded.items.mapNotNull { item -> item.sliceFromClock(stateVector[item.id.client] ?: 0) }
    return UpdateCodec.encodeV2Lossless(DocumentUpdate(filtered, decoded.deleteSet))
}

private fun StoreItem.sliceFromClock(targetClock: Long): StoreItem? {
    val end = checkedClockAdd(id.clock, length)
    if (end <= targetClock) return null
    if (id.clock >= targetClock) return this
    val deleted = content as? ItemContent.Deleted
        ?: error("state vector splits unsupported update item at $id:$targetClock")
    return copy(
        id = Id(id.client, targetClock),
        origin = if (isGc) null else Id(id.client, targetClock - 1),
        content = deleted.copy(length = end - targetClock),
    )
}

fun encodeStateVectorFromUpdate(update: ByteArray): ByteArray {
    val decoded = UpdateCodec.decode(update)
    val stateVector = decoded.items
        .groupBy { it.id.client }
        .mapValues { (_, items) -> items.contiguousClockFromZero() }
        .filterValues { it > 0 }
    return dev.yks.encodeStateVector(stateVector)
}

fun encodeStateVectorFromUpdateV2(update: ByteArray): ByteArray {
    val decoded = UpdateCodec.decodeV2(update)
    val stateVector = decoded.items
        .groupBy { it.id.client }
        .mapValues { (_, items) -> items.contiguousClockFromZero() }
        .filterValues { it > 0 }
    return dev.yks.encodeStateVector(stateVector)
}

private fun mergeDecodedUpdates(
    updates: List<DocumentUpdate>,
    encode: (DocumentUpdate) -> ByteArray,
): ByteArray {
    val items = mutableListOf<StoreItem>()
    val deleteSet = DeleteSet.empty()
    updates.forEach { decoded ->
        decoded.items.forEach { item ->
            items.mergeClockRange(item)
        }
        deleteSet.addAll(decoded.deleteSet)
    }
    deleteSet.clients.forEach { (client, ranges) ->
        ranges.forEach { range ->
            items.filter { it.id.client == client && rangesOverlap(it.id.clock, it.length, range.clock, range.length) }
                .forEach { it.deleted = true }
        }
    }
    val parentItemIds = updates.fold(linkedMapOf<String, Id>()) { merged, update ->
        merged.apply { putAll(update.parentItemIds) }
    }
    val parentKinds = updates.fold(linkedMapOf<String, RootKind>()) { merged, update ->
        merged.apply { putAll(update.parentKinds) }
    }
    return encode(
        DocumentUpdate(
            items = items
                .sortedWith(compareBy<StoreItem> { item -> item.id.client }.thenBy { item -> item.id.clock })
                .resolveSyntheticParentsFromUnion(),
            deleteSet = deleteSet,
            parentItemIds = parentItemIds,
            parentKinds = parentKinds,
        ),
    )
}

/**
 * Merged updates may contain the same packed GC/ContentDeleted clocks at different boundaries.
 * Yjs merges those structs as clock intervals. Deduplicating only by the starting [Id] either
 * loses an extending tail or leaves overlapping structs that cannot be represented on standard
 * wire. Store ordinary content one clock at a time and subtract all existing coverage from an
 * incoming range before retaining its uncovered pieces.
 */
private fun MutableList<StoreItem>.mergeClockRange(incoming: StoreItem) {
    val incomingEnd = checkedClockAdd(incoming.id.clock, incoming.length)
    val duplicateIndex = indexOfFirst { existing ->
        existing.id == incoming.id && existing.length == incoming.length
    }
    if (duplicateIndex >= 0) {
        this[duplicateIndex] = this[duplicateIndex].mergeDuplicateMetadata(incoming)
        return
    }
    val overlapping = filter { existing ->
        existing.id.client == incoming.id.client &&
            rangesOverlap(existing.id.clock, existing.length, incoming.id.clock, incoming.length)
    }
    if (incoming.deleted) overlapping.forEach { existing -> existing.deleted = true }

    var uncovered = listOf(incoming.id.clock untilClock incomingEnd)
    overlapping.sortedBy { existing -> existing.id.clock }.forEach { existing ->
        val existingEnd = checkedClockAdd(existing.id.clock, existing.length)
        uncovered = uncovered.flatMap { range -> range.subtract(existing.id.clock, existingEnd) }
    }
    uncovered.forEach { range -> add(incoming.sliceClockRange(range.start, range.end)) }
}

/** Duplicate standard/private representations must not make a lossless merge input-order dependent. */
private fun StoreItem.mergeDuplicateMetadata(other: StoreItem): StoreItem {
    require(origin == other.origin && rightOrigin == other.rightOrigin && parentSub == other.parentSub) {
        "conflicting structural metadata for duplicate update item $id"
    }
    val mergedUnresolvedParent = when {
        unresolvedParent == other.unresolvedParent -> unresolvedParent
        unresolvedParent == null -> other.unresolvedParent
        other.unresolvedParent == null -> unresolvedParent
        else -> error("conflicting unresolved parents for duplicate update item $id")
    }
    val mergedParent = when {
        parent == other.parent -> parent
        mergedUnresolvedParent != null -> minOf(parent, other.parent)
        else -> error("conflicting parents for duplicate update item $id")
    }
    return copy(
        parent = mergedParent,
        content = content.mergePrivateMetadata(other.content),
        deleted = deleted || other.deleted,
        // False is the stronger, private promise: this item may integrate without prior client clocks.
        requiresClockContinuity = requiresClockContinuity && other.requiresClockContinuity,
        isGc = isGc || other.isGc,
        unresolvedParent = mergedUnresolvedParent,
        // A GC replacement may retain the original Item.countable bit only on the richer side.
        countable = countable || other.countable,
    )
}

private fun ItemContent.mergePrivateMetadata(other: ItemContent): ItemContent = when {
    this == other -> this
    this is ItemContent.Text && other is ItemContent.Text && value == other.value && kind == other.kind -> copy(
        attributes = chooseLosslessAttributes(attributes, other.attributes),
        baseAttributes = chooseLosslessAttributes(baseAttributes, other.baseAttributes),
    )
    this is ItemContent.TextEmbed && other is ItemContent.TextEmbed && value == other.value && kind == other.kind -> copy(
        attributes = chooseLosslessAttributes(attributes, other.attributes),
        baseAttributes = chooseLosslessAttributes(baseAttributes, other.baseAttributes),
    )
    this is ItemContent.XmlType && other is ItemContent.XmlType &&
        ref == other.ref && nodeName == other.nodeName && kind == other.kind -> copy(
        attributes = chooseLosslessAttributes(attributes, other.attributes),
        baseAttributes = chooseLosslessAttributes(baseAttributes, other.baseAttributes),
    )
    else -> error("conflicting content for duplicate update item")
}

private fun chooseLosslessAttributes(
    left: Map<String, YValue>,
    right: Map<String, YValue>,
): Map<String, YValue> = when {
    left == right -> left
    left.isEmpty() -> right
    right.isEmpty() -> left
    else -> error("conflicting private text metadata for duplicate update item")
}

private data class ClockInterval(val start: Long, val end: Long) {
    init {
        require(start >= 0 && end > start) { "clock interval must be non-empty" }
    }

    fun subtract(otherStart: Long, otherEnd: Long): List<ClockInterval> {
        if (otherEnd <= start || otherStart >= end) return listOf(this)
        return buildList(2) {
            if (otherStart > start) add(ClockInterval(start, minOf(otherStart, end)))
            if (otherEnd < end) add(ClockInterval(maxOf(otherEnd, start), end))
        }
    }
}

private infix fun Long.untilClock(end: Long): ClockInterval = ClockInterval(this, end)

private fun rangesOverlap(leftClock: Long, leftLength: Long, rightClock: Long, rightLength: Long): Boolean =
    leftClock < checkedClockAdd(rightClock, rightLength) && rightClock < checkedClockAdd(leftClock, leftLength)

private fun StoreItem.sliceClockRange(start: Long, end: Long): StoreItem {
    val originalEnd = checkedClockAdd(id.clock, length)
    require(start >= id.clock && end <= originalEnd && start < end) { "slice must be contained in the item" }
    if (start == id.clock && end == originalEnd) return this
    val deleted = content as? ItemContent.Deleted
        ?: error("only packed deleted content can span multiple clocks")
    return copy(
        id = Id(id.client, start),
        origin = if (isGc || start == id.clock) origin else Id(id.client, start - 1),
        content = deleted.copy(length = end - start),
    )
}

/**
 * A standalone incremental Yjs update may inherit its parent from an origin
 * that is only present in an earlier update. The decoder preserves that
 * unresolved relationship as a synthetic parent name. Once updates are
 * merged, resolve only aliases whose anchor is present in the merged union so
 * the result can be emitted as a genuine standard update. Aliases without an
 * anchor deliberately remain untouched for standalone format conversion.
 */
private fun List<StoreItem>.resolveSyntheticParentsFromUnion(): List<StoreItem> {
    if (none { item -> item.unresolvedParent != null }) return this

    val itemsByClient = groupBy { item -> item.id.client }
        .mapValues { (_, items) -> items.sortedBy { item -> item.id.clock } }
    val resolved = mutableMapOf<Id, StoreItem>()

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
        return candidate?.takeIf { item -> id.clock < checkedClockAdd(item.id.clock, item.length) }
    }

    fun resolve(item: StoreItem, seen: Set<Id> = emptySet()): StoreItem {
        resolved[item.id]?.let { return it }
        val unresolved = item.unresolvedParent ?: return item
        if (item.id in seen) return item

        val next = when (unresolved) {
            is UnresolvedYjsParent.Nested -> {
                val owner = containing(unresolved.id) ?: return item
                val ownerRef = owner.content.directTypeRef() ?: return item
                item.copy(
                    parent = ownerRef.name,
                    content = item.content.withResolvedParentKind(ownerRef.kind),
                    unresolvedParent = null,
                )
            }
            is UnresolvedYjsParent.Inherit -> {
                val anchor = containing(unresolved.id) ?: return item
                val resolvedAnchor = resolve(anchor, seen + item.id)
                if (resolvedAnchor.unresolvedParent != null) return item
                val parentSub = resolvedAnchor.parentSub
                val kind = when {
                    parentSub != null -> RootKind.Map
                    resolvedAnchor.content is ItemContent.Deleted -> item.content.kind
                    else -> resolvedAnchor.content.kind
                }
                item.copy(
                    parent = resolvedAnchor.parent,
                    parentSub = parentSub,
                    content = item.content.withResolvedParentKind(kind),
                    unresolvedParent = null,
                )
            }
        }
        resolved[item.id] = next
        return next
    }

    return map { item -> resolve(item) }
}

private fun ItemContent.withResolvedParentKind(kind: RootKind): ItemContent = when (this) {
    is ItemContent.Value -> when (kind) {
        RootKind.Map,
        RootKind.XmlHook -> ItemContent.MapEntry(value)
        RootKind.Array -> this
        RootKind.Text,
        RootKind.XmlText -> ItemContent.TextEmbed(value, kind = kind)
        else -> this
    }
    is ItemContent.MapEntry -> if (kind == RootKind.Map || kind == RootKind.XmlHook) this else ItemContent.Value(value)
    is ItemContent.Text -> copy(kind = kind)
    is ItemContent.TextEmbed -> copy(kind = kind)
    is ItemContent.TextFormat -> copy(kind = kind)
    is ItemContent.NativeTextFormat -> copy(kind = kind)
    is ItemContent.XmlNode -> copy(kind = kind)
    is ItemContent.XmlType -> copy(kind = kind)
    is ItemContent.Deleted -> copy(kind = kind)
}

private fun StoreItem.toDecodedStruct(): DecodedUpdateStruct = DecodedUpdateStruct(
    id = id,
    origin = origin,
    rightOrigin = rightOrigin,
    parent = parent,
    parentSub = parentSub,
    kind = content.kind,
    deleted = deleted,
    length = length,
    content = content.toContent(YDoc()),
)

private fun List<StoreItem>.contiguousClockFromZero(): Long {
    var clock = 0L
    for (item in sortedBy { it.id.clock }) {
        if (item.id.clock != clock) break
        clock = checkedClockAdd(item.id.clock, item.length)
    }
    return clock
}

private fun AbstractStruct.toDecodedUpdateStruct(): DecodedUpdateStruct = when (this) {
    is ItemStruct -> DecodedUpdateStruct(
        id = id,
        origin = origin,
        rightOrigin = rightOrigin,
        parent = parent,
        parentSub = parentSub,
        kind = kind,
        deleted = deleted,
        length = length,
        content = content,
    )
    else -> error("local update codec can only write item structs")
}

private fun DecodedUpdateStruct.slice(offset: Long, offsetEnd: Long): DecodedUpdateStruct {
    require(offset >= 0) { "offset must be non-negative" }
    require(offsetEnd >= 0) { "offsetEnd must be non-negative" }
    require(offset <= length) { "offset is out of bounds" }
    require(offsetEnd <= length - offset) { "offsetEnd is out of bounds" }
    val keepLength = length - offset - offsetEnd
    require(keepLength > 0) { "sliced update structs must keep at least one clock" }
    if (offset == 0L && offsetEnd == 0L) return copy(content = content)

    var slicedContent = content.copy()
    if (offset > 0) {
        slicedContent = slicedContent.splice(offset)
    }
    if (offsetEnd > 0) {
        slicedContent.splice(keepLength)
    }
    return copy(
        id = Id(id.client, id.clock + offset),
        origin = if (offset == 0L) origin else Id(id.client, id.clock + offset - 1),
        length = keepLength,
        content = slicedContent,
    )
}

private fun DecodedUpdateStruct.toStoreItems(original: StoreItem? = null): List<StoreItem> {
    require(length > 0) { "local update structs must have positive length" }
    if (content is ContentDeleted) {
        require(content.len == length) { "deleted content length ${content.len} does not match struct length $length" }
        return listOf(
            StoreItem(
                id = id,
                origin = origin,
                rightOrigin = rightOrigin,
                parent = parent,
                parentSub = parentSub,
                content = ItemContent.Deleted(kind, length),
                deleted = true,
            ),
        )
    }
    val contents = content.toItemContents(kind, original?.content)
    require(contents.size.toLong() == length) {
        "local update content length ${contents.size} does not match struct length $length"
    }
    return contents.mapIndexed { index, itemContent ->
        val clockOffset = index.toLong()
        StoreItem(
            id = Id(id.client, id.clock + clockOffset),
            origin = if (index == 0) origin else Id(id.client, id.clock + clockOffset - 1),
            rightOrigin = rightOrigin,
            parent = parent,
            parentSub = parentSub,
            content = itemContent,
            deleted = deleted,
        )
    }
}

private fun AbstractContent.toItemContents(kind: RootKind, original: ItemContent?): List<ItemContent> =
    when (this) {
        is ContentDeleted -> toDeletedItemContents(kind)
        else -> when (kind) {
            RootKind.Text,
            RootKind.XmlText -> toTextItemContents(
                kind,
                original.textAttributesOrEmpty(),
                original.baseTextAttributesOrEmpty(),
            )
            RootKind.Array -> toArrayItemContents()
            RootKind.Map,
            RootKind.XmlHook -> if (this is ContentType) {
                listOf(toContentTypeItem(kind))
            } else {
                listOf(ItemContent.MapEntry(toSingleYValue()))
            }
            RootKind.XmlFragment -> toXmlFragmentItemContents()
            RootKind.XmlElement -> toXmlSequenceItemContents(kind)
        }
    }

private fun ContentDeleted.toDeletedItemContents(kind: RootKind): List<ItemContent> =
    listOf(ItemContent.Deleted(kind, len))

private fun AbstractContent.toTextItemContents(
    kind: RootKind,
    attributes: Map<String, YValue>,
    baseAttributes: Map<String, YValue>,
): List<ItemContent> = when (this) {
    is ContentString -> str.map { char -> ItemContent.Text(char.toString(), attributes, baseAttributes, kind) }
    is ContentEmbed -> listOf(ItemContent.TextEmbed(YValue.from(embed), attributes, baseAttributes, kind))
    is ContentType -> listOf(toContentTypeItem(kind, attributes, baseAttributes))
    is ContentTextFormatRange -> listOf(
        ItemContent.TextFormat(
            target = target,
            length = len,
            attributes = this.attributes.toSortedMap(),
            afterAttributes = afterAttributes.toSortedMap(),
            beforeAttributes = beforeAttributes.map { attributes -> attributes.toSortedMap() },
            kind = kind,
        ),
    )
    is ContentFormat -> listOf(ItemContent.NativeTextFormat(key, YValue.from(value), kind))
    else -> error("unsupported text update content: ${this::class.simpleName}")
}

private fun AbstractContent.toArrayItemContents(): List<ItemContent> = when (this) {
    is ContentAny,
    is ContentJSON -> getContent().map { value -> ItemContent.Value(YValue.from(value)) }
    is ContentType -> listOf(toContentTypeItem(RootKind.Array))
    else -> listOf(ItemContent.Value(toSingleYValue()))
}

private fun ContentType.toContentTypeItem(
    parentKind: RootKind,
    attributes: Map<String, YValue> = emptyMap(),
    baseAttributes: Map<String, YValue> = attributes,
): ItemContent.XmlType = ItemContent.XmlType(
    ref = YValue.TypeRef(type.kind, type.name),
    nodeName = type.xmlNodeNameOrEmpty(),
    kind = parentKind,
    attributes = attributes,
    baseAttributes = baseAttributes,
)

private fun AbstractContent.toSingleYValue(): YValue = when (this) {
    is ContentAny,
    is ContentJSON -> {
        val values = getContent()
        require(values.size == 1) { "array/map update content must contain exactly one value" }
        YValue.from(values.single())
    }
    is ContentBinary -> YValue.BinaryValue(content)
    is ContentString -> YValue.StringValue(str)
    is ContentEmbed -> YValue.from(embed)
    is ContentType -> YValue.TypeRef(type.kind, type.name)
    is ContentDoc -> toSubdocRef()
    else -> error("unsupported array/map update content: ${this::class.simpleName}")
}

private fun AbstractContent.toXmlNodeValues(): List<YXmlNodeValue> {
    val values = getContent()
    return values.map { value -> xmlNodeFromDeltaValue(value).toValue() }
}

private fun AbstractContent.toXmlFragmentItemContents(): List<ItemContent> =
    toXmlSequenceItemContents(RootKind.XmlFragment)

private fun AbstractContent.toXmlSequenceItemContents(kind: RootKind): List<ItemContent> = when (this) {
    is ContentType -> listOf(toContentTypeItem(kind))
    else -> toXmlNodeValues().map { node -> ItemContent.XmlNode(node, kind) }
}

private fun ItemContent?.textAttributesOrEmpty(): Map<String, YValue> = when (this) {
    is ItemContent.Text -> attributes
    is ItemContent.TextEmbed -> attributes
    is ItemContent.XmlType -> attributes
    else -> emptyMap()
}

private fun ItemContent?.baseTextAttributesOrEmpty(): Map<String, YValue> = when (this) {
    is ItemContent.Text -> baseAttributes
    is ItemContent.TextEmbed -> baseAttributes
    is ItemContent.XmlType -> baseAttributes
    else -> emptyMap()
}

private class UpdateObfuscator(
    private val options: ObfuscatorOptions,
) {
    private val nodeNameCache = linkedMapOf<String, String>()
    private val formattingKeyCache = linkedMapOf<String, String>()
    private var nextId = 0

    fun obfuscate(content: ItemContent): ItemContent = when (content) {
        is ItemContent.Value -> ItemContent.Value(obfuscate(content.value))
        is ItemContent.Text -> content.copy(
            value = "0",
            attributes = if (options.formatting) obfuscateFormatting(content.attributes) else content.attributes,
            baseAttributes = if (options.formatting) {
                obfuscateFormatting(content.baseAttributes)
            } else {
                content.baseAttributes
            },
        )
        is ItemContent.TextEmbed -> ItemContent.TextEmbed(
            obfuscate(content.value),
            if (options.formatting) obfuscateFormatting(content.attributes) else content.attributes,
            if (options.formatting) obfuscateFormatting(content.baseAttributes) else content.baseAttributes,
            content.kind,
        )
        is ItemContent.MapEntry -> ItemContent.MapEntry(obfuscate(content.value))
        is ItemContent.XmlNode -> content.copy(value = obfuscate(content.value))
        is ItemContent.XmlType -> content.copy(
            nodeName = if (options.name) obfuscateNodeName(content.nodeName) else content.nodeName,
            attributes = if (options.formatting) obfuscateFormatting(content.attributes) else content.attributes,
            baseAttributes = if (options.formatting) {
                obfuscateFormatting(content.baseAttributes)
            } else {
                content.baseAttributes
            },
        )
        is ItemContent.TextFormat -> content.copy(
            attributes = if (options.formatting) obfuscateFormatting(content.attributes) else content.attributes,
            afterAttributes = if (options.formatting) obfuscateFormatting(content.afterAttributes) else content.afterAttributes,
            beforeAttributes = if (options.formatting) {
                content.beforeAttributes.map(::obfuscateFormatting)
            } else {
                content.beforeAttributes
            },
        )
        is ItemContent.NativeTextFormat -> if (options.formatting) {
            content.copy(
                key = formattingKeyCache.getOrPut(content.key, ::nextToken),
                value = obfuscate(content.value),
            )
        } else {
            content
        }
        is ItemContent.Deleted -> content
    }

    private fun obfuscate(value: YValue): YValue = when (value) {
        YValue.Undefined -> YValue.Undefined
        YValue.Null -> YValue.Null
        is YValue.Bool -> YValue.Bool(false)
        is YValue.LongNumber -> YValue.LongNumber(0L)
        is YValue.DoubleNumber -> YValue.DoubleNumber(0.0)
        is YValue.BigIntNumber -> YValue.BigIntNumber(java.math.BigInteger.ZERO)
        is YValue.StringValue -> YValue.StringValue(value.value.obfuscatedString())
        is YValue.BinaryValue -> YValue.BinaryValue(ByteArray(value.bytes().size))
        is YValue.ListValue -> YValue.ListValue(value.value.map(::obfuscate))
        is YValue.MapValue -> YValue.MapValue(obfuscateValues(value.value))
        is YValue.TypeRef -> value
        is YValue.SubdocRef -> if (options.subdocs) {
            val id = nextToken()
            value.copy(
                guid = id,
                instanceId = id,
                collectionId = null,
                meta = YValue.Null,
            )
        } else {
            value
        }
    }

    private fun obfuscate(value: YXmlNodeValue): YXmlNodeValue = when (value) {
        is YXmlNodeValue.Text -> YXmlNodeValue.Text(
            value.text.obfuscatedString(),
            if (options.formatting) obfuscateFormatting(value.attributes) else value.attributes,
        )
        is YXmlNodeValue.Element -> YXmlNodeValue.Element(
            nodeName = if (options.name) obfuscateNodeName(value.nodeName) else value.nodeName,
            attributes = obfuscateValues(value.attributes),
            children = value.children.map(::obfuscate),
        )
    }

    private fun obfuscateValues(values: Map<String, YValue>): Map<String, YValue> =
        values.mapValues { (_, value) -> obfuscate(value) }

    private fun obfuscateFormatting(values: Map<String, YValue>): Map<String, YValue> =
        values.entries.associate { (key, value) ->
            formattingKeyCache.getOrPut(key, ::nextToken) to obfuscate(value)
        }.toSortedMap()

    private fun obfuscateNodeName(name: String): String =
        nodeNameCache.getOrPut(name) { "typename-${nextToken()}" }

    private fun nextToken(): String = (nextId++).toString()
}

private fun String.obfuscatedString(): String = "0".repeat(length)

private fun writeContentIdSet(encoder: BinaryEncoder, idSet: IdSet) {
    val clients = idSet.clients.filterValues { it.isNotEmpty() }.toSortedMap(compareByDescending { it })
    encoder.writeVarUInt(clients.size.toLong())
    clients.forEach { (client, ranges) ->
        encoder.writeVarUInt(client)
        encoder.writeVarUInt(ranges.size.toLong())
        ranges.forEach { range ->
            encoder.writeVarUInt(range.clock)
            encoder.writeVarUInt(range.len)
        }
    }
}

private fun readContentIdSet(decoder: BinaryDecoder): IdSet {
    val idSet = createIdSet()
    repeat(decoder.readVarUInt().toDecodedCount()) {
        val client = decoder.readVarUInt()
        repeat(decoder.readVarUInt().toDecodedCount()) {
            idSet.add(client, decoder.readVarUInt(), decoder.readVarUInt())
        }
    }
    return idSet
}

private fun intersectIdMapWithIdSet(idMap: IdMap, idSet: IdSet): IdMap {
    val result = createIdMap()
    idMap.ranges().forEach { (client, range) ->
        idSet.slice(client, range.clock, range.len)
            .filter { it.exists }
            .forEach { slice ->
                val attrs = idMap.slice(client, slice.clock, slice.len).firstOrNull()?.attrs.orEmpty()
                result.add(client, slice.clock, slice.len, attrs)
            }
    }
    return result
}

private fun writeContentIdMap(encoder: BinaryEncoder, idMap: IdMap) {
    val clients = idMap.clients.filterValues { it.isNotEmpty() }.toSortedMap()
    encoder.writeVarUInt(clients.size.toLong())
    clients.forEach { (client, ranges) ->
        encoder.writeVarUInt(client)
        encoder.writeVarUInt(ranges.size.toLong())
        ranges.forEach { range ->
            encoder.writeVarUInt(range.clock)
            encoder.writeVarUInt(range.len)
            encoder.writeVarUInt(range.attrs.size.toLong())
            range.attrs.forEach { attr ->
                encoder.writeString(attr.name)
                writeYValue(encoder, attr.value)
            }
        }
    }
}

private fun readContentIdMap(decoder: BinaryDecoder): IdMap {
    val idMap = createIdMap()
    repeat(decoder.readVarUInt().toDecodedCount()) {
        val client = decoder.readVarUInt()
        repeat(decoder.readVarUInt().toDecodedCount()) {
            val clock = decoder.readVarUInt()
            val len = decoder.readVarUInt()
            val attrs = List(decoder.readVarUInt().toDecodedCount()) {
                ContentAttribute(decoder.readString(), readYValue(decoder))
            }
            idMap.add(client, clock, len, attrs)
        }
    }
    return idMap
}

private fun createInsertIdSet(items: List<StoreItem>, filterDeleted: Boolean = false): IdSet {
    val idSet = createIdSet()
    items
        .filterNot { filterDeleted && it.deleted }
        .sortedWith(compareBy<StoreItem> { it.id.client }.thenBy { it.id.clock })
        .forEach { item -> idSet.add(item.id, item.length) }
    return idSet
}
