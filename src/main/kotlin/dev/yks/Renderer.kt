package dev.yks

const val rendererType: String = "y:r"
val `$renderer`: String = rendererType

data class AttributionSchemaField(
    val type: String,
    val optional: Boolean = true,
    val itemType: String? = null,
    val valueType: String? = null,
)

class AttributionJsonSchema internal constructor() {
    val fields: Map<String, AttributionSchemaField> = linkedMapOf(
        "insert" to AttributionSchemaField(type = "array", itemType = "string"),
        "insertAt" to AttributionSchemaField(type = "number"),
        "delete" to AttributionSchemaField(type = "array", itemType = "string"),
        "deleteAt" to AttributionSchemaField(type = "number"),
        "format" to AttributionSchemaField(type = "record", itemType = "string", valueType = "array<string>"),
        "formatAt" to AttributionSchemaField(type = "number"),
    )

    fun check(value: Any?): Boolean {
        val map = value as? Map<*, *> ?: return false
        return map.all { (key, rawValue) ->
            key is String && fields[key]?.matches(rawValue) == true
        }
    }
}

val attributionJsonSchema: AttributionJsonSchema = AttributionJsonSchema()

data class AttributedContent(
    val content: AbstractContent,
    val clock: Long,
    val deleted: Boolean,
    val attrs: List<ContentAttribute>?,
    val renderBehavior: Int,
) {
    init {
        require(renderBehavior in 0..3) { "renderBehavior must be 0, 1, 2, or 3" }
    }

    val render: Boolean = when (renderBehavior) {
        0 -> false
        1 -> !deleted || attrs != null
        else -> true
    }
    val fresh: Boolean = renderBehavior == 3
}

private fun AttributionSchemaField.matches(value: Any?): Boolean = when (type) {
    "array" -> value is List<*> && (itemType == null || value.all { item -> item.matchesSchemaType(itemType) })
    "number" -> value is Number
    "record" -> value is Map<*, *> && value.all { (recordKey, recordValue) ->
        recordKey.matchesSchemaType(itemType) && recordValue.matchesRecordValueType(valueType)
    }
    else -> false
}

private fun Any?.matchesSchemaType(type: String?): Boolean = when (type) {
    null -> true
    "string" -> this is String
    "number" -> this is Number
    else -> false
}

private fun Any?.matchesRecordValueType(type: String?): Boolean = when (type) {
    null -> true
    "array<string>" -> this is List<*> && all { item -> item is String }
    else -> matchesSchemaType(type)
}

data class RendererEvent(
    val name: String,
    val renderer: AbstractRenderer,
    val idSet: IdSet? = null,
    val origin: Any? = null,
    val local: Boolean = true,
)

abstract class AbstractRenderer {
    private val eventListeners = linkedMapOf<String, MutableList<(RendererEvent) -> Unit>>()

    open val attributed: IdSet = createIdSet()
    open val type: String get() = rendererType
    open val `$type`: String get() = rendererType

    open fun hasItem(item: ItemStruct): Boolean =
        attributed.intersects(item.id.client, item.id.clock, item.length)

    fun on(eventName: String, listener: (RendererEvent) -> Unit): Subscription {
        val listeners = eventListeners.getOrPut(eventName) { mutableListOf() }
        listeners.add(listener)
        return Subscription { off(eventName, listener) }
    }

    fun once(eventName: String, listener: (RendererEvent) -> Unit): Subscription {
        lateinit var subscription: Subscription
        val onceListener: (RendererEvent) -> Unit = { event ->
            subscription.close()
            listener(event)
        }
        subscription = on(eventName, onceListener)
        return subscription
    }

    fun off(eventName: String, listener: (RendererEvent) -> Unit) {
        val listeners = eventListeners[eventName] ?: return
        listeners.remove(listener)
        if (listeners.isEmpty()) {
            eventListeners.remove(eventName)
        }
    }

    fun emit(eventName: String, event: RendererEvent = RendererEvent(name = eventName, renderer = this)) {
        emit(if (event.name == eventName && event.renderer === this) event else event.copy(name = eventName, renderer = this))
    }

    fun emit(event: RendererEvent) {
        callAllYksCallbacks(eventListeners[event.name].orEmpty().toList().map { listener -> { listener(event) } })
    }

