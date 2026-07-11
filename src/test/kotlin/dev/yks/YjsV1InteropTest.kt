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

    @Test
    fun appliesHelloUpdateProducedByUpstreamYjs() {
        val doc = YDoc(clientId = 2)

        applyUpdate(doc, Files.readAllBytes(fixture))

        assertEquals("hello", doc.getText("body").toString())
        assertEquals(mapOf(1L to 5L), decodeStateVector(encodeStateVector(doc)))
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
        val update = Files.createTempFile("yks-$scenario-v1-", ".bin")

        try {
            Files.write(update, encodeStateAsUpdate(doc))
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
