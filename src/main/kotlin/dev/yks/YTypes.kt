package dev.yks

public enum class RootKind {
    Array,
    Map,
    Text,
    XmlFragment,
    XmlElement,
    XmlHook,
    XmlText,
}

public const val YArrayRefID: Int = 0
public const val YMapRefID: Int = 1
public const val YTextRefID: Int = 2
public const val YXmlElementRefID: Int = 3
public const val YXmlFragmentRefID: Int = 4
public const val YXmlHookRefID: Int = 5
public const val YXmlTextRefID: Int = 6

public val `$ytypeAny`: (Any?) -> Boolean = { value -> value is AbstractYType }

public fun `$ytype`(): (Any?) -> Boolean = `$ytypeAny`

public val `$ydoc`: (Any?) -> Boolean = { value -> value is YDoc }

public typealias Attribution = Map<String, Any?>

/**
 * Distinguishes an omitted Y.Text attribute argument from an explicitly supplied empty map.
 *
 * Upstream Yjs inherits the formatting at the insertion point when the argument is omitted,
 * while `{}` explicitly inserts unformatted content. Keeping a private sentinel preserves the
 * existing non-null public parameter type and its default-argument ABI.
 */
private object UnspecifiedTextAttributes : Map<String, Any?> by emptyMap()

public fun warnPrematureAccess() {
    System.err.println("Invalid access: Add Yjs type to a document before reading data.")
}

internal sealed interface YTypeBinding {
    data object Detached : YTypeBinding

    data class Reserved(val doc: YDoc, val name: String) : YTypeBinding

    data class Root(val doc: YDoc, val name: String) : YTypeBinding

    data class Nested(val doc: YDoc, val name: String, val ownerId: Id?) : YTypeBinding
}

internal data class YTypeMutableState(
    val doc: YDoc,
    val name: String,
    val binding: YTypeBinding,
    val preliminaryList: List<Any?>,
    val preliminaryMap: Map<String, Any?>,
    val preliminaryOperations: List<() -> Unit>,
    val preliminaryOperationValues: List<Any?>,
)

