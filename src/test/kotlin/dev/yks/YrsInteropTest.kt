package dev.yks

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@Tag("yrs-interop")
class YrsInteropTest {
    private val projectDirectory: Path = Path.of(
        System.getProperty("yks.projectDirectory") ?: System.getProperty("user.dir"),
    )
    private val fixtureDirectory: Path = projectDirectory.resolve("interop/yrs-oracle/fixtures")

    @Test
    fun appliesCommittedYrsUnicodeDeleteFixturesInCausalAndDeleteFirstOrder() {
        assertUnicodeDeleteFixture(
            base = fixture("text-base-v1"),
            deletion = fixture("text-delete-v1"),
            apply = ::applyUpdate,
        )
        assertUnicodeDeleteFixture(
            base = fixture("text-base-v2"),
            deletion = fixture("text-delete-v2"),
            apply = ::applyUpdateV2,
        )
    }

    @Test
    fun appliesCommittedYrsHighClientScalarAndNestedFixtures() {
        val highClient = YDoc(clientId = 2, gc = false)
        highClient.getText("body")
        applyUpdate(highClient, fixture("high-client-v1"))
        assertEquals("high-client", highClient.getText("body").toString())
        assertEquals(
            mapOf(HIGH_CLIENT_ID to 11L),
            decodeStateVector(encodeStateVector(highClient)),
        )

        val scalar = YDoc(clientId = 2, gc = false)
        scalar.getArray("items")
        scalar.getMap("meta")
        applyUpdate(scalar, fixture("array-map-v1"))

        val items = scalar.getArray("items").toList()
        assertEquals(listOf("a", 42L, true, null), items.take(4))
        assertContentEquals(byteArrayOf(1, 2), assertIs<ByteArray>(items[4]))
        assertEquals("hello", scalar.getMap("meta").get("title"))
        assertEquals(2L, scalar.getMap("meta").get("count"))

        val nested = YDoc(clientId = 2, gc = false)
        nested.getMap("root")
        applyUpdate(nested, fixture("nested-map-v1"))
        val profile = assertIs<YMap>(nested.getMap("root").get("profile"))
        assertEquals("Ada", profile.get("name"))
        assertEquals("Seoul", profile.get("city"))
    }

    @Test
    fun appliesCommittedYrsConcurrentArrayFixturesInEveryDeliveryOrder() {
        val updates = listOf(
            fixture("concurrent-array-base-v1"),
            fixture("concurrent-array-x-v1"),
            fixture("concurrent-array-y-v1"),
        )

        permutations(updates).forEach { order ->
            val doc = YDoc(clientId = 9, gc = false)
            doc.getArray("letters")
            order.forEach { update -> applyUpdate(doc, update) }

            assertEquals(listOf("a", "X", "Y", "b"), doc.getArray("letters").toList())
            assertEquals(
                mapOf(1L to 2L, 2L to 1L, 3L to 1L),
                decodeStateVector(encodeStateVector(doc)),
            )
            assertEquals(null, doc.store.pendingStructs)
        }
    }

    @Test
    fun fillsTheMiddleOfCommittedYrsSkipFixturesWithoutLosingBlocks() {
        val causal = listOf(
            "skip-middle-anchor-v1",
            "skip-middle-c0-v1",
            "skip-middle-c1-v1",
            "skip-middle-c2-v1",
            "skip-middle-c3-v1",
        )
        val skipInducing = listOf(
            "skip-middle-anchor-v1",
            "skip-middle-c0-v1",
            "skip-middle-c3-v1",
            "skip-middle-c2-v1",
            "skip-middle-c1-v1",
        )

        listOf(causal, skipInducing).forEach { order ->
            val doc = YDoc(clientId = 2, gc = false)
            doc.getText("t")
            order.forEach { name -> applyUpdate(doc, fixture(name)) }

            assertEquals("PcabQd", doc.getText("t").toString())
            assertEquals(null, doc.store.pendingStructs)
        }
    }

