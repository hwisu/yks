package dev.yks.awareness

import dev.yks.YDoc
import dev.yks.YValue
import java.nio.file.Files
import java.util.Base64
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.Tag

@Tag("yjs-interop")
class AwarenessInteropTest {
    @Test
    fun appliesUpdateProducedByPinnedYProtocols() {
        val output = runOracle("emit").trim()
        val target = Awareness(YDoc(clientId = 100), AwarenessOptions(autoStart = false))

        target.applyUpdate(Base64.getDecoder().decode(output), OracleOrigin)

        assertEquals(
            AwarenessState(
                "name" to YValue.StringValue("Ada 😀"),
                "cursor" to YValue.ListValue(listOf(YValue.LongNumber(3), YValue.LongNumber(9))),
                "profile" to YValue.MapValue(mapOf("active" to YValue.Bool(true))),
            ),
            target.getStates()[7],
        )
        assertEquals(1L, target.getMeta().getValue(7).clock)
        target.close()
    }

    @Test
    fun pinnedYProtocolsAppliesUpdateProducedByKotlin() {
        val source = Awareness(YDoc(clientId = 9), AwarenessOptions(autoStart = false))
        source.setLocalState(
            AwarenessState(
                "name" to YValue.StringValue("Kotlin 😀"),
                "cursor" to YValue.ListValue(listOf(YValue.LongNumber(4), YValue.LongNumber(12))),
                "profile" to YValue.MapValue(mapOf("active" to YValue.Bool(false))),
            ),
        )
        val updateFile = Files.createTempFile("yks-awareness-", ".bin")
        try {
            updateFile.writeBytes(source.encodeUpdate())
            runOracle("verify", updateFile.toAbsolutePath().toString())
        } finally {
            Files.deleteIfExists(updateFile)
            source.close()
        }
    }

    private fun runOracle(vararg arguments: String): String {
        val projectDirectory = System.getProperty("yks.projectDirectory") ?: "."
        val process = ProcessBuilder(
            listOf("node", "interop/awareness/oracle.mjs") + arguments,
        )
            .directory(java.io.File(projectDirectory))
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "awareness oracle failed" }
        return output
    }

    private data object OracleOrigin : AwarenessOrigin
}
