package dev.yks

import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.test.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("yjs-interop")
class CodecValueInteropTest {
    private val projectDirectory = Path.of(System.getProperty("user.dir"))

    @Test
    fun upstreamYjsAppliesEmojiAndSpecialLib0ValuesFromV1AndV2() {
        val doc = YDoc(clientId = 1)
        doc.getText("body").insert(0, "A😀B")
        doc.getArray("values").push(
            listOf(
                linkedMapOf(
                    "b" to 1,
                    "a" to 2,
                    "10" to 10,
                    "2" to 2,
                    "undef" to YValue.Undefined,
                    "big" to BigInteger("9007199254740993"),
                    "negzero" to -0.0,
                    "nan" to Double.NaN,
                    "positiveInfinity" to Double.POSITIVE_INFINITY,
                    "negativeInfinity" to Double.NEGATIVE_INFINITY,
                ),
            ),
        )
        doc.getArray("subs").push(listOf(YDoc(clientId = 2, guid = "default-child")))

        assertUpstreamValues(encodeStateAsUpdate(doc), "v1")
        assertUpstreamValues(encodeStateAsUpdateV2(doc), "v2")
    }

    @Test
    fun upstreamYjsAppliesLargeDeletedRangesRelayedByKotlin() {
        listOf(1L shl 32, (1L shl 32) + 1).forEach { length ->
            val doc = YDoc(clientId = 2)
            applyUpdate(doc, contentDeletedUpdate(length))
            assertUpstreamLargeDeleted(encodeStateAsUpdate(doc), length, "v1")
            assertUpstreamLargeDeleted(encodeStateAsUpdateV2(doc), length, "v2")

            val gcDoc = YDoc(clientId = 2)
            applyUpdate(gcDoc, gcUpdate(length))
            assertUpstreamLargeDeleted(encodeStateAsUpdate(gcDoc), length, "v1")
            assertUpstreamLargeDeleted(encodeStateAsUpdateV2(gcDoc), length, "v2")
        }
    }

    private fun assertUpstreamValues(bytes: ByteArray, format: String) {
        val update = Files.createTempFile("yks-codec-values-$format-", ".bin")
        try {
            Files.write(update, bytes)
            val process = ProcessBuilder(
                "node",
                "interop/yjs-v1/verify-codec-values.mjs",
                update.absolutePathString(),
                format,
            )
                .directory(projectDirectory.toFile())
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            assertTrue(process.waitFor() == 0, "upstream Yjs rejected codec values ($format):\n$output")
        } finally {
            Files.deleteIfExists(update)
        }
    }

    private fun assertUpstreamLargeDeleted(bytes: ByteArray, length: Long, format: String) {
        val update = Files.createTempFile("yks-large-deleted-$format-", ".bin")
        try {
            Files.write(update, bytes)
            val process = ProcessBuilder(
                "node",
                "interop/yjs-v1/verify-large-deleted.mjs",
                update.absolutePathString(),
                length.toString(),
                format,
            )
                .directory(projectDirectory.toFile())
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            assertTrue(process.waitFor() == 0, "upstream Yjs rejected large deleted range ($format):\n$output")
        } finally {
            Files.deleteIfExists(update)
        }
    }

    private fun contentDeletedUpdate(length: Long): ByteArray = BinaryEncoder().also { encoder ->
        encoder.writeVarUInt(1)
        encoder.writeVarUInt(1)
        encoder.writeVarUInt(1)
        encoder.writeVarUInt(0)
        encoder.writeByte(contentDeletedRefNumber)
        encoder.writeVarUInt(1)
        encoder.writeString("a")
        encoder.writeVarUInt(length)
        encoder.writeVarUInt(0)
    }.toByteArray()

    private fun gcUpdate(length: Long): ByteArray = BinaryEncoder().also { encoder ->
        encoder.writeVarUInt(1)
        encoder.writeVarUInt(1)
        encoder.writeVarUInt(1)
        encoder.writeVarUInt(0)
        encoder.writeByte(structGCRefNumber)
        encoder.writeVarUInt(length)
        encoder.writeVarUInt(0)
    }.toByteArray()
}