    @Test
    fun appliesRawYrsPartialSkipRegressionVectors() {
        // Yrs 0.27.2 `apply_update_filling_partial_skip` regression vectors.
        val updates = listOf(
            bytes(1, 1, 182, 144, 197, 137, 4, 0, 4, 1, 1, 116, 1, 109, 0),
            bytes(1, 1, 152, 176, 234, 156, 14, 3, 132, 152, 176, 234, 156, 14, 0, 1, 99, 0),
            bytes(0, 1, 152, 176, 234, 156, 14, 1, 2, 1),
            bytes(1, 1, 152, 176, 234, 156, 14, 0, 4, 1, 1, 116, 1, 112, 0),
            bytes(1, 1, 152, 176, 234, 156, 14, 1, 68, 152, 176, 234, 156, 14, 0, 1, 100, 0),
            bytes(
                1, 1, 152, 176, 234, 156, 14, 2, 196, 152, 176, 234, 156, 14, 1, 152, 176, 234,
                156, 14, 0, 1, 110, 0,
            ),
            bytes(1, 1, 182, 144, 197, 137, 4, 1, 132, 182, 144, 197, 137, 4, 0, 1, 100, 0),
        )
        val doc = YDoc(clientId = 2, gc = false)
        doc.getText("t")

        updates.forEach { update -> applyUpdate(doc, update) }

        assertEquals("mddpc", doc.getText("t").toString())
        assertEquals(
            mapOf(1_093_748_790L to 2L, 3_818_559_512L to 4L),
            decodeStateVector(encodeStateVector(doc)),
        )
        assertEquals(null, doc.store.pendingStructs)
        assertEquals(null, doc.store.pendingDs)
    }

    @Test
    fun preservesRawYrsPendingDeleteSetsUntilTheirDependenciesArrive() {
        // Yrs 0.27.2 `apply_update_pending_delete_set_not_lost` regression vectors.
        val insertBar = bytes(1, 1, 174, 156, 239, 251, 3, 0, 4, 1, 1, 116, 1, 124, 0)
        val insertG = bytes(1, 1, 174, 156, 239, 251, 3, 1, 68, 174, 156, 239, 251, 3, 0, 1, 71, 0)
        val deleteG = bytes(0, 1, 174, 156, 239, 251, 3, 1, 1, 1)
        val deleteBar = bytes(0, 1, 174, 156, 239, 251, 3, 1, 0, 1)

        listOf(
            listOf(insertBar, insertG, deleteG, deleteBar),
            listOf(insertG, deleteG, deleteBar, insertBar),
            listOf(deleteG, insertG, deleteBar, insertBar),
        ).forEach { updates ->
            val doc = YDoc(clientId = 2, gc = false)
            doc.getText("t")
            updates.forEach { update -> applyUpdate(doc, update) }
            assertEquals("", doc.getText("t").toString())
            assertEquals(null, doc.store.pendingDs)
        }
    }

    @Test
    fun yrsAppliesKotlinGeneratedFixtureBundle(@TempDir outputDirectory: Path) {
        writeKotlinFixtureBundle(outputDirectory)

        val manifest = projectDirectory.resolve("interop/yrs-oracle/Cargo.toml")
        assertTrue(Files.isRegularFile(manifest), "Yrs oracle manifest is missing: $manifest")
        val process = ProcessBuilder(
            "cargo",
            "run",
            "--locked",
            "--quiet",
            "--manifest-path",
            manifest.absolutePathString(),
            "--",
            "verify-kotlin",
            outputDirectory.absolutePathString(),
        )
            .directory(projectDirectory.toFile())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }

