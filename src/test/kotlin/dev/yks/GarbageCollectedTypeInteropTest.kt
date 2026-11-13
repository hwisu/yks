package dev.yks

import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.io.path.absolutePathString
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** What each clock of an update holds: a GC struct, a deleted item, or live content. */
internal fun describeClocks(update: ByteArray): Map<Id, String> = buildMap {
    decodeUpdate(update).structs.forEach { struct ->
        val description = when {
            struct.parent.startsWith("__yjs_gc__:") -> "gc"
            struct.content is ContentDeleted -> "deleted item"
            else -> struct.content::class.simpleName.orEmpty()
        }
        for (offset in 0 until struct.length) put(Id(struct.id.client, struct.id.clock + offset), description)
    }
}

internal fun ByteArray.namesAnInternalType(): Boolean = String(this, Charsets.ISO_8859_1).contains("__yks")

@Tag("yjs-interop")
class GarbageCollectedTypeInteropTest {
    private val projectDirectory = Path.of(System.getProperty("user.dir"))

    @Test
    fun collectedNestedTypesEncodeTheStateUpstreamEncodes() {
        val rows = upstreamRows()
        assertTrue(rows.size >= 10, "expected every upstream shape, got ${rows.map { it.name }}")
        for (row in rows) {
            val doc = YDoc(clientId = 9, gc = true)
            row.updates.forEach { update -> applyUpdate(doc, update) }
            val v1 = encodeStateAsUpdate(doc)
            val v2AsV1 = convertUpdateFormatV2ToV1(encodeStateAsUpdateV2(doc))

            assertFalse(v1.namesAnInternalType(), row.name)
            assertFalse(encodeStateAsUpdateV2(doc).namesAnInternalType(), row.name)
            assertEquals(describeClocks(row.expected), describeClocks(v1), row.name)
            assertEquals(describeClocks(row.expected), describeClocks(v2AsV1), "${row.name} V2")
            // A GC struct that arrives after its neighbour was collected is kept as its own struct.
            if (row.name != "late-child") {
                assertContentEquals(row.expected, v1, row.name)
                assertContentEquals(row.expected, v2AsV1, "${row.name} V2")
            }

            val reloaded = YDoc(clientId = 10, gc = true)
            applyUpdate(reloaded, v1)
            assertEquals(describeClocks(row.expected), describeClocks(encodeStateAsUpdate(reloaded)), row.name)
            assertContentEquals(doc.encodeStateVector(), reloaded.encodeStateVector(), row.name)
        }
    }

    private class Row(val name: String, val updates: List<ByteArray>, val expected: ByteArray)

    private fun upstreamRows(): List<Row> {
        val output = Files.createTempFile("yks-gc-nested-types-", ".tsv")
        try {
            val process = ProcessBuilder(
                "node",
                "interop/yjs-v1/generate-gc-nested-types.mjs",
                output.absolutePathString(),
            )
                .directory(projectDirectory.toFile())
                .redirectErrorStream(true)
                .start()
            val log = process.inputStream.bufferedReader().readText()
            assertTrue(process.waitFor() == 0, "upstream generator failed:\n$log")
            val decoder = Base64.getDecoder()
            return Files.readAllLines(output).filter(String::isNotBlank).map { line ->
                val columns = line.split('\t')
                assertEquals(3, columns.size, "invalid row: $line")
                Row(columns[0], columns[1].split(',').map(decoder::decode), decoder.decode(columns[2]))
            }
        } finally {
            Files.deleteIfExists(output)
        }
    }
}
