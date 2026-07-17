package dev.yks

public data class UndoManagerOptions(
    val captureTimeoutMillis: Long = 500,
    val captureTimeout: Long? = null,
    val trackedOrigins: Set<Any?> = setOf(null),
    val captureTransaction: ((YTransactionEvent) -> Boolean)? = null,
    val deleteFilter: (Item) -> Boolean = { true },
    val ignoreRemoteAttributeChanges: Boolean = false,
    val ignoreRemoteMapChanges: Boolean = false,
    val doc: YDoc? = null,
) {
    /**
     * Backwards-compatible alias for Yjs' former option name.
     *
     * Upstream renamed `ignoreRemoteMapChanges` to `ignoreRemoteAttributeChanges`;
     * both names control the same map-attribute overwrite behavior.
     */
    internal fun shouldIgnoreRemoteAttributeChanges(): Boolean =
        ignoreRemoteAttributeChanges || ignoreRemoteMapChanges

    internal fun effectiveCaptureTimeoutMillis(): Long =
        captureTimeout ?: captureTimeoutMillis
}

public class StackItem internal constructor(
    internal val insertedItems: List<StoreItem>,
    internal val deletedItems: List<RestoreItem>,
    internal val explicitInserts: IdSet? = null,
    internal val explicitDeletes: IdSet? = null,
) {
    public constructor(insertions: IdSet, deletions: IdSet) : this(
        insertedItems = emptyList(),
        deletedItems = emptyList(),
        explicitInserts = insertions.copy(),
        explicitDeletes = deletions.copy(),
    )

    public val meta: MutableMap<Any?, Any?> = linkedMapOf()
    public val inserts: IdSet get() = explicitInserts?.copy() ?: insertedItems.toIdSet()
    public val deletes: IdSet get() = explicitDeletes?.copy() ?: deletedItems.map { it.item }.toIdSet()
    public val insertedCount: Int get() = explicitInserts?.rangeLengthAsInt() ?: insertedItems
        .sumOf { item -> item.length }
        .toNonNegativeInt("undo insertion count")
    public val deletedCount: Int get() = explicitDeletes?.rangeLengthAsInt() ?: deletedItems
        .sumOf { restore -> restore.item.length }
        .toNonNegativeInt("undo deletion count")
    public val isEmpty: Boolean
        get() {
            val insertsEmpty = explicitInserts?.isEmpty() ?: insertedItems.isEmpty()
            val deletesEmpty = explicitDeletes?.isEmpty() ?: deletedItems.isEmpty()
            return insertsEmpty && deletesEmpty
        }
}

public enum class UndoStackItemType {
    Undo,
    Redo,
}

public data class UndoManagerEvent(
    val stackItem: StackItem? = null,
    val type: UndoStackItemType? = null,
    val origin: Any? = null,
    val changedParentTypes: Set<AbstractYType> = emptySet(),
    val undoStackCleared: Boolean = false,
    val redoStackCleared: Boolean = false,
)

