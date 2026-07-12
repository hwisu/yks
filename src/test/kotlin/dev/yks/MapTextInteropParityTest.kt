package dev.yks

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("yjs-interop")
class MapTextInteropParityTest {
    private val projectDirectory: Path = Path.of(System.getProperty("user.dir"))

    private fun fixture(name: String): ByteArray = Files.readAllBytes(
        projectDirectory.resolve("interop/yjs-v1/fixtures/$name.bin"),
    )

    @Test
    fun remoteMapOrderMatchesUpstreamDeliveryAndCanonicalMergeOrder() {
        val one = fixture("map-order-client-one-v1")
        val two = fixture("map-order-client-two-v1")

        fun keys(vararg updates: ByteArray): List<String> {
            val doc = YDoc(clientId = 9, gc = false)
            updates.forEach { update -> doc.applyUpdate(update) }
            return doc.getMap("ordered").keys().toList()
        }

        assertEquals(listOf("z", "a"), keys(one, two))
        assertEquals(listOf("a", "z"), keys(two, one))
        assertEquals(listOf("a", "z"), keys(fixture("map-order-merged-v1")))
        assertEquals(listOf("a", "z"), keys(mergeUpdates(listOf(one, two))))
        assertEquals(listOf("a", "z"), keys(mergeUpdates(listOf(two, one))))
    }

    @Test
    fun nativeContentFormatMarkersRemainDistinctDeltaSegments() {
        val updates = listOf(
            fixture("concurrent-format-base-v1"),
            fixture("text-format-boundary-long-v1"),
            fixture("text-format-boundary-short-v1"),
        )
        val expected = YTextDelta(
            listOf(
                YTextDeltaOp(insert = "a"),
                YTextDeltaOp(insert = "b", attributes = mapOf("italic" to "x")),
                YTextDeltaOp(insert = "c"),
                YTextDeltaOp(insert = "d"),
            ),
        )

        permutations(updates).forEach { deliveryOrder ->
            val doc = YDoc(clientId = 9, gc = false)
            deliveryOrder.forEach { update -> doc.applyUpdate(update) }
            val text = doc.getText("body")
            val current = snapshot(doc)

            assertEquals(expected, text.toDelta())
            assertEquals(expected, typeTextToDeltaSnapshot(text, current))

            val relay = YDoc(clientId = 10, gc = false)
            relay.applyUpdate(doc.encodeStateAsUpdate())
            assertEquals(expected, relay.getText("body").toDelta())
        }
    }

    private fun <T> permutations(values: List<T>): List<List<T>> =
        if (values.size <= 1) {
            listOf(values)
        } else {
            values.flatMapIndexed { index, value ->
                permutations(values.filterIndexed { otherIndex, _ -> otherIndex != index })
                    .map { rest -> listOf(value) + rest }
            }
        }
}
