package dev.yks

/**
 * A type-safe view over the backwards-compatible [YEvent] payload.
 *
 * Kotlin's existing `observe { YEvent -> ... }` ABI is intentionally retained. Call
 * [observeTyped] when the shared type's concrete event/delta shape is useful.
 */
public sealed class YTypedEvent<out T : AbstractYType> protected constructor(
    public val rawEvent: YEvent,
) {
    public abstract val target: T

    public val origin: Any? get() = rawEvent.origin
    public val update: ByteArray get() = rawEvent.update
    public val insertSet: IdSet get() = rawEvent.insertSet
    public val deleteSet: DeleteSet get() = rawEvent.deleteSet
    public val transaction: YTransactionEvent? get() = rawEvent.transaction
    public val currentTarget: AbstractYType get() = rawEvent.currentTarget
    public val changedTarget: AbstractYType get() = rawEvent.changedTarget
    public val path: List<Any> get() = rawEvent.path
    public val deepEvents: List<YEvent> get() = rawEvent.deepEvents
    public val deltaDeep: Any get() = rawEvent.deltaDeep

    public fun getDelta(deep: Boolean = false, renderer: AbstractRenderer = target.activeRenderer): Any =
        rawEvent.getDelta(deep, renderer)

    public fun adds(id: Id): Boolean = rawEvent.adds(id)

    public fun deletes(id: Id): Boolean = rawEvent.deletes(id)
}

/**
 * Typed Kotlin representation of Yjs' common `event.changes` object.
 *
 * For array/XML sequence events, [added] and [deleted] exclude items that were both inserted
 * and deleted in the same transaction. Upstream text events intentionally leave both sets
 * empty and expose edits through [delta]. [keys] carries map/attribute changes for every type.
 */
public interface YEventChangesIdSetView {
    public val added: IdSet
    public val deleted: IdSet

    public operator fun component1(): IdSet
    public operator fun component2(): IdSet
}

public interface YEventChangesItemSetView {
    public val added: Set<Item>
    public val deleted: Set<Item>

    public operator fun component1(): Set<Item>
    public operator fun component2(): Set<Item>
}

/**
 * A dual view used to retain the pre-0.2.9 [IdSet] JVM ABI while exposing Yjs-compatible items.
 */
public class YEventItemSet internal constructor(
    items: Set<Item>,
    ids: IdSet,
) : IdSet(ids.clients), Set<Item> {
    private val items: Set<Item> = items.toCollection(linkedSetOf())

    override val size: Int get() = items.size

    override fun contains(element: Item): Boolean = items.contains(element)

    override fun containsAll(elements: Collection<Item>): Boolean = items.containsAll(elements)

    override fun isEmpty(): Boolean = items.isEmpty() && super.isEmpty()

    override fun iterator(): Iterator<Item> = items.iterator()

    override fun equals(other: Any?): Boolean = items == other

    override fun hashCode(): Int = items.hashCode()

    override fun toString(): String = items.toString()
}

public data class YEventChanges<out D>(
    override val added: YEventItemSet,
    override val deleted: YEventItemSet,
    val delta: D,
    val keys: Map<String, YMapChange>,
) : YEventChangesIdSetView, YEventChangesItemSetView {
    public constructor(
        added: Set<Item>,
        deleted: Set<Item>,
        delta: D,
        keys: Map<String, YMapChange>,
    ) : this(
        added = YEventItemSet(added, added.toIdSet()),
        deleted = YEventItemSet(deleted, deleted.toIdSet()),
        delta = delta,
        keys = keys,
    )

    public constructor(
        added: IdSet,
        deleted: IdSet,
        delta: D,
        keys: Map<String, YMapChange>,
    ) : this(
        added = YEventItemSet(emptySet(), added),
        deleted = YEventItemSet(emptySet(), deleted),
        delta = delta,
        keys = keys,
    )

    /** Range-oriented YKS view retained for callers that need compact ID membership tests. */
    public val addedIds: IdSet get() = added

    /** Range-oriented YKS view retained for callers that need compact ID membership tests. */
    public val deletedIds: IdSet get() = deleted

    public fun copy(
        added: Set<Item>,
        deleted: Set<Item>,
        delta: @UnsafeVariance D,
        keys: Map<String, YMapChange>,
    ): YEventChanges<D> = YEventChanges(added, deleted, delta, keys)

    public fun copy(
        added: IdSet,
        deleted: IdSet,
        delta: @UnsafeVariance D,
        keys: Map<String, YMapChange>,
    ): YEventChanges<D> = YEventChanges(added, deleted, delta, keys)

    @Suppress("UNCHECKED_CAST", "UNUSED_PARAMETER")
    public companion object {
        @JvmStatic
        @JvmSynthetic
        @JvmName("copy\$default")
        public fun <D> copySetDefault(
            self: YEventChanges<D>,
            added: Set<Item>?,
            deleted: Set<Item>?,
            delta: D?,
            keys: Map<String, YMapChange>?,
            mask: Int,
            marker: Any?,
        ): YEventChanges<D> = self.copy(
            added = if (mask and 1 != 0) self.added else requireNotNull(added),
            deleted = if (mask and 2 != 0) self.deleted else requireNotNull(deleted),
            delta = if (mask and 4 != 0) self.delta else delta as D,
            keys = if (mask and 8 != 0) self.keys else requireNotNull(keys),
        )

        @JvmStatic
        @JvmSynthetic
        @JvmName("copy\$default")
        public fun <D> copyIdSetDefault(
            self: YEventChanges<D>,
            added: IdSet?,
            deleted: IdSet?,
            delta: D?,
            keys: Map<String, YMapChange>?,
            mask: Int,
            marker: Any?,
        ): YEventChanges<D> = self.copy(
            added = if (mask and 1 != 0) self.added else requireNotNull(added),
            deleted = if (mask and 2 != 0) self.deleted else requireNotNull(deleted),
            delta = if (mask and 4 != 0) self.delta else delta as D,
            keys = if (mask and 8 != 0) self.keys else requireNotNull(keys),
        )
    }
}

