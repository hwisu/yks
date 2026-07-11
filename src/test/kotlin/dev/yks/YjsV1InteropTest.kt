package dev.yks

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("yjs-interop")
class YjsV1InteropTest {
    private val projectDirectory: Path = Path.of(System.getProperty("user.dir"))
    private val fixture: Path = projectDirectory.resolve("interop/yjs-v1/fixtures/hello-text-v1.bin")

    private fun fixture(name: String): ByteArray = Files.readAllBytes(
        projectDirectory.resolve("interop/yjs-v1/fixtures/$name.bin"),
    )

    @Test
    fun appliesHelloUpdateProducedByUpstreamYjs() {
        val doc = YDoc(clientId = 2)

        applyUpdate(doc, Files.readAllBytes(fixture))

        assertEquals("hello", doc.getText("body").toString())
        assertEquals(mapOf(1L to 5L), decodeStateVector(encodeStateVector(doc)))
    }

    @Test
    fun appliesPackedArrayAndBinaryProducedByUpstreamYjs() {
        val doc = YDoc(clientId = 2)

        applyUpdate(doc, fixture("array-v1"))

        val values = doc.getArray("items").toList()
        assertEquals(listOf("a", 42L, true, null), values.take(4))
        assertTrue((values[4] as ByteArray).contentEquals(byteArrayOf(1, 2)))
        assertEquals(mapOf(1L to 5L), decodeStateVector(encodeStateVector(doc)))
    }

    @Test
    fun appliesFullMapReplacementAndDeleteSetProducedByUpstreamYjs() {
        val doc = YDoc(clientId = 2, gc = false)

        applyUpdate(doc, fixture("map-full-v1"))

        assertEquals(mapOf("title" to "new"), doc.getMap("meta").toMap())
        assertTrue(doc.deleteSet().contains(Id(1, 0)))
    }

    @Test
    fun appliesGarbageCollectedMapReplacementProducedByUpstreamYjs() {
        val doc = YDoc(clientId = 2)

        applyUpdate(doc, fixture("map-full-gc-v1"))

        assertEquals(mapOf("title" to "new"), doc.getMap("meta").toMap())
    }

    @Test
    fun appliesMapReplacementAndExplicitDeleteProducedByUpstreamYjs() {
        val doc = YDoc(clientId = 2)

        applyUpdate(doc, fixture("map-delete-full-v1"))

        assertEquals(mapOf("title" to "new"), doc.getMap("meta").toMap())
    }

    @Test
    fun appliesIncrementalPackedArrayUpdatesInEitherOrder() {
        listOf(
            listOf("array-base-v1", "array-append-v1"),
            listOf("array-append-v1", "array-base-v1"),
        ).forEach { order ->
            val doc = YDoc(clientId = 2, gc = false)
            doc.getArray("numbers")
            order.forEach { name -> applyUpdate(doc, fixture(name)) }

            assertEquals(listOf(1L, 2L, 3L, 4L), doc.getArray("numbers").toList())
            assertEquals(null, doc.store.pendingStructs)
        }
    }

    @Test
    fun duplicatePendingUpdateIsIdempotent() {
        val doc = YDoc(clientId = 2, gc = false)
        doc.getArray("numbers")

        applyUpdate(doc, fixture("array-append-v1"))
        applyUpdate(doc, fixture("array-append-v1"))
        applyUpdate(doc, fixture("array-base-v1"))

        assertEquals(listOf(1L, 2L, 3L, 4L), doc.getArray("numbers").toList())
        assertEquals(null, doc.store.pendingStructs)
    }

    @Test
    fun appliesIncrementalMapReplacementInEitherOrder() {
        listOf(
            listOf("map-base-v1", "map-replace-v1"),
            listOf("map-replace-v1", "map-base-v1"),
        ).forEach { order ->
            val doc = YDoc(clientId = 2, gc = false)
            doc.getMap("meta")
            order.forEach { name -> applyUpdate(doc, fixture(name)) }

            assertEquals(mapOf("title" to "new"), doc.getMap("meta").toMap())
            assertEquals(null, doc.store.pendingStructs)
        }
    }

