package dev.yks

import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Verifies that strict update utilities remain closed over genuine upstream Yjs wire updates. */
class StandardUpdateClosureTest {
    private val fixtureDirectory = Path.of("interop/yjs-v1/fixtures")
    private val emptyStateVector = encodeStateVector(emptyMap())

    @Test
    fun `V1 utilities accept every upstream fixture`() {
        val failures = fixtures("-v1.bin").flatMap { path ->
            val update = Files.readAllBytes(path)
            val contentIds = createContentIdsFromUpdate(update)
            val operations = linkedMapOf<String, () -> Unit>(
                "merge-single" to { mergeUpdates(listOf(update)) },
                "diff-empty" to { diffUpdate(update, emptyStateVector) },
                "convert-identity" to { convertUpdateFormat(update) },
                "convert-explicit-identity" to { convertUpdateFormat(update) { it } },
                "convert-v2" to { convertUpdateFormatV1ToV2(update) },
                "obfuscate" to { obfuscateUpdate(update) },
                "intersect-full" to { intersectUpdateWithContentIds(update, contentIds) },
                "metadata" to { parseUpdateMeta(update) },
                "state-vector" to { encodeStateVectorFromUpdate(update) },
            )
            operationFailures(path, operations)
        }

        assertEquals(emptyList(), failures, failures.joinToString("\n"))
    }

    @Test
    fun `V2 utilities accept every upstream fixture`() {
        val failures = fixtures("-v2.bin").flatMap { path ->
            val update = Files.readAllBytes(path)
            val contentIds = createContentIdsFromUpdateV2(update)
            val operations = linkedMapOf<String, () -> Unit>(
                "merge-single" to { mergeUpdatesV2(listOf(update)) },
                "diff-empty" to { diffUpdateV2(update, emptyStateVector) },
                "convert-v1" to { convertUpdateFormatV2ToV1(update) },
                "obfuscate" to { obfuscateUpdateV2(update) },
                "intersect-full" to { intersectUpdateWithContentIdsV2(update, contentIds) },
                "metadata" to { parseUpdateMetaV2(update) },
                "state-vector" to { encodeStateVectorFromUpdateV2(update) },
            )
            operationFailures(path, operations)
        }

        assertEquals(emptyList(), failures, failures.joinToString("\n"))
    }

    @Test
    fun `format conversion preserves JSON undefined separately from null`() {
        val update = BinaryEncoder().also { encoder ->
            encoder.writeVarUInt(1)
            encoder.writeVarUInt(1)
            encoder.writeVarUInt(1)
            encoder.writeVarUInt(0)
            encoder.writeByte(contentJSONRefNumber)
            encoder.writeVarUInt(1)
            encoder.writeString("items")
            encoder.writeVarUInt(2)
            encoder.writeString("undefined")
            encoder.writeString("null")
            encoder.writeVarUInt(0)
        }.toByteArray()

        assertContentEquals(update, convertUpdateFormat(update))
        assertContentEquals(update, convertUpdateFormatV2ToV1(convertUpdateFormatV1ToV2(update)))
    }

    @Test
    @Tag("yjs-interop")
    fun `utility outputs retain upstream Yjs wire metadata`() {
        val rows = buildList {
            fixtures("-v1.bin").forEach { path ->
                val update = Files.readAllBytes(path)
                val ids = createContentIdsFromUpdate(update)
                addRow(path, "merge", "v1", update, "v1", mergeUpdates(listOf(update)))
                addRow(path, "diff", "v1", update, "v1", diffUpdate(update, emptyStateVector))
                addRow(path, "convert-v1", "v1", update, "v1", convertUpdateFormat(update))
                addRow(path, "convert-v2", "v1", update, "v2", convertUpdateFormatV1ToV2(update))
                addRow(path, "obfuscate", "v1", update, "v1", obfuscateUpdate(update))
                addRow(path, "intersect", "v1", update, "v1", intersectUpdateWithContentIds(update, ids))
            }
            fixtures("-v2.bin").forEach { path ->
                val update = Files.readAllBytes(path)
                val ids = createContentIdsFromUpdateV2(update)
                addRow(path, "merge", "v2", update, "v2", mergeUpdatesV2(listOf(update)))
                addRow(path, "diff", "v2", update, "v2", diffUpdateV2(update, emptyStateVector))
                addRow(path, "convert-v1", "v2", update, "v1", convertUpdateFormatV2ToV1(update))
                addRow(path, "obfuscate", "v2", update, "v2", obfuscateUpdateV2(update))
                addRow(path, "intersect", "v2", update, "v2", intersectUpdateWithContentIdsV2(update, ids))
            }
        }
        val process = ProcessBuilder("node", "interop/yjs-v1/verify-standard-utility-closure.mjs")
            .directory(Path.of(System.getProperty("yks.projectDirectory")).toFile())
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()
        process.outputStream.bufferedWriter().use { writer ->
            rows.forEach(writer::appendLine)
        }
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor() == 0, output)
    }

    private fun fixtures(suffix: String): List<Path> = Files.list(fixtureDirectory).use { paths ->
        paths
            .filter { path -> path.fileName.toString().endsWith(suffix) }
            .sorted()
            .toList()
    }

    private fun operationFailures(
        path: Path,
        operations: Map<String, () -> Unit>,
    ): List<String> = operations.mapNotNull { (name, operation) ->
        runCatching(operation)
            .exceptionOrNull()
            ?.let { failure -> "${path.fileName}:$name:${failure::class.simpleName}" }
    }

    private fun MutableList<String>.addRow(
        path: Path,
        operation: String,
        inputFormat: String,
        input: ByteArray,
        outputFormat: String,
        output: ByteArray,
    ) {
        add(
            listOf(
                path.fileName.toString(),
                operation,
                inputFormat,
                Base64.getEncoder().encodeToString(input),
                outputFormat,
                Base64.getEncoder().encodeToString(output),
            ).joinToString("\t"),
        )
    }
}
