package dev.yks

data class UndoManagerOptions(
    val captureTimeoutMillis: Long = 500,
    val trackedOrigins: Set<Any?> = setOf(null),
    val captureTransaction: ((YTransactionEvent) -> Boolean)? = null,
    val deleteFilter: (Id) -> Boolean = { true },
    val ignoreRemoteAttributeChanges: Boolean = false,
    val ignoreRemoteMapChanges: Boolean = false,
) {
    /**
     * Backwards-compatible alias for Yjs' former option name.
     *
     * Upstream renamed `ignoreRemoteMapChanges` to `ignoreRemoteAttributeChanges`;
     * both names control the same map-attribute overwrite behavior.
     */
    internal fun shouldIgnoreRemoteAttributeChanges(): Boolean =
        ignoreRemoteAttributeChanges || ignoreRemoteMapChanges
}

class StackItem internal constructor(
    internal val insertedItems: List<StoreItem>,
    internal val deletedItems: List<RestoreItem>,
    internal val explicitInserts: IdSet? = null,
    internal val explicitDeletes: IdSet? = null,
) {
    constructor(insertions: IdSet, deletions: IdSet) : this(
        insertedItems = emptyList(),
        deletedItems = emptyList(),
        explicitInserts = insertions.copy(),
        explicitDeletes = deletions.copy(),
    )

    val meta: MutableMap<Any?, Any?> = linkedMapOf()
    val inserts: IdSet get() = explicitInserts?.copy() ?: insertedItems.toIdSet()
    val deletes: IdSet get() = explicitDeletes?.copy() ?: deletedItems.map { it.item }.toIdSet()
    val insertedCount: Int get() = explicitInserts?.rangeLengthAsInt() ?: insertedItems.size
    val deletedCount: Int get() = explicitDeletes?.rangeLengthAsInt() ?: deletedItems.size
    val isEmpty: Boolean
        get() {
            val insertsEmpty = explicitInserts?.isEmpty() ?: insertedItems.isEmpty()
            val deletesEmpty = explicitDeletes?.isEmpty() ?: deletedItems.isEmpty()
            return insertsEmpty && deletesEmpty
        }
}

enum class UndoStackItemType {
    Undo,
    Redo,
}

data class UndoManagerEvent(
    val stackItem: StackItem? = null,
    val type: UndoStackItemType? = null,
    val origin: Any? = null,
    val changedParentTypes: Set<AbstractYType> = emptySet(),
    val undoStackCleared: Boolean = false,
    val redoStackCleared: Boolean = false,
)

