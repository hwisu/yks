package dev.yks

import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.io.path.absolutePathString
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("yjs-interop")
class YjsDifferentialFuzzTest {
    private val projectDirectory = Path.of(System.getProperty("user.dir"))

    @Test
    fun concurrentArrayTextAndMapOperationsMatchUpstreamAcross500Seeds() {
        val corpus = Files.createTempFile("yks-differential-fuzz-", ".tsv")
        try {
            val process = ProcessBuilder(
                "node",
                "interop/yjs-v1/generate-differential-fuzz.mjs",
                corpus.absolutePathString(),
                "500",
            )
                .directory(projectDirectory.toFile())
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            assertTrue(process.waitFor() == 0, "upstream fuzz generator failed:\n$output")

            Files.readAllLines(corpus).forEach { line ->
                val columns = line.split('\t')
                assertEquals(5, columns.size, "invalid fuzz corpus row: $line")
                val seed = columns[0]
                val doc = YDoc(clientId = 99, gc = false)
                doc.getArray("a")
                doc.getText("t")
                doc.getMap("m")
                columns[1]
                    .split(',')
                    .filter(String::isNotEmpty)
                    .map(Base64.getDecoder()::decode)
                    .forEach(doc::applyUpdate)

                assertEquals(columns[2], doc.getArray("a").toArray().toString(), "array diverged at seed $seed")
                assertEquals(columns[3], doc.getText("t").toString(), "text diverged at seed $seed")
                assertEquals(columns[4], doc.getMap("m").toMap().toSortedMap().toString(), "map diverged at seed $seed")
                assertEquals(null, doc.store.pendingStructs, "pending structs remained at seed $seed")
            }
        } finally {
            Files.deleteIfExists(corpus)
        }
    }
}
