package dev.yks.awareness

import dev.yks.BinaryEncoder
import dev.yks.YDoc
import dev.yks.YValue
import dev.yks.YksDecodingException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AwarenessTest {
    @Test
    fun localStateUsesTypedChangeAndHeartbeatUpdateSemantics() {
        val clock = MutableClock(1_000)
        val awareness = awareness(1, clock)
        val changes = mutableListOf<AwarenessEvent>()
        val updates = mutableListOf<AwarenessEvent>()
        awareness.onChange(changes::add)
        awareness.onUpdate(updates::add)

        assertEquals(AwarenessState.EMPTY, awareness.getLocalState())
        assertEquals(0L, awareness.getMeta().getValue(1).clock)

        awareness.setLocalState(AwarenessState.EMPTY)
        assertTrue(changes.isEmpty())
        assertEquals(AwarenessClientChanges(updated = listOf(1)), updates.single().changes)

        val state = state("Ada")
        awareness.setLocalState(state)
        assertEquals(AwarenessClientChanges(updated = listOf(1)), changes.single().changes)
        assertEquals(2L, awareness.getMeta().getValue(1).clock)

        awareness.setLocalState(null)
        assertNull(awareness.getLocalState())
        assertEquals(AwarenessClientChanges(removed = listOf(1)), changes.last().changes)
        assertEquals(3L, awareness.getMeta().getValue(1).clock)
        awareness.close()
    }

    @Test
    fun remoteClockOrderingMatchesYProtocolsIncludingLocalRemovalDefense() {
        val source = awareness(1, MutableClock(100))
        val target = awareness(2, MutableClock(200))
        val changes = mutableListOf<AwarenessEvent>()
        val updates = mutableListOf<AwarenessEvent>()
        target.onChange(changes::add)
        target.onUpdate(updates::add)

        source.setLocalState(state("Ada"))
        val first = source.encodeUpdate()
        target.applyUpdate(first, ProviderOrigin)
        assertEquals(state("Ada"), target.getStates()[1])
        assertEquals(AwarenessClientChanges(added = listOf(1)), changes.single().changes)

        target.applyUpdate(first, ProviderOrigin)
        assertEquals(1, changes.size)
        assertEquals(1, updates.size)

        source.setLocalState(state("Ada"))
        target.applyUpdate(source.encodeUpdate(), ProviderOrigin)
        assertEquals(1, changes.size)
        assertEquals(AwarenessClientChanges(updated = listOf(1)), updates.last().changes)

        source.setLocalState(null)
        target.applyUpdate(source.encodeUpdate(listOf(1)), ProviderOrigin)
        assertFalse(1L in target.getStates())
        assertEquals(AwarenessClientChanges(removed = listOf(1)), changes.last().changes)

        val localRemoval = BinaryEncoder().apply {
            writeVarUInt(1)
            writeVarUInt(2)
            writeVarUInt(target.getMeta().getValue(2).clock)
            writeString("null")
        }.toByteArray()
        target.applyUpdate(localRemoval, ProviderOrigin)
        assertEquals(AwarenessState.EMPTY, target.getLocalState())
        assertEquals(1L, target.getMeta().getValue(2).clock)
        assertEquals(AwarenessClientChanges(removed = listOf(2)), changes.last().changes)

        source.close()
        target.close()
    }

    @Test
    fun timeoutAndHeartbeatUseTheUpstreamThirtySecondContract() {
        val clock = MutableClock(0)
        val source = awareness(1, clock)
        val target = awareness(2, clock)
        source.setLocalState(state("remote"))
        target.applyUpdate(source.encodeUpdate(), ProviderOrigin)
        val changes = mutableListOf<AwarenessEvent>()
        val updates = mutableListOf<AwarenessEvent>()
        target.onChange(changes::add)
        target.onUpdate(updates::add)

        clock.now = 15_000
        target.runMaintenance()
        assertEquals(1L, target.getMeta().getValue(2).clock)
        assertEquals(AwarenessClientChanges(updated = listOf(2)), updates.single().changes)
        assertTrue(1L in target.getStates())

        clock.now = 30_000
        target.runMaintenance()
        assertFalse(1L in target.getStates())
        assertEquals(AwarenessOrigin.Timeout, changes.single().origin)
        assertEquals(AwarenessClientChanges(removed = listOf(1)), changes.single().changes)

        source.close()
        target.close()
    }

    @Test
    fun modifyUpdatePreservesClientAndClockWhileChangingTypedState() {
        val source = awareness(3, MutableClock(0))
        source.setLocalState(state("before"))

        val modified = modifyAwarenessUpdate(source.encodeUpdate()) { current ->
            current?.withField("name", YValue.StringValue("after"))
        }
        val target = awareness(4, MutableClock(0))
        target.applyUpdate(modified, ProviderOrigin)

        assertEquals(YValue.StringValue("after"), target.getStates().getValue(3)["name"])
        assertEquals(1L, target.getMeta().getValue(3).clock)
        source.close()
        target.close()
    }

    @Test
    fun malformedUpdatesAreRejectedBeforeAnyStateMutation() {
        val target = awareness(5, MutableClock(0))
        val malformed = BinaryEncoder().apply {
            writeVarUInt(2)
            writeVarUInt(7)
            writeVarUInt(1)
            writeString("{\"name\":\"Ada\"}")
        }.toByteArray()

        assertFailsWith<YksDecodingException> {
            target.applyUpdate(malformed, ProviderOrigin)
        }
        assertEquals(setOf(5L), target.getStates().keys)
        target.close()
    }

    @Test
    fun awarenessStateRejectsNonJsonYValues() {
        assertFailsWith<IllegalArgumentException> {
            AwarenessState("binary" to YValue.BinaryValue(byteArrayOf(1)))
        }
        assertFailsWith<IllegalArgumentException> {
            AwarenessState("undefined" to YValue.Undefined)
        }
    }

    @Test
    fun destroyingTheDocumentDestroysAwarenessAndEmitsTheOfflineUpdate() {
        val doc = YDoc(clientId = 8)
        val awareness = Awareness(doc, AwarenessOptions(autoStart = false))
        val order = mutableListOf<String>()
        awareness.onDestroy { order.add("destroy") }
        awareness.onUpdate { event ->
            if (event.changes.removed == listOf(8L)) order.add("offline")
        }

        doc.destroy()

        assertEquals(listOf("destroy", "offline"), order)
        assertFailsWith<IllegalStateException> { awareness.setLocalState(AwarenessState.EMPTY) }
    }

    private fun awareness(clientId: Long, clock: MutableClock): Awareness = Awareness(
        YDoc(clientId = clientId),
        AwarenessOptions(clock = clock, autoStart = false),
    )

    private fun state(name: String): AwarenessState = AwarenessState(
        "name" to YValue.StringValue(name),
        "cursor" to YValue.ListValue(listOf(YValue.LongNumber(1), YValue.LongNumber(2))),
        "active" to YValue.Bool(true),
    )

    private class MutableClock(var now: Long) : AwarenessClock {
        override fun nowMillis(): Long = now
    }

    private data object ProviderOrigin : AwarenessOrigin
}