    open fun destroy() {
        eventListeners.clear()
    }

    abstract fun readContent(
        contents: MutableList<AttributedContent>,
        client: Long,
        clock: Long,
        deleted: Boolean,
        content: AbstractContent,
        renderBehavior: Int,
    )

    abstract fun contentLength(item: ItemStruct): Long
}

fun rendererContentLength(renderer: AbstractRenderer?, item: ItemStruct): Long =
    if (renderer != null && renderer.hasItem(item)) {
        renderer.contentLength(item)
    } else if (item.deleted || !item.countable) {
        0
    } else {
        item.length
    }

open class BaseRenderer : AbstractRenderer() {
    override fun readContent(
        contents: MutableList<AttributedContent>,
        client: Long,
        clock: Long,
        deleted: Boolean,
        content: AbstractContent,
        renderBehavior: Int,
    ) {
        require(renderBehavior in 0..2) { "renderBehavior must be 0, 1, or 2" }
        if (!deleted || renderBehavior != 0) {
            contents.add(AttributedContent(content, clock, deleted, null, renderBehavior))
        }
    }

    override fun contentLength(item: ItemStruct): Long =
        if (item.deleted || !item.countable) 0 else item.length
}

val baseRenderer: BaseRenderer = BaseRenderer()

open class TwosetRenderer(
    inserts: IdMap,
    deletes: IdMap,
) : AbstractRenderer() {
    var inserts: IdMap = inserts
        set(value) {
            field = value
            attributed = rendererAttributed(field, deletes)
        }

    var deletes: IdMap = deletes
        set(value) {
            field = value
            attributed = rendererAttributed(inserts, field)
        }

    override var attributed: IdSet = rendererAttributed(inserts, deletes)
        protected set

    override fun readContent(
        contents: MutableList<AttributedContent>,
        client: Long,
        clock: Long,
        deleted: Boolean,
        content: AbstractContent,
        renderBehavior: Int,
    ) {
        require(renderBehavior in 0..3) { "renderBehavior must be 0, 1, 2, or 3" }
        val slice = (if (deleted) deletes else inserts).slice(client, clock, content.getLength())
        var remaining = if (slice.size == 1) content else content.copy()
        slice.forEach { range ->
            val current = remaining
            if (range.len < current.getLength()) {
                remaining = current.splice(range.len)
            }
            if (!deleted || range.attrs != null || (renderBehavior != 0 && renderBehavior != 3)) {
                contents.add(AttributedContent(current, range.clock, deleted, range.attrs, renderBehavior))
            }
        }
    }

    override fun contentLength(item: ItemStruct): Long {
        if (!item.countable) return 0
        if (!item.deleted) return item.length
        return deletes.slice(item.id.client, item.id.clock, item.length)
            .sumOf { range -> if (range.attrs != null) range.len else 0L }
    }
}

class Attributions(
    val inserts: IdMap = createIdMap(),
    val deletes: IdMap = createIdMap(),
)

data class DiffRendererOptions(
    val attrs: Attributions? = null,
)

