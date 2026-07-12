package dev.yks

enum class RootKind {
    Array,
    Map,
    Text,
    XmlFragment,
    XmlElement,
    XmlHook,
    XmlText,
}

const val YArrayRefID: Int = 0
const val YMapRefID: Int = 1
const val YTextRefID: Int = 2
const val YXmlElementRefID: Int = 3
const val YXmlFragmentRefID: Int = 4
const val YXmlHookRefID: Int = 5
const val YXmlTextRefID: Int = 6

val `$ytypeAny`: (Any?) -> Boolean = { value -> value is AbstractYType }

fun `$ytype`(): (Any?) -> Boolean = `$ytypeAny`

val `$ydoc`: (Any?) -> Boolean = { value -> value is YDoc }

typealias Attribution = Map<String, Any?>

/**
 * Distinguishes an omitted Y.Text attribute argument from an explicitly supplied empty map.
 *
 * Upstream Yjs inherits the formatting at the insertion point when the argument is omitted,
 * while `{}` explicitly inserts unformatted content. Keeping a private sentinel preserves the
 * existing non-null public parameter type and its default-argument ABI.
 */
private object UnspecifiedTextAttributes : Map<String, Any?> by emptyMap()

fun warnPrematureAccess() {
    System.err.println("Invalid access: Add Yjs type to a document before reading data.")
}

fun createAttributionFromAttributionItems(attrs: List<ContentAttribute>?, deleted: Boolean): Attribution? {
    if (attrs == null) return null
    val attribution = linkedMapOf<String, Any?>()
    val by = mutableListOf<Any?>()
    attribution[if (deleted) "delete" else "insert"] = by
    attrs.forEach { attr ->
        when (attr.name) {
            "insert" -> if (!deleted) by.add(attr.`val`)
            "delete" -> if (deleted) by.add(attr.`val`)
            else -> if (!attr.name.startsWith("_")) attribution[attr.name] = attr.`val`
        }
    }
    return attribution.entries.associateTo(linkedMapOf()) { (key, value) ->
        key to if (value is MutableList<*>) value.toList() else value
    }
}

sealed class AbstractYType protected constructor(
    internal val doc: YDoc,
    val name: String,
    internal val kind: RootKind,
) {
    companion object {
        fun from(delta: List<YArrayDeltaOp>, doc: YDoc = YDoc(), name: String = ""): YArray =
            YArray.from(delta, doc, name)

        fun from(delta: YArrayDeepDelta, doc: YDoc = YDoc(), name: String = ""): YArray =
            YArray.from(delta, doc, name)

        fun from(delta: YMapDelta, doc: YDoc = YDoc(), name: String = ""): YMap =
            YMap.from(delta, doc, name)

        fun from(delta: YMapDeepDelta, doc: YDoc = YDoc(), name: String = ""): YMap =
            YMap.from(delta, doc, name)

        fun from(delta: YTextDelta, doc: YDoc = YDoc(), name: String = ""): YText =
            YText.from(delta, doc, name)

        fun from(delta: YTextDeepDelta, doc: YDoc = YDoc(), name: String = ""): YText =
            YText.from(delta, doc, name)

        fun from(delta: YXmlFragmentDeepDelta, doc: YDoc = YDoc(), name: String = ""): YXmlFragment =
            YXmlFragment.from(delta, doc, name)
    }

    private val observers = mutableListOf<(YEvent) -> Unit>()
    private val transactionObservers = mutableListOf<(YEvent, YTransactionEvent?) -> Unit>()
    private val deepObservers = mutableListOf<(YEvent) -> Unit>()
    private val deepTransactionObservers = mutableListOf<(YEvent, YTransactionEvent?) -> Unit>()
    private val eventListeners = linkedMapOf<String, MutableList<(YTypeEvent) -> Unit>>()
    private var deltaCache: YDeepDelta? = null
    private var rendererChangeSubscription: Subscription? = null
    internal var activeRenderer: AbstractRenderer = baseRenderer
        private set
    var isDestroyed: Boolean = false
        private set

    fun observe(listener: (YEvent) -> Unit): Subscription {
        observers.add(listener)
        return Subscription { observers.remove(listener) }
    }

    fun observe(listener: (YEvent, YTransactionEvent?) -> Unit): Subscription {
        transactionObservers.add(listener)
        return Subscription { transactionObservers.remove(listener) }
    }

    fun observeDeep(listener: (YEvent) -> Unit): Subscription {
        deepObservers.add(listener)
        return Subscription { deepObservers.remove(listener) }
    }

    fun observeDeep(listener: (YEvent, YTransactionEvent?) -> Unit): Subscription {
        deepTransactionObservers.add(listener)
        return Subscription { deepTransactionObservers.remove(listener) }
    }

    fun unobserve(listener: (YEvent) -> Unit) {
        observers.remove(listener)
    }

    fun unobserve(listener: (YEvent, YTransactionEvent?) -> Unit) {
        transactionObservers.remove(listener)
    }

    fun unobserveDeep(listener: (YEvent) -> Unit) {
        deepObservers.remove(listener)
    }

    fun unobserveDeep(listener: (YEvent, YTransactionEvent?) -> Unit) {
        deepTransactionObservers.remove(listener)
    }

    fun on(eventName: String, listener: (YTypeEvent) -> Unit): Subscription {
        val listeners = eventListeners.getOrPut(eventName) { mutableListOf() }
        listeners.add(listener)
        return Subscription { off(eventName, listener) }
    }

    fun once(eventName: String, listener: (YTypeEvent) -> Unit): Subscription {
        lateinit var subscription: Subscription
        val onceListener: (YTypeEvent) -> Unit = { event ->
            subscription.close()
            listener(event)
        }
        subscription = on(eventName, onceListener)
        return subscription
    }

    fun off(eventName: String, listener: (YTypeEvent) -> Unit) {
        val listeners = eventListeners[eventName] ?: return
        listeners.remove(listener)
        if (listeners.isEmpty()) {
            eventListeners.remove(eventName)
        }
    }

    fun emit(eventName: String, event: YTypeEvent = YTypeEvent(name = eventName, target = this)) {
        emitTypeEvent(if (event.name == eventName && event.target === this) event else event.copy(name = eventName, target = this))
    }

    fun emit(event: YTypeEvent) {
        emitTypeEvent(if (event.target === this) event else event.copy(target = this))
    }

    fun destroy() {
        if (isDestroyed) return
        isDestroyed = true
        rendererChangeSubscription?.close()
        rendererChangeSubscription = null
        activeRenderer = baseRenderer
        clearCache()
        emitTypeEvent(YTypeEvent(name = "destroy", target = this))
        eventListeners.clear()
    }

    internal fun emit(event: YEvent) {
        clearCache()
        val callbacks = observers.toList().map { listener -> { listener(event) } }.toMutableList()
        callbacks.addAll(transactionObservers.toList().map { listener -> { listener(event, event.transaction) } })
        callbacks.addAll(deltaEventCallbacks(event))
        callAllYksCallbacks(callbacks)
    }

    internal val hasDeepObservers: Boolean get() = deepObservers.isNotEmpty() || deepTransactionObservers.isNotEmpty()

    internal val hasDeltaListeners: Boolean get() = eventListeners["delta"]?.isNotEmpty() == true

    internal val hasDeltaCache: Boolean get() = deltaCache != null

    internal fun emitDeep(event: YEvent) {
        val callbacks = deepObservers.toList().map { listener -> { listener(event) } }.toMutableList()
        callbacks.addAll(deepTransactionObservers.toList().map { listener -> { listener(event, event.transaction) } })
        callAllYksCallbacks(callbacks)
    }

    internal fun emitDelta(event: YEvent) {
        callAllYksCallbacks(deltaEventCallbacks(event))
    }

    private fun deltaEventCallbacks(event: YEvent): List<() -> Unit> {
        return eventListeners["delta"].orEmpty().toList().map { listener ->
            {
                listener(
                    YTypeEvent(
                        name = "delta",
                        target = this,
                        delta = event.delta,
                        origin = event.origin,
                        transaction = event.transaction,
                        yEvent = event,
                    ),
                )
            }
        }
    }

    private fun emitTypeEvent(event: YTypeEvent) {
        callAllYksCallbacks(eventListeners[event.name].orEmpty().toList().map { listener -> { listener(event) } })
    }

    fun getPathTo(child: AbstractYType, renderer: AbstractRenderer = baseRenderer): List<Any> {
        require(child.doc === doc) { "child must belong to the same document" }
        return doc.pathBetween(name, child.name, renderer) ?: error("target type is not a visible descendant")
    }

    abstract fun toJson(): Any?

    open fun toJSON(): Any? = toJson()

    val delta: YDeepDelta
        get() = deltaCache ?: renderDeepDelta().also { deltaCache = it }

    fun clearCache() {
        deltaCache = null
    }

    fun useRenderer(renderer: AbstractRenderer): AbstractYType {
        if (activeRenderer === renderer && renderer === baseRenderer) {
            return this
        }
        rendererChangeSubscription?.close()
        rendererChangeSubscription = null
        if (renderer !== baseRenderer) {
            rendererChangeSubscription = renderer.on("change") { event -> applyRendererChange(event) }
        }
        val hadDeltaCache = deltaCache != null
        val shouldRenderChange = hadDeltaCache || hasDeltaListeners
        val oldDelta = if (shouldRenderChange) {
            deltaCache ?: renderDeepDelta()
        } else {
            null
        }
        activeRenderer = renderer
        val newDelta = if (shouldRenderChange) renderDeepDelta() else null
        if (hadDeltaCache) {
            deltaCache = newDelta
        } else {
            clearCache()
        }
        if (hasDeltaListeners && newDelta != null && oldDelta != newDelta) {
            emitTypeEvent(YTypeEvent(name = "delta", target = this, delta = newDelta, origin = null))
        }
        return this
    }

    private fun applyRendererChange(event: RendererEvent) {
        if (deltaCache == null && !hasDeltaListeners) return
        if (event.renderer !== activeRenderer) return
        val changes = event.idSet ?: activeRenderer.attributed
        val changeDelta = renderDeepDelta(
            DeepDeltaRenderOptions(
                renderer = activeRenderer,
                itemsToRender = changes,
                retainInserts = true,
                retainDeletes = true,
            ),
        )
        val newDelta = renderDeepDelta()
        if (deltaCache != null) {
            deltaCache = newDelta
        }
        if (hasDeltaListeners && !changeDelta.isEmptyDeepDelta()) {
            emitTypeEvent(YTypeEvent(name = "delta", target = this, delta = changeDelta, origin = event.origin))
        }
    }

    val parent: AbstractYType? get() = doc.parentOf(this)

    val typeRef: Int get() = kind.toTypeRefId()

    val legacyTypeRef: Int get() = typeRef
}

