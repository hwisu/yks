package dev.yks

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class SnapshotWireSafetyTest {
    @Test
    fun stateVectorWritersRejectValuesOutsideTheJavaScriptSafeIntegerRange() {
        val unsafe = YJS_MAX_SAFE_INTEGER + 1

        assertFailsWith<IllegalArgumentException> {
            encodeStateVector(mapOf(1L to unsafe))
        }
        assertFailsWith<IllegalArgumentException> {
            encodeStateVector(mapOf(unsafe to 1L))
        }
        assertFailsWith<IllegalArgumentException> {
            encodeStateVectorV2(mapOf(1L to unsafe))
        }
    }

    @Test
    fun snapshotWritersRejectUnsafeDeleteSetCoordinatesAndRangeEnds() {
        val unsafeClient = createIdSet().also { it.add(YJS_MAX_SAFE_INTEGER + 1, 0, 1) }
        val unsafeClock = createIdSet().also { it.add(1, YJS_MAX_SAFE_INTEGER + 1, 1) }
        val unsafeEnd = createIdSet().also { it.add(1, YJS_MAX_SAFE_INTEGER, 1) }

        listOf(unsafeClient, unsafeClock, unsafeEnd).forEach { deleteSet ->
            val snapshot = createSnapshot(deleteSet, emptyMap())
            assertFailsWith<IllegalArgumentException> { encodeSnapshot(snapshot) }
            assertFailsWith<IllegalArgumentException> { encodeSnapshotV2(snapshot) }
        }
    }

    @Test
    fun snapshotValidatesItsStateVectorBeforeWritingToACallerProvidedEncoder() {
        val encoder = IdSetEncoderV2()
        val snapshot = createSnapshot(createIdSet(), mapOf(1L to YJS_MAX_SAFE_INTEGER + 1))

        assertFailsWith<IllegalArgumentException> { encodeSnapshotV2(snapshot, encoder) }

        assertContentEquals(ByteArray(0), encoder.toByteArray())
    }

    @Test
    fun standaloneIdSetWriterUsesTheSameWireSafetyGate() {
        val unsafeEnd = createIdSet().also { it.add(1, YJS_MAX_SAFE_INTEGER, 1) }

        assertFailsWith<IllegalArgumentException> { encodeIdSet(unsafeEnd) }
    }
}