    @Test
    fun waitsForMissingSameClientClockWithoutAnchors() {
        val doc = YDoc(clientId = 2, gc = false)
        doc.getMap("meta")

        applyUpdate(doc, fixture("map-second-key-v1"))

        assertEquals(mapOf(1L to 0L), doc.store.pendingStructs?.missing)
        assertEquals(emptyMap(), decodeStateVector(doc.encodeStateVector()))

        applyUpdate(doc, fixture("map-first-key-v1"))

        assertEquals(mapOf("first" to 1L, "second" to 2L), doc.getMap("meta").toMap())
        assertEquals(null, doc.store.pendingStructs)
    }

    @Test
    fun appliesRightOriginInsertionInEitherOrder() {
        listOf(
            listOf("array-front-base-v1", "array-front-insert-v1"),
            listOf("array-front-insert-v1", "array-front-base-v1"),
        ).forEach { order ->
            val doc = YDoc(clientId = 2, gc = false)
            doc.getArray("letters")
            order.forEach { name -> applyUpdate(doc, fixture(name)) }

            assertEquals(listOf("x", "a", "b"), doc.getArray("letters").toList())
            assertEquals(null, doc.store.pendingStructs)
        }
    }

    @Test
    fun appliesInteriorOriginAndRightOriginInEitherOrder() {
        listOf(
            listOf("array-interior-base-v1", "array-interior-insert-v1"),
            listOf("array-interior-insert-v1", "array-interior-base-v1"),
        ).forEach { order ->
            val doc = YDoc(clientId = 2, gc = false)
            doc.getArray("letters")
            order.forEach { name -> applyUpdate(doc, fixture(name)) }

            assertEquals(listOf("a", "b", "X", "c"), doc.getArray("letters").toList())
            assertEquals(null, doc.store.pendingStructs)
        }
    }

    @Test
    fun appliesIncrementalNestedMapUpdateInEitherOrder() {
        listOf(
            listOf("nested-map-base-v1", "nested-map-city-v1"),
            listOf("nested-map-city-v1", "nested-map-base-v1"),
        ).forEach { order ->
            val doc = YDoc(clientId = 2, gc = false)
            doc.getMap("root")
            order.forEach { name -> applyUpdate(doc, fixture(name)) }

            val profile = doc.getMap("root").get("profile") as YMap
            assertEquals(mapOf("city" to "Seoul", "name" to "Ada"), profile.toMap())
            assertEquals(null, doc.store.pendingStructs)
        }
    }

    @Test
    fun appliesCrossClientNestedParentDependencyInEitherOrder() {
        listOf(
            listOf("nested-owner-v1", "nested-child-v1"),
            listOf("nested-child-v1", "nested-owner-v1"),
        ).forEach { order ->
            val doc = YDoc(clientId = 3, gc = false)
            doc.getMap("root")
            applyUpdate(doc, fixture(order.first()))
            if (order.first() == "nested-child-v1") {
                assertEquals(mapOf(1L to 0L), doc.store.pendingStructs?.missing)
                assertEquals(emptyMap(), decodeStateVector(doc.encodeStateVector()))
            }
            applyUpdate(doc, fixture(order.last()))

            val profile = doc.getMap("root").get("profile") as YMap
            assertEquals(mapOf("name" to "Ada"), profile.toMap())
            assertEquals(null, doc.store.pendingStructs)
        }
    }

    @Test
    fun childArrivingAfterDeletedParentIsDeletedToo() {
        val doc = YDoc(clientId = 3, gc = false)
        doc.getMap("root")

        applyUpdate(doc, fixture("nested-owner-v1"))
        applyUpdate(doc, fixture("nested-owner-delete-v1"))
        applyUpdate(doc, fixture("nested-child-v1"))

        assertEquals(emptyMap(), doc.getMap("root").toMap())
        assertTrue(doc.deleteSet().contains(Id(1, 0)))
        assertTrue(doc.deleteSet().contains(Id(2, 0)))
        assertEquals(mapOf(1L to 1L, 2L to 1L), decodeStateVector(doc.encodeStateVector()))
        assertEquals(null, doc.store.pendingStructs)
    }