data class YTypeEvent(
    val name: String,
    val target: AbstractYType,
    val delta: Any? = null,
    val origin: Any? = null,
    val transaction: YTransactionEvent? = null,
    val yEvent: YEvent? = null,
)

fun RootKind.toTypeRefId(): Int = when (this) {
    RootKind.Array -> YArrayRefID
    RootKind.Map -> YMapRefID
    RootKind.Text -> YTextRefID
    RootKind.XmlFragment -> YXmlFragmentRefID
    RootKind.XmlElement -> YXmlElementRefID
    RootKind.XmlHook -> YXmlHookRefID
    RootKind.XmlText -> YXmlTextRefID
}

fun rootKindFromTypeRefId(typeRef: Int): RootKind = when (typeRef) {
    YArrayRefID -> RootKind.Array
    YMapRefID -> RootKind.Map
    YTextRefID -> RootKind.Text
    YXmlElementRefID -> RootKind.XmlElement
    YXmlFragmentRefID -> RootKind.XmlFragment
    YXmlHookRefID -> RootKind.XmlHook
    YXmlTextRefID -> RootKind.XmlText
    else -> error("unknown type ref: $typeRef")
}

fun typeRefId(type: AbstractYType): Int = type.typeRef

class YArray internal constructor(doc: YDoc, name: String) : AbstractYType(doc, name, RootKind.Array), Iterable<Any?> {
    constructor() : this(YDoc(), "")

    constructor(values: Iterable<Any?>) : this() {
        push(values.toList())
    }

    constructor(vararg values: Any?) : this(values.toList())

    val size: Int get() = doc.visibleSequence(name).count { it.content is ItemContent.Value }

    val length: Int get() = size

    val attrSize: Int get() = getAttrs().size

    fun insert(index: Int, values: List<Any?>) {
        if (values.isEmpty()) return
        val start = index.coerceAtLeast(0)
        require(start <= size) { "insert index is out of bounds" }
        doc.transact {
            val anchors = doc.insertionAnchors(name, RootKind.Array, start)
            var origin = anchors.first
            val rightOrigin = anchors.second
            values.forEach { raw ->
                val content = ItemContent.Value(doc.storeValue(raw, parent = name))
                val item = StoreItem(
                    id = doc.nextId(),
                    origin = origin,
                    rightOrigin = rightOrigin,
                    parent = name,
                    parentSub = null,
                    content = content,
                )
                doc.integrateLocal(item)
                origin = item.id
            }
        }
    }

    fun insert(index: Int, vararg values: Any?) {
        insert(index, values.toList())
    }

    fun push(values: List<Any?>) {
        insert(size, values)
    }

    fun push(vararg values: Any?) {
        push(values.toList())
    }