class DiffRenderer(
    private val prevDoc: YDoc,
    private val nextDoc: YDoc,
    options: DiffRendererOptions = DiffRendererOptions(),
) : TwosetRenderer(
    inserts = extractAttributions(
        options.attrs?.inserts,
        diffIdSet(createInsertSetFromDoc(nextDoc, filterDeleted = false), createInsertSetFromDoc(prevDoc, filterDeleted = false)),
    ),
    deletes = extractAttributions(
        options.attrs?.deletes,
        diffIdSet(createDeleteSetFromDoc(nextDoc), createDeleteSetFromDoc(prevDoc)),
    ),
) {
    private val attributionOptions: Attributions? = options.attrs
    private val prevInsertSet: IdSet = createInsertSetFromDoc(prevDoc, filterDeleted = false)
    private val prevDeleteSet: IdSet = createDeleteSetFromDoc(prevDoc)
    private val nextBeforeObserverSubscription: Subscription =
        nextDoc.observeBeforeObserverCalls(::handleNextDocBeforeObserverCalls)
    private val prevBeforeObserverSubscription: Subscription =
        prevDoc.observeBeforeObserverCalls(::handlePrevDocBeforeObserverCalls)
    private val prevUpdateSubscription: Subscription =
        prevDoc.onUpdateLossless(::handlePrevDocUpdate)
    private val nextUpdateSubscription: Subscription =
        nextDoc.onUpdateLossless(::handleNextDocUpdate)
    private val nextAfterTransactionSubscription: Subscription =
        nextDoc.observeAfterTransactions(::handleNextDocAfterTransaction)
    private val nextDestroySubscription: Subscription =
        nextDoc.on("destroy") { _: YDocEvent -> destroy() }
    private val prevDestroySubscription: Subscription =
        prevDoc.on("destroy") { _: YDocEvent -> destroy() }
    private var destroyed: Boolean = false

    var suggestionMode: Boolean = true
    var suggestionOrigins: List<Any?>? = null

    fun acceptAllChanges() {
        applyUpdate(prevDoc, encodeStateAsUpdateLossless(nextDoc), origin = this)
    }

    fun acceptChanges(start: Id, end: Id = start) {
        require(start.client == end.client) { "accepted change range must belong to one client" }
        require(end.clock >= start.clock) { "end must not be before start" }
        val selected = createIdSet().also { ids -> ids.add(start.client, start.clock, end.clock - start.clock + 1) }
        val contentIds = acceptedContentIds(intersectSets(selected, inserts), intersectSets(selected, deletes))
        applyUpdate(
            prevDoc,
            intersectUpdateWithContentIdsLossless(encodeStateAsUpdateLossless(nextDoc), contentIds),
            origin = this,
        )
    }

    fun rejectAllChanges() {
        val rejectedInserts = createIdSetFromIdMap(inserts)
        val rejectedDeletes = createIdSetFromIdMap(deletes)
        rejectChangeSet(rejectedInserts, rejectedDeletes)
    }

    fun rejectChanges(start: Id, end: Id = start) {
        require(start.client == end.client) { "rejected change range must belong to one client" }
        require(end.clock >= start.clock) { "end must not be before start" }
        val selected = createIdSet().also { ids -> ids.add(start.client, start.clock, end.clock - start.clock + 1) }
        rejectChangeSet(intersectSets(selected, inserts), intersectSets(selected, deletes))
    }

    override fun readContent(
        contents: MutableList<AttributedContent>,
        client: Long,
        clock: Long,
        deleted: Boolean,
        content: AbstractContent,
        renderBehavior: Int,
    ) {
        require(renderBehavior in 0..3) { "renderBehavior must be 0, 1, 2, or 3" }
        val slices = (if (deleted) deletes else inserts).slice(client, clock, content.getLength()).toMutableList()
        var remaining: AbstractContent? = if (slices.size == 1) content else content.copy()
        var index = 0
        while (index < slices.size) {
            var range = slices[index]
            if (remaining == null || remaining is ContentDeleted) {
                if ((renderBehavior == 0 && range.attrs == null) || inserts.has(client, range.clock)) {
                    index++
                    continue
                }
                remaining = previousContent(client, range.clock, range.len)
            }

            val current = remaining ?: run {
                index++
                continue
            }
            val currentLength = current.getLength()
            if (currentLength <= 0) {
                index++
                remaining = null
                continue
            }
            if (currentLength < range.len) {
                slices[index] = createMaybeAttrRange(range.clock, currentLength, range.attrs)
                slices.add(index + 1, createMaybeAttrRange(range.clock + currentLength, range.len - currentLength, range.attrs))
                range = slices[index]
            }
            remaining = if (range.len < currentLength) current.splice(range.len) else null
            if (!deleted || range.attrs != null || (renderBehavior != 0 && renderBehavior != 3)) {
                contents.add(AttributedContent(current, range.clock, deleted, range.attrs, renderBehavior))
            }
            index++
        }
    }

    override fun contentLength(item: ItemStruct): Long {
        if (!item.deleted) return if (item.countable) item.length else 0
        val contents = mutableListOf<AttributedContent>()
        readContent(contents, item.id.client, item.id.clock, deleted = true, item.content, renderBehavior = 0)
        return contents.sumOf { attributed ->
            if (attributed.attrs != null && attributed.content.isCountable()) attributed.content.getLength() else 0
        }
    }

    private fun refresh() {
        inserts = extractAttributions(
            attributionOptions?.inserts,
            diffIdSet(createInsertSetFromDoc(nextDoc, filterDeleted = false), createInsertSetFromDoc(prevDoc, filterDeleted = false)),
        )
        deletes = extractAttributions(
            attributionOptions?.deletes,
            diffIdSet(createDeleteSetFromDoc(nextDoc), createDeleteSetFromDoc(prevDoc)),
        )
        attributed = rendererAttributed(inserts, deletes)
    }

    private fun handleNextDocBeforeObserverCalls(event: YTransactionEvent) {
        val diffInserts = diffIdSet(event.insertSet, prevInsertSet)
        insertIntoIdMap(inserts, extractAttributions(attributionOptions?.inserts, diffInserts))

        val diffDeletes = diffIdSet(diffIdSet(event.deleteIdSet, prevDeleteSet), inserts)
        insertIntoIdMap(deletes, extractAttributions(attributionOptions?.deletes, diffDeletes))

        attributed = rendererAttributed(inserts, deletes)
    }

    private fun handlePrevDocBeforeObserverCalls(event: YTransactionEvent) {
        insertIntoIdSet(prevInsertSet, event.insertSet)
        insertIntoIdSet(prevDeleteSet, event.deleteIdSet)

        inserts = diffIdMap(inserts, event.insertSet)
        deletes = diffIdMap(deletes, event.deleteIdSet)
        attributed = rendererAttributed(inserts, deletes)

        emitChange(
            diffIdSet(
                mergeIdSets(listOf(event.insertSet, event.deleteIdSet)),
                intersectSets(event.insertSet, event.deleteIdSet),
            ),
            origin = event.origin,
            local = event.local,
        )
    }

    private fun previousContent(client: Long, clock: Long, len: Long): AbstractContent? {
        val previous = runCatching { prevDoc.store.getItem(Id(client, clock)) }.getOrNull() ?: return null
        val diffStart = clock - previous.id.clock
        var recovered = previous.content.copy()
        if (diffStart > 0) {
            recovered = recovered.splice(diffStart)
        }
        if (len < recovered.getLength()) {
            recovered.splice(len)
        }
        return recovered
    }

    private fun acceptedContentIds(acceptedInserts: IdSet, acceptedDeletes: IdSet): ContentIds {
        val expandedInserts = acceptedInserts.copy()
        val expandedDeletes = acceptedDeletes.copy()
        val queue = ArrayDeque<String>()

        fun enqueueRefs(item: StoreItem) {
            item.content.nestedTypeRefs().forEach { ref ->
                queue.add(ref.name)
            }
        }

        acceptedInserts.ranges().forEach { (client, range) ->
            nextDoc.store.allItems()
                .filter { item ->
                    item.id.client == client &&
                        item.id.clock < range.end &&
                        range.clock < checkedClockAdd(item.id.clock, item.length)
                }
                .forEach(::enqueueRefs)
        }

        val seenParents = linkedSetOf<String>()
        while (queue.isNotEmpty()) {
            val parent = queue.removeFirst()
            if (!seenParents.add(parent)) continue
            nextDoc.store.allItems()
                .filter { item -> item.parent == parent }
                .forEach { item ->
                    expandedInserts.add(item.id, item.length)
                    if (item.deleted) {
                        expandedDeletes.add(item.id, item.length)
                    }
                    enqueueRefs(item)
                }
        }

        return ContentIds(inserts = expandedInserts, deletes = expandedDeletes)
    }

    private fun rejectChangeSet(rejectedInserts: IdSet, rejectedDeletes: IdSet) {
        if (rejectedInserts.isEmpty() && rejectedDeletes.isEmpty()) return
        var undoInsertSet = createIdSet()
        var undoDeleteSet = createIdSet()
        val undoSubscription = nextDoc.observeAfterTransactions { event ->
            if (event.origin === this) {
                insertIntoIdSet(undoInsertSet, event.insertSet)
                insertIntoIdSet(undoDeleteSet, event.deleteIdSet)
            }
        }
        val undoManager = UndoManager(nextDoc, UndoManagerOptions(captureTimeoutMillis = 0))
        try {
            nextDoc.transact(origin = this) {
                undoManager.pushUndoStackItem(StackItem(rejectedInserts, rejectedDeletes))
                undoManager.undo()
            }
        } finally {
            undoManager.close()
            undoSubscription.close()
        }

        val syncInserts = mergeIdSets(listOf(rejectedInserts, undoInsertSet))
        val syncDeletes = mergeIdSets(listOf(rejectedInserts, rejectedDeletes, undoDeleteSet))
        if (syncInserts.isEmpty() && syncDeletes.isEmpty()) return
        val update = intersectUpdateWithContentIdsLossless(
            encodeStateAsUpdateLossless(nextDoc),
            ContentIds(inserts = syncInserts, deletes = syncDeletes),
        )
        applyUpdate(prevDoc, update, origin = this)
    }

    private fun handlePrevDocUpdate(
        update: ByteArray,
        origin: Any?,
        @Suppress("UNUSED_PARAMETER") doc: YDoc,
        @Suppress("UNUSED_PARAMETER") transaction: YTransactionEvent?,
    ) {
        if (origin !== this) {
            applyUpdate(nextDoc, update)
        }
    }

    private fun handleNextDocUpdate(
        update: ByteArray,
        origin: Any?,
        @Suppress("UNUSED_PARAMETER") doc: YDoc,
        transaction: YTransactionEvent?,
    ) {
        if (
            !suggestionMode &&
            transaction?.local == true &&
            (suggestionOrigins == null || suggestionOrigins!!.any { suggestionOrigin -> suggestionOrigin == origin })
        ) {
            applyUpdate(prevDoc, update, origin = this)
        }
    }

    private fun handleNextDocAfterTransaction(event: YTransactionEvent) {
        if (
            suggestionMode ||
            !event.local ||
            (suggestionOrigins != null && suggestionOrigins!!.none { suggestionOrigin -> suggestionOrigin == event.origin })
        ) {
            return
        }
        val attributedDeletes = event.meta[attributedDeletesMetaKey] as? IdSet ?: return
        if (attributedDeletes.isEmpty()) return
        applyUpdate(
            prevDoc,
            UpdateCodec.encodeLossless(DocumentUpdate(emptyList(), attributedDeletes.toDeleteSet())),
            origin = this,
        )
    }

    private fun currentChangeSet(): IdSet =
        mergeIdSets(listOf(createIdSetFromIdMap(inserts), createIdSetFromIdMap(deletes)))

    private fun emitChange(idSet: IdSet, origin: Any?, local: Boolean = true) {
        emit(RendererEvent(name = "change", renderer = this, idSet = idSet, origin = origin, local = local))
    }

    override fun destroy() {
        if (destroyed) return
        destroyed = true
        nextBeforeObserverSubscription.close()
        prevBeforeObserverSubscription.close()
        prevUpdateSubscription.close()
        nextUpdateSubscription.close()
        nextAfterTransactionSubscription.close()
        nextDestroySubscription.close()
        prevDestroySubscription.close()
        super.destroy()
    }
}

