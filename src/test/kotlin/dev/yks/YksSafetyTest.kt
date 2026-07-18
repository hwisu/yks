package dev.yks

import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
    fun retainedShareViewCannotBypassDocumentThreadConfinement() {
        val source = YDoc(clientId = 1)
        source.getText("body").insert(0, "remote")
        val doc = YDoc(clientId = 2)
        doc.applyUpdate(source.encodeStateAsUpdate())
        val share = doc.share
        val failure = AtomicReference<Throwable?>()
        val worker = Thread(
            { failure.set(runCatching { share["body"] }.exceptionOrNull()) },
            "yks-share-foreign-thread",
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

    @Test
    fun uncheckedScalarReadCachesRemainMutationCoherent() {
        val doc = YDoc(
            YDocOptions(clientId = 1),
            YDocRuntimeOptions(threadAccessPolicy = YThreadAccessPolicy.UNCHECKED),
        )
        val array = doc.getArray("array")
        val text = doc.getText("text")
        array.push(1L, 2L)
        text.insert(0, "ab")

        assertEquals(2, array.length)
        assertEquals(1L, array.get(0))
        assertEquals(2, text.length)

        array.unshift(0L)
        text.insert(text.length, "c")
        assertEquals(3, array.length)
        assertEquals(0L, array.get(0))
        assertEquals(3, text.length)

        array.delete(0)
        text.delete(0, 1)
        assertEquals(2, array.length)
        assertEquals(1L, array.get(0))
        assertEquals(2, text.length)
    }

    @Test
    fun externallySerializedPolicyAllowsCoroutineStyleThreadHandoff() {
        val doc = YDoc(
            YDocOptions(clientId = 1),
            YDocRuntimeOptions(threadAccessPolicy = YThreadAccessPolicy.EXTERNALLY_SERIALIZED),
        )
        val failures = mutableListOf<Throwable>()
        val updates = mutableListOf<String>()
        doc.observeUpdates { _, origin -> updates.add(origin.toString()) }

        listOf<(YDoc) -> Unit>(
            { current -> current.getText("body").insert(0, "a") },
            { current -> current.getText("body").insert(1, "b") },
            { current -> current.encodeStateVector() },
            { current -> current.encodeStateAsUpdate() },
            { current -> snapshot(current) },
        ).forEachIndexed { index, operation ->
            val worker = Thread(
                { runCatching { operation(doc) }.exceptionOrNull()?.let(failures::add) },
                "yks-dispatcher-$index",
            )
            worker.start()
            worker.join()
        }

        assertTrue(failures.isEmpty(), failures.joinToString())
        assertEquals("ab", doc.getText("body").toString())
        assertEquals(2, updates.size)

        val destroyWorker = Thread(doc::destroy, "yks-destroy-dispatcher")
        destroyWorker.start()
        destroyWorker.join()
        assertTrue(doc.isDestroyed)
    }

    @Test
    fun externallySerializedPolicyRejectsOverlappingOperations() {
        val doc = YDoc(
            YDocOptions(clientId = 1),
            YDocRuntimeOptions(threadAccessPolicy = YThreadAccessPolicy.EXTERNALLY_SERIALIZED),
        )
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val firstFailure = AtomicReference<Throwable?>()
        val secondFailure = AtomicReference<Throwable?>()
        val active = Thread(
            {
                firstFailure.set(
                    runCatching {
                        doc.transact {
                            entered.countDown()
                            check(release.await(5, TimeUnit.SECONDS))
                            doc.getText("body").insert(0, "safe")
                        }
                    }.exceptionOrNull(),
                )
            },
            "yks-active-dispatcher",
        )
        val overlapping = Thread(
            {
                check(entered.await(5, TimeUnit.SECONDS))
                secondFailure.set(runCatching { doc.encodeStateAsUpdate() }.exceptionOrNull())
                release.countDown()
            },
            "yks-overlapping-dispatcher",
        )

        active.start()
        overlapping.start()
        active.join()
        overlapping.join()

        assertNull(firstFailure.get())
        val error = assertIs<YksConcurrentAccessException>(secondFailure.get())
        assertEquals(active.name, error.activeThreadName)
        assertEquals(overlapping.name, error.currentThreadName)
        assertEquals("safe", doc.getText("body").toString())
    }
}