    fun unshift(values: List<Any?>) {
        insert(0, values)
    }

    fun unshift(vararg values: Any?) {
        unshift(values.toList())
    }

    fun delete(index: Int, length: Int = 1) {
        doc.deleteVisible(name, index, length)
    }

    fun clear() {
        delete(0, size)
    }

    fun setAttr(key: String, value: Any?): Any? = doc.setTypeAttribute(name, key, value)

    fun setAttribute(key: String, value: Any?): Any? = setAttr(key, value)

    fun setAttrs(values: Map<String, Any?>): YArray {
        doc.transact {
            values.toSortedMap().forEach { (key, value) -> setAttr(key, value) }
        }
        return this
    }

    fun getAttr(key: String): Any? = doc.typeAttribute(name, key)

    fun getAttr(key: String, snapshot: Snapshot): Any? =
        doc.mapValueAtSnapshot(this, key, snapshot)?.let(doc::valueToAny)

    fun getAttribute(key: String): Any? = getAttr(key)

    fun getAttribute(key: String, snapshot: Snapshot): Any? = getAttr(key, snapshot)

    fun getAttrs(): Map<String, Any?> = doc.typeAttributes(name)

    fun getAttrs(snapshot: Snapshot): Map<String, Any?> =
        doc.mapAtSnapshot(this, snapshot).mapValues { (_, value) -> doc.valueToAny(value) }

    fun attrKeys(): Set<String> = getAttrs().keys

    fun attrValues(): Collection<Any?> = getAttrs().values

    fun attrEntries(): Set<Map.Entry<String, Any?>> = getAttrs().entries

    fun <T> mapAttrs(transform: (value: Any?, key: String) -> T): List<T> {
        return getAttrs().map { (key, value) -> transform(value, key) }
    }

    fun <T> mapAttrs(transform: (value: Any?, key: String, type: YArray) -> T): List<T> {
        return getAttrs().map { (key, value) -> transform(value, key, this) }
    }

    fun forEachAttr(action: (value: Any?, key: String) -> Unit) {
        getAttrs().forEach { (key, value) -> action(value, key) }
    }

    fun forEachAttr(action: (value: Any?, key: String, type: YArray) -> Unit) {
        getAttrs().forEach { (key, value) -> action(value, key, this) }
    }

    fun hasAttr(key: String): Boolean = doc.hasTypeAttribute(name, key)

    fun hasAttr(key: String, snapshot: Snapshot): Boolean =
        doc.mapValueAtSnapshot(this, key, snapshot) != null

    fun hasAttribute(key: String): Boolean = hasAttr(key)

    fun hasAttribute(key: String, snapshot: Snapshot): Boolean = hasAttr(key, snapshot)

    fun deleteAttr(key: String) {
        doc.deleteTypeAttribute(name, key)
    }

    fun deleteAttribute(key: String) {
        deleteAttr(key)
    }

    fun removeAttribute(key: String) {
        deleteAttr(key)
    }

    fun clearAttrs() {
        doc.transact {
            getAttrs().keys.forEach(::deleteAttr)
        }
    }

    fun get(index: Int): Any? {
        if (index < 0) return null
        return toList().getOrNull(index)
    }

    fun slice(start: Int = 0, end: Int = size): List<Any?> {
        val values = toList()
        val normalizedStart = normalizeSliceIndex(start, values.size)
        val normalizedEnd = normalizeSliceIndex(end, values.size)
        if (normalizedEnd <= normalizedStart) return emptyList()
        return values.subList(normalizedStart, normalizedEnd)
    }

    fun toList(): List<Any?> = doc.visibleSequence(name)
        .filter { it.content is ItemContent.Value }
        .map { doc.valueToAny((it.content as ItemContent.Value).value) }

    fun toArray(): List<Any?> = toList()

    fun clone(targetDoc: YDoc = doc): YArray {
        return targetDoc.createArray().also { cloned ->
            cloned.push(toList().map { it.cloneValueInto(targetDoc) })
            cloned.setAttrs(getAttrs().mapValues { (_, value) -> value.cloneValueInto(targetDoc) })
        }
    }

    fun toDelta(): List<YArrayDeltaOp> {
        val values = toList()
        return if (values.isEmpty()) emptyList() else listOf(YArrayDeltaOp(insert = values))
    }

    fun toDeltaDeep(renderer: AbstractRenderer = activeRenderer): YArrayDeepDelta =
        renderArrayDeepDelta(this, DeepDeltaRenderOptions(renderer = renderer))

    fun applyDelta(
        delta: List<YArrayDeltaOp>,
        origin: Any? = null,
        renderer: AbstractRenderer = activeRenderer,
    ) {
        doc.transact({ transaction ->
            var renderedIndex = 0
            delta.forEach { op ->
                when {
                    op.retain != null -> renderedIndex += op.retain
                    op.delete != null -> {
                        if (op.delete <= 0) return@forEach
                        val startRendered = renderedIndex.coerceAtLeast(0)
                        recordRendererAttributedDeletes(transaction, this, startRendered, op.delete, renderer)
                        val index = renderedSequenceIndexToVisibleIndex(this, startRendered, renderer)
                        val rawEnd = renderedSequenceIndexToVisibleIndex(
                            this,
                            startRendered + op.delete,
                            renderer,
                            clampToEnd = true,
                        )
                        delete(index, rawEnd - index)
                    }
                    op.insert != null -> {
                        if (op.insert.isEmpty()) return@forEach
                        val index = renderedSequenceIndexToVisibleIndex(this, renderedIndex, renderer)
                        insert(index, op.insert)
                        renderedIndex += op.insert.size
                    }
                }
            }
        }, origin = origin)
    }

    fun applyDeltaDeep(delta: YArrayDeepDelta, origin: Any? = null) {
        doc.transact(origin = origin) {
            clear()
            clearAttrs()
            setAttrs(delta.attrs.fromDeepDeltaValues(doc))
            applyDelta(delta.delta.fromDeepDeltaValues(doc))
        }
    }

    fun <T> map(transform: (Any?) -> T): List<T> = toList().map(transform)

    fun <T> map(transform: (value: Any?, index: Int) -> T): List<T> =
        toList().mapIndexed { index, value -> transform(value, index) }

    fun <T> map(transform: (value: Any?, index: Int, type: YArray) -> T): List<T> =
        toList().mapIndexed { index, value -> transform(value, index, this) }

    fun forEach(action: (Any?) -> Unit) {
        toList().forEach(action)
    }

    fun forEach(action: (value: Any?, index: Int) -> Unit) {
        toList().forEachIndexed { index, value -> action(value, index) }
    }

    fun forEach(action: (value: Any?, index: Int, type: YArray) -> Unit) {
        toList().forEachIndexed { index, value -> action(value, index, this) }
    }