private fun ItemContent.nestedTypeRefs(): List<YValue.TypeRef> = when (this) {
    is ItemContent.Value -> value.nestedTypeRefs()
    is ItemContent.MapEntry -> value.nestedTypeRefs()
    is ItemContent.TextEmbed -> value.nestedTypeRefs()
    is ItemContent.XmlType -> listOf(ref)
    is ItemContent.Text,
    is ItemContent.TextFormat,
    is ItemContent.NativeTextFormat,
    is ItemContent.XmlNode,
    is ItemContent.Deleted -> emptyList()
}

private fun YValue.nestedTypeRefs(): List<YValue.TypeRef> = when (this) {
    is YValue.TypeRef -> listOf(this)
    is YValue.ListValue -> value.flatMap { item -> item.nestedTypeRefs() }
    is YValue.MapValue -> value.values.flatMap { item -> item.nestedTypeRefs() }
    YValue.Undefined,
    YValue.Null,
    is YValue.Bool,
    is YValue.LongNumber,
    is YValue.DoubleNumber,
    is YValue.BigIntNumber,
    is YValue.StringValue,
    is YValue.BinaryValue,
    is YValue.SubdocRef -> emptyList()
}

fun createDiffRenderer(
    prevDoc: YDoc,
    nextDoc: YDoc,
    options: DiffRendererOptions = DiffRendererOptions(),
): DiffRenderer = DiffRenderer(prevDoc, nextDoc, options)