/** Source-compatible copy overload for the Yjs item-set view. */
public fun <D> YEventChanges<D>.copy(
    added: Set<Item> = this.added,
    deleted: Set<Item> = this.deleted,
    delta: D = this.delta,
    keys: Map<String, YMapChange> = this.keys,
): YEventChanges<D> = YEventChanges(added, deleted, delta, keys)

/** Source-compatible copy overload for the pre-0.2.9 ID-range view. */
public fun <D> YEventChanges<D>.copy(
    added: IdSet = this.added,
    deleted: IdSet = this.deleted,
    delta: D = this.delta,
    keys: Map<String, YMapChange> = this.keys,
): YEventChanges<D> = YEventChanges(added, deleted, delta, keys)

internal fun <D> YEvent.toTypedChanges(
    delta: D,
    includeSequenceItems: Boolean = true,
): YEventChanges<D> {
    val deletedIds = deleteSet.toIdSet()
    val insertedOnly = diffIdSet(insertSet, deletedIds)
    val deletedOnly = diffIdSet(deletedIds, insertSet)
    val transactionEvent = transaction
    val targetSequenceIds = if (transactionEvent == null) {
        null
    } else {
        createIdSet().also { ids ->
            (transactionEvent.addedItems.asSequence() + transactionEvent.deletedItems.asSequence())
                .filter { item -> item.parent == target.name && item.parentSub == null && item.countable }
                .forEach { item -> ids.add(item.id, item.length) }
        }
    }
    return YEventChanges(
        added = when {
            !includeSequenceItems -> createIdSet()
            targetSequenceIds != null -> intersectSets(insertedOnly, targetSequenceIds)
            else -> insertedOnly
        }.toEventItems(transactionEvent, target, deleted = false),
        deleted = when {
            !includeSequenceItems -> createIdSet()
            targetSequenceIds != null -> intersectSets(deletedOnly, targetSequenceIds)
            else -> deletedOnly
        }.toEventItems(transactionEvent, target, deleted = true),
        delta = delta,
        keys = mapChanges.toMap(),
    )
}

private fun IdSet.toEventItems(
    transaction: YTransactionEvent?,
    target: AbstractYType,
    deleted: Boolean,
): Set<Item> {
    if (transaction == null || isEmpty()) return emptySet()
    val items = if (deleted) transaction.deletedItems else transaction.addedItems
    return items.asSequence()
        .filter { item -> item.parent == target.name && item.parentSub == null && has(item.id.client, item.id.clock) }
        .map { item -> transaction.itemView(item, deleted) }
        .toCollection(linkedSetOf())
}

private fun Set<Item>.toIdSet(): IdSet = createIdSet().also { ids ->
    forEach { item -> ids.add(item.id, item.length) }
}

/** Upstream-compatible alias for the common event key-change map. */
public val YEvent.keys: Map<String, YMapChange>
    get() = mapChanges

/** Upstream-compatible view of the common `event.changes` payload. */
public val YEvent.changes: YEventChanges<Any>
    get() = when (target.kind) {
        RootKind.Array,
        RootKind.XmlFragment,
        RootKind.XmlElement -> toTypedChanges(arrayDelta)
        RootKind.Map,
        RootKind.XmlHook -> toTypedChanges(emptyList<YArrayDeltaOp>())
        RootKind.Text,
        RootKind.XmlText -> toTypedChanges(textDelta, includeSequenceItems = false)
    }