    fun forEachIndexed(action: (Int, Any?) -> Unit) {
        toList().forEachIndexed(action)
    }

    override fun iterator(): Iterator<Any?> = toList().iterator()

    override fun toJson(): List<Any?> = doc.visibleSequence(name)
        .filter { it.content is ItemContent.Value }
        .map { doc.valueToJson((it.content as ItemContent.Value).value) }

    override fun toJSON(): List<Any?> = toList().map(::toYTypeJsonValue)

    companion object {
        fun from(values: Iterable<Any?>, doc: YDoc = YDoc(), name: String = ""): YArray {
            return doc.getArray(name).also { it.push(values.toList()) }
        }

        fun from(delta: List<YArrayDeltaOp>, doc: YDoc = YDoc(), name: String = ""): YArray {
            return doc.getArray(name).also { it.applyDelta(delta) }
        }

        fun from(delta: YArrayDeepDelta, doc: YDoc = YDoc(), name: String = ""): YArray {
            return doc.getArray(name).also { it.applyDeltaDeep(delta) }
        }
    }

    private fun normalizeSliceIndex(index: Int, size: Int): Int {
        val normalized = if (index < 0) size + index else index
        return normalized.coerceIn(0, size)
    }
}

open class YText internal constructor(
    doc: YDoc,
    name: String,
    kind: RootKind = RootKind.Text,
) : AbstractYType(doc, name, kind), Iterable<Any?> {
    constructor() : this(YDoc(), "")

    constructor(text: String, attributes: Map<String, Any?> = UnspecifiedTextAttributes) : this() {
        insert(0, text, attributes)
    }

    init {
        require(kind == RootKind.Text || kind == RootKind.XmlText) { "YText kind must be a text sequence" }
    }

    val length: Int get() = doc.visibleSequence(name).count { it.content.kind == kind }

    val attrSize: Int get() = getAttrs().size

    fun insert(index: Int, text: String, attributes: Map<String, Any?> = UnspecifiedTextAttributes) {
        if (text.isEmpty()) return
        val start = index.coerceAtLeast(0)
        require(start <= length) { "insert index is out of bounds" }
        doc.transact {
            val normalized = attributes.normalizeOptionalTextAttributes()
            val baseAttributes = normalized.orEmpty()
            insertAttributedTextEntries(
                start,
                text.map { ItemContent.Text(it.toString(), baseAttributes, kind = kind) },
                normalized,
            )
        }
    }

    fun insertText(
        index: Int,
        text: String,
        attributes: Map<String, Any?> = UnspecifiedTextAttributes,
        origin: Any? = null,
    ) {
        if (text.isEmpty()) return
        val start = index.coerceAtLeast(0)
        require(start <= length) { "insert index is out of bounds" }
        doc.transact(origin = origin) {
            val normalized = attributes.normalizeOptionalTextAttributes()
            val baseAttributes = normalized.orEmpty()
            insertAttributedTextEntries(
                start,
                text.map { ItemContent.Text(it.toString(), baseAttributes, kind = kind) },
                normalized,
            )
        }
    }

    fun insert(
        index: Int,
        values: List<Any?>,
        attributes: Map<String, Any?> = UnspecifiedTextAttributes,
    ) {
        if (values.isEmpty()) return
        val start = index.coerceAtLeast(0)
        require(start <= length) { "insert index is out of bounds" }
        doc.transact {
            val normalized = attributes.normalizeOptionalTextAttributes()
            val baseAttributes = normalized.orEmpty()
            val entries = values.flatMap { value ->
                when (value) {
                    is String -> value.map { ItemContent.Text(it.toString(), baseAttributes, kind = kind) }
                    is Char -> listOf(ItemContent.Text(value.toString(), baseAttributes, kind = kind))
                    null -> error("text embeds must not be null")
                    else -> listOf(ItemContent.TextEmbed(doc.storeValue(value, parent = name), baseAttributes, kind = kind))
                }
            }
            insertAttributedTextEntries(start, entries, normalized)
        }
    }

    fun insertEmbed(
        index: Int,
        embed: Any?,
        attributes: Map<String, Any?> = emptyMap(),
    ) {
        require(embed != null) { "embed must not be null" }
        val start = index.coerceAtLeast(0)
        require(start <= length) { "insert index is out of bounds" }
        doc.transact {
            val normalized = normalizeTextAttributes(attributes)
            insertAttributedTextEntries(
                start,
                listOf(ItemContent.TextEmbed(doc.storeValue(embed, parent = name), normalized, kind = kind)),
                normalized,
            )
        }
    }

    fun insertEmbed(index: Int, embed: Any?, attributes: Map<String, Any?> = emptyMap(), origin: Any?) {
        require(embed != null) { "embed must not be null" }
        val start = index.coerceAtLeast(0)
        require(start <= length) { "insert index is out of bounds" }
        doc.transact(origin = origin) {
            val normalized = normalizeTextAttributes(attributes)
            insertAttributedTextEntries(
                start,
                listOf(ItemContent.TextEmbed(doc.storeValue(embed, parent = name), normalized, kind = kind)),
                normalized,
            )
        }
    }

    fun push(text: String, attributes: Map<String, Any?> = UnspecifiedTextAttributes) {
        insert(length, text, attributes)
    }

    fun push(values: List<Any?>, attributes: Map<String, Any?> = UnspecifiedTextAttributes) {
        insert(length, values, attributes)
    }

    fun push(vararg values: Any?) {
        push(values.toList())
    }

    fun unshift(text: String, attributes: Map<String, Any?> = UnspecifiedTextAttributes) {
        insert(0, text, attributes)
    }

    fun unshift(values: List<Any?>, attributes: Map<String, Any?> = UnspecifiedTextAttributes) {
        insert(0, values, attributes)
    }

    fun unshift(vararg values: Any?) {
        unshift(values.toList())
    }

    fun delete(index: Int, length: Int = 1) {
        doc.deleteVisible(name, index, length)
    }

    fun deleteText(index: Int, length: Int = 1, origin: Any? = null) {
        doc.deleteVisible(name, index, length, origin = origin)
    }

    fun clear() {
        delete(0, length)
    }

    fun setAttr(key: String, value: Any?): Any? = doc.setTypeAttribute(name, key, value)

    fun setAttribute(key: String, value: Any?): Any? = setAttr(key, value)

    fun setAttrs(values: Map<String, Any?>): YText {
        doc.transact {
            values.toSortedMap().forEach { (key, value) -> setAttr(key, value) }
        }
        return this
    }

    fun getAttr(key: String): Any? = doc.typeAttribute(name, key)

    fun getAttr(key: String, snapshot: Snapshot): Any? =
        doc.mapValueAtSnapshot(this, key, snapshot)?.let(doc::valueToAny)

    fun getAttribute(key: String): Any? = getAttr(key)

    fun getAttribute(key: String, snapshot: Snapshot): Any? = getAttr(key, snapshot)

    fun getAttrs(): Map<String, Any?> = doc.typeAttributes(name)

    fun getAttrs(snapshot: Snapshot): Map<String, Any?> =
        doc.mapAtSnapshot(this, snapshot).mapValues { (_, value) -> doc.valueToAny(value) }

    fun attrKeys(): Set<String> = getAttrs().keys

    fun attrValues(): Collection<Any?> = getAttrs().values

    fun attrEntries(): Set<Map.Entry<String, Any?>> = getAttrs().entries

    fun <T> mapAttrs(transform: (value: Any?, key: String) -> T): List<T> {
        return getAttrs().map { (key, value) -> transform(value, key) }
    }

    fun <T> mapAttrs(transform: (value: Any?, key: String, type: YText) -> T): List<T> {
        return getAttrs().map { (key, value) -> transform(value, key, this) }
    }

    fun forEachAttr(action: (value: Any?, key: String) -> Unit) {
        getAttrs().forEach { (key, value) -> action(value, key) }
    }

    fun forEachAttr(action: (value: Any?, key: String, type: YText) -> Unit) {
        getAttrs().forEach { (key, value) -> action(value, key, this) }
    }

    fun hasAttr(key: String): Boolean = doc.hasTypeAttribute(name, key)

    fun hasAttr(key: String, snapshot: Snapshot): Boolean =
        doc.mapValueAtSnapshot(this, key, snapshot) != null

    fun hasAttribute(key: String): Boolean = hasAttr(key)

    fun hasAttribute(key: String, snapshot: Snapshot): Boolean = hasAttr(key, snapshot)

    fun deleteAttr(key: String) {
        doc.deleteTypeAttribute(name, key)
    }

    fun deleteAttribute(key: String) {
        deleteAttr(key)
    }

    fun removeAttribute(key: String) {
        deleteAttr(key)
    }

    fun clearAttrs() {
        doc.transact {
            getAttrs().keys.forEach(::deleteAttr)
        }
    }

    fun format(index: Int, length: Int, attributes: Map<String, Any?>) {
        val start = index.coerceAtLeast(0)
        require(start <= this.length) { "format range is out of bounds" }
        if (length <= 0 || attributes.isEmpty()) return
        require(start + length <= this.length) { "format range is out of bounds" }
        doc.transact {
            formatNativeRange(start, length, normalizeTextFormatAttributes(attributes))
        }
    }

    fun formatText(index: Int, length: Int, attributes: Map<String, Any?>, origin: Any? = null) {
        val start = index.coerceAtLeast(0)
        require(start <= this.length) { "format range is out of bounds" }
        if (length <= 0 || attributes.isEmpty()) return
        require(start + length <= this.length) { "format range is out of bounds" }
        doc.transact(origin = origin) {
            formatNativeRange(start, length, normalizeTextFormatAttributes(attributes))
        }
    }

    fun applyDelta(
        delta: YTextDelta,
        origin: Any? = null,
        renderer: AbstractRenderer = activeRenderer,
    ) {
        doc.transact({ transaction ->
            var renderedIndex = 0
            delta.ops.forEach { op ->
                when {
                    op.insert != null -> {
                        val insert = op.insert
                        if (insert is String) {
                            if (insert.isEmpty()) return@forEach
                            val index = renderedSequenceIndexToVisibleIndex(this, renderedIndex, renderer)
                            insert(index, insert, op.attributes)
                            renderedIndex += insert.length
                        } else if (insert is List<*>) {
                            if (insert.textInsertLength() == 0) return@forEach
                            val index = renderedSequenceIndexToVisibleIndex(this, renderedIndex, renderer)
                            insert(index, insert, op.attributes)
                            renderedIndex += insert.textInsertLength()
                        } else {
                            val index = renderedSequenceIndexToVisibleIndex(this, renderedIndex, renderer)
                            insertEmbed(index, insert, op.attributes)
                            renderedIndex += 1
                        }
                    }
                    op.retain != null -> {
                        if (op.retain > 0 && op.attributes.isNotEmpty()) {
                            val startRendered = renderedIndex.coerceAtLeast(0)
                            val index = renderedSequenceIndexToVisibleIndex(this, startRendered, renderer)
                            val rawEnd = renderedSequenceIndexToVisibleIndex(
                                this,
                                startRendered + op.retain,
                                renderer,
                            )
                            format(index, rawEnd - index, op.attributes)
                        }
                        renderedIndex += op.retain
                    }
                    op.delete != null -> {
                        if (op.delete <= 0) return@forEach
                        val startRendered = renderedIndex.coerceAtLeast(0)
                        recordRendererAttributedDeletes(transaction, this, startRendered, op.delete, renderer)
                        val index = renderedSequenceIndexToVisibleIndex(this, startRendered, renderer)
                        val rawEnd = renderedSequenceIndexToVisibleIndex(
                            this,
                            startRendered + op.delete,
                            renderer,
                            clampToEnd = true,
                        )
                        delete(index, rawEnd - index)
                    }
                }
            }
        }, origin = origin)
    }

    fun applyDeltaDeep(delta: YTextDeepDelta, origin: Any? = null) {
        doc.transact(origin = origin) {
            clear()
            clearAttrs()
            setAttrs(delta.attrs.fromDeepDeltaValues(doc))
            applyDelta(delta.delta.fromDeepDeltaValues(doc))
        }
    }

    fun toDelta(): YTextDelta {
        val delta = YTextDelta()
        var pendingText = StringBuilder()
        var pendingAttributes: Map<String, Any?>? = null

        fun flush() {
            if (pendingText.isNotEmpty()) {
                delta.insertSegment(pendingText.toString(), pendingAttributes.orEmpty())
                pendingText = StringBuilder()
            }
        }

        doc.sequence(name).forEach { item ->
            if (item.deleted || item.content.kind != kind) return@forEach
            if (item.content is ItemContent.NativeTextFormat) {
                // Upstream Y.Text.toDelta packs pending text at every visible ContentFormat
                // marker, even when the marker leaves the effective attributes unchanged.
                flush()
                return@forEach
            }
            when (val content = item.content) {
                is ItemContent.Text -> {
                    val attributes = textAttributesToPublic(content.textAttributes())
                    if (pendingAttributes != null && pendingAttributes != attributes) flush()
                    pendingAttributes = attributes
                    pendingText.append(content.value)
                }
                is ItemContent.TextEmbed -> {
                    val attributes = textAttributesToPublic(content.textAttributes())
                    flush()
                    delta.insertEmbed(doc.valueToAny(content.value), attributes)
                    pendingAttributes = null
                }
                else -> Unit
            }
        }
        flush()
        return delta
    }

    fun toDeltaDeep(renderer: AbstractRenderer = activeRenderer): YTextDeepDelta =
        renderTextDeepDelta(this, DeepDeltaRenderOptions(renderer = renderer))

    fun toList(): List<Any?> = textItems().map { item ->
        when (val content = item.content) {
            is ItemContent.Text -> content.value
            is ItemContent.TextEmbed -> doc.valueToAny(content.value)
            else -> null
        }
    }

    fun toArray(): List<Any?> = toList()

    fun slice(start: Int = 0, end: Int = length): List<Any?> {
        val values = toList()
        val normalizedStart = normalizeTextSliceIndex(start, values.size)
        val normalizedEnd = normalizeTextSliceIndex(end, values.size)
        if (normalizedEnd <= normalizedStart) return emptyList()
        return values.subList(normalizedStart, normalizedEnd)
    }

    fun get(index: Int): Any? {
        if (index < 0) return null
        return toList().getOrNull(index)
    }

    fun <T> map(transform: (Any?) -> T): List<T> = toList().map(transform)

    fun <T> map(transform: (value: Any?, index: Int) -> T): List<T> =
        toList().mapIndexed { index, value -> transform(value, index) }

    fun <T> map(transform: (value: Any?, index: Int, type: YText) -> T): List<T> =
        toList().mapIndexed { index, value -> transform(value, index, this) }

    fun forEach(action: (Any?) -> Unit) {
        toList().forEach(action)
    }

    fun forEach(action: (value: Any?, index: Int) -> Unit) {
        toList().forEachIndexed { index, value -> action(value, index) }
    }

    fun forEach(action: (value: Any?, index: Int, type: YText) -> Unit) {
        toList().forEachIndexed { index, value -> action(value, index, this) }
    }

    fun forEachIndexed(action: (Int, Any?) -> Unit) {
        toList().forEachIndexed(action)
    }

    override fun iterator(): Iterator<Any?> = toList().iterator()

    override fun toString(): String = doc.visibleSequence(name)
        .filter { it.content.kind == kind }
        .joinToString(separator = "") { item ->
            when (val content = item.content) {
                is ItemContent.Text -> content.value
                is ItemContent.TextEmbed -> ""
                else -> ""
            }
        }

    override fun toJson(): String = toString()

    open fun clone(targetDoc: YDoc = doc): YText {
        return targetDoc.createText().also { cloned ->
            cloned.applyDelta(toDelta().cloneInto(targetDoc))
            cloned.setAttrs(getAttrs().mapValues { (_, value) -> value.cloneValueInto(targetDoc) })
        }
    }

    companion object {
        fun from(delta: YTextDelta, doc: YDoc = YDoc(), name: String = ""): YText {
            return doc.getText(name).also { it.applyDelta(delta) }
        }

        fun from(delta: YTextDeepDelta, doc: YDoc = YDoc(), name: String = ""): YText {
            return doc.getText(name).also { it.applyDeltaDeep(delta) }
        }
    }

    private fun textItems(): List<StoreItem> = doc.visibleSequence(name).filter { it.content.kind == kind }

    private fun insertTextEntries(
        index: Int,
        entries: List<ItemContent>,
        originOverride: Id? = null,
        rightOriginOverride: Id? = null,
    ) {
        val visible = textItems()
        require(index <= visible.size) { "insert index is out of bounds" }
        if (entries.isEmpty()) return
        val anchors = doc.insertionAnchors(name, kind, index)
        var origin = originOverride ?: anchors.first
        val rightOrigin = rightOriginOverride ?: anchors.second
        entries.forEach { content ->
            require(content.kind == kind) { "YText entries must use matching text content" }
            val item = StoreItem(
                id = doc.nextId(),
                origin = origin,
                rightOrigin = rightOrigin,
                parent = name,
                parentSub = null,
                content = content,
            )
            doc.integrateLocal(item)
            origin = item.id
        }
    }

    private fun insertAttributedTextEntries(
        index: Int,
        entries: List<ItemContent>,
        attributes: Map<String, YValue>?,
    ) {
        if (attributes == null) {
            insertTextEntries(index, entries)
            return
        }
        val visible = textItems()
        val ambient = visible.getOrNull(index)?.content?.textAttributes() ?: run {
            val active = linkedMapOf<String, YValue>()
            doc.sequence(name).forEach { item ->
                val marker = item.content as? ItemContent.NativeTextFormat
                if (!item.deleted && marker?.kind == kind) active.applyTextFormatAttributes(mapOf(marker.key to marker.value))
            }
            active
        }
        val keys = (ambient.keys + attributes.keys).toSortedSet()
        val desired = keys.associateWith { key -> attributes[key] ?: YValue.Null }
        val restore = keys.associateWith { key -> ambient[key] ?: YValue.Null }
        val anchors = doc.insertionAnchors(name, kind, index)
        val markerOrigin = insertNativeFormatMarkers(index, desired)
        insertTextEntries(
            index,
            entries.map { entry -> entry.withoutTextAttributes() },
            originOverride = markerOrigin ?: anchors.first,
            rightOriginOverride = anchors.second,
        )
        insertNativeFormatMarkers(index + entries.size, restore)
    }

    private fun formatNativeRange(start: Int, length: Int, attributes: Map<String, YValue>) {
        val visible = textItems()
        val desired = visible.map { item -> item.content.textAttributes().toMutableMap() }
        for (index in start until start + length) {
            desired[index].applyTextFormatAttributes(attributes)
        }
        canonicalizeNativeFormatting(desired)
    }

    private fun canonicalizeNativeFormatting(desired: List<Map<String, YValue>>) {
        val visible = textItems()
        require(desired.size == visible.size) { "text formatting state must cover every visible item" }
        val nativeMarkers = doc.sequence(name)
            .filter { item ->
                !item.deleted && item.content.kind == kind && item.content is ItemContent.NativeTextFormat
            }
        doc.deleteItemsByIds(nativeMarkers.map { item -> item.id })

        val active = linkedMapOf<String, YValue>()
        visible.forEachIndexed { index, item ->
            val base = item.content.baseTextAttributes()
            val target = desired[index]
            val effective = base.toMutableMap().also { attrs -> attrs.applyTextFormatAttributes(active) }
            val keys = (effective.keys + target.keys + active.keys).toSortedSet()
            val changes = linkedMapOf<String, YValue>()
            keys.forEach { key ->
                val current = effective[key]
                val next = target[key]
                if (current != next) {
                    val markerValue = next ?: YValue.Null
                    changes[key] = markerValue
                    active[key] = markerValue
                }
            }
            insertNativeFormatMarkers(index, changes)
        }
        val terminalChanges = active
            .filterValues { value -> value != YValue.Null }
            .keys
            .associateWith { YValue.Null }
        insertNativeFormatMarkers(visible.size, terminalChanges)
    }

    private fun insertNativeFormatMarkers(index: Int, attributes: Map<String, YValue>): Id? {
        if (attributes.isEmpty()) return null
        val anchors = doc.insertionAnchors(name, kind, index)
        var origin = anchors.first
        attributes.forEach { (key, value) ->
            val item = StoreItem(
                id = doc.nextId(),
                origin = origin,
                rightOrigin = anchors.second,
                parent = name,
                parentSub = null,
                content = ItemContent.NativeTextFormat(key, value, kind),
            )
            doc.integrateLocal(item)
            origin = item.id
        }
        return origin
    }
}

