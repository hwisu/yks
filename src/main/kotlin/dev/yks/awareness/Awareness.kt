package dev.yks.awareness

import dev.yks.BinaryDecoder
import dev.yks.BinaryEncoder
import dev.yks.Subscription
import dev.yks.YDoc
import dev.yks.YValue
import dev.yks.buildDecodedList
import dev.yks.callAllYksCallbacks
import dev.yks.decodeBoundary
import dev.yks.parseJsonLiteral
import dev.yks.toDecodedCount
import dev.yks.toJsonLiteral
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

public const val AWARENESS_OUTDATED_TIMEOUT_MILLIS: Long = 30_000L

/** Source-compatible name used by `y-protocols/awareness`. */
public const val outdatedTimeout: Long = AWARENESS_OUTDATED_TIMEOUT_MILLIS

private const val AWARENESS_HEARTBEAT_MILLIS: Long = AWARENESS_OUTDATED_TIMEOUT_MILLIS / 2
private const val AWARENESS_CHECK_INTERVAL_MILLIS: Long = AWARENESS_OUTDATED_TIMEOUT_MILLIS / 10
private const val MAX_SAFE_JAVASCRIPT_INTEGER: Long = 9_007_199_254_740_991L

/** Wall-clock input for awareness freshness checks, expressed as Unix milliseconds. */
public fun interface AwarenessClock {
    public fun nowMillis(): Long

    public companion object {
        @JvmField
        public val SYSTEM: AwarenessClock = AwarenessClock(System::currentTimeMillis)
    }
}

/** Runtime timing policy. Defaults match `y-protocols/awareness` 1.0.7. */
public data class AwarenessOptions(
    val outdatedTimeoutMillis: Long = AWARENESS_OUTDATED_TIMEOUT_MILLIS,
    val heartbeatMillis: Long = AWARENESS_HEARTBEAT_MILLIS,
    val checkIntervalMillis: Long = AWARENESS_CHECK_INTERVAL_MILLIS,
    val clock: AwarenessClock = AwarenessClock.SYSTEM,
    val autoStart: Boolean = true,
) {
    init {
        require(outdatedTimeoutMillis > 0) { "awareness timeout must be positive" }
        require(heartbeatMillis in 1..outdatedTimeoutMillis) {
            "awareness heartbeat must be positive and no greater than the timeout"
        }
        require(checkIntervalMillis > 0) { "awareness check interval must be positive" }
    }
}

/** Typed event origin. Provider-specific origins can implement this interface. */
public interface AwarenessOrigin {
    public data object Local : AwarenessOrigin
    public data object Timeout : AwarenessOrigin
}

/** Immutable JSON-object state carried by the Awareness protocol. */
public class AwarenessState(fields: Map<String, YValue> = emptyMap()) {
    public val fields: Map<String, YValue> = Collections.unmodifiableMap(
        LinkedHashMap(fields.mapValues { (key, value) -> copyJsonValue(value, key) }),
    )

    public constructor(vararg fields: Pair<String, YValue>) : this(linkedMapOf(*fields))

    public operator fun get(field: String): YValue? = fields[field]

    public fun withField(field: String, value: YValue): AwarenessState =
        AwarenessState(LinkedHashMap(fields).also { next -> next[field] = value })

    public fun withoutField(field: String): AwarenessState =
        AwarenessState(LinkedHashMap(fields).also { next -> next.remove(field) })

    public fun toMap(): Map<String, YValue> = fields

    public fun toJSON(): Map<String, Any?> = fields.mapValues { (_, value) -> value.toAny() }

    override fun equals(other: Any?): Boolean = other is AwarenessState && fields == other.fields

    override fun hashCode(): Int = fields.hashCode()

    override fun toString(): String = "AwarenessState(fields=$fields)"

    public companion object {
        @JvmField
        public val EMPTY: AwarenessState = AwarenessState()
    }
}

