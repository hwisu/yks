package dev.yks

import java.security.SecureRandom
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicReference
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

internal data class YTransactionMutationSummary(
    val deletedItems: List<StoreItem>,
    val changedParentTypes: Set<AbstractYType>,
)

/**
 * Mutable Yjs-compatible document.
 *
 * Like an upstream Yjs document, a [YDoc] and its attached shared types are thread-confined.
 * The default runtime policy binds on first CRDT access and rejects every other thread. Encoded
 * updates, state vectors, and copied value snapshots are safe hand-off boundaries.
 */
public class YDoc(
    clientId: Long = randomClientId(),
    public var guid: String = randomGuid(),
    public var collectionId: String? = null,
    public var gc: Boolean = true,
    public var gcFilter: (AbstractStruct) -> Boolean = { true },
    public var meta: Any? = null,
    shouldLoad: Boolean = true,
    public var autoLoad: Boolean = false,
    public var isSuggestionDoc: Boolean = false,
) {
    public constructor(options: YDocOptions) : this(options, YDocRuntimeOptions.DEFAULT)

    public constructor(options: YDocOptions, rootSchemas: YRootSchemaRegistry) :
        this(options, YDocRuntimeOptions.DEFAULT, rootSchemas)

    public constructor(options: YDocOptions, runtimeOptions: YDocRuntimeOptions) : this(
        clientId = options.clientId,
        guid = options.guid,
        collectionId = options.collectionId,
        gc = options.gc,
        gcFilter = options.gcFilter,
        meta = options.meta,
        shouldLoad = options.shouldLoad,
        autoLoad = options.autoLoad,
        isSuggestionDoc = options.isSuggestionDoc,
    ) {
        configuredUpdateLimits = runtimeOptions.updateLimits
        configuredThreadAccessPolicy = runtimeOptions.threadAccessPolicy
        configuredStandardUpdatePolicy = runtimeOptions.standardUpdatePolicy
    }

    public constructor(
        options: YDocOptions,
        runtimeOptions: YDocRuntimeOptions,
        rootSchemas: YRootSchemaRegistry,
    ) : this(options, runtimeOptions) {
        configuredRootSchemas = rootSchemas
    }

    private val ownerThread = AtomicReference<Thread?>()
    // Only the owning thread writes this same-thread fast path. Foreign threads always miss it
    // and fall back to the atomic owner below, preserving the confinement check without paying an
    // AtomicReference read on every hot scalar lookup.
    private var ownerThreadFast: Thread? = null
    private val activeAccessThread = AtomicReference<Thread?>()
    private val accessDepth = ThreadLocal<Int>()
    private var configuredUpdateLimits: YUpdateLimits = YUpdateLimits.DEFAULT
    private var configuredThreadAccessPolicy: YThreadAccessPolicy = YThreadAccessPolicy.ENFORCED
    private var configuredStandardUpdatePolicy: YStandardUpdatePolicy =
        YStandardUpdatePolicy.ALLOW_LOSSLESS_EXTENSIONS
    private var configuredRootSchemas: YRootSchemaRegistry = YRootSchemaRegistry.EMPTY
    private val resolvedRootSchemas = linkedMapOf<String, YRootSchema?>()

    public val updateLimits: YUpdateLimits get() = configuredUpdateLimits
    public val threadAccessPolicy: YThreadAccessPolicy get() = configuredThreadAccessPolicy
    public val standardUpdatePolicy: YStandardUpdatePolicy get() = configuredStandardUpdatePolicy
    public val rootSchemas: YRootSchemaRegistry get() = configuredRootSchemas

    internal fun ensureThreadAccess() {
        val current = Thread.currentThread()
        if (ownerThreadFast === current) return
        val established = ownerThread.get()
        if (established === current) {
            ownerThreadFast = current
            return
        }
        when (threadAccessPolicy) {
            YThreadAccessPolicy.UNCHECKED -> return
            YThreadAccessPolicy.EXTERNALLY_SERIALIZED -> {
                val active = activeAccessThread.get()
                if (active == null || active === current) return
                throw YksConcurrentAccessException(active.name, current.name)
            }
            YThreadAccessPolicy.ENFORCED -> {
                if (established == null && ownerThread.compareAndSet(null, current)) {
                    ownerThreadFast = current
                    return
                }
                val owner = checkNotNull(ownerThread.get())
                throw YksThreadConfinementException(owner.name, current.name)
            }
        }
    }

    /**
     * Runs one logical document operation under the configured access policy.
     *
     * [YThreadAccessPolicy.EXTERNALLY_SERIALIZED] deliberately uses a fail-fast ownership token,
     * not a blocking lock. Server integrations keep control of scheduling and accidental overlap
     * cannot pin a request thread waiting for another coroutine.
     */
    internal fun <T> withDocumentAccess(block: () -> T): T {
        if (threadAccessPolicy != YThreadAccessPolicy.EXTERNALLY_SERIALIZED) {
            ensureThreadAccess()
            return block()
        }

        val current = Thread.currentThread()
        val depth = accessDepth.get() ?: 0
        if (depth == 0 && !activeAccessThread.compareAndSet(null, current)) {
            val active = checkNotNull(activeAccessThread.get())
            throw YksConcurrentAccessException(active.name, current.name)
        }
        accessDepth.set(depth + 1)
        return try {
            block()
        } finally {
            if (depth == 0) {
                accessDepth.remove()
                check(activeAccessThread.compareAndSet(current, null)) {
                    "YDoc access ownership changed unexpectedly"
                }
            } else {
                accessDepth.set(depth)
            }
        }
    }

    public var clientId: Long = clientId.also { require(it >= 0) { "clientId must be non-negative" } }
        set(value) {
            ensureThreadAccess()
            require(value >= 0) { "clientId must be non-negative" }
            field = value
        }
    public var clientID: Long
        get() = clientId
        set(value) {
            clientId = value
        }
    public var collectionid: String?
        get() = collectionId
        set(value) {
            collectionId = value
        }
    public var shouldLoad: Boolean = shouldLoad
        private set
    public var isLoaded: Boolean = false
        private set
    public var isSynced: Boolean = false
        private set
    public var isDestroyed: Boolean = false
        private set
    public val whenLoaded: CompletableFuture<YDoc> = CompletableFuture()
    public var whenSynced: CompletableFuture<YDoc> = CompletableFuture()
        private set
    public var cleanupFormatting: Boolean = !isSuggestionDoc
        set(value) {
            ensureThreadAccess()
            field = value
        }

    public val `$type`: (Any?) -> Boolean get() = `$ydoc`

    public val store: StructStore = StructStore(this)
    private data class CachedFullUpdate(
        val storeVersion: Long,
        val parentKinds: Map<String, RootKind>,
        val bytes: ByteArray,
    )

    private data class AtomicTransactionSnapshot(
        val store: StructStoreSnapshot,
        val clientId: Long,
        val rootTypes: Map<String, AbstractYType>,
        val unopenedRootEntries: Map<String, YUnopenedRoot>,
        val nestedTypes: Map<String, AbstractYType>,
        val referencedNestedNames: Set<String>,
        val pendingNestedReferenceStack: List<Set<String>>,
        val pendingStoreParentStack: List<String?>,
        val pendingPreliminaryAttachments: Map<String, AbstractYType>,
        val subdocsByInstanceId: Map<String, YDoc>,
        val pendingItems: List<StoreItem>,
        val pendingDeletes: DeleteSet,
        val redoneByOriginal: Map<Id, Id>,
        val redoneRangeEndByOriginal: Map<Id, Id>,
        val keptItems: Set<Id>,
        val nestedTypeCounter: Long,
        val cachedFullV1Update: CachedFullUpdate?,
        val typeStates: Map<AbstractYType, YTypeMutableState>,
    )
    private var cachedFullV1Update: CachedFullUpdate? = null
    private val rootTypes = linkedMapOf<String, AbstractYType>()
    private val unopenedRootEntries = linkedMapOf<String, YUnopenedRoot>()
    private val snapshotInterestedTypes = linkedMapOf<String, AbstractYType>()
    // Monotonic optimization hint: only types whose scalar length has actually been read need
    // per-struct callbacks. Open roots used solely for string reads otherwise pay no update cost.
    private val maintainedLengthParents = hashSetOf<String>()
    private val shareView: Map<String, AbstractYType> = object : AbstractMap<String, AbstractYType>() {
        override val entries: Set<Map.Entry<String, AbstractYType>>
            get() = rootNames().mapTo(linkedSetOf()) { name ->
                java.util.AbstractMap.SimpleImmutableEntry(
                    name,
                    checkNotNull(sharedRootEntry(name)) { "shared root '$name' disappeared while enumerating it" },
                )
            }

        override fun get(key: String): AbstractYType? {
            ensureThreadAccess()
            return sharedRootEntry(key)
        }

        override fun containsKey(key: String): Boolean {
            ensureThreadAccess()
            return hasSharedRoot(key)
        }
    }
    private val nestedTypes = linkedMapOf<String, AbstractYType>()
    private val referencedNestedNames = linkedSetOf<String>()
    private var visibleSubdocRefsCache: List<YValue.SubdocRef>? = null
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
    private val redoneStartsByClient = mutableMapOf<Long, java.util.TreeMap<Long, Id>>()
    private val redoneRangeEndsByClient = mutableMapOf<Long, java.util.TreeMap<Long, Id>>()
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
    private var pendingAtomicPreflightSnapshot: AtomicTransactionSnapshot? = null
    private val pendingAtomicPreflightTypeStates =
        java.util.IdentityHashMap<AbstractYType, YTypeMutableState>()
    private var isEmittingTransactions = false
    private var isEmittingTransactionEvent = false
    private val pendingTransactionEmits = ArrayDeque<Transaction>()
    private var nestedTypeCounter = 0L
    internal val subdocInstanceId: String = randomGuid()

    public val share: Map<String, AbstractYType>
        get() {
            ensureThreadAccess()
            return shareView
        }

    @get:JvmName("getSubdocsProperty")
    public val subdocs: Set<YDoc>
        get() = getSubdocs()

    public operator fun get(name: String): AbstractYType {
        ensureThreadAccess()
        return rootType(name)
            ?: materializeConfiguredRoot(name)
            ?: unopenedRoot(name)
            ?: createUnopenedRoot(name)
    }

    public fun get(name: String, kind: RootKind): AbstractYType = when (kind) {
        RootKind.Array -> getArray(name)
        RootKind.Map -> getMap(name)
        RootKind.Text -> getText(name)
        RootKind.XmlFragment -> getXmlFragment(name)
        RootKind.XmlElement -> getXmlElement(name)
        RootKind.XmlHook -> getXmlHook(name)
        RootKind.XmlText -> getXmlText(name)
    }

    public fun get(name: String, typeRef: Int): AbstractYType = get(name, rootKindFromTypeRefId(typeRef))

    public fun getOrNull(name: String): AbstractYType? {
        ensureThreadAccess()
        return rootType(name) ?: materializeConfiguredStoredRoot(name) ?: unopenedRoot(name)
    }

    /**
     * Replace the optional schema lookup. Existing roots are validated and materialized, while
     * schemas for absent roots stay lazy and do not change [share] or [rootNames].
     */
    public fun installRootSchemas(registry: YRootSchemaRegistry): YDoc = withDocumentAccess {
        val rootNames = rootNames()
        val resolved = rootNames.mapNotNull { name -> registry.resolve(name)?.let { schema -> name to schema } }
        resolved.forEach { (name, schema) -> requireCompatibleRootSchema(name, schema) }
        configuredRootSchemas = registry
        resolvedRootSchemas.clear()
        resolved.forEach { (name, schema) ->
            resolvedRootSchemas[name] = schema
            materializeRootSchema(name, schema)
        }
        this
    }

    /** Register one schema without creating an absent root. */
    public fun registerRootSchema(name: String, schema: YRootSchema): YDoc = withDocumentAccess {
        requireCompatibleRootSchema(name, schema)
        configuredRootSchemas = configuredRootSchemas.withSchema(name, schema)
        resolvedRootSchemas[name] = schema
        if (name in rootNames()) materializeRootSchema(name, schema)
        this
    }

    public fun get(): YArray = getArray("")

    public fun getArray(name: String = ""): YArray = getOrCreate(name, RootKind.Array) { YArray(this, name) }

    public fun getMap(name: String = ""): YMap = getOrCreate(name, RootKind.Map) { YMap(this, name) }

    public fun getText(name: String = ""): YText = getOrCreate(name, RootKind.Text) { YText(this, name) }

    public fun getXmlFragment(name: String = ""): YXmlFragment =
        getOrCreate(name, RootKind.XmlFragment) { YXmlFragment(this, name) }

    public fun getXmlElement(name: String = "", nodeName: String = "UNDEFINED"): YXmlElementType {
        val schema = resolveRootSchema(name) as? YRootSchema.XmlElement
        val resolvedNodeName = schema?.nodeName?.takeIf { nodeName == "UNDEFINED" } ?: nodeName
        requireConfiguredXmlName(name, RootKind.XmlElement, resolvedNodeName)
        return getOrCreate(name, RootKind.XmlElement) { YXmlElementType(this, name, resolvedNodeName) }
    }

    public fun getXmlHook(name: String = "", hookName: String = "UNDEFINED"): YXmlHook {
        val schema = resolveRootSchema(name) as? YRootSchema.XmlHook
        val resolvedHookName = schema?.hookName?.takeIf { hookName == "UNDEFINED" } ?: hookName
        requireConfiguredXmlName(name, RootKind.XmlHook, resolvedHookName)
        return getOrCreate(name, RootKind.XmlHook) { YXmlHook(this, name, resolvedHookName) }
    }

    public fun getXmlText(name: String = ""): YXmlTextType =
        getOrCreate(name, RootKind.XmlText) { YXmlTextType(this, name) }

    public fun createArray(): YArray = createNestedType(RootKind.Array) { nestedName -> YArray(this, nestedName) }

    public fun createMap(): YMap = createNestedType(RootKind.Map) { nestedName -> YMap(this, nestedName) }

    public fun createText(): YText = createNestedType(RootKind.Text) { nestedName -> YText(this, nestedName) }

    public fun createXmlFragment(): YXmlFragment =
        createNestedType(RootKind.XmlFragment) { nestedName -> YXmlFragment(this, nestedName) }

    public fun createXmlElement(nodeName: String): YXmlElementType =
        createXmlElementType(nodeName, RootKind.XmlElement)

    public fun createXmlHook(hookName: String): YXmlHook =
        createNestedType(RootKind.XmlHook) { nestedName -> YXmlHook(this, nestedName, hookName) }

    public fun createXmlText(): YXmlTextType =
        createXmlTextType()

    internal fun createXmlElementType(nodeName: String, kind: RootKind): YXmlElementType =
        createNestedType(kind) { nestedName -> YXmlElementType(this, nestedName, nodeName, kind) }

    internal fun createXmlTextType(): YXmlTextType =
        createNestedType(RootKind.XmlText) { nestedName -> YXmlTextType(this, nestedName) }

    public fun toJson(): Map<String, Any?> {
        ensureThreadAccess()
        return rootTypes
            .mapValues { (_, type) -> type.toJson() }
            .inJavaScriptObjectKeyOrder()
    }

    public fun toJSON(): Map<String, Any?> {
        ensureThreadAccess()
        return rootTypes
            .mapValues { (_, type) -> type.toJSON() }
            .inJavaScriptObjectKeyOrder()
    }

    public fun load() {
        ensureThreadAccess()
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

    public fun sync(synced: Boolean = true) {
        ensureThreadAccess()
        emit("sync", YDocEvent(name = "sync", synced = synced))
    }

    public fun destroy() {
        withDocumentAccess(::destroyWithinAccess)
    }

    private fun destroyWithinAccess() {
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

    public fun observeSubdocs(listener: (YSubdocEvent) -> Unit): Subscription {
        ensureThreadAccess()
        subdocObservers.add(listener)
        return confinedSubscription { subdocObservers.remove(listener) }
    }

    public fun onSubdocs(listener: (YSubdocEvent, YDoc, YTransactionEvent?) -> Unit): Subscription {
        ensureThreadAccess()
        subdocEventListeners.add(listener)
        return confinedSubscription { subdocEventListeners.remove(listener) }
    }

    public fun on(eventName: String, listener: (YDocEvent) -> Unit): Subscription {
        ensureThreadAccess()
        val listeners = eventListeners.getOrPut(eventName) { mutableListOf() }
        listeners.add(listener)
        return confinedSubscription { off(eventName, listener) }
    }

    public fun onDoc(eventName: String, listener: (YDoc) -> Unit): Subscription {
        ensureThreadAccess()
        require(eventName in docOnlyEventNames) { "event '$eventName' does not provide document callback arguments" }
        val listeners = docOnlyEventListeners.getOrPut(eventName) { mutableListOf() }
        listeners.add(listener)
        return confinedSubscription { offDoc(eventName, listener) }
    }

    public fun on(eventName: String, listener: (ByteArray, Any?, YDoc, YTransactionEvent?) -> Unit): Subscription =
        when (eventName) {
            "update" -> onUpdate(listener)
            "updateV2" -> onUpdateV2(listener)
            "updateLossless" -> onUpdateLossless(listener)
            "updateV2Lossless" -> onUpdateV2Lossless(listener)
            else -> error("event '$eventName' does not provide update callback arguments")
        }

    public fun onSync(listener: (Boolean, YDoc) -> Unit): Subscription {
        ensureThreadAccess()
        syncEventListeners.add(listener)
        return confinedSubscription { offSync(listener) }
    }

    public fun on(eventName: String, listener: (YTransactionEvent, YDoc) -> Unit): Subscription {
        ensureThreadAccess()
        require(eventName in transactionEventNames) {
            "event '$eventName' does not provide transaction callback arguments"
        }
        val listeners = transactionEventListeners.getOrPut(eventName) { mutableListOf() }
        listeners.add(listener)
        return confinedSubscription { off(eventName, listener) }
    }

    public fun onAfterAllTransactions(listener: (YDoc, List<YTransactionEvent>) -> Unit): Subscription {
        ensureThreadAccess()
        afterAllTransactionsEventListeners.add(listener)
        return confinedSubscription { offAfterAllTransactions(listener) }
    }

    public fun on(eventName: String, listener: (YSubdocEvent, YDoc, YTransactionEvent?) -> Unit): Subscription {
        require(eventName == "subdocs") { "event '$eventName' does not provide subdoc callback arguments" }
        return onSubdocs(listener)
    }

    public fun once(eventName: String, listener: (YDocEvent) -> Unit): Subscription {
        lateinit var subscription: Subscription
        val onceListener: (YDocEvent) -> Unit = { event ->
            subscription.close()
            listener(event)
        }
        subscription = on(eventName, onceListener)
        return subscription
    }

    public fun onceDoc(eventName: String, listener: (YDoc) -> Unit): Subscription {
        lateinit var subscription: Subscription
        val onceListener: (YDoc) -> Unit = { doc ->
            subscription.close()
            listener(doc)
        }
        subscription = onDoc(eventName, onceListener)
        return subscription
    }

    public fun once(eventName: String, listener: (ByteArray, Any?, YDoc, YTransactionEvent?) -> Unit): Subscription {
        lateinit var subscription: Subscription
        val onceListener: (ByteArray, Any?, YDoc, YTransactionEvent?) -> Unit = { update, origin, doc, transaction ->
            subscription.close()
            listener(update, origin, doc, transaction)
        }
        subscription = on(eventName, onceListener)
        return subscription
    }

    public fun onceSync(listener: (Boolean, YDoc) -> Unit): Subscription {
        lateinit var subscription: Subscription
        val onceListener: (Boolean, YDoc) -> Unit = { synced, doc ->
            subscription.close()
            listener(synced, doc)
        }
        subscription = onSync(onceListener)
        return subscription
    }

    public fun once(eventName: String, listener: (YTransactionEvent, YDoc) -> Unit): Subscription {
        lateinit var subscription: Subscription
        val onceListener: (YTransactionEvent, YDoc) -> Unit = { transaction, doc ->
            subscription.close()
            listener(transaction, doc)
        }
        subscription = on(eventName, onceListener)
        return subscription
    }

    public fun onceAfterAllTransactions(listener: (YDoc, List<YTransactionEvent>) -> Unit): Subscription {
        lateinit var subscription: Subscription
        val onceListener: (YDoc, List<YTransactionEvent>) -> Unit = { doc, transactions ->
            subscription.close()
            listener(doc, transactions)
        }
        subscription = onAfterAllTransactions(onceListener)
        return subscription
    }

    public fun once(eventName: String, listener: (YSubdocEvent, YDoc, YTransactionEvent?) -> Unit): Subscription {
        lateinit var subscription: Subscription
        val onceListener: (YSubdocEvent, YDoc, YTransactionEvent?) -> Unit = { event, doc, transaction ->
            subscription.close()
            listener(event, doc, transaction)
        }
        subscription = on(eventName, onceListener)
        return subscription
    }

    public fun off(eventName: String, listener: (YDocEvent) -> Unit) {
        ensureThreadAccess()
        val listeners = eventListeners[eventName] ?: return
        listeners.remove(listener)
        if (listeners.isEmpty()) {
            eventListeners.remove(eventName)
        }
    }

    public fun offDoc(eventName: String, listener: (YDoc) -> Unit) {
        ensureThreadAccess()
        val listeners = docOnlyEventListeners[eventName] ?: return
        listeners.remove(listener)
        if (listeners.isEmpty()) {
            docOnlyEventListeners.remove(eventName)
        }
    }

    public fun off(eventName: String, listener: (ByteArray, Any?, YDoc, YTransactionEvent?) -> Unit) {
        ensureThreadAccess()
        when (eventName) {
            "update" -> updateEventListeners.remove(listener)
            "updateV2" -> updateV2EventListeners.remove(listener)
            "updateLossless" -> updateLosslessEventListeners.remove(listener)
            "updateV2Lossless" -> updateV2LosslessEventListeners.remove(listener)
        }
    }

    public fun offSync(listener: (Boolean, YDoc) -> Unit) {
        ensureThreadAccess()
        syncEventListeners.remove(listener)
    }

    public fun off(eventName: String, listener: (YTransactionEvent, YDoc) -> Unit) {
        ensureThreadAccess()
        val listeners = transactionEventListeners[eventName] ?: return
        listeners.remove(listener)
        if (listeners.isEmpty()) {
            transactionEventListeners.remove(eventName)
        }
    }

    public fun offAfterAllTransactions(listener: (YDoc, List<YTransactionEvent>) -> Unit) {
        ensureThreadAccess()
        afterAllTransactionsEventListeners.remove(listener)
    }

    public fun off(eventName: String, listener: (YSubdocEvent, YDoc, YTransactionEvent?) -> Unit) {
        ensureThreadAccess()
        if (eventName == "subdocs") {
            subdocEventListeners.remove(listener)
        }
    }

    public fun emit(eventName: String, event: YDocEvent = YDocEvent(name = eventName)) {
        emit(if (event.name == eventName) event else event.copy(name = eventName))
    }

    public fun emit(event: YDocEvent) {
        ensureThreadAccess()
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

    public fun getSubdocs(): Set<YDoc> {
        ensureThreadAccess()
        return visibleSubdocRefs()
            .map(::subdocFromRef)
            .distinctBy { it.subdocInstanceId }
            .toCollection(linkedSetOf())
    }

    public fun getSubdocGuids(): Set<String> = getSubdocs().map { it.guid }.toSortedSet()

    public fun <T> transact(origin: Any? = null, local: Boolean = true, block: () -> T): T {
        return withDocumentAccess {
            transactWithinAccess(origin, local, block)
        }
    }

    private fun <T> transactWithinAccess(
        origin: Any?,
        local: Boolean,
        block: () -> T,
        captureItems: Boolean = false,
        onAccepted: ((Transaction) -> Unit)? = null,
    ): T {
        val existing = currentTransaction
        if (existing != null) return block()

        val transaction = Transaction(origin, local, beforeState = store.stateVector(), captureItems = captureItems)
        val requireAtomicStandard = local && requiresAtomicStandardTransaction()
        val rollbackSnapshot = if (requireAtomicStandard) {
            pendingAtomicPreflightSnapshot ?: captureAtomicTransactionSnapshot()
        } else {
            null
        }
        if (requireAtomicStandard) {
            pendingAtomicPreflightTypeStates.forEach(transaction.typeStates::putIfAbsent)
            pendingAtomicPreflightSnapshot = null
            pendingAtomicPreflightTypeStates.clear()
        }
        currentTransaction = transaction
        val emitBeforeAll = !isEmittingTransactionEvent
        var blockError: Throwable? = null
        var blockCompleted = false
        var transactionAccepted = false
        try {
            if (emitBeforeAll && hasBeforeAllTransactionListeners()) {
                emitBeforeAllTransactions()
            }
            emitBeforeTransaction(transaction)
            val result = block()
            blockCompleted = true
            if (requireAtomicStandard) validateStandardTransaction(transaction)
            transactionAccepted = true
            onAccepted?.invoke(transaction)
            return result
        } catch (error: Throwable) {
            blockError = error
            if (rollbackSnapshot != null) {
                if (blockCompleted) {
                    // The block completed and standard-wire validation rejected its result.
                    restoreAtomicTransactionSnapshot(
                        rollbackSnapshot,
                        transaction.typeStates,
                        transaction.addedMapKeys,
                    )
                } else {
                    // Preserve upstream Yjs' commit-on-callback-error behavior for a representable
                    // partial transaction. Roll back only when that partial state is non-standard.
                    try {
                        validateStandardTransaction(transaction)
                        transactionAccepted = true
                    } catch (validationError: Throwable) {
                        restoreAtomicTransactionSnapshot(
                            rollbackSnapshot,
                            transaction.typeStates,
                            transaction.addedMapKeys,
                        )
                        if (validationError !== error) error.addSuppressed(validationError)
                    }
                }
            }
            throw error
        } finally {
            currentTransaction = null
            if (rollbackSnapshot != null && transactionAccepted) {
                store.releaseSnapshot(rollbackSnapshot.store)
            }
            val shouldEmit = !transaction.isEmpty || hasNonUpdateTransactionConsumers()
            if ((!requireAtomicStandard || transactionAccepted) && shouldEmit) {
                try {
                    enqueueEmit(transaction)
                } catch (emitError: Throwable) {
                    if (blockError == null) {
                        throw emitError
                    }
                    blockError.addSuppressed(emitError)
                }
            }
        }
    }

    /**
     * Captures the minimal mutation result needed by UndoManager without installing a temporary
     * public transaction observer. A nested call temporarily enables item capture on the active
     * transaction and restores its previous capture mode before returning.
     */
    internal fun transactCapturingMutation(
        origin: Any?,
        block: () -> Unit,
    ): YTransactionMutationSummary = withDocumentAccess {
        currentTransaction?.let { transaction ->
            val addedStart = transaction.addedItems.size
            val deletedStart = transaction.deletedItems.size
            val previousCaptureItems = transaction.captureItems
            transaction.captureItems = true
            try {
                block()
                val added = transaction.addedItems.subList(addedStart, transaction.addedItems.size)
                val deleted = transaction.deletedItems.subList(deletedStart, transaction.deletedItems.size)
                val changedParentTypes = (added.asSequence() + deleted.asSequence())
                    .map(StoreItem::parent)
                    .distinct()
                    .mapNotNull(::typeForParent)
                    .toCollection(linkedSetOf())
                return@withDocumentAccess YTransactionMutationSummary(
                    deletedItems = deleted.map { item -> item.copy(deleted = false) },
                    changedParentTypes = changedParentTypes,
                )
            } finally {
                transaction.captureItems = previousCaptureItems
            }
        }
        var summary: YTransactionMutationSummary? = null
        transactWithinAccess(
            origin = origin,
            local = true,
            block = block,
            captureItems = true,
            onAccepted = { transaction ->
                summary = YTransactionMutationSummary(
                    deletedItems = transaction.deletedItems.map { item -> item.copy(deleted = false) },
                    changedParentTypes = changedParentsFor(transaction)
                        .mapNotNullTo(linkedSetOf(), ::typeForParent),
                )
            },
        )
        checkNotNull(summary)
    }

    public fun <T> transact(block: (YTransaction) -> T, origin: Any? = null, local: Boolean = true): T {
        return withDocumentAccess {
            val existing = currentTransaction
            if (existing != null) {
                block(YTransaction(this, existing))
            } else {
                val runBlock: () -> T = {
                    val active = currentTransaction ?: error("transaction is not active")
                    block(YTransaction(this, active))
                }
                transactWithinAccess(origin = origin, local = local, block = runBlock)
            }
        }
    }

    private fun requiresAtomicStandardTransaction(): Boolean =
        standardUpdatePolicy == YStandardUpdatePolicy.REQUIRE_STANDARD ||
            updateListeners.isNotEmpty() ||
            updateEventListeners.isNotEmpty() ||
            updateV2EventListeners.isNotEmpty() ||
            eventListeners["update"].orEmpty().isNotEmpty() ||
            eventListeners["updateV2"].orEmpty().isNotEmpty()

    private fun validateStandardTransaction(transaction: Transaction) {
        val items = store.itemsSince(transaction.beforeState)
        if (items.isEmpty() && transaction.deleteSet.isEmpty) return
        transaction.validatedStandardUpdate = UpdateCodec.encode(transactionDocumentUpdate(items, transaction.deleteSet))
    }

    private fun captureAtomicTransactionSnapshot(): AtomicTransactionSnapshot {
        val typeStates = java.util.IdentityHashMap<AbstractYType, YTypeMutableState>()
        (rootTypes.values + unopenedRootEntries.values + nestedTypes.values + pendingPreliminaryAttachments.values)
            .forEach { type -> typeStates.putIfAbsent(type, type.captureMutableState()) }
        return AtomicTransactionSnapshot(
            store = store.captureSnapshot(),
            clientId = clientId,
            rootTypes = rootTypes.toMap(),
            unopenedRootEntries = unopenedRootEntries.toMap(),
            nestedTypes = nestedTypes.toMap(),
            referencedNestedNames = referencedNestedNames.toSet(),
            pendingNestedReferenceStack = pendingNestedReferenceStack.map { names -> names.toSet() },
            pendingStoreParentStack = pendingStoreParentStack.toList(),
            pendingPreliminaryAttachments = pendingPreliminaryAttachments.toMap(),
            subdocsByInstanceId = subdocsByInstanceId.toMap(),
            pendingItems = pendingItems.map(StoreItem::copy),
            pendingDeletes = pendingDeletes.copy(),
            redoneByOriginal = redoneByOriginal.toMap(),
            redoneRangeEndByOriginal = redoneRangeEndByOriginal.toMap(),
            keptItems = keptItems.toSet(),
            nestedTypeCounter = nestedTypeCounter,
            cachedFullV1Update = cachedFullV1Update?.let { cached ->
                cached.copy(parentKinds = cached.parentKinds.toMap(), bytes = cached.bytes.copyOf())
            },
            typeStates = typeStates,
        )
    }

    private fun restoreAtomicTransactionSnapshot(
        snapshot: AtomicTransactionSnapshot,
        additionalTypeStates: Map<AbstractYType, YTypeMutableState>,
        addedMapKeys: Map<String, Set<String>>,
    ) {
        val typeStates = java.util.IdentityHashMap<AbstractYType, YTypeMutableState>()
        snapshot.typeStates.forEach(typeStates::put)
        additionalTypeStates.forEach(typeStates::putIfAbsent)
        typeStates.forEach { (type, state) -> type.restoreMutableState(state) }

        val currentSubdocs = subdocsByInstanceId.values.toSet()
        val restoredSubdocs = snapshot.subdocsByInstanceId.values.toSet()
        (currentSubdocs - restoredSubdocs).forEach { subdoc -> subdoc.parentDocs.remove(this) }
        restoredSubdocs.forEach { subdoc -> subdoc.parentDocs.add(this) }

        clientId = snapshot.clientId
        rootTypes.clear()
        rootTypes.putAll(snapshot.rootTypes)
        unopenedRootEntries.clear()
        unopenedRootEntries.putAll(snapshot.unopenedRootEntries)
        nestedTypes.clear()
        nestedTypes.putAll(snapshot.nestedTypes)
        referencedNestedNames.clear()
        referencedNestedNames.addAll(snapshot.referencedNestedNames)
        pendingNestedReferenceStack.clear()
        snapshot.pendingNestedReferenceStack.forEach { names ->
            pendingNestedReferenceStack.add(names.toMutableSet())
        }
        pendingStoreParentStack.clear()
        pendingStoreParentStack.addAll(snapshot.pendingStoreParentStack)
        pendingPreliminaryAttachments.clear()
        pendingPreliminaryAttachments.putAll(snapshot.pendingPreliminaryAttachments)
        subdocsByInstanceId.clear()
        subdocsByInstanceId.putAll(snapshot.subdocsByInstanceId)
        pendingItems.clear()
        pendingItems.addAll(snapshot.pendingItems.map(StoreItem::copy))
        pendingDeletes.clear()
        pendingDeletes.addAll(snapshot.pendingDeletes)
        redoneByOriginal.clear()
        redoneByOriginal.putAll(snapshot.redoneByOriginal)
        redoneRangeEndByOriginal.clear()
        redoneRangeEndByOriginal.putAll(snapshot.redoneRangeEndByOriginal)
        rebuildRedoneIndexes()
        keptItems.clear()
        keptItems.addAll(snapshot.keptItems)
        addedMapKeys.forEach { (parent, keys) ->
            mapKeyOrders[parent]?.let { order ->
                order.removeAll(keys)
                if (order.isEmpty()) mapKeyOrders.remove(parent)
            }
        }
        nestedTypeCounter = snapshot.nestedTypeCounter
        cachedFullV1Update = snapshot.cachedFullV1Update?.let { cached ->
            cached.copy(parentKinds = cached.parentKinds.toMap(), bytes = cached.bytes.copyOf())
        }
        store.restoreSnapshot(snapshot.store)
    }

    public fun observeUpdates(listener: (update: ByteArray, origin: Any?) -> Unit): Subscription {
        ensureThreadAccess()
        updateListeners.add(listener)
        return confinedSubscription { updateListeners.remove(listener) }
    }

    public fun observeUpdatesLossless(listener: (update: ByteArray, origin: Any?) -> Unit): Subscription {
        ensureThreadAccess()
        val wrapper: (ByteArray, Any?, YDoc, YTransactionEvent?) -> Unit = { update, origin, _, _ ->
            listener(update, origin)
        }
        updateLosslessEventListeners.add(wrapper)
        return confinedSubscription { updateLosslessEventListeners.remove(wrapper) }
    }

    public fun onUpdate(listener: (update: ByteArray, origin: Any?, doc: YDoc, transaction: YTransactionEvent?) -> Unit): Subscription {
        ensureThreadAccess()
        updateEventListeners.add(listener)
        return confinedSubscription { updateEventListeners.remove(listener) }
    }

    public fun onUpdateV2(listener: (update: ByteArray, origin: Any?, doc: YDoc, transaction: YTransactionEvent?) -> Unit): Subscription {
        ensureThreadAccess()
        updateV2EventListeners.add(listener)
        return confinedSubscription { updateV2EventListeners.remove(listener) }
    }

    public fun onUpdateLossless(
        listener: (update: ByteArray, origin: Any?, doc: YDoc, transaction: YTransactionEvent?) -> Unit,
    ): Subscription {
        ensureThreadAccess()
        updateLosslessEventListeners.add(listener)
        return confinedSubscription { updateLosslessEventListeners.remove(listener) }
    }

    public fun onUpdateV2Lossless(
        listener: (update: ByteArray, origin: Any?, doc: YDoc, transaction: YTransactionEvent?) -> Unit,
    ): Subscription {
        ensureThreadAccess()
        updateV2LosslessEventListeners.add(listener)
        return confinedSubscription { updateV2LosslessEventListeners.remove(listener) }
    }

    public fun observeBeforeAllTransactions(listener: () -> Unit): Subscription {
        ensureThreadAccess()
        beforeAllTransactionListeners.add(listener)
        return confinedSubscription { beforeAllTransactionListeners.remove(listener) }
    }

    public fun observeBeforeTransactions(listener: (YTransactionEvent) -> Unit): Subscription {
        ensureThreadAccess()
        beforeTransactionListeners.add(listener)
        return confinedSubscription { beforeTransactionListeners.remove(listener) }
    }

    public fun observeBeforeObserverCalls(listener: (YTransactionEvent) -> Unit): Subscription {
        ensureThreadAccess()
        beforeObserverCallsListeners.add(listener)
        return confinedSubscription { beforeObserverCallsListeners.remove(listener) }
    }

    public fun observeAfterTransactions(listener: (YTransactionEvent) -> Unit): Subscription {
        ensureThreadAccess()
        afterTransactionListeners.add(listener)
        return confinedSubscription { afterTransactionListeners.remove(listener) }
    }

    public fun observeAfterTransactionCleanup(listener: (YTransactionEvent) -> Unit): Subscription {
        ensureThreadAccess()
        afterTransactionCleanupListeners.add(listener)
        return confinedSubscription { afterTransactionCleanupListeners.remove(listener) }
    }

    public fun observeAfterAllTransactions(listener: (List<YTransactionEvent>) -> Unit): Subscription {
        ensureThreadAccess()
        afterAllTransactionsListeners.add(listener)
        return confinedSubscription { afterAllTransactionsListeners.remove(listener) }
    }

    internal fun observeTransactions(listener: (YTransactionEvent) -> Unit): Subscription {
        ensureThreadAccess()
        transactionListeners.add(listener)
        return confinedSubscription { transactionListeners.remove(listener) }
    }

    private fun confinedSubscription(unsubscribe: () -> Unit): Subscription = Subscription {
        ensureThreadAccess()
        unsubscribe()
    }

    public fun encodeStateVector(): ByteArray = withDocumentAccess {
        dev.yks.encodeStateVector(store.stateVector())
    }

    internal fun stateVector(): StateVector = withDocumentAccess { store.stateVector() }

    internal fun integrityCheck() {
        store.integrityCheck()
    }

    internal fun deleteSet(): DeleteSet = withDocumentAccess { store.deleteSet() }

    internal fun encodeSnapshotAsUpdate(snapshot: Snapshot): ByteArray = withDocumentAccess {
        val items = buildList {
            ClockRangeCursor(store.allItems()).forEachRange(
                boundariesForClient = { client ->
                    buildList {
                        snapshot.sv[client]?.let(::add)
                        snapshot.ds.rangesFor(client).forEach { range ->
                            add(range.clock)
                            add(range.end)
                        }
                    }
                },
            ) { source, startClock, endClock ->
                val snapshotClock = snapshot.sv[source.id.client] ?: 0
                if (startClock >= snapshotClock) return@forEachRange true
                if (source.isGc) {
                    // Yjs cannot split GC content and writes the complete packed GC struct when
                    // its start is covered by the snapshot state vector.
                    add(source.copy())
                    return@forEachRange true
                }
                val boundedEnd = minOf(endClock, snapshotClock)
                if (boundedEnd > startClock) {
                    add(
                        source.clockRangeView(startClock, boundedEnd).copy(
                            deleted = snapshot.ds.hasId(Id(source.id.client, startClock)),
                        ),
                    )
                }
                true
            }
        }
        UpdateCodec.encodeLossless(
            DocumentUpdate(items, snapshot.deleteSet, store.parentItemIds(), store.parentKinds()),
        )
    }

    public fun encodeStateAsUpdate(encodedStateVector: ByteArray = ByteArray(0)): ByteArray = withDocumentAccess {
        val cacheEligible = encodedStateVector.isEmpty() && pendingItems.isEmpty() && pendingDeletes.isEmpty
        val parentKinds = if (cacheEligible) store.parentKinds() else emptyMap()
        if (cacheEligible) {
            cachedFullV1Update
                ?.takeIf { cached -> cached.storeVersion == store.version && cached.parentKinds == parentKinds }
                ?.let { cached -> return@withDocumentAccess cached.bytes.copyOf() }
        }
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
        val encoded = if (updates.size == 1) updates.single() else mergeUpdates(updates)
        if (cacheEligible) {
            cachedFullV1Update = CachedFullUpdate(store.version, parentKinds.toMap(), encoded.copyOf())
        }
        encoded
    }

    public fun encodeStateAsUpdateLossless(encodedStateVector: ByteArray = ByteArray(0)): ByteArray = withDocumentAccess {
        val stateVector = decodeStateVector(encodedStateVector)
        val items = losslessItems(store.itemsSince(stateVector))
        val update = DocumentUpdate(
            items,
            store.deleteSet(),
            store.parentItemIds(),
            store.parentKinds(),
        )
        val encoded = UpdateCodec.encodeLossless(update)
        val updates = mutableListOf(
            if (encoded.hasPrivateEnvelope()) {
                UpdateCodec.encodeLossless(update.copy(items = materializeRenderedTextAttributes(items)))
            } else {
                encoded
            },
        )
        pendingDeleteSetUpdate()?.let(updates::add)
        pendingStructsView()?.update
            ?.let { pendingUpdate -> diffUpdateLossless(pendingUpdate, encodedStateVector) }
            ?.let(updates::add)
        if (updates.size == 1) updates.single() else mergeUpdatesLossless(updates)
    }

    internal fun encodeStateAsUpdateV2(encodedStateVector: ByteArray = ByteArray(0)): ByteArray = withDocumentAccess {
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
        if (updates.size == 1) updates.single() else mergeUpdatesV2(updates)
    }

    internal fun encodeStateAsUpdateV2Lossless(encodedStateVector: ByteArray = ByteArray(0)): ByteArray = withDocumentAccess {
        val stateVector = decodeStateVector(encodedStateVector)
        val items = losslessItems(store.itemsSince(stateVector))
        val update = DocumentUpdate(
            items,
            store.deleteSet(),
            store.parentItemIds(),
            store.parentKinds(),
        )
        val encoded = UpdateCodec.encodeV2Lossless(update)
        val updates = mutableListOf(
            if (encoded.hasPrivateEnvelope()) {
                UpdateCodec.encodeV2Lossless(update.copy(items = materializeRenderedTextAttributes(items)))
            } else {
                encoded
            },
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
        if (updates.size == 1) updates.single() else mergeUpdatesV2Lossless(updates)
    }

    private fun losslessItems(items: List<StoreItem>): List<StoreItem> {
        val markerParents = items.asSequence()
            .filter { item -> item.content is ItemContent.NativeTextFormat }
            .map { item -> item.parent }
            .toSet()
        return materializeRenderedTextAttributes(items) { item -> item.parent !in markerParents }
    }

    private fun materializeRenderedTextAttributes(
        items: List<StoreItem>,
        predicate: (StoreItem) -> Boolean = { true },
    ): List<StoreItem> = items.map { item ->
        if (!predicate(item)) return@map item
        val content = item.content.withRenderedTextAttributes(renderedTextAttributes(item)) ?: return@map item
        if (content == item.content) item else item.copy(content = content)
    }

    private fun ByteArray.hasPrivateEnvelope(): Boolean =
        size >= 3 && this[0] == 'Y'.code.toByte() && this[1] == 'K'.code.toByte() && this[2] == 'S'.code.toByte()

    public fun applyUpdate(update: ByteArray, origin: Any? = null) {
        withDocumentAccess {
            updateLimits.requireEncodedSize(update.size)
            val decoded = UpdateCodec.decodeStandard(
                update,
                updateLimits.maxStructs,
                updateLimits.maxDeleteRanges,
            )
            applyUpdate(decoded, origin)
        }
    }

    public fun applyUpdateV2(update: ByteArray, origin: Any? = null) {
        withDocumentAccess {
            updateLimits.requireEncodedSize(update.size)
            applyUpdate(
                UpdateCodec.decodeStandardV2(
                    update,
                    updateLimits.maxStructs,
                    updateLimits.maxDeleteRanges,
                ),
                origin,
            )
        }
    }

    /** Apply either a standard V1 update or YKS' explicit lossless envelope. */
    public fun applyUpdateLossless(update: ByteArray, origin: Any? = null) {
        withDocumentAccess {
            updateLimits.requireEncodedSize(update.size)
            applyUpdate(
                UpdateCodec.decode(update, updateLimits.maxStructs, updateLimits.maxDeleteRanges),
                origin,
            )
        }
    }

    /** Apply a standard V2/V1 update or YKS' explicit lossless envelope. */
    public fun applyUpdateV2Lossless(update: ByteArray, origin: Any? = null) {
        withDocumentAccess {
            updateLimits.requireEncodedSize(update.size)
            applyUpdate(
                UpdateCodec.decodeV2(update, updateLimits.maxStructs, updateLimits.maxDeleteRanges),
                origin,
            )
        }
    }

    internal fun applyUpdate(update: DocumentUpdate, origin: Any? = null) {
        withDocumentAccess {
            updateLimits.requireStructCount(update.items.size)
            updateLimits.requireDeleteRangeCount(update.deleteSet.rangeCount())
            prepareConfiguredRoots(update)
            avoidClientIdCollision(update)
            transact(origin, local = false) {
                integrateRemote(update.items)
                applyDeleteSet(update.deleteSet)
            }
        }
    }

    internal fun nextId(): Id = Id(clientId, store.getClock(clientId))

    internal fun visibleSequence(parent: String): List<StoreItem> {
        ensureThreadAccess()
        return store.sequence(parent)
            .filter { !it.deleted && it.countable }
    }

    internal fun visibleLength(parent: String, kind: RootKind): Long {
        ensureThreadAccess()
        return store.visibleLength(parent, kind)
    }

    internal fun visibleSequenceItemAt(parent: String, kind: RootKind, index: Int): StoreItem? {
        ensureThreadAccess()
        if (index < 0) return null
        return store.visibleSequenceItemAt(parent, kind, index.toLong())?.first
    }

    internal fun visibleSequencePositionAt(parent: String, kind: RootKind, index: Int): Pair<StoreItem, Long>? {
        ensureThreadAccess()
        if (index < 0) return null
        val (item, itemStart) = store.visibleSequenceItemAt(parent, kind, index.toLong()) ?: return null
        return item to (index.toLong() - itemStart)
    }

    internal fun firstVisibleSequenceItem(parent: String, kind: RootKind): StoreItem? {
        ensureThreadAccess()
        return store.firstVisibleSequenceItem(parent, kind)
    }

    internal fun visibleSequenceIndexAfter(parent: String, kind: RootKind, id: Id): Int? =
        store.visibleSequenceIndexAfter(parent, kind, id)?.toNonNegativeInt("visible sequence index")

    internal fun sequenceCursor(parent: String): SequenceCursor {
        ensureThreadAccess()
        return store.sequenceCursor(parent)
    }

    internal fun sequenceCursorAtFirstUndeleted(parent: String): SequenceCursor {
        ensureThreadAccess()
        return store.sequenceCursorAtFirstUndeleted(parent)
    }

    internal fun adjustOpenedTypeLength(parent: String, kind: RootKind, delta: Long) {
        if (maintainedLengthParents.isEmpty() || parent !in maintainedLengthParents) return
        (rootTypes[parent] ?: nestedTypes[parent])?.adjustVisibleLength(kind, delta)
    }

    internal fun registerMaintainedLength(parent: String) {
        maintainedLengthParents.add(parent)
    }

    internal fun renderedTextAttributes(item: StoreItem): Map<String, YValue> {
        ensureThreadAccess()
        return store.renderedTextAttributes(item)
    }

    private fun renderedTextItem(item: StoreItem): StoreItem {
        val attributes = renderedTextAttributes(item)
        val content = item.content.withRenderedTextAttributes(attributes) ?: return item
        return if (content == item.content) item else item.copy(content = content)
    }

    internal fun visibleText(parent: String, kind: RootKind): String {
        ensureThreadAccess()
        return store.visibleText(parent, kind)
    }

    internal fun sequence(parent: String): List<StoreItem> {
        ensureThreadAccess()
        return store.sequence(parent)
    }

    internal fun firstSequenceItem(parent: String): StoreItem? {
        ensureThreadAccess()
        return store.firstSequenceItem(parent)
    }

    internal fun nextSequenceItem(item: StoreItem): StoreItem? {
        ensureThreadAccess()
        return store.sequenceNeighbors(item).second
    }

    internal fun typeChildren(type: AbstractYType): List<StoreItem> {
        require(type.doc === this) { "type must belong to this document" }
        return sequence(type.name)
            .filter { item -> item.content.kind == type.kind }
            .map(::renderedTextItem)
    }

    internal fun directNestedChildTypes(type: AbstractYType): List<AbstractYType> {
        require(type.doc === this) { "type must belong to this document" }
        return directNestedChildren(type.name).mapNotNull { (_, nestedName) -> typeForParent(nestedName) }
    }

    internal fun parentOf(type: AbstractYType): AbstractYType? {
        require(type.doc === this) { "type must belong to this document" }
        store.firstOwnerForNested(type.name)?.let { owner ->
            typeForParent(owner.parent)?.let { return it }
        }
        // Private lossless values may contain nested type references below a JSON-like
        // container. Standard Yjs ContentType references always take the indexed path above.
        val candidates = (rootNames().mapNotNull(::rootType) + nestedTypes.keys.mapNotNull(::typeForParent))
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
        if (pendingDeletes.isEmpty) return null
        val pendingIds = diffIdSet(pendingDeletes.toIdSet(), store.deleteSet().toIdSet())
        if (pendingIds.isEmpty()) return null
        return UpdateCodec.encodeLossless(DocumentUpdate(emptyList(), pendingIds.toDeleteSet()))
    }

    internal fun setPendingDeleteSetUpdate(update: ByteArray?) {
        pendingDeletes.clear()
        update?.let { pendingDeletes.addAll(UpdateCodec.decode(it).deleteSet) }
    }

    internal fun itemsForIdSet(
        idSet: IdSet,
        predicate: (StoreItem) -> Boolean = { true },
        deletedOverride: Boolean? = null,
        materializeTextAttributes: Boolean = false,
    ): List<StoreItem> {
        val items = mutableListOf<StoreItem>()
        idSet.ranges().forEach { (client, range) ->
            val clientItems = store.itemsForClient(client)
            var index = store.firstItemEndingAfter(client, range.clock)
            while (index < clientItems.size) {
                val item = clientItems[index++]
                if (item.id.clock >= range.end) break
                if (!predicate(item)) continue
                val itemEnd = checkedClockAdd(item.id.clock, item.length)
                val start = maxOf(item.id.clock, range.clock)
                val end = minOf(itemEnd, range.end)
                var selected = item.sliceClocks(start, end)
                if (materializeTextAttributes) {
                    item.content.withRenderedTextAttributes(renderedTextAttributes(item))?.let { content ->
                        selected = selected.copy(content = content)
                    }
                }
                items.add(if (deletedOverride == null) selected else selected.copy(deleted = deletedOverride))
            }
        }
        return items
    }

    internal fun splitStoreAtIdSetBoundaries(idSet: IdSet) {
        store.splitAtDeleteSetBoundaries(idSet.toDeleteSet()) { right ->
            currentTransaction?.mergeStructs?.add(right.id)
        }
    }

    internal fun followRedone(id: Id): Id {
        var current = id
        val seen = mutableSetOf<Id>()
        while (seen.add(current)) {
            val direct = redoneByOriginal[current]
            val ranged = if (direct == null) {
                redoneStartsByClient[current.client]
                    ?.floorEntry(current.clock)
                    ?.let { (clock, restoredStart) ->
                        val originalStart = Id(current.client, clock)
                        val original = store.getStoreItem(originalStart) ?: return@let null
                        val offset = current.clock - clock
                        if (offset >= original.length) return@let null
                        Id(restoredStart.client, checkedClockAdd(restoredStart.clock, offset, "follow redone clock"))
                    }
            } else {
                null
            }
            current = direct ?: ranged ?: return current
        }
        return current
    }

    internal fun redoneRangeEnd(id: Id): Id? {
        val end = redoneRangeEndByOriginal[id]
            ?: redoneRangeEndsByClient[id.client]
                ?.floorEntry(id.clock)
                ?.let { (clock, restoredEnd) ->
                    val original = store.getStoreItem(Id(id.client, clock)) ?: return@let null
                    restoredEnd.takeIf { id.clock - clock < original.length }
                }
        return end?.let(::followRedone)
    }

    internal fun directRedone(id: Id): Id? = redoneByOriginal[id]

    internal fun itemLinks(id: Id): Pair<Item?, Item?> {
        val item = store.getStoreItem(id) ?: return null to null
        val links = if (item.parentSub == null) {
            store.sequenceNeighbors(item)
        } else {
            val order = mapItemOrder(item.parent, item.parentSub)
            val index = order.indexOfFirst { candidate -> candidate.id == item.id }
            order.getOrNull(index - 1) to order.getOrNull(index + 1)
        }
        return links.first?.toItemStruct(this) to links.second?.toItemStruct(this)
    }

    internal fun visibleItemNeighbor(id: Id, previous: Boolean): Item? {
        var current = if (previous) itemLinks(id).first else itemLinks(id).second
        while (current?.deleted == true) current = if (previous) current.left else current.right
        return current
    }

    private fun rememberRedone(original: Id, restored: Id) {
        redoneByOriginal[original] = restored
        redoneStartsByClient.getOrPut(original.client) { java.util.TreeMap() }[original.clock] = restored
    }

    private fun rememberRedoneRangeEnd(original: Id, restoredEnd: Id) {
        redoneRangeEndByOriginal[original] = restoredEnd
        redoneRangeEndsByClient.getOrPut(original.client) { java.util.TreeMap() }[original.clock] = restoredEnd
    }

    private fun rebuildRedoneIndexes() {
        redoneStartsByClient.clear()
        redoneByOriginal.forEach(::rememberRedoneIndex)
        redoneRangeEndsByClient.clear()
        redoneRangeEndByOriginal.forEach(::rememberRedoneRangeEndIndex)
    }

    private fun rememberRedoneIndex(original: Id, restored: Id) {
        redoneStartsByClient.getOrPut(original.client) { java.util.TreeMap() }[original.clock] = restored
    }

    private fun rememberRedoneRangeEndIndex(original: Id, restoredEnd: Id) {
        redoneRangeEndsByClient.getOrPut(original.client) { java.util.TreeMap() }[original.clock] = restoredEnd
    }

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
        return store.firstOwnerForNested(type.name)
            ?.takeIf { item -> item.content.directTypeRef()?.kind == type.kind }
            ?.id
    }

    internal fun rootType(name: String): AbstractYType? = rootTypes[name]

    public fun rootNames(): Set<String> {
        ensureThreadAccess()
        return sortedSetOf<String>().apply {
            addAll(rootTypes.keys)
            addAll(unopenedRootEntries.keys)
            addAll(store.parentNames())
            removeAll { name -> !isSharedRootName(name) }
        }
    }

    /**
     * Returns whether the named root has never acquired list or map structs.
     *
     * This intentionally matches Yjs' type-neutral `!type._start && !type._map.size`
     * contract. Deleted list content and deleted map keys still make the root
     * non-empty because their structs remain attached to the shared type.
     */
    public fun isRootEmpty(name: String): Boolean {
        ensureThreadAccess()
        return store.firstItemForParent(name, sequenceOnly = true) == null &&
            store.mapKeysForParent(name).isEmpty()
    }

    private fun sharedRootEntry(name: String): AbstractYType? {
        rootTypes[name]?.let { return it }
        return materializeConfiguredStoredRoot(name) ?: unopenedRoot(name)
    }

    private fun hasSharedRoot(name: String): Boolean =
        isSharedRootName(name) &&
            (name in rootTypes || name in unopenedRootEntries || store.hasParent(name))

    private fun isSharedRootName(name: String): Boolean =
        !isNestedName(name) && !name.startsWith("__yjs_gc__:")

    private fun unopenedRoot(name: String): YUnopenedRoot? {
        unopenedRootEntries[name]?.let { return it }
        if (!isSharedRootName(name) || !store.hasParent(name)) return null
        return unopenedRootEntries.getOrPut(name) { YUnopenedRoot(this, name) }
    }

    private fun createUnopenedRoot(name: String): YUnopenedRoot {
        require(!isNestedName(name)) { "nested type '$name' cannot be opened as a root type" }
        return unopenedRootEntries.getOrPut(name) { YUnopenedRoot(this, name) }
    }

    internal fun concreteRootTypes(): Map<String, AbstractYType> = rootTypes.toMap()

    private fun resolveRootSchema(name: String): YRootSchema? {
        if (configuredRootSchemas === YRootSchemaRegistry.EMPTY) return null
        if (name in resolvedRootSchemas) return resolvedRootSchemas[name]
        return configuredRootSchemas.resolve(name).also { schema -> resolvedRootSchemas[name] = schema }
    }

    private fun materializeConfiguredStoredRoot(name: String): AbstractYType? {
        if (!store.hasParent(name)) return null
        return materializeConfiguredRoot(name)
    }

    private fun materializeConfiguredRoot(name: String): AbstractYType? =
        resolveRootSchema(name)?.let { schema -> materializeRootSchema(name, schema) }

    private fun materializeRootSchema(name: String, schema: YRootSchema): AbstractYType {
        require(!isNestedName(name)) { "nested type '$name' cannot be opened as a root type" }
        requireCompatibleRootSchema(name, schema)
        return when (schema) {
            YRootSchema.Array -> getArray(name)
            YRootSchema.Map -> getMap(name)
            YRootSchema.Text -> getText(name)
            YRootSchema.XmlFragment -> getXmlFragment(name)
            is YRootSchema.XmlElement -> getXmlElement(name, schema.nodeName)
            is YRootSchema.XmlHook -> getXmlHook(name, schema.hookName)
            YRootSchema.XmlText -> getXmlText(name)
        }
    }

    private fun requireCompatibleRootSchema(name: String, schema: YRootSchema) {
        val existing = rootTypes[name] ?: return
        if (existing.kind != schema.kind) {
            throw YRootSchemaConflictException(name, "configured ${schema.kind}, document has ${existing.kind}")
        }
        when {
            existing is YXmlElementType && schema is YRootSchema.XmlElement &&
                existing.nodeName != schema.nodeName -> throw YRootSchemaConflictException(
                name,
                "configured XML nodeName '${schema.nodeName}', document has '${existing.nodeName}'",
            )

            existing is YXmlHook && schema is YRootSchema.XmlHook &&
                existing.hookName != schema.hookName -> throw YRootSchemaConflictException(
                name,
                "configured XML hookName '${schema.hookName}', document has '${existing.hookName}'",
            )
        }
    }

    private fun requireConfiguredXmlName(name: String, kind: RootKind, requestedName: String) {
        when (val schema = resolveRootSchema(name)) {
            is YRootSchema.XmlElement -> if (kind == RootKind.XmlElement && schema.nodeName != requestedName) {
                throw YRootSchemaConflictException(
                    name,
                    "configured XML nodeName '${schema.nodeName}', getter requested '$requestedName'",
                )
            }

            is YRootSchema.XmlHook -> if (kind == RootKind.XmlHook && schema.hookName != requestedName) {
                throw YRootSchemaConflictException(
                    name,
                    "configured XML hookName '${schema.hookName}', getter requested '$requestedName'",
                )
            }

            else -> Unit
        }
    }

    private fun prepareConfiguredRoots(update: DocumentUpdate) {
        if (configuredRootSchemas === YRootSchemaRegistry.EMPTY) return
        val nestedParents = update.parentItemIds.keys
        val candidateNames = buildSet {
            update.items.asSequence()
                .filter { item -> item.unresolvedParent == null }
                .mapTo(this) { item -> item.parent }
            addAll(update.parentKinds.keys)
        }.filter { name ->
            isSharedRootName(name) &&
                name !in nestedParents &&
                !name.startsWith("__yks_yjs_nested__:")
        }
        val resolved = candidateNames.mapNotNull { name ->
            resolveRootSchema(name)?.let { schema -> name to schema }
        }
        resolved.forEach { (name, schema) ->
            update.parentKinds[name]?.let { updateKind ->
                if (updateKind != schema.kind) {
                    throw YRootSchemaConflictException(name, "configured ${schema.kind}, update declares $updateKind")
                }
            }
            requireCompatibleRootSchema(name, schema)
        }
        resolved.forEach { (name, schema) -> materializeRootSchema(name, schema) }
    }

    internal fun knownParentKinds(): Map<String, RootKind> = buildMap {
        rootTypes.forEach { (name, type) -> put(name, type.kind) }
        nestedTypes.forEach { (name, type) -> put(name, type.kind) }
        store.nestedOwnerKinds().forEach { (name, kind) -> put(name, kind) }
    }

    private fun knownParentKind(parent: String): RootKind? =
        rootTypes[parent]?.kind
            ?: nestedTypes[parent]?.kind
            ?: store.firstOwnerForNested(parent)?.content?.directTypeRef()?.kind

    private fun isNestedName(name: String): Boolean =
        name in nestedTypes || store.hasNestedOwner(name)

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

    /**
     * Stores one scalar from a container that has already passed [preflightNestedValue].
     *
     * Packed array inserts preflight the complete value graph once. Repeating graph validation
     * and allocating two nested-reference stacks for every primitive made a single ContentAny
     * item O(n) with a large avoidable constant. Reference-bearing values retain the full path.
     */
    internal fun storePreflightedScalar(value: Any?, parent: String? = null): YValue = when (value) {
        null -> YValue.Null
        YValue.Undefined,
        YValue.Null,
        is YValue.Bool,
        is YValue.LongNumber,
        is YValue.DoubleNumber,
        is YValue.BigIntNumber,
        is YValue.StringValue,
        is YValue.BinaryValue -> value as YValue
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
        else -> storeValue(value, parent)
    }

    internal fun preflightNestedValue(value: Any?) {
        prepareAtomicPreflightIfNeeded()
        try {
            validateStoreValue(value)
            preparePreliminaryGraph(value)
        } catch (error: Throwable) {
            rollbackPendingAtomicPreflight()
            throw error
        }
    }

    /** Preflights only shared-type identity/binding for APIs that transform non-YValue inputs. */
    internal fun preflightSharedTypes(value: Any?) {
        prepareAtomicPreflightIfNeeded()
        try {
            preparePreliminaryGraph(value)
        } catch (error: Throwable) {
            rollbackPendingAtomicPreflight()
            throw error
        }
    }

    private fun prepareAtomicPreflightIfNeeded() {
        if (
            currentTransaction == null &&
            requiresAtomicStandardTransaction() &&
            pendingAtomicPreflightSnapshot == null
        ) {
            pendingAtomicPreflightSnapshot = captureAtomicTransactionSnapshot()
        }
    }

    private fun rollbackPendingAtomicPreflight() {
        val snapshot = pendingAtomicPreflightSnapshot ?: return
        val typeStates = java.util.IdentityHashMap<AbstractYType, YTypeMutableState>()
        pendingAtomicPreflightTypeStates.forEach(typeStates::put)
        pendingAtomicPreflightSnapshot = null
        pendingAtomicPreflightTypeStates.clear()
        restoreAtomicTransactionSnapshot(snapshot, typeStates, emptyMap())
    }

    private fun captureTypeStateForMutation(type: AbstractYType) {
        val transaction = currentTransaction
        if (transaction != null) {
            transaction.captureTypeState(type)
        } else if (pendingAtomicPreflightSnapshot != null) {
            pendingAtomicPreflightTypeStates.putIfAbsent(type, type.captureMutableState())
        }
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
        if (renderer === baseRenderer) {
            val path = ArrayDeque<List<Any>>()
            val seen = mutableSetOf(child)
            var current = child
            while (current != parent) {
                val step = directParentPathStep(current) ?: return pathBetweenByTraversal(parent, child, renderer)
                if (!seen.add(step.parent)) return null
                path.addFirst(step.segments)
                current = step.parent
            }
            return path.flatten()
        }
        return pathBetweenByTraversal(parent, child, renderer)
    }

    private data class ParentPathStep(
        val parent: String,
        val segments: List<Any>,
    )

    private fun directParentPathStep(child: String): ParentPathStep? {
        val owner = store.firstOwnerForNested(child) ?: return null
        if (owner.content.directTypeRef()?.name != child) return null
        val key = owner.parentSub
        if (key != null) {
            if (currentVisibleMapItemId(owner.parent, key) != owner.id) return null
            return ParentPathStep(owner.parent, listOf(key))
        }
        if (owner.deleted || !owner.countable) return null
        val indexAfter = store.visibleSequenceIndexAfter(owner.parent, owner.content.kind, owner.lastId)
            ?: return null
        val index = (indexAfter - owner.length).toNonNegativeInt("nested type path index")
        return ParentPathStep(owner.parent, listOf(index))
    }

    private fun pathBetweenByTraversal(
        parent: String,
        child: String,
        renderer: AbstractRenderer,
    ): List<Any>? {
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
        val targetIndex = index.toLong()
        val visibleLength = store.visibleLength(parent, kind)
        require(targetIndex <= visibleLength) { "insert index is out of bounds" }
        if (targetIndex == visibleLength) {
            val left = store.lastSequenceItem(parent, kind)
            registerVirtualInsertionSplitCandidate(left, null)
            return left?.lastId to null
        }
        val position = store.visibleSequenceItemAt(parent, kind, targetIndex)
        if (position != null && targetIndex > position.second) {
            val containing = position.first
            val splitClock = checkedClockAdd(
                containing.id.clock,
                targetIndex - position.second,
                "sequence insertion split clock",
            )
            store.getStoreItemCleanStart(Id(containing.id.client, splitClock)) { right ->
                currentTransaction?.mergeStructs?.add(right.id)
            }
        }
        val (left, right) = store.sequenceAnchors(parent, kind, targetIndex)
        registerVirtualInsertionSplitCandidate(left, right)
        return left?.lastId to right?.id
    }

    private fun registerVirtualInsertionSplitCandidate(left: StoreItem?, right: StoreItem?) {
        if (
            left != null &&
            right != null &&
            left.canVirtuallyMerge(left, right, logicallyAdjacent = true)
        ) {
            currentTransaction?.mergeStructs?.add(right.id)
        }
    }

    internal fun visibleMapValue(parent: String, key: String): YValue? {
        ensureThreadAccess()
        return currentMapItem(parent, key)
            ?.takeIf { item -> !item.deleted }
            ?.content
            ?.mapContentValue()
    }

    internal fun visibleMap(parent: String): Map<String, YValue> {
        ensureThreadAccess()
        return mapKeysInInsertionOrder(parent)
            .mapNotNull { key -> visibleMapValue(parent, key)?.let { key to it } }
            .toMap(linkedMapOf())
    }

    private fun visibleMapItemIds(parent: String): Map<String, Id> =
        mapKeysInInsertionOrder(parent)
            .mapNotNull { key -> currentVisibleMapItemId(parent, key)?.let { key to it } }
            .toMap(linkedMapOf())

    internal fun mapValueAtSnapshot(type: AbstractYType, key: String, snapshot: Snapshot): YValue? {
        ensureThreadAccess()
        require(type.doc === this) { "type must belong to this document" }
        mapItemOrder(type.name, key).asReversed().forEach { item ->
            val visibleEnd = minOf(item.clockEnd(), snapshot.sv[item.id.client] ?: 0)
            if (visibleEnd <= item.id.clock) return@forEach
            val visibleClock = visibleEnd - 1
            if (snapshot.ds.hasId(Id(item.id.client, visibleClock))) return null
            return when (val content = item.content) {
                is ItemContent.MapEntries -> {
                    val index = (visibleClock - item.id.clock).toNonNegativeInt("snapshot map offset")
                    content.values[index]
                }
                else -> content.mapContentValue()
            }
        }
        return null
    }

    internal fun mapAtSnapshot(type: AbstractYType, snapshot: Snapshot): Map<String, YValue> {
        ensureThreadAccess()
        require(type.doc === this) { "type must belong to this document" }
        return mapKeysInInsertionOrder(type.name)
            .mapNotNull { key -> mapValueAtSnapshot(type, key, snapshot)?.let { key to it } }
            .toMap(linkedMapOf())
    }

    internal fun currentMapItemId(parent: String, key: String): Id? = currentMapItem(parent, key)?.id

    internal fun currentVisibleMapItemId(parent: String, key: String): Id? =
        currentMapItem(parent, key)?.takeUnless { item -> item.deleted }?.id

    private fun currentMapItem(parent: String, key: String): StoreItem? =
        store.cachedCurrentMapItem(parent, key) { mapItemOrder(parent, key).lastOrNull() }

    internal fun mapItemKeys(parent: String): Set<String> =
        mapKeysInInsertionOrder(parent).toCollection(linkedSetOf())

    private fun mapKeysInInsertionOrder(parent: String): List<String> {
        val remembered = mapKeyOrders[parent].orEmpty()
        val indexed = store.mapKeysForParent(parent)
        if (remembered.isEmpty()) return indexed.toList()
        // The fallback makes this robust to internal test/store construction that bypasses
        // YDoc integration. Normal local and remote integration only take the remembered path.
        val missing = indexed.asSequence().filterNot(remembered::contains)
        return remembered.toList() + missing.toList()
    }

    private fun rememberMapKey(item: StoreItem) {
        val key = item.parentSub ?: return
        if (mapKeyOrders.getOrPut(item.parent) { linkedSetOf() }.add(key)) {
            currentTransaction?.addedMapKeys?.getOrPut(item.parent) { linkedSetOf() }?.add(key)
        }
    }

    private fun ItemContent.mapContentValue(): YValue? = when (this) {
        is ItemContent.MapEntry -> value
        is ItemContent.MapEntries -> values.last()
        is ItemContent.XmlType -> ref.takeIf { kind == RootKind.Map || kind == RootKind.XmlHook }
        else -> null
    }

    internal fun mapItemOrder(parent: String, key: String): List<StoreItem> {
        ensureThreadAccess()
        return store.cachedMapOrder(parent, key) { buildMapItemOrder(parent, key) }
    }

    private fun buildMapItemOrder(parent: String, key: String): List<StoreItem> {
        val entries = store.mapEntries(parent, key)
        if (entries.size < 2) return entries
        if (entries.zipWithNext().all { (left, right) -> right.origin == left.lastId }) {
            return entries
        }
        val sharedOrigin = entries.first().origin
        val sharedRightOrigin = entries.first().rightOrigin
        if (entries.all { item -> item.origin == sharedOrigin && item.rightOrigin == sharedRightOrigin }) {
            return entries.sortedBy { item -> item.id }
        }
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

    internal fun arrayAtSnapshot(type: YArray, snapshot: Snapshot): List<Any?> = buildList {
        fun boundaries(client: Long): Iterable<Long> = buildList {
            snapshot.sv[client]?.let(::add)
            snapshot.ds.rangesFor(client).forEach { range ->
                add(range.clock)
                add(range.end)
            }
        }
        ClockRangeCursor(sequence(type.name)).forEachRange(::boundaries) { source, startClock, endClock ->
            val item = source.clockRangeView(startClock, endClock)
            if (item.content.kind == type.kind && item.countable && item.isVisibleIn(snapshot)) {
                addAll(arrayItemValues(item))
            }
            true
        }
    }

    internal fun arrayItemValue(item: StoreItem): Any? = when (val content = item.content) {
        is ItemContent.Value -> valueToAny(content.value)
        is ItemContent.ArrayValues -> valueToAny(content.values.last())
        is ItemContent.XmlType -> typeFromXmlType(content)
        else -> error("item content is not an array value: ${content::class.simpleName}")
    }

    internal fun arrayItemValues(item: StoreItem): List<Any?> = when (val content = item.content) {
        is ItemContent.Value -> listOf(valueToAny(content.value))
        is ItemContent.ArrayValues -> content.values.map(::valueToAny)
        is ItemContent.XmlType -> listOf(typeFromXmlType(content))
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
            captureParentBefore(item.parent, item.content.kind, item.parentSub)
            check(store.add(item)) { "duplicate local item id: ${item.id}" }
            rememberMapKey(item)
            item.parentSub?.let { key -> store.cacheCurrentMapItem(item.parent, key, item) }
            rememberNestedRefs(item.content)
            if (shouldReapplyTextFormatsAfter(item)) {
                reapplyTextFormats(item.parent)
            }
            recordAddedItem(item)
            currentTransaction?.markChanged(item.parent, item.parentSub)
            attachAndReplayPreliminaryTypes(item.content, item.id)
            deletePreviousMapCurrentIfSuperseded(item, previousMapCurrent)
        }
    }

    private fun integrateLocalTextFormat(item: StoreItem) {
        transact {
            captureParentBefore(item.parent, item.content.kind, item.parentSub)
            check(store.add(item)) { "duplicate local item id: ${item.id}" }
            applyTextFormat(item)
            recordAddedItem(item)
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
        val start = index.coerceAtLeast(0)
        transact(origin = origin) {
            if (length <= 0) return@transact
            val totalLength = store.totalVisibleLength(parent)
            val available = (totalLength - start.toLong()).coerceAtLeast(0).toNonNegativeInt("visible delete length")
            val deleteCount = minOf(length, available)
            if (deleteCount > 0) {
                val deleteSet = DeleteSet.empty()
                val deleteStart = start.toLong()
                val deletedItems = if (deleteStart == 0L && deleteCount.toLong() == totalLength) {
                    // Deleting the full visible range needs no positional ordering. Avoid building
                    // a sequence treap that this same transaction would immediately invalidate.
                    store.visibleSequenceItemsForParent(parent)
                } else {
                    splitVisibleSequenceBoundary(parent, deleteStart)
                    splitVisibleSequenceBoundary(
                        parent,
                        checkedClockAdd(deleteStart, deleteCount.toLong(), "delete end"),
                    )
                    store.visibleItemsInRange(parent, deleteStart, deleteCount.toLong())
                }
                deleteSet.addContiguousItems(deletedItems)
                applyDeleteSet(deleteSet)
            }
            if (strictLength && length > available) {
                throw IllegalArgumentException("delete range is out of bounds")
            }
        }
    }

    private fun splitVisibleSequenceBoundary(parent: String, index: Long) {
        val position = store.visibleSequenceItemAt(parent, index) ?: return
        if (index <= position.second) return
        val containing = position.first
        val splitClock = checkedClockAdd(
            containing.id.clock,
            index - position.second,
            "visible sequence split clock",
        )
        store.getStoreItemCleanStart(Id(containing.id.client, splitClock)) { right ->
            currentTransaction?.mergeStructs?.add(right.id)
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

    internal fun deleteItemRanges(items: Iterable<StoreItem>) {
        transact {
            val deleteSet = DeleteSet.empty()
            items.forEach { item -> deleteSet.add(item.id, item.length) }
            applyDeleteSet(deleteSet)
        }
    }

    internal fun restoreItems(items: List<RestoreItem>): List<StoreItem> {
        if (items.isEmpty()) return emptyList()
        if (items.size == 1 && !items.single().item.content.isTextFormatControl()) {
            return restoreSingleItem(items.single())
        }
        val originalPositions = if (items.size == 1) emptyMap() else restorePositions(items)
        val sortedItems = if (items.size == 1) items else items.sortedForRestore(originalPositions)
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
                        source.origin?.let { restoredByOriginal[it] } ?: source.origin?.let(::followRedone)
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
                        source.rightOrigin?.let { restoredByOriginal[it] } ?: source.rightOrigin?.let(::followRedone)
                    },
                    parent = source.parent,
                    parentSub = source.parentSub,
                    content = source.content.retargetTextFormat(restoredByOriginal),
                    deleted = false,
                )
                if (!original.anchorAfterOriginal) {
                    // Redo may anchor inside a packed text/array item. Remote integration
                    // already normalizes those boundaries; local restoration must do the
                    // same before rebuilding the sequence index.
                    cleanRemoteOrigins(item)
                }
                check(store.add(item)) { "duplicate restored item id: ${item.id}" }
                rememberMapKey(item)
                restoredByOriginal[source.id] = item.id
                rememberRedone(source.id, item.id)
                restoredPairs.add(source to item)
                restored.add(item)
                if (sourcePosition != null) {
                    previousRestoredByParent[parentKey] = sourcePosition to item.lastId
                }
                recordAddedItem(item)
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

    private fun restoreSingleItem(original: RestoreItem): List<StoreItem> {
        val source = original.item
        var restored: StoreItem? = null
        transact {
            val mapOrigin = source.parentSub?.let { key -> currentMapItem(source.parent, key)?.id }
            val item = StoreItem(
                id = nextId(),
                origin = when {
                    source.parentSub != null -> mapOrigin
                    original.anchorAfterOriginal -> source.id
                    else -> source.origin?.let(::followRedone)
                },
                rightOrigin = when {
                    source.parentSub != null -> null
                    original.anchorAfterOriginal -> inferRightOrigin(source)
                    else -> source.rightOrigin?.let(::followRedone)
                },
                parent = source.parent,
                parentSub = source.parentSub,
                content = source.content,
                deleted = false,
            )
            if (!original.anchorAfterOriginal) {
                cleanRemoteOrigins(item)
            }
            check(store.add(item)) { "duplicate restored item id: ${item.id}" }
            rememberMapKey(item)
            rememberRedone(source.id, item.id)
            rememberRedoneRangeEnd(source.id, item.lastId)
            recordAddedItem(item)
            currentTransaction?.markChanged(item.parent, item.parentSub)
            restored = item
        }
        return listOf(checkNotNull(restored))
    }

    internal fun restoreItemAtCurrentPosition(item: StoreItem): RestoreItem {
        val needsCurrentSequenceAnchors = item.parentSub == null && !isNestedName(item.parent)
        val anchored = if (needsCurrentSequenceAnchors) {
            val (left, right) = store.visibleSequenceNeighbors(item)
            item.copy(
                origin = left?.lastId,
                rightOrigin = right?.id,
            )
        } else {
            item
        }
        return RestoreItem(
            anchored.copy(deleted = false),
            anchorAfterOriginal = !needsCurrentSequenceAnchors,
        )
    }

    private fun applyDeleteSet(
        deleteSet: DeleteSet,
        deferredTextFormatParents: MutableSet<String>? = null,
        registerMergeCandidates: Boolean = true,
    ) {
        if (deleteSet.isEmpty) return
        val expandedDeleteSet = expandDeleteSetWithNestedTypeContent(deleteSet)
        if (registerMergeCandidates) registerVirtualSplitMergeCandidates(expandedDeleteSet)
        store.splitAtDeleteSetBoundaries(expandedDeleteSet) { right ->
            currentTransaction?.mergeStructs?.add(right.id)
        }
        val itemsInDeleteSet = store.itemsStartingIn(expandedDeleteSet)
        val newlyDeletedItems = itemsInDeleteSet.filterNot { it.deleted }
        val captureDeletedItems = hasTransactionConsumers()
        val newlyDeleted = if (captureDeletedItems) {
            newlyDeletedItems.map { it.copy(deleted = false) }
        } else {
            emptyList()
        }
        if (needsTransactionSnapshots()) {
            newlyDeleted.forEach { captureParentBefore(it.parent, it.content.kind, it.parentSub) }
        }
        val newlyDeletedSet = if (newlyDeletedItems.size == itemsInDeleteSet.size) {
            expandedDeleteSet.copy()
        } else {
            DeleteSet.empty().also { transactionDeleteSet ->
                transactionDeleteSet.addContiguousItems(newlyDeletedItems)
            }
        }
        val textFormatParents = newlyDeletedItems
            .filter { item -> item.content.isTextFormatControl() }
            .mapTo(linkedSetOf()) { item -> item.parent }
        pendingDeletes.addAll(expandedDeleteSet)
        val changed = store.markDeleted(newlyDeletedItems)
        if (changed) {
            currentTransaction?.deleteSet?.addAll(newlyDeletedSet)
            if (captureDeletedItems) {
                newlyDeletedItems.forEachIndexed { index, item ->
                    val captured = checkNotNull(newlyDeleted.getOrNull(index))
                    val hasSubdocRefs = captured.content.hasSubdocRefs()
                    if (hasSubdocRefs) visibleSubdocRefsCache = null
                    currentTransaction?.recordDeleted(captured, hasSubdocRefs)
                    currentTransaction?.markChanged(item.parent, item.parentSub)
                }
            } else {
                var hasSubdocRefs = false
                val changedParents = linkedMapOf<String, MutableSet<String?>>()
                newlyDeletedItems.forEach { item ->
                    hasSubdocRefs = hasSubdocRefs || item.content.hasSubdocRefs()
                    changedParents.getOrPut(item.parent) { linkedSetOf() }.add(item.parentSub)
                }
                if (hasSubdocRefs) visibleSubdocRefsCache = null
                currentTransaction?.recordDeletedMetadata(hasSubdocRefs)
                changedParents.forEach { (parent, parentSubs) ->
                    parentSubs.forEach { parentSub -> currentTransaction?.markChanged(parent, parentSub) }
                }
            }
            textFormatParents.forEach { parent ->
                    if (deferredTextFormatParents == null) {
                        reapplyTextFormats(parent)
                    } else {
                        deferredTextFormatParents.add(parent)
                    }
                    currentTransaction?.markChanged(parent, null)
                }
        }
    }

    /** Record deletion-boundary splits for transaction-cleanup merging, matching Yjs splitItem. */
    private fun registerVirtualSplitMergeCandidates(deleteSet: DeleteSet) {
        val transaction = currentTransaction ?: return
        val touchedItems = store.itemsOverlapping(deleteSet)
        if (touchedItems.none { item -> item.content.virtualMergeClass() != null }) return
        val checkedPairs = hashSetOf<Pair<Id, Id>>()
        touchedItems
            .filter { item -> item.parentSub == null && item.content.virtualMergeClass() != null }
            .forEach { item ->
                val (left, right) = store.sequenceNeighbors(item)
                listOfNotNull(left?.let { it to item }, right?.let { item to it }).forEach { (pairLeft, pairRight) ->
                    if (!checkedPairs.add(pairLeft.id to pairRight.id)) return@forEach
                    val leftWillBeDeleted = !pairLeft.deleted && deleteSet.contains(pairLeft.id)
                    val rightWillBeDeleted = !pairRight.deleted && deleteSet.contains(pairRight.id)
                    if (
                        leftWillBeDeleted != rightWillBeDeleted &&
                        pairLeft.canVirtuallyMerge(pairLeft, pairRight, logicallyAdjacent = true)
                    ) {
                        transaction.mergeStructs.add(pairRight.id)
                    }
                }
            }

        if (touchedItems.none { item -> item.parentSub != null }) return
        touchedItems.asSequence()
            .mapNotNull { item -> item.parentSub?.let { key -> item.parent to key } }
            .distinct()
            .forEach { (parent, parentSub) ->
            val logicalItems = mapItemOrder(parent, parentSub)
            logicalItems.zipWithNext().forEach { (left, right) ->
                val leftWillBeDeleted = !left.deleted && deleteSet.contains(left.id)
                val rightWillBeDeleted = !right.deleted && deleteSet.contains(right.id)
                if (
                    leftWillBeDeleted != rightWillBeDeleted &&
                    left.canVirtuallyMerge(left, right, logicallyAdjacent = true)
                ) {
                    transaction.mergeStructs.add(right.id)
                }
            }
        }
    }

    private fun expandDeleteSetWithNestedTypeContent(deleteSet: DeleteSet): DeleteSet {
        val initialItems = store.itemsOverlapping(deleteSet)
        if (initialItems.none { item ->
                item.content.directTypeRef()?.let { ref -> store.hasParent(ref.name) } == true
            }
        ) {
            return deleteSet
        }
        val expanded = deleteSet.copy()
        val queue = ArrayDeque<StoreItem>()
        initialItems.forEach(queue::add)

        val visited = linkedSetOf<Id>()
        while (queue.isNotEmpty()) {
            val item = queue.removeFirst()
            if (!visited.add(item.id)) continue
            val ref = item.content.directTypeRef() ?: continue
            store.itemsForParent(ref.name)
                .asSequence()
                .filterNot { child -> child.deleted }
                .forEach { child ->
                    if (!expanded.contains(child.id)) {
                        expanded.add(child.id, child.length)
                    }
                    queue.add(child)
                }
        }
        return expanded
    }

    private fun DeleteSet.addContiguousItems(items: Iterable<StoreItem>) {
        var runClient = -1L
        var runClock = 0L
        var runEnd = 0L
        fun flush() {
            if (runClient >= 0) add(Id(runClient, runClock), runEnd - runClock)
        }
        items.forEach { item ->
            if (item.id.client == runClient && item.id.clock == runEnd) {
                runEnd = item.clockEnd()
            } else {
                flush()
                runClient = item.id.client
                runClock = item.id.clock
                runEnd = item.clockEnd()
            }
        }
        flush()
    }

    private fun integrateRemote(items: List<StoreItem>) {
        val storeInitiallyEmpty = currentTransaction?.beforeState?.isEmpty() == true
        val textFormatParents = linkedSetOf<String>()
        val changedMapKeys = linkedSetOf<Pair<String, String>>()
        store.prepareBulkSequenceIntegration(items)
        if (pendingItems.isEmpty() && storeInitiallyEmpty) {
            items.forEach { item -> pendingItems.add(resolveRemoteParentAlias(item, checkStoreAnchors = false)) }
        } else if (pendingItems.isEmpty()) {
            items.forEach { item ->
                if (!store.contains(item.id)) pendingItems.add(resolveRemoteParentAlias(item))
            }
        } else {
            val pendingIds = pendingItems.mapTo(hashSetOf()) { item -> item.id }
            items.forEach { item ->
                if (!store.contains(item.id) && pendingIds.add(item.id)) {
                    pendingItems.add(resolveRemoteParentAlias(item))
                }
            }
        }
        val captureSnapshots = needsTransactionSnapshots()
        var madeProgress: Boolean
        do {
            madeProgress = false
            val iterator = pendingItems.iterator()
            while (iterator.hasNext()) {
                val pendingItem = iterator.next()
                val item = if (
                    pendingItem.unresolvedParent != null ||
                    isNestedName(pendingItem.parent)
                ) {
                    resolveRemoteParentAlias(pendingItem)
                } else {
                    pendingItem
                }
                if (item.id.clock < store.getClock(item.id.client)) {
                    iterator.remove()
                    madeProgress = true
                    continue
                }
                if (canIntegrate(item)) {
                    iterator.remove()
                    cleanRemoteOrigins(item)
                    if (captureSnapshots) captureParentBefore(item.parent, item.content.kind, item.parentSub)
                    if (store.add(item)) {
                        rememberMapKey(item)
                        item.parentSub?.let { key -> changedMapKeys.add(item.parent to key) }
                        if (shouldReapplyTextFormatsAfter(item)) {
                            textFormatParents.add(item.parent)
                        }
                        rememberNestedRefs(item.content)
                        recordAddedItem(item)
                        currentTransaction?.markChanged(item.parent, item.parentSub)
                    }
                    madeProgress = true
                }
            }
            if (pendingItems.isEmpty()) break
        } while (madeProgress)
        val supersededMapItems = DeleteSet.empty()
        changedMapKeys.forEach { (parent, key) ->
            val order = mapItemOrder(parent, key)
            val current = order.lastOrNull()
            store.cacheCurrentMapItem(parent, key, current)
            order.asSequence()
                .filterNot { item -> item.deleted || item.id == current?.id }
                .forEach { item -> supersededMapItems.add(item.id, item.length) }
        }
        if (!supersededMapItems.isEmpty) {
            applyDeleteSet(supersededMapItems, deferredTextFormatParents = textFormatParents)
        }
        // Pending delete ranges may point inside a newly integrated packed item. Apply them only
        // after struct integration, matching Yjs and allowing the normal boundary-split path to
        // select the exact UTF-16 clock range.
        if (!pendingDeletes.isEmpty) {
            applyDeleteSet(pendingDeletes.copy(), deferredTextFormatParents = textFormatParents)
        }
        textFormatParents.forEach(::reapplyTextFormats)
    }

    private fun cleanRemoteOrigins(item: StoreItem) {
        item.origin?.let { origin ->
            if (
                origin.client == item.id.client &&
                checkedClockAdd(origin.clock, 1, "remote origin clock") == item.id.clock
            ) return@let
            val anchor = store.getStoreItem(origin) ?: return@let
            if (!anchor.isGc && origin.clock < anchor.lastId.clock) {
                store.getStoreItemCleanEnd(origin) { right -> currentTransaction?.mergeStructs?.add(right.id) }
            }
        }
        item.rightOrigin?.let { rightOrigin ->
            val anchor = store.getStoreItem(rightOrigin) ?: return@let
            if (!anchor.isGc && rightOrigin.clock > anchor.id.clock) {
                store.getStoreItemCleanStart(rightOrigin) { right -> currentTransaction?.mergeStructs?.add(right.id) }
            }
        }
    }

    private fun resolveRemoteParentAlias(item: StoreItem, checkStoreAnchors: Boolean = true): StoreItem {
        if (
            checkStoreAnchors &&
            (
                item.origin?.let(store::getStoreItem)?.isGc == true ||
                    item.rightOrigin?.let(store::getStoreItem)?.isGc == true
            )
        ) {
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
                    anchor.parentSub != null -> knownParentKind(anchor.parent)
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
            null -> return knownParentKind(item.parent)?.let { kind ->
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
        val directlyMergeable = previousMapCurrent.canVirtuallyMerge(
            previousMapCurrent,
            item,
            logicallyAdjacent = true,
        )
        // A same-client primitive map replacement is already known to be the exact adjacent pair.
        // Rebuilding and scanning the complete key history for every set makes a transaction of N
        // replacements quadratic. The delete-set cleanup records the resulting deleted merge, so
        // the new visible item is not a temporary split candidate.
        if (
            previousMapCurrent.content.isPrimitiveMapHistory() &&
            item.content.isPrimitiveMapHistory()
        ) {
            deleteKnownPrimitiveMapItem(previousMapCurrent)
        } else {
            applyDeleteSet(deleteSet, registerMergeCandidates = !directlyMergeable)
        }
    }

    private fun deleteKnownPrimitiveMapItem(item: StoreItem) {
        if (item.deleted) return
        val transaction = checkNotNull(currentTransaction) { "map replacement requires an active transaction" }
        val captured = item.copy(deleted = false).takeIf { hasTransactionConsumers() }
        if (needsTransactionSnapshots()) {
            captureParentBefore(item.parent, item.content.kind, item.parentSub)
        }
        pendingDeletes.add(item.id, item.length)
        if (!store.markDeleted(listOf(item))) return
        transaction.deleteSet.add(item.id, item.length)
        if (captured != null) {
            transaction.recordDeleted(captured, hasSubdocRefs = false)
        } else {
            transaction.recordDeletedMetadata(hasSubdocRefs = false)
        }
        transaction.markChanged(item.parent, item.parentSub)
    }

    private fun ItemContent.isPrimitiveMapHistory(): Boolean = when (this) {
        is ItemContent.MapEntry -> value.isPrimitiveMapHistory()
        is ItemContent.MapEntries -> values.all { value -> value.isPrimitiveMapHistory() }
        else -> false
    }

    private fun YValue.isPrimitiveMapHistory(): Boolean = when (this) {
        is YValue.TypeRef,
        is YValue.SubdocRef,
        is YValue.ListValue,
        is YValue.MapValue,
        is YValue.BinaryValue -> false
        else -> true
    }

    private fun canIntegrate(item: StoreItem): Boolean {
        val textFormat = item.content as? ItemContent.TextFormat
        if (item.unresolvedParent != null) {
            return false
        }
        val clientClock = store.getClock(item.id.client)
        fun containsAnchor(id: Id?): Boolean = id == null ||
            (
                item.requiresClockContinuity &&
                    id.client == item.id.client &&
                    clientClock > 0 &&
                    id.clock == clientClock - 1
            ) ||
            store.contains(id)
        return (!item.requiresClockContinuity || item.id.clock == clientClock) &&
            containsAnchor(item.origin) &&
            (textFormat == null || containsAnchor(textFormat.target)) &&
            containsAnchor(item.rightOrigin)
    }

    private fun applyTextFormat(item: StoreItem): List<StoreItem> {
        if (!item.content.isTextFormatControl()) return emptyList()
        return reapplyTextFormats(item.parent)
    }

    private fun shouldReapplyTextFormatsAfter(item: StoreItem): Boolean =
        item.content is ItemContent.TextFormat ||
            store.hasVisibleLegacyTextFormat(item.parent) &&
            (item.content is ItemContent.NativeTextFormat || item.content.isTextCountable())

    private fun reapplyTextFormats(parent: String): List<StoreItem> {
        if (!store.hasVisibleLegacyTextFormat(parent)) return emptyList()
        val activeNativeAttributes = linkedMapOf<String, YValue>()
        var activeEffectiveAttributes: Map<String, YValue> = emptyMap()
        val nativeReplacements = linkedMapOf<Id, ItemContent>()
        sequence(parent).toList().forEach { item ->
            if (item.deleted) return@forEach
            when (val content = item.content) {
                is ItemContent.NativeTextFormat -> {
                    activeNativeAttributes[content.key] = content.value
                    activeEffectiveAttributes = activeNativeAttributes
                        .filterValues { value -> value != YValue.Null }
                        .toSortedMap()
                }
                is ItemContent.Text,
                is ItemContent.TextEmbed,
                is ItemContent.XmlType -> {
                    val attributes = if (content.baseTextAttributesOrEmpty().isEmpty()) {
                        activeEffectiveAttributes
                    } else {
                        content.effectiveTextAttributes(activeNativeAttributes)
                    }
                    content.withTextAttributesOrNull(attributes)
                        ?.takeIf { nextContent -> nextContent != content }
                        ?.let { nextContent -> nativeReplacements[item.id] = nextContent }
                }
                else -> Unit
            }
        }
        val changed = store.replaceEquivalentContents(nativeReplacements).toMutableList()
        sequence(parent).toList()
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
        if (
            !hasNonTypeTransactionConsumers() &&
            (
                snapshotInterestedTypes.isEmpty() ||
                    transaction.changedParents.none(::needsParentSnapshot)
            )
        ) {
            cleanupUnobservedTransaction(transaction)
            return
        }
        if (hasSimpleStandardUpdateConsumersOnly()) {
            emitSimpleStandardUpdate(transaction)
            return
        }
        pendingTransactionEmits.addLast(transaction)
        if (isEmittingTransactions) return
        isEmittingTransactions = true
        var firstError: Throwable? = null
        try {
            while (pendingTransactionEmits.isNotEmpty()) {
                val batch = mutableListOf<YTransactionEvent>()
                // Retain the representative id of cleanup-merged fragments for later events in
                // the same cleanup batch, matching Yjs transaction cleanup.
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

    /**
     * Fast server path for `observeUpdates`: no transaction/type event DTOs or whole-store
     * representative maps are needed when the standard update bytes are the only observation.
     */
    private fun emitSimpleStandardUpdate(transaction: Transaction) {
        val beforeState = transaction.beforeState
        val afterState = store.stateVector()
        val hasWireContent = afterState.any { (client, clock) ->
            clock > (beforeState[client] ?: 0)
        } || !transaction.deleteSet.isEmpty
        cleanupUnobservedTransaction(transaction)
        if (!hasWireContent) return

        val updateItems = store.itemsSince(beforeState)
        val update = transaction.validatedStandardUpdate
            ?: UpdateCodec.encode(transactionDocumentUpdate(updateItems, transaction.deleteSet))
        callAllYksCallbacks(updateListeners.toList()) { listener ->
            listener(update, transaction.origin)
        }
    }

    private fun cleanupUnobservedTransaction(transaction: Transaction) {
        val afterState = store.stateVector()
        transaction.afterState = afterState
        if (gc && !transaction.deleteSet.isEmpty) {
            collectGarbageNow(this, transaction.deleteSet.toIdSet(), gcFilter)
        }
        store.mergeDeletedItems(transaction.deleteSet)
        store.mergeSplitCandidates(transaction.mergeStructs)
        mergeNewItemsUnobserved(transaction.beforeState, afterState)
        mergeSplitCandidatesUnobserved(transaction.mergeStructs)
        collectSubdocEvent(transaction)?.removed?.forEach { subdoc ->
            if (!subdoc.isDestroyed) subdoc.destroy()
        }
    }

    /**
     * Packs newly appended text without constructing observer-only representative maps.
     *
     * Only the changed client suffix can contain a new physical merge boundary. Keeping the
     * scan on that suffix mirrors Yjs' cleanup pass and avoids rebuilding every logical parent
     * for transactions that have no observers.
     */
    private fun mergeNewItemsUnobserved(beforeState: StateVector, afterState: StateVector) {
        afterState.forEach { (client, afterClock) ->
            val beforeClock = beforeState[client] ?: 0
            if (beforeClock == afterClock) return@forEach

            val clientItems = store.itemsForClient(client)
            var index = maxOf(store.firstItemEndingAfter(client, beforeClock), 1)
            while (true) {
                if (index >= clientItems.size) break
                val firstIndex = index - 1
                var cursor = index
                while (cursor < clientItems.size) {
                    val left = clientItems[cursor - 1]
                    val right = clientItems[cursor]
                    if (
                        !left.canVirtuallyMergeIgnoringAdjacency(left, right) ||
                        !areLogicallyAdjacent(left, right)
                    ) break
                    cursor++
                }
                val itemCount = cursor - firstIndex
                if (itemCount > 1 && store.mergeCompatibleItemsAt(client, firstIndex, itemCount) != null) {
                    // The merged item may also merge with the item now at this index.
                    continue
                }
                index++
            }
        }
    }

    /** Re-packs temporary text splits in reverse split order, matching Yjs cleanup. */
    private fun mergeSplitCandidatesUnobserved(candidates: List<Id>) {
        for (candidateIndex in candidates.lastIndex downTo 0) {
            val candidate = candidates[candidateIndex]
            val clientItems = store.itemsForClient(candidate.client)
            if (clientItems.size < 2) continue
            val rightIndex = store.firstItemEndingAfter(candidate.client, candidate.clock)
            if (rightIndex !in 1 until clientItems.size) continue
            val right = clientItems[rightIndex]
            if (right.id != candidate) continue
            val left = clientItems[rightIndex - 1]
            if (
                left.canVirtuallyMergeIgnoringAdjacency(left, right) &&
                areLogicallyAdjacent(left, right)
            ) {
                store.mergeCompatibleItemsAt(candidate.client, rightIndex - 1, 2)
            }
        }
    }

    private fun hasBeforeAllTransactionListeners(): Boolean =
        beforeAllTransactionListeners.isNotEmpty() ||
            docOnlyEventListeners["beforeAllTransactions"].orEmpty().isNotEmpty() ||
            eventListeners["beforeAllTransactions"].orEmpty().isNotEmpty()

    /** Empty transactions do not produce update events, so update-only consumers need no cleanup pass. */
    private fun hasNonUpdateTransactionConsumers(): Boolean =
        beforeAllTransactionListeners.isNotEmpty() ||
            beforeTransactionListeners.isNotEmpty() ||
            beforeObserverCallsListeners.isNotEmpty() ||
            afterTransactionListeners.isNotEmpty() ||
            afterTransactionCleanupListeners.isNotEmpty() ||
            afterAllTransactionsListeners.isNotEmpty() ||
            transactionListeners.isNotEmpty() ||
            transactionEventListeners.isNotEmpty() ||
            afterAllTransactionsEventListeners.isNotEmpty() ||
            subdocObservers.isNotEmpty() ||
            subdocEventListeners.isNotEmpty() ||
            docOnlyEventListeners.isNotEmpty() ||
            eventListeners.any { (name, listeners) ->
                listeners.isNotEmpty() && name !in setOf("update", "updateV2", "updateLossless", "updateV2Lossless")
            }

    private fun hasNonTypeTransactionConsumers(): Boolean =
        beforeAllTransactionListeners.isNotEmpty() ||
            beforeTransactionListeners.isNotEmpty() ||
            beforeObserverCallsListeners.isNotEmpty() ||
            afterTransactionListeners.isNotEmpty() ||
            afterTransactionCleanupListeners.isNotEmpty() ||
            afterAllTransactionsListeners.isNotEmpty() ||
            transactionListeners.isNotEmpty() ||
            transactionEventListeners.isNotEmpty() ||
            afterAllTransactionsEventListeners.isNotEmpty() ||
            subdocObservers.isNotEmpty() ||
            subdocEventListeners.isNotEmpty() ||
            updateListeners.isNotEmpty() ||
            updateEventListeners.isNotEmpty() ||
            updateV2EventListeners.isNotEmpty() ||
            updateLosslessEventListeners.isNotEmpty() ||
            updateV2LosslessEventListeners.isNotEmpty() ||
            eventListeners.isNotEmpty() ||
            docOnlyEventListeners["beforeAllTransactions"].orEmpty().isNotEmpty()

    private fun hasTransactionConsumers(): Boolean =
        currentTransaction?.captureItems == true ||
            hasNonTypeTransactionConsumers() ||
            snapshotInterestedTypes.isNotEmpty()

    private fun hasSimpleStandardUpdateConsumersOnly(): Boolean =
        updateListeners.isNotEmpty() &&
            updateEventListeners.isEmpty() &&
            updateV2EventListeners.isEmpty() &&
            updateLosslessEventListeners.isEmpty() &&
            updateV2LosslessEventListeners.isEmpty() &&
            eventListeners.isEmpty() &&
            !hasNonUpdateTransactionConsumers() &&
            !needsTransactionSnapshots()

    private fun createTransactionEvent(transaction: Transaction): YTransactionEvent {
        val updateItems = transaction.addedItems.toList()
        val updateDeleteSet = transaction.deleteSet.copy()
        val deferredUpdate = lazy(LazyThreadSafetyMode.NONE) {
            UpdateCodec.encodeLossless(
                transactionDocumentUpdate(
                    updateItems.map { item -> item.copy(requiresClockContinuity = true) },
                    updateDeleteSet,
                ),
            )
        }
        val changedParents = changedParentsFor(transaction)
        transaction.afterState = store.stateVector()
        transaction.deferUpdate { deferredUpdate.value }
        val eventItems = YTransactionEventItems(
            added = transaction.addedItems.toList(),
            deleted = transaction.deletedItems.toList(),
        )
        val subdocEvent = collectSubdocEvent(transaction)
        return YTransactionEvent(
            doc = this,
            origin = transaction.origin,
            local = transaction.local,
            update = null,
            updateProvider = { deferredUpdate.value },
            beforeState = transaction.beforeState,
            afterState = transaction.afterState,
            insertSet = null,
            deleteSet = transaction.deleteSet.copy(),
            deleteIdSet = null,
            cleanUps = transaction.cleanUps.copy(),
            meta = transaction.meta,
            addedStructs = null,
            deletedStructs = null,
            addedItems = null,
            deletedItems = null,
            eventItems = eventItems,
            changedParents = changedParents,
            changedTypes = changedParents.mapNotNull { typeForParent(it) }.toSet(),
            subdocsAdded = subdocEvent?.added?.toCollection(linkedSetOf()) ?: emptySet(),
            subdocsRemoved = subdocEvent?.removed?.toCollection(linkedSetOf()) ?: emptySet(),
            subdocsLoaded = subdocEvent?.loaded?.toCollection(linkedSetOf()) ?: emptySet(),
        )
    }

    private fun transactionDocumentUpdate(
        items: List<StoreItem>,
        deleteSet: DeleteSet,
    ): DocumentUpdate {
        val parents = items.asSequence().map(StoreItem::parent).toCollection(linkedSetOf())
        val parentItemIds = parents.mapNotNull { parent ->
            store.firstOwnerForNested(parent)?.id?.let { id -> parent to id }
        }.toMap()
        val parentKinds = parents.mapNotNull { parent ->
            knownParentKind(parent)?.let { kind -> parent to kind }
        }.toMap()
        return DocumentUpdate(items, deleteSet, parentItemIds, parentKinds)
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
            transactionDocumentUpdate(
                store.itemsSince(transaction.beforeState).map { item -> item.copy() },
                transaction.deleteSet.copy(),
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
            transactionListeners.toList().forEach { listener ->
                observerCallbacks.add { listener(event) }
            }
            val interestedParents = if (snapshotInterestedTypes.isEmpty()) {
                emptyList()
            } else {
                event.changedParents.filter(::needsParentSnapshot)
            }
            if (interestedParents.isNotEmpty()) {
                val eventUpdateItems = store.itemsSince(transaction.beforeState)
                val effectiveSets = lazy(LazyThreadSafetyMode.NONE) {
                    val inserts = createIdSet().also { ids ->
                        eventUpdateItems.forEach { item ->
                            val representative = mergedStructRepresentatives[item.id] ?: item.id
                            if (representative.clock >= (transaction.beforeState[representative.client] ?: 0)) {
                                ids.add(item.id, item.length)
                            }
                        }
                    }
                    val deletes = createDeleteSet().also { deleteSet ->
                        store.itemsOverlapping(transaction.deleteSet).forEach { item ->
                            val representative = mergedStructRepresentatives[item.id] ?: item.id
                            if (transaction.deleteSet.contains(representative)) {
                                deleteSet.add(item.id, item.length)
                            }
                        }
                        mergedStructRepresentatives.forEach { (member, representative) ->
                            if (!transaction.deleteSet.contains(representative)) return@forEach
                            store.getStoreItem(member)?.let { item -> deleteSet.add(item.id, item.length) }
                        }
                    }
                    YEventSets(inserts, deletes)
                }
                val directEvents = linkedMapOf<String, YEvent>()
                interestedParents.forEach { parent ->
                    typeForParent(parent)?.let { type ->
                        val yEvent = createEvent(
                            type,
                            transaction,
                            event,
                            transaction.beforeParents[parent],
                            effectiveSets::value,
                            event::update,
                        )
                        directEvents[parent] = yEvent
                        if (type.hasDirectEventInterest) {
                            observerCallbacks.add { type.emit(yEvent) }
                        }
                    }
                }
                observerCallbacks.add { emitDeepEvents(transaction, event::update, directEvents) }
            }
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
        if (candidates.isEmpty() || representatives.isEmpty()) return
        val membersByRepresentative = representatives.entries.groupBy(
            keySelector = { entry -> entry.value },
            valueTransform = { entry -> entry.key },
        )
        candidates.forEach { candidate ->
            val representative = representatives[candidate] ?: return@forEach
            membersByRepresentative[representative].orEmpty().forEach { id -> representatives[id] = id }
        }
    }

    /** Mirrors cleanupTransactions' afterState scan, including its firstChangePos bound. */
    private fun mergeNewStructRepresentatives(
        beforeState: StateVector,
        afterState: StateVector,
        representatives: MutableMap<Id, Id>,
    ) {
        val physicallyMergeableTextGroups = mutableListOf<List<Id>>()
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
                    clientItems[groupStart - 1].canVirtuallyMergeIgnoringAdjacency(
                        clientItems[groupStart - 1],
                        clientItems[groupStart],
                    ) &&
                    areLogicallyAdjacent(clientItems[groupStart - 1], clientItems[groupStart])
                ) {
                    groupStart--
                }
                if (groupStart < index) {
                    val representative = clientItems[groupStart].id
                    for (memberIndex in groupStart..index) {
                        representatives[clientItems[memberIndex].id] = representative
                    }
                    physicallyMergeableTextGroups.add(
                        (groupStart..index).map { memberIndex -> clientItems[memberIndex].id },
                    )
                } else {
                    representatives[clientItems[index].id] = clientItems[index].id
                }
                index = groupStart - 1
            }
        }
        physicallyMergeableTextGroups.forEach { ids ->
            store.mergeCompatibleItems(ids)
        }
    }

    /** Mirrors the targeted right-then-left `_mergeStructs` phase for virtual unit items. */
    private fun mergeSplitCandidateRepresentatives(
        candidates: List<Id>,
        representatives: MutableMap<Id, Id>,
    ) {
        if (candidates.isEmpty()) return
        val processed = hashSetOf<Id>()
        candidates.asReversed().forEach { candidate ->
            val seed = store.getStoreItem(candidate) ?: return@forEach
            if (!processed.add(seed.id)) return@forEach
            val group = ArrayDeque<StoreItem>().also { items -> items.add(seed) }

            if (seed.parentSub == null) {
                var current = seed
                while (true) {
                    val left = store.sequenceNeighbors(current).first ?: break
                    if (!left.canVirtuallyMerge(left, current, logicallyAdjacent = true)) break
                    group.addFirst(left)
                    processed.add(left.id)
                    current = left
                }
                current = seed
                while (true) {
                    val right = store.sequenceNeighbors(current).second ?: break
                    if (!current.canVirtuallyMerge(current, right, logicallyAdjacent = true)) break
                    group.addLast(right)
                    processed.add(right.id)
                    current = right
                }
            } else {
                val logicalItems = mapItemOrder(seed.parent, checkNotNull(seed.parentSub))
                val seedIndex = store.cachedMapItemIndex(
                    seed.parent,
                    checkNotNull(seed.parentSub),
                    seed.id,
                ) { logicalItems }
                if (seedIndex >= 0) {
                    var index = seedIndex
                    while (
                        index > 0 &&
                        logicalItems[index - 1].canVirtuallyMerge(
                            logicalItems[index - 1],
                            logicalItems[index],
                            logicallyAdjacent = true,
                        )
                    ) {
                        group.addFirst(logicalItems[--index])
                    }
                    index = seedIndex
                    while (
                        index + 1 < logicalItems.size &&
                        logicalItems[index].canVirtuallyMerge(
                            logicalItems[index],
                            logicalItems[index + 1],
                            logicallyAdjacent = true,
                        )
                    ) {
                        group.addLast(logicalItems[++index])
                    }
                }
            }
            if (group.size > 1) {
                val representative = group.first().id
                group.forEach { item -> representatives[item.id] = representative }
            }
        }
    }

    private fun areLogicallyAdjacent(left: StoreItem, right: StoreItem): Boolean {
        if (left.parent != right.parent || left.parentSub != right.parentSub) return false
        val key = left.parentSub
        if (key == null) return store.areSequenceAdjacent(left, right)
        val logicalItems = mapItemOrder(left.parent, key)
        val index = store.cachedMapItemIndex(left.parent, key, left.id) { logicalItems }
        return index >= 0 && logicalItems.getOrNull(index + 1)?.id == right.id
    }

    private fun StoreItem.canVirtuallyMerge(
        previous: StoreItem,
        right: StoreItem,
        logicallyAdjacent: Boolean,
    ): Boolean {
        return logicallyAdjacent && canVirtuallyMergeIgnoringAdjacency(previous, right)
    }

    private fun StoreItem.canVirtuallyMergeIgnoringAdjacency(
        previous: StoreItem,
        right: StoreItem,
    ): Boolean {
        // Yjs can retain malformed-but-decodable zero-length ContentString items. Its Item
        // cleanup keeps them as separate structs, and there is no valid last clock to anchor a
        // merge to.
        if (previous.length == 0L || length == 0L || right.length == 0L) return false
        val previousLastId = Id(
            previous.id.client,
            checkedClockAdd(previous.id.clock, previous.length - 1, "virtual merged item last id"),
        )
        val mergeClass = content.virtualMergeClass() ?: return false
        return id.client == right.id.client &&
            checkedClockAdd(previous.id.clock, previous.length, "virtual merged item end") == right.id.clock &&
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
        is ItemContent.ArrayValues -> if (
            values.any { value ->
                value is YValue.BinaryValue || value is YValue.TypeRef || value is YValue.SubdocRef
            }
        ) null else VirtualMergeClass.Any
        is ItemContent.MapEntry -> when (value) {
            is YValue.BinaryValue,
            is YValue.TypeRef,
            is YValue.SubdocRef -> null
            else -> VirtualMergeClass.Any
        }
        is ItemContent.MapEntries -> if (
            values.any { value ->
                value is YValue.BinaryValue || value is YValue.TypeRef || value is YValue.SubdocRef
            }
        ) null else VirtualMergeClass.Any
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
        if (
            beforeTransactionListeners.isEmpty() &&
            transactionEventListeners["beforeTransaction"].orEmpty().isEmpty() &&
            eventListeners["beforeTransaction"].orEmpty().isEmpty()
        ) return
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
        update: () -> ByteArray,
        directEvents: Map<String, YEvent>,
    ) {
        if (directEvents.isEmpty()) return
        val deepTypes = snapshotInterestedTypes.values.filter(AbstractYType::hasDeepEventInterest)
        if (deepTypes.isEmpty()) return
        val grouped = linkedMapOf<AbstractYType, MutableList<YEvent>>()
        directEvents.forEach { (changedParent, directEvent) ->
            val matched = mutableSetOf<String>()
            val seen = mutableSetOf(changedParent)
            var current = changedParent
            var path = emptyList<Any>()
            while (true) {
                snapshotInterestedTypes[current]
                    ?.takeIf(AbstractYType::hasDeepEventInterest)
                    ?.let { ancestor ->
                        matched.add(ancestor.name)
                        grouped.getOrPut(ancestor) { mutableListOf() }
                            .add(
                                directEvent.copyForDeep(
                                    path = path,
                                    changedTarget = directEvent.target,
                                    currentTarget = ancestor,
                                ),
                            )
                    }
                val step = directParentPathStep(current) ?: break
                if (!seen.add(step.parent)) break
                path = step.segments + path
                current = step.parent
            }
            // Private lossless values may contain nested references below JSON-like containers.
            // Standard ContentType owners always take the indexed upward path above.
            if (matched.size < deepTypes.size) {
                deepTypes.forEach { ancestor ->
                    if (ancestor.name in matched) return@forEach
                    val fallbackPath = pathBetweenByTraversal(ancestor.name, changedParent, baseRenderer)
                        ?: return@forEach
                    grouped.getOrPut(ancestor) { mutableListOf() }
                        .add(
                            directEvent.copyForDeep(
                                path = fallbackPath,
                                changedTarget = directEvent.target,
                                currentTarget = ancestor,
                            ),
                        )
                }
            }
        }
        val callbacks = mutableListOf<() -> Unit>()
        grouped.forEach { (ancestor, events) ->
            val sortedEvents = events.sortedBy { childEvent -> childEvent.path.size }
            ancestor.clearCache()
            val event = if (sortedEvents.size == 1) {
                val only = sortedEvents.single()
                if (only.target == ancestor) {
                    only.copyForDeep(deepEvents = sortedEvents)
                } else {
                    YEvent(
                        target = ancestor,
                        origin = transaction.origin,
                        update = ByteArray(0),
                        insertSet = createIdSet(),
                        deleteSet = DeleteSet.empty(),
                        transaction = only.transaction,
                        currentTarget = ancestor,
                        path = only.path,
                        changedTarget = only.target,
                        deepEvents = sortedEvents,
                    ).deferUpdate(update).deferSets {
                        YEventSets(only.insertSet, only.deleteSet)
                    }
                }
            } else {
                YEvent(
                    target = ancestor,
                    origin = transaction.origin,
                    update = ByteArray(0),
                    insertSet = createIdSet(),
                    deleteSet = DeleteSet.empty(),
                    transaction = sortedEvents.firstOrNull()?.transaction,
                    currentTarget = ancestor,
                    changedTarget = ancestor,
                    deepEvents = sortedEvents,
                ).deferUpdate(update).deferSets {
                    val first = sortedEvents.first()
                    YEventSets(first.insertSet, first.deleteSet)
                }
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

    private fun captureParentBefore(parent: String, kindHint: RootKind, keyHint: String? = null) {
        val transaction = currentTransaction ?: return
        if (!needsParentSnapshot(parent)) return
        val existing = transaction.beforeParents[parent]
        val snapshotKind = if (kindHint == RootKind.XmlHook || knownParentKind(parent) == RootKind.XmlHook) {
            RootKind.Map
        } else {
            kindHint
        }
        if (
            (
                snapshotKind == RootKind.Map &&
                    existing?.mapSnapshot()?.let { snapshot ->
                        snapshot.complete || keyHint != null && keyHint in snapshot.capturedKeys
                    } == true
            ) ||
            (snapshotKind != RootKind.Map && existing?.sequenceSnapshot()?.kind == snapshotKind)
        ) {
            return
        }
        val captured = when (snapshotKind) {
            RootKind.Map -> if (keyHint == null) {
                ParentSnapshot.MapSnapshot(
                    values = visibleMap(parent),
                    itemIds = visibleMapItemIds(parent),
                    capturedKeys = mapKeysInInsertionOrder(parent).toSet(),
                    complete = true,
                )
            } else {
                ParentSnapshot.MapSnapshot(
                    values = visibleMapValue(parent, keyHint)?.let { value -> mapOf(keyHint to value) }.orEmpty(),
                    itemIds = currentVisibleMapItemId(parent, keyHint)?.let { id -> mapOf(keyHint to id) }.orEmpty(),
                    capturedKeys = setOf(keyHint),
                )
            }
            RootKind.Array,
            RootKind.XmlFragment,
            RootKind.XmlElement -> ParentSnapshot.SequenceSnapshot(snapshotKind, emptyList())
            RootKind.Text,
            RootKind.XmlText -> ParentSnapshot.SequenceSnapshot(
                snapshotKind,
                visibleSequence(parent).map(::renderedTextItem),
            )
            RootKind.XmlHook -> error("XML hook snapshots use map semantics")
        }
        transaction.beforeParents[parent] = existing?.merge(captured) ?: captured
    }

    private fun needsTransactionSnapshots(): Boolean {
        if (hasGlobalSnapshotConsumer()) return true
        return snapshotInterestedTypes.isNotEmpty()
    }

    private fun hasGlobalSnapshotConsumer(): Boolean =
        (
            beforeObserverCallsListeners.isNotEmpty() ||
                transactionEventListeners["beforeObserverCalls"].orEmpty().isNotEmpty() ||
                eventListeners["beforeObserverCalls"].orEmpty().isNotEmpty()
            )

    private fun needsParentSnapshot(parent: String): Boolean {
        if (hasGlobalSnapshotConsumer()) return true
        val seen = mutableSetOf(parent)
        var current = parent
        var direct = true
        while (true) {
            snapshotInterestedTypes[current]?.let { type ->
                if (direct && type.needsTransactionSnapshot || !direct && type.hasDeepEventInterest) return true
            }
            val step = directParentPathStep(current) ?: break
            if (!seen.add(step.parent)) break
            current = step.parent
            direct = false
        }
        // Only private lossless nested values require the materializing traversal fallback.
        return snapshotInterestedTypes.values.any { type ->
            type.hasDeepEventInterest && pathBetweenByTraversal(type.name, parent, baseRenderer) != null
        }
    }

    internal fun refreshTypeSnapshotInterest(type: AbstractYType) {
        ensureThreadAccess()
        snapshotInterestedTypes.entries.removeAll { (_, interested) -> interested === type }
        if (type.doc === this && !type.isDestroyed && type.needsTransactionSnapshot) {
            snapshotInterestedTypes[type.name] = type
        }
    }

    internal fun removeTypeSnapshotInterest(type: AbstractYType) {
        ensureThreadAccess()
        snapshotInterestedTypes.entries.removeAll { (_, interested) -> interested === type }
    }

    private fun createEvent(
        type: AbstractYType,
        transaction: Transaction,
        event: YTransactionEvent,
        before: ParentSnapshot?,
        effectiveSets: () -> YEventSets,
        update: () -> ByteArray,
    ): YEvent {
        val changedSubs = transaction.changedParentSubs[type.name].orEmpty()
        val changedKeys = changedSubs.filterNotNull().toSet()
        val deferredMapDetails = before?.mapSnapshot()?.let { mapBefore ->
            val afterValues = changedKeys
                .mapNotNull { key -> visibleMapValue(type.name, key)?.let { value -> key to value } }
                .toMap()
            val afterItemIds = changedKeys
                .mapNotNull { key -> currentVisibleMapItemId(type.name, key)?.let { id -> key to id } }
                .toMap()
            val provider: () -> YEventMapDetails = {
                val changes = diffMapChanges(
                    before = mapBefore.values,
                    after = afterValues,
                    beforeItemIds = mapBefore.itemIds,
                    afterItemIds = afterItemIds,
                    changedKeys = changedKeys,
                )
                YEventMapDetails(
                    changes = changes,
                    delta = changes.toMapDelta(),
                    value = changedKeys.singleOrNull()
                        ?.let(afterValues::get)
                        ?.let(::valueToAny),
                )
            }
            provider
        }
        val sequenceBefore = before?.sequenceSnapshot()
        val sequenceSets by lazy(LazyThreadSafetyMode.NONE, effectiveSets)
        val arrayDelta = when {
            sequenceBefore == null -> emptyList()
            sequenceBefore.kind == RootKind.Array && type.kind == RootKind.Array -> {
                diffArrayDelta(type as YArray, sequenceSets.insertSet, sequenceSets.deleteSet)
            }
            sequenceBefore.kind == RootKind.XmlFragment && type.kind == RootKind.XmlFragment -> {
                diffXmlDelta(type as YXmlSharedType, sequenceSets.insertSet, sequenceSets.deleteSet)
            }
            sequenceBefore.kind == RootKind.XmlElement && type.kind == RootKind.XmlElement -> {
                diffXmlDelta(type as YXmlSharedType, sequenceSets.insertSet, sequenceSets.deleteSet)
            }
            else -> emptyList()
        }
        val textDelta = if (
            sequenceBefore != null &&
            sequenceBefore.kind == type.kind &&
            (type.kind == RootKind.Text || type.kind == RootKind.XmlText)
        ) {
            diffTextDelta(type as YText, sequenceBefore.items, sequenceSets.insertSet, sequenceSets.deleteSet)
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
            update = ByteArray(0),
            insertSet = createIdSet(),
            deleteSet = DeleteSet.empty(),
            transaction = event,
            keysChanged = changedKeys,
            name = changedKeys.singleOrNull(),
            arrayDelta = arrayDelta,
            textDelta = textDelta,
            childListChanged = childListChanged,
        ).deferUpdate(update).deferSets(effectiveSets).also { yEvent ->
            deferredMapDetails?.let(yEvent::deferMapDetails)
        }
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
        diffSequenceEvent(type, effectiveInsertSet, effectiveDeleteSet, ::arrayItemValues)

    private fun diffXmlDelta(
        type: YXmlSharedType,
        effectiveInsertSet: IdSet,
        effectiveDeleteSet: DeleteSet,
    ): List<YArrayDeltaOp> =
        diffSequenceEvent(type, effectiveInsertSet, effectiveDeleteSet) { item ->
            listOf(when (val content = item.content) {
                is ItemContent.XmlType -> typeFromXmlType(content)
                else -> content.toXmlEventJson(this)
            })
        }

    /**
     * Mirrors YEvent.changes' linked-list scan. Looking only at a common prefix/suffix turns
     * two disjoint edits into a delete-and-reinsert of the unchanged middle of the sequence.
     */
    private fun diffSequenceEvent(
        type: AbstractYType,
        effectiveInsertSet: IdSet,
        effectiveDeleteSet: DeleteSet,
        insertedValues: (StoreItem) -> List<Any?>,
    ): List<YArrayDeltaOp> {
        val delta = mutableListOf<YArrayDeltaOp>()
        sequence(type.name)
            .filter { item -> item.content.kind == type.kind && item.countable }
            .forEach { item ->
                val added = effectiveInsertSet.hasId(item.id)
                val deleted = effectiveDeleteSet.contains(item.id)
                when {
                    item.deleted && deleted && !added -> delta.appendDelete(item.length.toDeltaLength())
                    !item.deleted && added -> insertedValues(item).forEach { value -> delta.appendInsert(value) }
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
        val beforeByClient = before
            .groupBy { item -> item.id.client }
            .mapValues { (_, items) -> items.sortedBy { item -> item.id.clock } }

        fun beforeContentAt(id: Id): ItemContent? {
            val items = beforeByClient[id.client] ?: return null
            var low = 0
            var high = items.size - 1
            while (low <= high) {
                val middle = (low + high) ushr 1
                val candidate = items[middle]
                when {
                    id.clock < candidate.id.clock -> high = middle - 1
                    id.clock >= candidate.clockEnd() -> low = middle + 1
                    else -> return candidate.content
                }
            }
            return null
        }

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
                        val oldContent = beforeContentAt(item.id)
                        val attributes = if (oldContent == null) {
                            emptyMap()
                        } else {
                            textAttributeDiff(
                                oldContent.textAttributesOrEmpty(),
                                renderedTextAttributes(item),
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
        val attributes = textAttributesToPublic(renderedTextAttributes(item))
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

    private inline fun <reified T : AbstractYType> getOrCreate(
        name: String,
        kind: RootKind,
        factory: () -> T,
    ): T {
        ensureThreadAccess()
        resolveRootSchema(name)?.let { schema ->
            if (schema.kind != kind) {
                throw YRootSchemaConflictException(name, "configured ${schema.kind}, getter requested $kind")
            }
        }
        val existing = rootTypes[name]
        if (existing != null) {
            require(existing.kind == kind) { "root type '$name' already exists as ${existing.kind}" }
            return checkNotNull(existing as? T) {
                "root type '$name' has kind $kind but unexpected runtime type ${existing::class.qualifiedName}"
            }
        }
        require(!isNestedName(name)) { "nested type '$name' cannot be opened as a root type" }
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
        if (!store.hasParent(name)) return
        var changed = false
        store.itemsForParent(name)
            .asSequence()
            .filter { item -> item.parentSub == null }
            .forEach { item ->
                val normalized = item.content.withRemoteParentKind(kind)
                if (normalized != item.content) {
                    checkNotNull(store.replaceContent(item.id, normalized)) {
                        "root item disappeared while materializing '$name'"
                    }
                    changed = true
                }
            }
        if (kind in setOf(RootKind.Text, RootKind.XmlText) && changed) {
            reapplyTextFormats(name)
        }
    }

    private fun <T : AbstractYType> createNestedType(kind: RootKind, factory: (String) -> T): T {
        ensureThreadAccess()
        val name = nextNestedTypeName()
        return factory(name).also { type ->
            captureTypeStateForMutation(type)
            check(type.kind == kind) { "nested type factory returned ${type.kind}, expected $kind" }
            type.markDetached()
            type.reserve(this, name)
            nestedTypes[name] = type
        }
    }

    private fun nextNestedTypeName(): String {
        var candidate: String
        do {
            candidate = "__yks_nested__:$clientId:${nestedTypeCounter++}"
        } while (candidate in rootTypes || isNestedName(candidate))
        return candidate
    }

    internal fun typeForParent(parent: String): AbstractYType? {
        rootTypes[parent]?.let { return it }
        nestedTypes[parent]?.let { return it }
        if (!isNestedName(parent)) return null
        val kind = store.firstOwnerForNested(parent)?.content?.directTypeRef()?.kind
            ?: store.firstItemForParent(parent, sequenceOnly = true)?.content?.kind
            ?: store.firstItemForParent(parent)?.content?.kind
            ?: return null
        return typeFromRef(YValue.TypeRef(kind, parent))
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
        store.firstOwnerForNested(ref.name)?.content?.directTypeRef()?.let { known ->
            require(known.kind == ref.kind) {
                "nested type '${ref.name}' exists as ${known.kind}, not ${ref.kind}"
            }
        }
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
            type.markDecodedNested(this, ref.name, store.firstOwnerForNested(ref.name)?.id)
            nestedTypes[ref.name] = type
        }
    }

    internal fun typeFromXmlType(content: ItemContent.XmlType): AbstractYType =
        typeFromRef(content.ref, content.nodeName)

    private fun rememberNestedRefs(content: ItemContent) {
        when (content) {
            is ItemContent.Value -> rememberNestedRefs(content.value)
            is ItemContent.ArrayValues -> content.values.forEach(::rememberNestedRefs)
            is ItemContent.MapEntry -> rememberNestedRefs(content.value)
            is ItemContent.MapEntries -> content.values.forEach(::rememberNestedRefs)
            is ItemContent.TextEmbed -> rememberNestedRefs(content.value)
            is ItemContent.XmlType -> {
                rememberNestedTypeRef(content.ref)
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
                rememberNestedTypeRef(value)
            }
            is YValue.SubdocRef -> subdocFromRef(value)
            is YValue.ListValue -> value.value.forEach(::rememberNestedRefs)
            is YValue.MapValue -> value.value.values.forEach(::rememberNestedRefs)
            else -> Unit
        }
    }

    private fun rememberNestedTypeRef(ref: YValue.TypeRef) {
        nestedTypes[ref.name]?.let { existing ->
            require(existing.kind == ref.kind) {
                "nested type '${ref.name}' exists as ${existing.kind}, not ${ref.kind}"
            }
        }
    }

    private fun attachAndReplayPreliminaryTypes(content: ItemContent, ownerId: Id) {
        content.nestedTypeRefNames().forEach { name ->
            val type = pendingPreliminaryAttachments.remove(name) ?: return@forEach
            captureTypeStateForMutation(type)
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
            captureTypeStateForMutation(type)
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
            store.hasNestedOwner(name)

    private fun ItemContent.nestedTypeRefNames(): Set<String> = when (this) {
        is ItemContent.Value -> value.nestedTypeRefNames()
        is ItemContent.ArrayValues -> values.flatMapTo(linkedSetOf()) { value -> value.nestedTypeRefNames() }
        is ItemContent.MapEntry -> value.nestedTypeRefNames()
        is ItemContent.MapEntries -> values.flatMapTo(linkedSetOf()) { value -> value.nestedTypeRefNames() }
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
            YDocOptions(
                guid = ref.guid,
                gc = ref.gc,
                collectionId = ref.collectionId,
                meta = ref.meta.toAny(),
                shouldLoad = shouldLoad,
                autoLoad = ref.autoLoad,
                isSuggestionDoc = ref.isSuggestionDoc,
            ),
            YDocRuntimeOptions(updateLimits, threadAccessPolicy, standardUpdatePolicy),
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
        val addedFromItems = if (transaction.hasSubdocContentChanges) {
            transaction.addedItems.flatMap { subdocRefs(it.content) }.map(::subdocFromRef)
        } else {
            emptyList()
        }
        val added = (addedFromItems + transaction.addedSubdocs).toMutableList()
        val loadedFromAdded = added.filter { it.shouldLoad }
        val removedFromItems = if (transaction.hasSubdocContentChanges) {
            transaction.deletedItems.flatMap { subdocRefs(it.content) }.map(::subdocFromRef)
        } else {
            emptyList()
        }
        val removed = (removedFromItems + transaction.removedSubdocs).toMutableList()
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
        visibleSubdocRefsCache?.let { return it }
        return store.allItems()
            .filter { !it.deleted }
            .flatMap { subdocRefs(it.content) }
            .also { refs -> visibleSubdocRefsCache = refs }
    }

    private fun recordAddedItem(item: StoreItem) {
        val hasSubdocRefs = item.content.hasSubdocRefs()
        if (hasSubdocRefs) visibleSubdocRefsCache = null
        currentTransaction?.recordAdded(item, hasSubdocRefs)
    }

    private fun subdocRefs(content: ItemContent): List<YValue.SubdocRef> = when (content) {
        is ItemContent.Value -> subdocRefs(content.value)
        is ItemContent.ArrayValues -> content.values.flatMap(::subdocRefs)
        is ItemContent.MapEntry -> subdocRefs(content.value)
        is ItemContent.MapEntries -> content.values.flatMap(::subdocRefs)
        is ItemContent.TextEmbed -> subdocRefs(content.value)
        is ItemContent.Text,
        is ItemContent.TextFormat,
        is ItemContent.NativeTextFormat,
        is ItemContent.XmlType,
        is ItemContent.XmlNode,
        is ItemContent.Deleted -> emptyList()
    }

    private fun ItemContent.hasSubdocRefs(): Boolean = when (this) {
        is ItemContent.Value -> value.hasSubdocRefs()
        is ItemContent.ArrayValues -> values.any { value -> value.hasSubdocRefs() }
        is ItemContent.MapEntry -> value.hasSubdocRefs()
        is ItemContent.MapEntries -> values.any { value -> value.hasSubdocRefs() }
        is ItemContent.TextEmbed -> value.hasSubdocRefs()
        is ItemContent.Text,
        is ItemContent.TextFormat,
        is ItemContent.NativeTextFormat,
        is ItemContent.XmlType,
        is ItemContent.XmlNode,
        is ItemContent.Deleted -> false
    }

    private fun YValue.hasSubdocRefs(): Boolean = when (this) {
        is YValue.SubdocRef -> true
        is YValue.ListValue -> value.any { nested -> nested.hasSubdocRefs() }
        is YValue.MapValue -> value.values.any { nested -> nested.hasSubdocRefs() }
        else -> false
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
                group.forEach { (source, restored) -> rememberRedoneRangeEnd(source.id, restored.id) }
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
                val end = block.last().second.lastId
                block.forEach { (source, _) -> rememberRedoneRangeEnd(source.id, end) }
                block.clear()
            }

            sorted.forEach { pair ->
                val position = originalPositions[pair.first.id]
                val contiguous = previousPosition?.let { previous ->
                    position != null && position == previous + 1
                } ?: false
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
        val sourceEnd = checkedClockAdd(source.id.clock, source.length, "restore source end")
        val containing = store.getStoreItem(source.id)
        if (
            containing != null &&
            containing.id.client == source.id.client &&
            sourceEnd < checkedClockAdd(containing.id.clock, containing.length, "restore containing end")
        ) {
            return Id(source.id.client, sourceEnd)
        }
        return containing?.let { item -> store.sequenceNeighbors(item).second?.id }
    }

    private fun StoreItem.isVisibleIn(snapshot: Snapshot): Boolean =
        id.clock < (snapshot.sv[id.client] ?: 0) && !snapshot.ds.hasId(id)

    internal class Transaction(
        val origin: Any?,
        val local: Boolean,
        val beforeState: StateVector,
        var captureItems: Boolean = false,
    ) {
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
        val typeStates = java.util.IdentityHashMap<AbstractYType, YTypeMutableState>()
        val addedMapKeys = linkedMapOf<String, MutableSet<String>>()
        var hasSubdocContentChanges: Boolean = false
            private set
        var afterState: StateVector = beforeState
        private var updateValue: ByteArray = ByteArray(0)
        private var deferredUpdate: (() -> ByteArray)? = null
        val update: ByteArray
            get() {
                deferredUpdate?.let { provider ->
                    updateValue = provider()
                    deferredUpdate = null
                }
                return updateValue
            }
        var validatedStandardUpdate: ByteArray? = null
        val isEmpty: Boolean
            get() = addedItems.isEmpty() &&
                deleteSet.isEmpty &&
                mergeStructs.isEmpty() &&
                addedSubdocs.isEmpty() &&
                removedSubdocs.isEmpty() &&
                loadedSubdocs.isEmpty() &&
                changedParents.isEmpty()

        fun markChanged(parent: String, parentSub: String?) {
            changedParents.add(parent)
            changedParentSubs.getOrPut(parent) { linkedSetOf() }.add(parentSub)
        }

        fun recordAdded(item: StoreItem, hasSubdocRefs: Boolean) {
            addedItems.add(item)
            hasSubdocContentChanges = hasSubdocContentChanges || hasSubdocRefs
        }

        fun recordDeleted(item: StoreItem, hasSubdocRefs: Boolean) {
            deletedItems.add(item)
            hasSubdocContentChanges = hasSubdocContentChanges || hasSubdocRefs
        }

        fun recordDeletedMetadata(hasSubdocRefs: Boolean) {
            hasSubdocContentChanges = hasSubdocContentChanges || hasSubdocRefs
        }

        fun deferUpdate(provider: () -> ByteArray) {
            deferredUpdate = provider
        }

        fun captureTypeState(type: AbstractYType) {
            typeStates.putIfAbsent(type, type.captureMutableState())
        }
    }

    public companion object {
        private val random = SecureRandom()

        public fun generateNewClientId(): Long = randomClientId()

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

public class Subscription internal constructor(private val unsubscribeAction: () -> Unit) : AutoCloseable {
    override fun close() {
        unsubscribeAction()
    }
}

public class YTransaction internal constructor(
    public val doc: YDoc,
    private val transaction: YDoc.Transaction,
) {
    public val origin: Any? get() = transaction.origin
    public val local: Boolean get() = transaction.local
    public val beforeState: StateVector get() = transaction.beforeState
    public val afterState: StateVector get() = transaction.afterState
    public val insertSet: IdSet get() = transaction.addedItems.toIdSet()
    public val deleteSet: DeleteSet get() = transaction.deleteSet
    public val cleanUps: IdSet get() = transaction.cleanUps
    public val meta: MutableMap<Any?, Any?> get() = transaction.meta
    public val changedParents: Set<String> get() = doc.changedParentsFor(transaction)
    public val changedTypes: Set<AbstractYType> get() = changedParents.mapNotNull { doc.typeForParent(it) }.toSet()
    public val changed: Map<AbstractYType, Set<String?>>
        get() = transaction.changedParentSubs.mapNotNull { (parent, parentSubs) ->
            doc.typeForParent(parent)?.let { type -> type to parentSubs.toSet() }
        }.toMap()
    public val subdocsAdded: Set<YDoc> get() = transaction.addedSubdocs
    public val subdocsRemoved: Set<YDoc> get() = transaction.removedSubdocs
    public val subdocsLoaded: Set<YDoc> get() = transaction.loadedSubdocs
    public val addedItemCount: Int get() = transaction.addedItems.logicalEventItemCount()
    public val deletedItemCount: Int get() = transaction.deletedItems.logicalEventItemCount()
    public val update: ByteArray get() = transaction.update

    public fun adds(id: Id): Boolean = id.clock >= (beforeState[id.client] ?: 0)

    public fun adds(client: Long, clock: Long): Boolean = adds(Id(client, clock))

    public fun deletes(id: Id): Boolean = deleteSet.contains(id)

    public fun deletes(client: Long, clock: Long): Boolean = deletes(Id(client, clock))

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

internal data class YEventMapDetails(
    val changes: Map<String, YMapChange>,
    val delta: YMapDelta,
    val value: Any?,
)

internal data class YEventSets(
    val insertSet: IdSet,
    val deleteSet: DeleteSet,
)

public class YEvent(
    public val target: AbstractYType,
    public val origin: Any?,
    update: ByteArray,
    insertSet: IdSet = createIdSet(),
    deleteSet: DeleteSet = DeleteSet.empty(),
    public val transaction: YTransactionEvent? = null,
    public val currentTarget: AbstractYType = target,
    public val childListChanged: Boolean = false,
    public val keysChanged: Set<String> = emptySet(),
    mapChanges: Map<String, YMapChange> = emptyMap(),
    mapDelta: YMapDelta = YMapDelta(),
    public val name: String? = null,
    value: Any? = null,
    public val arrayDelta: List<YArrayDeltaOp> = emptyList(),
    public val textDelta: YTextDelta = YTextDelta(),
    public val path: List<Any> = emptyList(),
    public val changedTarget: AbstractYType = target,
    public val deepEvents: List<YEvent> = emptyList(),
) {
    private var updateValue: ByteArray? = update
    private var updateProvider: (() -> ByteArray)? = null
    private var mapDetailsValue = YEventMapDetails(mapChanges, mapDelta, value)
    private var mapDetailsProvider: (() -> YEventMapDetails)? = null
    private var setsValue = YEventSets(insertSet, deleteSet)
    private var setsProvider: (() -> YEventSets)? = null

    public val update: ByteArray
        get() {
            updateValue?.let { return it }
            val resolved = checkNotNull(mapNotNullUpdateProvider())()
            updateValue = resolved
            updateProvider = null
            return resolved
        }

    public val mapChanges: Map<String, YMapChange>
        get() = mapDetails().changes

    public val mapDelta: YMapDelta
        get() = mapDetails().delta

    public val value: Any?
        get() = mapDetails().value

    public val insertSet: IdSet
        get() = sets().insertSet

    public val deleteSet: DeleteSet
        get() = sets().deleteSet

    private fun mapNotNullUpdateProvider(): (() -> ByteArray)? = updateProvider

    private fun mapDetails(): YEventMapDetails {
        mapDetailsProvider?.let { provider ->
            mapDetailsValue = provider()
            mapDetailsProvider = null
        }
        return mapDetailsValue
    }

    private fun sets(): YEventSets {
        setsProvider?.let { provider ->
            setsValue = provider()
            setsProvider = null
        }
        return setsValue
    }

    internal fun deferUpdate(provider: () -> ByteArray): YEvent {
        updateValue = null
        updateProvider = provider
        return this
    }

    internal fun deferMapDetails(provider: () -> YEventMapDetails) {
        mapDetailsValue = YEventMapDetails(emptyMap(), YMapDelta(), null)
        mapDetailsProvider = provider
    }

    internal fun deferSets(provider: () -> YEventSets): YEvent {
        setsValue = YEventSets(createIdSet(), DeleteSet.empty())
        setsProvider = provider
        return this
    }

    internal fun copyForDeep(
        target: AbstractYType = this.target,
        transaction: YTransactionEvent? = this.transaction,
        currentTarget: AbstractYType = this.currentTarget,
        path: List<Any> = this.path,
        changedTarget: AbstractYType = this.changedTarget,
        deepEvents: List<YEvent> = this.deepEvents,
    ): YEvent = YEvent(
        target = target,
        origin = origin,
        update = ByteArray(0),
        insertSet = createIdSet(),
        deleteSet = DeleteSet.empty(),
        transaction = transaction,
        currentTarget = currentTarget,
        childListChanged = childListChanged,
        keysChanged = keysChanged,
        name = name,
        arrayDelta = arrayDelta,
        textDelta = textDelta,
        path = path,
        changedTarget = changedTarget,
        deepEvents = deepEvents,
    ).deferUpdate { update }.deferSets {
        YEventSets(insertSet, deleteSet)
    }.also { copied ->
        copied.deferMapDetails {
            YEventMapDetails(mapChanges, mapDelta, value)
        }
    }

    @get:kotlin.jvm.JvmName("getDeltaValue")
    public val delta: Any
        get() = when (target.kind) {
            RootKind.Array,
            RootKind.XmlFragment,
            RootKind.XmlElement -> arrayDelta
            RootKind.Map,
            RootKind.XmlHook -> mapDelta
            RootKind.Text,
            RootKind.XmlText -> textDelta
        }

    public val deltaDeep: Any
        get() = getDelta(deep = true)

    public fun getDelta(deep: Boolean = false, renderer: AbstractRenderer = target.activeRenderer): Any {
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

    public fun adds(id: Id): Boolean = insertSet.hasId(id)

    public fun adds(client: Long, clock: Long): Boolean = insertSet.has(client, clock)

    public fun adds(struct: AbstractStruct): Boolean = adds(struct.id)

    public fun adds(struct: DecodedUpdateStruct): Boolean = adds(struct.id)

    public fun deletes(id: Id): Boolean = deleteSet.contains(id)

    public fun deletes(client: Long, clock: Long): Boolean = deletes(Id(client, clock))

    public fun deletes(struct: AbstractStruct): Boolean = deletes(struct.id)

    public fun deletes(struct: DecodedUpdateStruct): Boolean = deletes(struct.id)
}

public enum class YMapChangeAction {
    Add,
    Update,
    Delete,
}

public data class YMapChange(
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
    is ItemContent.ArrayValues -> null
    is ItemContent.MapEntry -> value as? YValue.TypeRef
    is ItemContent.MapEntries -> null
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
    is ItemContent.ArrayValues -> {
        require(kind == RootKind.Array) { "packed array values cannot be retagged as $kind" }
        this
    }
    is ItemContent.MapEntries -> {
        require(kind == RootKind.Map || kind == RootKind.XmlHook) {
            "packed map history cannot be retagged as $kind"
        }
        this
    }
    is ItemContent.Text -> if (
        kind == RootKind.Text ||
        kind == RootKind.XmlText ||
        kind == RootKind.XmlFragment ||
        kind == RootKind.XmlElement
    ) copy(kind = kind) else this
    is ItemContent.TextEmbed -> if (kind == RootKind.Text || kind == RootKind.XmlText) copy(kind = kind) else this
    is ItemContent.TextFormat -> if (kind == RootKind.Text || kind == RootKind.XmlText) copy(kind = kind) else this
    is ItemContent.NativeTextFormat -> if (kind == RootKind.Text || kind == RootKind.XmlText) copy(kind = kind) else this
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

public data class YArrayDeltaOp(
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

internal class YTransactionEventItems(
    val added: List<StoreItem>,
    val deleted: List<StoreItem>,
)

public class YTransactionEvent internal constructor(
    public val doc: YDoc,
    public val origin: Any?,
    public val local: Boolean,
    update: ByteArray?,
    private val updateProvider: (() -> ByteArray)? = null,
    public val beforeState: StateVector,
    public val afterState: StateVector,
    insertSet: IdSet?,
    public val deleteSet: DeleteSet,
    deleteIdSet: IdSet? = null,
    public val cleanUps: IdSet = createIdSet(),
    public val meta: MutableMap<Any?, Any?> = linkedMapOf(),
    addedStructs: List<ItemStruct>? = emptyList(),
    deletedStructs: List<ItemStruct>? = emptyList(),
    addedItems: List<StoreItem>?,
    deletedItems: List<StoreItem>?,
    internal val eventItems: YTransactionEventItems? = null,
    public val changedParents: Set<String>,
    public val changedTypes: Set<AbstractYType> = emptySet(),
    public val subdocsAdded: Set<YDoc> = emptySet(),
    public val subdocsRemoved: Set<YDoc> = emptySet(),
    public val subdocsLoaded: Set<YDoc> = emptySet(),
) {
    private val itemViewCache = mutableMapOf<Pair<Id, Boolean>, ItemStruct>()
    private val eagerUpdate = update
    private val eagerInsertSet = insertSet
    private val eagerDeleteIdSet = deleteIdSet
    private val eagerAddedStructs = addedStructs
    private val eagerDeletedStructs = deletedStructs
    private val eagerAddedItems = addedItems
    private val eagerDeletedItems = deletedItems

    public val update: ByteArray by lazy(LazyThreadSafetyMode.NONE) {
        eagerUpdate ?: updateProvider?.invoke() ?: ByteArray(0)
    }
    public val insertSet: IdSet by lazy(LazyThreadSafetyMode.NONE) {
        eagerInsertSet ?: eventItems?.added?.toIdSet() ?: createIdSet()
    }
    public val deleteIdSet: IdSet by lazy(LazyThreadSafetyMode.NONE) {
        eagerDeleteIdSet ?: deleteSet.toIdSet()
    }
    public val addedStructs: List<ItemStruct> by lazy(LazyThreadSafetyMode.NONE) {
        eagerAddedStructs ?: eventItems?.added.orEmpty().map { item -> itemView(item, deleted = false) }
    }
    public val deletedStructs: List<ItemStruct> by lazy(LazyThreadSafetyMode.NONE) {
        eagerDeletedStructs
            ?: eventItems?.deleted.orEmpty().map { item -> itemView(item, deleted = true) }
    }
    internal val addedItems: List<StoreItem> by lazy(LazyThreadSafetyMode.NONE) {
        eagerAddedItems ?: eventItems?.added.orEmpty()
    }
    internal val deletedItems: List<StoreItem> by lazy(LazyThreadSafetyMode.NONE) {
        eagerDeletedItems ?: eventItems?.deleted.orEmpty()
    }

    public val changedParentTypes: Set<AbstractYType> get() = changedTypes

    public val addedItemCount: Int
        get() = (eagerAddedItems ?: eventItems?.added).orEmpty().logicalEventItemCount()
    public val deletedItemCount: Int
        get() = (eagerDeletedItems ?: eventItems?.deleted).orEmpty().logicalEventItemCount()

    public fun adds(id: Id): Boolean = id.clock >= (beforeState[id.client] ?: 0)

    public fun adds(client: Long, clock: Long): Boolean = adds(Id(client, clock))

    public fun adds(struct: AbstractStruct): Boolean = adds(struct.id)

    public fun deletes(id: Id): Boolean = deleteSet.contains(id)

    public fun deletes(client: Long, clock: Long): Boolean = deletes(Id(client, clock))

    public fun deletes(struct: AbstractStruct): Boolean = deletes(struct.id)

    internal fun itemView(item: StoreItem, deleted: Boolean): ItemStruct =
        itemViewCache.getOrPut(item.id to deleted) {
            (if (item.deleted == deleted) item else item.copy(deleted = deleted)).toItemStruct(doc)
        }

    internal fun subdocEvent(): YSubdocEvent? {
        if (subdocsAdded.isEmpty() && subdocsRemoved.isEmpty() && subdocsLoaded.isEmpty()) return null
        return YSubdocEvent(
            added = subdocsAdded.toList(),
            removed = subdocsRemoved.toList(),
            loaded = subdocsLoaded.toList(),
        )
    }
}

public typealias Transaction = YTransaction
public typealias TransactionEvent = YTransactionEvent

private fun List<StoreItem>.toIdSet(): IdSet {
    val idSet = createIdSet()
    forEach { item -> idSet.add(item.id, item.length) }
    return idSet
}

private fun List<StoreItem>.logicalEventItemCount(): Int = fold(0L) { count, item ->
    val increment = when (item.content) {
        is ItemContent.ArrayValues,
        is ItemContent.MapEntries -> item.length
        else -> 1L
    }
    checkedClockAdd(count, increment, "transaction item count")
}.toNonNegativeInt("transaction item count")

public data class YSubdocEvent(
    val added: List<YDoc> = emptyList(),
    val removed: List<YDoc> = emptyList(),
    val loaded: List<YDoc> = emptyList(),
)

public data class YDocEvent(
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
        val capturedKeys: Set<String> = values.keys + itemIds.keys,
        val complete: Boolean = false,
    ) : ParentSnapshot()
    data class CombinedSnapshot(
        val sequence: SequenceSnapshot? = null,
        val map: MapSnapshot? = null,
    ) : ParentSnapshot()
}

private fun ParentSnapshot.merge(other: ParentSnapshot): ParentSnapshot {
    val sequence = sequenceSnapshot() ?: other.sequenceSnapshot()
    val firstMap = mapSnapshot()
    val secondMap = other.mapSnapshot()
    val map = when {
        firstMap == null -> secondMap
        secondMap == null -> firstMap
        else -> ParentSnapshot.MapSnapshot(
            // This snapshot was captured earlier, so it wins for keys present in both.
            values = secondMap.values + firstMap.values,
            itemIds = secondMap.itemIds + firstMap.itemIds,
            capturedKeys = firstMap.capturedKeys + secondMap.capturedKeys,
            complete = firstMap.complete || secondMap.complete,
        )
    }
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