    @Test
    fun appliesGcStructWithoutExposingSyntheticRoots() {
        val doc = YDoc(clientId = 2)

        applyUpdate(doc, fixture("gc-nested-delete-v1"))

        assertEquals(emptyList(), doc.getArray("gc-root").toList())
        assertEquals(setOf("gc-root"), doc.rootNames())
        assertEquals(mapOf(1L to 2L), decodeStateVector(doc.encodeStateVector()))
    }

    @Test
    fun skipDoesNotOwnClocksOrIntegrateFollowingStructs() {
        val doc = YDoc(clientId = 2, gc = false)

        applyUpdate(doc, fixture("skip-then-text-v1"))

        assertEquals("", doc.getText("body").toString())
        assertEquals(emptyMap(), decodeStateVector(doc.encodeStateVector()))
        assertEquals(mapOf(1L to 1L), doc.store.pendingStructs?.missing)
    }

    @Test
    fun gcOwnsClocksAndIntegratesFollowingStructs() {
        val doc = YDoc(clientId = 2, gc = false)

        applyUpdate(doc, fixture("gc-then-text-v1"))

        assertEquals("x", doc.getText("body").toString())
        assertEquals(mapOf(1L to 3L), decodeStateVector(doc.encodeStateVector()))
        assertEquals(null, doc.store.pendingStructs)
        assertEquals(setOf("body"), doc.rootNames())
    }

    @Test
    fun deletedTextContentDoesNotOverrideReplacementKind() {
        val doc = YDoc(clientId = 2)

        applyUpdate(doc, fixture("text-replace-full-v1"))

        assertEquals("new", doc.getText("body").toString())
        assertEquals(mapOf(1L to 6L), decodeStateVector(doc.encodeStateVector()))
        assertTrue(doc.deleteSet().contains(Id(1, 0)))
    }

    @Test
    fun contentTargetingGcParentBecomesGcWithoutSyntheticRoot() {
        val doc = YDoc(clientId = 3, gc = false)

        applyUpdate(doc, fixture("gc-then-text-v1"))
        applyUpdate(doc, fixture("nested-child-v1"))

        assertEquals("x", doc.getText("body").toString())
        assertEquals(setOf("body"), doc.rootNames())
        assertEquals(mapOf(1L to 3L, 2L to 1L), decodeStateVector(doc.encodeStateVector()))
        assertEquals(null, doc.store.pendingStructs)
    }

    @Test
    fun appliesNativeTextFormatMarkersProducedByUpstreamYjs() {
        val update = fixture("text-format-insert-v1")
        val doc = YDoc(clientId = 2, gc = false)

        applyUpdate(doc, update)

        assertEquals(
            YTextDelta().insert("ab", mapOf("bold" to true)),
            doc.getText("body").toDelta(),
        )
        assertEquals(mapOf(1L to 4L), decodeStateVector(doc.encodeStateVector()))
        assertEquals(
            listOf("bold" to true, "bold" to null),
            decodeUpdate(update).structs.mapNotNull { struct ->
                (struct.content as? ContentFormat)?.let { format -> format.key to format.value }
            },
        )
    }

    @Test
    fun appliesNativeFormatRemovalInEitherOrder() {
        listOf(
            listOf("text-format-insert-v1", "text-format-remove-v1"),
            listOf("text-format-remove-v1", "text-format-insert-v1"),
        ).forEach { order ->
            val doc = YDoc(clientId = 2, gc = false)
            order.forEach { name -> applyUpdate(doc, fixture(name)) }

            assertEquals(YTextDelta().insert("ab"), doc.getText("body").toDelta())
            assertEquals(mapOf(1L to 4L), decodeStateVector(doc.encodeStateVector()))
            assertEquals(null, doc.store.pendingDs)
        }
    }

