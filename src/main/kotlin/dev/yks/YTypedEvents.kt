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
public data class YEventChanges<out D>(
    val added: IdSet,
    val deleted: IdSet,
    val delta: D,
    val keys: Map<String, YMapChange>,
)

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
        },
        deleted = when {
            !includeSequenceItems -> createIdSet()
            targetSequenceIds != null -> intersectSets(deletedOnly, targetSequenceIds)
            else -> deletedOnly
        },
        delta = delta,
        keys = mapChanges.toMap(),
    )
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
