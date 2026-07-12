package dev.yks

/**
 * Minimal JVM counterpart of Yjs' experimental `AbstractConnector`.
 *
 * Providers can extend this class to expose a common document/awareness pair and use the
 * lightweight observable surface for provider-specific events.
 */
open class AbstractConnector(
    val doc: YDoc,
    val awareness: Any?,
) : AutoCloseable {
    private val listeners = linkedMapOf<String, MutableList<(Any?) -> Unit>>()

    fun on(eventName: String, listener: (Any?) -> Unit): Subscription {
        listeners.getOrPut(eventName) { mutableListOf() }.add(listener)
        return Subscription { off(eventName, listener) }
    }

    fun once(eventName: String, listener: (Any?) -> Unit): Subscription {
        lateinit var subscription: Subscription
        val onceListener: (Any?) -> Unit = { value ->
            subscription.close()
            listener(value)
        }
        subscription = on(eventName, onceListener)
        return subscription
    }

    fun off(eventName: String, listener: (Any?) -> Unit) {
        val eventListeners = listeners[eventName] ?: return
        eventListeners.remove(listener)
        if (eventListeners.isEmpty()) listeners.remove(eventName)
    }

    fun emit(eventName: String, value: Any? = null) {
        callAllYksCallbacks(listeners[eventName].orEmpty().toList().map { listener -> { listener(value) } })
    }

    override fun close() {
        listeners.clear()
    }

    fun destroy() {
        close()
    }
}