private fun List<*>.textInsertLength(): Int =
    sumOf { value ->
        when (value) {
            is String -> value.length
            else -> 1
        }
    }

private fun normalizeTextSliceIndex(index: Int, size: Int): Int {
    val normalized = if (index < 0) size + index else index
    return normalized.coerceIn(0, size)
}

private fun ItemContent.textAttributes(): Map<String, YValue> = when (this) {
    is ItemContent.Text -> attributes
    is ItemContent.TextEmbed -> attributes
    else -> error("content is not text-like")
}

private fun ItemContent.baseTextAttributes(): Map<String, YValue> = when (this) {
    is ItemContent.Text -> baseAttributes
    is ItemContent.TextEmbed -> baseAttributes
    else -> error("content is not text-like")
}

private fun MutableMap<String, YValue>.applyTextFormatAttributes(attributes: Map<String, YValue>) {
    attributes.forEach { (key, value) ->
        if (value == YValue.Null) remove(key) else this[key] = value
    }
}

private fun ItemContent.withTextAttributes(attributes: Map<String, YValue>): ItemContent = when (this) {
    is ItemContent.Text -> copy(attributes = attributes)
    is ItemContent.TextEmbed -> copy(attributes = attributes)
    else -> error("content is not text-like")
}

private fun ItemContent.withoutTextAttributes(): ItemContent = when (this) {
    is ItemContent.Text -> copy(attributes = emptyMap(), baseAttributes = emptyMap())
    is ItemContent.TextEmbed -> copy(attributes = emptyMap(), baseAttributes = emptyMap())
    else -> error("content is not text-like")
}

