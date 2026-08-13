package dev.yks

import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Tag

@Tag("yjs-interop")
class Yjs14InteropTest {
    private val projectDirectory: Path =
        Path.of(System.getProperty("yks.projectDirectory", System.getProperty("user.dir")))

    @Test
    fun yjs14V1AndV2UpdatesApplyInKotlin() {
        val corpus = Files.createTempFile("yks-yjs14-", ".tsv")
        val roundTripDirectory = Files.createTempDirectory("yks-yjs14-roundtrip-")
        try {
            runNode("interop/yjs-v14/generate-kotlin-fixtures.mjs", corpus.toString())
            Files.readAllLines(corpus).forEach { line ->
                val (format, encoded) = line.split('\t')
                val doc = YDoc(clientId = 2, gc = false)
                val body = doc.getText("body")
                val items = doc.getArray("items")
                val meta = doc.getMap("meta")
                val xml = doc.getXmlFragment("xml")
                val update = Base64.getDecoder().decode(encoded)
                if (format == "v1") doc.applyUpdate(update) else doc.applyUpdateV2(update)

                assertEquals(listOf(YTextDeltaOp(insert = "A😀한", attributes = mapOf("bold" to true))), body.toDelta().ops)
                assertEquals(listOf(1L, "x", true), items.toArray())
                assertEquals(mapOf("title" to "hello"), meta.toMap())
                assertEquals("<p id=\"intro\">hello</p>", xml.toString())

                val roundTrip = if (format == "v1") doc.encodeStateAsUpdate() else doc.encodeStateAsUpdateV2()
                Files.write(roundTripDirectory.resolve("roundtrip-$format.bin"), roundTrip)
            }
            runNode("interop/yjs-v14/verify-roundtrip-updates.mjs", roundTripDirectory.toString())
        } finally {
            corpus.deleteIfExists()
            Files.walk(roundTripDirectory).sorted(Comparator.reverseOrder()).forEach(Path::deleteIfExists)
        }
    }

    @Test
    fun kotlinV1AndV2UpdatesApplyInYjs14() {
        val directory = Files.createTempDirectory("yks-yjs14-out-")
        try {
            val source = YDoc(clientId = 13, gc = false)
            source.getText("body").insert(0, "B😀한", mapOf("italic" to true))
            source.getArray("items").push(2, "y", false)
            source.getMap("meta").set("title", "world")
            source.getXmlFragment("xml").push(
                YXmlElementType("q").also { element -> element.setAttr("id", "outro") },
            )
            Files.write(directory.resolve("kotlin-v1.bin"), source.encodeStateAsUpdate())
            Files.write(directory.resolve("kotlin-v2.bin"), source.encodeStateAsUpdateV2())

            runNode("interop/yjs-v14/verify-kotlin-updates.mjs", directory.toString())
        } finally {
            Files.walk(directory).sorted(Comparator.reverseOrder()).forEach(Path::deleteIfExists)
        }
    }

    @Test
    fun installedYjs14OracleMatchesTheExactAliasPin() {
        val output = runNode("interop/yjs-v14/assert-yjs-version.mjs")
        assertTrue(output.isBlank(), output)
        val packageJson = projectDirectory.resolve("node_modules/yjs14/package.json").toFile().readText()
        val installed = checkNotNull(Regex("\\\"version\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").find(packageJson)).groupValues[1]
        assertEquals("14.0.0-rc.24", installed)
    }

    private fun runNode(vararg arguments: String): String {
        val process = ProcessBuilder(listOf("node") + arguments)
            .directory(projectDirectory.toFile())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), output)
        return output
    }
}
