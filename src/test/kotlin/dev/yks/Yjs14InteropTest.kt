package dev.yks

import dev.yks.experimental.v14.DeltaBuilder
import dev.yks.experimental.v14.DeltaValue
import dev.yks.experimental.v14.ExperimentalYjs14Api
import dev.yks.experimental.v14.asV14Type
import dev.yks.experimental.v14.getType
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
                val mixed = doc.getArray("mixed")
                val xml = doc.getXmlFragment("xml")
                val update = Base64.getDecoder().decode(encoded)
                if (format == "v1") doc.applyUpdate(update) else doc.applyUpdateV2(update)

                assertEquals(listOf(YTextDeltaOp(insert = "A😀한", attributes = mapOf("bold" to true))), body.toDelta().ops)
                assertEquals(listOf(1L, "x", true), items.toArray())
                assertEquals(mapOf("title" to "hello"), meta.toMap())
                assertEquals(listOf("A", null, 7L), mixed.toArray())
                assertEquals("<p id=\"intro\">hello</p>", xml.toString())

                val arrayProjectionDoc = YDoc(clientId = 3, gc = false)
                val arrayProjection = arrayProjectionDoc.getArray("body")
                if (format == "v1") arrayProjectionDoc.applyUpdate(update) else arrayProjectionDoc.applyUpdateV2(update)
                assertEquals("A😀한".map(Char::toString), arrayProjection.toArray())
                assertEquals("A😀한".length, arrayProjection.length)

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

    @OptIn(ExperimentalYjs14Api::class)
    @Test
    fun experimentalTypeFacadeRemainsBidirectionallyCompatibleWithYjs14() {
        val directory = Files.createTempDirectory("yks-yjs14-facade-")
        try {
            val source = YDoc(clientId = 14, gc = false)
            source.getType("body", RootKind.Text).applyDelta(
                DeltaBuilder().insert("A😀한", mapOf("bold" to YValue.Bool(true))).done(),
            )
            source.getType("items", RootKind.Array).applyDelta(
                DeltaBuilder().insertValues(
                    listOf(
                        DeltaValue.integer(1),
                        DeltaValue.text("x"),
                        DeltaValue.bool(true),
                    ),
                ).done(),
            )
            source.getType("meta", RootKind.Map).applyDelta(
                DeltaBuilder().setDataAttr("title", YValue.StringValue("hello")).done(),
            )
            source.getType("mixed", RootKind.Array).applyDelta(
                DeltaBuilder()
                    .insert("A")
                    .insertValues(listOf(DeltaValue.Data(YValue.Null), DeltaValue.integer(7)))
                    .done(),
            )
            source.getType("formatted", RootKind.Array).applyDelta(
                DeltaBuilder()
                    .insert("A", mapOf("bold" to YValue.Bool(true)))
                    .insertValue(
                        DeltaValue.integer(1),
                        mapOf("color" to YValue.StringValue("red")),
                    )
                    .done(),
            )
            val paragraph = YXmlElementType("p")
            source.getType("xml", RootKind.XmlFragment).applyDelta(
                DeltaBuilder().insertType(paragraph).done(),
            )
            paragraph.asV14Type().applyDelta(DeltaBuilder().insert("hello").done())

            Files.write(directory.resolve("facade-kotlin-v1.bin"), source.encodeStateAsUpdate())
            Files.write(directory.resolve("facade-kotlin-v2.bin"), source.encodeStateAsUpdateV2())
            runNode("interop/yjs-v14/verify-experimental-facade-updates.mjs", directory.toString())

            listOf("v1", "v2").forEach { format ->
                val target = YDoc(clientId = 2, gc = false)
                val body = target.getText("body")
                val items = target.getArray("items")
                val meta = target.getMap("meta")
                val xml = target.getXmlFragment("xml")
                val mixed = target.getArray("mixed")
                val formatted = target.getType("formatted", RootKind.Array)
                val update = Files.readAllBytes(directory.resolve("facade-yjs14-$format.bin"))
                if (format == "v1") target.applyUpdate(update) else target.applyUpdateV2(update)

                assertEquals("A!😀한", body.toString())
                assertEquals(listOf(1L, "y", true), items.toArray())
                assertEquals(mapOf("title" to "hello", "verified" to true), meta.toMap())
                assertEquals("<p>hello!</p>", xml.toString())
                assertEquals(listOf("A", null, 8L), mixed.toArray())
                assertEquals(listOf("A", 1L), formatted.delegate.toJson())
                assertEquals(
                    listOf(
                        dev.yks.experimental.v14.ChildOp.InsertText(
                            "A",
                            mapOf("italic" to YValue.Bool(true)),
                        ),
                        dev.yks.experimental.v14.ChildOp.InsertValues(
                            listOf(DeltaValue.integer(1)),
                            mapOf("color" to YValue.StringValue("blue")),
                        ),
                    ),
                    formatted.toDelta().children,
                )
            }
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
