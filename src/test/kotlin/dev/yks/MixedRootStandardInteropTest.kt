package dev.yks

import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

class MixedRootStandardInteropTest {
    @Test
    fun `strict documents relay legacy mixed roots as genuine upstream Yjs updates`() {
        val inputs = generateUpdates()
        val initial = inputs.first()
        val incremental = inputs.drop(1).dropLast(1)
        val full = inputs.last()
        val scenarios = listOf(
            listOf(initial) + incremental,
            listOf(full),
            listOf(mergeUpdates(listOf(full, initial) + incremental + full)),
        )

        scenarios.forEachIndexed { index, updates ->
            val document = strictDocument(9_100L + index)
            val relayed = mutableListOf<ByteArray>()
            document.observeUpdates { update, _ -> relayed += update }

            updates.forEach { update -> document.applyUpdate(update, origin = "legacy-load") }
            updates.asReversed().forEach { update -> document.applyUpdate(update, origin = "duplicate") }

            assertTrue(relayed.isNotEmpty())
            val mirror = YDoc(clientId = 9_200L + index, gc = false)
            relayed.forEach(mirror::applyUpdate)
            assertContentEquals(document.encodeStateVector(), mirror.encodeStateVector())
            verifyWithUpstream(mergeUpdates(relayed), "relay-$index")
            verifyWithUpstream(document.encodeStateAsUpdate(), "snapshot-$index")
            mirror.destroy()
            document.destroy()
        }
    }

    private fun strictDocument(clientId: Long): YDoc = YDoc(
        YDocOptions(clientId = clientId, gc = false),
        YDocRuntimeOptions(standardUpdatePolicy = YStandardUpdatePolicy.REQUIRE_STANDARD),
    )

    private fun generateUpdates(): List<ByteArray> {
        val process = ProcessBuilder(
            "node",
            "--no-warnings",
            "interop/yjs-v1/generate-mixed-root-updates.mjs",
        )
            .directory(Path.of(System.getProperty("user.dir")).toFile())
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()
        val lines = process.inputStream.bufferedReader().readLines()
        assertTrue(process.waitFor() == 0, lines.joinToString("\n"))
        return lines.filter(String::isNotBlank).map(Base64.getDecoder()::decode)
    }

    private fun verifyWithUpstream(update: ByteArray, label: String) {
        val file = Files.createTempFile("yks-mixed-root-$label-", ".bin")
        try {
            Files.write(file, update)
            val process = ProcessBuilder(
                "node",
                "--no-warnings",
                "interop/yjs-v1/generate-mixed-root-updates.mjs",
                "verify",
                file.toAbsolutePath().toString(),
            )
                .directory(Path.of(System.getProperty("user.dir")).toFile())
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            assertTrue(process.waitFor() == 0, output)
        } finally {
            Files.deleteIfExists(file)
        }
    }
}
