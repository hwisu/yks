package dev.yks

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("yjs-interop")
class PreliminaryInteropTest {
    private val projectDirectory: Path = Path.of(System.getProperty("user.dir"))

    @Test
    fun attachedPreliminaryGraphIsOneGenuineUpdateAcceptedByUpstreamYjs() {
        val doc = YDoc(clientId = 1, gc = false)
        val outer = YArray()
        val inner = YMap().also { it.set("answer", 42) }
        val element = YXmlElementType("P")
        element.push(YXmlTextType().also { it.insert(0, "abc") })
        element.setAttr("class", "intro")
        val hook = YXmlHook("widget").also { it.set("enabled", true) }
        outer.push(listOf("before", inner, element, hook, "after"))
        val mapElement = YXmlElementType("ASIDE").also {
            it.push(YXmlTextType().also { text -> text.insert(0, "map-child") })
        }
        val textChild = YArray().also { it.push("text-child") }
        val updates = mutableListOf<ByteArray>()
        doc.onUpdate { update, _, _, _ -> updates.add(update) }

        doc.transact {
            doc.getArray("root").push(outer)
            doc.getMap("map").set("element", mapElement)
            doc.getText("body").insertEmbed(0, textChild, mapOf("bold" to true))
        }

        assertEquals(1, updates.size)
        assertTrue(updates.single().size < 3 || !updates.single().copyOfRange(0, 3).contentEquals("YKS".encodeToByteArray()))
        assertUpstreamApplies(updates.single())
    }

    private fun assertUpstreamApplies(bytes: ByteArray) {
        val update = Files.createTempFile("yks-preliminary-v1-", ".bin")
        try {
            Files.write(update, bytes)
            val process = ProcessBuilder(
                "node",
                "interop/yjs-v1/verify-preliminary-update.mjs",
                update.absolutePathString(),
            )
                .directory(projectDirectory.toFile())
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            assertTrue(process.waitFor() == 0, "upstream Yjs rejected the preliminary update:\n$output")
        } finally {
            Files.deleteIfExists(update)
        }
    }
}
