package dev.yks

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EventHandlerTest {
    @Test
    fun eventHandlerCanBeConstructedDirectly() {
        val handler = EventHandler<String, Int>()
        val calls = mutableListOf<String>()

        addEventHandlerListener(handler) { label, count -> calls.add("$label:$count") }
        callEventHandlerListeners(handler, "direct", 1)

        assertEquals(listOf("direct:1"), calls)
    }

    @Test
    fun eventHandlerAddsCallsAndRemovesListenersInOrder() {
        val handler = createEventHandler<String, Int>()
        val calls = mutableListOf<String>()
        val first: (String, Int) -> Unit = { label, count -> calls.add("first:$label:$count") }
        val second: (String, Int) -> Unit = { label, count -> calls.add("second:$label:$count") }

        assertEquals(1, addEventHandlerListener(handler, first))
        assertEquals(2, addEventHandlerListener(handler, second))
        assertEquals(listOf(first, second), handler.l)
        callEventHandlerListeners(handler, "tick", 1)

        assertEquals(listOf("first:tick:1", "second:tick:1"), calls)
        assertTrue(removeEventHandlerListener(handler, first))
        assertFalse(removeEventHandlerListener(handler, first))
        assertEquals(listOf(second), handler.l)

        calls.clear()
        callEventHandlerListeners(handler, "tock", 2)

        assertEquals(listOf("second:tock:2"), calls)
    }

    @Test
    fun eventHandlerCanRemoveAllListeners() {
        val handler = createEventHandler<Unit, Unit>()
        var calls = 0

        addEventHandlerListener(handler) { _, _ -> calls++ }
        addEventHandlerListener(handler) { _, _ -> calls++ }
        removeAllEventHandlerListeners(handler)
        callEventHandlerListeners(handler, Unit, Unit)

        assertEquals(0, calls)
    }

    @Test
    fun eventHandlerCallsAllListenersAndRethrowsFirstFailure() {
        val handler = createEventHandler<String, String>()
        val calls = mutableListOf<String>()

        addEventHandlerListener(handler) { _, _ -> calls.add("before") }
        addEventHandlerListener(handler) { _, _ -> error("first failure") }
        addEventHandlerListener(handler) { left, right -> calls.add("$left:$right") }
        addEventHandlerListener(handler) { _, _ -> error("second failure") }

        val thrown = assertFailsWith<IllegalStateException> {
            callEventHandlerListeners(handler, "a", "b")
        }

        assertEquals("first failure", thrown.message)
        assertEquals(1, thrown.suppressed.size)
        assertEquals("second failure", thrown.suppressed.single().message)
        assertEquals(listOf("before", "a:b"), calls)
    }
}