class UndoManager private constructor(
    private val doc: YDoc,
    initialScopeNames: Set<String>?,
    private val options: UndoManagerOptions,
) : AutoCloseable {
    constructor(typeScope: AbstractYType, options: UndoManagerOptions = UndoManagerOptions()) :
        this(typeScope.doc, setOf(typeScope.name), options)

    constructor(typeScope: List<AbstractYType>, options: UndoManagerOptions = UndoManagerOptions()) :
        this(resolveDoc(typeScope), typeScope.map { it.name }.toSet(), options)

    constructor(doc: YDoc, options: UndoManagerOptions = UndoManagerOptions()) :
        this(doc, null, options)

    private val subscription = doc.observeTransactions(::capture)
    private val destroySubscription = doc.on("destroy") { _: YDocEvent -> close() }
    private var scopeNames: MutableSet<String>? = initialScopeNames?.toMutableSet()
    private val trackedOrigins = options.trackedOrigins.toMutableSet().also { it.add(this) }
    val undoStack: MutableList<StackItem> = mutableListOf()
    val redoStack: MutableList<StackItem> = mutableListOf()
    private val eventListeners = linkedMapOf<String, MutableList<(UndoManagerEvent) -> Unit>>()
    private var lastCaptureTime = 0L
    private var captureDisabled = false
    private var closed = false
    var currStackItem: StackItem? = null
        private set
    var undoing: Boolean = false
        private set
    var redoing: Boolean = false
        private set

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
    val undoStackSize: Int get() = undoStack.size
    val redoStackSize: Int get() = redoStack.size

    fun canUndo(): Boolean = canUndo

    fun canRedo(): Boolean = canRedo

    fun stopCapturing() {
        lastCaptureTime = 0
    }

    fun addToScope(typeScope: AbstractYType) {
        require(typeScope.doc === doc) { "UndoManager scope type must belong to the same YDoc" }
        scopeNames?.add(typeScope.name)
    }

    fun addToScope(typeScope: List<AbstractYType>) {
        require(typeScope.isNotEmpty()) { "UndoManager type scope must not be empty" }
        typeScope.forEach(::addToScope)
    }

    fun addToScope(docScope: YDoc) {
        require(docScope === doc) { "UndoManager scope doc must be the managed YDoc" }
        scopeNames = null
    }

    fun addTrackedOrigin(origin: Any?) {
        trackedOrigins.add(origin)
    }

    fun removeTrackedOrigin(origin: Any?) {
        trackedOrigins.remove(origin)
    }

    fun clear(clearUndoStack: Boolean = true, clearRedoStack: Boolean = true) {
        val undoStackCleared = clearUndoStack && undoStack.isNotEmpty()
        val redoStackCleared = clearRedoStack && redoStack.isNotEmpty()
        if (clearUndoStack) undoStack.clear()
        if (clearRedoStack) redoStack.clear()
        stopCapturing()
        if (undoStackCleared || redoStackCleared) {
            emit(
                "stack-cleared",
                UndoManagerEvent(
                    undoStackCleared = undoStackCleared,
                    redoStackCleared = redoStackCleared,
                ),
            )
        }
    }

    fun on(eventName: String, listener: (UndoManagerEvent) -> Unit): Subscription {
        val listeners = eventListeners.getOrPut(eventName) { mutableListOf() }
        listeners.add(listener)
        return Subscription { off(eventName, listener) }
    }

    fun once(eventName: String, listener: (UndoManagerEvent) -> Unit): Subscription {
        lateinit var subscription: Subscription
        val onceListener: (UndoManagerEvent) -> Unit = { event ->
            subscription.close()
            listener(event)
        }
        subscription = on(eventName, onceListener)
        return subscription
    }

    fun off(eventName: String, listener: (UndoManagerEvent) -> Unit) {
        val listeners = eventListeners[eventName] ?: return
        listeners.remove(listener)
        if (listeners.isEmpty()) {
            eventListeners.remove(eventName)
        }
    }

    fun undo(): StackItem? {
        val stackItem = popNormalizedStackItem(undoStack) ?: return null
        currStackItem = stackItem
        undoing = true
        redoing = false
        try {
            val applied = applyStackItem(stackItem)
            if (!applied.reverseStackItem.isEmpty) {
                redoStack.add(applied.reverseStackItem)
                emitStackItemEvent(
                    "stack-item-added",
                    applied.reverseStackItem,
                    UndoStackItemType.Redo,
                    this,
                    applied.changedParentTypes,
                )
            }
            emitStackItemEvent("stack-item-popped", stackItem, UndoStackItemType.Undo, this, applied.changedParentTypes)
            return stackItem
        } finally {
            currStackItem = null
            undoing = false
            stopCapturing()
        }
    }

    fun redo(): StackItem? {
        val stackItem = popNormalizedStackItem(redoStack) ?: return null
        currStackItem = stackItem
        undoing = false
        redoing = true
        try {
            val applied = applyStackItem(stackItem)
            if (!applied.reverseStackItem.isEmpty) {
                undoStack.add(applied.reverseStackItem)
                emitStackItemEvent(
                    "stack-item-added",
                    applied.reverseStackItem,
                    UndoStackItemType.Undo,
                    this,
                    applied.changedParentTypes,
                )
            }
            emitStackItemEvent("stack-item-popped", stackItem, UndoStackItemType.Redo, this, applied.changedParentTypes)
            return stackItem
        } finally {
            currStackItem = null
            redoing = false
            stopCapturing()
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        subscription.close()
        destroySubscription.close()
        trackedOrigins.remove(this)
        eventListeners.clear()
    }

    fun destroy() {
        close()
    }

    internal fun pushUndoStackItem(stackItem: StackItem) {
        val normalized = normalizeStackItem(stackItem)
        if (!normalized.isEmpty) {
            undoStack.add(normalized)
        }
    }

    private fun capture(event: YTransactionEvent) {
        if (captureDisabled || !event.local || event.addedItems.isEmpty() && event.deletedItems.isEmpty()) return
        if (!isTrackedOrigin(event.origin)) return
        if (!isInScope(event)) return
        if (options.captureTransaction?.invoke(event) == false) return

        val stackItem = normalizeStackItem(
            StackItem(
                insertedItems = event.addedItems.map { it.copy(deleted = false) },
                deletedItems = event.deletedItems.map { RestoreItem(it.copy(deleted = false), anchorAfterOriginal = true) },
            ),
        )
        if (stackItem.isEmpty) return

        val now = System.currentTimeMillis()
        val shouldMerge = undoStack.isNotEmpty() &&
            lastCaptureTime != 0L &&
            options.captureTimeoutMillis > 0 &&
            now - lastCaptureTime <= options.captureTimeoutMillis

        if (shouldMerge) {
            val previous = undoStack.removeLast()
            val merged = normalizeStackItem(previous.merge(stackItem)).also { merged ->
                merged.meta.putAll(previous.meta)
            }
            undoStack.add(merged)
            emitStackItemEvent(
                "stack-item-updated",
                merged,
                UndoStackItemType.Undo,
                event.origin,
                event.changedParentTypes,
            )
        } else {
            undoStack.add(stackItem)
            emitStackItemEvent(
                "stack-item-added",
                stackItem,
                UndoStackItemType.Undo,
                event.origin,
                event.changedParentTypes,
            )
        }
        if (redoStack.isNotEmpty()) {
            redoStack.clear()
            emit(
                "stack-cleared",
                UndoManagerEvent(undoStackCleared = false, redoStackCleared = true),
            )
        }
        lastCaptureTime = now
    }

    private fun applyStackItem(stackItem: StackItem): AppliedStackItem {
        captureDisabled = true
        try {
            val insertedItemsToDelete = stackItem.insertedItems.filter { options.deleteFilter(it.id) }
            val insertedIdsToDelete = insertedItemsToDelete.map { item -> item.id }.toSet()
            val deletedItemsToRestore = stackItem.deletedItems.filter { restore ->
                val source = restore.item
                val key = source.parentSub ?: return@filter true
                if (options.shouldIgnoreRemoteAttributeChanges()) return@filter true
                val currentId = doc.currentVisibleMapItemId(source.parent, key) ?: return@filter true
                currentId == source.id || currentId in insertedIdsToDelete
            }
            var restoredItems = emptyList<StoreItem>()
            var changedParentTypes = emptySet<AbstractYType>()
            val changedTypesSubscription = doc.observeAfterTransactions { event ->
                if (event.origin === this) {
                    changedParentTypes = event.changedParentTypes
                }
            }
            try {
                doc.transact(origin = this) {
                    doc.deleteItemsByIds(insertedIdsToDelete)
                    restoredItems = doc.restoreItems(deletedItemsToRestore)
                }
            } finally {
                changedTypesSubscription.close()
            }
            return AppliedStackItem(
                reverseStackItem = normalizeStackItem(
                    StackItem(
                        insertedItems = restoredItems.map { it.copy(deleted = false) },
                        deletedItems = insertedItemsToDelete.map(doc::restoreItemAtCurrentPosition),
                    ),
                ),
                changedParentTypes = changedParentTypes,
            )
        } finally {
            captureDisabled = false
        }
    }

    private fun isTrackedOrigin(origin: Any?): Boolean {
        return trackedOrigins.contains(origin) || (origin != null && trackedOrigins.contains(origin::class))
    }

    private fun isInScope(event: YTransactionEvent): Boolean {
        val names = scopeNames ?: return true
        return event.changedParents.any { changedParent ->
            names.any { scopeName -> changedParent == scopeName || doc.pathBetween(scopeName, changedParent) != null }
        }
    }

    private fun StackItem.merge(other: StackItem): StackItem = StackItem(
        insertedItems = insertedItems + other.insertedItems,
        deletedItems = deletedItems + other.deletedItems,
    )

    private fun emitStackItemEvent(
        eventName: String,
        stackItem: StackItem,
        type: UndoStackItemType,
        origin: Any?,
        changedParentTypes: Set<AbstractYType> = emptySet(),
    ) {
        emit(
            eventName,
            UndoManagerEvent(
                stackItem = stackItem,
                type = type,
                origin = origin,
                changedParentTypes = changedParentTypes,
            ),
        )
    }

    private fun emit(eventName: String, event: UndoManagerEvent) {
        val callbacks = eventListeners[eventName].orEmpty().toList().map { listener ->
            { listener(event) }
        }
        callAllYksCallbacks(callbacks)
    }

    private fun normalizeStackItem(stackItem: StackItem): StackItem {
        val insertedById = linkedMapOf<Id, StoreItem>()
        val insertedItems = stackItem.explicitInserts
            ?.let { idSet -> doc.itemsForIdSet(idSet, { !it.deleted }, deletedOverride = false) }
            ?: stackItem.insertedItems
        insertedItems.forEach { item ->
            val followedId = doc.followRedone(item.id)
            val current = if (followedId != item.id) {
                doc.getItem(followedId)?.takeUnless { followed -> followed.deleted } ?: item
            } else {
                item
            }
            insertedById[current.id] = current.copy(deleted = false)
        }
        val deletedById = linkedMapOf<Id, RestoreItem>()
        val deletedItems = stackItem.explicitDeletes
            ?.let { idSet ->
                doc.itemsForIdSet(idSet, { it.deleted }, deletedOverride = false)
                    .map { item -> RestoreItem(item, anchorAfterOriginal = true) }
            }
            ?: stackItem.deletedItems
        deletedItems.forEach { deletedById[it.item.id] = it.copy(item = it.item.copy(deleted = false)) }

        val insertedAndDeleted = insertedById.keys.intersect(deletedById.keys)
        insertedAndDeleted.forEach {
            insertedById.remove(it)
            deletedById.remove(it)
        }

        return StackItem(
            insertedItems = insertedById.values.toList(),
            deletedItems = deletedById.values.toList(),
        ).also { normalized -> normalized.meta.putAll(stackItem.meta) }
    }

    private fun popNormalizedStackItem(stack: MutableList<StackItem>): StackItem? {
        while (stack.isNotEmpty()) {
            val normalized = normalizeStackItem(stack.removeLast())
            if (!normalized.isEmpty) return normalized
        }
        return null
    }

    private data class AppliedStackItem(
        val reverseStackItem: StackItem,
        val changedParentTypes: Set<AbstractYType>,
    )

    companion object {
        private fun resolveDoc(types: List<AbstractYType>): YDoc {
            require(types.isNotEmpty()) { "UndoManager type scope must not be empty" }
            val doc = types.first().doc
            require(types.all { it.doc === doc }) { "all UndoManager scoped types must belong to the same YDoc" }
            return doc
        }
    }
}

fun undoContentIds(
    doc: YDoc,
    contentIds: ContentIds,
    options: UndoManagerOptions = UndoManagerOptions(),
): StackItem? {
    val insertedItems = doc.itemsForIdSet(diffIdSet(contentIds.inserts, contentIds.deletes), { !it.deleted }, deletedOverride = false)
    val deletedItems = doc.itemsForIdSet(diffIdSet(contentIds.deletes, contentIds.inserts), { it.deleted }, deletedOverride = false)
        .map { item -> RestoreItem(item, anchorAfterOriginal = true) }
    val stackItem = StackItem(insertedItems = insertedItems, deletedItems = deletedItems)
    val undoManager = UndoManager(doc, options)
    return try {
        undoManager.pushUndoStackItem(stackItem)
        undoManager.undo()
    } finally {
        undoManager.close()
    }
}

@Suppress("UNUSED_PARAMETER")
fun redoItem(
    transaction: YTransaction,
    item: Item,
    redoitems: Set<Item> = emptySet(),
    itemsToDelete: IdSet = createIdSet(),
    ignoreRemoteAttributeChanges: Boolean = false,
    undoManager: UndoManager? = null,
): Item? = redoItem(transaction.doc, item, redoitems, itemsToDelete, ignoreRemoteAttributeChanges, undoManager)

@Suppress("UNUSED_PARAMETER")
fun redoItem(
    doc: YDoc,
    item: Item,
    redoitems: Set<Item> = emptySet(),
    itemsToDelete: IdSet = createIdSet(),
    ignoreRemoteAttributeChanges: Boolean = false,
    undoManager: UndoManager? = null,
): Item? {
    val followed = doc.followRedone(item.id)
    if (followed != item.id) {
        return doc.getItem(followed)?.toItemStruct(doc)
    }

    val source = doc.getItem(item.id) ?: return null
    if (!source.deleted) {
        return source.toItemStruct(doc)
    }

    val restored = doc.restoreItems(listOf(RestoreItem(source.copy(deleted = false), anchorAfterOriginal = true)))
        .firstOrNull()
        ?: return null
    return restored.toItemStruct(doc).also { restoredItem -> keepItem(doc, restoredItem, keep = true) }
}

private fun List<StoreItem>.toIdSet(): IdSet {
    val idSet = createIdSet()
    forEach { item -> idSet.add(item.id, item.length) }
    return idSet
}

private fun IdSet.rangeLengthAsInt(): Int =
    ranges().sumOf { (_, range) -> range.len }.toInt()