public data class AwarenessClientMeta(
    val clock: Long,
    val lastUpdatedMillis: Long,
) {
    init {
        require(clock in 0..MAX_SAFE_JAVASCRIPT_INTEGER) { "awareness clock is outside the JavaScript safe range" }
    }
}

public data class AwarenessClientChanges(
    val added: List<Long> = emptyList(),
    val updated: List<Long> = emptyList(),
    val removed: List<Long> = emptyList(),
) {
    public val isEmpty: Boolean get() = added.isEmpty() && updated.isEmpty() && removed.isEmpty()
}

public data class AwarenessEvent(
    val changes: AwarenessClientChanges,
    val origin: AwarenessOrigin,
)

/**
 * JVM implementation of `y-protocols/awareness` 1.0.7.
 *
 * Awareness is ephemeral and uses a separate wire format from Yjs document updates. Public state
 * snapshots are immutable; mutations and timer maintenance are serialized internally.
 */
public class Awareness(
    public val doc: YDoc,
    public val options: AwarenessOptions = AwarenessOptions(),
) : AutoCloseable {
    public val clientId: Long = doc.clientId.also(::requireSafeClientId)
    public val clientID: Long get() = clientId

    private val lock = Any()
    private val statesByClient = linkedMapOf<Long, AwarenessState>()
    private val metaByClient = linkedMapOf<Long, AwarenessClientMeta>()
    private val changeListeners = mutableListOf<(AwarenessEvent) -> Unit>()
    private val updateListeners = mutableListOf<(AwarenessEvent) -> Unit>()
    private val destroyListeners = mutableListOf<(Awareness) -> Unit>()
    private val docDestroySubscription: Subscription
    private var maintenanceFuture: ScheduledFuture<*>? = null
    private var destroying = false
    private var destroyed = false

    init {
        require(!doc.isDestroyed) { "cannot create Awareness for a destroyed YDoc" }
        docDestroySubscription = doc.onDoc("destroy") { destroy(closeDocSubscription = false) }
        setLocalState(AwarenessState.EMPTY)
        if (options.autoStart) {
            maintenanceFuture = AwarenessScheduler.schedule(options.checkIntervalMillis, ::runMaintenance)
        }
    }

    public fun getLocalState(): AwarenessState? = synchronized(lock) { statesByClient[clientId] }

    public fun getStates(): Map<Long, AwarenessState> = synchronized(lock) {
        Collections.unmodifiableMap(LinkedHashMap(statesByClient))
    }

    public fun getMeta(): Map<Long, AwarenessClientMeta> = synchronized(lock) {
        Collections.unmodifiableMap(LinkedHashMap(metaByClient))
    }

    public fun setLocalState(state: AwarenessState?) {
        emit(setLocalStateLocked(state, AwarenessOrigin.Local))
    }

    public fun setLocalStateField(field: String, value: YValue) {
        val current = getLocalState() ?: return
        setLocalState(current.withField(field, value))
    }

    public fun removeLocalStateField(field: String) {
        val current = getLocalState() ?: return
        setLocalState(current.withoutField(field))
    }

    public fun onChange(listener: (AwarenessEvent) -> Unit): Subscription = addListener(changeListeners, listener)

    public fun onUpdate(listener: (AwarenessEvent) -> Unit): Subscription = addListener(updateListeners, listener)

    public fun onDestroy(listener: (Awareness) -> Unit): Subscription = synchronized(lock) {
        check(!destroyed) { "Awareness is destroyed" }
        destroyListeners.add(listener)
        Subscription { synchronized(lock) { destroyListeners.remove(listener) } }
    }

    public fun encodeUpdate(clients: Collection<Long> = getStates().keys): ByteArray =
        encodeAwarenessUpdate(this, clients)

    public fun applyUpdate(update: ByteArray, origin: AwarenessOrigin) {
        applyAwarenessUpdate(this, update, origin)
    }

    public fun removeStates(clients: Collection<Long>, origin: AwarenessOrigin) {
        removeAwarenessStates(this, clients, origin)
    }

    override fun close() {
        destroy(closeDocSubscription = true)
    }

    public fun destroy() {
        close()
    }

    internal fun metaFor(clientId: Long): AwarenessClientMeta? = synchronized(lock) { metaByClient[clientId] }

    internal fun applyEntries(entries: List<AwarenessWireEntry>, origin: AwarenessOrigin) {
        val pending = synchronized(lock) {
            check(!destroyed) { "Awareness is destroyed" }
            val timestamp = options.clock.nowMillis()
            val added = mutableListOf<Long>()
            val updated = mutableListOf<Long>()
            val changedUpdated = mutableListOf<Long>()
            val removed = mutableListOf<Long>()
            entries.forEach { entry ->
                val clientMeta = metaByClient[entry.clientId]
                val previousState = statesByClient[entry.clientId]
                val currentClock = clientMeta?.clock ?: 0L
                if (
                    currentClock < entry.clock ||
                    (currentClock == entry.clock && entry.state == null && entry.clientId in statesByClient)
                ) {
                    var appliedClock = entry.clock
                    if (entry.state == null) {
                        if (entry.clientId == clientId && statesByClient[clientId] != null) {
                            appliedClock = incrementClock(appliedClock)
                        } else {
                            statesByClient.remove(entry.clientId)
                        }
                    } else {
                        statesByClient[entry.clientId] = entry.state
                    }
                    metaByClient[entry.clientId] = AwarenessClientMeta(appliedClock, timestamp)
                    when {
                        clientMeta == null && entry.state != null -> added.add(entry.clientId)
                        clientMeta != null && entry.state == null -> removed.add(entry.clientId)
                        entry.state != null -> {
                            if (entry.state != previousState) changedUpdated.add(entry.clientId)
                            updated.add(entry.clientId)
                        }
                    }
                }
            }
            val change = AwarenessClientChanges(added, changedUpdated, removed)
            val update = AwarenessClientChanges(added, updated, removed)
            PendingEvents(
                change = change.takeUnless(AwarenessClientChanges::isEmpty)?.let { AwarenessEvent(it, origin) },
                update = update.takeUnless(AwarenessClientChanges::isEmpty)?.let { AwarenessEvent(it, origin) },
            )
        }
        emit(pending)
    }

    internal fun runMaintenance() {
        if (synchronized(lock) { destroyed }) return
        val now = options.clock.nowMillis()
        val localState = getLocalState()
        val localMeta = metaFor(clientId)
        if (localState != null && localMeta != null && now - localMeta.lastUpdatedMillis >= options.heartbeatMillis) {
            setLocalState(localState)
        }
        val staleClients = synchronized(lock) {
            metaByClient.entries.asSequence()
                .filter { (id, meta) ->
                    id != clientId && id in statesByClient && now - meta.lastUpdatedMillis >= options.outdatedTimeoutMillis
                }
                .map(Map.Entry<Long, AwarenessClientMeta>::key)
                .toList()
        }
        if (staleClients.isNotEmpty()) removeStates(staleClients, AwarenessOrigin.Timeout)
    }

    private fun setLocalStateLocked(state: AwarenessState?, origin: AwarenessOrigin): PendingEvents {
        val pending = synchronized(lock) {
            check(!destroyed) { "Awareness is destroyed" }
            setLocalStateWhileOpen(state, origin)
        }
        return pending
    }

    private fun setLocalStateWhileOpen(state: AwarenessState?, origin: AwarenessOrigin): PendingEvents {
        val previousMeta = metaByClient[clientId]
        val clock = previousMeta?.clock?.let(::incrementClock) ?: 0L
        val previousState = statesByClient[clientId]
        if (state == null) statesByClient.remove(clientId) else statesByClient[clientId] = state
        metaByClient[clientId] = AwarenessClientMeta(clock, options.clock.nowMillis())
        val changes = when {
            state == null -> AwarenessClientChanges(removed = listOf(clientId))
            previousState == null -> AwarenessClientChanges(added = listOf(clientId))
            state != previousState -> AwarenessClientChanges(updated = listOf(clientId))
            else -> AwarenessClientChanges()
        }
        val update = when {
            state == null -> AwarenessClientChanges(removed = listOf(clientId))
            previousState == null -> AwarenessClientChanges(added = listOf(clientId))
            else -> AwarenessClientChanges(updated = listOf(clientId))
        }
        return PendingEvents(
            change = changes.takeUnless(AwarenessClientChanges::isEmpty)?.let { AwarenessEvent(it, origin) },
            update = AwarenessEvent(update, origin),
        )
    }

    private fun removeStatesLocked(clients: Collection<Long>, origin: AwarenessOrigin): PendingEvents = synchronized(lock) {
        check(!destroyed) { "Awareness is destroyed" }
        val removed = mutableListOf<Long>()
        clients.forEach { id ->
            requireSafeClientId(id)
            if (statesByClient.remove(id) != null) {
                if (id == clientId) {
                    val current = checkNotNull(metaByClient[id]) { "local awareness metadata is missing" }
                    metaByClient[id] = AwarenessClientMeta(incrementClock(current.clock), options.clock.nowMillis())
                }
                removed.add(id)
            }
        }
        val changes = AwarenessClientChanges(removed = removed)
        val event = changes.takeUnless(AwarenessClientChanges::isEmpty)?.let { AwarenessEvent(it, origin) }
        PendingEvents(change = event, update = event)
    }

    private fun <T> addListener(listeners: MutableList<(T) -> Unit>, listener: (T) -> Unit): Subscription =
        synchronized(lock) {
            check(!destroyed) { "Awareness is destroyed" }
            listeners.add(listener)
            Subscription { synchronized(lock) { listeners.remove(listener) } }
        }

    private fun emit(pending: PendingEvents) {
        pending.change?.let { event ->
            val listeners = synchronized(lock) { changeListeners.toList() }
            callAllYksCallbacks(listeners) { listener -> listener(event) }
        }
        pending.update?.let { event ->
            val listeners = synchronized(lock) { updateListeners.toList() }
            callAllYksCallbacks(listeners) { listener -> listener(event) }
        }
    }

    private fun destroy(closeDocSubscription: Boolean) {
        val listeners = synchronized(lock) {
            if (destroyed || destroying) return
            destroying = true
            destroyListeners.toList()
        }
        try {
            callAllYksCallbacks(listeners) { listener -> listener(this) }
            emit(setLocalStateLocked(null, AwarenessOrigin.Local))
        } finally {
            synchronized(lock) {
                destroyed = true
                destroying = false
                maintenanceFuture?.cancel(false)
                maintenanceFuture = null
                changeListeners.clear()
                updateListeners.clear()
                destroyListeners.clear()
            }
            if (closeDocSubscription) docDestroySubscription.close()
        }
    }

    internal fun removeStatesInternal(clients: Collection<Long>, origin: AwarenessOrigin) {
        emit(removeStatesLocked(clients, origin))
    }
}

