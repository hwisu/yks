package dev.yks

import java.security.SecureRandom
import java.util.concurrent.CompletableFuture
import java.util.UUID
import kotlin.jvm.JvmName

private val transactionEventNames = setOf(
    "beforeTransaction",
    "beforeObserverCalls",
    "afterTransaction",
    "afterTransactionCleanup",
)

private val docOnlyEventNames = setOf(
    "destroy",
    "load",
    "beforeAllTransactions",
)

class YDoc(
    clientId: Long = randomClientId(),
    var guid: String = randomGuid(),
    var collectionId: String? = null,
    var gc: Boolean = true,
    var gcFilter: (AbstractStruct) -> Boolean = { true },
    var meta: Any? = null,
    shouldLoad: Boolean = true,
    var autoLoad: Boolean = false,
    var isSuggestionDoc: Boolean = false,
) {
    constructor(options: YDocOptions) : this(
        clientId = options.clientId,
        guid = options.guid,
        collectionId = options.collectionId,
        gc = options.gc,
        gcFilter = options.gcFilter,
        meta = options.meta,
        shouldLoad = options.shouldLoad,
        autoLoad = options.autoLoad,
        isSuggestionDoc = options.isSuggestionDoc,
    )

    var clientId: Long = clientId.also { require(it >= 0) { "clientId must be non-negative" } }
        set(value) {
            require(value >= 0) { "clientId must be non-negative" }
            field = value
        }
    var clientID: Long
        get() = clientId
        set(value) {
            clientId = value
        }
    var collectionid: String?
        get() = collectionId
        set(value) {
            collectionId = value
        }
    var shouldLoad: Boolean = shouldLoad
        private set
    var isLoaded: Boolean = false
        private set
    var isSynced: Boolean = false
        private set
    var isDestroyed: Boolean = false
        private set
    val whenLoaded: CompletableFuture<YDoc> = CompletableFuture()
    var whenSynced: CompletableFuture<YDoc> = CompletableFuture()
        private set
    var cleanupFormatting: Boolean = !isSuggestionDoc

    val `$type`: (Any?) -> Boolean get() = `$ydoc`

    val store: StructStore = StructStore(this)
    private val rootTypes = linkedMapOf<String, AbstractYType>()
    private val nestedTypes = linkedMapOf<String, AbstractYType>()
    private val nestedNames = linkedSetOf<String>()
    private val subdocsByInstanceId = linkedMapOf<String, YDoc>()
    private val subdocObservers = mutableListOf<(YSubdocEvent) -> Unit>()
    private val subdocEventListeners = mutableListOf<(YSubdocEvent, YDoc, YTransactionEvent?) -> Unit>()
    private val parentDocs = linkedSetOf<YDoc>()
    private val eventListeners = linkedMapOf<String, MutableList<(YDocEvent) -> Unit>>()
    private val docOnlyEventListeners = linkedMapOf<String, MutableList<(YDoc) -> Unit>>()
    private val transactionEventListeners = linkedMapOf<String, MutableList<(YTransactionEvent, YDoc) -> Unit>>()
    private val syncEventListeners = mutableListOf<(Boolean, YDoc) -> Unit>()
    private val afterAllTransactionsEventListeners = mutableListOf<(YDoc, List<YTransactionEvent>) -> Unit>()
    private val pendingItems = mutableListOf<StoreItem>()
    private val pendingDeletes = DeleteSet.empty()
    private val redoneByOriginal = linkedMapOf<Id, Id>()
    private val redoneRangeEndByOriginal = linkedMapOf<Id, Id>()
    private val keptItems = linkedSetOf<Id>()
    private val beforeAllTransactionListeners = mutableListOf<() -> Unit>()
    private val beforeTransactionListeners = mutableListOf<(YTransactionEvent) -> Unit>()
    private val beforeObserverCallsListeners = mutableListOf<(YTransactionEvent) -> Unit>()
    private val afterTransactionListeners = mutableListOf<(YTransactionEvent) -> Unit>()
    private val afterTransactionCleanupListeners = mutableListOf<(YTransactionEvent) -> Unit>()
    private val afterAllTransactionsListeners = mutableListOf<(List<YTransactionEvent>) -> Unit>()
    private val updateListeners = mutableListOf<(ByteArray, Any?) -> Unit>()
    private val updateEventListeners = mutableListOf<(ByteArray, Any?, YDoc, YTransactionEvent?) -> Unit>()
    private val updateV2EventListeners = mutableListOf<(ByteArray, Any?, YDoc, YTransactionEvent?) -> Unit>()
    private val transactionListeners = mutableListOf<(YTransactionEvent) -> Unit>()
    private var currentTransaction: Transaction? = null
    private var isEmittingTransactions = false
    private var isEmittingTransactionEvent = false
    private val pendingTransactionEmits = ArrayDeque<Transaction>()
    private var nestedTypeCounter = 0L
    internal val subdocInstanceId: String = randomGuid()

    val share: Map<String, AbstractYType>
        get() = rootNames().mapNotNull { name -> rootType(name)?.let { type -> name to type } }.toMap()

    @get:JvmName("getSubdocsProperty")
    val subdocs: Set<YDoc>
        get() = getSubdocs()

    operator fun get(name: String): AbstractYType = rootType(name) ?: getArray(name)

    fun get(name: String, kind: RootKind): AbstractYType = when (kind) {
        RootKind.Array -> getArray(name)
        RootKind.Map -> getMap(name)
        RootKind.Text -> getText(name)
        RootKind.XmlFragment -> getXmlFragment(name)
        RootKind.XmlElement,
        RootKind.XmlHook,
        RootKind.XmlText -> error("XML node type refs cannot be document roots")
    }

    fun get(name: String, typeRef: Int): AbstractYType = get(name, rootKindFromTypeRefId(typeRef))

    fun getOrNull(name: String): AbstractYType? = rootType(name)

    fun get(): YArray = getArray("")

    fun getArray(name: String): YArray = getOrCreate(name, RootKind.Array) { YArray(this, name) }

    fun getMap(name: String): YMap = getOrCreate(name, RootKind.Map) { YMap(this, name) }

    fun getText(name: String): YText = getOrCreate(name, RootKind.Text) { YText(this, name) }

    fun getXmlFragment(name: String): YXmlFragment = getOrCreate(name, RootKind.XmlFragment) { YXmlFragment(this, name) }

    fun createArray(): YArray = createNestedType(RootKind.Array) { nestedName -> YArray(this, nestedName) }

    fun createMap(): YMap = createNestedType(RootKind.Map) { nestedName -> YMap(this, nestedName) }

    fun createText(): YText = createNestedType(RootKind.Text) { nestedName -> YText(this, nestedName) }

    fun createXmlFragment(): YXmlFragment =
        createNestedType(RootKind.XmlFragment) { nestedName -> YXmlFragment(this, nestedName) }

    fun toJson(): Map<String, Any?> {
        return rootNames()
            .mapNotNull { name ->
                val type = rootType(name) ?: return@mapNotNull null
                name to type.toJson()
            }
            .toMap()
    }

    fun toJSON(): Map<String, Any?> {
        return rootNames()
            .mapNotNull { name ->
                val type = rootType(name) ?: return@mapNotNull null
                name to type.toJSON()
            }
            .toMap()
    }

    fun load() {
        if (!shouldLoad) {
            shouldLoad = true
            parentDocs.forEach { parent ->
                parent.transact {
                    val active = parent.currentTransaction ?: error("transaction is not active")
                    active.loadedSubdocs.add(this)
                }
            }
        }
    }

    fun sync(synced: Boolean = true) {
        emit("sync", YDocEvent(name = "sync", synced = synced))
    }

    fun destroy() {
        if (isDestroyed) return
        val childSubdocs = getSubdocs()
        val sharedTypes = rootTypes.values.distinctBy { it.name }
        isDestroyed = true
        sharedTypes.forEach { type -> type.destroy() }
        childSubdocs.forEach { subdoc ->
            subdoc.parentDocs.remove(this)
            subdoc.destroy()
        }
        val parentDocsToNotify = parentDocs.toList()
        parentDocsToNotify.forEach { parent -> parent.handleSubdocDestroyed(this) }
        parentDocs.clear()
        emitDocEvent(YDocEvent(name = "destroy"))
        clearEventHandlers()
    }

    private fun clearEventHandlers() {
        subdocObservers.clear()
        subdocEventListeners.clear()
        eventListeners.clear()
        docOnlyEventListeners.clear()
        transactionEventListeners.clear()
        syncEventListeners.clear()
        afterAllTransactionsEventListeners.clear()
        beforeAllTransactionListeners.clear()
        beforeTransactionListeners.clear()
        beforeObserverCallsListeners.clear()
        afterTransactionListeners.clear()
        afterTransactionCleanupListeners.clear()
        afterAllTransactionsListeners.clear()
        updateListeners.clear()
        updateEventListeners.clear()
        updateV2EventListeners.clear()
        transactionListeners.clear()
    }

    fun observeSubdocs(listener: (YSubdocEvent) -> Unit): Subscription {
        subdocObservers.add(listener)
        return Subscription { subdocObservers.remove(listener) }
    }

    fun onSubdocs(listener: (YSubdocEvent, YDoc, YTransactionEvent?) -> Unit): Subscription {
        subdocEventListeners.add(listener)
        return Subscription { subdocEventListeners.remove(listener) }
    }

    fun on(eventName: String, listener: (YDocEvent) -> Unit): Subscription {
        val listeners = eventListeners.getOrPut(eventName) { mutableListOf() }
        listeners.add(listener)
        return Subscription { off(eventName, listener) }
    }

    fun onDoc(eventName: String, listener: (YDoc) -> Unit): Subscription {
        require(eventName in docOnlyEventNames) { "event '$eventName' does not provide document callback arguments" }
        val listeners = docOnlyEventListeners.getOrPut(eventName) { mutableListOf() }
        listeners.add(listener)
        return Subscription { offDoc(eventName, listener) }
    }

    fun on(eventName: String, listener: (ByteArray, Any?, YDoc, YTransactionEvent?) -> Unit): Subscription =
        when (eventName) {
            "update" -> onUpdate(listener)
            "updateV2" -> onUpdateV2(listener)
            else -> error("event '$eventName' does not provide update callback arguments")
        }

    fun onSync(listener: (Boolean, YDoc) -> Unit): Subscription {
        syncEventListeners.add(listener)
        return Subscription { offSync(listener) }
    }

    fun on(eventName: String, listener: (YTransactionEvent, YDoc) -> Unit): Subscription {
        require(eventName in transactionEventNames) {
            "event '$eventName' does not provide transaction callback arguments"
        }
        val listeners = transactionEventListeners.getOrPut(eventName) { mutableListOf() }
        listeners.add(listener)
        return Subscription { off(eventName, listener) }
    }

    fun onAfterAllTransactions(listener: (YDoc, List<YTransactionEvent>) -> Unit): Subscription {
        afterAllTransactionsEventListeners.add(listener)
        return Subscription { offAfterAllTransactions(listener) }
    }

    fun on(eventName: String, listener: (YSubdocEvent, YDoc, YTransactionEvent?) -> Unit): Subscription {
        require(eventName == "subdocs") { "event '$eventName' does not provide subdoc callback arguments" }
        return onSubdocs(listener)
    }

    fun once(eventName: String, listener: (YDocEvent) -> Unit): Subscription {
        lateinit var subscription: Subscription
        val onceListener: (YDocEvent) -> Unit = { event ->
            subscription.close()
            listener(event)
        }
        subscription = on(eventName, onceListener)
        return subscription
    }

    fun onceDoc(eventName: String, listener: (YDoc) -> Unit): Subscription {
        lateinit var subscription: Subscription
        val onceListener: (YDoc) -> Unit = { doc ->
            subscription.close()
            listener(doc)
        }
        subscription = onDoc(eventName, onceListener)
        return subscription
    }

    fun once(eventName: String, listener: (ByteArray, Any?, YDoc, YTransactionEvent?) -> Unit): Subscription {
        lateinit var subscription: Subscription
        val onceListener: (ByteArray, Any?, YDoc, YTransactionEvent?) -> Unit = { update, origin, doc, transaction ->
            subscription.close()
            listener(update, origin, doc, transaction)
        }
        subscription = on(eventName, onceListener)
        return subscription
    }

    fun onceSync(listener: (Boolean, YDoc) -> Unit): Subscription {
        lateinit var subscription: Subscription
        val onceListener: (Boolean, YDoc) -> Unit = { synced, doc ->
            subscription.close()
            listener(synced, doc)
        }
        subscription = onSync(onceListener)
        return subscription
    }

    fun once(eventName: String, listener: (YTransactionEvent, YDoc) -> Unit): Subscription {
        lateinit var subscription: Subscription
        val onceListener: (YTransactionEvent, YDoc) -> Unit = { transaction, doc ->
            subscription.close()
            listener(transaction, doc)
        }
        subscription = on(eventName, onceListener)
        return subscription
    }

    fun onceAfterAllTransactions(listener: (YDoc, List<YTransactionEvent>) -> Unit): Subscription {
        lateinit var subscription: Subscription
        val onceListener: (YDoc, List<YTransactionEvent>) -> Unit = { doc, transactions ->
            subscription.close()
            listener(doc, transactions)
        }
        subscription = onAfterAllTransactions(onceListener)
        return subscription
    }

    fun once(eventName: String, listener: (YSubdocEvent, YDoc, YTransactionEvent?) -> Unit): Subscription {
        lateinit var subscription: Subscription
        val onceListener: (YSubdocEvent, YDoc, YTransactionEvent?) -> Unit = { event, doc, transaction ->
            subscription.close()
            listener(event, doc, transaction)
        }
        subscription = on(eventName, onceListener)
        return subscription
    }

    fun off(eventName: String, listener: (YDocEvent) -> Unit) {
        val listeners = eventListeners[eventName] ?: return
        listeners.remove(listener)
        if (listeners.isEmpty()) {
            eventListeners.remove(eventName)
        }
    }

    fun offDoc(eventName: String, listener: (YDoc) -> Unit) {
        val listeners = docOnlyEventListeners[eventName] ?: return
        listeners.remove(listener)
        if (listeners.isEmpty()) {
            docOnlyEventListeners.remove(eventName)
        }
    }

    fun off(eventName: String, listener: (ByteArray, Any?, YDoc, YTransactionEvent?) -> Unit) {
        when (eventName) {
            "update" -> updateEventListeners.remove(listener)
            "updateV2" -> updateV2EventListeners.remove(listener)
        }
    }

    fun offSync(listener: (Boolean, YDoc) -> Unit) {
        syncEventListeners.remove(listener)
    }

    fun off(eventName: String, listener: (YTransactionEvent, YDoc) -> Unit) {
        val listeners = transactionEventListeners[eventName] ?: return
        listeners.remove(listener)
        if (listeners.isEmpty()) {
            transactionEventListeners.remove(eventName)
        }
    }

    fun offAfterAllTransactions(listener: (YDoc, List<YTransactionEvent>) -> Unit) {
        afterAllTransactionsEventListeners.remove(listener)
    }

    fun off(eventName: String, listener: (YSubdocEvent, YDoc, YTransactionEvent?) -> Unit) {
        if (eventName == "subdocs") {
            subdocEventListeners.remove(listener)
        }
    }

    fun emit(eventName: String, event: YDocEvent = YDocEvent(name = eventName)) {
        emit(if (event.name == eventName) event else event.copy(name = eventName))
    }

    fun emit(event: YDocEvent) {
        when (event.name) {
            "load" -> {
                if (!isLoaded) {
                    isLoaded = true
                    whenLoaded.complete(this)
                }
                emitDocEvent(event)
            }
            "sync" -> {
                val synced = event.synced ?: true
                if (!synced && isSynced) {
                    whenSynced = CompletableFuture()
                }
                isSynced = synced
                if (synced && !isLoaded) {
                    emit("load", YDocEvent(name = "load"))
                }
                if (synced) {
                    whenSynced.complete(this)
                }
                emitDocEvent(event.copy(synced = synced))
            }
            else -> emitDocEvent(event)
        }
    }

    fun getSubdocs(): Set<YDoc> =
        visibleSubdocRefs()
            .map(::subdocFromRef)
            .distinctBy { it.subdocInstanceId }
            .toCollection(linkedSetOf())

    fun getSubdocGuids(): Set<String> = getSubdocs().map { it.guid }.toSortedSet()

    fun <T> transact(origin: Any? = null, local: Boolean = true, block: () -> T): T {
        val existing = currentTransaction
        if (existing != null) {
            return block()
        }
        val transaction = Transaction(origin, local, beforeState = store.stateVector())
        currentTransaction = transaction
        val emitBeforeAll = !isEmittingTransactionEvent
        var blockError: Throwable? = null
        var result: T? = null
        try {
            if (emitBeforeAll) {
                emitBeforeAllTransactions()
            }
            emitBeforeTransaction(transaction)
            result = block()
        } catch (error: Throwable) {
            blockError = error
            throw error
        } finally {
            currentTransaction = null
            try {
                enqueueEmit(transaction)
            } catch (emitError: Throwable) {
                if (blockError == null) {
                    throw emitError
                }
                blockError.addSuppressed(emitError)
            }
        }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    fun <T> transact(block: (YTransaction) -> T, origin: Any? = null, local: Boolean = true): T {
        val existing = currentTransaction
        if (existing != null) {
            return block(YTransaction(this, existing))
        }
        val runBlock: () -> T = {
            val active = currentTransaction ?: error("transaction is not active")
            block(YTransaction(this, active))
        }
        return transact(origin = origin, local = local, block = runBlock)
    }

    fun observeUpdates(listener: (update: ByteArray, origin: Any?) -> Unit): Subscription {
        updateListeners.add(listener)
        return Subscription { updateListeners.remove(listener) }
    }

    fun onUpdate(listener: (update: ByteArray, origin: Any?, doc: YDoc, transaction: YTransactionEvent?) -> Unit): Subscription {
        updateEventListeners.add(listener)
        return Subscription { updateEventListeners.remove(listener) }
    }

    fun onUpdateV2(listener: (update: ByteArray, origin: Any?, doc: YDoc, transaction: YTransactionEvent?) -> Unit): Subscription {
        updateV2EventListeners.add(listener)
        return Subscription { updateV2EventListeners.remove(listener) }
    }

    fun observeBeforeAllTransactions(listener: () -> Unit): Subscription {
        beforeAllTransactionListeners.add(listener)
        return Subscription { beforeAllTransactionListeners.remove(listener) }
    }

    fun observeBeforeTransactions(listener: (YTransactionEvent) -> Unit): Subscription {
        beforeTransactionListeners.add(listener)
        return Subscription { beforeTransactionListeners.remove(listener) }
    }

    fun observeBeforeObserverCalls(listener: (YTransactionEvent) -> Unit): Subscription {
        beforeObserverCallsListeners.add(listener)
        return Subscription { beforeObserverCallsListeners.remove(listener) }
    }

    fun observeAfterTransactions(listener: (YTransactionEvent) -> Unit): Subscription {
        afterTransactionListeners.add(listener)
        return Subscription { afterTransactionListeners.remove(listener) }
    }

    fun observeAfterTransactionCleanup(listener: (YTransactionEvent) -> Unit): Subscription {
        afterTransactionCleanupListeners.add(listener)
        return Subscription { afterTransactionCleanupListeners.remove(listener) }
    }

    fun observeAfterAllTransactions(listener: (List<YTransactionEvent>) -> Unit): Subscription {
        afterAllTransactionsListeners.add(listener)
        return Subscription { afterAllTransactionsListeners.remove(listener) }
    }

    internal fun observeTransactions(listener: (YTransactionEvent) -> Unit): Subscription {
        transactionListeners.add(listener)
        return Subscription { transactionListeners.remove(listener) }
    }

    fun encodeStateVector(): ByteArray = dev.yks.encodeStateVector(store.stateVector())

    internal fun stateVector(): StateVector = store.stateVector()

    internal fun integrityCheck() {
        store.integrityCheck()
    }

    internal fun deleteSet(): DeleteSet = store.deleteSet()

    internal fun encodeSnapshotAsUpdate(snapshot: Snapshot): ByteArray {
        val items = store.allItems()
            .filter { item -> item.id.clock < (snapshot.sv[item.id.client] ?: 0) }
            .map { item -> item.copy(deleted = snapshot.ds.hasId(item.id)) }
        return UpdateCodec.encode(DocumentUpdate(items, snapshot.deleteSet))
    }

    fun encodeStateAsUpdate(encodedStateVector: ByteArray = ByteArray(0)): ByteArray {
        val stateVector = decodeStateVector(encodedStateVector)
        val updates = mutableListOf(
            UpdateCodec.encode(DocumentUpdate(store.itemsSince(stateVector), store.deleteSet())),
        )
        pendingDeleteSetUpdate()?.let(updates::add)
        pendingStructsView()?.update
            ?.let { pendingUpdate -> diffUpdate(pendingUpdate, encodedStateVector) }
            ?.let(updates::add)
        return if (updates.size == 1) updates.single() else mergeUpdates(updates)
    }

    fun applyUpdate(update: ByteArray, origin: Any? = null) {
        val decoded = UpdateCodec.decode(update)
        applyUpdate(decoded, origin)
    }

    internal fun applyUpdate(update: DocumentUpdate, origin: Any? = null) {
        avoidClientIdCollision(update)
        transact(origin, local = false) {
            integrateRemote(update.items)
            applyDeleteSet(update.deleteSet)
        }
    }

    internal fun nextId(): Id = Id(clientId, store.getClock(clientId))

    internal fun visibleSequence(parent: String): List<StoreItem> =
        store.sequence(parent).filter { !it.deleted && it.content.isCountable() }

    internal fun sequence(parent: String): List<StoreItem> = store.sequence(parent)

    internal fun typeChildren(type: AbstractYType): List<StoreItem> {
        require(type.doc === this) { "type must belong to this document" }
        return sequence(type.name).filter { item -> item.content.kind == type.kind }
    }

    internal fun directNestedChildTypes(type: AbstractYType): List<AbstractYType> {
        require(type.doc === this) { "type must belong to this document" }
        return directNestedChildren(type.name).mapNotNull { (_, nestedName) -> typeForParent(nestedName) }
    }

    internal fun parentOf(type: AbstractYType): AbstractYType? {
        require(type.doc === this) { "type must belong to this document" }
        val candidates = (rootNames().mapNotNull(::rootType) + nestedNames.mapNotNull(::typeForParent))
            .distinctBy { it.name }
        return candidates.firstOrNull { candidate ->
            candidate.name != type.name && directNestedChildren(candidate.name).any { (_, nestedName) -> nestedName == type.name }
        }
    }

    internal fun getItem(id: Id): StoreItem? = store.getStoreItem(id)

    internal fun pendingStructsView(): PendingStructs? {
        if (pendingItems.isEmpty()) return null
        return PendingStructs(
            missing = pendingItems.missingDependencies(),
            update = UpdateCodec.encode(DocumentUpdate(pendingItems.toList(), DeleteSet.empty())),
        )
    }

    internal fun setPendingStructsView(pendingStructs: PendingStructs?) {
        pendingItems.clear()
        pendingStructs?.let { pending ->
            pendingItems.addAll(UpdateCodec.decode(pending.update).items)
        }
    }

    internal fun pendingDeleteSetUpdate(): ByteArray? {
        val pendingIds = diffIdSet(pendingDeletes.toIdSet(), store.deleteSet().toIdSet())
        if (pendingIds.isEmpty()) return null
        return UpdateCodec.encode(DocumentUpdate(emptyList(), pendingIds.toDeleteSet()))
    }

    internal fun setPendingDeleteSetUpdate(update: ByteArray?) {
        pendingDeletes.clients.clear()
        update?.let { pendingDeletes.addAll(UpdateCodec.decode(it).deleteSet) }
    }

    internal fun itemsForIdSet(
        idSet: IdSet,
        predicate: (StoreItem) -> Boolean = { true },
        deletedOverride: Boolean? = null,
    ): List<StoreItem> {
        val items = mutableListOf<StoreItem>()
        idSet.ranges().forEach { (client, range) ->
            for (clock in range.clock until range.end) {
                val item = getItem(Id(client, clock))
                if (item != null && predicate(item)) {
                    items.add(if (deletedOverride == null) item.copy() else item.copy(deleted = deletedOverride))
                }
            }
        }
        return items
    }

    internal fun followRedone(id: Id): Id {
        var current = id
        val seen = mutableSetOf<Id>()
        while (seen.add(current)) {
            current = redoneByOriginal[current] ?: return current
        }
        return current
    }

    internal fun redoneRangeEnd(id: Id): Id? = redoneRangeEndByOriginal[id]?.let(::followRedone)

    internal fun setItemKeep(id: Id, keep: Boolean) {
        if (keep) {
            keptItems.add(id)
        } else {
            keptItems.remove(id)
        }
    }

    internal fun isItemKept(id: Id): Boolean = id in keptItems

    internal fun typeFromItemId(id: Id): AbstractYType? {
        val item = store.getStoreItem(id) ?: return null
        val ref = item.content.directTypeRef() ?: return null
        return typeFromRef(ref)
    }

    internal fun typeRefItemId(type: AbstractYType): Id? {
        require(type.doc === this) { "type must belong to this document" }
        return store.allItems().firstOrNull { item ->
            val ref = item.content.directTypeRef()
            ref?.name == type.name && ref.kind == type.kind
        }?.id
    }

    internal fun rootType(name: String): AbstractYType? {
        rootTypes[name]?.let { return it }
        val kind = store.allItems().firstOrNull { it.parent == name && it.parentSub == null }?.content?.kind
            ?: store.allItems().firstOrNull { it.parent == name }?.content?.kind
            ?: return null
        return when (kind) {
            RootKind.Array -> getArray(name)
            RootKind.Map -> getMap(name)
            RootKind.Text -> getText(name)
            RootKind.XmlFragment -> getXmlFragment(name)
            RootKind.XmlElement,
            RootKind.XmlHook,
            RootKind.XmlText -> error("XML node type refs cannot be document roots")
        }
    }

    fun rootNames(): Set<String> {
        return (rootTypes.keys + store.allItems().map { it.parent })
            .filterNot { it in nestedNames }
            .toSortedSet()
    }

    internal fun storeValue(value: Any?): YValue = storeAnyValue(value).also(::rememberNestedRefs)

    private fun storeAnyValue(value: Any?): YValue = when (value) {
        null -> YValue.Null
        is YValue -> value
        is AbstractYType -> registerNestedTypeValue(value).let { nested ->
            YValue.TypeRef(nested.kind, nested.name)
        }
        is YDoc -> registerSubdocValue(value)
        is Boolean -> YValue.Bool(value)
        is Byte -> YValue.LongNumber(value.toLong())
        is Short -> YValue.LongNumber(value.toLong())
        is Int -> YValue.LongNumber(value.toLong())
        is Long -> YValue.LongNumber(value)
        is Float -> YValue.DoubleNumber(value.toDouble())
        is Double -> YValue.DoubleNumber(value)
        is String -> YValue.StringValue(value)
        is ByteArray -> YValue.BinaryValue(value.copyOf())
        is List<*> -> YValue.ListValue(value.map(::storeAnyValue))
        is Array<*> -> YValue.ListValue(value.map(::storeAnyValue))
        is Map<*, *> -> YValue.MapValue(value.entries.associate { (key, nested) ->
            require(key is String) { "YValue map keys must be strings" }
            key to storeAnyValue(nested)
        }.toSortedMap())
        else -> error("unsupported YValue type: ${value::class.qualifiedName}")
    }

    internal fun valueToAny(value: YValue): Any? = when (value) {
        is YValue.TypeRef -> typeFromRef(value)
        is YValue.SubdocRef -> subdocFromRef(value)
        is YValue.ListValue -> value.value.map { valueToAny(it) }
        is YValue.MapValue -> value.value.mapValues { (_, nested) -> valueToAny(nested) }
        else -> value.toAny()
    }

    internal fun valueToJson(value: YValue): Any? = when (value) {
        is YValue.TypeRef -> typeFromRef(value).toJson()
        is YValue.SubdocRef -> mapOf("guid" to value.guid)
        is YValue.ListValue -> value.value.map { valueToJson(it) }
        is YValue.MapValue -> value.value.mapValues { (_, nested) -> valueToJson(nested) }
        else -> value.toAny()
    }

    internal fun pathBetween(
        parent: String,
        child: String,
        renderer: AbstractRenderer = baseRenderer,
    ): List<Any>? {
        if (parent == child) return emptyList()
        fun visit(current: String, seen: Set<String>): List<Any>? {
            directNestedChildren(current, renderer).forEach { (segments, nestedName) ->
                if (nestedName == child) return segments
                if (nestedName !in seen) {
                    val nestedPath = visit(nestedName, seen + nestedName)
                    if (nestedPath != null) return segments + nestedPath
                }
            }
            return null
        }
        return visit(parent, setOf(parent))
    }

    internal fun insertionAnchors(parent: String, kind: RootKind, index: Int): Pair<Id?, Id?> {
        val full = sequence(parent).filter { it.content.kind == kind && it.content.isCountable() }
        val visible = full.filter { !it.deleted }
        require(index <= visible.size) { "insert index is out of bounds" }
        val right = visible.getOrNull(index)
        val rightIndex = right?.let { full.indexOf(it) } ?: full.size
        val origin = full.getOrNull(rightIndex - 1)?.id
        return origin to right?.id
    }

    internal fun visibleMapValue(parent: String, key: String): YValue? {
        return currentMapItem(parent, key)
            ?.takeIf { item -> !item.deleted }
            ?.content
            ?.let { it as? ItemContent.MapEntry }
            ?.value
    }

    internal fun visibleMap(parent: String): Map<String, YValue> {
        val keys = store.allItems().asSequence()
            .filter { it.parent == parent && it.parentSub != null }
            .mapNotNull { it.parentSub }
            .toSortedSet()
        return keys.mapNotNull { key -> visibleMapValue(parent, key)?.let { key to it } }.toMap()
    }

    internal fun mapValueAtSnapshot(type: AbstractYType, key: String, snapshot: Snapshot): YValue? {
        require(type.doc === this) { "type must belong to this document" }
        val item = mapItemOrder(type.name, key)
            .asReversed()
            .firstOrNull { item -> item.id.clock < (snapshot.sv[item.id.client] ?: 0) }
            ?: return null
        if (snapshot.ds.hasId(item.id)) return null
        val content = item.content as? ItemContent.MapEntry ?: return null
        return content.value
    }

    internal fun mapAtSnapshot(type: AbstractYType, snapshot: Snapshot): Map<String, YValue> {
        require(type.doc === this) { "type must belong to this document" }
        val keys = store.allItems().asSequence()
            .filter { it.parent == type.name && it.parentSub != null }
            .mapNotNull { it.parentSub }
            .toSortedSet()
        return keys.mapNotNull { key -> mapValueAtSnapshot(type, key, snapshot)?.let { key to it } }.toMap()
    }

    internal fun currentMapItemId(parent: String, key: String): Id? = currentMapItem(parent, key)?.id

    internal fun currentVisibleMapItemId(parent: String, key: String): Id? =
        currentMapItem(parent, key)?.takeUnless { item -> item.deleted }?.id

    private fun currentMapItem(parent: String, key: String): StoreItem? =
        mapItemOrder(parent, key).lastOrNull()

    private fun mapItemOrder(parent: String, key: String): List<StoreItem> {
        val entries = store.mapEntries(parent, key)
        if (entries.isEmpty()) return emptyList()
        val knownIds = entries.map { item -> item.id }.toSet()
        val remaining = entries.sortedBy { item -> item.id }.toMutableList()
        val ordered = mutableListOf<StoreItem>()

        while (remaining.isNotEmpty()) {
            val nextIndex = remaining.indexOfFirst { item ->
                item.origin == null || item.origin !in knownIds || ordered.any { existing -> existing.id == item.origin }
            }.takeIf { index -> index >= 0 } ?: 0
            insertMapItem(ordered, remaining.removeAt(nextIndex))
        }

        return ordered
    }

    private fun insertMapItem(ordered: MutableList<StoreItem>, item: StoreItem) {
        var leftIndex = item.origin?.let { origin ->
            ordered.indexOfFirst { existing -> existing.id == origin }.takeIf { index -> index >= 0 }
        } ?: -1
        var scanIndex = leftIndex + 1
        val conflictingItems = linkedSetOf<Id>()
        val itemsBeforeOrigin = linkedSetOf<Id>()

        while (scanIndex < ordered.size) {
            val other = ordered[scanIndex]
            itemsBeforeOrigin.add(other.id)
            conflictingItems.add(other.id)
            when {
                compareIDs(item.origin, other.origin) -> {
                    if (other.id.client < item.id.client) {
                        leftIndex = scanIndex
                        conflictingItems.clear()
                    } else if (compareIDs(item.rightOrigin, other.rightOrigin)) {
                        break
                    }
                }
                other.origin != null && other.origin in itemsBeforeOrigin -> {
                    if (other.origin !in conflictingItems) {
                        leftIndex = scanIndex
                        conflictingItems.clear()
                    }
                }
                else -> break
            }
            scanIndex++
        }

        ordered.add(leftIndex + 1, item)
    }

    internal fun sequenceAtSnapshot(type: AbstractYType, snapshot: Snapshot): List<StoreItem> {
        require(type.doc === this) { "type must belong to this document" }
        return sequence(type.name).filter { item ->
            item.content.kind == type.kind && item.content.isCountable() && item.isVisibleIn(snapshot)
        }
    }

    private fun textItemsAtSnapshot(type: YText, snapshot: Snapshot): List<StoreItem> {
        require(type.doc === this) { "type must belong to this document" }
        val textItems = sequence(type.name)
            .filter { item -> item.content.isTextCountable() && item.isVisibleIn(snapshot) }
            .map { item -> item.withBaseTextAttributes() }
            .toMutableList()
        sequence(type.name)
            .filter { item -> item.content is ItemContent.TextFormat && item.isVisibleIn(snapshot) }
            .sortedWith(textFormatApplicationOrder)
            .forEach { formatItem -> applySingleTextFormat(textItems, formatItem) }
        return textItems
    }

    internal fun arrayAtSnapshot(type: YArray, snapshot: Snapshot): List<Any?> =
        sequenceAtSnapshot(type, snapshot).map { item -> valueToAny((item.content as ItemContent.Value).value) }

    internal fun textDeltaAtSnapshot(type: YText, snapshot: Snapshot): YTextDelta {
        val delta = YTextDelta()
        var pendingText = StringBuilder()
        var pendingAttributes: Map<String, Any?>? = null

        fun flush() {
            if (pendingText.isNotEmpty()) {
                delta.insert(pendingText.toString(), pendingAttributes.orEmpty())
                pendingText = StringBuilder()
            }
        }

        textItemsAtSnapshot(type, snapshot).forEach { item ->
            val attrs = textAttributesToPublic(item.content.textAttributesOrEmpty())
            if (pendingAttributes != null && pendingAttributes != attrs) flush()
            pendingAttributes = attrs
            when (val content = item.content) {
                is ItemContent.Text -> pendingText.append(content.value)
                is ItemContent.TextEmbed -> {
                    flush()
                    delta.insertEmbed(valueToAny(content.value), attrs)
                    pendingAttributes = null
                }
                else -> Unit
            }
        }
        flush()
        return delta
    }

    internal fun textStringAtSnapshot(type: YText, snapshot: Snapshot): String =
        textItemsAtSnapshot(type, snapshot).joinToString(separator = "") { item ->
            when (val content = item.content) {
                is ItemContent.Text -> content.value
                is ItemContent.TextEmbed -> "\uFFFC"
                else -> ""
            }
        }

    internal fun textArrayAtSnapshot(type: YText, snapshot: Snapshot): List<Any?> =
        textItemsAtSnapshot(type, snapshot).map { item ->
            when (val content = item.content) {
                is ItemContent.Text -> content.value
                is ItemContent.TextEmbed -> valueToAny(content.value)
                else -> null
            }
        }

    internal fun xmlFragmentAtSnapshot(type: YXmlFragment, snapshot: Snapshot): List<Any?> =
        sequenceAtSnapshot(type, snapshot).map { item -> (item.content as ItemContent.XmlNode).value.toEventJson() }

    internal fun xmlFragmentArrayAtSnapshot(type: YXmlFragment, snapshot: Snapshot): List<YXmlNode> =
        sequenceAtSnapshot(type, snapshot).map { item -> (item.content as ItemContent.XmlNode).value.toNode() }

    internal fun xmlFragmentStringAtSnapshot(
        type: YXmlFragment,
        snapshot: Snapshot,
        forceTag: Boolean = false,
    ): String = renderXmlFragmentString(
        name = type.name,
        attrs = mapAtSnapshot(type, snapshot).mapValues { (_, value) -> valueToAny(value) },
        nodes = xmlFragmentArrayAtSnapshot(type, snapshot),
        forceTag = forceTag,
    )

    internal fun xmlFragmentDeltaAtSnapshot(type: YXmlFragment, snapshot: Snapshot): List<YArrayDeltaOp> {
        val nodes = xmlFragmentArrayAtSnapshot(type, snapshot)
        return if (nodes.isEmpty()) emptyList() else listOf(YArrayDeltaOp(insert = nodes))
    }

    internal fun setTypeAttribute(parent: String, key: String, value: Any?): Any? {
        transact {
            val content = ItemContent.MapEntry(storeValue(value))
            val item = StoreItem(
                id = nextId(),
                origin = currentMapItem(parent, key)?.id,
                rightOrigin = null,
                parent = parent,
                parentSub = key,
                content = content,
            )
            integrateLocal(item)
        }
        return typeAttribute(parent, key)
    }

    internal fun typeAttribute(parent: String, key: String): Any? = visibleMapValue(parent, key)?.let(::valueToAny)

    internal fun typeAttributes(parent: String): Map<String, Any?> =
        visibleMap(parent).mapValues { (_, value) -> valueToAny(value) }

    internal fun hasTypeAttribute(parent: String, key: String): Boolean = visibleMapValue(parent, key) != null

    internal fun deleteTypeAttribute(parent: String, key: String) {
        deleteMapKey(parent, key)
    }

    internal fun integrateLocal(item: StoreItem) {
        if (item.content is ItemContent.TextFormat) {
            integrateLocalTextFormat(item)
            return
        }
        transact {
            val previousMapCurrent = item.parentSub?.let { key -> currentMapItem(item.parent, key) }
            captureParentBefore(item.parent, item.content.kind)
            check(store.add(item)) { "duplicate local item id: ${item.id}" }
            rememberNestedRefs(item.content)
            currentTransaction?.addedItems?.add(item)
            currentTransaction?.changedParents?.add(item.parent)
            deletePreviousMapCurrentIfSuperseded(item, previousMapCurrent)
        }
    }

    private fun integrateLocalTextFormat(item: StoreItem) {
        transact {
            captureParentBefore(item.parent, item.content.kind)
            check(store.add(item)) { "duplicate local item id: ${item.id}" }
            applyTextFormat(item)
            currentTransaction?.addedItems?.add(item)
            currentTransaction?.changedParents?.add(item.parent)
        }
    }

    internal fun deleteVisible(parent: String, index: Int, length: Int, origin: Any? = null) {
        val visible = visibleSequence(parent)
        val start = index.coerceAtLeast(0)
        require(start <= visible.size) { "delete range is out of bounds" }
        if (length <= 0 || start == visible.size) return
        val end = (start + length).coerceAtMost(visible.size)
        transact(origin = origin) {
            val deleteSet = DeleteSet.empty()
            visible.subList(start, end).forEach { deleteSet.add(it.id, it.length) }
            applyDeleteSet(deleteSet)
        }
    }

    internal fun deleteMapKey(parent: String, key: String) {
        transact {
            val deleteSet = DeleteSet.empty()
            currentMapItem(parent, key)
                ?.takeIf { item -> !item.deleted }
                ?.let { item -> deleteSet.add(item.id, item.length) }
            applyDeleteSet(deleteSet)
        }
    }

    internal fun deleteItemsByIds(ids: Iterable<Id>) {
        transact {
            val deleteSet = DeleteSet.empty()
            ids.forEach { id ->
                val item = store.getStoreItem(id)
                if (item != null && !item.deleted) {
                    deleteSet.add(id, item.length)
                }
            }
            applyDeleteSet(deleteSet)
        }
    }

    internal fun restoreItems(items: List<RestoreItem>): List<StoreItem> {
        if (items.isEmpty()) return emptyList()
        val originalPositions = restorePositions(items)
        val sortedItems = items.sortedForRestore(originalPositions)
        val restored = mutableListOf<StoreItem>()
        transact {
            val restoredByOriginal = mutableMapOf<Id, Id>()
            val restoredPairs = mutableListOf<Pair<StoreItem, StoreItem>>()
            sortedItems.forEach { original ->
                val source = original.item
                val mapOrigin = source.parentSub?.let { key -> currentMapItem(source.parent, key)?.id }
                val item = StoreItem(
                    id = nextId(),
                    origin = if (source.parentSub != null) {
                        mapOrigin
                    } else if (original.anchorAfterOriginal) {
                        source.origin?.let { restoredByOriginal[it] } ?: source.id
                    } else {
                        source.origin?.let { restoredByOriginal[it] } ?: source.origin
                    },
                    rightOrigin = if (source.parentSub != null) {
                        null
                    } else if (original.anchorAfterOriginal) {
                        inferRightOrigin(source)
                    } else {
                        source.rightOrigin
                    },
                    parent = source.parent,
                    parentSub = source.parentSub,
                    content = source.content,
                    deleted = false,
                )
                check(store.add(item)) { "duplicate restored item id: ${item.id}" }
                restoredByOriginal[source.id] = item.id
                redoneByOriginal[source.id] = item.id
                restoredPairs.add(source to item)
                restored.add(item)
                currentTransaction?.addedItems?.add(item)
                currentTransaction?.changedParents?.add(item.parent)
            }
            rememberRedoneRangeEnds(restoredPairs, originalPositions)
            restored
                .filter { item -> item.content is ItemContent.TextFormat }
                .map { item -> item.parent }
                .toSet()
                .forEach { parent ->
                    captureParentBefore(parent, RootKind.Text)
                    reapplyTextFormats(parent)
                    currentTransaction?.changedParents?.add(parent)
                }
        }
        return restored
    }

    internal fun restoreItemAtCurrentPosition(item: StoreItem): RestoreItem {
        val anchored = if (item.parentSub == null && item.rightOrigin == null) {
            item.copy(rightOrigin = inferRightOrigin(item))
        } else {
            item
        }
        return RestoreItem(anchored.copy(deleted = false), anchorAfterOriginal = true)
    }

    private fun applyDeleteSet(deleteSet: DeleteSet) {
        if (deleteSet.isEmpty) return
        val expandedDeleteSet = expandDeleteSetWithNestedTypeContent(deleteSet)
        val newlyDeleted = store.allItems()
            .filter { !it.deleted && expandedDeleteSet.contains(it.id) }
            .map { it.copy(deleted = false) }
        newlyDeleted.forEach { captureParentBefore(it.parent, it.content.kind) }
        pendingDeletes.addAll(expandedDeleteSet)
        val changed = store.markDeleted(expandedDeleteSet)
        if (changed) {
            currentTransaction?.deleteSet?.addAll(expandedDeleteSet)
            newlyDeleted.forEach {
                currentTransaction?.deletedItems?.add(it)
                currentTransaction?.changedParents?.add(it.parent)
            }
            newlyDeleted
                .filter { item -> item.content is ItemContent.TextFormat }
                .map { item -> item.parent }
                .toSet()
                .forEach { parent ->
                    reapplyTextFormats(parent)
                    currentTransaction?.changedParents?.add(parent)
                }
        }
    }

    private fun expandDeleteSetWithNestedTypeContent(deleteSet: DeleteSet): DeleteSet {
        val expanded = deleteSet.copy()
        val queue = ArrayDeque<StoreItem>()
        deleteSet.clients.forEach { (client, ranges) ->
            ranges.forEach { range ->
                for (clock in range.clock until range.end) {
                    store.getStoreItem(Id(client, clock))?.let(queue::add)
                }
            }
        }

        val visited = linkedSetOf<Id>()
        while (queue.isNotEmpty()) {
            val item = queue.removeFirst()
            if (!visited.add(item.id)) continue
            val ref = item.content.directTypeRef() ?: continue
            store.allItems()
                .filter { child -> child.parent == ref.name && !child.deleted }
                .forEach { child ->
                    if (!expanded.contains(child.id)) {
                        expanded.add(child.id, child.length)
                    }
                    queue.add(child)
                }
        }
        return expanded
    }

    private fun integrateRemote(items: List<StoreItem>) {
        pendingItems.addAll(items.filterNot { store.contains(it.id) })
        var madeProgress: Boolean
        do {
            madeProgress = false
            val iterator = pendingItems.iterator()
            while (iterator.hasNext()) {
                val item = iterator.next()
                if (canIntegrate(item)) {
                    iterator.remove()
                    if (pendingDeletes.contains(item.id)) {
                        item.deleted = true
                    }
                    captureParentBefore(item.parent, item.content.kind)
                    if (store.add(item)) {
                        if (item.content is ItemContent.TextFormat) {
                            applyTextFormat(item)
                        }
                        rememberNestedRefs(item.content)
                        currentTransaction?.addedItems?.add(item)
                        currentTransaction?.changedParents?.add(item.parent)
                        deletePreviousMapCurrentIfSuperseded(
                            item,
                            item.parentSub?.let { key ->
                                mapItemOrder(item.parent, key).dropLast(1).lastOrNull()
                            },
                        )
                    }
                    madeProgress = true
                }
            }
        } while (madeProgress)
    }

    private fun deletePreviousMapCurrentIfSuperseded(item: StoreItem, previousMapCurrent: StoreItem?) {
        val key = item.parentSub ?: return
        if (previousMapCurrent == null || previousMapCurrent.deleted) return
        if (currentMapItem(item.parent, key)?.id != item.id) return
        val deleteSet = DeleteSet.empty()
        deleteSet.add(previousMapCurrent.id, previousMapCurrent.length)
        applyDeleteSet(deleteSet)
    }

    private fun canIntegrate(item: StoreItem): Boolean {
        val textFormat = item.content as? ItemContent.TextFormat
        return (item.origin == null || store.contains(item.origin)) &&
            (textFormat == null || store.contains(textFormat.target)) &&
            (item.rightOrigin == null || store.contains(item.rightOrigin))
    }

    private fun applyTextFormat(item: StoreItem): List<StoreItem> {
        if (item.content !is ItemContent.TextFormat) return emptyList()
        return reapplyTextFormats(item.parent)
    }

    private fun reapplyTextFormats(parent: String): List<StoreItem> {
        val changed = mutableListOf<StoreItem>()
        sequence(parent)
            .filter { item -> !item.deleted && item.content.isTextCountable() }
            .forEach { textItem -> resetTextAttributes(textItem)?.let(changed::add) }
        sequence(parent)
            .filter { item -> !item.deleted && item.content is ItemContent.TextFormat }
            .sortedWith(textFormatApplicationOrder)
            .forEach { formatItem -> changed.addAll(applySingleTextFormat(formatItem)) }
        return changed
    }

    private fun applySingleTextFormat(item: StoreItem): List<StoreItem> {
        val format = item.content as? ItemContent.TextFormat ?: return emptyList()
        val textItems = sequence(item.parent)
            .filter { textItem -> !textItem.deleted && textItem.content.isTextCountable() }
        val start = textItems.indexOfFirst { textItem -> textItem.id == format.target }
        if (start < 0) return emptyList()

        val changed = mutableListOf<StoreItem>()
        val end = (start + format.length.toInt()).coerceAtMost(textItems.size)
        for (index in start until end) {
            replaceTextAttributes(textItems[index], format.attributes)?.let(changed::add)
        }
        if (end < textItems.size) {
            replaceTextAttributes(textItems[end], format.afterAttributes)?.let(changed::add)
        }
        return changed
    }

    private fun applySingleTextFormat(items: MutableList<StoreItem>, item: StoreItem) {
        val format = item.content as? ItemContent.TextFormat ?: return
        val start = items.indexOfFirst { textItem -> textItem.id == format.target }
        if (start < 0) return

        val end = (start + format.length.toInt()).coerceAtMost(items.size)
        for (index in start until end) {
            items.replaceTextAttributes(index, format.attributes)
        }
        if (end < items.size) {
            items.replaceTextAttributes(end, format.afterAttributes)
        }
    }

    private fun resetTextAttributes(item: StoreItem): StoreItem? {
        val current = store.getStoreItem(item.id) ?: return null
        val baseAttributes = current.content.baseTextAttributesOrEmpty()
        return setTextAttributes(current, baseAttributes)
    }

    private fun replaceTextAttributes(item: StoreItem, attributes: Map<String, YValue>): StoreItem? {
        if (attributes.isEmpty()) return null
        val current = store.getStoreItem(item.id) ?: return null
        val nextAttributes = current.content.textAttributesOrEmpty().toMutableMap()
        attributes.forEach { (key, value) ->
            if (value == YValue.Null) {
                nextAttributes.remove(key)
            } else {
                nextAttributes[key] = value
            }
        }
        return setTextAttributes(current, nextAttributes.toSortedMap())
    }

    private fun setTextAttributes(item: StoreItem, attributes: Map<String, YValue>): StoreItem? {
        val nextContent = item.content.withTextAttributesOrNull(attributes.toSortedMap()) ?: return null
        if (nextContent == item.content) return null
        return store.replaceContent(item.id, nextContent)
    }

    private fun List<StoreItem>.missingDependencies(): Map<Long, Long> {
        val missing = linkedMapOf<Long, Long>()

        fun record(client: Long, clock: Long) {
            missing[client] = minOf(missing[client] ?: clock, clock)
        }

        fun recordIfMissing(id: Id) {
            val clock = store.getClock(id.client)
            if (id.clock >= clock && !store.skips.hasId(id)) {
                record(id.client, clock)
            }
        }

        forEach { item ->
            val clientClock = store.getClock(item.id.client)
            if (item.id.clock > clientClock) {
                record(item.id.client, clientClock)
            }
            item.origin?.let(::recordIfMissing)
            item.rightOrigin?.let(::recordIfMissing)
        }
        return missing.toSortedMap()
    }

    private fun avoidClientIdCollision(update: DocumentUpdate) {
        val hasUnknownStructFromCurrentClient = update.items.any { item ->
            item.id.client == clientId && !store.contains(item.id)
        }
        if (!hasUnknownStructFromCurrentClient) return
        val reservedClients = (store.stateVector().keys + update.items.map { it.id.client } + clientId).toSet()
        clientId = randomClientId(excluding = reservedClients)
    }

    private fun enqueueEmit(transaction: Transaction) {
        if (transaction.isEmpty) return
        pendingTransactionEmits.addLast(transaction)
        if (isEmittingTransactions) return
        isEmittingTransactions = true
        var firstError: Throwable? = null
        try {
            while (pendingTransactionEmits.isNotEmpty()) {
                val batch = mutableListOf<YTransactionEvent>()
                while (pendingTransactionEmits.isNotEmpty()) {
                    val next = pendingTransactionEmits.removeFirst()
                    val event = createTransactionEvent(next)
                    batch.add(event)
                    try {
                        val wasEmittingTransactionEvent = isEmittingTransactionEvent
                        isEmittingTransactionEvent = true
                        try {
                            emit(next, event)
                        } finally {
                            isEmittingTransactionEvent = wasEmittingTransactionEvent
                        }
                    } catch (error: Throwable) {
                        if (firstError == null) {
                            firstError = error
                        } else {
                            firstError.addSuppressed(error)
                        }
                    }
                }
                try {
                    emitAfterAllTransactions(batch)
                } catch (error: Throwable) {
                    if (firstError == null) {
                        firstError = error
                    } else {
                        firstError.addSuppressed(error)
                    }
                }
            }
        } finally {
            isEmittingTransactions = false
        }
        firstError?.let { throw it }
    }

    private fun createTransactionEvent(transaction: Transaction): YTransactionEvent {
        val update = UpdateCodec.encode(DocumentUpdate(transaction.addedItems, transaction.deleteSet))
        val insertSet = transaction.addedItems.toIdSet()
        val deleteIdSet = transaction.deleteSet.toIdSet()
        val changedParents = changedParentsFor(transaction)
        transaction.afterState = store.stateVector()
        transaction.update = update
        val subdocEvent = collectSubdocEvent(transaction)
        return YTransactionEvent(
            doc = this,
            origin = transaction.origin,
            local = transaction.local,
            update = update,
            beforeState = transaction.beforeState,
            afterState = transaction.afterState,
            insertSet = insertSet,
            deleteSet = transaction.deleteSet.copy(),
            deleteIdSet = deleteIdSet,
            cleanUps = transaction.cleanUps.copy(),
            meta = transaction.meta,
            addedStructs = transaction.addedItems.map { it.toItemStruct(this) },
            deletedStructs = transaction.deletedItems.map { it.copy(deleted = true).toItemStruct(this) },
            addedItems = transaction.addedItems.map { it.copy() },
            deletedItems = transaction.deletedItems.map { it.copy(deleted = false) },
            changedParents = changedParents,
            changedTypes = changedParents.mapNotNull { typeForParent(it) }.toSet(),
            subdocsAdded = subdocEvent?.added?.toCollection(linkedSetOf()) ?: emptySet(),
            subdocsRemoved = subdocEvent?.removed?.toCollection(linkedSetOf()) ?: emptySet(),
            subdocsLoaded = subdocEvent?.loaded?.toCollection(linkedSetOf()) ?: emptySet(),
        )
    }

    private fun emit(transaction: Transaction, event: YTransactionEvent) {
        val callbacks = mutableListOf<() -> Unit>()
        beforeObserverCallsListeners.toList().forEach { listener ->
            callbacks.add { listener(event) }
        }
        callbacks.addAll(transactionEventCallbacks("beforeObserverCalls", event))
        callbacks.addAll(docEventCallbacks("beforeObserverCalls", YDocEvent(name = "beforeObserverCalls", transaction = event)))
        transactionListeners.toList().forEach { listener ->
            callbacks.add { listener(event) }
        }
        val directEvents = linkedMapOf<String, YEvent>()
        event.changedParents.forEach { parent ->
            typeForParent(parent)?.let { type ->
                val yEvent = createEvent(type, transaction, event, transaction.beforeParents[parent])
                directEvents[parent] = yEvent
                callbacks.add { type.emit(yEvent) }
            }
        }
        callbacks.add { emitDeepEvents(transaction, event.update, directEvents) }
        afterTransactionListeners.toList().forEach { listener ->
            callbacks.add { listener(event) }
        }
        callbacks.addAll(transactionEventCallbacks("afterTransaction", event))
        callbacks.addAll(docEventCallbacks("afterTransaction", YDocEvent(name = "afterTransaction", transaction = event)))
        afterTransactionCleanupListeners.toList().forEach { listener ->
            callbacks.add { listener(event) }
        }
        callbacks.addAll(transactionEventCallbacks("afterTransactionCleanup", event))
        callbacks.addAll(
            docEventCallbacks(
                "afterTransactionCleanup",
                YDocEvent(name = "afterTransactionCleanup", transaction = event),
            ),
        )
        event.subdocEvent()?.let { subdocEvent ->
            callbacks.add { emitSubdocEvent(subdocEvent, event) }
        }
        updateListeners.toList().forEach { listener ->
            callbacks.add { listener(event.update, transaction.origin) }
        }
        updateEventListeners.toList().forEach { listener ->
            callbacks.add { listener(event.update, transaction.origin, this, event) }
        }
        callbacks.addAll(
            docEventCallbacks(
                "update",
                YDocEvent(name = "update", update = event.update, origin = transaction.origin, transaction = event),
            ),
        )
        updateV2EventListeners.toList().forEach { listener ->
            callbacks.add { listener(event.update, transaction.origin, this, event) }
        }
        callbacks.addAll(
            docEventCallbacks(
                "updateV2",
                YDocEvent(name = "updateV2", update = event.update, origin = transaction.origin, transaction = event),
            ),
        )
        callAllYksCallbacks(callbacks)
    }

    internal fun changedParentsFor(transaction: Transaction): Set<String> =
        transaction.changedParents.filterTo(linkedSetOf()) { parent ->
            val type = typeForParent(parent) ?: return@filterTo false
            val typeItemId = typeRefItemId(type) ?: return@filterTo true
            transaction.addedItems.none { item -> item.id == typeItemId }
        }

    private fun emitBeforeAllTransactions() {
        val callbacks = mutableListOf<() -> Unit>()
        beforeAllTransactionListeners.toList().forEach { listener ->
            callbacks.add { listener() }
        }
        docOnlyEventListeners["beforeAllTransactions"].orEmpty().toList().forEach { listener ->
            callbacks.add { listener(this) }
        }
        callbacks.addAll(docEventCallbacks("beforeAllTransactions", YDocEvent(name = "beforeAllTransactions")))
        callAllYksCallbacks(callbacks)
    }

    private fun emitAfterAllTransactions(events: List<YTransactionEvent>) {
        if (events.isEmpty()) return
        val frozenEvents = events.toList()
        val callbacks = mutableListOf<() -> Unit>()
        afterAllTransactionsListeners.toList().forEach { listener ->
            callbacks.add { listener(frozenEvents) }
        }
        afterAllTransactionsEventListeners.toList().forEach { listener ->
            callbacks.add { listener(this, frozenEvents) }
        }
        callbacks.addAll(
            docEventCallbacks(
                "afterAllTransactions",
                YDocEvent(name = "afterAllTransactions", transactions = frozenEvents),
            ),
        )
        callAllYksCallbacks(callbacks)
    }

    private fun emitBeforeTransaction(transaction: Transaction) {
        val event = YTransactionEvent(
            doc = this,
            origin = transaction.origin,
            local = transaction.local,
            update = ByteArray(0),
            beforeState = transaction.beforeState,
            afterState = transaction.beforeState,
            insertSet = createIdSet(),
            deleteSet = DeleteSet.empty(),
            meta = transaction.meta,
            addedItems = emptyList(),
            deletedItems = emptyList(),
            changedParents = emptySet(),
            subdocsAdded = emptySet(),
            subdocsRemoved = emptySet(),
            subdocsLoaded = emptySet(),
        )
        val callbacks = mutableListOf<() -> Unit>()
        beforeTransactionListeners.toList().forEach { listener ->
            callbacks.add { listener(event) }
        }
        callbacks.addAll(transactionEventCallbacks("beforeTransaction", event))
        callbacks.addAll(docEventCallbacks("beforeTransaction", YDocEvent(name = "beforeTransaction", transaction = event)))
        callAllYksCallbacks(callbacks)
    }

    private fun emitDeepEvents(
        transaction: Transaction,
        update: ByteArray,
        directEvents: Map<String, YEvent>,
    ) {
        if (directEvents.isEmpty()) return
        val deepTypes = materializedTypes().filter { it.hasDeepObservers || it.hasDeltaListeners || it.hasDeltaCache }
        if (deepTypes.isEmpty()) return
        val grouped = linkedMapOf<AbstractYType, MutableList<YEvent>>()
        directEvents.forEach { (changedParent, directEvent) ->
            deepTypes.forEach { ancestor ->
                val path = pathBetween(ancestor.name, changedParent) ?: return@forEach
                grouped.getOrPut(ancestor) { mutableListOf() }
                    .add(directEvent.copy(path = path, changedTarget = directEvent.target, currentTarget = ancestor))
            }
        }
        val callbacks = mutableListOf<() -> Unit>()
        grouped.forEach { (ancestor, events) ->
            ancestor.clearCache()
            val insertSet = events.first().insertSet
            val deleteSet = events.first().deleteSet
            val event = if (events.size == 1) {
                val only = events.single()
                if (only.target == ancestor) {
                    only.copy(deepEvents = events)
                } else {
                    YEvent(
                        target = ancestor,
                        origin = transaction.origin,
                        update = update,
                        insertSet = insertSet,
                        deleteSet = deleteSet,
                        transaction = only.transaction,
                        currentTarget = ancestor,
                        path = only.path,
                        changedTarget = only.target,
                        deepEvents = events,
                    )
                }
            } else {
                YEvent(
                    target = ancestor,
                    origin = transaction.origin,
                    update = update,
                    insertSet = insertSet,
                    deleteSet = deleteSet,
                    transaction = events.firstOrNull()?.transaction,
                    currentTarget = ancestor,
                    changedTarget = ancestor,
                    deepEvents = events,
                )
            }
            if (ancestor.hasDeepObservers) {
                callbacks.add { ancestor.emitDeep(event) }
            }
            if (ancestor.hasDeltaListeners && events.any { it.target != ancestor }) {
                callbacks.add { ancestor.emitDelta(event) }
            }
        }
        callAllYksCallbacks(callbacks)
    }

    private fun captureParentBefore(parent: String, kindHint: RootKind) {
        val transaction = currentTransaction ?: return
        val captured = when (kindHint) {
            RootKind.Map -> ParentSnapshot.MapSnapshot(visibleMap(parent))
            RootKind.Array,
            RootKind.Text,
            RootKind.XmlFragment -> ParentSnapshot.SequenceSnapshot(kindHint, visibleSequence(parent))
            RootKind.XmlElement,
            RootKind.XmlHook,
            RootKind.XmlText -> error("XML node type refs cannot be captured as parent snapshots")
        }
        val existing = transaction.beforeParents[parent]
        transaction.beforeParents[parent] = existing?.merge(captured) ?: captured
    }

    private fun createEvent(
        type: AbstractYType,
        transaction: Transaction,
        event: YTransactionEvent,
        before: ParentSnapshot?,
    ): YEvent {
        val update = event.update
        val mapChanges = before?.mapSnapshot()?.let { mapBefore ->
            diffMapChanges(
                before = mapBefore.values,
                after = visibleMap(type.name),
                changedKeys = changedMapKeys(type.name, transaction),
            )
        }.orEmpty()
        val sequenceBefore = before?.sequenceSnapshot()
        val arrayDelta = when {
            sequenceBefore == null -> emptyList()
            sequenceBefore.kind == RootKind.Array && type.kind == RootKind.Array -> {
                diffArrayDelta(sequenceBefore.items, visibleSequence(type.name))
            }
            sequenceBefore.kind == RootKind.XmlFragment && type.kind == RootKind.XmlFragment -> {
                diffXmlDelta(sequenceBefore.items, visibleSequence(type.name))
            }
            else -> emptyList()
        }
        val textDelta = if (sequenceBefore?.kind == RootKind.Text && type.kind == RootKind.Text) {
            diffTextDelta(sequenceBefore.items, visibleSequence(type.name))
        } else {
            YTextDelta()
        }
        val childListChanged = arrayDelta.isNotEmpty() || textDelta.ops.isNotEmpty()
        return YEvent(
            target = type,
            origin = transaction.origin,
            update = update,
            insertSet = event.insertSet,
            deleteSet = event.deleteSet,
            transaction = event,
            keysChanged = mapChanges.keys,
            mapChanges = mapChanges,
            mapDelta = mapChanges.toMapDelta(),
            name = mapChanges.keys.singleOrNull(),
            value = mapChanges.keys.singleOrNull()?.let { key -> typeAttribute(type.name, key) },
            arrayDelta = arrayDelta,
            textDelta = textDelta,
            childListChanged = childListChanged,
        )
    }

    private fun changedMapKeys(parent: String, transaction: Transaction): Set<String> =
        (transaction.addedItems.asSequence() + transaction.deletedItems.asSequence())
            .filter { item -> item.parent == parent }
            .mapNotNull { item -> item.parentSub }
            .toSortedSet()

    private fun diffMapChanges(
        before: Map<String, YValue>,
        after: Map<String, YValue>,
        changedKeys: Set<String>,
    ): Map<String, YMapChange> {
        return (before.keys + after.keys + changedKeys).sorted().mapNotNull { key ->
            val oldPresent = before.containsKey(key)
            val newPresent = after.containsKey(key)
            val oldValue = before[key]
            val newValue = after[key]
            when {
                !oldPresent && newPresent -> key to YMapChange(YMapChangeAction.Add, null, newValue?.let(::valueToAny))
                oldPresent && !newPresent -> key to YMapChange(YMapChangeAction.Delete, oldValue?.let(::valueToAny), null)
                oldPresent && newPresent && (oldValue != newValue || key in changedKeys) -> key to YMapChange(
                    YMapChangeAction.Update,
                    oldValue?.let(::valueToAny),
                    newValue?.let(::valueToAny),
                )
                else -> null
            }
        }.toMap()
    }

    private fun diffArrayDelta(before: List<StoreItem>, after: List<StoreItem>): List<YArrayDeltaOp> {
        return diffSequence(before, after) { items ->
            listOf(YArrayDeltaOp(insert = items.map { valueToAny((it.content as ItemContent.Value).value) }))
        }
    }

    private fun diffXmlDelta(before: List<StoreItem>, after: List<StoreItem>): List<YArrayDeltaOp> {
        return diffSequence(before, after) { items ->
            listOf(YArrayDeltaOp(insert = items.map { (it.content as ItemContent.XmlNode).value.toEventJson() }))
        }
    }

    private fun diffTextDelta(before: List<StoreItem>, after: List<StoreItem>): YTextDelta {
        diffTextFormattingDelta(before, after)?.let { return it }
        val delta = YTextDelta()
        val (prefix, deleteCount, inserted) = diffSequenceParts(before, after)
        if (prefix > 0) delta.retain(prefix)
        if (deleteCount > 0) delta.delete(deleteCount)
        var pending = StringBuilder()
        var pendingAttrs: Map<String, Any?>? = null
        fun flush() {
            if (pending.isNotEmpty()) {
                delta.insert(pending.toString(), pendingAttrs.orEmpty())
                pending = StringBuilder()
            }
        }
        inserted.forEach { item ->
            val attrs = textAttributesToPublic(item.content.textAttributesOrEmpty())
            if (pendingAttrs != null && pendingAttrs != attrs) flush()
            pendingAttrs = attrs
            when (val content = item.content) {
                is ItemContent.Text -> pending.append(content.value)
                is ItemContent.TextEmbed -> {
                    flush()
                    delta.insertEmbed(valueToAny(content.value), attrs)
                    pendingAttrs = null
                }
                else -> Unit
            }
        }
        flush()
        return delta
    }

    private fun diffTextFormattingDelta(before: List<StoreItem>, after: List<StoreItem>): YTextDelta? {
        if (before.size != after.size) return null
        if (before.indices.any { index -> !before[index].content.sameTextContentAs(after[index].content) }) return null
        val segments = mutableListOf<Pair<Int, Map<String, Any?>>>()
        before.indices.forEach { index ->
            val attrs = textAttributeDiff(
                before[index].content.textAttributesOrEmpty(),
                after[index].content.textAttributesOrEmpty(),
            )
            val last = segments.lastOrNull()
            if (last != null && last.second == attrs) {
                segments[segments.lastIndex] = last.first + 1 to attrs
            } else {
                segments.add(1 to attrs)
            }
        }
        while (segments.lastOrNull()?.second?.isEmpty() == true) {
            segments.removeAt(segments.lastIndex)
        }
        val delta = YTextDelta()
        segments.forEach { (length, attrs) -> delta.retain(length, attrs) }
        return delta
    }

    private fun textAttributeDiff(before: Map<String, YValue>, after: Map<String, YValue>): Map<String, Any?> {
        return (before.keys + after.keys).sorted().mapNotNull { key ->
            val beforeValue = before[key]
            val afterValue = after[key]
            if (beforeValue == afterValue) {
                null
            } else {
                key to afterValue?.let(::valueToAny)
            }
        }.toMap()
    }

    private fun diffSequence(
        before: List<StoreItem>,
        after: List<StoreItem>,
        insertOps: (List<StoreItem>) -> List<YArrayDeltaOp>,
    ): List<YArrayDeltaOp> {
        val (prefix, deleteCount, inserted) = diffSequenceParts(before, after)
        val delta = mutableListOf<YArrayDeltaOp>()
        if (prefix > 0) delta.add(YArrayDeltaOp(retain = prefix))
        if (deleteCount > 0) delta.add(YArrayDeltaOp(delete = deleteCount))
        if (inserted.isNotEmpty()) delta.addAll(insertOps(inserted))
        return delta
    }

    private fun diffSequenceParts(before: List<StoreItem>, after: List<StoreItem>): Triple<Int, Int, List<StoreItem>> {
        var prefix = 0
        while (prefix < before.size && prefix < after.size && before[prefix].id == after[prefix].id) {
            prefix++
        }
        var suffix = 0
        while (
            suffix < before.size - prefix &&
            suffix < after.size - prefix &&
            before[before.lastIndex - suffix].id == after[after.lastIndex - suffix].id
        ) {
            suffix++
        }
        val deleteCount = before.size - prefix - suffix
        val inserted = after.subList(prefix, after.size - suffix)
        return Triple(prefix, deleteCount, inserted)
    }

    private fun <T : AbstractYType> getOrCreate(name: String, kind: RootKind, factory: () -> T): T {
        val existing = rootTypes[name]
        if (existing != null) {
            require(existing.kind == kind) { "root type '$name' already exists as ${existing.kind}" }
            @Suppress("UNCHECKED_CAST")
            return existing as T
        }
        require(name !in nestedNames) { "nested type '$name' cannot be opened as a root type" }
        return factory().also { rootTypes[name] = it }
    }

    private fun <T : AbstractYType> createNestedType(kind: RootKind, factory: (String) -> T): T {
        val name = nextNestedTypeName()
        return factory(name).also { type ->
            check(type.kind == kind) { "nested type factory returned ${type.kind}, expected $kind" }
            nestedTypes[name] = type
            nestedNames.add(name)
        }
    }

    private fun nextNestedTypeName(): String {
        var candidate: String
        do {
            candidate = "__yks_nested__:$clientId:${nestedTypeCounter++}"
        } while (candidate in rootTypes || candidate in nestedTypes)
        return candidate
    }

    internal fun typeForParent(parent: String): AbstractYType? {
        rootTypes[parent]?.let { return it }
        nestedTypes[parent]?.let { return it }
        if (parent !in nestedNames) return null
        val kind = store.allItems().firstOrNull { it.parent == parent && it.parentSub == null }?.content?.kind
            ?: store.allItems().firstOrNull { it.parent == parent }?.content?.kind
            ?: return null
        return typeFromRef(YValue.TypeRef(kind, parent))
    }

    private fun materializedTypes(): List<AbstractYType> {
        return (rootTypes.values + nestedTypes.values).distinctBy { it.name }
    }

    private fun directNestedChildren(
        parent: String,
        renderer: AbstractRenderer = baseRenderer,
    ): List<Pair<List<Any>, String>> {
        val children = mutableListOf<Pair<List<Any>, String>>()
        visibleMap(parent).forEach { (key, value) ->
            value.nestedTypeRefPaths().forEach { (segments, nestedName) ->
                children.add(listOf(key) + segments to nestedName)
            }
        }
        var renderedIndex = 0
        sequence(parent).forEach { item ->
            val itemLength = rendererContentLength(renderer, item.toItemStruct(this)).toInt()
            if (!item.deleted || itemLength > 0) {
                val value = when (val content = item.content) {
                    is ItemContent.Value -> content.value
                    is ItemContent.TextEmbed -> content.value
                    else -> null
                }
                value?.nestedTypeRefPaths().orEmpty().forEach { (segments, nestedName) ->
                    children.add(listOf(renderedIndex) + segments to nestedName)
                }
            }
            renderedIndex += itemLength
        }
        return children
    }

    private fun YValue.nestedTypeRefPaths(): List<Pair<List<Any>, String>> = when (this) {
        is YValue.TypeRef -> listOf(emptyList<Any>() to name)
        is YValue.ListValue -> value.flatMapIndexed { index, nested ->
            nested.nestedTypeRefPaths().map { (segments, nestedName) ->
                listOf(index) + segments to nestedName
            }
        }
        is YValue.MapValue -> value.flatMap { (key, nested) ->
            nested.nestedTypeRefPaths().map { (segments, nestedName) ->
                listOf(key) + segments to nestedName
            }
        }
        else -> emptyList()
    }

    private fun typeFromRef(ref: YValue.TypeRef): AbstractYType {
        nestedNames.add(ref.name)
        nestedTypes[ref.name]?.let { existing ->
            require(existing.kind == ref.kind) { "nested type '${ref.name}' exists as ${existing.kind}, not ${ref.kind}" }
            return existing
        }
        rootTypes[ref.name]?.let { existing ->
            require(existing.kind == ref.kind) { "root type '${ref.name}' exists as ${existing.kind}, not ${ref.kind}" }
            return existing
        }
        return when (ref.kind) {
            RootKind.Array -> YArray(this, ref.name)
            RootKind.Map -> YMap(this, ref.name)
            RootKind.Text -> YText(this, ref.name)
            RootKind.XmlFragment -> YXmlFragment(this, ref.name)
            RootKind.XmlElement -> YXmlElementType(this, ref.name)
            RootKind.XmlHook -> YXmlElementType(this, ref.name, RootKind.XmlHook)
            RootKind.XmlText -> YXmlTextType(this)
        }.also { nestedTypes[ref.name] = it }
    }

    private fun rememberNestedRefs(content: ItemContent) {
        when (content) {
            is ItemContent.Value -> rememberNestedRefs(content.value)
            is ItemContent.MapEntry -> rememberNestedRefs(content.value)
            is ItemContent.TextEmbed -> rememberNestedRefs(content.value)
            is ItemContent.Text,
            is ItemContent.TextFormat,
            is ItemContent.XmlNode,
            is ItemContent.Deleted -> Unit
        }
    }

    private fun rememberNestedRefs(value: YValue) {
        when (value) {
            is YValue.TypeRef -> typeFromRef(value)
            is YValue.SubdocRef -> subdocFromRef(value)
            is YValue.ListValue -> value.value.forEach(::rememberNestedRefs)
            is YValue.MapValue -> value.value.values.forEach(::rememberNestedRefs)
            else -> Unit
        }
    }

    private fun registerNestedTypeValue(value: AbstractYType): AbstractYType {
        val local = if (value.doc === this) value else value.cloneValueInto(this) as AbstractYType
        require(rootTypes[local.name] == null) { "root shared types cannot be inserted as nested content" }
        nestedTypes[local.name] = local
        nestedNames.add(local.name)
        return local
    }

    private fun registerSubdocValue(value: YDoc): YValue.SubdocRef {
        require(value !== this) { "a document cannot contain itself as a subdoc" }
        subdocsByInstanceId[value.subdocInstanceId] = value
        value.parentDocs.add(this)
        return YValue.SubdocRef(
            guid = value.guid,
            shouldLoad = value.shouldLoad,
            autoLoad = value.autoLoad,
            instanceId = value.subdocInstanceId,
            collectionId = value.collectionId,
            meta = YValue.from(value.meta),
            isSuggestionDoc = value.isSuggestionDoc,
        )
    }

    private fun subdocFromRef(ref: YValue.SubdocRef, shouldLoadOverride: Boolean? = null): YDoc {
        val existing = subdocsByInstanceId[ref.instanceId]
        if (existing != null && !existing.isDestroyed) {
            existing.parentDocs.add(this)
            return existing
        }
        if (existing != null) {
            subdocsByInstanceId.remove(ref.instanceId)
        }
        val shouldLoad = shouldLoadOverride ?: if (existing?.isDestroyed == true) false else ref.shouldLoad
        return YDoc(
            guid = ref.guid,
            collectionId = ref.collectionId,
            meta = ref.meta.toAny(),
            shouldLoad = shouldLoad,
            autoLoad = ref.autoLoad,
            isSuggestionDoc = ref.isSuggestionDoc,
        ).also { subdoc ->
            subdocsByInstanceId[ref.instanceId] = subdoc
            subdoc.parentDocs.add(this)
        }
    }

    private fun handleSubdocDestroyed(subdoc: YDoc) {
        transact {
            val active = currentTransaction ?: error("transaction is not active")
            val refInstanceIds = subdocsByInstanceId
                .filterValues { it === subdoc }
                .keys
                .toList()
            refInstanceIds.forEach(subdocsByInstanceId::remove)
            val replacements = visibleSubdocRefs()
                .filter { it.instanceId in refInstanceIds }
                .map { ref -> subdocFromRef(ref, shouldLoadOverride = false) }
            active.addedSubdocs.addAll(replacements)
            active.removedSubdocs.add(subdoc)
        }
    }

    private fun collectSubdocEvent(transaction: Transaction): YSubdocEvent? {
        val added = transaction.addedItems.flatMap { subdocRefs(it.content) }.map(::subdocFromRef) +
            transaction.addedSubdocs
        val removed = transaction.deletedItems.flatMap { subdocRefs(it.content) }.map(::subdocFromRef) +
            transaction.removedSubdocs
        added.forEach { subdoc ->
            subdoc.clientID = clientID
            if (subdoc.collectionid == null) {
                subdoc.collectionid = collectionid
            }
        }
        val loaded = (added.filter { it.shouldLoad } + transaction.loadedSubdocs)
            .distinctBy { it.subdocInstanceId }
        if (added.isEmpty() && removed.isEmpty() && loaded.isEmpty()) return null
        return YSubdocEvent(added = added, removed = removed, loaded = loaded)
    }

    private fun visibleSubdocRefs(): List<YValue.SubdocRef> {
        return store.allItems()
            .filter { !it.deleted }
            .flatMap { subdocRefs(it.content) }
    }

    private fun subdocRefs(content: ItemContent): List<YValue.SubdocRef> = when (content) {
        is ItemContent.Value -> subdocRefs(content.value)
        is ItemContent.MapEntry -> subdocRefs(content.value)
        is ItemContent.TextEmbed -> subdocRefs(content.value)
        is ItemContent.Text,
        is ItemContent.TextFormat,
        is ItemContent.XmlNode,
        is ItemContent.Deleted -> emptyList()
    }

    private fun subdocRefs(value: YValue): List<YValue.SubdocRef> = when (value) {
        is YValue.SubdocRef -> listOf(value)
        is YValue.ListValue -> value.value.flatMap(::subdocRefs)
        is YValue.MapValue -> value.value.values.flatMap(::subdocRefs)
        else -> emptyList()
    }

    private fun emitSubdocEvent(event: YSubdocEvent, transaction: YTransactionEvent? = null) {
        val callbacks = mutableListOf<() -> Unit>()
        subdocObservers.toList().forEach { listener ->
            callbacks.add { listener(event) }
        }
        subdocEventListeners.toList().forEach { listener ->
            callbacks.add { listener(event, this, transaction) }
        }
        callbacks.addAll(docEventCallbacks("subdocs", YDocEvent(name = "subdocs", transaction = transaction, subdocs = event)))
        callAllYksCallbacks(callbacks)
    }

    private fun emitDocEvent(event: YDocEvent) {
        val callbacks = mutableListOf<() -> Unit>()
        when (event.name) {
            "load",
            "destroy" -> docOnlyEventListeners[event.name].orEmpty().toList().forEach { listener ->
                callbacks.add { listener(this) }
            }
            "sync" -> syncEventListeners.toList().forEach { listener ->
                callbacks.add { listener(event.synced ?: true, this) }
            }
        }
        callbacks.addAll(docEventCallbacks(event.name, event))
        callAllYksCallbacks(callbacks)
    }

    private fun docEventCallbacks(eventName: String, event: YDocEvent): List<() -> Unit> {
        return eventListeners[eventName].orEmpty().toList().map { listener -> { listener(event) } }
    }

    private fun transactionEventCallbacks(eventName: String, event: YTransactionEvent): List<() -> Unit> {
        return transactionEventListeners[eventName].orEmpty().toList().map { listener -> { listener(event, this) } }
    }

    private fun restorePositions(items: Iterable<RestoreItem>): Map<Id, Int> {
        val positions = mutableMapOf<Id, Int>()
        items.map { it.item.parent }.distinct().forEach { parent ->
            sequence(parent).forEachIndexed { index, item -> positions[item.id] = index }
        }
        return positions
    }

    private fun List<RestoreItem>.sortedForRestore(positions: Map<Id, Int> = restorePositions(this)): List<RestoreItem> {
        return sortedWith(
            compareBy<RestoreItem> { it.item.parent }
                .thenBy { it.item.parentSub ?: "" }
                .thenBy { positions[it.item.id] ?: Int.MAX_VALUE }
                .thenBy { it.item.id.client }
                .thenBy { it.item.id.clock },
        )
    }

    private fun rememberRedoneRangeEnds(pairs: List<Pair<StoreItem, StoreItem>>, originalPositions: Map<Id, Int>) {
        pairs.groupBy { (source, _) -> source.parent to source.parentSub }.values.forEach { group ->
            val parentSub = group.first().first.parentSub
            if (parentSub != null) {
                group.forEach { (source, restored) -> redoneRangeEndByOriginal[source.id] = restored.id }
                return@forEach
            }

            val sorted = group.sortedWith(
                compareBy<Pair<StoreItem, StoreItem>> { (source, _) -> originalPositions[source.id] ?: Int.MAX_VALUE }
                    .thenBy { (source, _) -> source.id.client }
                    .thenBy { (source, _) -> source.id.clock },
            )
            val block = mutableListOf<Pair<StoreItem, StoreItem>>()
            var previousPosition: Int? = null

            fun flushBlock() {
                if (block.isEmpty()) return
                val end = block.last().second.id
                block.forEach { (source, _) -> redoneRangeEndByOriginal[source.id] = end }
                block.clear()
            }

            sorted.forEach { pair ->
                val position = originalPositions[pair.first.id]
                val contiguous = position != null && previousPosition != null && position == previousPosition!! + 1
                if (block.isNotEmpty() && !contiguous) {
                    flushBlock()
                }
                block.add(pair)
                previousPosition = position
            }
            flushBlock()
        }
    }

    private fun inferRightOrigin(source: StoreItem): Id? {
        source.rightOrigin?.let { return it }
        if (source.parentSub != null) return null
        val sequence = sequence(source.parent)
        val index = sequence.indexOfFirst { it.id == source.id }
        if (index < 0) return null
        return sequence.drop(index + 1).firstOrNull { it.parentSub == null }?.id
    }

    private fun StoreItem.isVisibleIn(snapshot: Snapshot): Boolean =
        id.clock < (snapshot.sv[id.client] ?: 0) && !snapshot.ds.hasId(id)

    internal class Transaction(val origin: Any?, val local: Boolean, val beforeState: StateVector) {
        val addedItems = mutableListOf<StoreItem>()
        val deletedItems = mutableListOf<StoreItem>()
        val deleteSet = DeleteSet.empty()
        val cleanUps = createIdSet()
        val addedSubdocs = linkedSetOf<YDoc>()
        val removedSubdocs = linkedSetOf<YDoc>()
        val loadedSubdocs = linkedSetOf<YDoc>()
        val meta: MutableMap<Any?, Any?> = linkedMapOf()
        val changedParents = linkedSetOf<String>()
        val beforeParents = linkedMapOf<String, ParentSnapshot>()
        var afterState: StateVector = beforeState
        var update: ByteArray = ByteArray(0)
        val isEmpty: Boolean
            get() = addedItems.isEmpty() &&
                deleteSet.isEmpty &&
                addedSubdocs.isEmpty() &&
                removedSubdocs.isEmpty() &&
                loadedSubdocs.isEmpty() &&
                changedParents.isEmpty()
    }

    companion object {
        private val random = SecureRandom()

        fun generateNewClientId(): Long = randomClientId()

        private fun randomClientId(excluding: Set<Long> = emptySet()): Long {
            var value: Long
            do {
                value = random.nextLong() and Long.MAX_VALUE
                if (value == 0L) value = 1
            } while (value in excluding)
            return value
        }

        private fun randomGuid(): String = UUID.randomUUID().toString()
    }
}

class Subscription internal constructor(private val unsubscribeAction: () -> Unit) : AutoCloseable {
    override fun close() {
        unsubscribeAction()
    }
}

class YTransaction internal constructor(
    val doc: YDoc,
    private val transaction: YDoc.Transaction,
) {
    val origin: Any? get() = transaction.origin
    val local: Boolean get() = transaction.local
    val beforeState: StateVector get() = transaction.beforeState
    val afterState: StateVector get() = transaction.afterState
    val insertSet: IdSet get() = transaction.addedItems.toIdSet()
    val deleteSet: DeleteSet get() = transaction.deleteSet
    val cleanUps: IdSet get() = transaction.cleanUps
    val meta: MutableMap<Any?, Any?> get() = transaction.meta
    val changedParents: Set<String> get() = doc.changedParentsFor(transaction)
    val changedTypes: Set<AbstractYType> get() = changedParents.mapNotNull { doc.typeForParent(it) }.toSet()
    val addedItemCount: Int get() = transaction.addedItems.size
    val deletedItemCount: Int get() = transaction.deletedItems.size
    val update: ByteArray get() = transaction.update

    fun adds(id: Id): Boolean = insertSet.hasId(id)

    fun adds(client: Long, clock: Long): Boolean = insertSet.has(client, clock)

    fun deletes(id: Id): Boolean = deleteSet.contains(id)

    fun deletes(client: Long, clock: Long): Boolean = deletes(Id(client, clock))

    internal fun addChangedType(type: AbstractYType) {
        require(type.doc === doc) { "type must belong to this transaction's document" }
        val typeItemId = doc.typeRefItemId(type)
        if (typeItemId == null || transaction.addedItems.none { item -> item.id == typeItemId }) {
            transaction.changedParents.add(type.name)
        }
    }
}

data class YEvent(
    val target: AbstractYType,
    val origin: Any?,
    val update: ByteArray,
    val insertSet: IdSet = createIdSet(),
    val deleteSet: DeleteSet = DeleteSet.empty(),
    val transaction: YTransactionEvent? = null,
    val currentTarget: AbstractYType = target,
    val childListChanged: Boolean = false,
    val keysChanged: Set<String> = emptySet(),
    val mapChanges: Map<String, YMapChange> = emptyMap(),
    val mapDelta: YMapDelta = YMapDelta(),
    val name: String? = null,
    val value: Any? = null,
    val arrayDelta: List<YArrayDeltaOp> = emptyList(),
    val textDelta: YTextDelta = YTextDelta(),
    val path: List<Any> = emptyList(),
    val changedTarget: AbstractYType = target,
    val deepEvents: List<YEvent> = emptyList(),
) {
    @get:kotlin.jvm.JvmName("getDeltaValue")
    val delta: Any
        get() = when (target.kind) {
            RootKind.Array,
            RootKind.XmlFragment -> arrayDelta
            RootKind.Map -> mapDelta
            RootKind.Text -> textDelta
            RootKind.XmlElement,
            RootKind.XmlHook,
            RootKind.XmlText -> emptyList<Any?>()
        }

    val deltaDeep: Any
        get() = getDelta(deep = true)

    fun getDelta(deep: Boolean = false, renderer: AbstractRenderer = target.activeRenderer): Any {
        if (!deep) return delta
        val itemsToRender = eventItemsToRender(renderer)
        if (!itemsToRender.isEmpty()) {
            val options = DeepDeltaRenderOptions(
                renderer = renderer,
                itemsToRender = itemsToRender,
                retainDeletes = true,
                insertedItems = insertSet,
            )
            when (target.kind) {
                RootKind.Array -> return (target.renderDeepDelta(options) as YArrayDeepDelta).delta
                RootKind.Text -> return (target.renderDeepDelta(options) as YTextDeepDelta).delta
                RootKind.XmlFragment -> return (target.renderDeepDelta(options) as YXmlFragmentDeepDelta).delta
                RootKind.Map,
                RootKind.XmlElement,
                RootKind.XmlHook,
                RootKind.XmlText -> Unit
            }
        }
        val options = DeepDeltaRenderOptions(renderer = renderer)
        return when (target.kind) {
            RootKind.Array,
            RootKind.XmlFragment -> arrayDelta.toDeepDeltaValues(options)
            RootKind.Map -> mapDelta.toDeepDeltaValues(options)
            RootKind.Text -> textDelta.toDeepDeltaValues(options)
            RootKind.XmlElement,
            RootKind.XmlHook,
            RootKind.XmlText -> emptyList<Any?>()
        }
    }

    private fun eventItemsToRender(renderer: AbstractRenderer): IdSet {
        val deleteIdSet = deleteSet.toIdSet()
        val both = intersectSets(insertSet, deleteIdSet)
        val sets = mutableListOf(
            diffIdSet(insertSet, deleteIdSet),
            diffIdSet(deleteIdSet, insertSet),
        )
        if (!both.isEmpty()) {
            sets.add(intersectSets(both, renderer.attributed))
        }
        return mergeIdSets(sets)
    }

    fun adds(id: Id): Boolean = insertSet.hasId(id)

    fun adds(client: Long, clock: Long): Boolean = insertSet.has(client, clock)

    fun adds(struct: AbstractStruct): Boolean = adds(struct.id)

    fun adds(struct: DecodedUpdateStruct): Boolean = adds(struct.id)

    fun deletes(id: Id): Boolean = deleteSet.contains(id)

    fun deletes(client: Long, clock: Long): Boolean = deletes(Id(client, clock))

    fun deletes(struct: AbstractStruct): Boolean = deletes(struct.id)

    fun deletes(struct: DecodedUpdateStruct): Boolean = deletes(struct.id)
}

enum class YMapChangeAction {
    Add,
    Update,
    Delete,
}

data class YMapChange(
    val action: YMapChangeAction,
    val oldValue: Any?,
    val newValue: Any?,
)

private fun Map<String, YMapChange>.toMapDelta(): YMapDelta {
    val delta = YMapDelta()
    toSortedMap().forEach { (key, change) ->
        when (change.action) {
            YMapChangeAction.Add,
            YMapChangeAction.Update -> delta.setAttr(key, change.newValue, change.oldValue)
            YMapChangeAction.Delete -> delta.deleteAttr(key, change.oldValue)
        }
    }
    return delta
}

internal fun ItemContent.directTypeRef(): YValue.TypeRef? = when (this) {
    is ItemContent.Value -> value as? YValue.TypeRef
    is ItemContent.MapEntry -> value as? YValue.TypeRef
    is ItemContent.Text,
    is ItemContent.TextEmbed,
    is ItemContent.TextFormat,
    is ItemContent.XmlNode,
    is ItemContent.Deleted -> null
}

private val textFormatApplicationOrder: Comparator<StoreItem> =
    compareByDescending<StoreItem> { it.id.client }.thenBy { it.id.clock }

private fun StoreItem.withBaseTextAttributes(): StoreItem =
    copy(content = content.withTextAttributesOrNull(content.baseTextAttributesOrEmpty()) ?: content)

private fun ItemContent.textAttributesOrEmpty(): Map<String, YValue> = when (this) {
    is ItemContent.Text -> attributes
    is ItemContent.TextEmbed -> attributes
    else -> emptyMap()
}

private fun ItemContent.baseTextAttributesOrEmpty(): Map<String, YValue> = when (this) {
    is ItemContent.Text -> baseAttributes
    is ItemContent.TextEmbed -> baseAttributes
    else -> emptyMap()
}

private fun ItemContent.withTextAttributesOrNull(attributes: Map<String, YValue>): ItemContent? = when (this) {
    is ItemContent.Text -> copy(attributes = attributes)
    is ItemContent.TextEmbed -> copy(attributes = attributes)
    else -> null
}

private fun ItemContent.isTextCountable(): Boolean =
    this is ItemContent.Text || this is ItemContent.TextEmbed

private fun MutableList<StoreItem>.replaceTextAttributes(index: Int, attributes: Map<String, YValue>) {
    if (attributes.isEmpty()) return
    val item = this[index]
    val nextAttributes = item.content.textAttributesOrEmpty().toMutableMap()
    attributes.forEach { (key, value) ->
        if (value == YValue.Null) {
            nextAttributes.remove(key)
        } else {
            nextAttributes[key] = value
        }
    }
    val nextContent = item.content.withTextAttributesOrNull(nextAttributes.toSortedMap()) ?: return
    this[index] = item.copy(content = nextContent)
}

private fun ItemContent.sameTextContentAs(other: ItemContent): Boolean = when {
    this is ItemContent.Text && other is ItemContent.Text -> value == other.value
    this is ItemContent.TextEmbed && other is ItemContent.TextEmbed -> value == other.value
    else -> false
}

internal fun callAllYksCallbacks(callbacks: Iterable<() -> Unit>) {
    var firstError: Throwable? = null
    callbacks.forEach { callback ->
        try {
            callback()
        } catch (error: Throwable) {
            if (firstError == null) {
                firstError = error
            } else {
                firstError.addSuppressed(error)
            }
        }
    }
    firstError?.let { throw it }
}

data class YArrayDeltaOp(
    val retain: Int? = null,
    val insert: List<Any?>? = null,
    val delete: Int? = null,
) {
    init {
        val active = listOf(retain != null, insert != null, delete != null).count { it }
        require(active == 1) { "exactly one array delta operation must be set" }
        require(retain == null || retain > 0) { "retain must be positive" }
        require(delete == null || delete > 0) { "delete must be positive" }
        require(insert == null || insert.isNotEmpty()) { "insert must not be empty" }
    }
}

class YTransactionEvent internal constructor(
    val doc: YDoc,
    val origin: Any?,
    val local: Boolean,
    val update: ByteArray,
    val beforeState: StateVector,
    val afterState: StateVector,
    val insertSet: IdSet,
    val deleteSet: DeleteSet,
    val deleteIdSet: IdSet = deleteSet.toIdSet(),
    val cleanUps: IdSet = createIdSet(),
    val meta: MutableMap<Any?, Any?> = linkedMapOf(),
    val addedStructs: List<ItemStruct> = emptyList(),
    val deletedStructs: List<ItemStruct> = emptyList(),
    internal val addedItems: List<StoreItem>,
    internal val deletedItems: List<StoreItem>,
    val changedParents: Set<String>,
    val changedTypes: Set<AbstractYType> = emptySet(),
    val subdocsAdded: Set<YDoc> = emptySet(),
    val subdocsRemoved: Set<YDoc> = emptySet(),
    val subdocsLoaded: Set<YDoc> = emptySet(),
) {
    val changedParentTypes: Set<AbstractYType> get() = changedTypes

    val addedItemCount: Int get() = addedItems.size
    val deletedItemCount: Int get() = deletedItems.size

    fun adds(id: Id): Boolean = insertSet.hasId(id)

    fun adds(client: Long, clock: Long): Boolean = insertSet.has(client, clock)

    fun adds(struct: AbstractStruct): Boolean = adds(struct.id)

    fun deletes(id: Id): Boolean = deleteSet.contains(id)

    fun deletes(client: Long, clock: Long): Boolean = deletes(Id(client, clock))

    fun deletes(struct: AbstractStruct): Boolean = deletes(struct.id)

    internal fun subdocEvent(): YSubdocEvent? {
        if (subdocsAdded.isEmpty() && subdocsRemoved.isEmpty() && subdocsLoaded.isEmpty()) return null
        return YSubdocEvent(
            added = subdocsAdded.toList(),
            removed = subdocsRemoved.toList(),
            loaded = subdocsLoaded.toList(),
        )
    }
}

typealias Transaction = YTransactionEvent

private fun List<StoreItem>.toIdSet(): IdSet {
    val idSet = createIdSet()
    forEach { item -> idSet.add(item.id, item.length) }
    return idSet
}

data class YSubdocEvent(
    val added: List<YDoc> = emptyList(),
    val removed: List<YDoc> = emptyList(),
    val loaded: List<YDoc> = emptyList(),
)

data class YDocEvent(
    val name: String,
    val update: ByteArray = ByteArray(0),
    val origin: Any? = null,
    val transaction: YTransactionEvent? = null,
    val transactions: List<YTransactionEvent> = emptyList(),
    val subdocs: YSubdocEvent? = null,
    val synced: Boolean? = null,
)

    internal data class RestoreItem(
    val item: StoreItem,
    val anchorAfterOriginal: Boolean,
)

internal sealed class ParentSnapshot {
    data class SequenceSnapshot(val kind: RootKind, val items: List<StoreItem>) : ParentSnapshot()
    data class MapSnapshot(val values: Map<String, YValue>) : ParentSnapshot()
    data class CombinedSnapshot(
        val sequence: SequenceSnapshot? = null,
        val map: MapSnapshot? = null,
    ) : ParentSnapshot()
}

private fun ParentSnapshot.merge(other: ParentSnapshot): ParentSnapshot {
    val sequence = sequenceSnapshot() ?: other.sequenceSnapshot()
    val map = mapSnapshot() ?: other.mapSnapshot()
    return when {
        sequence != null && map != null -> ParentSnapshot.CombinedSnapshot(sequence, map)
        sequence != null -> sequence
        map != null -> map
        else -> this
    }
}

private fun ParentSnapshot.sequenceSnapshot(): ParentSnapshot.SequenceSnapshot? = when (this) {
    is ParentSnapshot.SequenceSnapshot -> this
    is ParentSnapshot.MapSnapshot -> null
    is ParentSnapshot.CombinedSnapshot -> sequence
}

private fun ParentSnapshot.mapSnapshot(): ParentSnapshot.MapSnapshot? = when (this) {
    is ParentSnapshot.SequenceSnapshot -> null
    is ParentSnapshot.MapSnapshot -> this
    is ParentSnapshot.CombinedSnapshot -> map
}
