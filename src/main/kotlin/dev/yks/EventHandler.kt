package dev.yks

public class EventHandler<ARG0, ARG1> {
    public val l: MutableList<(ARG0, ARG1) -> Unit> = mutableListOf()

    internal val listeners: MutableList<(ARG0, ARG1) -> Unit> get() = l
}

public fun <ARG0, ARG1> createEventHandler(): EventHandler<ARG0, ARG1> = EventHandler()

public fun <ARG0, ARG1> addEventHandlerListener(
    eventHandler: EventHandler<ARG0, ARG1>,
    listener: (ARG0, ARG1) -> Unit,
): Int {
    eventHandler.listeners.add(listener)
    return eventHandler.listeners.size
}

public fun <ARG0, ARG1> removeEventHandlerListener(
    eventHandler: EventHandler<ARG0, ARG1>,
    listener: (ARG0, ARG1) -> Unit,
): Boolean {
    val removed = eventHandler.listeners.removeAll { it === listener }
    if (!removed) {
        System.err.println("[yjs] Tried to remove event handler that doesn't exist.")
    }
    return removed
}

public fun <ARG0, ARG1> removeAllEventHandlerListeners(eventHandler: EventHandler<ARG0, ARG1>) {
    eventHandler.listeners.clear()
}

public fun <ARG0, ARG1> callEventHandlerListeners(
    eventHandler: EventHandler<ARG0, ARG1>,
    arg0: ARG0,
    arg1: ARG1,
) {
    callAllYksCallbacks(eventHandler.listeners.toList()) { listener -> listener(arg0, arg1) }
}