public fun encodeAwarenessUpdate(
    awareness: Awareness,
    clients: Collection<Long> = awareness.getStates().keys,
    states: Map<Long, AwarenessState> = awareness.getStates(),
): ByteArray {
    val encoder = BinaryEncoder()
    encoder.writeVarUInt(clients.size.toLong())
    clients.forEach { clientId ->
        requireSafeClientId(clientId)
        val meta = checkNotNull(awareness.metaFor(clientId)) { "missing awareness metadata for client $clientId" }
        encoder.writeVarUInt(clientId)
        encoder.writeVarUInt(meta.clock)
        encoder.writeString(encodeAwarenessState(states[clientId]))
    }
    return encoder.toByteArray()
}

public fun applyAwarenessUpdate(awareness: Awareness, update: ByteArray, origin: AwarenessOrigin) {
    awareness.applyEntries(decodeAwarenessEntries(update), origin)
}

public fun removeAwarenessStates(
    awareness: Awareness,
    clients: Collection<Long>,
    origin: AwarenessOrigin,
) {
    awareness.removeStatesInternal(clients, origin)
}

public fun modifyAwarenessUpdate(
    update: ByteArray,
    modify: (AwarenessState?) -> AwarenessState?,
): ByteArray {
    val entries = decodeAwarenessEntries(update)
    val encoder = BinaryEncoder()
    encoder.writeVarUInt(entries.size.toLong())
    entries.forEach { entry ->
        encoder.writeVarUInt(entry.clientId)
        encoder.writeVarUInt(entry.clock)
        encoder.writeString(encodeAwarenessState(modify(entry.state)))
    }
    return encoder.toByteArray()
}

