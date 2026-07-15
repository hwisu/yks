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
    private val unopenedRootEntries = linkedMapOf<String, YUnopenedRoot>()
    private val shareView: Map<String, AbstractYType> = object : AbstractMap<String, AbstractYType>() {
        override val entries: Set<Map.Entry<String, AbstractYType>>
            get() = rootNames().mapTo(linkedSetOf()) { name ->
                java.util.AbstractMap.SimpleImmutableEntry(name, sharedRootEntry(name)!!)
            }

        override fun get(key: String): AbstractYType? = sharedRootEntry(key)

        override fun containsKey(key: String): Boolean = key in rootNames()
    }
    private val nestedTypes = linkedMapOf<String, AbstractYType>()
    private val nestedNames = linkedSetOf<String>()
    private val referencedNestedNames = linkedSetOf<String>()
    private val pendingNestedReferenceStack = mutableListOf<MutableSet<String>>()
    private val pendingStoreParentStack = mutableListOf<String?>()
    private val pendingPreliminaryAttachments = linkedMapOf<String, AbstractYType>()
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
    /**
     * Mirrors AbstractType._map's insertion-ordered JavaScript Map in upstream Yjs.
     *
     * Updating or deleting a key does not move it. A key only gets an order slot the first
     * time an item for that parent/key is integrated into this document. In particular, the
     * order can legitimately depend on remote update delivery order, just as it does in Yjs.
     */
    private val mapKeyOrders = linkedMapOf<String, LinkedHashSet<String>>()
    private val beforeAllTransactionListeners = mutableListOf<() -> Unit>()
    private val beforeTransactionListeners = mutableListOf<(YTransactionEvent) -> Unit>()
    private val beforeObserverCallsListeners = mutableListOf<(YTransactionEvent) -> Unit>()
    private val afterTransactionListeners = mutableListOf<(YTransactionEvent) -> Unit>()
    private val afterTransactionCleanupListeners = mutableListOf<(YTransactionEvent) -> Unit>()
    private val afterAllTransactionsListeners = mutableListOf<(List<YTransactionEvent>) -> Unit>()
    private val updateListeners = mutableListOf<(ByteArray, Any?) -> Unit>()
    private val updateEventListeners = mutableListOf<(ByteArray, Any?, YDoc, YTransactionEvent?) -> Unit>()
    private val updateV2EventListeners = mutableListOf<(ByteArray, Any?, YDoc, YTransactionEvent?) -> Unit>()
    private val updateLosslessEventListeners = mutableListOf<(ByteArray, Any?, YDoc, YTransactionEvent?) -> Unit>()
    private val updateV2LosslessEventListeners = mutableListOf<(ByteArray, Any?, YDoc, YTransactionEvent?) -> Unit>()
    private val transactionListeners = mutableListOf<(YTransactionEvent) -> Unit>()
    private var currentTransaction: Transaction? = null
    private var isEmittingTransactions = false
    private var isEmittingTransactionEvent = false
    private val pendingTransactionEmits = ArrayDeque<Transaction>()
    private var nestedTypeCounter = 0L
    internal val subdocInstanceId: String = randomGuid()

    val share: Map<String, AbstractYType>
        get() = shareView

    @get:JvmName("getSubdocsProperty")
    val subdocs: Set<YDoc>
        get() = getSubdocs()

    operator fun get(name: String): AbstractYType =
        rootType(name) ?: unopenedRoot(name) ?: createUnopenedRoot(name)

    fun get(name: String, kind: RootKind): AbstractYType = when (kind) {
        RootKind.Array -> getArray(name)
        RootKind.Map -> getMap(name)
        RootKind.Text -> getText(name)
        RootKind.XmlFragment -> getXmlFragment(name)
        RootKind.XmlElement -> getXmlElement(name)
        RootKind.XmlHook,
        RootKind.XmlText -> error("XML node type refs cannot be document roots")
    }

    fun get(name: String, typeRef: Int): AbstractYType = get(name, rootKindFromTypeRefId(typeRef))

    fun getOrNull(name: String): AbstractYType? = rootType(name) ?: unopenedRoot(name)

    fun get(): YArray = getArray("")

    fun getArray(name: String = ""): YArray = getOrCreate(name, RootKind.Array) { YArray(this, name) }

    fun getMap(name: String = ""): YMap = getOrCreate(name, RootKind.Map) { YMap(this, name) }

    fun getText(name: String = ""): YText = getOrCreate(name, RootKind.Text) { YText(this, name) }

    fun getXmlFragment(name: String = ""): YXmlFragment =
        getOrCreate(name, RootKind.XmlFragment) { YXmlFragment(this, name) }

    fun getXmlElement(name: String = "", nodeName: String = "UNDEFINED"): YXmlElementType =
        getOrCreate(name, RootKind.XmlElement) { YXmlElementType(this, name, nodeName) }

    fun createArray(): YArray = createNestedType(RootKind.Array) { nestedName -> YArray(this, nestedName) }

    fun createMap(): YMap = createNestedType(RootKind.Map) { nestedName -> YMap(this, nestedName) }

    fun createText(): YText = createNestedType(RootKind.Text) { nestedName -> YText(this, nestedName) }

    fun createXmlFragment(): YXmlFragment =
        createNestedType(RootKind.XmlFragment) { nestedName -> YXmlFragment(this, nestedName) }

    fun createXmlElement(nodeName: String): YXmlElementType =
        createXmlElementType(nodeName, RootKind.XmlElement)

    fun createXmlHook(hookName: String): YXmlHook =
        createNestedType(RootKind.XmlHook) { nestedName -> YXmlHook(this, nestedName, hookName) }

    fun createXmlText(): YXmlTextType =
        createXmlTextType()

    internal fun createXmlElementType(nodeName: String, kind: RootKind): YXmlElementType =
        createNestedType(kind) { nestedName -> YXmlElementType(this, nestedName, nodeName, kind) }

    internal fun createXmlTextType(): YXmlTextType =
        createNestedType(RootKind.XmlText) { nestedName -> YXmlTextType(this, nestedName) }

    fun toJson(): Map<String, Any?> {
        return rootTypes
            .mapValues { (_, type) -> type.toJson() }
            .inJavaScriptObjectKeyOrder()
    }

    fun toJSON(): Map<String, Any?> {
        return rootTypes
            .mapValues { (_, type) -> type.toJSON() }
            .inJavaScriptObjectKeyOrder()
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
        val sharedTypes = (rootTypes.values + unopenedRootEntries.values).distinctBy { it.name }
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
        updateLosslessEventListeners.clear()
        updateV2LosslessEventListeners.clear()
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
            "updateLossless" -> onUpdateLossless(listener)
            "updateV2Lossless" -> onUpdateV2Lossless(listener)
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
            "updateLossless" -> updateLosslessEventListeners.remove(listener)
            "updateV2Lossless" -> updateV2LosslessEventListeners.remove(listener)
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

    fun observeUpdatesLossless(listener: (update: ByteArray, origin: Any?) -> Unit): Subscription {
        val wrapper: (ByteArray, Any?, YDoc, YTransactionEvent?) -> Unit = { update, origin, _, _ ->
            listener(update, origin)
        }
        updateLosslessEventListeners.add(wrapper)
        return Subscription { updateLosslessEventListeners.remove(wrapper) }
    }

    fun onUpdate(listener: (update: ByteArray, origin: Any?, doc: YDoc, transaction: YTransactionEvent?) -> Unit): Subscription {
        updateEventListeners.add(listener)
        return Subscription { updateEventListeners.remove(listener) }
    }

    fun onUpdateV2(listener: (update: ByteArray, origin: Any?, doc: YDoc, transaction: YTransactionEvent?) -> Unit): Subscription {
        updateV2EventListeners.add(listener)
        return Subscription { updateV2EventListeners.remove(listener) }
    }

    fun onUpdateLossless(
        listener: (update: ByteArray, origin: Any?, doc: YDoc, transaction: YTransactionEvent?) -> Unit,
    ): Subscription {
        updateLosslessEventListeners.add(listener)
        return Subscription { updateLosslessEventListeners.remove(listener) }
    }

    fun onUpdateV2Lossless(
        listener: (update: ByteArray, origin: Any?, doc: YDoc, transaction: YTransactionEvent?) -> Unit,
    ): Subscription {
        updateV2LosslessEventListeners.add(listener)
        return Subscription { updateV2LosslessEventListeners.remove(listener) }
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
        return UpdateCodec.encodeLossless(
            DocumentUpdate(items, snapshot.deleteSet, store.parentItemIds(), store.parentKinds()),
        )
    }

    fun encodeStateAsUpdate(encodedStateVector: ByteArray = ByteArray(0)): ByteArray {
        val stateVector = decodeStateVector(encodedStateVector)
        val updates = mutableListOf(
            UpdateCodec.encode(
                DocumentUpdate(
                    store.itemsSince(stateVector),
                    store.deleteSet(),
                    store.parentItemIds(),
                    store.parentKinds(),
                ),
            ),
        )
        pendingDeleteSetUpdate()?.let(updates::add)
        pendingStructsView()?.update
            ?.let { pendingUpdate -> diffUpdate(pendingUpdate, encodedStateVector) }
            ?.let(updates::add)
        return if (updates.size == 1) updates.single() else mergeUpdates(updates)
    }

    fun encodeStateAsUpdateLossless(encodedStateVector: ByteArray = ByteArray(0)): ByteArray {
        val stateVector = decodeStateVector(encodedStateVector)
        val updates = mutableListOf(
            UpdateCodec.encodeLossless(
                DocumentUpdate(
                    store.itemsSince(stateVector),
                    store.deleteSet(),
                    store.parentItemIds(),
                    store.parentKinds(),
                ),
            ),
        )
        pendingDeleteSetUpdate()?.let(updates::add)
        pendingStructsView()?.update
            ?.let { pendingUpdate -> diffUpdateLossless(pendingUpdate, encodedStateVector) }
            ?.let(updates::add)
        return if (updates.size == 1) updates.single() else mergeUpdatesLossless(updates)
    }

    internal fun encodeStateAsUpdateV2(encodedStateVector: ByteArray = ByteArray(0)): ByteArray {
        val stateVector = decodeStateVector(encodedStateVector)
        val updates = mutableListOf(
            UpdateCodec.encodeV2(
                DocumentUpdate(
                    store.itemsSince(stateVector),
                    store.deleteSet(),
                    store.parentItemIds(),
                    store.parentKinds(),
                ),
            ),
        )
        pendingDeleteSetUpdate()
            ?.let(UpdateCodec::decode)
            ?.let(UpdateCodec::encodeV2)
            ?.let(updates::add)
        pendingStructsView()?.update
            ?.let { pendingUpdate -> diffUpdate(pendingUpdate, encodedStateVector) }
            ?.let(UpdateCodec::decode)
            ?.let(UpdateCodec::encodeV2)
            ?.let(updates::add)
        return if (updates.size == 1) updates.single() else mergeUpdatesV2(updates)
    }

    internal fun encodeStateAsUpdateV2Lossless(encodedStateVector: ByteArray = ByteArray(0)): ByteArray {
        val stateVector = decodeStateVector(encodedStateVector)
        val updates = mutableListOf(
            UpdateCodec.encodeV2Lossless(
                DocumentUpdate(
                    store.itemsSince(stateVector),
                    store.deleteSet(),
                    store.parentItemIds(),
                    store.parentKinds(),
                ),
            ),
        )
        pendingDeleteSetUpdate()
            ?.let(UpdateCodec::decode)
            ?.let(UpdateCodec::encodeV2Lossless)
            ?.let(updates::add)
        pendingStructsView()?.update
            ?.let { pendingUpdate -> diffUpdateLossless(pendingUpdate, encodedStateVector) }
            ?.let(UpdateCodec::decode)
            ?.let(UpdateCodec::encodeV2Lossless)
            ?.let(updates::add)
        return if (updates.size == 1) updates.single() else mergeUpdatesV2Lossless(updates)
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
        store.sequence(parent).filter { !it.deleted && it.countable }

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
            update = UpdateCodec.encodeLossless(
                DocumentUpdate(
                    pendingItems.toList(),
                    DeleteSet.empty(),
                    store.parentItemIds(),
                    store.parentKinds(),
                ),
            ),
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
        return UpdateCodec.encodeLossless(DocumentUpdate(emptyList(), pendingIds.toDeleteSet()))
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
            store.allItems()
                .filter { item ->
                    item.id.client == client &&
                        item.id.clock < range.end &&
                        range.clock < checkedClockAdd(item.id.clock, item.length) &&
                        predicate(item)
                }
                .forEach { item ->
                    val itemEnd = checkedClockAdd(item.id.clock, item.length)
                    val start = maxOf(item.id.clock, range.clock)
                    val end = minOf(itemEnd, range.end)
                    val selected = if (start == item.id.clock && end == itemEnd) {
                        item.copy()
                    } else {
                        val deleted = item.content as? ItemContent.Deleted
                            ?: error("id range splits unsupported store item at ${item.id}")
                        item.copy(
                            id = Id(client, start),
                            origin = if (item.isGc || start == item.id.clock) item.origin else Id(client, start - 1),
                            content = deleted.copy(length = end - start),
                        )
                    }
                    items.add(if (deletedOverride == null) selected else selected.copy(deleted = deletedOverride))
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

    internal fun rootType(name: String): AbstractYType? = rootTypes[name]

    fun rootNames(): Set<String> {
        return (rootTypes.keys + unopenedRootEntries.keys + store.allItems().map { it.parent })
            .filterNot { it in nestedNames }
            .filterNot { it.startsWith("__yjs_gc__:") }
            .toSortedSet()
    }

    private fun sharedRootEntry(name: String): AbstractYType? {
        rootTypes[name]?.let { return it }
        return unopenedRoot(name)
    }

    private fun unopenedRoot(name: String): YUnopenedRoot? {
        if (name !in rootNames()) return null
        return unopenedRootEntries.getOrPut(name) { YUnopenedRoot(this, name) }
    }

    private fun createUnopenedRoot(name: String): YUnopenedRoot {
        require(name !in nestedNames) { "nested type '$name' cannot be opened as a root type" }
        return unopenedRootEntries.getOrPut(name) { YUnopenedRoot(this, name) }
    }

    internal fun concreteRootTypes(): Map<String, AbstractYType> = rootTypes.toMap()

    internal fun knownParentKinds(): Map<String, RootKind> = buildMap {
        rootTypes.forEach { (name, type) -> put(name, type.kind) }
        nestedTypes.forEach { (name, type) -> put(name, type.kind) }
    }

    internal fun storeValue(value: Any?, parent: String? = null): YValue {
        preflightNestedValue(value)
        pendingNestedReferenceStack.add(linkedSetOf())
        pendingStoreParentStack.add(parent)
        return try {
            storeAnyValue(value).also(::rememberNestedRefs)
        } finally {
            pendingStoreParentStack.removeAt(pendingStoreParentStack.lastIndex)
            pendingNestedReferenceStack.removeAt(pendingNestedReferenceStack.lastIndex)
        }
    }

    internal fun preflightNestedValue(value: Any?) {
        validateStoreValue(value)
        preparePreliminaryGraph(value)
    }

    /** Preflights only shared-type identity/binding for APIs that transform non-YValue inputs. */
    internal fun preflightSharedTypes(value: Any?) {
        preparePreliminaryGraph(value)
    }

    private fun validateStoreValue(value: Any?) {
        when (value) {
            null,
            is YValue,
            is AbstractYType,
            is Boolean,
            is Byte,
            is Short,
            is Int,
            is Long,
            is java.math.BigInteger,
            is Float,
            is Double,
            is String,
            is ByteArray -> Unit
            is YDoc -> require(value !== this) { "a document cannot contain itself as a subdoc" }
            is List<*> -> value.forEach(::validateStoreValue)
            is Array<*> -> value.forEach(::validateStoreValue)
            is Map<*, *> -> value.forEach { (key, nested) ->
                require(key is String) { "YValue map keys must be strings" }
                validateStoreValue(nested)
            }
            else -> error("unsupported YValue type: ${value::class.qualifiedName}")
        }
    }

    private fun storeAnyValue(value: Any?): YValue = when (value) {
        null -> YValue.Null
        is YValue.TypeRef -> registerNestedTypeRefValue(value)
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
        is java.math.BigInteger -> YValue.BigIntNumber(value)
        is Float -> YValue.DoubleNumber(value.toDouble())
        is Double -> YValue.DoubleNumber(value)
        is String -> YValue.StringValue(value)
        is ByteArray -> YValue.BinaryValue(value.copyOf())
        is List<*> -> YValue.ListValue(value.map(::storeAnyValue))
        is Array<*> -> YValue.ListValue(value.map(::storeAnyValue))
        is Map<*, *> -> YValue.MapValue(value.entries.associate { (key, nested) ->
            require(key is String) { "YValue map keys must be strings" }
            key to storeAnyValue(nested)
        })
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
        if (kind == RootKind.Text || kind == RootKind.XmlText) {
            val full = sequence(parent).filter { it.content.kind == kind }
            val visible = full.filter { !it.deleted && it.countable }
            require(index <= visible.size) { "insert index is out of bounds" }
            val right = visible.getOrNull(index)
            val rightIndex = right?.let(full::indexOf) ?: full.size
            val left = full.getOrNull(rightIndex - 1)
            registerVirtualInsertionSplitCandidate(left, right)
            return left?.id to right?.id
        }
        val full = sequence(parent).filter { it.content.kind == kind && it.countable }
        val visible = full.filter { !it.deleted }
        require(index <= visible.size) { "insert index is out of bounds" }
        val right = visible.getOrNull(index)
        val rightIndex = right?.let { full.indexOf(it) } ?: full.size
        val left = full.getOrNull(rightIndex - 1)
        registerVirtualInsertionSplitCandidate(left, right)
        return left?.id to right?.id
    }

    private fun registerVirtualInsertionSplitCandidate(left: StoreItem?, right: StoreItem?) {
        if (
            left != null &&
            right != null &&
            left.canVirtuallyMerge(left, right, setOf(left.id to right.id))
        ) {
            currentTransaction?.mergeStructs?.add(right.id)
        }
    }

    internal fun visibleMapValue(parent: String, key: String): YValue? {
        return currentMapItem(parent, key)
            ?.takeIf { item -> !item.deleted }
            ?.content
            ?.mapContentValue()
    }

    internal fun visibleMap(parent: String): Map<String, YValue> {
        return mapKeysInInsertionOrder(parent)
            .mapNotNull { key -> visibleMapValue(parent, key)?.let { key to it } }
            .toMap(linkedMapOf())
    }

    private fun visibleMapItemIds(parent: String): Map<String, Id> =
        mapKeysInInsertionOrder(parent)
            .mapNotNull { key -> currentVisibleMapItemId(parent, key)?.let { key to it } }
            .toMap(linkedMapOf())

    internal fun mapValueAtSnapshot(type: AbstractYType, key: String, snapshot: Snapshot): YValue? {
        require(type.doc === this) { "type must belong to this document" }
        val item = mapItemOrder(type.name, key)
            .asReversed()
            .firstOrNull { item -> item.id.clock < (snapshot.sv[item.id.client] ?: 0) }
            ?: return null
        if (snapshot.ds.hasId(item.id)) return null
        return item.content.mapContentValue()
    }

    internal fun mapAtSnapshot(type: AbstractYType, snapshot: Snapshot): Map<String, YValue> {
        require(type.doc === this) { "type must belong to this document" }
        return mapKeysInInsertionOrder(type.name)
            .mapNotNull { key -> mapValueAtSnapshot(type, key, snapshot)?.let { key to it } }
            .toMap(linkedMapOf())
    }

    internal fun currentMapItemId(parent: String, key: String): Id? = currentMapItem(parent, key)?.id

    internal fun currentVisibleMapItemId(parent: String, key: String): Id? =
        currentMapItem(parent, key)?.takeUnless { item -> item.deleted }?.id

    private fun currentMapItem(parent: String, key: String): StoreItem? =
        mapItemOrder(parent, key).lastOrNull()

    internal fun mapItemKeys(parent: String): Set<String> =
        mapKeysInInsertionOrder(parent).toCollection(linkedSetOf())

    private fun mapKeysInInsertionOrder(parent: String): List<String> {
        val remembered = mapKeyOrders[parent].orEmpty()
        if (remembered.isEmpty()) {
            return store.allItems().asSequence()
                .filter { item -> item.parent == parent && item.parentSub != null }
                .mapNotNull { item -> item.parentSub }
                .distinct()
                .toList()
        }
        // The fallback makes this robust to internal test/store construction that bypasses
        // YDoc integration. Normal local and remote integration only take the remembered path.
        val missing = store.allItems().asSequence()
            .filter { item -> item.parent == parent && item.parentSub != null }
            .mapNotNull { item -> item.parentSub }
            .filterNot(remembered::contains)
            .distinct()
        return remembered.toList() + missing.toList()
    }

    private fun rememberMapKey(item: StoreItem) {
        val key = item.parentSub ?: return
        mapKeyOrders.getOrPut(item.parent) { linkedSetOf() }.add(key)
    }

    private fun ItemContent.mapContentValue(): YValue? = when (this) {
        is ItemContent.MapEntry -> value
        is ItemContent.XmlType -> ref.takeIf { kind == RootKind.Map || kind == RootKind.XmlHook }
        else -> null
    }

    internal fun mapItemOrder(parent: String, key: String): List<StoreItem> =
        store.cachedMapOrder(parent, key) { buildMapItemOrder(parent, key) }

    private fun buildMapItemOrder(parent: String, key: String): List<StoreItem> {
        val entries = store.mapEntries(parent, key)
        if (entries.isEmpty()) return emptyList()
        val entriesByClient = entries.groupBy { item -> item.id.client }
            .mapValues { (_, clientEntries) -> clientEntries.sortedBy { item -> item.id.clock } }

        fun findEntry(id: Id?): StoreItem? {
            if (id == null) return null
            val clientEntries = entriesByClient[id.client] ?: return null
            var low = 0
            var high = clientEntries.lastIndex
            var candidate: StoreItem? = null
            while (low <= high) {
                val middle = (low + high) ushr 1
                val entry = clientEntries[middle]
                if (entry.id.clock <= id.clock) {
                    candidate = entry
                    low = middle + 1
                } else {
                    high = middle - 1
                }
            }
            return candidate?.takeIf { entry -> entry.containsClockId(id) }
        }

        val remainingIds = entries.mapTo(hashSetOf()) { item -> item.id }
        val dependents = mutableMapOf<Id, MutableList<StoreItem>>()
        val indegrees = mutableMapOf<Id, Int>()
        entries.forEach { item ->
            val ownerId = findEntry(item.origin)?.id
            indegrees[item.id] = if (ownerId == null) 0 else 1
            if (ownerId != null) {
                dependents.getOrPut(ownerId) { mutableListOf() }.add(item)
            }
        }

        val ready = java.util.PriorityQueue(compareBy<StoreItem> { item -> item.id })
        entries.filterTo(ready) { item -> indegrees.getValue(item.id) == 0 }
        val ordered = mutableListOf<StoreItem>()

        while (remainingIds.isNotEmpty()) {
            var item = ready.poll()
            while (item != null && item.id !in remainingIds) item = ready.poll()
            if (item == null) {
                item = entries.asSequence()
                    .filter { candidate -> candidate.id in remainingIds }
                    .minBy { candidate -> candidate.id }
            }
            insertMapItem(ordered, item, ::findEntry)
            remainingIds.remove(item.id)
            dependents[item.id].orEmpty().forEach { dependent ->
                val next = indegrees.getValue(dependent.id) - 1
                indegrees[dependent.id] = next
                if (next == 0 && dependent.id in remainingIds) ready.add(dependent)
            }
        }

        return ordered
    }

    private fun insertMapItem(
        ordered: MutableList<StoreItem>,
        item: StoreItem,
        findEntry: (Id?) -> StoreItem?,
    ) {
        var leftIndex = item.origin?.let { origin ->
            val ownerId = findEntry(origin)?.id ?: return@let null
            ordered.indexOfFirst { existing -> existing.id == ownerId }.takeIf { index -> index >= 0 }
        } ?: -1
        var scanIndex = leftIndex + 1
        val conflictingItems = hashSetOf<Id>()
        val itemsBeforeOrigin = hashSetOf<Id>()

        while (scanIndex < ordered.size) {
            val other = ordered[scanIndex]
            val otherOriginId = findEntry(other.origin)?.id
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
                otherOriginId != null && otherOriginId in itemsBeforeOrigin -> {
                    if (otherOriginId !in conflictingItems) {
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
            item.content.kind == type.kind && item.countable && item.isVisibleIn(snapshot)
        }
    }

    private fun textItemsAtSnapshot(type: YText, snapshot: Snapshot): List<StoreItem> {
        require(type.doc === this) { "type must belong to this document" }
        val visibleItems = sequence(type.name).filter { item ->
            item.content.kind == type.kind && item.isVisibleIn(snapshot)
        }
        val textItems = visibleItems.withNativeTextFormatting()
        visibleItems
            .filter { item -> item.content is ItemContent.TextFormat }
            .sortedWith(textFormatApplicationOrder)
            .forEach { formatItem -> applySingleTextFormat(textItems, formatItem) }
        return textItems
    }

    internal fun arrayAtSnapshot(type: YArray, snapshot: Snapshot): List<Any?> =
        sequenceAtSnapshot(type, snapshot).map(::arrayItemValue)

    internal fun arrayItemValue(item: StoreItem): Any? = when (val content = item.content) {
        is ItemContent.Value -> valueToAny(content.value)
        is ItemContent.XmlType -> typeFromXmlType(content)
        else -> error("item content is not an array value: ${content::class.simpleName}")
    }

    internal fun textDeltaAtSnapshot(type: YText, snapshot: Snapshot): YTextDelta {
        val delta = YTextDelta()
        var pendingText = StringBuilder()
        var pendingAttributes: Map<String, Any?>? = null
        val formattedById = textItemsAtSnapshot(type, snapshot).associateBy { item -> item.id }

        fun flush() {
            if (pendingText.isNotEmpty()) {
                delta.insertSegment(pendingText.toString(), pendingAttributes.orEmpty())
                pendingText = StringBuilder()
            }
        }

        sequence(type.name).forEach { rawItem ->
            if (rawItem.content.kind != type.kind || !rawItem.isVisibleIn(snapshot)) return@forEach
            if (rawItem.content is ItemContent.NativeTextFormat) {
                flush()
                return@forEach
            }
            val item = formattedById[rawItem.id] ?: return@forEach
            when (val content = item.content) {
                is ItemContent.Text -> {
                    val attrs = textAttributesToPublic(content.textAttributesOrEmpty())
                    if (pendingAttributes != null && pendingAttributes != attrs) flush()
                    pendingAttributes = attrs
                    pendingText.append(content.value)
                }
                is ItemContent.TextEmbed -> {
                    val attrs = textAttributesToPublic(content.textAttributesOrEmpty())
                    flush()
                    delta.insertEmbed(valueToAny(content.value), attrs)
                    pendingAttributes = null
                }
                is ItemContent.XmlType -> {
                    val attrs = textAttributesToPublic(content.textAttributesOrEmpty())
                    flush()
                    delta.insertEmbed(typeFromXmlType(content), attrs)
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
                is ItemContent.TextEmbed -> ""
                else -> ""
            }
        }

    internal fun textArrayAtSnapshot(type: YText, snapshot: Snapshot): List<Any?> =
        textItemsAtSnapshot(type, snapshot).map { item ->
            when (val content = item.content) {
                is ItemContent.Text -> content.value
                is ItemContent.TextEmbed -> valueToAny(content.value)
                is ItemContent.XmlType -> typeFromXmlType(content)
                else -> null
            }
        }

    internal fun xmlFragmentAtSnapshot(type: YXmlFragment, snapshot: Snapshot): List<Any?> =
        sequenceAtSnapshot(type, snapshot).map { item -> xmlNodeAtSnapshot(item.content, snapshot).toJson() }

    internal fun xmlFragmentArrayAtSnapshot(type: YXmlFragment, snapshot: Snapshot): List<Any?> =
        sequenceAtSnapshot(type, snapshot).map { item -> xmlArrayValueAtSnapshot(item.content) }

    internal fun xmlFragmentStringAtSnapshot(
        type: YXmlFragment,
        snapshot: Snapshot,
        forceTag: Boolean = false,
    ): String = renderXmlFragmentString(
        name = type.name,
        attrs = mapAtSnapshot(type, snapshot).mapValues { (_, value) -> valueToAny(value) },
        nodes = sequenceAtSnapshot(type, snapshot).map { item -> xmlNodeAtSnapshot(item.content, snapshot) },
        forceTag = forceTag,
    )

    internal fun xmlFragmentDeltaAtSnapshot(type: YXmlFragment, snapshot: Snapshot): List<YArrayDeltaOp> {
        val nodes = xmlFragmentArrayAtSnapshot(type, snapshot)
        return xmlChildrenToDelta(nodes)
    }

    /** Upstream list/delta snapshot helpers expose every ContentType as the same live instance. */
    private fun xmlArrayValueAtSnapshot(content: ItemContent): Any? = when (content) {
        is ItemContent.XmlNode -> content.value.toNode()
        is ItemContent.XmlType -> typeFromXmlType(content)
        else -> error("item content is not an XML snapshot child: ${content::class.simpleName}")
    }

    private fun xmlNodeAtSnapshot(content: ItemContent, snapshot: Snapshot): YXmlNode = when (content) {
        is ItemContent.XmlNode -> content.value.toNode()
        is ItemContent.XmlType -> when (val type = typeFromXmlType(content)) {
            is YXmlTextType -> YXmlSnapshotText(textDeltaAtSnapshot(type, snapshot))
            is YText -> YXmlText(textStringAtSnapshot(type, snapshot))
            is YXmlElementType -> YXmlElement(type.nodeName).also { element ->
                element.setAttrs(type.getAttrs(snapshot))
                element.push(sequenceAtSnapshot(type, snapshot).map { item -> xmlNodeAtSnapshot(item.content, snapshot) })
            }
            is YXmlHook -> YXmlText(type.toString())
            is YXmlFragment -> YXmlText(xmlFragmentStringAtSnapshot(type, snapshot))
            else -> YXmlText("[object Object]")
        }
        else -> error("item content is not an XML snapshot child: ${content::class.simpleName}")
    }

    internal fun setTypeAttribute(parent: String, key: String, value: Any?): Any? {
        transact {
            val content = ItemContent.MapEntry(storeValue(value, parent = parent))
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
            rememberMapKey(item)
            rememberNestedRefs(item.content)
            if (shouldReapplyTextFormatsAfter(item)) {
                reapplyTextFormats(item.parent)
            }
            currentTransaction?.addedItems?.add(item)
            currentTransaction?.markChanged(item.parent, item.parentSub)
            attachAndReplayPreliminaryTypes(item.content, item.id)
            deletePreviousMapCurrentIfSuperseded(item, previousMapCurrent)
        }
    }

    private fun integrateLocalTextFormat(item: StoreItem) {
        transact {
            captureParentBefore(item.parent, item.content.kind)
            check(store.add(item)) { "duplicate local item id: ${item.id}" }
            applyTextFormat(item)
            currentTransaction?.addedItems?.add(item)
            currentTransaction?.markChanged(item.parent, item.parentSub)
        }
    }

    internal fun deleteVisible(
        parent: String,
        index: Int,
        length: Int,
        origin: Any? = null,
        strictLength: Boolean = true,
    ) {
        val visible = visibleSequence(parent)
        val start = index.coerceAtLeast(0)
        transact(origin = origin) {
            if (length <= 0) return@transact
            val available = (visible.size - start).coerceAtLeast(0)
            val deleteCount = minOf(length, available)
            if (deleteCount > 0) {
                val deleteSet = DeleteSet.empty()
                visible.subList(start, start + deleteCount).forEach { deleteSet.add(it.id, it.length) }
                applyDeleteSet(deleteSet)
            }
            if (strictLength && length > available) {
                throw IllegalArgumentException("delete range is out of bounds")
            }
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

    internal fun deleteItemsByIds(ids: Iterable<Id>, markCleanups: Boolean = false) {
        transact {
            val deleteSet = DeleteSet.empty()
            ids.forEach { id ->
                val item = store.getStoreItem(id)
                if (item != null && !item.deleted) {
                    deleteSet.add(id, item.length)
                    if (markCleanups) {
                        currentTransaction?.cleanUps?.add(id, item.length)
                    }
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
            val previousRestoredByParent = mutableMapOf<Pair<String, String?>, Pair<Int, Id>>()
            sortedItems.forEach { original ->
                val source = original.item
                val mapOrigin = source.parentSub?.let { key -> currentMapItem(source.parent, key)?.id }
                val sourcePosition = originalPositions[source.id]
                val parentKey = source.parent to source.parentSub
                val contiguousOrigin = if (source.parentSub == null && sourcePosition != null) {
                    previousRestoredByParent[parentKey]
                        ?.takeIf { (previousPosition, _) -> sourcePosition == previousPosition + 1 }
                        ?.second
                } else {
                    null
                }
                val item = StoreItem(
                    id = nextId(),
                    origin = if (source.parentSub != null) {
                        mapOrigin
                    } else if (contiguousOrigin != null) {
                        contiguousOrigin
                    } else if (source.content is ItemContent.NativeTextFormat) {
                        source.origin?.let { restoredByOriginal[it] } ?: source.origin?.let(::followRedone)
                    } else if (original.anchorAfterOriginal) {
                        source.origin?.let { restoredByOriginal[it] } ?: source.id
                    } else {
                        source.origin?.let { restoredByOriginal[it] } ?: source.origin
                    },
                    rightOrigin = if (source.parentSub != null) {
                        null
                    } else if (contiguousOrigin != null) {
                        inferRightOrigin(source)
                    } else if (source.content is ItemContent.NativeTextFormat) {
                        source.rightOrigin?.let { restoredByOriginal[it] } ?: source.rightOrigin?.let(::followRedone)
                    } else if (original.anchorAfterOriginal) {
                        inferRightOrigin(source)
                    } else {
                        source.rightOrigin
                    },
                    parent = source.parent,
                    parentSub = source.parentSub,
                    content = source.content.retargetTextFormat(restoredByOriginal),
                    deleted = false,
                )
                check(store.add(item)) { "duplicate restored item id: ${item.id}" }
                rememberMapKey(item)
                restoredByOriginal[source.id] = item.id
                redoneByOriginal[source.id] = item.id
                restoredPairs.add(source to item)
                restored.add(item)
                if (sourcePosition != null) {
                    previousRestoredByParent[parentKey] = sourcePosition to item.id
                }
                currentTransaction?.addedItems?.add(item)
                currentTransaction?.markChanged(item.parent, item.parentSub)
            }
            rememberRedoneRangeEnds(restoredPairs, originalPositions)
            restored.forEachIndexed { index, item ->
                val retargeted = item.content.retargetTextFormat(restoredByOriginal)
                if (retargeted != item.content) {
                    val updated = store.replaceContent(item.id, retargeted) ?: item.copy(content = retargeted)
                    restored[index] = updated
                    val addedIndex = currentTransaction?.addedItems?.indexOfFirst { added -> added.id == item.id } ?: -1
                    if (addedIndex >= 0) {
                        currentTransaction?.addedItems?.set(addedIndex, updated)
                    }
                }
            }
            restored
                .filter { item -> item.content.isTextFormatControl() }
                .map { item -> item.parent to item.content.kind }
                .toSet()
                .forEach { (parent, kind) ->
                    captureParentBefore(parent, kind)
                    reapplyTextFormats(parent)
                    currentTransaction?.markChanged(parent, null)
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
        registerVirtualSplitMergeCandidates(expandedDeleteSet)
        val newlyDeletedItems = store.itemsStartingIn(expandedDeleteSet).filterNot { it.deleted }
        val newlyDeleted = newlyDeletedItems.map { it.copy(deleted = false) }
        newlyDeleted.forEach { captureParentBefore(it.parent, it.content.kind) }
        pendingDeletes.addAll(expandedDeleteSet)
        val changed = store.markDeleted(newlyDeletedItems)
        if (changed) {
            val newlyDeletedSet = DeleteSet.empty().also { transactionDeleteSet ->
                newlyDeleted.forEach { item -> transactionDeleteSet.add(item.id, item.length) }
            }
            currentTransaction?.deleteSet?.addAll(newlyDeletedSet)
            newlyDeleted.forEach {
                currentTransaction?.deletedItems?.add(it)
                currentTransaction?.markChanged(it.parent, it.parentSub)
            }
            newlyDeleted
                .filter { item -> item.content.isTextFormatControl() }
                .map { item -> item.parent }
                .toSet()
                .forEach { parent ->
                    reapplyTextFormats(parent)
                    currentTransaction?.markChanged(parent, null)
                }
        }
    }

    /**
     * StoreItem values are unit-sized even when upstream currently represents adjacent values as
     * one packed Item. Record the right unit at every deletion boundary that would split such an
     * Item, mirroring splitItem's transaction._mergeStructs entry.
     */
    private fun registerVirtualSplitMergeCandidates(deleteSet: DeleteSet) {
        val transaction = currentTransaction ?: return
        val allItems = store.allItems()
        allItems.groupBy { item -> item.parent to item.parentSub }.forEach { (parentKey, _) ->
            val (parent, parentSub) = parentKey
            val logicalItems = if (parentSub == null) store.sequence(parent) else mapItemOrder(parent, parentSub)
            logicalItems.zipWithNext().forEach { (left, right) ->
                val leftWillBeDeleted = !left.deleted && deleteSet.contains(left.id)
                val rightWillBeDeleted = !right.deleted && deleteSet.contains(right.id)
                if (
                    leftWillBeDeleted != rightWillBeDeleted &&
                    left.canVirtuallyMerge(left, right, setOf(left.id to right.id))
                ) {
                    transaction.mergeStructs.add(right.id)
                }
            }
        }
    }

    private fun expandDeleteSetWithNestedTypeContent(deleteSet: DeleteSet): DeleteSet {
        val expanded = deleteSet.copy()
        val queue = ArrayDeque<StoreItem>()
        store.itemsOverlapping(deleteSet).forEach(queue::add)

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
        val pendingIds = pendingItems.mapTo(hashSetOf()) { item -> item.id }
        pendingItems.addAll(
            items.filterNot { item -> store.contains(item.id) || !pendingIds.add(item.id) }
                .map(::resolveRemoteParentAlias),
        )
        var madeProgress: Boolean
        do {
            madeProgress = false
            val iterator = pendingItems.iterator()
            while (iterator.hasNext()) {
                val item = resolveRemoteParentAlias(iterator.next())
                if (item.id.clock < store.getClock(item.id.client)) {
                    iterator.remove()
                    madeProgress = true
                    continue
                }
                if (canIntegrate(item)) {
                    iterator.remove()
                    val wasPendingDelete = pendingDeletes.contains(item.id)
                    if (wasPendingDelete) {
                        item.deleted = true
                    }
                    captureParentBefore(item.parent, item.content.kind)
                    if (store.add(item)) {
                        rememberMapKey(item)
                        if (shouldReapplyTextFormatsAfter(item)) {
                            reapplyTextFormats(item.parent)
                        }
                        rememberNestedRefs(item.content)
                        currentTransaction?.addedItems?.add(item)
                        currentTransaction?.markChanged(item.parent, item.parentSub)
                        if (wasPendingDelete) {
                            currentTransaction?.deleteSet?.add(item.id, item.length)
                            currentTransaction?.deletedItems?.add(item.copy(deleted = false))
                        }
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

    private fun resolveRemoteParentAlias(item: StoreItem): StoreItem {
        val gcAnchor = listOfNotNull(item.origin, item.rightOrigin)
            .mapNotNull(store::getStoreItem)
            .firstOrNull { anchor -> anchor.isGc }
        if (gcAnchor != null) {
            return item.asRemoteGc()
        }
        when (val unresolved = item.unresolvedParent) {
            is UnresolvedYjsParent.Nested -> {
                val parentItem = store.getStoreItem(unresolved.id) ?: return item
                if (parentItem.isGc) return item.asRemoteGc()
                val ref = parentItem.content.directTypeRef() ?: return item
                val parentKind = if (item.parentSub != null && ref.kind != RootKind.XmlHook) {
                    RootKind.Map
                } else {
                    ref.kind
                }
                return item.copy(
                    parent = ref.name,
                    content = item.content.withRemoteParentKind(parentKind),
                    deleted = item.deleted || parentItem.deleted,
                    unresolvedParent = null,
                )
            }
            is UnresolvedYjsParent.Inherit -> {
                val anchor = store.getStoreItem(unresolved.id) ?: return item
                if (anchor.isGc) return item.asRemoteGc()
                val inheritedKind = when {
                    anchor.parentSub != null -> knownParentKinds()[anchor.parent]
                        ?.takeIf { kind -> kind == RootKind.XmlHook }
                        ?: RootKind.Map
                    anchor.content is ItemContent.Deleted -> item.content.kind
                    else -> anchor.content.kind
                }
                return item.copy(
                    parent = anchor.parent,
                    parentSub = anchor.parentSub,
                    content = item.content.withRemoteParentKind(inheritedKind),
                    unresolvedParent = null,
                )
            }
            null -> return knownParentKinds()[item.parent]?.let { kind ->
                item.copy(
                    content = item.content.withRemoteParentKind(
                        if (item.parentSub != null && kind != RootKind.XmlHook) RootKind.Map else kind,
                    ),
                )
            } ?: item
        }
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
        if (item.unresolvedParent != null) {
            return false
        }
        return (!item.requiresClockContinuity || item.id.clock == store.getClock(item.id.client)) &&
            (item.origin == null || store.contains(item.origin)) &&
            (textFormat == null || store.contains(textFormat.target)) &&
            (item.rightOrigin == null || store.contains(item.rightOrigin))
    }

    private fun applyTextFormat(item: StoreItem): List<StoreItem> {
        if (!item.content.isTextFormatControl()) return emptyList()
        return reapplyTextFormats(item.parent)
    }

    private fun shouldReapplyTextFormatsAfter(item: StoreItem): Boolean =
        item.content.isTextFormatControl() ||
            item.content.isTextCountable() &&
            store.allItems().any { candidate ->
                candidate.parent == item.parent &&
                !candidate.deleted && candidate.content is ItemContent.NativeTextFormat
            }

    private fun reapplyTextFormats(parent: String): List<StoreItem> {
        val changed = mutableListOf<StoreItem>()
        val activeNativeAttributes = linkedMapOf<String, YValue>()
        sequence(parent).forEach { item ->
            if (item.deleted) return@forEach
            when (val content = item.content) {
                is ItemContent.NativeTextFormat -> activeNativeAttributes[content.key] = content.value
                is ItemContent.Text,
                is ItemContent.TextEmbed,
                is ItemContent.XmlType -> {
                    val attributes = content.effectiveTextAttributes(activeNativeAttributes)
                    setTextAttributes(item, attributes)?.let(changed::add)
                }
                else -> Unit
            }
        }
        sequence(parent)
            .filter { item -> !item.deleted && item.content is ItemContent.TextFormat }
            .sortedWith(textFormatApplicationOrder)
            .forEach { formatItem -> changed.addAll(applySingleTextFormat(formatItem)) }
        return changed
    }

    private fun applySingleTextFormat(item: StoreItem): List<StoreItem> {
        val format = item.content as? ItemContent.TextFormat ?: return emptyList()
        val textItems = sequence(item.parent)
            .filter { textItem ->
                !textItem.deleted && textItem.content.kind == format.kind && textItem.content.isTextCountable()
            }
        val start = textItems.indexOfFirst { textItem -> textItem.id == format.target }
        if (start < 0) return emptyList()

        val changed = mutableListOf<StoreItem>()
        val end = boundedIntRangeEnd(start, format.length, textItems.size, "text format")
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
        val start = items.indexOfFirst { textItem -> textItem.content.kind == format.kind && textItem.id == format.target }
        if (start < 0) return

        val end = boundedIntRangeEnd(start, format.length, items.size, "text format")
        for (index in start until end) {
            items.replaceTextAttributes(index, format.attributes)
        }
        if (end < items.size) {
            items.replaceTextAttributes(end, format.afterAttributes)
        }
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
            if (id.clock >= clock) {
                record(id.client, clock)
            }
        }

        forEach { item ->
            val clientClock = store.getClock(item.id.client)
            if (item.id.clock > clientClock) {
                record(item.id.client, item.id.clock - 1)
                return@forEach
            }
            item.origin?.let(::recordIfMissing)
            item.rightOrigin?.let(::recordIfMissing)
            item.unresolvedParent?.id?.let(::recordIfMissing)
            (item.content as? ItemContent.TextFormat)?.target?.let(::recordIfMissing)
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
        pendingTransactionEmits.addLast(transaction)
        if (isEmittingTransactions) return
        isEmittingTransactions = true
        var firstError: Throwable? = null
        try {
            while (pendingTransactionEmits.isNotEmpty()) {
                val batch = mutableListOf<YTransactionEvent>()
                // Yjs physically merges adjacent Item structs during transaction cleanup. This
                // store keeps one item per value/code unit, so retain the equivalent representative
                // id for later events in the same cleanup batch.
                val mergedStructRepresentatives = linkedMapOf<Id, Id>()
                while (pendingTransactionEmits.isNotEmpty()) {
                    val next = pendingTransactionEmits.removeFirst()
                    val event = createTransactionEvent(next)
                    batch.add(event)
                    try {
                        val wasEmittingTransactionEvent = isEmittingTransactionEvent
                        isEmittingTransactionEvent = true
                        try {
                            emit(next, event, mergedStructRepresentatives)
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
        val update = UpdateCodec.encodeLossless(
            DocumentUpdate(
                transaction.addedItems,
                transaction.deleteSet,
                store.parentItemIds(),
                store.parentKinds(),
            ),
        )
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

    private fun emit(
        transaction: Transaction,
        event: YTransactionEvent,
        mergedStructRepresentatives: MutableMap<Id, Id>,
    ) {
        resetSplitRepresentatives(transaction.mergeStructs, mergedStructRepresentatives)
        val beforeObserverCallbacks = mutableListOf<() -> Unit>()
        beforeObserverCallsListeners.toList().forEach { listener ->
            beforeObserverCallbacks.add { listener(event) }
        }
        beforeObserverCallbacks.addAll(transactionEventCallbacks("beforeObserverCalls", event))
        beforeObserverCallbacks.addAll(
            docEventCallbacks("beforeObserverCalls", YDocEvent(name = "beforeObserverCalls", transaction = event)),
        )
        var firstError: Throwable? = null
        fun callCallbacks(callbacks: Iterable<() -> Unit>) {
            callbacks.forEach { callback ->
                try {
                    callback()
                } catch (error: Throwable) {
                    if (firstError == null) {
                        firstError = error
                    } else {
                        firstError?.addSuppressed(error)
                    }
                }
            }
        }
        callCallbacks(beforeObserverCallbacks)

        val hasWireContent = event.afterState.any { (client, clock) ->
            clock > (event.beforeState[client] ?: 0)
        } || !transaction.deleteSet.isEmpty
        // Observer callbacks may enqueue another transaction before the update listeners run.
        // Snapshot the wire payload lazily so this update includes those newly integrated structs,
        // matching Yjs' writeStructsFromTransaction cleanup timing.
        val wireTransactionUpdate by lazy {
            DocumentUpdate(
                store.itemsSince(transaction.beforeState).map { item -> item.copy() },
                transaction.deleteSet.copy(),
                store.parentItemIds().toMap(),
                store.parentKinds().toMap(),
            )
        }
        val standardUpdate by lazy { UpdateCodec.encode(wireTransactionUpdate) }
        val standardUpdateV2 by lazy { UpdateCodec.encodeV2(wireTransactionUpdate) }
        val losslessUpdate by lazy { UpdateCodec.encodeLossless(wireTransactionUpdate) }
        val losslessUpdateV2 by lazy {
            UpdateCodec.encodeV2Lossless(wireTransactionUpdate)
        }

        // Upstream aborts the type/deep/afterTransaction phase when beforeObserverCalls fails,
        // then still performs all cleanup work from finally.
        if (firstError == null) {
            val observerCallbacks = mutableListOf<() -> Unit>()
            val eventUpdateItems = store.itemsSince(transaction.beforeState)
            val eventLosslessUpdate = UpdateCodec.encodeLossless(
                DocumentUpdate(
                    eventUpdateItems,
                    transaction.deleteSet,
                    store.parentItemIds(),
                    store.parentKinds(),
                ),
            )
            transactionListeners.toList().forEach { listener ->
                observerCallbacks.add { listener(event) }
            }
            val effectiveInsertSet = createIdSet().also { ids ->
                eventUpdateItems.forEach { item ->
                    val representative = mergedStructRepresentatives[item.id] ?: item.id
                    if (representative.clock >= (transaction.beforeState[representative.client] ?: 0)) {
                        ids.add(item.id, item.length)
                    }
                }
            }
            val effectiveDeleteSet = createDeleteSet().also { deletes ->
                store.allItems().forEach { item ->
                    val representative = mergedStructRepresentatives[item.id] ?: item.id
                    if (transaction.deleteSet.contains(representative)) {
                        deletes.add(item.id, item.length)
                    }
                }
            }
            val directEvents = linkedMapOf<String, YEvent>()
            event.changedParents.forEach { parent ->
                typeForParent(parent)?.let { type ->
                    val yEvent = createEvent(
                        type,
                        transaction,
                        event,
                        transaction.beforeParents[parent],
                        effectiveInsertSet,
                        effectiveDeleteSet,
                        eventLosslessUpdate,
                    )
                    directEvents[parent] = yEvent
                    observerCallbacks.add { type.emit(yEvent) }
                }
            }
            observerCallbacks.add { emitDeepEvents(transaction, eventLosslessUpdate, directEvents) }
            afterTransactionListeners.toList().forEach { listener ->
                observerCallbacks.add { listener(event) }
            }
            observerCallbacks.addAll(transactionEventCallbacks("afterTransaction", event))
            observerCallbacks.addAll(
                docEventCallbacks("afterTransaction", YDocEvent(name = "afterTransaction", transaction = event)),
            )
            callCallbacks(observerCallbacks)
        }

        val cleanupCallbacks = mutableListOf<() -> Unit>()
        cleanupCallbacks.add {
            if (gc && !transaction.deleteSet.isEmpty) {
                collectGarbageNow(this, transaction.deleteSet.toIdSet(), gcFilter)
            }
        }
        cleanupCallbacks.add {
            store.mergeDeletedItems(transaction.deleteSet)
            store.mergeSplitCandidates(transaction.mergeStructs)
        }
        cleanupCallbacks.add {
            mergeNewStructRepresentatives(event.beforeState, event.afterState, mergedStructRepresentatives)
            mergeSplitCandidateRepresentatives(transaction.mergeStructs, mergedStructRepresentatives)
        }
        afterTransactionCleanupListeners.toList().forEach { listener ->
            cleanupCallbacks.add { listener(event) }
        }
        cleanupCallbacks.addAll(transactionEventCallbacks("afterTransactionCleanup", event))
        cleanupCallbacks.addAll(
            docEventCallbacks(
                "afterTransactionCleanup",
                YDocEvent(name = "afterTransactionCleanup", transaction = event),
            ),
        )
        if (hasWireContent) {
            updateListeners.toList().forEach { listener ->
                cleanupCallbacks.add { listener(standardUpdate, transaction.origin) }
            }
            updateEventListeners.toList().forEach { listener ->
                cleanupCallbacks.add { listener(standardUpdate, transaction.origin, this, event) }
            }
            if (eventListeners["update"].orEmpty().isNotEmpty()) {
                cleanupCallbacks.add {
                    emitDocEvent(
                        YDocEvent(
                            name = "update",
                            update = standardUpdate,
                            origin = transaction.origin,
                            transaction = event,
                        ),
                    )
                }
            }
            updateV2EventListeners.toList().forEach { listener ->
                cleanupCallbacks.add { listener(standardUpdateV2, transaction.origin, this, event) }
            }
            if (eventListeners["updateV2"].orEmpty().isNotEmpty()) {
                cleanupCallbacks.add {
                    emitDocEvent(
                        YDocEvent(
                            name = "updateV2",
                            update = standardUpdateV2,
                            origin = transaction.origin,
                            transaction = event,
                        ),
                    )
                }
            }
            updateLosslessEventListeners.toList().forEach { listener ->
                cleanupCallbacks.add { listener(losslessUpdate, transaction.origin, this, event) }
            }
            if (eventListeners["updateLossless"].orEmpty().isNotEmpty()) {
                cleanupCallbacks.add {
                    emitDocEvent(
                        YDocEvent(
                            name = "updateLossless",
                            update = losslessUpdate,
                            origin = transaction.origin,
                            transaction = event,
                        ),
                    )
                }
            }
            updateV2LosslessEventListeners.toList().forEach { listener ->
                cleanupCallbacks.add { listener(losslessUpdateV2, transaction.origin, this, event) }
            }
            if (eventListeners["updateV2Lossless"].orEmpty().isNotEmpty()) {
                cleanupCallbacks.add {
                    emitDocEvent(
                        YDocEvent(
                            name = "updateV2Lossless",
                            update = losslessUpdateV2,
                            origin = transaction.origin,
                            transaction = event,
                        ),
                    )
                }
            }
        }
        event.subdocEvent()?.let { subdocEvent ->
            cleanupCallbacks.add { emitSubdocEvent(subdocEvent, event) }
            cleanupCallbacks.add {
                subdocEvent.removed.forEach { subdoc ->
                    if (!subdoc.isDestroyed) subdoc.destroy()
                }
            }
        }
        callCallbacks(cleanupCallbacks)
        firstError?.let { throw it }
    }

    private fun resetSplitRepresentatives(
        candidates: List<Id>,
        representatives: MutableMap<Id, Id>,
    ) {
        candidates.forEach { candidate ->
            val representative = representatives[candidate] ?: return@forEach
            representatives.entries
                .filter { (_, value) -> value == representative }
                .forEach { (id, _) -> representatives[id] = id }
        }
    }

    /** Mirrors cleanupTransactions' afterState scan, including its firstChangePos bound. */
    private fun mergeNewStructRepresentatives(
        beforeState: StateVector,
        afterState: StateVector,
        representatives: MutableMap<Id, Id>,
    ) {
        val allItems = store.allItems()
        val linkedPairs = currentLogicalPairs(allItems)
        afterState.forEach { (client, afterClock) ->
            val beforeClock = beforeState[client] ?: 0
            if (beforeClock == afterClock) return@forEach
            val clientItems = store.itemsForClient(client)
            if (clientItems.size < 2) return@forEach
            val changedIndex = store.firstItemEndingAfter(client, beforeClock)
            if (changedIndex >= clientItems.size) return@forEach
            val firstChangePosition = maxOf(changedIndex, 1)
            var index = clientItems.lastIndex
            while (index >= firstChangePosition) {
                var groupStart = index
                while (
                    groupStart > 0 &&
                    clientItems[groupStart - 1].canVirtuallyMerge(
                        clientItems[groupStart - 1],
                        clientItems[groupStart],
                        linkedPairs,
                    )
                ) {
                    groupStart--
                }
                if (groupStart < index) {
                    val representative = clientItems[groupStart].id
                    for (memberIndex in groupStart..index) {
                        representatives[clientItems[memberIndex].id] = representative
                    }
                } else {
                    representatives[clientItems[index].id] = clientItems[index].id
                }
                index = groupStart - 1
            }
        }
    }

    /** Mirrors the targeted right-then-left `_mergeStructs` phase for virtual unit items. */
    private fun mergeSplitCandidateRepresentatives(
        candidates: List<Id>,
        representatives: MutableMap<Id, Id>,
    ) {
        if (candidates.isEmpty()) return
        val candidateSet = candidates.toSet()
        val allItems = store.allItems()
        val linkedPairs = currentLogicalPairs(allItems)
        allItems.groupBy { item -> item.parent to item.parentSub }.forEach { (parentKey, _) ->
            val (parent, parentSub) = parentKey
            val logicalItems = if (parentSub == null) store.sequence(parent) else mapItemOrder(parent, parentSub)
            var index = 0
            while (index < logicalItems.size) {
                var end = index
                while (
                    end + 1 < logicalItems.size &&
                    logicalItems[end].canVirtuallyMerge(logicalItems[end], logicalItems[end + 1], linkedPairs)
                ) {
                    end++
                }
                val group = logicalItems.subList(index, end + 1)
                if (group.any { item -> candidateSet.any { candidate -> item.containsClock(candidate) } }) {
                    val representative = group.first().id
                    group.forEach { item -> representatives[item.id] = representative }
                }
                index = end + 1
            }
        }
    }

    private fun currentLogicalPairs(allItems: List<StoreItem>): Set<Pair<Id, Id>> = buildSet {
        allItems.groupBy { item -> item.parent to item.parentSub }.forEach { (parentKey, _) ->
            val (parent, parentSub) = parentKey
            val logicalItems = if (parentSub == null) store.sequence(parent) else mapItemOrder(parent, parentSub)
            logicalItems.zipWithNext().forEach { (left, right) -> add(left.id to right.id) }
        }
    }

    private fun StoreItem.containsClock(id: Id): Boolean =
        this.id.client == id.client &&
            id.clock >= this.id.clock &&
            id.clock < checkedClockAdd(this.id.clock, length, "virtual candidate item end")

    private fun StoreItem.canVirtuallyMerge(
        previous: StoreItem,
        right: StoreItem,
        linkedPairs: Set<Pair<Id, Id>>,
    ): Boolean {
        val previousLastId = Id(
            previous.id.client,
            checkedClockAdd(previous.id.clock, previous.length - 1, "virtual merged item last id"),
        )
        val mergeClass = content.virtualMergeClass() ?: return false
        return id.client == right.id.client &&
            checkedClockAdd(previous.id.clock, previous.length, "virtual merged item end") == right.id.clock &&
            (previous.id to right.id) in linkedPairs &&
            right.origin == previousLastId &&
            rightOrigin == right.rightOrigin &&
            deleted == right.deleted &&
            id !in redoneByOriginal &&
            right.id !in redoneByOriginal &&
            parent == right.parent &&
            parentSub == right.parentSub &&
            isGc == right.isGc &&
            mergeClass == right.content.virtualMergeClass()
    }

    private fun ItemContent.virtualMergeClass(): VirtualMergeClass? = when (this) {
        is ItemContent.Value -> when (value) {
            is YValue.BinaryValue,
            is YValue.TypeRef,
            is YValue.SubdocRef -> null
            else -> VirtualMergeClass.Any
        }
        is ItemContent.MapEntry -> when (value) {
            is YValue.BinaryValue,
            is YValue.TypeRef,
            is YValue.SubdocRef -> null
            else -> VirtualMergeClass.Any
        }
        is ItemContent.XmlNode -> VirtualMergeClass.Any
        is ItemContent.Text -> VirtualMergeClass.String
        is ItemContent.Deleted -> VirtualMergeClass.Deleted
        is ItemContent.TextEmbed,
        is ItemContent.TextFormat,
        is ItemContent.NativeTextFormat,
        is ItemContent.XmlType -> null
    }

    private enum class VirtualMergeClass {
        Any,
        String,
        Deleted,
    }

    internal fun changedParentsFor(transaction: Transaction): Set<String> =
        transaction.changedParents.filterTo(linkedSetOf()) { parent ->
            if (parent in transaction.preliminaryReplayedParents) return@filterTo false
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
            val sortedEvents = events.sortedBy { childEvent -> childEvent.path.size }
            ancestor.clearCache()
            val insertSet = sortedEvents.first().insertSet
            val deleteSet = sortedEvents.first().deleteSet
            val event = if (sortedEvents.size == 1) {
                val only = sortedEvents.single()
                if (only.target == ancestor) {
                    only.copy(deepEvents = sortedEvents)
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
                        deepEvents = sortedEvents,
                    )
                }
            } else {
                YEvent(
                    target = ancestor,
                    origin = transaction.origin,
                    update = update,
                    insertSet = insertSet,
                    deleteSet = deleteSet,
                    transaction = sortedEvents.firstOrNull()?.transaction,
                    currentTarget = ancestor,
                    changedTarget = ancestor,
                    deepEvents = sortedEvents,
                )
            }
            if (ancestor.hasDeepObservers) {
                callbacks.add { ancestor.emitDeep(event) }
            }
            if (ancestor.hasDeltaListeners && sortedEvents.any { it.target != ancestor }) {
                callbacks.add { ancestor.emitDelta(event) }
            }
        }
        callAllYksCallbacks(callbacks)
    }

    private fun captureParentBefore(parent: String, kindHint: RootKind) {
        val transaction = currentTransaction ?: return
        val existing = transaction.beforeParents[parent]
        val snapshotKind = if (kindHint == RootKind.XmlHook || knownParentKinds()[parent] == RootKind.XmlHook) {
            RootKind.Map
        } else {
            kindHint
        }
        if (
            (snapshotKind == RootKind.Map && existing?.mapSnapshot() != null) ||
            (snapshotKind != RootKind.Map && existing?.sequenceSnapshot()?.kind == snapshotKind)
        ) {
            return
        }
        val captured = when (snapshotKind) {
            RootKind.Map -> ParentSnapshot.MapSnapshot(
                values = visibleMap(parent),
                itemIds = visibleMapItemIds(parent),
            )
            RootKind.Array,
            RootKind.Text,
            RootKind.XmlFragment,
            RootKind.XmlElement,
            RootKind.XmlText -> ParentSnapshot.SequenceSnapshot(snapshotKind, visibleSequence(parent))
            RootKind.XmlHook -> error("XML hook snapshots use map semantics")
        }
        transaction.beforeParents[parent] = existing?.merge(captured) ?: captured
    }

    private fun createEvent(
        type: AbstractYType,
        transaction: Transaction,
        event: YTransactionEvent,
        before: ParentSnapshot?,
        effectiveInsertSet: IdSet,
        effectiveDeleteSet: DeleteSet,
        update: ByteArray,
    ): YEvent {
        val changedSubs = transaction.changedParentSubs[type.name].orEmpty()
        val changedKeys = changedSubs.filterNotNull().toSet()
        val mapChanges = before?.mapSnapshot()?.let { mapBefore ->
            diffMapChanges(
                before = mapBefore.values,
                after = visibleMap(type.name),
                beforeItemIds = mapBefore.itemIds,
                afterItemIds = visibleMapItemIds(type.name),
                changedKeys = changedKeys,
            )
        }.orEmpty()
        val sequenceBefore = before?.sequenceSnapshot()
        val arrayDelta = when {
            sequenceBefore == null -> emptyList()
            sequenceBefore.kind == RootKind.Array && type.kind == RootKind.Array -> {
                diffArrayDelta(type as YArray, effectiveInsertSet, effectiveDeleteSet)
            }
            sequenceBefore.kind == RootKind.XmlFragment && type.kind == RootKind.XmlFragment -> {
                diffXmlDelta(type as YXmlSharedType, effectiveInsertSet, effectiveDeleteSet)
            }
            sequenceBefore.kind == RootKind.XmlElement && type.kind == RootKind.XmlElement -> {
                diffXmlDelta(type as YXmlSharedType, effectiveInsertSet, effectiveDeleteSet)
            }
            else -> emptyList()
        }
        val textDelta = if (
            sequenceBefore != null &&
            sequenceBefore.kind == type.kind &&
            (type.kind == RootKind.Text || type.kind == RootKind.XmlText)
        ) {
            diffTextDelta(type as YText, sequenceBefore.items, effectiveInsertSet, effectiveDeleteSet)
        } else {
            YTextDelta()
        }
        // Yjs derives these flags from the transaction's changed subs, not from the
        // resulting delta. Preserve the signal even when a transaction inserts and then
        // deletes the same child, or adds and then removes the same attribute.
        val childListChanged = null in changedSubs
        return YEvent(
            target = type,
            origin = transaction.origin,
            update = update,
            insertSet = effectiveInsertSet,
            deleteSet = effectiveDeleteSet,
            transaction = event,
            keysChanged = changedKeys,
            mapChanges = mapChanges,
            mapDelta = mapChanges.toMapDelta(),
            name = mapChanges.keys.singleOrNull(),
            value = mapChanges.keys.singleOrNull()?.let { key -> typeAttribute(type.name, key) },
            arrayDelta = arrayDelta,
            textDelta = textDelta,
            childListChanged = childListChanged,
        )
    }

    private fun diffMapChanges(
        before: Map<String, YValue>,
        after: Map<String, YValue>,
        beforeItemIds: Map<String, Id>,
        afterItemIds: Map<String, Id>,
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
                oldPresent && newPresent &&
                    (oldValue != newValue || beforeItemIds[key] != afterItemIds[key]) -> key to YMapChange(
                    YMapChangeAction.Update,
                    oldValue?.let(::valueToAny),
                    newValue?.let(::valueToAny),
                )
                else -> null
            }
        }.toMap()
    }

    private fun diffArrayDelta(
        type: YArray,
        effectiveInsertSet: IdSet,
        effectiveDeleteSet: DeleteSet,
    ): List<YArrayDeltaOp> =
        diffSequenceEvent(type, effectiveInsertSet, effectiveDeleteSet) { item -> arrayItemValue(item) }

    private fun diffXmlDelta(
        type: YXmlSharedType,
        effectiveInsertSet: IdSet,
        effectiveDeleteSet: DeleteSet,
    ): List<YArrayDeltaOp> =
        diffSequenceEvent(type, effectiveInsertSet, effectiveDeleteSet) { item ->
            when (val content = item.content) {
                is ItemContent.XmlType -> typeFromXmlType(content)
                else -> content.toXmlEventJson(this)
            }
        }

    /**
     * Mirrors YEvent.changes' linked-list scan. Looking only at a common prefix/suffix turns
     * two disjoint edits into a delete-and-reinsert of the unchanged middle of the sequence.
     */
    private fun diffSequenceEvent(
        type: AbstractYType,
        effectiveInsertSet: IdSet,
        effectiveDeleteSet: DeleteSet,
        insertedValue: (StoreItem) -> Any?,
    ): List<YArrayDeltaOp> {
        val delta = mutableListOf<YArrayDeltaOp>()
        sequence(type.name)
            .filter { item -> item.content.kind == type.kind && item.countable }
            .forEach { item ->
                val added = effectiveInsertSet.hasId(item.id)
                val deleted = effectiveDeleteSet.contains(item.id)
                when {
                    item.deleted && deleted && !added -> delta.appendDelete(item.length.toDeltaLength())
                    !item.deleted && added -> delta.appendInsert(insertedValue(item))
                    !item.deleted -> delta.appendRetain(item.length.toDeltaLength())
                }
            }
        while (delta.lastOrNull()?.retain != null) delta.removeLast()
        return delta
    }

    /**
     * Text uses the same item-identity scan, with attribute diffs on retained content. This
     * preserves disjoint insert/delete/format spans in a single transaction.
     */
    private fun diffTextDelta(
        type: YText,
        before: List<StoreItem>,
        effectiveInsertSet: IdSet,
        effectiveDeleteSet: DeleteSet,
    ): YTextDelta {
        val delta = YTextDelta()
        val beforeById = before.associateBy { item -> item.id }

        sequence(type.name)
            .filter { item -> item.content.kind == type.kind }
            .forEach { item ->
                if (!item.countable) {
                    return@forEach
                }
                val added = effectiveInsertSet.hasId(item.id)
                val deleted = effectiveDeleteSet.contains(item.id)
                when {
                    item.deleted && deleted && !added -> {
                        delta.delete(item.length.toDeltaLength())
                    }
                    !item.deleted && added -> {
                        appendTextEventInsert(delta, item)
                    }
                    !item.deleted -> {
                        val oldContent = beforeById[item.id]?.content
                        val attributes = if (oldContent == null) {
                            emptyMap()
                        } else {
                            textAttributeDiff(
                                oldContent.textAttributesOrEmpty(),
                                item.content.textAttributesOrEmpty(),
                            )
                        }
                        delta.retain(item.length.toDeltaLength(), attributes)
                    }
                }
            }

        val trimmed = delta.ops.toMutableList()
        while (trimmed.lastOrNull()?.let { op -> op.retain != null && op.attributes.isEmpty() } == true) {
            trimmed.removeLast()
        }
        return YTextDelta(trimmed)
    }

    private fun appendTextEventInsert(delta: YTextDelta, item: StoreItem) {
        val attributes = textAttributesToPublic(item.content.textAttributesOrEmpty())
        when (val content = item.content) {
            is ItemContent.Text -> delta.insert(content.value, attributes)
            is ItemContent.TextEmbed -> delta.insertEmbed(valueToAny(content.value), attributes)
            is ItemContent.XmlType -> delta.insertEmbed(typeFromXmlType(content), attributes)
            else -> Unit
        }
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

    private fun MutableList<YArrayDeltaOp>.appendRetain(length: Int) {
        val last = lastOrNull()
        if (last?.retain != null) {
            this[lastIndex] = last.copy(retain = last.retain + length)
        } else {
            add(YArrayDeltaOp(retain = length))
        }
    }

    private fun MutableList<YArrayDeltaOp>.appendDelete(length: Int) {
        val last = lastOrNull()
        if (last?.delete != null) {
            this[lastIndex] = last.copy(delete = last.delete + length)
        } else {
            add(YArrayDeltaOp(delete = length))
        }
    }

    private fun MutableList<YArrayDeltaOp>.appendInsert(value: Any?) {
        val last = lastOrNull()
        if (last?.insert != null && last.attributes.isEmpty()) {
            this[lastIndex] = last.copy(insert = last.insert + value)
        } else {
            add(YArrayDeltaOp(insert = listOf(value)))
        }
    }

    private fun Long.toDeltaLength(): Int {
        require(this in 1..Int.MAX_VALUE.toLong()) { "event delta length exceeds Int range: $this" }
        return toInt()
    }

    private fun <T : AbstractYType> getOrCreate(name: String, kind: RootKind, factory: () -> T): T {
        val existing = rootTypes[name]
        if (existing != null) {
            require(existing.kind == kind) { "root type '$name' already exists as ${existing.kind}" }
            @Suppress("UNCHECKED_CAST")
            return existing as T
        }
        require(name !in nestedNames) { "nested type '$name' cannot be opened as a root type" }
        return factory().also { type ->
            rootTypes[name] = type
            unopenedRootEntries.remove(name)
            normalizeAmbiguousRootContent(name, kind)
        }
    }

    /**
     * A root ContentType does not carry its parent type on Yjs wire. The decoder therefore
     * keeps a best-effort kind until the receiver opens that root with a concrete getter.
     * Retag the complete direct sequence here; map attributes keep map semantics.
     */
    private fun normalizeAmbiguousRootContent(name: String, kind: RootKind) {
        var changed = false
        store.allItems()
            .asSequence()
            .filter { item -> item.parent == name && item.parentSub == null }
            .forEach { item ->
                val normalized = item.content.withRemoteParentKind(kind)
                if (normalized != item.content) {
                    checkNotNull(store.replaceContent(item.id, normalized)) {
                        "root item disappeared while materializing '$name'"
                    }
                    changed = true
                }
            }
        if (kind == RootKind.Text && changed) {
            reapplyTextFormats(name)
        }
    }

    private fun <T : AbstractYType> createNestedType(kind: RootKind, factory: (String) -> T): T {
        val name = nextNestedTypeName()
        return factory(name).also { type ->
            check(type.kind == kind) { "nested type factory returned ${type.kind}, expected $kind" }
            type.markDetached()
            type.reserve(this, name)
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
            val itemLength = rendererContentLength(renderer, item.toItemStruct(this))
                .toNonNegativeInt("rendered nested item length")
            if (!item.deleted || itemLength > 0) {
                when (val content = item.content) {
                    is ItemContent.Value -> content.value.nestedTypeRefPaths()
                    is ItemContent.TextEmbed -> content.value.nestedTypeRefPaths()
                    is ItemContent.XmlType -> listOf(emptyList<Any>() to content.ref.name)
                    else -> emptyList()
                }.forEach { (segments, nestedName) ->
                    children.add(listOf(renderedIndex) + segments to nestedName)
                }
            }
            renderedIndex = checkedClockAdd(
                renderedIndex.toLong(),
                itemLength.toLong(),
                "rendered nested index",
            ).toNonNegativeInt("rendered nested index")
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

    private fun typeFromRef(ref: YValue.TypeRef, xmlNodeName: String? = null): AbstractYType {
        referencedNestedNames.add(ref.name)
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
            RootKind.XmlElement -> YXmlElementType(this, ref.name, xmlNodeName ?: ref.name)
            RootKind.XmlHook -> YXmlHook(this, ref.name, xmlNodeName ?: ref.name)
            RootKind.XmlText -> YXmlTextType(this, ref.name)
        }.also { type ->
            type.markDecodedNested(this, ref.name, store.parentItemIds()[ref.name])
            nestedTypes[ref.name] = type
        }
    }

    internal fun typeFromXmlType(content: ItemContent.XmlType): AbstractYType =
        typeFromRef(content.ref, content.nodeName)

    private fun rememberNestedRefs(content: ItemContent) {
        when (content) {
            is ItemContent.Value -> rememberNestedRefs(content.value)
            is ItemContent.MapEntry -> rememberNestedRefs(content.value)
            is ItemContent.TextEmbed -> rememberNestedRefs(content.value)
            is ItemContent.XmlType -> {
                referencedNestedNames.add(content.ref.name)
                typeFromXmlType(content)
            }
            is ItemContent.Text,
            is ItemContent.TextFormat,
            is ItemContent.NativeTextFormat,
            is ItemContent.XmlNode,
            is ItemContent.Deleted -> Unit
        }
    }

    private fun rememberNestedRefs(value: YValue) {
        when (value) {
            is YValue.TypeRef -> {
                referencedNestedNames.add(value.name)
                typeFromRef(value)
            }
            is YValue.SubdocRef -> subdocFromRef(value)
            is YValue.ListValue -> value.value.forEach(::rememberNestedRefs)
            is YValue.MapValue -> value.value.values.forEach(::rememberNestedRefs)
            else -> Unit
        }
    }

    private fun attachAndReplayPreliminaryTypes(content: ItemContent, ownerId: Id) {
        content.nestedTypeRefNames().forEach { name ->
            val type = pendingPreliminaryAttachments.remove(name) ?: return@forEach
            type.integrateReserved(this, ownerId)
            currentTransaction?.preliminaryReplayedParents?.add(type.name)
            type.replayPreliminaryContent()
        }
    }

    private fun registerNestedTypeRefValue(ref: YValue.TypeRef): YValue.TypeRef {
        require(rootTypes[ref.name] == null) { "root shared types cannot be inserted as nested content" }
        require(ref.name != pendingStoreParentStack.lastOrNull()) { "shared type '${ref.name}' cannot contain itself" }
        require(!hasNestedTypeReference(ref.name)) { "shared type '${ref.name}' is already defined" }
        pendingNestedReferenceStack.lastOrNull()?.add(ref.name) ?: referencedNestedNames.add(ref.name)
        return ref
    }

    private fun registerNestedTypeValue(value: AbstractYType): AbstractYType {
        val reserved = value.binding as? YTypeBinding.Reserved
            ?: error("shared type must be detached or reserved before insertion; clone it explicitly to reuse content")
        require(reserved.doc === this) { "shared type is reserved for another document" }
        require(rootTypes[value.name] == null) { "root shared types cannot be inserted as nested content" }
        require(value.name != pendingStoreParentStack.lastOrNull()) { "shared type '${value.name}' cannot contain itself" }
        require(!hasNestedTypeReference(value.name)) { "shared type '${value.name}' is already defined" }
        pendingNestedReferenceStack.lastOrNull()?.add(value.name) ?: referencedNestedNames.add(value.name)
        nestedTypes[value.name] = value
        nestedNames.add(value.name)
        pendingPreliminaryAttachments[value.name] = value
        return value
    }

    private fun preparePreliminaryGraph(value: Any?) {
        val visiting = java.util.IdentityHashMap<AbstractYType, Boolean>()
        val visited = java.util.IdentityHashMap<AbstractYType, Boolean>()
        val ordered = mutableListOf<AbstractYType>()

        lateinit var visitValue: (Any?) -> Unit
        lateinit var visitType: (AbstractYType) -> Unit

        visitValue = { raw ->
            when (raw) {
                is AbstractYType -> visitType(raw)
                is YTextDelta -> raw.ops.forEach { op ->
                    visitValue(op.insert)
                    visitValue(op.attributes)
                }
                is YTextDeepDelta -> {
                    visitValue(raw.attrs)
                    visitValue(raw.delta)
                }
                is YArrayDeepDelta -> {
                    visitValue(raw.attrs)
                    raw.delta.forEach { op -> visitValue(op.insert) }
                }
                is YMapDeepDelta -> visitValue(raw.attrs)
                is YXmlFragmentDeepDelta -> {
                    visitValue(raw.attrs)
                    raw.delta.forEach { op -> visitValue(op.insert) }
                }
                is YXmlElementDeepDelta -> {
                    visitValue(raw.attrs)
                    raw.children.forEach { child -> visitValue(child) }
                }
                is Map<*, *> -> raw.forEach { (key, nested) ->
                    require(key is String) { "YValue map keys must be strings" }
                    visitValue(nested)
                }
                is Iterable<*> -> raw.forEach { nested -> visitValue(nested) }
                is Array<*> -> raw.forEach { nested -> visitValue(nested) }
                else -> Unit
            }
        }

        visitType = { type ->
            require(visiting[type] != true) { "shared type graph contains a cycle" }
            require(visited[type] != true) { "shared type instance occurs more than once in the inserted graph" }
            when (val current = type.binding) {
                YTypeBinding.Detached -> Unit
                is YTypeBinding.Reserved -> {
                    require(current.doc === this) { "shared type is reserved for another document" }
                    require(!hasNestedTypeReference(current.name)) { "shared type '${current.name}' is already defined" }
                }
                is YTypeBinding.Root -> require(false) { "root shared types cannot be inserted as nested content" }
                is YTypeBinding.Nested -> require(false) {
                    "shared type is already integrated; clone it explicitly before reinsertion"
                }
            }
            validatePreliminaryContent(type)
            visiting[type] = true
            ordered.add(type)
            type.preliminaryGraphValues().forEach { nested -> visitValue(nested) }
            visiting.remove(type)
            visited[type] = true
        }

        visitValue(value)
        ordered.forEach { type ->
            if (type.binding is YTypeBinding.Detached) {
                val reservedName = nextNestedTypeName()
                type.reserve(this, reservedName)
                nestedTypes[reservedName] = type
                nestedNames.add(reservedName)
            }
            pendingPreliminaryAttachments[type.name] = type
        }
    }

    private fun validatePreliminaryContent(type: AbstractYType) {
        when (type) {
            is YUnopenedRoot -> error("an unopened root cannot be inserted as preliminary content")
            is YArray -> {
                type.preliminaryList.forEach(::validateStoreValue)
                type.preliminaryMap.values.forEach(::validateStoreValue)
            }
            is YMap -> type.preliminaryMap.values.forEach(::validateStoreValue)
            is YText -> type.preliminaryOperationValues.forEach(::validateStoreValue)
            is YXmlElementType,
            is YXmlFragment -> {
                type.preliminaryList.forEach { child ->
                    require(child is AbstractYType || child is YXmlNode) {
                        "unsupported preliminary XML child: ${child?.let { it::class.qualifiedName }}"
                    }
                }
                type.preliminaryMap.values.forEach(::validateStoreValue)
            }
        }
    }

    private fun hasNestedTypeReference(name: String): Boolean =
        name in referencedNestedNames ||
            pendingNestedReferenceStack.any { pending -> name in pending } ||
            store.allItems().any { item -> item.content.nestedTypeRefNames().contains(name) }

    private fun ItemContent.nestedTypeRefNames(): Set<String> = when (this) {
        is ItemContent.Value -> value.nestedTypeRefNames()
        is ItemContent.MapEntry -> value.nestedTypeRefNames()
        is ItemContent.TextEmbed -> value.nestedTypeRefNames()
        is ItemContent.XmlType -> setOf(ref.name)
        is ItemContent.Text,
        is ItemContent.TextFormat,
        is ItemContent.NativeTextFormat,
        is ItemContent.XmlNode,
        is ItemContent.Deleted -> emptySet()
    }

    private fun YValue.nestedTypeRefNames(): Set<String> = when (this) {
        is YValue.TypeRef -> setOf(name)
        is YValue.ListValue -> value.flatMap { nested -> nested.nestedTypeRefNames() }.toSet()
        is YValue.MapValue -> value.values.flatMap { nested -> nested.nestedTypeRefNames() }.toSet()
        else -> emptySet()
    }

    private fun registerSubdocValue(value: YDoc): YValue.SubdocRef {
        require(value !== this) { "a document cannot contain itself as a subdoc" }
        subdocsByInstanceId[value.subdocInstanceId] = value
        value.parentDocs.add(this)
        return YValue.SubdocRef(
            guid = value.guid,
            gc = value.gc,
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
        val shouldLoad = shouldLoadOverride ?: if (existing?.isDestroyed == true) {
            false
        } else {
            ref.shouldLoad || ref.autoLoad
        }
        return YDoc(
            guid = ref.guid,
            gc = ref.gc,
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
        val added = (transaction.addedItems.flatMap { subdocRefs(it.content) }.map(::subdocFromRef) +
            transaction.addedSubdocs).toMutableList()
        val loadedFromAdded = added.filter { it.shouldLoad }
        val removed = (transaction.deletedItems.flatMap { subdocRefs(it.content) }.map(::subdocFromRef) +
            transaction.removedSubdocs).toMutableList()
        removed.toList().forEach { subdoc ->
            val addedIndex = added.indexOfFirst { candidate -> candidate.subdocInstanceId == subdoc.subdocInstanceId }
            if (addedIndex >= 0) {
                added.removeAt(addedIndex)
                removed.remove(subdoc)
            }
        }
        added.forEach { subdoc ->
            subdoc.clientID = clientID
            if (subdoc.collectionid == null) {
                subdoc.collectionid = collectionid
            }
        }
        val loaded = (loadedFromAdded + transaction.loadedSubdocs)
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
        is ItemContent.NativeTextFormat,
        is ItemContent.XmlType,
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
        items.groupBy { restore -> restore.item.parent }.forEach { (parent, restores) ->
            val restoredIds = restores.mapTo(hashSetOf()) { restore -> restore.item.id }
            var position = 0
            sequence(parent).forEach { item ->
                if (!item.deleted || item.id in restoredIds) {
                    if (item.id in restoredIds) positions[item.id] = position
                    position++
                }
            }
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
        val mergeStructs = mutableListOf<Id>()
        val addedSubdocs = linkedSetOf<YDoc>()
        val removedSubdocs = linkedSetOf<YDoc>()
        val loadedSubdocs = linkedSetOf<YDoc>()
        val meta: MutableMap<Any?, Any?> = linkedMapOf()
        val changedParents = linkedSetOf<String>()
        val changedParentSubs = linkedMapOf<String, MutableSet<String?>>()
        val preliminaryReplayedParents = linkedSetOf<String>()
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

        fun markChanged(parent: String, parentSub: String?) {
            changedParents.add(parent)
            changedParentSubs.getOrPut(parent) { linkedSetOf() }.add(parentSub)
        }
    }

    companion object {
        private val random = SecureRandom()

        fun generateNewClientId(): Long = randomClientId()

        private fun randomClientId(excluding: Set<Long> = emptySet()): Long {
            var value: Long
            do {
                // Yjs client IDs are unsigned 32-bit integers (`random.uint32`).
                value = random.nextInt().toLong() and 0xffff_ffffL
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

    fun adds(id: Id): Boolean = id.clock >= (beforeState[id.client] ?: 0)

    fun adds(client: Long, clock: Long): Boolean = adds(Id(client, clock))

    fun deletes(id: Id): Boolean = deleteSet.contains(id)

    fun deletes(client: Long, clock: Long): Boolean = deletes(Id(client, clock))

    internal fun registerSplitMergeCandidate(item: StoreItem) {
        transaction.mergeStructs.add(item.id)
    }

    internal fun addChangedType(type: AbstractYType, parentSub: String? = null) {
        require(type.doc === doc) { "type must belong to this transaction's document" }
        val typeItemId = doc.typeRefItemId(type)
        if (typeItemId == null || transaction.addedItems.none { item -> item.id == typeItemId }) {
            transaction.markChanged(type.name, parentSub)
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
            RootKind.XmlFragment,
            RootKind.XmlElement -> arrayDelta
            RootKind.Map,
            RootKind.XmlHook -> mapDelta
            RootKind.Text,
            RootKind.XmlText -> textDelta
        }

    val deltaDeep: Any
        get() = getDelta(deep = true)

    fun getDelta(deep: Boolean = false, renderer: AbstractRenderer = target.activeRenderer): Any {
        if (!deep) return delta
        val itemsToRender = eventItemsToRender(renderer)
        if (!itemsToRender.isEmpty()) {
            val modified = computeModifiedFromItems(target.doc, itemsToRender)
            val options = DeepDeltaRenderOptions(
                renderer = renderer,
                itemsToRender = itemsToRender,
                retainDeletes = true,
                insertedItems = insertSet,
                modified = modified,
            )
            when (target.kind) {
                RootKind.Array -> return (target.renderDeepDelta(options) as YArrayDeepDelta).delta
                RootKind.Text,
                RootKind.XmlText -> return (target.renderDeepDelta(options) as YTextDeepDelta).delta
                RootKind.XmlFragment -> return (target.renderDeepDelta(options) as YXmlFragmentDeepDelta).delta
                RootKind.XmlElement -> return (target.renderDeepDelta(options) as YXmlElementDeepDelta).children
                RootKind.Map,
                RootKind.XmlHook -> Unit
            }
        }
        val options = DeepDeltaRenderOptions(renderer = renderer)
        return when (target.kind) {
            RootKind.Array,
            RootKind.XmlFragment,
            RootKind.XmlElement -> arrayDelta.toDeepDeltaValues(options)
            RootKind.Map,
            RootKind.XmlHook -> mapDelta.toDeepDeltaValues(options)
            RootKind.Text,
            RootKind.XmlText -> textDelta.toDeepDeltaValues(options)
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
    is ItemContent.XmlType -> ref
    is ItemContent.Text,
    is ItemContent.TextEmbed,
    is ItemContent.TextFormat,
    is ItemContent.NativeTextFormat,
    is ItemContent.XmlNode,
    is ItemContent.Deleted -> null
}

private fun ItemContent.withRemoteParentKind(kind: RootKind): ItemContent = when (this) {
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

private fun StoreItem.asRemoteGc(): StoreItem = copy(
    origin = null,
    rightOrigin = null,
    parent = "__yjs_gc__:${id.client}",
    parentSub = null,
    content = ItemContent.Deleted(content.kind, length),
    deleted = true,
    isGc = true,
    unresolvedParent = null,
)

private fun StoreItem.containsClockId(id: Id): Boolean =
    id.client == this.id.client &&
        id.clock >= this.id.clock &&
        id.clock < checkedClockAdd(this.id.clock, length)

private val textFormatApplicationOrder: Comparator<StoreItem> =
    compareByDescending<StoreItem> { it.id.client }.thenBy { it.id.clock }

private fun List<StoreItem>.withNativeTextFormatting(): MutableList<StoreItem> {
    val activeAttributes = linkedMapOf<String, YValue>()
    return buildList {
        this@withNativeTextFormatting.forEach { item ->
            when (val content = item.content) {
                is ItemContent.NativeTextFormat -> activeAttributes[content.key] = content.value
                is ItemContent.Text,
                is ItemContent.TextEmbed,
                is ItemContent.XmlType -> add(
                    item.copy(content = content.withTextAttributesOrNull(content.effectiveTextAttributes(activeAttributes))!!),
                )
                else -> Unit
            }
        }
    }.toMutableList()
}

private fun ItemContent.retargetTextFormat(restoredByOriginal: Map<Id, Id>): ItemContent = when (this) {
    is ItemContent.TextFormat -> copy(target = restoredByOriginal[target] ?: target)
    else -> this
}

private fun ItemContent.textAttributesOrEmpty(): Map<String, YValue> = when (this) {
    is ItemContent.Text -> attributes
    is ItemContent.TextEmbed -> attributes
    is ItemContent.XmlType -> attributes
    else -> emptyMap()
}

private fun ItemContent.baseTextAttributesOrEmpty(): Map<String, YValue> = when (this) {
    is ItemContent.Text -> baseAttributes
    is ItemContent.TextEmbed -> baseAttributes
    is ItemContent.XmlType -> baseAttributes
    else -> emptyMap()
}

private fun ItemContent.withTextAttributesOrNull(attributes: Map<String, YValue>): ItemContent? = when (this) {
    is ItemContent.Text -> copy(attributes = attributes)
    is ItemContent.TextEmbed -> copy(attributes = attributes)
    is ItemContent.XmlType -> copy(attributes = attributes)
    else -> null
}

private fun ItemContent.effectiveTextAttributes(activeAttributes: Map<String, YValue>): Map<String, YValue> {
    val attributes = baseTextAttributesOrEmpty().toMutableMap()
    activeAttributes.forEach { (key, value) ->
        if (value == YValue.Null) {
            attributes.remove(key)
        } else {
            attributes[key] = value
        }
    }
    return attributes.toSortedMap()
}

private fun ItemContent.isTextFormatControl(): Boolean =
    this is ItemContent.TextFormat || this is ItemContent.NativeTextFormat

private fun ItemContent.isTextCountable(): Boolean =
    this is ItemContent.Text ||
        this is ItemContent.TextEmbed ||
        (this is ItemContent.XmlType && kind in setOf(RootKind.Text, RootKind.XmlText))

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
    this is ItemContent.XmlType && other is ItemContent.XmlType -> ref == other.ref
    else -> false
}

internal fun callAllYksCallbacks(callbacks: Iterable<() -> Unit>) =
    callAllYksCallbacks(callbacks) { callback -> callback() }

internal fun <T> callAllYksCallbacks(values: Iterable<T>, callback: (T) -> Unit) {
    var firstError: Throwable? = null
    values.forEach { value ->
        try {
            callback(value)
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
    val attributes: Map<String, Any?> = emptyMap(),
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

    fun adds(id: Id): Boolean = id.clock >= (beforeState[id.client] ?: 0)

    fun adds(client: Long, clock: Long): Boolean = adds(Id(client, clock))

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

typealias Transaction = YTransaction
typealias TransactionEvent = YTransactionEvent

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
    data class MapSnapshot(
        val values: Map<String, YValue>,
        val itemIds: Map<String, Id>,
    ) : ParentSnapshot()
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
