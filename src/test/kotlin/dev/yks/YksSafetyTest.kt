package dev.yks

import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class YksSafetyTest {
    @Test
    fun malformedExternalBytesUseStableDecodingExceptions() {
        val updateError = assertFailsWith<YksDecodingException> {
            YDoc(clientId = 2).applyUpdate(byteArrayOf(0x80.toByte()))
        }

        assertEquals("Yjs update V1", updateError.format)
        assertNotNull(updateError.cause)

        val stateVectorError = assertFailsWith<YksDecodingException> {
            decodeStateVector(byteArrayOf(0x80.toByte()))
        }

        assertEquals("Yjs state vector", stateVectorError.format)
        assertNotNull(stateVectorError.cause)
    }

    @Test
    fun encodedByteLimitRejectsBeforeDocumentMutation() {
        val source = YDoc(clientId = 1)
        source.getText("body").insert(0, "payload")
        val update = source.encodeStateAsUpdate()
        val target = YDoc(
            YDocOptions(clientId = 2),
            YDocRuntimeOptions(
                updateLimits = YUpdateLimits(maxEncodedBytes = update.size - 1),
            ),
        )

        val error = assertFailsWith<YksUpdateLimitException> { target.applyUpdate(update) }

        assertEquals("encoded byte size", error.limit)
        assertEquals(update.size.toLong(), error.actual)
        assertTrue(target.rootNames().isEmpty())
        assertEquals(emptyMap(), decodeStateVector(target.encodeStateVector()))
    }

    @Test
    fun structLimitRejectsV1AndV2BeforeDocumentMutation() {
        val source = YDoc(clientId = 1)
        val text = source.getText("body")
        text.insert(0, "a")
        source.clientId = 2
        text.insert(text.length, "b")
        val limits = YUpdateLimits(maxStructs = 1)

        listOf(
            source.encodeStateAsUpdate() to false,
            source.encodeStateAsUpdateV2() to true,
        ).forEach { (update, isV2) ->
            val target = YDoc(
                YDocOptions(clientId = 3),
                YDocRuntimeOptions(updateLimits = limits),
            )

            val error = assertFailsWith<YksUpdateLimitException> {
                if (isV2) target.applyUpdateV2(update) else target.applyUpdate(update)
            }

            assertEquals("struct count", error.limit)
            assertEquals(1L, error.maximum)
            assertEquals(2L, error.actual)
            assertTrue(target.rootNames().isEmpty())
            assertEquals(emptyMap(), decodeStateVector(target.encodeStateVector()))
        }
    }

    @Test
    fun deleteRangeLimitRejectsV1AndV2BeforePendingStateMutation() {
        val deleteSet = DeleteSet.empty().also { ranges ->
            ranges.add(Id(1, 0), 1)
            ranges.add(Id(1, 2), 1)
        }
        val decoded = DocumentUpdate(emptyList(), deleteSet)
        val limits = YUpdateLimits(maxDeleteRanges = 1)

        listOf(
            UpdateCodec.encode(decoded) to false,
            UpdateCodec.encodeV2(decoded) to true,
        ).forEach { (update, isV2) ->
            val target = YDoc(
                YDocOptions(clientId = 3),
                YDocRuntimeOptions(updateLimits = limits),
            )

            val error = assertFailsWith<YksUpdateLimitException> {
                if (isV2) target.applyUpdateV2(update) else target.applyUpdate(update)
            }

            assertEquals("delete range count", error.limit)
            assertEquals(1L, error.maximum)
            assertEquals(2L, error.actual)
            assertNull(target.store.pendingDs)
            assertEquals(emptyMap(), decodeStateVector(target.encodeStateVector()))
        }
    }

    @Test
    fun updateLimitConfigurationRejectsInvalidValues() {
        assertFailsWith<IllegalArgumentException> { YUpdateLimits(maxEncodedBytes = 0) }
        assertFailsWith<IllegalArgumentException> { YUpdateLimits(maxStructs = 0) }
        assertFailsWith<IllegalArgumentException> { YUpdateLimits(maxDeleteRanges = 0) }
        assertFailsWith<IllegalArgumentException> {
            YUpdateLimits(maxStructs = MAX_DECODED_COLLECTION_SIZE + 1)
        }
        assertFailsWith<IllegalArgumentException> {
            YUpdateLimits(maxDeleteRanges = MAX_DECODED_COLLECTION_SIZE + 1)
        }
    }

    @Test
    fun documentBindsToItsFirstAccessThreadByDefault() {
        val doc = YDoc(clientId = 1)
        val text = doc.getText("body")
        text.insert(0, "bound")
        val failure = AtomicReference<Throwable?>()
        val worker = Thread(
            { failure.set(runCatching { text.length }.exceptionOrNull()) },
            "yks-foreign-thread",
        )

        worker.start()
        worker.join()

        val error = assertIs<YksThreadConfinementException>(failure.get())
        assertEquals(Thread.currentThread().name, error.ownerThreadName)
        assertEquals(worker.name, error.currentThreadName)
    }

    @Test
    fun uncheckedPolicyAllowsCallerSerializedThreadHandoff() {
        val doc = YDoc(
            YDocOptions(clientId = 1),
            YDocRuntimeOptions(
                threadAccessPolicy = YThreadAccessPolicy.UNCHECKED,
            ),
        )
        val text = doc.getText("body")
        text.insert(0, "a")
        val failure = AtomicReference<Throwable?>()
        val worker = Thread(
            { failure.set(runCatching { text.insert(text.length, "b") }.exceptionOrNull()) },
            "yks-serialized-thread",
        )

        worker.start()
        worker.join()

        assertNull(failure.get())
        assertEquals("ab", text.toString())
    }
}