public class YArrayEvent internal constructor(event: YEvent) : YTypedEvent<YArray>(event) {
    init {
        require(event.target is YArray) { "YArrayEvent target must be a YArray" }
    }

    override val target: YArray get() = rawEvent.target as YArray
    public val delta: List<YArrayDeltaOp> get() = rawEvent.arrayDelta
    public val arrayDelta: List<YArrayDeltaOp> get() = delta
    public val changes: YEventChanges<List<YArrayDeltaOp>> get() = rawEvent.toTypedChanges(delta)
    public val childListChanged: Boolean get() = rawEvent.childListChanged
}

public class YMapEvent internal constructor(event: YEvent) : YTypedEvent<YMap>(event) {
    init {
        require(event.target is YMap) { "YMapEvent target must be a YMap" }
    }

    override val target: YMap get() = rawEvent.target as YMap
    public val keysChanged: Set<String> get() = rawEvent.keysChanged
    public val changes: YEventChanges<List<YArrayDeltaOp>>
        get() = rawEvent.toTypedChanges(emptyList())
    public val mapChanges: Map<String, YMapChange> get() = changes.keys
    public val delta: YMapDelta get() = rawEvent.mapDelta
}

public class YTextEvent internal constructor(event: YEvent) : YTypedEvent<YText>(event) {
    init {
        require(event.target is YText) { "YTextEvent target must be a YText" }
    }

    override val target: YText get() = rawEvent.target as YText
    public val delta: YTextDelta get() = rawEvent.textDelta
    public val textDelta: YTextDelta get() = delta
    // Upstream YTextEvent intentionally exposes empty added/deleted sets and represents
    // text changes exclusively through its delta.
    public val changes: YEventChanges<YTextDelta>
        get() = rawEvent.toTypedChanges(delta, includeSequenceItems = false)
    public val childListChanged: Boolean get() = rawEvent.childListChanged
    public val keysChanged: Set<String> get() = rawEvent.keysChanged
}

public class YXmlEvent internal constructor(event: YEvent) : YTypedEvent<YXmlSharedType>(event) {
    init {
        require(event.target is YXmlSharedType) {
            "YXmlEvent target must be a YXmlFragment or YXmlElementType"
        }
    }

    override val target: YXmlSharedType get() = rawEvent.target as YXmlSharedType
    public val childListChanged: Boolean get() = rawEvent.childListChanged
    public val attributesChanged: Set<String> get() = rawEvent.keysChanged
    public val delta: List<YArrayDeltaOp> get() = rawEvent.arrayDelta
    public val arrayDelta: List<YArrayDeltaOp> get() = delta
    public val changes: YEventChanges<List<YArrayDeltaOp>> get() = rawEvent.toTypedChanges(delta)
}

public fun YEvent.asTypedEvent(): YTypedEvent<AbstractYType> = when (target.kind) {
    RootKind.Array -> YArrayEvent(this)
    RootKind.Map,
    RootKind.XmlHook -> YMapEvent(this)
    RootKind.Text,
    RootKind.XmlText -> YTextEvent(this)
    RootKind.XmlFragment,
    RootKind.XmlElement -> YXmlEvent(this)
}

public fun YEvent.asArrayEvent(): YArrayEvent = YArrayEvent(this)

public fun YEvent.asMapEvent(): YMapEvent = YMapEvent(this)

public fun YEvent.asTextEvent(): YTextEvent = YTextEvent(this)

public fun YEvent.asXmlEvent(): YXmlEvent = YXmlEvent(this)

public fun YArray.observeTyped(listener: (YArrayEvent) -> Unit): Subscription =
    observe { event -> listener(event.asArrayEvent()) }

public fun YMap.observeTyped(listener: (YMapEvent) -> Unit): Subscription =
    observe { event -> listener(event.asMapEvent()) }

public fun YText.observeTyped(listener: (YTextEvent) -> Unit): Subscription =
    observe { event -> listener(event.asTextEvent()) }

public fun YXmlFragment.observeTyped(listener: (YXmlEvent) -> Unit): Subscription =
    observe { event -> listener(event.asXmlEvent()) }

public fun YXmlElementType.observeTyped(listener: (YXmlEvent) -> Unit): Subscription =
    observe { event -> listener(event.asXmlEvent()) }

/** Typed counterpart to [AbstractYType.observeDeepEvents]. */
public fun AbstractYType.observeDeepTyped(
    listener: (List<YTypedEvent<AbstractYType>>, YTransactionEvent?) -> Unit,
): Subscription = observeDeepEvents { events, transaction ->
    listener(events.map(YEvent::asTypedEvent), transaction)
}
