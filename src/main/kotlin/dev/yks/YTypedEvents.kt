package dev.yks

/**
 * A type-safe view over the backwards-compatible [YEvent] payload.
 *
 * Kotlin's existing `observe { YEvent -> ... }` ABI is intentionally retained. Call
 * [observeTyped] when the shared type's concrete event/delta shape is useful.
 */
sealed class YTypedEvent<out T : AbstractYType> protected constructor(
    val rawEvent: YEvent,
) {
    abstract val target: T

    val origin: Any? get() = rawEvent.origin
    val update: ByteArray get() = rawEvent.update
    val insertSet: IdSet get() = rawEvent.insertSet
    val deleteSet: DeleteSet get() = rawEvent.deleteSet
    val transaction: YTransactionEvent? get() = rawEvent.transaction
    val currentTarget: AbstractYType get() = rawEvent.currentTarget
    val changedTarget: AbstractYType get() = rawEvent.changedTarget
    val path: List<Any> get() = rawEvent.path
    val deepEvents: List<YEvent> get() = rawEvent.deepEvents
    val deltaDeep: Any get() = rawEvent.deltaDeep

    fun getDelta(deep: Boolean = false, renderer: AbstractRenderer = target.activeRenderer): Any =
        rawEvent.getDelta(deep, renderer)

    fun adds(id: Id): Boolean = rawEvent.adds(id)

    fun deletes(id: Id): Boolean = rawEvent.deletes(id)
}

/**
 * Typed Kotlin representation of Yjs' common `event.changes` object.
 *
 * For array/XML sequence events, [added] and [deleted] exclude items that were both inserted
 * and deleted in the same transaction. Upstream text events intentionally leave both sets
 * empty and expose edits through [delta]. [keys] carries map/attribute changes for every type.
 */
data class YEventChanges<out D>(
    val added: IdSet,
    val deleted: IdSet,
    val delta: D,
    val keys: Map<String, YMapChange>,
)

private fun <D> YEvent.toTypedChanges(
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

class YArrayEvent internal constructor(event: YEvent) : YTypedEvent<YArray>(event) {
    init {
        require(event.target is YArray) { "YArrayEvent target must be a YArray" }
    }

    override val target: YArray get() = rawEvent.target as YArray
    val delta: List<YArrayDeltaOp> get() = rawEvent.arrayDelta
    val arrayDelta: List<YArrayDeltaOp> get() = delta
    val changes: YEventChanges<List<YArrayDeltaOp>> get() = rawEvent.toTypedChanges(delta)
    val childListChanged: Boolean get() = rawEvent.childListChanged
}

class YMapEvent internal constructor(event: YEvent) : YTypedEvent<YMap>(event) {
    init {
        require(event.target is YMap) { "YMapEvent target must be a YMap" }
    }

    override val target: YMap get() = rawEvent.target as YMap
    val keysChanged: Set<String> get() = rawEvent.keysChanged
    val changes: YEventChanges<List<YArrayDeltaOp>>
        get() = rawEvent.toTypedChanges(emptyList())
    val mapChanges: Map<String, YMapChange> get() = changes.keys
    val delta: YMapDelta get() = rawEvent.mapDelta
}

class YTextEvent internal constructor(event: YEvent) : YTypedEvent<YText>(event) {
    init {
        require(event.target is YText) { "YTextEvent target must be a YText" }
    }

    override val target: YText get() = rawEvent.target as YText
    val delta: YTextDelta get() = rawEvent.textDelta
    val textDelta: YTextDelta get() = delta
    // Upstream YTextEvent intentionally exposes empty added/deleted sets and represents
    // text changes exclusively through its delta.
    val changes: YEventChanges<YTextDelta>
        get() = rawEvent.toTypedChanges(delta, includeSequenceItems = false)
    val childListChanged: Boolean get() = rawEvent.childListChanged
    val keysChanged: Set<String> get() = rawEvent.keysChanged
}

class YXmlEvent internal constructor(event: YEvent) : YTypedEvent<YXmlSharedType>(event) {
    init {
        require(event.target is YXmlSharedType) {
            "YXmlEvent target must be a YXmlFragment or YXmlElementType"
        }
    }

    override val target: YXmlSharedType get() = rawEvent.target as YXmlSharedType
    val childListChanged: Boolean get() = rawEvent.childListChanged
    val attributesChanged: Set<String> get() = rawEvent.keysChanged
    val delta: List<YArrayDeltaOp> get() = rawEvent.arrayDelta
    val arrayDelta: List<YArrayDeltaOp> get() = delta
    val changes: YEventChanges<List<YArrayDeltaOp>> get() = rawEvent.toTypedChanges(delta)
}

fun YEvent.asTypedEvent(): YTypedEvent<AbstractYType> = when (target.kind) {
    RootKind.Array -> YArrayEvent(this)
    RootKind.Map,
    RootKind.XmlHook -> YMapEvent(this)
    RootKind.Text,
    RootKind.XmlText -> YTextEvent(this)
    RootKind.XmlFragment,
    RootKind.XmlElement -> YXmlEvent(this)
}

fun YEvent.asArrayEvent(): YArrayEvent = YArrayEvent(this)

fun YEvent.asMapEvent(): YMapEvent = YMapEvent(this)

fun YEvent.asTextEvent(): YTextEvent = YTextEvent(this)

fun YEvent.asXmlEvent(): YXmlEvent = YXmlEvent(this)

fun YArray.observeTyped(listener: (YArrayEvent) -> Unit): Subscription =
    observe { event -> listener(event.asArrayEvent()) }

fun YMap.observeTyped(listener: (YMapEvent) -> Unit): Subscription =
    observe { event -> listener(event.asMapEvent()) }

fun YText.observeTyped(listener: (YTextEvent) -> Unit): Subscription =
    observe { event -> listener(event.asTextEvent()) }

fun YXmlFragment.observeTyped(listener: (YXmlEvent) -> Unit): Subscription =
    observe { event -> listener(event.asXmlEvent()) }

fun YXmlElementType.observeTyped(listener: (YXmlEvent) -> Unit): Subscription =
    observe { event -> listener(event.asXmlEvent()) }

/** Typed counterpart to [AbstractYType.observeDeepEvents]. */
fun AbstractYType.observeDeepTyped(
    listener: (List<YTypedEvent<AbstractYType>>, YTransactionEvent?) -> Unit,
): Subscription = observeDeepEvents { events, transaction ->
    listener(events.map(YEvent::asTypedEvent), transaction)
}
