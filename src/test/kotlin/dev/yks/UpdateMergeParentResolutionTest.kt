package dev.yks

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Tag

class UpdateMergeParentResolutionTest {
    @Test
    fun mergedBaselineAndNestedIncrementalUseGenuineStandardUpdates() {
        val updates = nestedMapUpdates()

        val mergedV1 = mergeUpdates(listOf(updates.baselineV1, updates.incrementalV1))
        val mergedV2 = mergeUpdatesV2(listOf(updates.baselineV2, updates.incrementalV2))

        assertFalse(mergedV1.hasLegacyMagic(), "merged V1 update must not use the private YKS envelope")
        assertFalse(mergedV2.hasLegacyMagic(), "merged V2 update must not use the private YKS envelope")
        assertEquals(0, mergedV2.first().toInt(), "a genuine V2 update starts with the feature flag")
        assertTrue(decodeUpdate(mergedV1).structs.none { struct -> struct.parent.startsWith("__yjs_") })
        assertTrue(decodeUpdateV2(mergedV2).structs.none { struct -> struct.parent.startsWith("__yjs_") })

        assertNestedMapState(createDocFromUpdate(mergedV1))
        assertNestedMapState(createDocFromUpdateV2(mergedV2))
    }

    @Test
    fun standaloneIncrementalFormatConversionKeepsItsDeferredParentDependency() {
        val updates = nestedMapUpdates()
        val baselineV2 = convertUpdateFormatV1ToV2(updates.baselineV1)
        val incrementalV2 = convertUpdateFormatV1ToV2(updates.incrementalV1)
        val targetV2 = YDoc(clientId = 9, gc = false)

        applyUpdateV2(targetV2, incrementalV2)
        assertTrue(targetV2.store.pendingStructs != null)
        applyUpdateV2(targetV2, baselineV2)

        assertNull(targetV2.store.pendingStructs)
        assertNestedMapState(targetV2)

        val targetV1 = YDoc(clientId = 10, gc = false)
        applyUpdate(targetV1, convertUpdateFormatV2ToV1(incrementalV2))
        applyUpdate(targetV1, convertUpdateFormatV2ToV1(baselineV2))
        assertNull(targetV1.store.pendingStructs)
        assertNestedMapState(targetV1)
    }

    @Test
    fun syntheticLookingRootNamesAreNeverResolvedAsParentAliases() {
        for (rootName in listOf("__yjs_inherit__:1:0", "__yjs_nested__:1:0")) {
            val source = YDoc(clientId = 4, gc = false)
            source.getArray(rootName).push("value")
            val update = encodeStateAsUpdate(source)
            val target = YDoc(clientId = 5, gc = false)

            applyUpdate(target, update)

            assertEquals(listOf("value"), target.getArray(rootName).toList())
            assertNull(target.store.pendingStructs)
            assertFalse(mergeUpdates(listOf(update, encodeStateAsUpdate(YDoc(clientId = 6)))).hasLegacyMagic())
        }
    }

    @Test
    @Tag("yjs-interop")
    fun upstreamYjsAppliesMergedNestedUpdatesInV1AndV2() {
        val updates = nestedMapUpdates()
        assertUpstreamApplies(
            mergeUpdates(listOf(updates.baselineV1, updates.incrementalV1)),
            v2 = false,
        )
        assertUpstreamApplies(
            mergeUpdatesV2(listOf(updates.baselineV2, updates.incrementalV2)),
            v2 = true,
        )
    }

    private fun nestedMapUpdates(): NestedMapUpdates {
        val source = YDoc(clientId = 1, gc = false)
        val profile = source.createMap()
        source.getMap("root").set("profile", profile)
        profile.set("name", "Ada")
        val stateVector = encodeStateVector(source)
        val baselineV1 = encodeStateAsUpdate(source)
        val baselineV2 = encodeStateAsUpdateV2(source)

        profile.set("city", "Seoul")
        profile.set("name", "Grace")

        return NestedMapUpdates(
            baselineV1 = baselineV1,
            incrementalV1 = encodeStateAsUpdate(source, stateVector),
            baselineV2 = baselineV2,
            incrementalV2 = encodeStateAsUpdateV2(source, stateVector),
        )
    }

    private fun assertNestedMapState(doc: YDoc) {
        val profile = doc.getMap("root").get("profile") as YMap
        assertEquals(mapOf("name" to "Grace", "city" to "Seoul"), profile.toMap())
    }

    private fun assertUpstreamApplies(update: ByteArray, v2: Boolean) {
        val path = Files.createTempFile("yks-merged-nested-", if (v2) "-v2.bin" else "-v1.bin")
        try {
            Files.write(path, update)
            val script = if (v2) {
                "interop/yjs-v1/verify-update-v2.mjs"
            } else {
                "interop/yjs-v1/verify-update.mjs"
            }
            val process = ProcessBuilder(
                "node",
                script,
                path.absolutePathString(),
                "nested-map-replace-update",
            )
                .directory(Path.of(System.getProperty("user.dir")).toFile())
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(0, process.waitFor(), "upstream Yjs rejected merged update:\n$output")
        } finally {
            Files.deleteIfExists(path)
        }
    }

    private fun ByteArray.hasLegacyMagic(): Boolean =
        size >= 3 && this[0] == 'Y'.code.toByte() && this[1] == 'K'.code.toByte() && this[2] == 'S'.code.toByte()

    private data class NestedMapUpdates(
        val baselineV1: ByteArray,
        val incrementalV1: ByteArray,
        val baselineV2: ByteArray,
        val incrementalV2: ByteArray,
    )
}