public fun createAttributionFromAttributionItems(attrs: List<ContentAttribute>?, deleted: Boolean): Attribution? {
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

public sealed class AbstractYType protected constructor(
    doc: YDoc,
    name: String,
    internal val kind: RootKind,
) {
    public var doc: YDoc = doc
        private set
    public var name: String = name
        internal set
    internal var binding: YTypeBinding = YTypeBinding.Root(doc, name)
        private set
    private var preliminaryListValue: MutableList<Any?>? = null
    private var preliminaryMapValue: MutableMap<String, Any?>? = null
    private var preliminaryOperationsValue: MutableList<() -> Unit>? = null
    private var preliminaryOperationValuesValue: MutableList<Any?>? = null
    internal val preliminaryList: MutableList<Any?>
        get() = preliminaryListValue ?: mutableListOf<Any?>().also { preliminaryListValue = it }
    internal val preliminaryMap: MutableMap<String, Any?>
        get() = preliminaryMapValue ?: linkedMapOf<String, Any?>().also { preliminaryMapValue = it }
    internal val preliminaryOperations: MutableList<() -> Unit>
        get() = preliminaryOperationsValue
            ?: mutableListOf<() -> Unit>().also { preliminaryOperationsValue = it }
    internal val preliminaryOperationValues: MutableList<Any?>
        get() = preliminaryOperationValuesValue
            ?: mutableListOf<Any?>().also { preliminaryOperationValuesValue = it }

    internal val isPreliminary: Boolean
        get() = binding is YTypeBinding.Detached || binding is YTypeBinding.Reserved

    internal fun captureMutableState(): YTypeMutableState = YTypeMutableState(
        doc = doc,
        name = name,
        binding = binding,
        preliminaryList = preliminaryList.toList(),
        preliminaryMap = preliminaryMap.toMap(),
        preliminaryOperations = preliminaryOperations.toList(),
        preliminaryOperationValues = preliminaryOperationValues.toList(),
    )

    internal fun restoreMutableState(state: YTypeMutableState) {
        if (doc !== state.doc) doc.removeTypeSnapshotInterest(this)
        doc = state.doc
        name = state.name
        binding = state.binding
        preliminaryList.clear()
        preliminaryList.addAll(state.preliminaryList)
        preliminaryMap.clear()
        preliminaryMap.putAll(state.preliminaryMap)
        preliminaryOperations.clear()
        preliminaryOperations.addAll(state.preliminaryOperations)
        preliminaryOperationValues.clear()
        preliminaryOperationValues.addAll(state.preliminaryOperationValues)
        doc.refreshTypeSnapshotInterest(this)
    }

    internal fun markDetached() {
        check(binding is YTypeBinding.Root) { "only a fresh shared type can become detached" }
        binding = YTypeBinding.Detached
    }

    internal fun reserve(target: YDoc, reservedName: String) {
        when (val current = binding) {
            YTypeBinding.Detached -> Unit
            is YTypeBinding.Reserved -> {
                require(current.doc === target) { "shared type is reserved for another document" }
                require(current.name == reservedName) { "shared type already has a different reserved name" }
                return
            }
            is YTypeBinding.Root -> error("root shared types cannot be inserted as nested content")
            is YTypeBinding.Nested -> error("shared type is already integrated")
        }
        if (doc !== target) doc.removeTypeSnapshotInterest(this)
        doc = target
        name = reservedName
        binding = YTypeBinding.Reserved(target, reservedName)
        doc.refreshTypeSnapshotInterest(this)
    }

    internal fun integrateReserved(target: YDoc, ownerId: Id?) {
        val current = binding as? YTypeBinding.Reserved
            ?: error("shared type is not reserved for integration")
        require(current.doc === target) { "shared type is reserved for another document" }
        binding = YTypeBinding.Nested(target, current.name, ownerId)
    }

    internal fun markDecodedNested(target: YDoc, nestedName: String, ownerId: Id?) {
        if (doc !== target) doc.removeTypeSnapshotInterest(this)
        doc = target
        name = nestedName
        binding = YTypeBinding.Nested(target, nestedName, ownerId)
        doc.refreshTypeSnapshotInterest(this)
    }

    internal fun warnIfPreliminary(): Boolean {
        if (!isPreliminary) return false
        warnPrematureAccess()
        return true
    }

    internal fun queuePreliminaryOperation(values: Any? = null, operation: () -> Unit) {
        check(isPreliminary) { "preliminary operations can only be queued before integration" }
        preliminaryOperations.add(operation)
        if (values != null) preliminaryOperationValues.add(values)
    }

    internal fun preliminaryGraphValues(): List<Any?> = when (this) {
        is YUnopenedRoot -> emptyList()
        is YArray -> preliminaryList + preliminaryMap.values
        is YMap -> preliminaryMap.values.toList()
        is YText -> preliminaryOperationValues.toList()
        is YXmlElementType -> preliminaryList + preliminaryMap.values
        is YXmlFragment -> preliminaryList + preliminaryMap.values
    }

    internal fun replayPreliminaryContent() {
        check(!isPreliminary) { "shared type must be integrated before replay" }
        when (this) {
            is YUnopenedRoot -> error("an unopened root cannot replay preliminary content")
            is YArray -> {
                val values = preliminaryList.toList()
                val attrs = preliminaryMap.toMap()
                preliminaryList.clear()
                preliminaryMap.clear()
                if (values.isNotEmpty()) push(values)
                attrs.forEach { (key, value) -> setAttr(key, value) }
            }
            is YMap -> {
                val entries = preliminaryMap.toList()
                preliminaryMap.clear()
                entries.forEach { (key, value) -> set(key, value) }
            }
            is YText -> {
                val operations = preliminaryOperations.toList()
                preliminaryOperations.clear()
                preliminaryOperationValues.clear()
                try {
                    operations.forEach { operation -> operation() }
                } catch (error: Exception) {
                    // Upstream Y.Text logs a failed pending operation during _integrate, but it
                    // keeps the ContentType owner integrated and clears the pending queue.
                    error.printStackTrace(System.err)
                }
            }
            is YXmlElementType -> {
                val children = preliminaryList.toList()
                val attrs = preliminaryMap.toMap()
                preliminaryList.clear()
                preliminaryMap.clear()
                children.forEach { child ->
                    when (child) {
                        is AbstractYType -> insertType(length, child)
                        is YXmlNode -> insert(length, listOf(child))
                        else -> error("unsupported preliminary XML child: ${child?.let { it::class.qualifiedName }}")
                    }
                }
                attrs.forEach { (key, value) -> setAttr(key, value) }
            }
            is YXmlFragment -> {
                val children = preliminaryList.toList()
                val attrs = preliminaryMap.toMap()
                preliminaryList.clear()
                preliminaryMap.clear()
                children.forEach { child ->
                    when (child) {
                        is AbstractYType -> insertType(length, child)
                        is YXmlNode -> insert(length, listOf(child))
                        else -> error("unsupported preliminary XML child: ${child?.let { it::class.qualifiedName }}")
                    }
                }
                attrs.forEach { (key, value) -> setAttr(key, value) }
            }
        }
    }

    public companion object {
        public fun from(delta: List<YArrayDeltaOp>, doc: YDoc = YDoc(), name: String = ""): YArray =
            YArray.from(delta, doc, name)

        public fun from(delta: YArrayDeepDelta, doc: YDoc = YDoc(), name: String = ""): YArray =
            YArray.from(delta, doc, name)

        public fun from(delta: YMapDelta, doc: YDoc = YDoc(), name: String = ""): YMap =
            YMap.from(delta, doc, name)

        public fun from(delta: YMapDeepDelta, doc: YDoc = YDoc(), name: String = ""): YMap =
            YMap.from(delta, doc, name)

        public fun from(delta: YTextDelta, doc: YDoc = YDoc(), name: String = ""): YText =
            YText.from(delta, doc, name)

        public fun from(delta: YTextDeepDelta, doc: YDoc = YDoc(), name: String = ""): YText =
            YText.from(delta, doc, name)

        public fun from(delta: YXmlFragmentDeepDelta, doc: YDoc = YDoc(), name: String = ""): YXmlFragment =
            YXmlFragment.from(delta, doc, name)
    }

    private var observers: MutableList<(YEvent) -> Unit>? = null
    private var transactionObservers: MutableList<(YEvent, YTransactionEvent?) -> Unit>? = null
    private var deepObservers: MutableList<(YEvent) -> Unit>? = null
    private var deepTransactionObservers: MutableList<(YEvent, YTransactionEvent?) -> Unit>? = null
    private var deepEventListObservers: MutableList<(List<YEvent>, YTransactionEvent?) -> Unit>? = null
    private var eventListeners: MutableMap<String, MutableList<(YTypeEvent) -> Unit>>? = null
    private var deltaCache: YDeepDelta? = null
    private var rendererChangeSubscription: Subscription? = null
    internal var activeRenderer: AbstractRenderer = baseRenderer
        private set
    public var isDestroyed: Boolean = false
        private set

    public fun observe(listener: (YEvent) -> Unit): Subscription {
        (observers ?: mutableListOf<(YEvent) -> Unit>().also { observers = it }).add(listener)
        doc.refreshTypeSnapshotInterest(this)
        return Subscription {
            observers?.remove(listener)
            doc.refreshTypeSnapshotInterest(this)
        }
    }

    public fun observe(listener: (YEvent, YTransactionEvent?) -> Unit): Subscription {
        (
            transactionObservers
                ?: mutableListOf<(YEvent, YTransactionEvent?) -> Unit>().also { transactionObservers = it }
        ).add(listener)
        doc.refreshTypeSnapshotInterest(this)
        return Subscription {
            transactionObservers?.remove(listener)
            doc.refreshTypeSnapshotInterest(this)
        }
    }

    public fun observeDeep(listener: (YEvent) -> Unit): Subscription {
        (deepObservers ?: mutableListOf<(YEvent) -> Unit>().also { deepObservers = it }).add(listener)
        doc.refreshTypeSnapshotInterest(this)
        return Subscription {
            deepObservers?.remove(listener)
            doc.refreshTypeSnapshotInterest(this)
        }
    }

    public fun observeDeep(listener: (YEvent, YTransactionEvent?) -> Unit): Subscription {
        (
            deepTransactionObservers
                ?: mutableListOf<(YEvent, YTransactionEvent?) -> Unit>().also { deepTransactionObservers = it }
        ).add(listener)
        doc.refreshTypeSnapshotInterest(this)
        return Subscription {
            deepTransactionObservers?.remove(listener)
            doc.refreshTypeSnapshotInterest(this)
        }
    }

    /**
     * Upstream-style deep observation that receives each changed descendant as a separate event.
     *
     * The existing [observeDeep] overloads intentionally keep their aggregate-event ABI. This
     * opt-in form exposes the Yjs callback shape without changing existing Kotlin callers.
     */
    public fun observeDeepEvents(listener: (List<YEvent>, YTransactionEvent?) -> Unit): Subscription {
        (
            deepEventListObservers
                ?: mutableListOf<(List<YEvent>, YTransactionEvent?) -> Unit>()
                    .also { deepEventListObservers = it }
        ).add(listener)
        doc.refreshTypeSnapshotInterest(this)
        return Subscription {
            deepEventListObservers?.remove(listener)
            doc.refreshTypeSnapshotInterest(this)
        }
    }

    public fun unobserve(listener: (YEvent) -> Unit) {
        observers?.remove(listener)
        doc.refreshTypeSnapshotInterest(this)
    }

    public fun unobserve(listener: (YEvent, YTransactionEvent?) -> Unit) {
        transactionObservers?.remove(listener)
        doc.refreshTypeSnapshotInterest(this)
    }

    public fun unobserveDeep(listener: (YEvent) -> Unit) {
        deepObservers?.remove(listener)
        doc.refreshTypeSnapshotInterest(this)
    }

    public fun unobserveDeep(listener: (YEvent, YTransactionEvent?) -> Unit) {
        deepTransactionObservers?.remove(listener)
        doc.refreshTypeSnapshotInterest(this)
    }

    public fun unobserveDeepEvents(listener: (List<YEvent>, YTransactionEvent?) -> Unit) {
        deepEventListObservers?.remove(listener)
        doc.refreshTypeSnapshotInterest(this)
    }

    public fun on(eventName: String, listener: (YTypeEvent) -> Unit): Subscription {
        val listeners = eventListeners.getOrCreateMap().getOrPut(eventName) { mutableListOf() }
        listeners.add(listener)
        doc.refreshTypeSnapshotInterest(this)
        return Subscription { off(eventName, listener) }
    }

    public fun once(eventName: String, listener: (YTypeEvent) -> Unit): Subscription {
        lateinit var subscription: Subscription
        val onceListener: (YTypeEvent) -> Unit = { event ->
            subscription.close()
            listener(event)
        }
        subscription = on(eventName, onceListener)
        return subscription
    }

    public fun off(eventName: String, listener: (YTypeEvent) -> Unit) {
        val listeners = eventListeners?.get(eventName) ?: return
        listeners.remove(listener)
        if (listeners.isEmpty()) {
            eventListeners?.remove(eventName)
        }
        doc.refreshTypeSnapshotInterest(this)
    }

    public fun emit(eventName: String, event: YTypeEvent = YTypeEvent(name = eventName, target = this)) {
        emitTypeEvent(if (event.name == eventName && event.target === this) event else event.copy(name = eventName, target = this))
    }

    public fun emit(event: YTypeEvent) {
        emitTypeEvent(if (event.target === this) event else event.copy(target = this))
    }

    public fun destroy() {
        if (isDestroyed) return
        isDestroyed = true
        rendererChangeSubscription?.close()
        rendererChangeSubscription = null
        activeRenderer = baseRenderer
        clearCache()
        emitTypeEvent(YTypeEvent(name = "destroy", target = this))
        eventListeners?.clear()
        doc.removeTypeSnapshotInterest(this)
    }

    internal fun emit(event: YEvent) {
        clearCache()
        val callbacks = observers.orEmpty().toList().map { listener -> { listener(event) } }.toMutableList()
        callbacks.addAll(transactionObservers.orEmpty().toList().map { listener -> { listener(event, event.transaction) } })
        callbacks.addAll(deltaEventCallbacks(event))
        callAllYksCallbacks(callbacks)
    }

    internal val hasDeepObservers: Boolean
        get() = !deepObservers.isNullOrEmpty() ||
            !deepTransactionObservers.isNullOrEmpty() ||
            !deepEventListObservers.isNullOrEmpty()

    internal val hasDirectEventInterest: Boolean
        get() = !observers.isNullOrEmpty() ||
            !transactionObservers.isNullOrEmpty() ||
            hasDeltaListeners ||
            hasDeltaCache

    internal val hasDeepEventInterest: Boolean
        get() = hasDeepObservers ||
            hasDeltaListeners ||
            hasDeltaCache

    internal val needsTransactionSnapshot: Boolean
        get() = hasDirectEventInterest || hasDeepObservers

    internal val hasDeltaListeners: Boolean get() = eventListeners?.get("delta")?.isNotEmpty() == true

    internal val hasDeltaCache: Boolean get() = deltaCache != null

    internal fun emitDeep(event: YEvent) {
        val callbacks = deepObservers.orEmpty().toList().map { listener -> { listener(event) } }.toMutableList()
        callbacks.addAll(deepTransactionObservers.orEmpty().toList().map { listener -> { listener(event, event.transaction) } })
        val upstreamEvents = event.deepEvents
            .ifEmpty { listOf(event) }
            .sortedBy { childEvent -> childEvent.path.size }
        callbacks.addAll(
            deepEventListObservers.orEmpty().toList().map { listener ->
                { listener(upstreamEvents, event.transaction) }
            },
        )
        callAllYksCallbacks(callbacks)
    }

    internal fun emitDelta(event: YEvent) {
        callAllYksCallbacks(deltaEventCallbacks(event))
    }

    private fun deltaEventCallbacks(event: YEvent): List<() -> Unit> {
        return eventListeners?.get("delta").orEmpty().toList().map { listener ->
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
        callAllYksCallbacks(eventListeners?.get(event.name).orEmpty().toList()) { listener -> listener(event) }
    }

    private fun MutableMap<String, MutableList<(YTypeEvent) -> Unit>>?.getOrCreateMap():
        MutableMap<String, MutableList<(YTypeEvent) -> Unit>> =
        this ?: linkedMapOf<String, MutableList<(YTypeEvent) -> Unit>>().also { eventListeners = it }

    public fun getPathTo(child: AbstractYType, renderer: AbstractRenderer = baseRenderer): List<Any> {
        require(child.doc === doc) { "child must belong to the same document" }
        return doc.pathBetween(name, child.name, renderer) ?: error("target type is not a visible descendant")
    }

    public abstract fun toJson(): Any?

    public open fun toJSON(): Any? = toJson()

    public val delta: YDeepDelta
        get() = deltaCache ?: renderDeepDelta().also {
            deltaCache = it
            doc.refreshTypeSnapshotInterest(this)
        }

    public fun clearCache() {
        deltaCache = null
        doc.refreshTypeSnapshotInterest(this)
    }

    internal open fun adjustVisibleLength(changedKind: RootKind, delta: Long) = Unit

    public fun useRenderer(renderer: AbstractRenderer): AbstractYType {
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

    public val parent: AbstractYType? get() = doc.parentOf(this)

    public open val typeRef: Int get() = kind.toTypeRefId()

    public open val legacyTypeRef: Int get() = typeRef
}

/**
 * Upstream keeps remotely discovered roots as an undecided AbstractType until a concrete
 * getter is called. This placeholder intentionally exposes no guessed RootKind/type ref.
 */
public class YUnopenedRoot internal constructor(doc: YDoc, name: String) :
    AbstractYType(doc, name, RootKind.Array) {
    override val typeRef: Int
        get() = error("unopened root '$name' has no concrete type ref")

    override val legacyTypeRef: Int
        get() = typeRef

    override fun toJson(): Any? = null

    override fun toJSON(): Any? = null

    override fun toString(): String = "YUnopenedRoot(name=$name)"
}

public data class YTypeEvent(
    val name: String,
    val target: AbstractYType,
    val delta: Any? = null,
    val origin: Any? = null,
    val transaction: YTransactionEvent? = null,
    val yEvent: YEvent? = null,
)

public fun RootKind.toTypeRefId(): Int = when (this) {
    RootKind.Array -> YArrayRefID
    RootKind.Map -> YMapRefID
    RootKind.Text -> YTextRefID
    RootKind.XmlFragment -> YXmlFragmentRefID
    RootKind.XmlElement -> YXmlElementRefID
    RootKind.XmlHook -> YXmlHookRefID
    RootKind.XmlText -> YXmlTextRefID
}

public fun rootKindFromTypeRefId(typeRef: Int): RootKind = when (typeRef) {
    YArrayRefID -> RootKind.Array
    YMapRefID -> RootKind.Map
    YTextRefID -> RootKind.Text
    YXmlElementRefID -> RootKind.XmlElement
    YXmlFragmentRefID -> RootKind.XmlFragment
    YXmlHookRefID -> RootKind.XmlHook
    YXmlTextRefID -> RootKind.XmlText
    else -> error("unknown type ref: $typeRef")
}

public fun typeRefId(type: AbstractYType): Int = type.typeRef

/** Matches upstream AbstractType._copy(): preserve the concrete type, not its content. */
internal fun AbstractYType.emptyContentTypeCopy(): AbstractYType = when (this) {
    is YUnopenedRoot -> error("an unopened root has no concrete type to copy")
    is YXmlTextType -> YXmlTextType()
    is YXmlHook -> YXmlHook(hookName)
    is YXmlElementType -> YXmlElementType(nodeName)
    is YXmlFragment -> YXmlFragment()
    is YArray -> YArray()
    is YMap -> YMap()
    is YText -> YText()
}

public class YArray internal constructor(doc: YDoc, name: String) : AbstractYType(doc, name, RootKind.Array), Iterable<Any?> {
    public constructor() : this(YDoc(), "") {
        markDetached()
    }

    public constructor(values: Iterable<Any?>) : this() {
        push(values.toList())
    }

    public constructor(vararg values: Any?) : this(values.toList())

    private var maintainedLengthInitialized = false
    private var maintainedLength = 0
    private var cachedAccessUnchecked = false
    private var firstVisibleItemCached = false
    private var firstVisibleItem: StoreItem? = null
    private var firstVisibleScalarCached = false
    private var firstVisibleScalar: Any? = null

    public val size: Int
        get() {
            if (maintainedLengthInitialized && cachedAccessUnchecked) return maintainedLength
            if (warnIfPreliminary()) return 0
            doc.ensureThreadAccess()
            if (!maintainedLengthInitialized) {
                maintainedLength = doc.visibleLength(name, RootKind.Array).toNonNegativeInt("array length")
                cachedAccessUnchecked = doc.threadAccessPolicy == YThreadAccessPolicy.UNCHECKED
                maintainedLengthInitialized = true
                doc.registerMaintainedLength(name)
            }
            return maintainedLength
        }

    public val length: Int get() = size

    internal override fun adjustVisibleLength(changedKind: RootKind, delta: Long) {
        if (changedKind != RootKind.Array) return
        firstVisibleItemCached = false
        firstVisibleItem = null
        firstVisibleScalarCached = false
        firstVisibleScalar = null
        if (!maintainedLengthInitialized) return
        maintainedLength = try {
            Math.addExact(maintainedLength.toLong(), delta)
        } catch (_: ArithmeticException) {
            error("maintained array length overflow")
        }.toNonNegativeInt("maintained array length")
    }

    public val attrSize: Int get() = getAttrs().size

    public fun insert(index: Int, values: List<Any?>) {
        if (isPreliminary) {
            if (values.isEmpty()) return
            require(values.none { value -> value === this }) { "shared type cannot contain itself" }
            preliminaryList.addAll(index.coerceIn(0, preliminaryList.size), values)
            return
        }
        val start = index.coerceAtLeast(0)
        if (values.isEmpty()) {
            doc.transact {
                require(start <= size) { "insert index is out of bounds" }
            }
            return
        }
        require(start <= size) { "insert index is out of bounds" }
        doc.preflightNestedValue(values)
        doc.transact {
            val anchors = doc.insertionAnchors(name, RootKind.Array, start)
            var origin = anchors.first
            val rightOrigin = anchors.second
            val contents = buildList {
                val packed = mutableListOf<YValue>()
                fun flushPacked() {
                    when (packed.size) {
                        0 -> Unit
                        1 -> add(ItemContent.Value(packed.single()))
                        else -> add(ItemContent.ArrayValues(packed.toList()))
                    }
                    packed.clear()
                }
                values.forEach { raw ->
                    val stored = doc.storePreflightedScalar(raw, parent = name)
                    when {
                        raw is AbstractYType -> {
                            flushPacked()
                            add(ItemContent.XmlType(stored as YValue.TypeRef, raw.xmlNodeNameOrEmpty(), RootKind.Array))
                        }
                        stored is YValue.BinaryValue || stored is YValue.SubdocRef || stored is YValue.TypeRef -> {
                            // Yjs emits ContentBinary, ContentDoc, and ContentType as individual
                            // Items and packs all remaining generic values into one ContentAny.
                            flushPacked()
                            add(ItemContent.Value(stored))
                        }
                        else -> packed.add(stored)
                    }
                }
                flushPacked()
            }
            contents.forEach { content ->
                val item = StoreItem(
                    id = doc.nextId(),
                    origin = origin,
                    rightOrigin = rightOrigin,
                    parent = name,
                    parentSub = null,
                    content = content,
                )
                doc.integrateLocal(item)
                origin = item.lastId
            }
        }
    }

    public fun insert(index: Int, vararg values: Any?) {
        insert(index, values.toList())
    }

    public fun push(values: List<Any?>) {
        require(values.none { it === this }) { "A shared type cannot contain itself" }
        if (isPreliminary) {
            preliminaryList.addAll(values)
            return
        }
        insert(size, values)
    }

    public fun push(vararg values: Any?) {
        push(values.toList())
    }

    public fun unshift(values: List<Any?>) {
        insert(0, values)
    }

    public fun unshift(vararg values: Any?) {
        unshift(values.toList())
    }

    public fun delete(index: Int, length: Int = 1) {
        if (isPreliminary) {
            if (length <= 0 || preliminaryList.isEmpty()) return
            val start = index.coerceIn(0, preliminaryList.size)
            val end = start + minOf(length, preliminaryList.size - start)
            preliminaryList.subList(start, end).clear()
            return
        }
        doc.deleteVisible(name, index, length)
    }

    public fun clear() {
        delete(0, size)
    }

    public fun setAttr(key: String, value: Any?): Any? {
        if (isPreliminary) {
            preliminaryMap[key] = value
            return value
        }
        return doc.setTypeAttribute(name, key, value)
    }

    public fun setAttribute(key: String, value: Any?): Any? = setAttr(key, value)

    public fun setAttrs(values: Map<String, Any?>): YArray {
        if (isPreliminary) {
            values.forEach { (key, value) -> preliminaryMap[key] = value }
            return this
        }
        doc.preflightNestedValue(values.values.toList())
        doc.transact {
            values.toSortedMap().forEach { (key, value) -> setAttr(key, value) }
        }
        return this
    }

    public fun getAttr(key: String): Any? {
        if (warnIfPreliminary()) return null
        return doc.typeAttribute(name, key)
    }

    public fun getAttr(key: String, snapshot: Snapshot): Any? =
        doc.mapValueAtSnapshot(this, key, snapshot)?.let(doc::valueToAny)

    public fun getAttribute(key: String): Any? = getAttr(key)

    public fun getAttribute(key: String, snapshot: Snapshot): Any? = getAttr(key, snapshot)

    public fun getAttrs(): Map<String, Any?> {
        if (warnIfPreliminary()) return emptyMap()
        return doc.typeAttributes(name)
    }

    public fun getAttrs(snapshot: Snapshot): Map<String, Any?> =
        doc.mapAtSnapshot(this, snapshot).mapValues { (_, value) -> doc.valueToAny(value) }

    public fun attrKeys(): Set<String> = getAttrs().keys

    public fun attrValues(): Collection<Any?> = getAttrs().values

    public fun attrEntries(): Set<Map.Entry<String, Any?>> = getAttrs().entries

    public fun <T> mapAttrs(transform: (value: Any?, key: String) -> T): List<T> {
        return getAttrs().map { (key, value) -> transform(value, key) }
    }

    public fun <T> mapAttrs(transform: (value: Any?, key: String, type: YArray) -> T): List<T> {
        return getAttrs().map { (key, value) -> transform(value, key, this) }
    }

    public fun forEachAttr(action: (value: Any?, key: String) -> Unit) {
        getAttrs().forEach { (key, value) -> action(value, key) }
    }

    public fun forEachAttr(action: (value: Any?, key: String, type: YArray) -> Unit) {
        getAttrs().forEach { (key, value) -> action(value, key, this) }
    }

    public fun hasAttr(key: String): Boolean {
        if (warnIfPreliminary()) return false
        return doc.hasTypeAttribute(name, key)
    }

    public fun hasAttr(key: String, snapshot: Snapshot): Boolean =
        doc.mapValueAtSnapshot(this, key, snapshot) != null

    public fun hasAttribute(key: String): Boolean = hasAttr(key)

    public fun hasAttribute(key: String, snapshot: Snapshot): Boolean = hasAttr(key, snapshot)

    public fun deleteAttr(key: String) {
        if (isPreliminary) {
            preliminaryMap.remove(key)
            return
        }
        doc.deleteTypeAttribute(name, key)
    }

    public fun deleteAttribute(key: String) {
        deleteAttr(key)
    }

    public fun removeAttribute(key: String) {
        deleteAttr(key)
    }

    public fun clearAttrs() {
        if (isPreliminary) {
            preliminaryMap.clear()
            return
        }
        doc.transact {
            getAttrs().keys.forEach(::deleteAttr)
        }
    }

    public fun get(index: Int): Any? {
        if (index < 0) return null
        if (index == 0) {
            if (firstVisibleScalarCached && cachedAccessUnchecked) return firstVisibleScalar
            if (!firstVisibleItemCached || !cachedAccessUnchecked) {
                doc.ensureThreadAccess()
            }
            if (!firstVisibleItemCached) {
                firstVisibleItem = doc.firstVisibleSequenceItem(name, RootKind.Array)
                cachedAccessUnchecked = doc.threadAccessPolicy == YThreadAccessPolicy.UNCHECKED
                firstVisibleItemCached = true
            }
            val item = firstVisibleItem ?: return null
            val scalar = when (val content = item.content) {
                is ItemContent.Value -> content.value
                is ItemContent.ArrayValues -> content.values.first()
                else -> null
            }
            if (scalar != null) {
                return doc.valueToAny(scalar).also { value ->
                    if (cachedAccessUnchecked && scalar.isStablePublicScalar()) {
                        firstVisibleScalar = value
                        firstVisibleScalarCached = true
                    }
                }
            }
            return when (val content = item.content) {
                is ItemContent.XmlType -> doc.typeFromXmlType(content)
                else -> null
            }
        }
        val (item, offset) = doc.visibleSequencePositionAt(name, RootKind.Array, index) ?: return null
        return when (val content = item.content) {
            is ItemContent.Value -> doc.valueToAny(content.value)
            is ItemContent.ArrayValues ->
                doc.valueToAny(content.values[offset.toNonNegativeInt("array value offset")])
            is ItemContent.XmlType -> doc.typeFromXmlType(content)
            else -> null
        }
    }

    public fun slice(start: Int = 0, end: Int = size): List<Any?> {
        val values = toList()
        val normalizedStart = normalizeSliceIndex(start, values.size)
        val normalizedEnd = normalizeSliceIndex(end, values.size)
        if (normalizedEnd <= normalizedStart) return emptyList()
        return values.subList(normalizedStart, normalizedEnd)
    }

    public fun toList(): List<Any?> {
        if (warnIfPreliminary()) return emptyList()
        return doc.visibleSequence(name)
            .filter { item ->
                item.content is ItemContent.Value ||
                    item.content is ItemContent.ArrayValues ||
                    (item.content is ItemContent.XmlType && item.content.kind == RootKind.Array)
            }
            .flatMap { item ->
                when (val content = item.content) {
                    is ItemContent.Value -> listOf(doc.valueToAny(content.value))
                    is ItemContent.ArrayValues -> content.values.map(doc::valueToAny)
                    is ItemContent.XmlType -> listOf(doc.typeFromXmlType(content))
                    else -> error("item content is not an array value: ${content::class.simpleName}")
                }
            }
    }

    public fun toArray(): List<Any?> = toList()

    public fun clone(): YArray {
        return YArray().also { cloned ->
            cloned.push(toList().map { it.cloneValueDetached() })
            cloned.setAttrs(getAttrs().mapValues { (_, value) -> value.cloneValueDetached() })
        }
    }

    public fun clone(targetDoc: YDoc): YArray {
        return targetDoc.createArray().also { cloned ->
            cloned.push(toList().map { it.cloneValueInto(targetDoc) })
            cloned.setAttrs(getAttrs().mapValues { (_, value) -> value.cloneValueInto(targetDoc) })
        }
    }

    public fun toDelta(): List<YArrayDeltaOp> {
        val values = toList()
        return if (values.isEmpty()) emptyList() else listOf(YArrayDeltaOp(insert = values))
    }

    public fun toDeltaDeep(renderer: AbstractRenderer = activeRenderer): YArrayDeepDelta =
        renderArrayDeepDelta(this, DeepDeltaRenderOptions(renderer = renderer))

    public fun applyDelta(
        delta: List<YArrayDeltaOp>,
        origin: Any? = null,
        renderer: AbstractRenderer = activeRenderer,
    ) {
        if (!isPreliminary) doc.preflightNestedValue(delta.map { op -> op.insert })
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

    public fun applyDeltaDeep(delta: YArrayDeepDelta, origin: Any? = null) {
        doc.transact(origin = origin) {
            clear()
            clearAttrs()
            setAttrs(delta.attrs.fromDeepDeltaValues(doc))
            applyDelta(delta.delta.fromDeepDeltaValues(doc))
        }
    }

    public fun <T> map(transform: (Any?) -> T): List<T> = toList().map(transform)

    public fun <T> map(transform: (value: Any?, index: Int) -> T): List<T> =
        toList().mapIndexed { index, value -> transform(value, index) }

    public fun <T> map(transform: (value: Any?, index: Int, type: YArray) -> T): List<T> =
        toList().mapIndexed { index, value -> transform(value, index, this) }

    public fun forEach(action: (Any?) -> Unit) {
        toList().forEach(action)
    }

    public fun forEach(action: (value: Any?, index: Int) -> Unit) {
        toList().forEachIndexed { index, value -> action(value, index) }
    }

    public fun forEach(action: (value: Any?, index: Int, type: YArray) -> Unit) {
        toList().forEachIndexed { index, value -> action(value, index, this) }
    }

    public fun forEachIndexed(action: (Int, Any?) -> Unit) {
        toList().forEachIndexed(action)
    }

    override fun iterator(): Iterator<Any?> = toList().iterator()

    override fun toJson(): List<Any?> {
        if (warnIfPreliminary()) return emptyList()
        return doc.visibleSequence(name)
            .filter { item ->
                item.content is ItemContent.Value ||
                    item.content is ItemContent.ArrayValues ||
                    (item.content is ItemContent.XmlType && item.content.kind == RootKind.Array)
            }
            .flatMap { item ->
                when (val content = item.content) {
                    is ItemContent.Value -> listOf(doc.valueToJson(content.value))
                    is ItemContent.ArrayValues -> content.values.map(doc::valueToJson)
                    is ItemContent.XmlType -> listOf(doc.typeFromXmlType(content).toJson())
                    else -> error("item content is not an array value: ${content::class.simpleName}")
                }
            }
    }

    override fun toJSON(): List<Any?> = toList().map(::toYTypeJsonValue)

    public companion object {
        public fun from(values: Iterable<Any?>): YArray = YArray(values)

        public fun from(values: Iterable<Any?>, doc: YDoc, name: String = ""): YArray {
            return doc.getArray(name).also { it.push(values.toList()) }
        }

        public fun from(delta: List<YArrayDeltaOp>, doc: YDoc, name: String = ""): YArray {
            return doc.getArray(name).also { it.applyDelta(delta) }
        }

        public fun from(delta: YArrayDeepDelta, doc: YDoc = YDoc(), name: String = ""): YArray {
            return doc.getArray(name).also { it.applyDeltaDeep(delta) }
        }
    }

    private fun normalizeSliceIndex(index: Int, size: Int): Int {
        val normalized = if (index < 0) size + index else index
        return normalized.coerceIn(0, size)
    }

    private fun YValue.isStablePublicScalar(): Boolean = when (this) {
        YValue.Null,
        YValue.Undefined,
        is YValue.Bool,
        is YValue.LongNumber,
        is YValue.DoubleNumber,
        is YValue.BigIntNumber,
        is YValue.StringValue,
        -> true
        is YValue.BinaryValue,
        is YValue.ListValue,
        is YValue.MapValue,
        is YValue.SubdocRef,
        is YValue.TypeRef,
        -> false
    }
}

public open class YText internal constructor(
    doc: YDoc,
    name: String,
    kind: RootKind = RootKind.Text,
) : AbstractYType(doc, name, kind), Iterable<Any?> {
    public constructor() : this(YDoc(), "") {
        markDetached()
    }

    public constructor(text: String, attributes: Map<String, Any?> = UnspecifiedTextAttributes) : this() {
        insert(0, text, attributes)
    }

    init {
        require(kind == RootKind.Text || kind == RootKind.XmlText) { "YText kind must be a text sequence" }
    }

    private var cachedLengthInitialized = false
    private var cachedLength: Int = 0
    private var cachedLengthUnchecked = false
    private var cachedStringVersion: Long = Long.MIN_VALUE
    private var cachedString: String = ""

    public val length: Int
        get() {
            if (cachedLengthInitialized && cachedLengthUnchecked) return cachedLength
            if (warnIfPreliminary()) return 0
            doc.ensureThreadAccess()
            if (!cachedLengthInitialized) {
                cachedLength = doc.visibleLength(name, kind).toNonNegativeInt("text length")
                cachedLengthUnchecked = doc.threadAccessPolicy == YThreadAccessPolicy.UNCHECKED
                cachedLengthInitialized = true
                doc.registerMaintainedLength(name)
            }
            return cachedLength
        }

    internal override fun adjustVisibleLength(changedKind: RootKind, delta: Long) {
        if (!cachedLengthInitialized || changedKind != kind) return
        cachedLength = try {
            Math.addExact(cachedLength.toLong(), delta)
        } catch (_: ArithmeticException) {
            error("maintained text length overflow")
        }.toNonNegativeInt("maintained text length")
    }

    public val attrSize: Int get() = getAttrs().size

    public fun insert(index: Int, text: String, attributes: Map<String, Any?> = UnspecifiedTextAttributes) {
        if (text.isEmpty()) return
        if (isPreliminary) {
            queuePreliminaryOperation(attributes) { insert(index, text, attributes) }
            return
        }
        val start = index.coerceAtLeast(0)
        require(start <= length) { "insert index is out of bounds" }
        doc.transact {
            val normalized = attributes.normalizeOptionalTextAttributes()
            val baseAttributes = normalized.orEmpty()
            insertAttributedTextEntries(
                start,
                listOf(ItemContent.Text(text, baseAttributes, kind = kind)),
                normalized,
            )
        }
    }

    public fun insertText(
        index: Int,
        text: String,
        attributes: Map<String, Any?> = UnspecifiedTextAttributes,
        origin: Any? = null,
    ) {
        if (text.isEmpty()) return
        if (isPreliminary) {
            queuePreliminaryOperation(attributes) { insertText(index, text, attributes, origin) }
            return
        }
        val start = index.coerceAtLeast(0)
        require(start <= length) { "insert index is out of bounds" }
        doc.transact(origin = origin) {
            val normalized = attributes.normalizeOptionalTextAttributes()
            val baseAttributes = normalized.orEmpty()
            insertAttributedTextEntries(
                start,
                listOf(ItemContent.Text(text, baseAttributes, kind = kind)),
                normalized,
            )
        }
    }

    public fun insert(
        index: Int,
        values: List<Any?>,
        attributes: Map<String, Any?> = UnspecifiedTextAttributes,
    ) {
        if (values.isEmpty()) return
        if (isPreliminary) {
            require(values.none { value -> value === this }) { "shared type cannot contain itself" }
            val storedValues = values.filterNot { value -> value is String || value is Char }
            queuePreliminaryOperation(listOf(storedValues, attributes)) { insert(index, values, attributes) }
            return
        }
        val start = index.coerceAtLeast(0)
        require(start <= length) { "insert index is out of bounds" }
        doc.preflightNestedValue(
            listOf(values.filterNot { value -> value is String || value is Char }, attributes),
        )
        doc.transact {
            val normalized = attributes.normalizeOptionalTextAttributes()
            val baseAttributes = normalized.orEmpty()
            val entries = values.flatMap { value ->
                when (value) {
                    is String -> value.takeIf(String::isNotEmpty)
                        ?.let { text -> listOf(ItemContent.Text(text, baseAttributes, kind = kind)) }
                        .orEmpty()
                    is Char -> listOf(ItemContent.Text(value.toString(), baseAttributes, kind = kind))
                    null -> error("text embeds must not be null")
                    is AbstractYType -> listOf(
                        ItemContent.XmlType(
                            doc.storeValue(value, parent = name) as YValue.TypeRef,
                            value.xmlNodeNameOrEmpty(),
                            kind,
                            baseAttributes,
                        ),
                    )
                    else -> listOf(ItemContent.TextEmbed(doc.storeValue(value, parent = name), baseAttributes, kind = kind))
                }
            }
            insertAttributedTextEntries(start, entries, normalized)
        }
    }

    public fun insertEmbed(
        index: Int,
        embed: Any?,
        attributes: Map<String, Any?> = emptyMap(),
    ) {
        require(embed != null) { "embed must not be null" }
        if (isPreliminary) {
            require(embed !== this) { "shared type cannot contain itself" }
            queuePreliminaryOperation(listOf(embed, attributes)) { insertEmbed(index, embed, attributes) }
            return
        }
        val start = index.coerceAtLeast(0)
        require(start <= length) { "insert index is out of bounds" }
        doc.preflightNestedValue(embed)
        doc.transact {
            val normalized = normalizeTextAttributes(attributes)
            val content = if (embed is AbstractYType) {
                ItemContent.XmlType(
                    doc.storeValue(embed, parent = name) as YValue.TypeRef,
                    embed.xmlNodeNameOrEmpty(),
                    kind,
                    normalized,
                )
            } else {
                ItemContent.TextEmbed(doc.storeValue(embed, parent = name), normalized, kind = kind)
            }
            insertAttributedTextEntries(
                start,
                listOf(content),
                normalized,
            )
        }
    }

    public fun insertEmbed(index: Int, embed: Any?, attributes: Map<String, Any?> = emptyMap(), origin: Any?) {
        require(embed != null) { "embed must not be null" }
        if (isPreliminary) {
            require(embed !== this) { "shared type cannot contain itself" }
            queuePreliminaryOperation(listOf(embed, attributes)) { insertEmbed(index, embed, attributes, origin) }
            return
        }
        val start = index.coerceAtLeast(0)
        require(start <= length) { "insert index is out of bounds" }
        doc.preflightNestedValue(embed)
        doc.transact(origin = origin) {
            val normalized = normalizeTextAttributes(attributes)
            val content = if (embed is AbstractYType) {
                ItemContent.XmlType(
                    doc.storeValue(embed, parent = name) as YValue.TypeRef,
                    embed.xmlNodeNameOrEmpty(),
                    kind,
                    normalized,
                )
            } else {
                ItemContent.TextEmbed(doc.storeValue(embed, parent = name), normalized, kind = kind)
            }
            insertAttributedTextEntries(
                start,
                listOf(content),
                normalized,
            )
        }
    }

    public fun push(text: String, attributes: Map<String, Any?> = UnspecifiedTextAttributes) {
        insert(length, text, attributes)
    }

    public fun push(values: List<Any?>, attributes: Map<String, Any?> = UnspecifiedTextAttributes) {
        insert(length, values, attributes)
    }

    public fun push(vararg values: Any?) {
        push(values.toList())
    }

    public fun unshift(text: String, attributes: Map<String, Any?> = UnspecifiedTextAttributes) {
        insert(0, text, attributes)
    }

    public fun unshift(values: List<Any?>, attributes: Map<String, Any?> = UnspecifiedTextAttributes) {
        insert(0, values, attributes)
    }

    public fun unshift(vararg values: Any?) {
        unshift(values.toList())
    }

    public fun delete(index: Int, length: Int = 1) {
        if (length == 0) return
        if (isPreliminary) {
            queuePreliminaryOperation { delete(index, length) }
            return
        }
        doc.deleteVisible(name, index, length, strictLength = false)
    }

    public fun deleteText(index: Int, length: Int = 1, origin: Any? = null) {
        if (length == 0) return
        if (isPreliminary) {
            queuePreliminaryOperation { deleteText(index, length, origin) }
            return
        }
        doc.deleteVisible(name, index, length, origin = origin, strictLength = false)
    }

    public fun clear() {
        delete(0, length)
    }

    public fun setAttr(key: String, value: Any?): Any? {
        if (isPreliminary) {
            queuePreliminaryOperation(value) { setAttr(key, value) }
            return value
        }
        return doc.setTypeAttribute(name, key, value)
    }

    public fun setAttribute(key: String, value: Any?): Any? = setAttr(key, value)

    public fun setAttrs(values: Map<String, Any?>): YText {
        if (!isPreliminary) doc.preflightNestedValue(values.values.toList())
        doc.transact {
            values.toSortedMap().forEach { (key, value) -> setAttr(key, value) }
        }
        return this
    }

    public fun getAttr(key: String): Any? {
        if (warnIfPreliminary()) return null
        return doc.typeAttribute(name, key)
    }

    public fun getAttr(key: String, snapshot: Snapshot): Any? =
        doc.mapValueAtSnapshot(this, key, snapshot)?.let(doc::valueToAny)

    public fun getAttribute(key: String): Any? = getAttr(key)

    public fun getAttribute(key: String, snapshot: Snapshot): Any? = getAttr(key, snapshot)

    public fun getAttrs(): Map<String, Any?> {
        if (warnIfPreliminary()) return emptyMap()
        return doc.typeAttributes(name)
    }

    public fun getAttributes(): Map<String, Any?> = getAttrs()

    public fun getAttrs(snapshot: Snapshot): Map<String, Any?> =
        doc.mapAtSnapshot(this, snapshot).mapValues { (_, value) -> doc.valueToAny(value) }

    public fun attrKeys(): Set<String> = getAttrs().keys

    public fun attrValues(): Collection<Any?> = getAttrs().values

    public fun attrEntries(): Set<Map.Entry<String, Any?>> = getAttrs().entries

    public fun <T> mapAttrs(transform: (value: Any?, key: String) -> T): List<T> {
        return getAttrs().map { (key, value) -> transform(value, key) }
    }

    public fun <T> mapAttrs(transform: (value: Any?, key: String, type: YText) -> T): List<T> {
        return getAttrs().map { (key, value) -> transform(value, key, this) }
    }

    public fun forEachAttr(action: (value: Any?, key: String) -> Unit) {
        getAttrs().forEach { (key, value) -> action(value, key) }
    }

    public fun forEachAttr(action: (value: Any?, key: String, type: YText) -> Unit) {
        getAttrs().forEach { (key, value) -> action(value, key, this) }
    }

    public fun hasAttr(key: String): Boolean {
        if (warnIfPreliminary()) return false
        return doc.hasTypeAttribute(name, key)
    }

    public fun hasAttr(key: String, snapshot: Snapshot): Boolean =
        doc.mapValueAtSnapshot(this, key, snapshot) != null

    public fun hasAttribute(key: String): Boolean = hasAttr(key)

    public fun hasAttribute(key: String, snapshot: Snapshot): Boolean = hasAttr(key, snapshot)

    public fun deleteAttr(key: String) {
        if (isPreliminary) {
            queuePreliminaryOperation { deleteAttr(key) }
            return
        }
        doc.deleteTypeAttribute(name, key)
    }

    public fun deleteAttribute(key: String) {
        deleteAttr(key)
    }

    public fun removeAttribute(key: String) {
        deleteAttr(key)
    }

    public fun clearAttrs() {
        doc.transact {
            getAttrs().keys.forEach(::deleteAttr)
        }
    }

    public fun format(index: Int, length: Int, attributes: Map<String, Any?>) {
        formatRange(index, length, attributes, origin = null)
    }

    public fun formatText(index: Int, length: Int, attributes: Map<String, Any?>, origin: Any? = null) {
        formatRange(index, length, attributes, origin)
    }

    private fun formatRange(index: Int, length: Int, attributes: Map<String, Any?>, origin: Any?) {
        if (length == 0) return
        if (isPreliminary) {
            queuePreliminaryOperation(attributes) { formatRange(index, length, attributes, origin) }
            return
        }
        val start = index.coerceAtLeast(0)
        doc.transact(origin = origin) {
            val currentLength = this.length
            if (start >= currentLength) return@transact
            val normalized = normalizeTextFormatAttributes(attributes)
            if (length < 0) {
                insertTransientFormatMarkers(start, normalized)
                return@transact
            }
            val available = currentLength - start
            val existingLength = minOf(length, available)
            if (normalized.isNotEmpty()) {
                formatNativeRange(start, existingLength, normalized)
            }
            val overflow = length - available
            if (overflow > 0) {
                insert(this.length, "\n".repeat(overflow), attributes)
            }
        }
    }

    public fun applyDelta(
        delta: YTextDelta,
        origin: Any? = null,
        renderer: AbstractRenderer = activeRenderer,
        sanitize: Boolean = true,
    ) {
        if (isPreliminary) {
            queuePreliminaryOperation(
                delta.ops.flatMap { op -> listOf(op.insert, op.attributes) },
            ) { applyDelta(delta, origin, renderer, sanitize) }
            return
        }
        doc.preflightNestedValue(delta.ops.flatMap { op -> listOf(op.insert, op.attributes) })
        doc.transact({ transaction ->
            var renderedIndex = 0
            delta.ops.forEachIndexed { opIndex, op ->
                when {
                    op.insert != null -> {
                        val rawInsert = op.insert
                        val index = renderedSequenceIndexToVisibleIndex(this, renderedIndex, renderer)
                        val insert = if (
                            !sanitize &&
                            opIndex == delta.ops.lastIndex &&
                            rawInsert is String &&
                            rawInsert.endsWith('\n') &&
                            index == length
                        ) {
                            rawInsert.dropLast(1)
                        } else {
                            rawInsert
                        }
                        if (insert is String) {
                            if (insert.isEmpty()) return@forEachIndexed
                            insert(index, insert, op.attributes)
                            renderedIndex += insert.length
                        } else {
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
                        if (op.delete <= 0) return@forEachIndexed
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

    public fun applyDelta(delta: YTextDelta, sanitize: Boolean) {
        applyDelta(delta, origin = null, renderer = activeRenderer, sanitize = sanitize)
    }

    public fun applyDeltaDeep(delta: YTextDeepDelta, origin: Any? = null) {
        if (isPreliminary) {
            queuePreliminaryOperation(
                listOf(delta.attrs) + delta.delta.ops.flatMap { op -> listOf(op.insert, op.attributes) },
            ) { applyDeltaDeep(delta, origin) }
            return
        }
        doc.transact(origin = origin) {
            clear()
            clearAttrs()
            setAttrs(delta.attrs.fromDeepDeltaValues(doc))
            applyDelta(delta.delta.fromDeepDeltaValues(doc))
        }
    }

    public fun toDelta(): YTextDelta {
        if (warnIfPreliminary()) return YTextDelta()
        val delta = YTextDelta()
        var pendingText = StringBuilder()
        var pendingAttributes: Map<String, Any?>? = null
        val activeNativeAttributes = linkedMapOf<String, YValue>()
        var activeWithoutNulls: Map<String, YValue> = emptyMap()

        fun renderedAttributes(content: ItemContent): Map<String, Any?> {
            val stored = content.storedTextAttributes()
            val rendered = if (stored.isEmpty()) {
                activeWithoutNulls
            } else {
                stored.toMutableMap().also { values ->
                    activeNativeAttributes.forEach { (key, value) ->
                        if (value == YValue.Null) values.remove(key) else values[key] = value
                    }
                }.toSortedMap()
            }
            return textAttributesToPublic(rendered)
        }

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
                val marker = item.content
                activeNativeAttributes[marker.key] = marker.value
                activeWithoutNulls = activeNativeAttributes
                    .filterValues { value -> value != YValue.Null }
                    .toSortedMap()
                return@forEach
            }
            when (val content = item.content) {
                is ItemContent.Text -> {
                    val attributes = renderedAttributes(content)
                    if (pendingAttributes != null && pendingAttributes != attributes) flush()
                    pendingAttributes = attributes
                    pendingText.append(content.value)
                }
                is ItemContent.TextEmbed -> {
                    val attributes = renderedAttributes(content)
                    flush()
                    delta.insertEmbed(doc.valueToAny(content.value), attributes)
                    pendingAttributes = null
                }
                is ItemContent.XmlType -> {
                    val attributes = renderedAttributes(content)
                    flush()
                    delta.insertEmbed(doc.typeFromXmlType(content), attributes)
                    pendingAttributes = null
                }
                else -> Unit
            }
        }
        flush()
        return delta
    }

    public fun toDelta(
        snapshot: Snapshot?,
        prevSnapshot: Snapshot? = null,
        computeYChange: ((change: String, id: Id) -> Any?)? = null,
    ): YTextDelta {
        if (warnIfPreliminary()) return YTextDelta()
        if (prevSnapshot == null) {
            return snapshot?.let { doc.textDeltaAtSnapshot(this, it) } ?: toDelta()
        }

        fun StoreItem.isVisibleAt(target: Snapshot?): Boolean = if (target == null) {
            !deleted
        } else {
            id.clock < (target.sv[id.client] ?: 0) && !target.ds.hasId(id)
        }

        val delta = YTextDelta()
        val formats = linkedMapOf<String, YValue>()
        var pendingText = StringBuilder()
        var pendingAttributes: Map<String, Any?>? = null
        var hasYChange = false
        var yChange: Any? = null
        var yChangeType: String? = null
        var yChangeClient: Long? = null
        var yChangeEndClock: Long? = null

        fun publicAttributes(content: ItemContent): Map<String, Any?> {
            val baseAttributes = content.baseTextAttributes()
            if (baseAttributes.isEmpty() && formats.isEmpty()) {
                return if (hasYChange) mapOf("ychange" to yChange) else emptyMap()
            }
            val values = baseAttributes.toMutableMap()
            formats.forEach { (key, value) ->
                if (value == YValue.Null) values.remove(key) else values[key] = value
            }
            return buildMap {
                putAll(textAttributesToPublic(values))
                if (hasYChange) put("ychange", yChange)
            }
        }

        fun flush() {
            if (pendingText.isNotEmpty()) {
                delta.insertSegment(pendingText.toString(), pendingAttributes.orEmpty())
                pendingText = StringBuilder()
            }
        }

        fun resetYChange() {
            hasYChange = false
            yChange = null
            yChangeType = null
            yChangeClient = null
            yChangeEndClock = null
        }

        fun snapshotBoundaries(client: Long): LongArray {
            val snapshotClock = snapshot?.sv?.get(client)
            val previousClock = prevSnapshot.sv[client]
            val snapshotRanges = snapshot?.ds?.rangesForTraversal(client).orEmpty()
            val previousRanges = prevSnapshot.ds.rangesForTraversal(client)
            val clocks = LongArray(
                (if (snapshotClock != null) 1 else 0) +
                    (if (previousClock != null) 1 else 0) +
                    (snapshotRanges.size + previousRanges.size) * 2,
            )
            var size = 0
            snapshotClock?.let { clocks[size++] = it }
            previousClock?.let { clocks[size++] = it }
            snapshotRanges.forEach { range ->
                clocks[size++] = range.clock
                clocks[size++] = range.end
            }
            previousRanges.forEach { range ->
                clocks[size++] = range.clock
                clocks[size++] = range.end
            }
            clocks.sort(0, size)
            var uniqueSize = 0
            for (index in 0 until size) {
                if (uniqueSize == 0 || clocks[index] != clocks[uniqueSize - 1]) {
                    clocks[uniqueSize++] = clocks[index]
                }
            }
            return if (uniqueSize == clocks.size) clocks else clocks.copyOf(uniqueSize)
        }

        ClockRangeCursor(doc.sequence(name)).forEachRangeWithClocks(
            ::snapshotBoundaries,
        ) { source, startClock, endClock ->
            val item = source.clockRangeView(startClock, endClock)
            if (item.content.kind != kind || !item.isVisibleAt(snapshot) && !item.isVisibleAt(prevSnapshot)) {
                return@forEachRangeWithClocks true
            }
            when (val content = item.content) {
                is ItemContent.NativeTextFormat -> {
                    flush()
                    resetYChange()
                    if (item.isVisibleAt(snapshot)) {
                        if (content.value == YValue.Null) {
                            formats.remove(content.key)
                        } else {
                            formats[content.key] = content.value
                        }
                    }
                }
                is ItemContent.Text -> {
                    val change = when {
                        snapshot != null && !item.isVisibleAt(snapshot) -> "removed"
                        !item.isVisibleAt(prevSnapshot) -> "added"
                        else -> null
                    }
                    if (change == null) {
                        if (hasYChange) {
                            flush()
                            resetYChange()
                        }
                    } else {
                        val continuesPackedString = hasYChange &&
                            yChangeType == change &&
                            yChangeClient == item.id.client &&
                            yChangeEndClock == item.id.clock
                        if (!continuesPackedString) {
                            val nextYChange = computeYChange?.invoke(change, item.id) ?: mapOf("type" to change)
                            if (!hasYChange || yChange != nextYChange) flush()
                            yChange = nextYChange
                        }
                        hasYChange = true
                        yChangeType = change
                        yChangeClient = item.id.client
                        yChangeEndClock = item.id.clock + item.length
                    }
                    val attributes = publicAttributes(content)
                    if (pendingAttributes != null && pendingAttributes != attributes) flush()
                    pendingAttributes = attributes
                    pendingText.append(content.value)
                }
                is ItemContent.TextEmbed -> {
                    flush()
                    delta.insertEmbed(doc.valueToAny(content.value), publicAttributes(content))
                    pendingAttributes = null
                    resetYChange()
                }
                is ItemContent.XmlType -> {
                    flush()
                    delta.insertEmbed(doc.typeFromXmlType(content), publicAttributes(content))
                    pendingAttributes = null
                    resetYChange()
                }
                is ItemContent.TextFormat,
                is ItemContent.Value,
                is ItemContent.ArrayValues,
                is ItemContent.MapEntry,
                is ItemContent.MapEntries,
                is ItemContent.XmlNode,
                is ItemContent.Deleted -> {
                    flush()
                    resetYChange()
                }
            }
            true
        }
        flush()
        return delta
    }

    public fun toDeltaDeep(renderer: AbstractRenderer = activeRenderer): YTextDeepDelta =
        renderTextDeepDelta(this, DeepDeltaRenderOptions(renderer = renderer))

    public fun toList(): List<Any?> {
        if (warnIfPreliminary()) return emptyList()
        return buildList(length) {
            doc.sequence(name).forEach { item ->
                if (item.deleted || !item.countable || item.content.kind != kind) return@forEach
                when (val content = item.content) {
                    is ItemContent.Text -> content.value.forEach { character -> add(character.toString()) }
                    is ItemContent.TextEmbed -> add(doc.valueToAny(content.value))
                    is ItemContent.XmlType -> add(doc.typeFromXmlType(content))
                    else -> Unit
                }
            }
        }
    }

    public fun toArray(): List<Any?> = toList()

    public fun slice(start: Int = 0, end: Int = length): List<Any?> {
        val values = toList()
        val normalizedStart = normalizeTextSliceIndex(start, values.size)
        val normalizedEnd = normalizeTextSliceIndex(end, values.size)
        if (normalizedEnd <= normalizedStart) return emptyList()
        return values.subList(normalizedStart, normalizedEnd)
    }

    public fun get(index: Int): Any? {
        if (index < 0) return null
        val (item, offset) = doc.visibleSequencePositionAt(name, kind, index) ?: return null
        return when (val content = item.content) {
            is ItemContent.Text -> content.value[offset.toNonNegativeInt("text item offset")].toString()
            is ItemContent.TextEmbed -> doc.valueToAny(content.value)
            is ItemContent.XmlType -> doc.typeFromXmlType(content)
            else -> null
        }
    }

    public fun <T> map(transform: (Any?) -> T): List<T> = toList().map(transform)

    public fun <T> map(transform: (value: Any?, index: Int) -> T): List<T> =
        toList().mapIndexed { index, value -> transform(value, index) }

    public fun <T> map(transform: (value: Any?, index: Int, type: YText) -> T): List<T> =
        toList().mapIndexed { index, value -> transform(value, index, this) }

    public fun forEach(action: (Any?) -> Unit) {
        toList().forEach(action)
    }

    public fun forEach(action: (value: Any?, index: Int) -> Unit) {
        toList().forEachIndexed { index, value -> action(value, index) }
    }

    public fun forEach(action: (value: Any?, index: Int, type: YText) -> Unit) {
        toList().forEachIndexed { index, value -> action(value, index, this) }
    }

    public fun forEachIndexed(action: (Int, Any?) -> Unit) {
        toList().forEachIndexed(action)
    }

    override fun iterator(): Iterator<Any?> = toList().iterator()

    override fun toString(): String {
        if (warnIfPreliminary()) return ""
        doc.ensureThreadAccess()
        val version = doc.store.version
        if (cachedStringVersion != version) {
            cachedString = doc.visibleText(name, kind)
            cachedStringVersion = version
        }
        return cachedString
    }

    override fun toJson(): String = toString()

    public open fun clone(): YText {
        return YText().also { cloned ->
            cloned.applyDelta(toDelta().cloneDetached())
            cloned.setAttrs(getAttrs().mapValues { (_, value) -> value.cloneValueDetached() })
        }
    }

    public open fun clone(targetDoc: YDoc): YText {
        return targetDoc.createText().also { cloned ->
            cloned.applyDelta(toDelta().cloneInto(targetDoc))
            cloned.setAttrs(getAttrs().mapValues { (_, value) -> value.cloneValueInto(targetDoc) })
        }
    }

    public companion object {
        public fun from(delta: YTextDelta, doc: YDoc = YDoc(), name: String = ""): YText {
            return doc.getText(name).also { it.applyDelta(delta) }
        }

        public fun from(delta: YTextDeepDelta, doc: YDoc = YDoc(), name: String = ""): YText {
            return doc.getText(name).also { it.applyDeltaDeep(delta) }
        }
    }

    private fun insertTextEntries(
        index: Int,
        entries: List<ItemContent>,
        originOverride: Id? = null,
        rightOriginOverride: Id? = null,
    ): StoreItem? {
        val visibleLength = doc.visibleLength(name, kind)
        require(index.toLong() <= visibleLength) {
            "insert index $index exceeds visible text length $visibleLength"
        }
        if (entries.isEmpty()) return null
        val anchors = doc.insertionAnchors(name, kind, index)
        var origin = originOverride ?: anchors.first
        val rightOrigin = rightOriginOverride ?: anchors.second
        var lastInserted: StoreItem? = null
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
            origin = item.lastId
            lastInserted = item
        }
        return lastInserted
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
        doc.insertionAnchors(name, kind, index)
        val cursor = findNativeTextPosition(index)
        val desired = attributes.toMutableMap()
        cursor.currentAttributes.keys.forEach { key ->
            if (key !in desired) desired[key] = YValue.Null
        }
        minimizeNativeAttributeChanges(cursor, desired)
        val negatedAttributes = insertNativeAttributes(cursor, desired)
        val inserted = insertTextEntries(
            index,
            entries.map { entry -> entry.withoutTextAttributes() },
            originOverride = cursor.left?.lastId,
            rightOriginOverride = cursor.right?.id,
        )
        if (inserted != null) {
            cursor.sequence.seek(inserted)
            cursor.sequence.advance()
            cursor.left = inserted
        }
        insertNegatedNativeAttributes(cursor, negatedAttributes)
    }

    private fun formatNativeRange(start: Int, length: Int, attributes: Map<String, YValue>) {
        if (length <= 0) return
        // Yjs formats from a linked ItemTextListPosition. Split only the two requested boundaries,
        // then walk to the start and touch format markers/content inside the requested range.
        // Zero is already a structural boundary. Avoid replacing the indexed-sequence position
        // hint for the end boundary; repeated small formats can then resolve the unchanged end in
        // O(1), matching Yjs' linked-position behavior.
        if (start > 0) doc.insertionAnchors(name, kind, start)
        doc.insertionAnchors(name, kind, start + length)
        val cursor = findNativeTextPosition(start)
        minimizeNativeAttributeChanges(cursor, attributes)
        val negatedAttributes = insertNativeAttributes(cursor, attributes)
        var remaining = length.toLong()
        while (true) {
            val right = cursor.right ?: break
            val marker = if (right.deleted) null else right.nativeTextFormat()
            if (
                remaining <= 0L &&
                (negatedAttributes.isEmpty() || !right.deleted && marker == null)
            ) {
                break
            }
            if (!right.deleted) {
                if (marker != null) {
                    if (attributes.containsKey(marker.key)) {
                        val desired = attributes.getValue(marker.key)
                        if (desired == marker.value) {
                            negatedAttributes.remove(marker.key)
                        } else {
                            if (remaining <= 0L) break
                            negatedAttributes[marker.key] = marker.value
                        }
                        doc.deleteItemsByIds(listOf(right.id))
                    } else {
                        cursor.currentAttributes.applyTextFormatAttributes(mapOf(marker.key to marker.value))
                    }
                } else if (right.countable && right.content.kind == kind) {
                    check(remaining >= right.length) { "text format boundary must split packed content" }
                    remaining -= right.length
                }
            }
            forwardNativeTextCursor(cursor)
        }
        insertNegatedNativeAttributes(cursor, negatedAttributes)
    }

    private class NativeTextCursor(
        var left: StoreItem?,
        val sequence: SequenceCursor,
        val currentAttributes: MutableMap<String, YValue>,
    ) {
        val right: StoreItem? get() = sequence.current
    }

    private fun findNativeTextPosition(index: Int): NativeTextCursor {
        var remaining = index.toLong()
        val sequence = doc.sequenceCursorAtFirstUndeleted(name)
        val cursor = NativeTextCursor(
            left = sequence.previous,
            sequence = sequence,
            currentAttributes = linkedMapOf(),
        )
        while (remaining > 0L) {
            val right = cursor.right ?: break
            if (!right.deleted) {
                val marker = right.nativeTextFormat()
                if (marker != null) {
                    cursor.currentAttributes.applyTextFormatAttributes(mapOf(marker.key to marker.value))
                } else if (right.countable && right.content.kind == kind) {
                    check(remaining >= right.length) { "text format start must split packed content" }
                    remaining -= right.length
                }
            }
            forwardNativeTextCursor(cursor, updateAttributes = false)
        }
        require(remaining == 0L) { "text format index is out of bounds" }
        return cursor
    }

    private fun forwardNativeTextCursor(cursor: NativeTextCursor, updateAttributes: Boolean = true) {
        val right = cursor.right ?: return
        if (updateAttributes && !right.deleted) {
            right.nativeTextFormat()?.let { marker ->
                cursor.currentAttributes.applyTextFormatAttributes(mapOf(marker.key to marker.value))
            }
        }
        cursor.sequence.advanceToNextUndeleted()
        cursor.left = cursor.sequence.previous
    }

    private fun minimizeNativeAttributeChanges(
        cursor: NativeTextCursor,
        attributes: Map<String, YValue>,
    ) {
        while (true) {
            val right = cursor.right ?: return
            val marker = if (right.deleted) null else right.nativeTextFormat()
            if (
                !right.deleted &&
                (marker == null || (attributes[marker.key] ?: YValue.Null) != marker.value)
            ) {
                return
            }
            forwardNativeTextCursor(cursor)
        }
    }

    private fun insertNativeAttributes(
        cursor: NativeTextCursor,
        attributes: Map<String, YValue>,
    ): MutableMap<String, YValue> {
        val negated = linkedMapOf<String, YValue>()
        attributes.forEach { (key, value) ->
            val current = cursor.currentAttributes[key] ?: YValue.Null
            if (current != value) {
                negated[key] = current
                insertNativeFormatMarker(cursor, key, value)
            }
        }
        return negated
    }

    private fun insertNegatedNativeAttributes(
        cursor: NativeTextCursor,
        negatedAttributes: MutableMap<String, YValue>,
    ) {
        while (true) {
            val right = cursor.right ?: break
            val marker = if (right.deleted) null else right.nativeTextFormat()
            val matchesNegated = marker != null &&
                negatedAttributes[marker.key] == marker.value
            if (!right.deleted && !matchesNegated) break
            if (!right.deleted && marker != null) {
                negatedAttributes.remove(marker.key)
            }
            forwardNativeTextCursor(cursor)
        }
        negatedAttributes.forEach { (key, value) ->
            insertNativeFormatMarker(cursor, key, value)
        }
    }

    private fun insertNativeFormatMarker(
        cursor: NativeTextCursor,
        key: String,
        value: YValue,
    ) {
        val item = StoreItem(
            id = doc.nextId(),
            origin = cursor.left?.lastId,
            rightOrigin = cursor.right?.id,
            parent = name,
            parentSub = null,
            content = ItemContent.NativeTextFormat(key, value, kind),
        )
        doc.integrateLocal(item)
        cursor.sequence.seek(item)
        forwardNativeTextCursor(cursor)
    }

    private fun StoreItem.nativeTextFormat(): ItemContent.NativeTextFormat? =
        (content as? ItemContent.NativeTextFormat)?.takeIf { marker -> marker.kind == kind }

    private fun insertTransientFormatMarkers(index: Int, attributes: Map<String, YValue>) {
        if (attributes.isEmpty()) return
        val ambient = attributesAt(index)
        val changes = attributes.filter { (key, value) -> (ambient[key] ?: YValue.Null) != value }
        if (changes.isEmpty()) return
        val lastMarker = insertNativeFormatMarkers(index, changes)
        val restore = changes.keys.associateWith { key -> ambient[key] ?: YValue.Null }
        insertNativeFormatMarkers(index, restore, originOverride = lastMarker)
    }

    private fun attributesAt(index: Int): Map<String, YValue> {
        doc.visibleSequencePositionAt(name, kind, index)?.first?.let { item ->
            return doc.renderedTextAttributes(item)
        }
        val active = linkedMapOf<String, YValue>()
        doc.sequence(name).forEach { item ->
            val marker = item.content as? ItemContent.NativeTextFormat
            if (!item.deleted && marker?.kind == kind) {
                active.applyTextFormatAttributes(mapOf(marker.key to marker.value))
            }
        }
        return active
    }

    private fun insertNativeFormatMarkers(
        index: Int,
        attributes: Map<String, YValue>,
        originOverride: Id? = null,
    ): Id? {
        if (attributes.isEmpty()) return null
        val anchors = doc.insertionAnchors(name, kind, index)
        var origin = originOverride ?: anchors.first
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
    is ItemContent.XmlType -> attributes
    else -> error("content is not text-like")
}

private fun ItemContent.baseTextAttributes(): Map<String, YValue> = when (this) {
    is ItemContent.Text -> baseAttributes
    is ItemContent.TextEmbed -> baseAttributes
    is ItemContent.XmlType -> baseAttributes
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
    is ItemContent.XmlType -> copy(attributes = attributes)
    else -> error("content is not text-like")
}

private fun ItemContent.withoutTextAttributes(): ItemContent = when (this) {
    is ItemContent.Text -> copy(attributes = emptyMap(), baseAttributes = emptyMap())
    is ItemContent.TextEmbed -> copy(attributes = emptyMap(), baseAttributes = emptyMap())
    is ItemContent.XmlType -> copy(attributes = emptyMap(), baseAttributes = emptyMap())
    else -> error("content is not text-like")
}

private fun normalizeTextFormatAttributes(attributes: Map<String, Any?>): Map<String, YValue> =
    attributes.mapValues { (_, value) -> value?.let(YValue::from) ?: YValue.Null }.toSortedMap()

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

internal fun YTextDelta.cloneDetached(): YTextDelta {
    val cloned = YTextDelta()
    ops.forEach { op ->
        val attrs = op.attributes.mapValues { (_, value) -> value.cloneValueDetached() }
        when {
            op.insert is String -> cloned.insertSegment(op.insert, attrs)
            op.insert != null -> cloned.insertEmbed(op.insert.cloneValueDetached(), attrs)
            op.retain != null -> cloned.retain(op.retain, attrs)
            op.delete != null -> cloned.delete(op.delete)
        }
    }
    return cloned
}

public open class YMap internal constructor(
    doc: YDoc,
    name: String,
    kind: RootKind = RootKind.Map,
) :
    AbstractYType(doc, name, kind),
    Iterable<Map.Entry<String, Any?>> {
    public constructor() : this(YDoc(), "") {
        markDetached()
    }

    public constructor(values: Map<String, Any?>) : this() {
        setAttrs(values)
    }

    public constructor(entries: Iterable<Pair<String, Any?>>) : this() {
        setAttrs(entries.toMap())
    }

    init {
        require(kind == RootKind.Map || kind == RootKind.XmlHook) {
            "YMap kind must be a map or XML hook"
        }
    }

    public val size: Int
        get() {
            if (warnIfPreliminary()) return 0
            return doc.visibleMap(name).size
        }

    public val attrSize: Int get() = size

    public fun set(key: String, value: Any?): Any? {
        if (isPreliminary) {
            require(value !== this) { "shared type cannot contain itself" }
            preliminaryMap[key] = value
            return value
        }
        doc.transact {
            val stored = doc.storeValue(value, parent = name)
            val content = if (value is AbstractYType) {
                ItemContent.XmlType(stored as YValue.TypeRef, value.xmlNodeNameOrEmpty(), kind)
            } else {
                ItemContent.MapEntry(stored)
            }
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

    public fun setAttr(key: String, value: Any?): Any? = set(key, value)

    public fun setAttribute(key: String, value: Any?): Any? = setAttr(key, value)

    public fun setAttrs(values: Map<String, Any?>): YMap {
        if (isPreliminary) {
            values.forEach { (key, value) -> preliminaryMap[key] = value }
            return this
        }
        doc.preflightNestedValue(values.values.toList())
        doc.transact {
            values.forEach { (key, value) -> set(key, value) }
        }
        return this
    }

    public fun get(key: String): Any? {
        if (warnIfPreliminary()) return null
        return doc.visibleMapValue(name, key)?.let { doc.valueToAny(it) }
    }

    public fun get(key: String, snapshot: Snapshot): Any? =
        doc.mapValueAtSnapshot(this, key, snapshot)?.let(doc::valueToAny)

    public fun getAttr(key: String): Any? = get(key)

    public fun getAttr(key: String, snapshot: Snapshot): Any? = get(key, snapshot)

    public fun getAttribute(key: String): Any? = getAttr(key)

    public fun getAttribute(key: String, snapshot: Snapshot): Any? = getAttr(key, snapshot)

    public fun has(key: String): Boolean {
        if (warnIfPreliminary()) return false
        return doc.visibleMapValue(name, key) != null
    }

    public fun has(key: String, snapshot: Snapshot): Boolean = doc.mapValueAtSnapshot(this, key, snapshot) != null

    public fun hasAttr(key: String): Boolean = has(key)

    public fun hasAttr(key: String, snapshot: Snapshot): Boolean = has(key, snapshot)

    public fun hasAttribute(key: String): Boolean = hasAttr(key)

    public fun hasAttribute(key: String, snapshot: Snapshot): Boolean = hasAttr(key, snapshot)

    public fun delete(key: String) {
        if (isPreliminary) {
            preliminaryMap.remove(key)
            return
        }
        doc.deleteMapKey(name, key)
    }

    public fun deleteAttr(key: String) {
        delete(key)
    }

    public fun deleteAttribute(key: String) {
        deleteAttr(key)
    }

    public fun removeAttribute(key: String) {
        deleteAttr(key)
    }

    public fun clear() {
        if (isPreliminary) {
            preliminaryMap.clear()
            return
        }
        doc.transact {
            keys().forEach(::delete)
        }
    }

    public fun clearAttrs() {
        clear()
    }

    public fun applyDelta(delta: YMapDelta, origin: Any? = null) {
        if (!isPreliminary) {
            doc.preflightNestedValue(
                delta.ops.values.filter { op -> op.action == YMapDeltaAction.Set }.map { op -> op.value },
            )
        }
        doc.transact(origin = origin) {
            delta.ops.forEach { (key, op) ->
                when (op.action) {
                    YMapDeltaAction.Set -> set(key, op.value)
                    YMapDeltaAction.Delete -> delete(key)
                }
            }
        }
    }

    public fun applyDeltaDeep(delta: YMapDeepDelta, origin: Any? = null) {
        doc.transact(origin = origin) {
            clear()
            setAttrs(delta.attrs.fromDeepDeltaValues(doc))
        }
    }

    public fun toDelta(): YMapDelta {
        val delta = YMapDelta()
        toMap().forEach { (key, value) -> delta.setAttr(key, value) }
        return delta
    }

    public fun toDeltaDeep(renderer: AbstractRenderer = activeRenderer): YMapDeepDelta =
        renderMapDeepDelta(this, DeepDeltaRenderOptions(renderer = renderer))

    public fun keys(): Set<String> = toMap().keys

    public fun attrKeys(): Set<String> = keys()

    public fun values(): Collection<Any?> = toMap().values

    public fun attrValues(): Collection<Any?> = values()

    public fun entries(): Set<Map.Entry<String, Any?>> = toMap().entries

    public fun attrEntries(): Set<Map.Entry<String, Any?>> = entries()

    public fun forEach(action: (value: Any?, key: String) -> Unit) {
        toMap().forEach { (key, value) -> action(value, key) }
    }

    public fun forEach(action: (value: Any?, key: String, type: YMap) -> Unit) {
        toMap().forEach { (key, value) -> action(value, key, this) }
    }

    public fun <T> mapAttrs(transform: (value: Any?, key: String) -> T): List<T> {
        return toMap().map { (key, value) -> transform(value, key) }
    }

    public fun <T> mapAttrs(transform: (value: Any?, key: String, type: YMap) -> T): List<T> {
        return toMap().map { (key, value) -> transform(value, key, this) }
    }

    public fun forEachAttr(action: (value: Any?, key: String) -> Unit) {
        toMap().forEach { (key, value) -> action(value, key) }
    }

    public fun forEachAttr(action: (value: Any?, key: String, type: YMap) -> Unit) {
        toMap().forEach { (key, value) -> action(value, key, this) }
    }

    override fun iterator(): Iterator<Map.Entry<String, Any?>> = entries().iterator()

    public fun toMap(): Map<String, Any?> {
        if (warnIfPreliminary()) return emptyMap()
        return doc.visibleMap(name).mapValues { (_, value) -> doc.valueToAny(value) }
    }

    public fun toMap(snapshot: Snapshot): Map<String, Any?> =
        doc.mapAtSnapshot(this, snapshot).mapValues { (_, value) -> doc.valueToAny(value) }

    public fun getAttrs(): Map<String, Any?> = toMap()

    public fun getAttrs(snapshot: Snapshot): Map<String, Any?> = toMap(snapshot)

    override fun toJson(): Map<String, Any?> {
        if (warnIfPreliminary()) return emptyMap()
        return doc.visibleMap(name)
            .mapValues { (_, value) -> doc.valueToJson(value) }
            .inJavaScriptObjectKeyOrder()
    }

    override fun toJSON(): Map<String, Any?> = toMap()
        .mapValues { (_, value) -> toYTypeJsonValue(value) }
        .inJavaScriptObjectKeyOrder()

    public open fun clone(): YMap {
        return YMap().also { cloned ->
            cloned.setAttrs(toMap().mapValues { (_, value) -> value.cloneValueDetached() })
        }
    }

    public open fun clone(targetDoc: YDoc): YMap {
        return targetDoc.createMap().also { cloned ->
            cloned.setAttrs(toMap().mapValues { (_, value) -> value.cloneValueInto(targetDoc) })
        }
    }

    public companion object {
        public fun from(delta: YMapDelta, doc: YDoc = YDoc(), name: String = ""): YMap {
            return doc.getMap(name).also { it.applyDelta(delta) }
        }

        public fun from(delta: YMapDeepDelta, doc: YDoc = YDoc(), name: String = ""): YMap {
            return doc.getMap(name).also { it.applyDeltaDeep(delta) }
        }
    }
}

/**
 * JavaScript objects enumerate array-index property names before other strings. Y.Map itself
 * iterates its backing Map in insertion order, but Y.Map#toJSON returns a plain object.
 */
internal fun <T> Map<String, T>.inJavaScriptObjectKeyOrder(): Map<String, T> {
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
    is YXmlTextType -> clone(targetDoc)
    is YXmlHook -> clone(targetDoc)
    is YXmlElementType -> clone(targetDoc)
    is YXmlFragment -> clone(targetDoc)
    is YArray -> clone(targetDoc)
    is YMap -> clone(targetDoc)
    is YText -> clone(targetDoc)
    is List<*> -> map { it.cloneValueInto(targetDoc) }
    is Array<*> -> map { it.cloneValueInto(targetDoc) }
    is Map<*, *> -> entries.associateTo(linkedMapOf()) { (key, value) ->
        require(key is String) { "YValue map keys must be strings" }
        key to value.cloneValueInto(targetDoc)
    }
    is ByteArray -> copyOf()
    else -> this
}

internal fun Any?.cloneValueDetached(): Any? = when (this) {
    is YXmlTextType -> clone()
    is YXmlHook -> clone()
    is YXmlElementType -> clone()
    is YXmlFragment -> clone()
    is YArray -> clone()
    is YMap -> clone()
    is YText -> clone()
    is YXmlNode -> clone()
    is List<*> -> map { it.cloneValueDetached() }
    is Array<*> -> map { it.cloneValueDetached() }
    is Map<*, *> -> entries.associateTo(linkedMapOf()) { (key, value) ->
        require(key is String) { "YValue map keys must be strings" }
        key to value.cloneValueDetached()
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