internal data class AwarenessWireEntry(
    val clientId: Long,
    val clock: Long,
    val state: AwarenessState?,
)

private data class PendingEvents(
    val change: AwarenessEvent?,
    val update: AwarenessEvent?,
)

private object AwarenessScheduler {
    private val executor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "yks-awareness").apply { isDaemon = true }
    }

    fun schedule(intervalMillis: Long, action: () -> Unit): ScheduledFuture<*> =
        executor.scheduleWithFixedDelay(action, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS)
}

private fun decodeAwarenessEntries(update: ByteArray): List<AwarenessWireEntry> =
    decodeBoundary("Yjs awareness update") {
        val decoder = BinaryDecoder(update)
        val count = decoder.readVarUInt().toDecodedCount("awareness client count", update.size)
        buildDecodedList(count) {
            val clientId = decoder.readVarUInt().also(::requireSafeClientId)
            val clock = decoder.readVarUInt().also(::requireSafeClock)
            AwarenessWireEntry(clientId, clock, decodeAwarenessState(decoder.readString()))
        }.also {
            check(!decoder.hasRemaining()) { "awareness update has trailing bytes" }
        }
    }

private fun encodeAwarenessState(state: AwarenessState?): String =
    toJsonLiteral(state?.toJSON())

private fun decodeAwarenessState(encoded: String): AwarenessState? {
    val decoded = parseJsonLiteral(encoded) ?: return null
    check(decoded is Map<*, *>) { "awareness state must be a JSON object or null" }
    return AwarenessState(decoded.entries.associate { (key, value) ->
        check(key is String) { "awareness state keys must be strings" }
        key to YValue.from(value)
    })
}