class SnapshotRenderer(
    val prevSnapshot: Snapshot,
    val nextSnapshot: Snapshot = prevSnapshot,
) : AbstractRenderer() {
    val attrs: IdMap = snapshotAttrs(prevSnapshot, nextSnapshot)
    override val attributed: IdSet = createIdSetFromIdMap(attrs)

    override fun hasItem(item: ItemStruct): Boolean =
        (nextSnapshot.sv[item.id.client] ?: 0) < item.id.clock + item.length ||
            attributed.intersects(item.id.client, item.id.clock, item.length)

    override fun readContent(
        contents: MutableList<AttributedContent>,
        client: Long,
        clock: Long,
        deleted: Boolean,
        content: AbstractContent,
        renderBehavior: Int,
    ) {
        require(renderBehavior in 0..3) { "renderBehavior must be 0, 1, 2, or 3" }
        if ((nextSnapshot.sv[client] ?: 0) <= clock) return
        val slice = attrs.slice(client, clock, content.getLength())
        var remaining = if (slice.size == 1) content else content.copy()
        slice.forEach { range ->
            if ((nextSnapshot.sv[client] ?: 0) <= range.clock) return@forEach
            val current = remaining
            if (range.len < current.getLength()) {
                remaining = current.splice(range.len)
            }
            val rangeDeleted = nextSnapshot.ds.hasId(Id(client, range.clock))
            if (renderBehavior != 0 || !rangeDeleted || range.attrs?.isNotEmpty() == true) {
                val attrsWithoutChange = when {
                    range.attrs == null -> null
                    range.attrs.isEmpty() -> null
                    else -> range.attrs.filterNot { attr -> attr.name == "change" }
                }
                contents.add(AttributedContent(current, range.clock, rangeDeleted, attrsWithoutChange, renderBehavior))
            }
        }
    }

    override fun contentLength(item: ItemStruct): Long {
        if (!item.countable) return 0
        if (!item.deleted) return item.length
        return attrs.slice(item.id.client, item.id.clock, item.length)
            .sumOf { range -> if (range.attrs != null) range.len else 0L }
    }
}