    @Test
    fun appliesIncrementalNativeFormattingInEitherOrder() {
        listOf(
            listOf("text-format-base-v1", "text-format-partial-v1"),
            listOf("text-format-partial-v1", "text-format-base-v1"),
        ).forEach { order ->
            val doc = YDoc(clientId = 2, gc = false)
            applyUpdate(doc, fixture(order.first()))
            if (order.first() == "text-format-partial-v1") {
                assertEquals(mapOf(1L to 3L), doc.store.pendingStructs?.missing)
                assertEquals(emptyMap(), decodeStateVector(doc.encodeStateVector()))
            }
            applyUpdate(doc, fixture(order.last()))

            assertEquals(
                YTextDelta()
                    .insert("a")
                    .insert("bc", mapOf("bold" to true))
                    .insert("d"),
                doc.getText("body").toDelta(),
            )
            assertEquals(mapOf(1L to 6L), decodeStateVector(doc.encodeStateVector()))
            assertEquals(null, doc.store.pendingStructs)
        }
    }

    @Test
    fun nativeFormatOnlyUpdateEmitsFormattingDeltaAndPreservesSnapshots() {
        val doc = YDoc(clientId = 2, gc = false)
        val text = doc.getText("body")
        applyUpdate(doc, fixture("text-format-base-v1"))
        val before = snapshot(doc)
        var observed = YTextDelta()
        text.observe { event -> observed = event.textDelta }

        applyUpdate(doc, fixture("text-format-partial-v1"))
        val after = snapshot(doc)

        assertEquals(
            YTextDelta().retain(1).retain(2, mapOf("bold" to true)),
            observed,
        )
        assertEquals(YTextDelta().insert("abcd"), typeTextToDeltaSnapshot(text, before))
        assertEquals(
            YTextDelta()
                .insert("a")
                .insert("bc", mapOf("bold" to true))
                .insert("d"),
            typeTextToDeltaSnapshot(text, after),
        )
    }

    @Test
    fun appliesNativeFormattingToEmbed() {
        val doc = YDoc(clientId = 2, gc = false)

        applyUpdate(doc, fixture("text-format-embed-v1"))

        assertEquals(
            YTextDelta().insertEmbed(mapOf("image" to "x"), mapOf("bold" to true)),
            doc.getText("body").toDelta(),
        )
        assertEquals(mapOf(1L to 3L), decodeStateVector(doc.encodeStateVector()))
    }

    @Test
    fun nativeFormatMarkerRestoresPreviousNonNullValue() {
        val doc = YDoc(clientId = 2, gc = false)

        applyUpdate(doc, fixture("text-format-restoration-v1"))

        assertEquals(
            YTextDelta()
                .insert("a", mapOf("url" to "outer"))
                .insert("b", mapOf("url" to "inner"))
                .insert("c", mapOf("url" to "outer")),
            doc.getText("body").toDelta(),
        )
    }

    @Test
    fun upstreamYjsAppliesNativeFormattingRelayedByKotlin() {
        val doc = YDoc(clientId = 2, gc = false)
        applyUpdate(doc, fixture("text-format-insert-v1"))

        val update = encodeStateAsUpdate(doc)

        assertTrue(!update.copyOfRange(0, 4).contentEquals(byteArrayOf('Y'.code.toByte(), 'K'.code.toByte(), 'S'.code.toByte(), 1)))
        assertUpstreamAppliesUpdate(update, "formatted-text")
    }

    @Test
    fun upstreamYjsAppliesIncrementalNativeFormattingRelayedByKotlin() {
        val doc = YDoc(clientId = 2, gc = false)
        val baseline = fixture("text-format-base-v1")
        applyUpdate(doc, baseline)
        val baselineState = encodeStateVector(doc)
        applyUpdate(doc, fixture("text-format-partial-v1"))

        val incremental = encodeStateAsUpdate(doc, baselineState)

        assertTrue(
            !incremental.copyOfRange(0, 4)
                .contentEquals(byteArrayOf('Y'.code.toByte(), 'K'.code.toByte(), 'S'.code.toByte(), 1)),
        )
        assertUpstreamAppliesSequence("partial-formatted-text", baseline, incremental)
    }