private fun copyJsonValue(value: YValue, path: String): YValue = when (value) {
    YValue.Null -> YValue.Null
    is YValue.Bool -> value
    is YValue.LongNumber -> value.also {
        require(it.value in -MAX_SAFE_JAVASCRIPT_INTEGER..MAX_SAFE_JAVASCRIPT_INTEGER) {
            "awareness JSON integer at '$path' is outside the JavaScript safe range"
        }
    }
    is YValue.DoubleNumber -> value.also {
        require(it.value.isFinite()) { "awareness JSON number at '$path' must be finite" }
    }
    is YValue.StringValue -> value
    is YValue.ListValue -> YValue.ListValue(
        Collections.unmodifiableList(value.value.mapIndexed { index, nested ->
            copyJsonValue(nested, "$path[$index]")
        }),
    )
    is YValue.MapValue -> YValue.MapValue(
        Collections.unmodifiableMap(LinkedHashMap(value.value.mapValues { (key, nested) ->
            copyJsonValue(nested, "$path.$key")
        })),
    )
    YValue.Undefined,
    is YValue.BigIntNumber,
    is YValue.BinaryValue,
    is YValue.SubdocRef,
    is YValue.TypeRef -> throw IllegalArgumentException("awareness field '$path' is not JSON-compatible")
}

private fun requireSafeClientId(clientId: Long) {
    require(clientId in 0..MAX_SAFE_JAVASCRIPT_INTEGER) {
        "awareness client id is outside the JavaScript safe range: $clientId"
    }
}

private fun requireSafeClock(clock: Long) {
    require(clock in 0..MAX_SAFE_JAVASCRIPT_INTEGER) {
        "awareness clock is outside the JavaScript safe range: $clock"
    }
}

private fun incrementClock(clock: Long): Long = (clock + 1).also(::requireSafeClock)