public class UndoManager private constructor(
    private val doc: YDoc,
    initialScopeNames: Set<String>?,
    private val options: UndoManagerOptions,
) : AutoCloseable {
    public constructor(typeScope: AbstractYType, options: UndoManagerOptions = UndoManagerOptions()) :
        this(typeScope.doc, setOf(typeScope.name), options)

    public constructor(typeScope: List<AbstractYType>, options: UndoManagerOptions = UndoManagerOptions()) :
        this(resolveDoc(typeScope, options.doc), typeScope.map { it.name }.toSet(), options)

    public constructor(doc: YDoc, options: UndoManagerOptions = UndoManagerOptions()) :
        this(doc, null, options)

    private val subscription = doc.observeTransactions(::capture)
    private val destroySubscription = doc.on("destroy") { _: YDocEvent -> close() }
    private var scopeNames: MutableSet<String>? = initialScopeNames?.toMutableSet()
    private val trackedOrigins = options.trackedOrigins.toMutableSet().also { it.add(this) }
    public val undoStack: MutableList<StackItem> = mutableListOf()
    public val redoStack: MutableList<StackItem> = mutableListOf()
    private val eventListeners = linkedMapOf<String, MutableList<(UndoManagerEvent) -> Unit>>()
    private var lastCaptureTime = 0L
    private var captureDisabled = false
    private var closed = false
    public var currStackItem: StackItem? = null
        private set
    public var undoing: Boolean = false
        private set
    public var redoing: Boolean = false
        private set

    public val canUndo: Boolean get() = undoStack.isNotEmpty()
    public val canRedo: Boolean get() = redoStack.isNotEmpty()
    public val undoStackSize: Int get() = undoStack.size
    public val redoStackSize: Int get() = redoStack.size

    public fun canUndo(): Boolean = canUndo

    public fun canRedo(): Boolean = canRedo

    public fun stopCapturing() {
        lastCaptureTime = 0
    }

    public fun addToScope(typeScope: AbstractYType) {
        require(typeScope.doc === doc) { "UndoManager scope type must belong to the same YDoc" }
        scopeNames?.add(typeScope.name)
    }

    public fun addToScope(typeScope: List<AbstractYType>) {
        require(typeScope.isNotEmpty()) { "UndoManager type scope must not be empty" }
        typeScope.forEach(::addToScope)
    }

    public fun addToScope(docScope: YDoc) {
        require(docScope === doc) { "UndoManager scope doc must be the managed YDoc" }
        scopeNames = null
    }

    public fun addTrackedOrigin(origin: Any?) {
        trackedOrigins.add(origin)
    }

    public fun removeTrackedOrigin(origin: Any?) {
        trackedOrigins.remove(origin)
    }

    public fun clear(clearUndoStack: Boolean = true, clearRedoStack: Boolean = true) {
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

    public fun on(eventName: String, listener: (UndoManagerEvent) -> Unit): Subscription {
        val listeners = eventListeners.getOrPut(eventName) { mutableListOf() }
        listeners.add(listener)
        return Subscription { off(eventName, listener) }
    }

    public fun once(eventName: String, listener: (UndoManagerEvent) -> Unit): Subscription {
        lateinit var subscription: Subscription
        val onceListener: (UndoManagerEvent) -> Unit = { event ->
            subscription.close()
            listener(event)
        }
        subscription = on(eventName, onceListener)
        return subscription
    }

    public fun off(eventName: String, listener: (UndoManagerEvent) -> Unit) {
        val listeners = eventListeners[eventName] ?: return
        listeners.remove(listener)
        if (listeners.isEmpty()) {
            eventListeners.remove(eventName)
        }
    }

    public fun undo(): StackItem? {
        undoing = true
        redoing = false
        try {
            while (true) {
                val stackItem = popNormalizedStackItem(undoStack) ?: return null
                currStackItem = stackItem
                val applied = applyStackItem(stackItem)
                if (!applied.performedChange) {
                    currStackItem = null
                    stopCapturing()
                    continue
                }
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
            }
        } finally {
            currStackItem = null
            undoing = false
            stopCapturing()
        }
    }

    public fun redo(): StackItem? {
        undoing = false
        redoing = true
        try {
            while (true) {
                val stackItem = popNormalizedStackItem(redoStack) ?: return null
                currStackItem = stackItem
                val applied = applyStackItem(stackItem)
                if (!applied.performedChange) {
                    currStackItem = null
                    stopCapturing()
                    continue
                }
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
            }
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

    public fun destroy() {
        close()
    }

    internal fun pushUndoStackItem(stackItem: StackItem) {
        val normalized = normalizeStackItem(stackItem)
        if (!normalized.isEmpty) {
            undoStack.add(normalized)
        }
    }

    private fun capture(event: YTransactionEvent) {
        if (captureDisabled) return
        if (options.captureTransaction?.invoke(event) == false) return
        if (!isInScope(event)) return
        if (!isTrackedOrigin(event.origin)) return

        // Upstream marks deleted items as kept during afterTransaction so automatic GC, which
        // runs immediately afterwards, cannot discard content required by a future undo.
        event.deletedItems
            .filter { item -> isItemInScope(item) }
            .forEach { item -> keepItem(doc, item.toItemStruct(doc), keep = true) }

        val stackItem = normalizeStackItem(
            StackItem(
                insertedItems = event.addedItems.map { it.copy(deleted = false) },
                deletedItems = event.deletedItems.map { RestoreItem(it.copy(deleted = false), anchorAfterOriginal = true) },
            ),
        )
        val now = System.currentTimeMillis()
        val shouldMerge = undoStack.isNotEmpty() &&
            lastCaptureTime != 0L &&
            options.effectiveCaptureTimeoutMillis() > 0 &&
            now - lastCaptureTime <= options.effectiveCaptureTimeoutMillis()

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
            // Yjs invokes deleteFilter once per physical Item, even when ContentString/ContentAny
            // spans many clocks. Keeping packed ranges intact avoids allocating one Item per
            // character/value and preserves the upstream filter contract.
            val insertedItemsToDelete = stackItem.insertedItems.filter { item ->
                options.deleteFilter(item.toItemStruct(doc))
            }
            val insertedIdsToDelete = insertedItemsToDelete.toIdSet()
            val restoreCandidates = stackItem.deletedItems.filter { restore ->
                val source = restore.item
                val key = source.parentSub ?: return@filter true
                if (options.shouldIgnoreRemoteAttributeChanges()) return@filter true
                val currentId = doc.currentVisibleMapItemId(source.parent, key) ?: return@filter true
                currentId == source.id || insertedIdsToDelete.hasId(currentId)
            }
            val restoreCandidatesById = restoreCandidates.associate { restore -> restore.item.id to restore.item }
            val restoreEligibility = mutableMapOf<Id, Boolean>()
            val deletedItemsToRestore = restoreCandidates.filter { restore ->
                canRestoreIntoCurrentParent(
                    item = restore.item,
                    restoreCandidatesById = restoreCandidatesById,
                    eligibility = restoreEligibility,
                    visiting = linkedSetOf(),
                )
            }
            var restoredItems = emptyList<StoreItem>()
            var deletedItems = emptyList<StoreItem>()
            var changedParentTypes = emptySet<AbstractYType>()
            val changedTypesSubscription = doc.observeAfterTransactions { event ->
                if (event.origin === this) {
                    deletedItems = event.deletedItems
                    deletedItems.forEach { item -> keepItem(doc, item.toItemStruct(doc), keep = true) }
                    changedParentTypes = event.changedParentTypes
                }
            }
            try {
                doc.transact(origin = this) {
                    doc.deleteItemRanges(insertedItemsToDelete)
                    restoredItems = doc.restoreItems(deletedItemsToRestore)
                }
            } finally {
                changedTypesSubscription.close()
            }
            return AppliedStackItem(
                performedChange = restoredItems.isNotEmpty() || deletedItems.isNotEmpty(),
                reverseStackItem = normalizeStackItem(
                    StackItem(
                        insertedItems = restoredItems.map { it.copy(deleted = false) },
                        deletedItems = deletedItems.map(doc::restoreItemAtCurrentPosition),
                    ),
                ),
                changedParentTypes = changedParentTypes,
            )
        } finally {
            captureDisabled = false
        }
    }

    private fun isTrackedOrigin(origin: Any?): Boolean {
        if (trackedOrigins.contains(origin)) return true
        if (origin == null) return false
        return trackedOrigins.any { trackedOrigin ->
            when (trackedOrigin) {
                is kotlin.reflect.KClass<*> -> trackedOrigin.isInstance(origin)
                is Class<*> -> trackedOrigin.isInstance(origin)
                else -> false
            }
        }
    }

    private fun isInScope(event: YTransactionEvent): Boolean {
        val names = scopeNames ?: return true
        return event.changedParents.any { changedParent ->
            names.any { scopeName -> changedParent == scopeName || doc.pathBetween(scopeName, changedParent) != null }
        }
    }

    private fun isItemInScope(item: StoreItem): Boolean {
        val names = scopeNames ?: return true
        return names.any { scopeName ->
            item.parent == scopeName || doc.pathBetween(scopeName, item.parent) != null
        }
    }

    /**
     * Yjs does not redo a child when its original nested-type owner was deleted remotely and
     * cannot itself be redone by the same stack item. A later ContentType with the same logical
     * parent name is not enough: without a local `redone` link it is a distinct owner.
     */
    private fun canRestoreIntoCurrentParent(
        item: StoreItem,
        restoreCandidatesById: Map<Id, StoreItem>,
        eligibility: MutableMap<Id, Boolean>,
        visiting: MutableSet<Id>,
    ): Boolean {
        eligibility[item.id]?.let { return it }
        if (!visiting.add(item.id)) return false
        val result = run {
            val parentType = doc.typeForParent(item.parent) ?: return@run true
            val ownerId = (parentType.binding as? YTypeBinding.Nested)?.ownerId ?: return@run true
            val owner = doc.getItem(ownerId) ?: return@run false
            if (!owner.deleted) return@run true
            val followed = doc.followRedone(ownerId)
            if (followed != ownerId && doc.getItem(followed)?.deleted == false) return@run true
            val ownerCandidate = restoreCandidatesById[followed] ?: restoreCandidatesById[ownerId]
            ownerCandidate != null && canRestoreIntoCurrentParent(
                item = ownerCandidate,
                restoreCandidatesById = restoreCandidatesById,
                eligibility = eligibility,
                visiting = visiting,
            )
        }
        visiting.remove(item.id)
        eligibility[item.id] = result
        return result
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
            ?: stackItem.insertedItems.flatMap(::currentStoreSlices)
        insertedItems.forEach { item ->
            val followedId = doc.followRedone(item.id)
            val currentItems = if (followedId != item.id) {
                val redoneIds = createIdSet().also { ids -> ids.add(followedId, item.length) }
                doc.itemsForIdSet(redoneIds, { followed -> !followed.deleted }, deletedOverride = false)
                    .ifEmpty { listOf(item) }
            } else {
                listOf(item)
            }
            currentItems.forEach { current ->
                insertedById[current.id] = current.copy(deleted = false)
            }
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

    /**
     * Transactions capture packed StoreItems, but later edits may split those clock ranges.
     * Normalize against every current slice so undo never forgets the tail of a packed insert
     * or delete after a partial edit.
     */
    private fun currentStoreSlices(item: StoreItem): List<StoreItem> {
        val ids = createIdSet().also { it.add(item.id, item.length) }
        return doc.itemsForIdSet(ids).ifEmpty { listOf(item) }
    }

    private fun popNormalizedStackItem(stack: MutableList<StackItem>): StackItem? {
        while (stack.isNotEmpty()) {
            val normalized = normalizeStackItem(stack.removeLast())
            if (!normalized.isEmpty) return normalized
        }
        return null
    }

    private data class AppliedStackItem(
        val performedChange: Boolean,
        val reverseStackItem: StackItem,
        val changedParentTypes: Set<AbstractYType>,
    )

    public companion object {
        private fun resolveDoc(types: List<AbstractYType>, optionsDoc: YDoc?): YDoc {
            if (types.isEmpty()) {
                return requireNotNull(optionsDoc) { "UndoManager type scope must not be empty without a doc option" }
            }
            val doc = types.first().doc
            require(types.all { it.doc === doc }) { "all UndoManager scoped types must belong to the same YDoc" }
            require(optionsDoc == null || optionsDoc === doc) {
                "UndoManager doc option must match scoped type documents"
            }
            return doc
        }
    }
}

public fun undoContentIds(
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
public fun redoItem(
    transaction: YTransaction,
    item: Item,
    redoitems: Set<Item> = emptySet(),
    itemsToDelete: IdSet = createIdSet(),
    ignoreRemoteAttributeChanges: Boolean = false,
    undoManager: UndoManager? = null,
): Item? = redoItem(transaction.doc, item, redoitems, itemsToDelete, ignoreRemoteAttributeChanges, undoManager)

@Suppress("UNUSED_PARAMETER")
public fun redoItem(
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

private fun IdSet.rangeLengthAsInt(): Int = ranges()
    .fold(0L) { length, (_, range) -> checkedClockAdd(length, range.len, "undo range length") }
    .toNonNegativeInt("undo range length")