        assertEquals(0, process.waitFor(), "Yrs rejected the Kotlin fixture bundle:\n$output")
    }

    private fun assertUnicodeDeleteFixture(
        base: ByteArray,
        deletion: ByteArray,
        apply: (YDoc, ByteArray, Any?) -> Unit,
    ) {
        val causal = YDoc(clientId = 2, gc = false)
        causal.getText("body")
        apply(causal, base, null)
        assertEquals("A😀BC", causal.getText("body").toString())
        apply(causal, deletion, null)
        assertEquals("A😀C", causal.getText("body").toString())
        assertEquals(mapOf(1L to 5L), decodeStateVector(encodeStateVector(causal)))
        assertEquals(null, causal.store.pendingDs)

        val deleteFirst = YDoc(clientId = 3, gc = false)
        deleteFirst.getText("body")
        apply(deleteFirst, deletion, null)
        assertEquals("", deleteFirst.getText("body").toString())
        assertTrue(deleteFirst.store.pendingDs != null)
        apply(deleteFirst, base, null)
        assertEquals("A😀C", deleteFirst.getText("body").toString())
        assertEquals(mapOf(1L to 5L), decodeStateVector(encodeStateVector(deleteFirst)))
        assertEquals(null, deleteFirst.store.pendingDs)
    }

    private fun writeKotlinFixtureBundle(outputDirectory: Path) {
        val text = YDoc(clientId = 1, gc = false)
        text.getText("body").insert(0, "A😀BC")
        val textState = encodeStateVector(text)
        write(outputDirectory, "text-base-v1", encodeStateAsUpdate(text))
        write(outputDirectory, "text-base-v2", encodeStateAsUpdateV2(text))
        text.getText("body").delete(3, 1)
        write(outputDirectory, "text-delete-v1", encodeStateAsUpdate(text, textState))
        write(outputDirectory, "text-delete-v2", encodeStateAsUpdateV2(text, textState))

        val highClient = YDoc(clientId = HIGH_CLIENT_ID, gc = false)
        highClient.getText("body").insert(0, "high-client")
        write(outputDirectory, "high-client-v1", encodeStateAsUpdate(highClient))

        val scalar = YDoc(clientId = 1, gc = false)
        scalar.getArray("items").insert(0, listOf("a", 42, true, null, byteArrayOf(1, 2)))
        scalar.getMap("meta").apply {
            set("title", "hello")
            set("count", 2)
        }
        write(outputDirectory, "array-map-v1", encodeStateAsUpdate(scalar))

        val nested = YDoc(clientId = 1, gc = false)
        val profile = nested.createMap()
        nested.getMap("root").set("profile", profile)
        profile.set("name", "Ada")
        profile.set("city", "Seoul")
        write(outputDirectory, "nested-map-v1", encodeStateAsUpdate(nested))

        val base = YDoc(clientId = 1, gc = false)
        base.getArray("letters").insert(0, listOf("a", "b"))
        val baseUpdate = encodeStateAsUpdate(base)
        val baseState = encodeStateVector(base)
        write(outputDirectory, "concurrent-array-base-v1", baseUpdate)

        val x = YDoc(clientId = 2, gc = false)
        x.getArray("letters")
        applyUpdate(x, baseUpdate)
        x.getArray("letters").insert(1, listOf("X"))
        write(outputDirectory, "concurrent-array-x-v1", encodeStateAsUpdate(x, baseState))

        val y = YDoc(clientId = 3, gc = false)
        y.getArray("letters")
        applyUpdate(y, baseUpdate)
        y.getArray("letters").insert(1, listOf("Y"))
        write(outputDirectory, "concurrent-array-y-v1", encodeStateAsUpdate(y, baseState))
    }

    private fun fixture(name: String): ByteArray = Files.readAllBytes(fixtureDirectory.resolve("$name.bin"))

    private fun write(directory: Path, name: String, update: ByteArray) {
        Files.write(directory.resolve("$name.bin"), update)
    }

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { index -> values[index].toByte() }

    private fun <T> permutations(values: List<T>): List<List<T>> = when {
        values.size <= 1 -> listOf(values)
        else -> values.flatMapIndexed { index, value ->
            permutations(values.filterIndexed { candidateIndex, _ -> candidateIndex != index })
                .map { rest -> listOf(value) + rest }
        }
    }

    private companion object {
        const val HIGH_CLIENT_ID = 9_007_199_254_740_000L
    }
}