private fun normalizeTextFormatAttributes(attributes: Map<String, Any?>): Map<String, YValue> =
    attributes.mapValues { (_, value) -> value?.let(YValue::from) ?: YValue.Null }.toSortedMap()

private fun textFormatAfterAttributes(
    visible: List<StoreItem>,
    endIndex: Int,
    keys: Set<String>,
): Map<String, YValue> {
    val afterAttributes = visible.getOrNull(endIndex)?.content?.textAttributes().orEmpty()
    return keys.associateWith { key -> afterAttributes[key] ?: YValue.Null }.toSortedMap()
}

private fun textFormatBeforeAttributes(
    items: List<StoreItem>,
    keys: Set<String>,
): List<Map<String, YValue>> =
    items.map { item ->
        val attributes = item.content.textAttributes()
        keys.associateWith { key -> attributes[key] ?: YValue.Null }.toSortedMap()
    }

internal fun YTextDelta.cloneInto(targetDoc: YDoc): YTextDelta {
    val cloned = YTextDelta()
    ops.forEach { op ->
        val attrs = op.attributes.mapValues { (_, value) -> value.cloneValueInto(targetDoc) }
        when {
            op.insert is String -> cloned.insertSegment(op.insert, attrs)
            op.insert != null -> cloned.insertEmbed(op.insert.cloneValueInto(targetDoc), attrs)
            op.retain != null -> cloned.retain(op.retain, attrs)
            op.delete != null -> cloned.delete(op.delete)
        }
    }
    return cloned
}