fun createSnapshotRenderer(
    prevSnapshot: Snapshot,
    nextSnapshot: Snapshot = prevSnapshot,
): SnapshotRenderer = SnapshotRenderer(prevSnapshot, nextSnapshot)

private fun extractAttributions(attrs: IdMap?, slice: IdSet): IdMap =
    if (attrs == null) {
        createIdMapFromIdSet(slice, emptyList())
    } else {
        mergeIdMaps(listOf(intersectIdMapWithIdSet(attrs, slice), createIdMapFromIdSet(slice, emptyList())))
    }

private fun rendererAttributed(inserts: IdMap, deletes: IdMap): IdSet =
    mergeIdSets(listOf(createIdSetFromIdMap(inserts), createIdSetFromIdMap(deletes)))

private fun snapshotAttrs(prevSnapshot: Snapshot, nextSnapshot: Snapshot): IdMap {
    val inserts = createIdMap()
    val change = listOf(createContentAttribute("change", ""))
    nextSnapshot.sv.toSortedMap().forEach { (client, clock) ->
        val prevClock = prevSnapshot.sv[client] ?: 0
        inserts.add(client, 0, prevClock, emptyList())
        inserts.add(client, prevClock, clock - prevClock, change)
    }
    val deletes = createIdMapFromIdSet(diffIdSet(nextSnapshot.ds, prevSnapshot.ds), change)
    return mergeIdMaps(listOf(diffIdMap(inserts, prevSnapshot.ds), deletes))
}

private fun intersectIdMapWithIdSet(idMap: IdMap, idSet: IdSet): IdMap {
    val result = createIdMap()
    idMap.ranges().forEach { (client, range) ->
        idSet.slice(client, range.clock, range.len)
            .filter { slice -> slice.exists }
            .forEach { slice -> result.add(client, slice.clock, slice.len, range.attrs) }
    }
    return result
}