    @Test
    fun legacyTransactionUpdatePreservesNativeFormatMarkers() {
        val source = YDoc(clientId = 2, gc = false)
        val updates = mutableListOf<ByteArray>()
        source.observeUpdates { update, _ -> updates.add(update) }

        applyUpdate(source, fixture("text-format-insert-v1"))

        val transactionUpdate = updates.single()
        assertTrue(transactionUpdate.copyOfRange(0, 4).contentEquals(byteArrayOf('Y'.code.toByte(), 'K'.code.toByte(), 'S'.code.toByte(), 1)))
        val target = YDoc(clientId = 3, gc = false)
        applyUpdate(target, transactionUpdate)
        assertEquals(source.getText("body").toDelta(), target.getText("body").toDelta())
    }

    @Test
    fun upstreamYjsAppliesHelloUpdateProducedByKotlin() {
        val doc = YDoc(clientId = 1)
        doc.getText("body").insert(0, "hello")

        assertUpstreamApplies(doc, "hello")
    }

    @Test
    fun upstreamYjsAppliesRootArrayUpdateProducedByKotlin() {
        val doc = YDoc(clientId = 1)
        doc.getArray("items").insert(0, listOf("a", 42, true, null, byteArrayOf(1, 2)))

        assertUpstreamApplies(doc, "array")
    }

    @Test
    fun upstreamYjsAppliesRootMapUpdateProducedByKotlin() {
        val doc = YDoc(clientId = 1)
        doc.getMap("meta").apply {
            set("title", "hello")
            set("count", 2)
        }

        assertUpstreamApplies(doc, "map")
    }

    @Test
    fun upstreamYjsAppliesNestedMapUpdateProducedByKotlin() {
        val doc = YDoc(clientId = 1)
        val profile = doc.createMap()
        doc.getMap("root").set("profile", profile)
        profile.set("name", "Ada")

        assertUpstreamApplies(doc, "nested-map")
    }

    @Test
    fun upstreamYjsAppliesNestedTextUpdateProducedByKotlin() {
        val doc = YDoc(clientId = 1)
        val text = doc.createText()
        doc.getArray("nodes").push(text)
        text.insert(0, "child")

        assertUpstreamApplies(doc, "nested-text")
    }

    @Test
    fun upstreamYjsAppliesIncrementalNestedMapUpdateProducedByKotlin() {
        val doc = YDoc(clientId = 1)
        val profile = doc.createMap()
        doc.getMap("root").set("profile", profile)
        profile.set("name", "Ada")
        val baseline = encodeStateAsUpdate(doc)
        val baselineState = encodeStateVector(doc)

        profile.set("city", "Seoul")
        val incremental = encodeStateAsUpdate(doc, baselineState)

        assertUpstreamAppliesSequence("nested-map-update", baseline, incremental)
    }

    private fun assertUpstreamApplies(doc: YDoc, scenario: String) {
        assertUpstreamAppliesUpdate(encodeStateAsUpdate(doc), scenario)
    }

    private fun assertUpstreamAppliesUpdate(bytes: ByteArray, scenario: String) {
        val update = Files.createTempFile("yks-$scenario-v1-", ".bin")

        try {
            Files.write(update, bytes)
            val process = ProcessBuilder(
                "node",
                "interop/yjs-v1/verify-update.mjs",
                update.absolutePathString(),
                scenario,
            )
                .directory(projectDirectory.toFile())
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            assertTrue(exitCode == 0, "upstream Yjs rejected the Kotlin update:\n$output")
        } finally {
            Files.deleteIfExists(update)
        }
    }

    private fun assertUpstreamAppliesSequence(scenario: String, vararg updates: ByteArray) {
        val paths = updates.mapIndexed { index, update ->
            Files.createTempFile("yks-$scenario-v1-$index-", ".bin").also { path -> Files.write(path, update) }
        }
        try {
            val process = ProcessBuilder(
                listOf(
                    "node",
                    "interop/yjs-v1/verify-update-sequence.mjs",
                    scenario,
                ) + paths.map(Path::absolutePathString),
            )
                .directory(projectDirectory.toFile())
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            assertTrue(exitCode == 0, "upstream Yjs rejected the Kotlin update sequence:\n$output")
        } finally {
            paths.forEach(Files::deleteIfExists)
        }
    }
}