open class YMap internal constructor(
    doc: YDoc,
    name: String,
    kind: RootKind = RootKind.Map,
) :
    AbstractYType(doc, name, kind),
    Iterable<Map.Entry<String, Any?>> {
    constructor() : this(YDoc(), "")

    constructor(values: Map<String, Any?>) : this() {
        setAttrs(values)
    }

    constructor(entries: Iterable<Pair<String, Any?>>) : this() {
        setAttrs(entries.toMap())
    }

    init {
        require(kind == RootKind.Map || kind == RootKind.XmlHook) {
            "YMap kind must be a map or XML hook"
        }
    }

    val size: Int get() = doc.visibleMap(name).size

    val attrSize: Int get() = size

    fun set(key: String, value: Any?): Any? {
        doc.transact {
            val content = ItemContent.MapEntry(doc.storeValue(value, parent = name))
            val item = StoreItem(
                id = doc.nextId(),
                origin = doc.currentMapItemId(name, key),
                rightOrigin = null,
                parent = name,
                parentSub = key,
                content = content,
            )
            doc.integrateLocal(item)
        }
        return get(key)
    }

    fun setAttr(key: String, value: Any?): Any? = set(key, value)

    fun setAttribute(key: String, value: Any?): Any? = setAttr(key, value)

    fun setAttrs(values: Map<String, Any?>): YMap {
        doc.transact {
            values.forEach { (key, value) -> set(key, value) }
        }
        return this
    }

    fun get(key: String): Any? = doc.visibleMapValue(name, key)?.let { doc.valueToAny(it) }

    fun get(key: String, snapshot: Snapshot): Any? =
        doc.mapValueAtSnapshot(this, key, snapshot)?.let(doc::valueToAny)

    fun getAttr(key: String): Any? = get(key)

    fun getAttr(key: String, snapshot: Snapshot): Any? = get(key, snapshot)

    fun getAttribute(key: String): Any? = getAttr(key)

    fun getAttribute(key: String, snapshot: Snapshot): Any? = getAttr(key, snapshot)

    fun has(key: String): Boolean = doc.visibleMapValue(name, key) != null

    fun has(key: String, snapshot: Snapshot): Boolean = doc.mapValueAtSnapshot(this, key, snapshot) != null

    fun hasAttr(key: String): Boolean = has(key)

    fun hasAttr(key: String, snapshot: Snapshot): Boolean = has(key, snapshot)

    fun hasAttribute(key: String): Boolean = hasAttr(key)

    fun hasAttribute(key: String, snapshot: Snapshot): Boolean = hasAttr(key, snapshot)

    fun delete(key: String) {
        doc.deleteMapKey(name, key)
    }

    fun deleteAttr(key: String) {
        delete(key)
    }

    fun deleteAttribute(key: String) {
        deleteAttr(key)
    }

    fun removeAttribute(key: String) {
        deleteAttr(key)
    }

    fun clear() {
        doc.transact {
            keys().forEach(::delete)
        }
    }

    fun clearAttrs() {
        clear()
    }

    fun applyDelta(delta: YMapDelta, origin: Any? = null) {
        doc.transact(origin = origin) {
            delta.ops.forEach { (key, op) ->
                when (op.action) {
                    YMapDeltaAction.Set -> set(key, op.value)
                    YMapDeltaAction.Delete -> delete(key)
                }
            }
        }
    }

    fun applyDeltaDeep(delta: YMapDeepDelta, origin: Any? = null) {
        doc.transact(origin = origin) {
            clear()
            setAttrs(delta.attrs.fromDeepDeltaValues(doc))
        }
    }

    fun toDelta(): YMapDelta {
        val delta = YMapDelta()
        toMap().forEach { (key, value) -> delta.setAttr(key, value) }
        return delta
    }

    fun toDeltaDeep(renderer: AbstractRenderer = activeRenderer): YMapDeepDelta =
        renderMapDeepDelta(this, DeepDeltaRenderOptions(renderer = renderer))

    fun keys(): Set<String> = toMap().keys

    fun attrKeys(): Set<String> = keys()

    fun values(): Collection<Any?> = toMap().values

    fun attrValues(): Collection<Any?> = values()

    fun entries(): Set<Map.Entry<String, Any?>> = toMap().entries

    fun attrEntries(): Set<Map.Entry<String, Any?>> = entries()

    fun forEach(action: (value: Any?, key: String) -> Unit) {
        toMap().forEach { (key, value) -> action(value, key) }
    }

    fun forEach(action: (value: Any?, key: String, type: YMap) -> Unit) {
        toMap().forEach { (key, value) -> action(value, key, this) }
    }

    fun <T> mapAttrs(transform: (value: Any?, key: String) -> T): List<T> {
        return toMap().map { (key, value) -> transform(value, key) }
    }

    fun <T> mapAttrs(transform: (value: Any?, key: String, type: YMap) -> T): List<T> {
        return toMap().map { (key, value) -> transform(value, key, this) }
    }

    fun forEachAttr(action: (value: Any?, key: String) -> Unit) {
        toMap().forEach { (key, value) -> action(value, key) }
    }

    fun forEachAttr(action: (value: Any?, key: String, type: YMap) -> Unit) {
        toMap().forEach { (key, value) -> action(value, key, this) }
    }

    override fun iterator(): Iterator<Map.Entry<String, Any?>> = entries().iterator()

    fun toMap(): Map<String, Any?> = doc.visibleMap(name).mapValues { (_, value) -> doc.valueToAny(value) }

    fun toMap(snapshot: Snapshot): Map<String, Any?> =
        doc.mapAtSnapshot(this, snapshot).mapValues { (_, value) -> doc.valueToAny(value) }

    fun getAttrs(): Map<String, Any?> = toMap()

    fun getAttrs(snapshot: Snapshot): Map<String, Any?> = toMap(snapshot)

    override fun toJson(): Map<String, Any?> = doc.visibleMap(name)
        .mapValues { (_, value) -> doc.valueToJson(value) }
        .inJavaScriptObjectKeyOrder()

    override fun toJSON(): Map<String, Any?> = toMap()
        .mapValues { (_, value) -> toYTypeJsonValue(value) }
        .inJavaScriptObjectKeyOrder()

    open fun clone(targetDoc: YDoc = doc): YMap {
        return targetDoc.createMap().also { cloned ->
            cloned.setAttrs(toMap().mapValues { (_, value) -> value.cloneValueInto(targetDoc) })
        }
    }

    companion object {
        fun from(delta: YMapDelta, doc: YDoc = YDoc(), name: String = ""): YMap {
            return doc.getMap(name).also { it.applyDelta(delta) }
        }

        fun from(delta: YMapDeepDelta, doc: YDoc = YDoc(), name: String = ""): YMap {
            return doc.getMap(name).also { it.applyDeltaDeep(delta) }
        }
    }
}

