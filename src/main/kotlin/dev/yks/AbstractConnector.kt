package dev.yks

/**
 * Minimal JVM counterpart of Yjs' experimental `AbstractConnector`.
 *
 * Providers can extend this class to expose a common document/awareness pair and use the
 * lightweight observable surface for provider-specific events.
 */
public open class AbstractConnector(
    public val doc: YDoc,
    public val awareness: Any?,
) : AutoCloseable {
    private val listeners = linkedMapOf<String, MutableList<(Any?) -> Unit>>()

    public fun on(eventName: String, listener: (Any?) -> Unit): Subscription {
        listeners.getOrPut(eventName) { mutableListOf() }.add(listener)
        return Subscription { off(eventName, listener) }
    }

    public fun once(eventName: String, listener: (Any?) -> Unit): Subscription {
        lateinit var subscription: Subscription
        val onceListener: (Any?) -> Unit = { value ->
            subscription.close()
            listener(value)
        }
        subscription = on(eventName, onceListener)
        return subscription
    }

    public fun off(eventName: String, listener: (Any?) -> Unit) {
        val eventListeners = listeners[eventName] ?: return
        eventListeners.remove(listener)
        if (eventListeners.isEmpty()) listeners.remove(eventName)
    }

    public fun emit(eventName: String, value: Any? = null) {
        callAllYksCallbacks(listeners[eventName].orEmpty().toList()) { listener -> listener(value) }
    }

    override fun close() {
        listeners.clear()
    }

    public fun destroy() {
        close()
    }
}
