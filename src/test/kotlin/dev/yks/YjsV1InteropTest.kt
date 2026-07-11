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
        val update = Files.createTempFile("yks-hello-v1-", ".bin")

        try {
            Files.write(update, encodeStateAsUpdate(doc))
            val process = ProcessBuilder(
                "node",
                "interop/yjs-v1/verify-update.mjs",
                update.absolutePathString(),
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
}