/**
 * JavaScript objects enumerate array-index property names before other strings. Y.Map itself
 * iterates its backing Map in insertion order, but Y.Map#toJSON returns a plain object.
 */
private fun <T> Map<String, T>.inJavaScriptObjectKeyOrder(): Map<String, T> {
    val result = linkedMapOf<String, T>()
    entries.asSequence()
        .mapNotNull { entry -> entry.key.toJavaScriptArrayIndexOrNull()?.let { index -> index to entry } }
        .sortedBy { (index, _) -> index }
        .forEach { (_, entry) -> result[entry.key] = entry.value }
    entries.forEach { entry ->
        if (entry.key.toJavaScriptArrayIndexOrNull() == null) result[entry.key] = entry.value
    }
    return result
}

private fun String.toJavaScriptArrayIndexOrNull(): Long? {
    if (isEmpty() || (length > 1 && first() == '0') || any { char -> char !in '0'..'9' }) return null
    val value = toLongOrNull() ?: return null
    return value.takeIf { index -> index <= 4_294_967_294L && index.toString() == this }
}

internal fun Any?.cloneValueInto(targetDoc: YDoc): Any? = when (this) {
    is YArray -> clone(targetDoc)
    is YMap -> clone(targetDoc)
    is YText -> clone(targetDoc)
    is YXmlFragment -> clone(targetDoc)
    is YXmlElementType -> clone(targetDoc)
    is YXmlTextType -> clone(targetDoc)
    is List<*> -> map { it.cloneValueInto(targetDoc) }
    is Array<*> -> map { it.cloneValueInto(targetDoc) }
    is Map<*, *> -> entries.associate { (key, value) ->
        require(key is String) { "YValue map keys must be strings" }
        key to value.cloneValueInto(targetDoc)
    }
    is ByteArray -> copyOf()
    else -> this
}

internal fun yTypeJsonObject(
    name: String? = null,
    children: List<Any?> = emptyList(),
    attrs: Map<String, Any?> = emptyMap(),
): Map<String, Any?> = linkedMapOf<String, Any?>().also { json ->
    if (name != null) json["name"] = name
    if (children.isNotEmpty()) json["children"] = children
    if (attrs.isNotEmpty()) json["attrs"] = attrs.toSortedMap()
}

private fun Map<String, Any?>.normalizeOptionalTextAttributes(): Map<String, YValue>? =
    if (this === UnspecifiedTextAttributes) null else normalizeTextAttributes(this)

internal fun toYTypeJsonValue(value: Any?): Any? = when (value) {
    is AbstractYType -> value.toJSON()
    is YXmlNode -> value.toJSON()
    is List<*> -> value.map(::toYTypeJsonValue)
    is Array<*> -> value.map(::toYTypeJsonValue)
    is Map<*, *> -> value.entries.associate { (key, nested) ->
        require(key is String) { "YValue map keys must be strings" }
        key to toYTypeJsonValue(nested)
    }.toSortedMap()
    is ByteArray -> value.copyOf()
    else -> value
}
