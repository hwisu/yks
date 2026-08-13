package dev.yks

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("yjs-interop")
class YjsV1InteropTest {
    private val projectDirectory: Path = Path.of(System.getProperty("user.dir"))
    private val fixture: Path = projectDirectory.resolve("interop/yjs-v1/fixtures/hello-text-v1.bin")

    private fun fixture(name: String): ByteArray = Files.readAllBytes(
        projectDirectory.resolve("interop/yjs-v1/fixtures/$name.bin"),
    )

    @Test
    fun appliesHelloUpdateProducedByUpstreamYjs() {
        val doc = YDoc(clientId = 2)

        applyUpdate(doc, Files.readAllBytes(fixture))

        assertEquals("hello", doc.getText("body").toString())
        assertEquals(mapOf(1L to 5L), decodeStateVector(encodeStateVector(doc)))
    }

    @Test
    fun appliesHelloUpdateV2ProducedByUpstreamYjs() {
        val doc = YDoc(clientId = 2)

        applyUpdateV2(doc, fixture("hello-text-v2"))

        assertEquals("hello", doc.getText("body").toString())
        assertEquals(mapOf(1L to 5L), decodeStateVector(encodeStateVector(doc)))
    }

    @Test
    fun appliesPackedArrayUpdateV2ProducedByUpstreamYjs() {
        val doc = YDoc(clientId = 2)

        applyUpdateV2(doc, fixture("array-v2"))

        val values = doc.getArray("items").toList()
        assertEquals(listOf("a", 42L, true, null), values.take(4))
        assertTrue((values[4] as ByteArray).contentEquals(byteArrayOf(1, 2)))
    }

    @Test
    fun appliesFormattedTextUpdateV2ProducedByUpstreamYjs() {
        val doc = YDoc(clientId = 2, gc = false)
        applyUpdateV2(doc, fixture("formatted-text-v2"))

        assertEquals(YTextDelta().insert("ab", mapOf("bold" to true)), doc.getText("body").toDelta())
    }

    @Test
    fun appliesDeleteSetUpdateV2ProducedByUpstreamYjs() {
        val doc = YDoc(clientId = 2, gc = false)
        applyUpdateV2(doc, fixture("text-delete-v2"))

        assertEquals("ho", doc.getText("body").toString())
        assertTrue(!doc.deleteSet().isEmpty)
    }

    @Test
    fun appliesFormattedXmlUpdateV2ProducedByUpstreamYjs() {
        val doc = YDoc(clientId = 2, gc = false)
        applyUpdateV2(doc, fixture("xml-formatted-v2"))

        val paragraph = doc.getXmlFragment("xml").getType(0) as YXmlElementType
        assertEquals(
            YTextDelta().insert("hi", mapOf("strong" to mapOf("level" to "1"))),
            (paragraph.getType(0) as YXmlTextType).toDelta(),
        )
    }

    @Test
    fun appliesMapBackedXmlHookProducedByUpstreamYjs() {
        val doc = YDoc(clientId = 2, gc = false)

        applyUpdate(doc, fixture("xml-hook-v1"))

        val hook = doc.getXmlFragment("xml").getType(0) as YXmlHook
        assertEquals("Widget", hook.hookName)
        assertEquals(mapOf("x" to 1L, "nested" to mapOf("ok" to true)), hook.toJSON())
        assertEquals("[object Object]", doc.getXmlFragment("xml").toString())
    }

    @Test
    fun appliesRootXmlTextProducedByUpstreamYjs() {
        val doc = YDoc(clientId = 2, gc = false)

        applyUpdate(doc, fixture("xml-root-text-v1"))
        val text = doc.getXmlText("root-xml-text")

        assertEquals(
            YTextDelta().insert("hello", mapOf("strong" to mapOf("level" to "1"))),
            text.toDelta(),
        )
        assertSame(text, doc.get("root-xml-text", YXmlTextRefID))
    }

    @Test
    fun appliesRootXmlHookProducedByUpstreamYjs() {
        val doc = YDoc(clientId = 2, gc = false)

        applyUpdate(doc, fixture("xml-root-hook-v1"))
        val hook = doc.getXmlHook("root-xml-hook", "Widget")

        assertEquals("Widget", hook.hookName)
        assertEquals(mapOf("count" to 1L, "nested" to mapOf("ok" to true)), hook.toJSON())
        assertSame(hook, doc.get("root-xml-hook", YXmlHookRefID))
    }

    @Test
    fun upstreamYjsAppliesRootXmlTextAndHookAuthoredByKotlin() {
        val textDoc = YDoc(clientId = 1, gc = false)
        textDoc.getXmlText("root-xml-text").insert(
            0,
            "hello",
            mapOf("strong" to mapOf("level" to "1")),
        )
        val hookDoc = YDoc(clientId = 1, gc = false)
        hookDoc.getXmlHook("root-xml-hook", "Widget").apply {
            set("count", 1)
            set("nested", mapOf("ok" to true))
        }

        assertUpstreamAppliesUpdate(textDoc.encodeStateAsUpdate(), "xml-root-text")
        assertUpstreamAppliesUpdate(hookDoc.encodeStateAsUpdate(), "xml-root-hook")
    }

    @Test
    fun appliesAnswerDocumentProducedByUpstreamYjs() {
        val doc = YDoc(clientId = 2, gc = false)

        applyUpdate(doc, fixture("answer-document-v1"))

        val question = doc.getMap("questions").get("42") as YMap
        assertEquals(42L, question.get("id"))
        assertEquals("IN_PROGRESS", question.get("status"))
        assertEquals(listOf("user-1", "사용자-😀"), question.get("assignUser"))
        val paragraph = doc.getXmlFragment("42").getType(0) as YXmlElementType
        assertEquals("paragraph", paragraph.nodeName)
        assertEquals(listOf("n4"), paragraph.getAttr("node_ids"))
        assertEquals(
            YTextDelta().insert("저장된 답변 😀"),
            (paragraph.getType(0) as YXmlTextType).toDelta(),
        )
        val selection = paragraph.getType(1) as YXmlElementType
        assertEquals("선택-😀", selection.getAttr("node_id"))
        assertEquals(true, selection.getAttr("selected"))
        assertEquals(1.5, selection.getAttr("score"))
    }

    @Test
    fun appliesSubdocumentUpdateV2ProducedByUpstreamYjs() {
        val doc = YDoc(clientId = 2, gc = false)
        applyUpdateV2(doc, fixture("subdoc-array-v2"))

        val child = doc.getArray("subs").get(0) as YDoc
        assertEquals("child-guid", child.guid)
        assertEquals(mapOf("role" to "child"), child.meta)
    }

    @Test
    fun appliesMultiClientUpdateV2ProducedByUpstreamYjs() {
        val doc = YDoc(clientId = 4, gc = false)
        applyUpdateV2(doc, fixture("concurrent-format-v2"))

        assertEquals(
            YTextDelta()
                .insert("a", mapOf("bold" to true))
                .insert("b", mapOf("bold" to true, "italic" to true))
                .insert("c", mapOf("italic" to true))
                .insert("d"),
            doc.getText("body").toDelta(),
        )
    }

    @Test
    fun appliesPackedArrayAndBinaryProducedByUpstreamYjs() {
        val doc = YDoc(clientId = 2)

        applyUpdate(doc, fixture("array-v1"))

        val values = doc.getArray("items").toList()
        assertEquals(listOf("a", 42L, true, null), values.take(4))
        assertTrue((values[4] as ByteArray).contentEquals(byteArrayOf(1, 2)))
        assertEquals(mapOf(1L to 5L), decodeStateVector(encodeStateVector(doc)))
    }

    @Test
    fun appliesFullMapReplacementAndDeleteSetProducedByUpstreamYjs() {
        val doc = YDoc(clientId = 2, gc = false)

        applyUpdate(doc, fixture("map-full-v1"))

        assertEquals(mapOf("title" to "new"), doc.getMap("meta").toMap())
        assertTrue(doc.deleteSet().contains(Id(1, 0)))
    }

    @Test
    fun appliesGarbageCollectedMapReplacementProducedByUpstreamYjs() {
        val doc = YDoc(clientId = 2)

        applyUpdate(doc, fixture("map-full-gc-v1"))

        assertEquals(mapOf("title" to "new"), doc.getMap("meta").toMap())
    }

    @Test
    fun appliesMapReplacementAndExplicitDeleteProducedByUpstreamYjs() {
        val doc = YDoc(clientId = 2)

        applyUpdate(doc, fixture("map-delete-full-v1"))

        assertEquals(mapOf("title" to "new"), doc.getMap("meta").toMap())
    }

    @Test
    fun appliesIncrementalPackedArrayUpdatesInEitherOrder() {
        listOf(
            listOf("array-base-v1", "array-append-v1"),
            listOf("array-append-v1", "array-base-v1"),
        ).forEach { order ->
            val doc = YDoc(clientId = 2, gc = false)
            doc.getArray("numbers")
            order.forEach { name -> applyUpdate(doc, fixture(name)) }

            assertEquals(listOf(1L, 2L, 3L, 4L), doc.getArray("numbers").toList())
            assertEquals(null, doc.store.pendingStructs)
        }
    }

    @Test
    fun concurrentArrayInsertionsConvergeAcrossEveryDeliveryOrder() {
        permutations(
            listOf("concurrent-array-base-v1", "concurrent-array-x-v1", "concurrent-array-y-v1"),
        ).forEach { order ->
            val doc = YDoc(clientId = 4, gc = false)
            order.forEach { name -> applyUpdate(doc, fixture(name)) }

            assertEquals(listOf("a", "X", "Y", "b"), doc.getArray("letters").toList())
            assertEquals(null, doc.store.pendingStructs)
        }
        val relay = YDoc(clientId = 4, gc = false)
        listOf("concurrent-array-base-v1", "concurrent-array-x-v1", "concurrent-array-y-v1")
            .forEach { name -> applyUpdate(relay, fixture(name)) }
        assertUpstreamAppliesUpdate(encodeStateAsUpdate(relay), "concurrent-array")
    }

    @Test
    fun duplicatePendingUpdateIsIdempotent() {
        val doc = YDoc(clientId = 2, gc = false)
        doc.getArray("numbers")

        applyUpdate(doc, fixture("array-append-v1"))
        applyUpdate(doc, fixture("array-append-v1"))
        applyUpdate(doc, fixture("array-base-v1"))

        assertEquals(listOf(1L, 2L, 3L, 4L), doc.getArray("numbers").toList())
        assertEquals(null, doc.store.pendingStructs)
    }

    @Test
    fun appliesIncrementalMapReplacementInEitherOrder() {
        listOf(
            listOf("map-base-v1", "map-replace-v1"),
            listOf("map-replace-v1", "map-base-v1"),
        ).forEach { order ->
            val doc = YDoc(clientId = 2, gc = false)
            doc.getMap("meta")
            order.forEach { name -> applyUpdate(doc, fixture(name)) }

            assertEquals(mapOf("title" to "new"), doc.getMap("meta").toMap())
            assertEquals(null, doc.store.pendingStructs)
        }
    }

    @Test
    fun waitsForMissingSameClientClockWithoutAnchors() {
        val doc = YDoc(clientId = 2, gc = false)
        doc.getMap("meta")

        applyUpdate(doc, fixture("map-second-key-v1"))

        assertEquals(mapOf(1L to 0L), doc.store.pendingStructs?.missing)
        assertEquals(emptyMap(), decodeStateVector(doc.encodeStateVector()))

        applyUpdate(doc, fixture("map-first-key-v1"))

        assertEquals(mapOf("first" to 1L, "second" to 2L), doc.getMap("meta").toMap())
        assertEquals(null, doc.store.pendingStructs)
    }

    @Test
    fun appliesRightOriginInsertionInEitherOrder() {
        listOf(
            listOf("array-front-base-v1", "array-front-insert-v1"),
            listOf("array-front-insert-v1", "array-front-base-v1"),
        ).forEach { order ->
            val doc = YDoc(clientId = 2, gc = false)
            doc.getArray("letters")
            order.forEach { name -> applyUpdate(doc, fixture(name)) }

            assertEquals(listOf("x", "a", "b"), doc.getArray("letters").toList())
            assertEquals(null, doc.store.pendingStructs)
        }
    }

    @Test
    fun appliesInteriorOriginAndRightOriginInEitherOrder() {
        listOf(
            listOf("array-interior-base-v1", "array-interior-insert-v1"),
            listOf("array-interior-insert-v1", "array-interior-base-v1"),
        ).forEach { order ->
            val doc = YDoc(clientId = 2, gc = false)
            doc.getArray("letters")
            order.forEach { name -> applyUpdate(doc, fixture(name)) }

            assertEquals(listOf("a", "b", "X", "c"), doc.getArray("letters").toList())
            assertEquals(null, doc.store.pendingStructs)
        }
    }

    @Test
    fun appliesIncrementalNestedMapUpdateInEitherOrder() {
        listOf(
            listOf("nested-map-base-v1", "nested-map-city-v1"),
            listOf("nested-map-city-v1", "nested-map-base-v1"),
        ).forEach { order ->
            val doc = YDoc(clientId = 2, gc = false)
            doc.getMap("root")
            order.forEach { name -> applyUpdate(doc, fixture(name)) }

            val profile = doc.getMap("root").get("profile") as YMap
            assertEquals(mapOf("city" to "Seoul", "name" to "Ada"), profile.toMap())
            assertEquals(null, doc.store.pendingStructs)
        }
    }

    @Test
    fun appliesCrossClientNestedParentDependencyInEitherOrder() {
        listOf(
            listOf("nested-owner-v1", "nested-child-v1"),
            listOf("nested-child-v1", "nested-owner-v1"),
        ).forEach { order ->
            val doc = YDoc(clientId = 3, gc = false)
            doc.getMap("root")
            applyUpdate(doc, fixture(order.first()))
            if (order.first() == "nested-child-v1") {
                assertEquals(mapOf(1L to 0L), doc.store.pendingStructs?.missing)
                assertEquals(emptyMap(), decodeStateVector(doc.encodeStateVector()))
            }
            applyUpdate(doc, fixture(order.last()))

            val profile = doc.getMap("root").get("profile") as YMap
            assertEquals(mapOf("name" to "Ada"), profile.toMap())
            assertEquals(null, doc.store.pendingStructs)
        }
    }

    @Test
    fun childArrivingAfterDeletedParentIsDeletedToo() {
        val doc = YDoc(clientId = 3, gc = false)
        doc.getMap("root")

        applyUpdate(doc, fixture("nested-owner-v1"))
        applyUpdate(doc, fixture("nested-owner-delete-v1"))
        applyUpdate(doc, fixture("nested-child-v1"))

        assertEquals(emptyMap(), doc.getMap("root").toMap())
        assertTrue(doc.deleteSet().contains(Id(1, 0)))
        assertTrue(doc.deleteSet().contains(Id(2, 0)))
        assertEquals(mapOf(1L to 1L, 2L to 1L), decodeStateVector(doc.encodeStateVector()))
        assertEquals(null, doc.store.pendingStructs)
    }

    @Test
    fun appliesGcStructWithoutExposingSyntheticRoots() {
        val doc = YDoc(clientId = 2)

        applyUpdate(doc, fixture("gc-nested-delete-v1"))

        assertEquals(emptyList(), doc.getArray("gc-root").toList())
        assertEquals(setOf("gc-root"), doc.rootNames())
        assertEquals(mapOf(1L to 2L), decodeStateVector(doc.encodeStateVector()))
    }

    @Test
    fun skipDoesNotOwnClocksOrIntegrateFollowingStructs() {
        val doc = YDoc(clientId = 2, gc = false)

        applyUpdate(doc, fixture("skip-then-text-v1"))

        assertEquals("", doc.getText("body").toString())
        assertEquals(emptyMap(), decodeStateVector(doc.encodeStateVector()))
        assertEquals(mapOf(1L to 1L), doc.store.pendingStructs?.missing)
    }

    @Test
    fun gcOwnsClocksAndIntegratesFollowingStructs() {
        val doc = YDoc(clientId = 2, gc = false)

        applyUpdate(doc, fixture("gc-then-text-v1"))

        assertEquals("x", doc.getText("body").toString())
        assertEquals(mapOf(1L to 3L), decodeStateVector(doc.encodeStateVector()))
        assertEquals(null, doc.store.pendingStructs)
        assertEquals(setOf("body"), doc.rootNames())
    }

    @Test
    fun deletedTextContentDoesNotOverrideReplacementKind() {
        val doc = YDoc(clientId = 2)

        applyUpdate(doc, fixture("text-replace-full-v1"))

        assertEquals("new", doc.getText("body").toString())
        assertEquals(mapOf(1L to 6L), decodeStateVector(doc.encodeStateVector()))
        assertTrue(doc.deleteSet().contains(Id(1, 0)))
    }

    @Test
    fun contentTargetingGcParentBecomesGcWithoutSyntheticRoot() {
        val doc = YDoc(clientId = 3, gc = false)

        applyUpdate(doc, fixture("gc-then-text-v1"))
        applyUpdate(doc, fixture("nested-child-v1"))

        assertEquals("x", doc.getText("body").toString())
        assertEquals(setOf("body"), doc.rootNames())
        assertEquals(mapOf(1L to 3L, 2L to 1L), decodeStateVector(doc.encodeStateVector()))
        assertEquals(null, doc.store.pendingStructs)
    }

    @Test
    fun appliesNativeTextFormatMarkersProducedByUpstreamYjs() {
        val update = fixture("text-format-insert-v1")
        val doc = YDoc(clientId = 2, gc = false)

        applyUpdate(doc, update)

        assertEquals(
            YTextDelta().insert("ab", mapOf("bold" to true)),
            doc.getText("body").toDelta(),
        )
        assertEquals(mapOf(1L to 4L), decodeStateVector(doc.encodeStateVector()))
        assertEquals(
            listOf("bold" to true, "bold" to null),
            decodeUpdate(update).structs.mapNotNull { struct ->
                (struct.content as? ContentFormat)?.let { format -> format.key to format.value }
            },
        )
    }

    @Test
    fun appliesNativeFormatRemovalInEitherOrder() {
        listOf(
            listOf("text-format-insert-v1", "text-format-remove-v1"),
            listOf("text-format-remove-v1", "text-format-insert-v1"),
        ).forEach { order ->
            val doc = YDoc(clientId = 2, gc = false)
            order.forEach { name -> applyUpdate(doc, fixture(name)) }

            assertEquals(YTextDelta().insert("ab"), doc.getText("body").toDelta())
            assertEquals(mapOf(1L to 4L), decodeStateVector(doc.encodeStateVector()))
            assertEquals(null, doc.store.pendingDs)
        }
    }

    @Test
    fun appliesIncrementalNativeFormattingInEitherOrder() {
        listOf(
            listOf("text-format-base-v1", "text-format-partial-v1"),
            listOf("text-format-partial-v1", "text-format-base-v1"),
        ).forEach { order ->
            val doc = YDoc(clientId = 2, gc = false)
            applyUpdate(doc, fixture(order.first()))
            if (order.first() == "text-format-partial-v1") {
                assertEquals(mapOf(1L to 3L), doc.store.pendingStructs?.missing)
                assertEquals(emptyMap(), decodeStateVector(doc.encodeStateVector()))
            }
            applyUpdate(doc, fixture(order.last()))

            assertEquals(
                YTextDelta()
                    .insert("a")
                    .insert("bc", mapOf("bold" to true))
                    .insert("d"),
                doc.getText("body").toDelta(),
            )
            assertEquals(mapOf(1L to 6L), decodeStateVector(doc.encodeStateVector()))
            assertEquals(null, doc.store.pendingStructs)
        }
    }

    @Test
    fun nativeFormatOnlyUpdateEmitsFormattingDeltaAndPreservesSnapshots() {
        val doc = YDoc(clientId = 2, gc = false)
        val text = doc.getText("body")
        applyUpdate(doc, fixture("text-format-base-v1"))
        val before = snapshot(doc)
        var observed = YTextDelta()
        text.observe { event -> observed = event.textDelta }

        applyUpdate(doc, fixture("text-format-partial-v1"))
        val after = snapshot(doc)

        assertEquals(
            YTextDelta().retain(1).retain(2, mapOf("bold" to true)),
            observed,
        )
        assertEquals(YTextDelta().insert("abcd"), typeTextToDeltaSnapshot(text, before))
        assertEquals(
            YTextDelta()
                .insert("a")
                .insert("bc", mapOf("bold" to true))
                .insert("d"),
            typeTextToDeltaSnapshot(text, after),
        )
    }

    @Test
    fun appliesNativeFormattingToEmbed() {
        val doc = YDoc(clientId = 2, gc = false)

        applyUpdate(doc, fixture("text-format-embed-v1"))

        assertEquals(
            YTextDelta().insertEmbed(mapOf("image" to "x"), mapOf("bold" to true)),
            doc.getText("body").toDelta(),
        )
        assertEquals(mapOf(1L to 3L), decodeStateVector(doc.encodeStateVector()))
    }

    @Test
    fun nativeFormatMarkerRestoresPreviousNonNullValue() {
        val doc = YDoc(clientId = 2, gc = false)

        applyUpdate(doc, fixture("text-format-restoration-v1"))

        assertEquals(
            YTextDelta()
                .insert("a", mapOf("url" to "outer"))
                .insert("b", mapOf("url" to "inner"))
                .insert("c", mapOf("url" to "outer")),
            doc.getText("body").toDelta(),
        )
    }

    @Test
    fun overlappingConcurrentFormattingConvergesAcrossEveryDeliveryOrder() {
        val expected = YTextDelta()
            .insert("a", mapOf("bold" to true))
            .insert("b", mapOf("bold" to true, "italic" to true))
            .insert("c", mapOf("italic" to true))
            .insert("d")
        permutations(
            listOf("concurrent-format-base-v1", "concurrent-format-bold-v1", "concurrent-format-italic-v1"),
        ).forEach { order ->
            val doc = YDoc(clientId = 4, gc = false)
            order.forEach { name -> applyUpdate(doc, fixture(name)) }

            assertEquals(expected, doc.getText("body").toDelta())
            assertEquals(null, doc.store.pendingStructs)
        }
        val relay = YDoc(clientId = 4, gc = false)
        listOf("concurrent-format-base-v1", "concurrent-format-bold-v1", "concurrent-format-italic-v1")
            .forEach { name -> applyUpdate(relay, fixture(name)) }
        assertUpstreamAppliesUpdate(encodeStateAsUpdate(relay), "concurrent-format")
    }

    @Test
    fun upstreamYjsAppliesNativeFormattingRelayedByKotlin() {
        val doc = YDoc(clientId = 2, gc = false)
        applyUpdate(doc, fixture("text-format-insert-v1"))

        val update = encodeStateAsUpdate(doc)

        assertStandardV1(update)
        assertUpstreamAppliesUpdate(update, "formatted-text")
    }

    @Test
    fun upstreamYjsAppliesIncrementalNativeFormattingRelayedByKotlin() {
        val doc = YDoc(clientId = 2, gc = false)
        val baseline = fixture("text-format-base-v1")
        applyUpdate(doc, baseline)
        val baselineState = encodeStateVector(doc)
        applyUpdate(doc, fixture("text-format-partial-v1"))

        val incremental = encodeStateAsUpdate(doc, baselineState)

        assertStandardV1(incremental)
        assertUpstreamAppliesSequence("partial-formatted-text", baseline, incremental)
    }

    @Test
    fun upstreamYjsAppliesNativeRangeFormattingAuthoredByKotlin() {
        val doc = YDoc(clientId = 1, gc = false)
        val text = doc.getText("body")
        text.insert(0, "abcd")
        val baseline = encodeStateAsUpdate(doc)
        val baselineState = encodeStateVector(doc)

        text.format(1, 2, mapOf("bold" to true))
        val full = encodeStateAsUpdate(doc)
        val incremental = encodeStateAsUpdate(doc, baselineState)

        assertStandardV1(full)
        assertStandardV1(incremental)
        assertUpstreamAppliesUpdate(full, "partial-formatted-text")
        assertUpstreamAppliesSequence("partial-formatted-text", baseline, incremental)
    }

    @Test
    fun upstreamYjsAppliesAttributedTextInsertAuthoredByKotlin() {
        val doc = YDoc(clientId = 1, gc = false)
        doc.getText("body").insert(0, "ab", mapOf("bold" to true))

        val update = encodeStateAsUpdate(doc)

        assertStandardV1(update)
        assertUpstreamAppliesUpdate(update, "formatted-text")
    }

    @Test
    fun upstreamYjsAppliesAttributedEmbedInsertAuthoredByKotlin() {
        val doc = YDoc(clientId = 1, gc = false)
        doc.getText("body").insertEmbed(0, mapOf("image" to "x"), mapOf("bold" to true))

        val update = encodeStateAsUpdate(doc)

        assertStandardV1(update)
        assertUpstreamAppliesUpdate(update, "formatted-embed")
    }

    @Test
    fun upstreamYjsAppliesGenuineV2UpdatesAuthoredByKotlin() {
        val hello = YDoc(clientId = 1).also { it.getText("body").insert(0, "hello") }
        val formatted = YDoc(clientId = 1, gc = false).also {
            it.getText("body").insert(0, "ab", mapOf("bold" to true))
        }
        val array = YDoc(clientId = 1).also {
            it.getArray("items").insert(0, listOf("a", 42, true, null, byteArrayOf(1, 2)))
        }
        val xml = YDoc(clientId = 1, gc = false).also { doc ->
            val paragraph = doc.createXmlElement("p")
            doc.getXmlFragment("xml").push(paragraph)
            paragraph.setAttr("class", "intro")
            val text = doc.createXmlText()
            paragraph.push(text)
            text.insert(0, "hi")
            text.format(0, 2, mapOf("strong" to mapOf("level" to "1")))
        }
        val subdoc = YDoc(clientId = 1, gc = false).also {
            it.getArray("subs").push(
                YDoc(
                    guid = "child-guid",
                    gc = false,
                    autoLoad = true,
                    meta = mapOf("role" to "child"),
                ),
            )
        }
        val deleted = YDoc(clientId = 1, gc = false).also {
            val text = it.getText("body")
            text.insert(0, "hello")
            text.delete(1, 3)
        }
        val concurrent = YDoc(clientId = 4, gc = false).also { doc ->
            listOf("concurrent-format-base-v1", "concurrent-format-bold-v1", "concurrent-format-italic-v1")
                .forEach { name -> applyUpdate(doc, fixture(name)) }
        }

        listOf(
            Triple(hello, "hello", "hello"),
            Triple(formatted, "formatted-text", "formatted"),
            Triple(array, "array", "array"),
            Triple(xml, "xml-formatted", "xml"),
            Triple(subdoc, "subdoc-array", "subdoc"),
            Triple(deleted, "text-delete", "delete"),
            Triple(concurrent, "concurrent-format", "concurrent"),
        ).forEach { (doc, scenario, label) ->
            assertUpstreamAppliesUpdateV2(encodeStateAsUpdateV2(doc), scenario, label)
        }
    }

    @Test
    fun upstreamYjsAppliesMergedAndDiffedV2Updates() {
        val source = YDoc(clientId = 1, gc = false)
        val text = source.getText("body")
        text.insert(0, "hello")
        val baselineState = encodeStateVector(source)
        val baseline = encodeStateAsUpdateV2(source)
        text.delete(1, 3)
        val incremental = encodeStateAsUpdateV2(source, baselineState)

        val merged = mergeUpdatesV2(listOf(baseline, incremental))
        val diffed = diffUpdateV2(merged, baselineState)

        assertUpstreamAppliesUpdateV2(merged, "text-delete", "merged")
        assertUpstreamAppliesSequenceV2("text-delete", baseline, diffed)
        assertEquals(
            decodeStateVector(encodeStateVector(source)),
            decodeStateVector(encodeStateVectorFromUpdateV2(merged)),
        )
    }

    @Test
    fun updateV2EventEmitsGenuineUpstreamCompatiblePayload() {
        val doc = YDoc(clientId = 1, gc = false)
        val updates = mutableListOf<ByteArray>()
        doc.onUpdateV2 { update, _, _, _ -> updates.add(update) }

        doc.getText("body").insert(0, "ab", mapOf("bold" to true))

        assertEquals(1, updates.size)
        assertUpstreamAppliesUpdateV2(updates.single(), "formatted-text", "event")
    }

    @Test
    fun updateEventEmitsStandardUpstreamCompatibleV1ForRootText() {
        val doc = YDoc(clientId = 1, gc = false)
        val updates = mutableListOf<ByteArray>()
        doc.observeUpdates { update, _ -> updates.add(update) }

        doc.getText("body").insert(0, "ab", mapOf("bold" to true))

        assertEquals(1, updates.size)
        assertStandardV1(updates.single())
        assertUpstreamAppliesUpdate(updates.single(), "formatted-text")
    }

    @Test
    fun updateEventEmitsStandardUpstreamCompatibleV1DeleteSet() {
        val doc = YDoc(clientId = 1, gc = false)
        val updates = mutableListOf<ByteArray>()
        doc.observeUpdates { update, _ -> updates.add(update) }
        val subs = doc.getMap("subs")

        subs.set("child", YDoc(guid = "child", shouldLoad = false))
        subs.delete("child")

        assertEquals(2, updates.size)
        updates.forEach(::assertStandardV1)
        assertUpstreamAppliesSequence("subdoc-delete", *updates.toTypedArray())
    }

    @Test
    fun upstreamYjsAppliesNativeXmlTextFormattingAuthoredByKotlin() {
        val doc = YDoc(clientId = 1, gc = false)
        val fragment = doc.getXmlFragment("xml")
        val paragraph = doc.createXmlElement("p")
        fragment.push(paragraph)
        paragraph.setAttr("class", "intro")
        val text = doc.createXmlText()
        paragraph.push(text)
        text.insert(0, "hi")
        text.format(0, 2, mapOf("strong" to mapOf("level" to "1")))

        val update = encodeStateAsUpdate(doc)

        assertStandardV1(update)
        assertUpstreamAppliesUpdate(update, "xml-formatted")
    }

    @Test
    fun upstreamYjsAppliesMapBackedXmlHookAuthoredByKotlin() {
        val doc = YDoc(clientId = 1, gc = false)
        val hook = doc.createXmlHook("Widget")
        doc.getXmlFragment("xml").push(hook)
        hook.set("x", 1)
        hook.set("nested", linkedMapOf("ok" to true))

        val update = encodeStateAsUpdate(doc)

        assertStandardV1(update)
        assertUpstreamAppliesUpdate(update, "xml-hook")
    }

    @Test
    fun standardTransactionUpdatePreservesNativeFormatMarkers() {
        val source = YDoc(clientId = 2, gc = false)
        val updates = mutableListOf<ByteArray>()
        source.observeUpdates { update, _ -> updates.add(update) }

        applyUpdate(source, fixture("text-format-insert-v1"))

        val transactionUpdate = updates.single()
        assertStandardV1(transactionUpdate)
        assertUpstreamAppliesUpdate(transactionUpdate, "formatted-text")
        val target = YDoc(clientId = 3, gc = false)
        applyUpdate(target, transactionUpdate)
        assertEquals(source.getText("body").toDelta(), target.getText("body").toDelta())
    }

    @Test
    fun appliesNativeXmlTreeProducedByUpstreamYjs() {
        val doc = YDoc(clientId = 3, gc = false)

        applyUpdate(doc, fixture("xml-basic-full-v1"))

        assertXml(doc, cssClass = "intro", text = "hi")
        assertEquals(mapOf(1L to 5L), decodeStateVector(doc.encodeStateVector()))
    }

    @Test
    fun appliesIncrementalNativeXmlInEitherOrder() {
        listOf(
            listOf("xml-owner-v1", "xml-content-v1"),
            listOf("xml-content-v1", "xml-owner-v1"),
        ).forEach { order ->
            val doc = YDoc(clientId = 3, gc = false)
            order.forEach { name -> applyUpdate(doc, fixture(name)) }

            assertXml(doc, cssClass = "intro", text = "hi")
            assertEquals(mapOf(1L to 5L), decodeStateVector(doc.encodeStateVector()))
            assertEquals(null, doc.store.pendingStructs)
        }
    }

    @Test
    fun appliesCrossClientNativeXmlInEitherOrder() {
        listOf(
            listOf("xml-owner-v1", "xml-cross-client-content-v1"),
            listOf("xml-cross-client-content-v1", "xml-owner-v1"),
        ).forEach { order ->
            val doc = YDoc(clientId = 3, gc = false)
            order.forEach { name -> applyUpdate(doc, fixture(name)) }

            assertXml(doc, cssClass = "remote", text = "ok")
            assertEquals(mapOf(1L to 1L, 2L to 4L), decodeStateVector(doc.encodeStateVector()))
            assertEquals(null, doc.store.pendingStructs)
        }
    }

    @Test
    fun upstreamYjsAppliesNativeXmlRelayedByKotlin() {
        val doc = YDoc(clientId = 3, gc = false)
        applyUpdate(doc, fixture("xml-basic-full-v1"))
        doc.getXmlFragment("xml")

        val update = encodeStateAsUpdate(doc)

        assertStandardV1(update)
        assertUpstreamAppliesUpdate(update, "xml")
    }

    @Test
    fun appliesAndRelaysFormattedNativeXmlText() {
        val doc = YDoc(clientId = 3, gc = false)
        applyUpdate(doc, fixture("xml-formatted-full-v1"))
        val paragraph = doc.getXmlFragment("xml").getType(0) as YXmlElementType
        val text = paragraph.getType(0) as YXmlTextType

        assertEquals(
            YTextDelta().insert("hi", mapOf("strong" to mapOf("level" to "1"))),
            text.toDelta(),
        )
        assertEquals("<p class=\"intro\"><strong level=\"1\">hi</strong></p>", doc.getXmlFragment("xml").toString())
        val update = encodeStateAsUpdate(doc)
        assertStandardV1(update)
        assertUpstreamAppliesUpdate(update, "xml-formatted")
    }

    @Test
    fun preMaterializedRootXmlElementRetainsItsKnownKind() {
        val doc = YDoc(clientId = 3, gc = false)
        val article = doc.getXmlElement("article", "article")

        applyUpdate(doc, fixture("xml-root-element-v1"))

        assertEquals(mapOf("class" to "root"), article.getAttrs())
        assertEquals(YTextDelta().insert("hi"), (article.getType(0) as YXmlTextType).toDelta())
        assertEquals("<article class=\"root\">hi</article>", article.toString())
        assertEquals(mapOf(1L to 4L), decodeStateVector(doc.encodeStateVector()))
    }

    @Test
    fun upstreamYjsAppliesOwnerFirstXmlAuthoredByKotlin() {
        val doc = YDoc(clientId = 1, gc = false)
        val fragment = doc.getXmlFragment("xml")
        val paragraph = doc.createXmlElement("p")
        fragment.push(paragraph)
        paragraph.setAttr("class", "intro")
        val text = doc.createXmlText()
        paragraph.push(text)
        text.insert(0, "hi")

        val update = encodeStateAsUpdate(doc)

        assertStandardV1(update)
        assertUpstreamAppliesUpdate(update, "xml")
    }

    @Test
    fun appliesDefaultMapSubdocumentProducedByUpstreamYjs() {
        val doc = YDoc(clientId = 2, gc = false)
        val events = mutableListOf<YSubdocEvent>()
        doc.observeSubdocs(events::add)

        applyUpdate(doc, fixture("subdoc-map-default-v1"))

        val child = doc.getMap("subs").get("child") as YDoc
        assertEquals("child", child.guid)
        assertEquals(true, child.gc)
        assertEquals(false, child.shouldLoad)
        assertEquals(false, child.autoLoad)
        assertEquals(null, child.meta)
        assertEquals(listOf(child), events.single().added)
        assertEquals(emptyList(), events.single().loaded)
        assertEquals(setOf("child"), doc.getSubdocGuids())
        assertStandardV1(encodeStateAsUpdate(doc))
        assertUpstreamAppliesUpdate(encodeStateAsUpdate(doc), "subdoc-map")
    }

    @Test
    fun appliesLoadedArraySubdocumentProducedByUpstreamYjs() {
        val doc = YDoc(clientId = 2, gc = false)
        val events = mutableListOf<YSubdocEvent>()
        doc.observeSubdocs(events::add)

        applyUpdate(doc, fixture("subdoc-array-options-v1"))

        val child = doc.getArray("subs").get(0) as YDoc
        assertEquals("child-guid", child.guid)
        assertEquals(false, child.gc)
        assertEquals(true, child.shouldLoad)
        assertEquals(true, child.autoLoad)
        assertEquals(mapOf("role" to "child"), child.meta)
        assertEquals(listOf(child), events.single().added)
        assertEquals(listOf(child), events.single().loaded)
        val update = encodeStateAsUpdate(doc)
        assertStandardV1(update)
        assertUpstreamAppliesUpdate(update, "subdoc-array")
    }

    @Test
    fun sameGuidSubdocumentsRemainDistinctInstances() {
        val doc = YDoc(clientId = 2, gc = false)

        applyUpdate(doc, fixture("subdoc-duplicate-guid-v1"))

        val children = doc.getArray("subs").toList().map { value -> value as YDoc }
        assertEquals(2, children.size)
        assertTrue(children[0] !== children[1])
        assertEquals(2, doc.getSubdocs().size)
        assertEquals(setOf("same-guid"), doc.getSubdocGuids())
        assertEquals(mapOf(1L to 2L), decodeStateVector(doc.encodeStateVector()))

        applyUpdate(doc, fixture("subdoc-duplicate-guid-v1"))
        val repeated = doc.getArray("subs").toList().map { value -> value as YDoc }
        assertTrue(children[0] === repeated[0])
        assertTrue(children[1] === repeated[1])
    }

    @Test
    fun upstreamYjsAppliesSafeKotlinAuthoredSubdocument() {
        val doc = YDoc(clientId = 1, gc = false)
        doc.getMap("subs").set("child", YDoc(guid = "child", shouldLoad = false))

        val update = encodeStateAsUpdate(doc)

        assertStandardV1(update)
        assertUpstreamAppliesUpdate(update, "subdoc-map")
    }

    @Test
    fun emitsStandardV1DeleteSetsForFullAndIncrementalTextUpdates() {
        val doc = YDoc(clientId = 1, gc = false)
        val text = doc.getText("body")
        text.insert(0, "hello")
        val baseline = encodeStateAsUpdate(doc)
        val baselineState = encodeStateVector(doc)

        text.delete(1, 3)
        val full = encodeStateAsUpdate(doc)
        val incremental = encodeStateAsUpdate(doc, baselineState)

        assertStandardV1(full)
        assertStandardV1(incremental)
        assertUpstreamAppliesUpdate(full, "text-delete")
        assertUpstreamAppliesSequence("text-delete", baseline, incremental)
    }

    @Test
    fun emitsStandardV1XmlDeletionAndPreservesRemoteDeleteEvents() {
        val source = YDoc(clientId = 1, gc = false)
        val fragment = source.getXmlFragment("xml")
        fragment.push(source.createXmlElement("p"))
        val baseline = encodeStateAsUpdate(source)
        val baselineState = encodeStateVector(source)
        fragment.delete(0)
        val incremental = encodeStateAsUpdate(source, baselineState)

        assertStandardV1(incremental)
        assertUpstreamAppliesSequence("xml-delete", baseline, incremental)

        val target = YDoc(clientId = 2, gc = false)
        applyUpdate(target, baseline)
        val events = mutableListOf<YEvent>()
        target.getXmlFragment("xml").observe(events::add)
        applyUpdate(target, incremental)
        assertEquals(0, target.getXmlFragment("xml").length)
        assertEquals(1, events.single().transaction?.deletedItemCount)
    }

    @Test
    fun emitsStandardV1SubdocumentDeletionAndRemovalEvent() {
        val source = YDoc(clientId = 1, gc = false)
        val subs = source.getMap("subs")
        subs.set("child", YDoc(guid = "child", shouldLoad = false))
        val baseline = encodeStateAsUpdate(source)
        val baselineState = encodeStateVector(source)
        subs.delete("child")
        val incremental = encodeStateAsUpdate(source, baselineState)

        assertStandardV1(incremental)
        assertUpstreamAppliesSequence("subdoc-delete", baseline, incremental)

        val target = YDoc(clientId = 2, gc = false)
        applyUpdate(target, baseline)
        val child = target.getMap("subs").get("child") as YDoc
        val events = mutableListOf<YSubdocEvent>()
        target.observeSubdocs(events::add)
        applyUpdate(target, incremental)
        assertEquals(null, target.getMap("subs").get("child"))
        assertEquals(listOf(listOf(child), listOf(child)), events.map(YSubdocEvent::removed))
    }

    @Test
    fun emitsStandardV1SubdocumentsInsideTextAndXmlText() {
        val textDoc = YDoc(clientId = 1, gc = false)
        val text = textDoc.getText("body")
        insertContent(text, 0, ContentDoc("text-child"))
        val textUpdate = encodeStateAsUpdate(textDoc)

        assertStandardV1(textUpdate)
        assertUpstreamAppliesUpdate(textUpdate, "subdoc-text")
        val textTarget = YDoc(clientId = 2, gc = false)
        textTarget.getText("body")
        applyUpdate(textTarget, textUpdate)
        assertEquals("text-child", (textTarget.getText("body").get(0) as YDoc).guid)

        val xmlDoc = YDoc(clientId = 1, gc = false)
        val paragraph = xmlDoc.createXmlElement("p")
        val xmlText = xmlDoc.createXmlText()
        xmlDoc.getXmlFragment("xml").push(paragraph)
        paragraph.push(xmlText)
        insertContent(xmlText, 0, ContentDoc("xml-child"))
        val xmlUpdate = encodeStateAsUpdate(xmlDoc)

        assertStandardV1(xmlUpdate)
        assertUpstreamAppliesUpdate(xmlUpdate, "subdoc-xml-text")
        val xmlTarget = YDoc(clientId = 2, gc = false)
        applyUpdate(xmlTarget, xmlUpdate)
        val remoteParagraph = xmlTarget.getXmlFragment("xml").getType(0) as YXmlElementType
        val remoteText = remoteParagraph.getType(0) as YXmlTextType
        assertEquals("xml-child", (remoteText.get(0) as YDoc).guid)
    }

    @Test
    fun emitsStandardV1DeletionForSubdocumentsInsideTextAndXmlText() {
        val textDoc = YDoc(clientId = 1, gc = false)
        val text = textDoc.getText("body")
        insertContent(text, 0, ContentDoc("text-child"))
        val textBaseline = encodeStateAsUpdate(textDoc)
        val textState = encodeStateVector(textDoc)
        text.delete(0)
        val textDelete = encodeStateAsUpdate(textDoc, textState)

        assertStandardV1(textBaseline)
        assertStandardV1(textDelete)
        assertUpstreamAppliesSequence("subdoc-text-delete", textBaseline, textDelete)

        val xmlDoc = YDoc(clientId = 1, gc = false)
        val paragraph = xmlDoc.createXmlElement("p")
        val xmlText = xmlDoc.createXmlText()
        xmlDoc.getXmlFragment("xml").push(paragraph)
        paragraph.push(xmlText)
        insertContent(xmlText, 0, ContentDoc("xml-child"))
        val xmlBaseline = encodeStateAsUpdate(xmlDoc)
        val xmlState = encodeStateVector(xmlDoc)
        xmlText.delete(0)
        val xmlDelete = encodeStateAsUpdate(xmlDoc, xmlState)

        assertStandardV1(xmlBaseline)
        assertStandardV1(xmlDelete)
        assertUpstreamAppliesSequence("subdoc-xml-text-delete", xmlBaseline, xmlDelete)
    }

    @Test
    fun unsupportedXmlAndSubdocumentShapesRequireExplicitLosslessApis() {
        val staticXml = YDoc(clientId = 1)
        staticXml.getXmlFragment("xml").push(YXmlElement("p"))
        assertFailsWith<UnsupportedYjsStandardUpdateException> { encodeStateAsUpdate(staticXml) }
        assertLegacyYks(encodeStateAsUpdateLossless(staticXml))

        val fragmentAttributes = YDoc(clientId = 1)
        fragmentAttributes.getXmlFragment("xml").setAttr("class", "private")
        assertFailsWith<UnsupportedYjsStandardUpdateException> { encodeStateAsUpdate(fragmentAttributes) }
        assertLegacyYks(encodeStateAsUpdateLossless(fragmentAttributes))

        val nonstandardSubdoc = YDoc(clientId = 1)
        nonstandardSubdoc.getArray("subs").push(
            YDoc(guid = "child", collectionId = "private", shouldLoad = false),
        )
        assertFailsWith<UnsupportedYjsStandardUpdateException> { encodeStateAsUpdate(nonstandardSubdoc) }
        assertLegacyYks(encodeStateAsUpdateLossless(nonstandardSubdoc))

        val loadOnlySubdoc = YDoc(clientId = 1)
        loadOnlySubdoc.getArray("subs").push(YDoc(guid = "child"))
        val loadOnlyUpdate = encodeStateAsUpdate(loadOnlySubdoc)
        assertStandardV1(loadOnlyUpdate)
        assertUpstreamAppliesUpdate(loadOnlyUpdate, "subdoc-array-default")
    }

    @Test
    fun upstreamYjsAppliesHelloUpdateProducedByKotlin() {
        val doc = YDoc(clientId = 1)
        doc.getText("body").insert(0, "hello")

        assertUpstreamApplies(doc, "hello")
    }

    @Test
    fun upstreamYjsAppliesRootArrayUpdateProducedByKotlin() {
        val doc = YDoc(clientId = 1)
        doc.getArray("items").insert(0, listOf("a", 42, true, null, byteArrayOf(1, 2)))

        assertUpstreamApplies(doc, "array")
    }

    @Test
    fun upstreamYjsAppliesRootMapUpdateProducedByKotlin() {
        val doc = YDoc(clientId = 1)
        doc.getMap("meta").apply {
            set("title", "hello")
            set("count", 2)
        }

        assertUpstreamApplies(doc, "map")
    }

    @Test
    fun upstreamYjsAppliesPackedMapHistoryProducedByKotlin() {
        val doc = YDoc(clientId = 1, gc = false)
        val map = doc.getMap("map")
        doc.transact {
            repeat(5_000) { index -> map.set("key", index) }
        }

        assertEquals(2, decodeUpdate(doc.encodeStateAsUpdate()).structs.size)
        assertUpstreamApplies(doc, "map-history")
    }

    @Test
    fun upstreamYjsAppliesNestedMapUpdateProducedByKotlin() {
        val doc = YDoc(clientId = 1)
        val profile = doc.createMap()
        doc.getMap("root").set("profile", profile)
        profile.set("name", "Ada")

        assertUpstreamApplies(doc, "nested-map")
    }

    @Test
    fun upstreamYjsAppliesNestedTextUpdateProducedByKotlin() {
        val doc = YDoc(clientId = 1)
        val text = doc.createText()
        doc.getArray("nodes").push(text)
        text.insert(0, "child")

        assertUpstreamApplies(doc, "nested-text")
    }

    @Test
    fun upstreamYjsAppliesAnswerDocumentProducedByKotlin() {
        val doc = YDoc(clientId = 1, gc = false)
        val question = doc.createMap()
        doc.getMap("questions").set("42", question)
        question.set("id", 42)
        question.set("status", "IN_PROGRESS")
        question.set("lastAppliedSourceId", null)
        question.set("assignUser", listOf("user-1", "사용자-😀"))
        question.set(
            "answer",
            linkedMapOf(
                "type" to "doc",
                "attrs" to linkedMapOf("answer_node_ids" to listOf("n4", "선택-😀")),
                "content" to listOf(
                    linkedMapOf(
                        "type" to "paragraph",
                        "attrs" to linkedMapOf("index" to 0, "node_ids" to listOf("n4")),
                        "content" to listOf(linkedMapOf("type" to "text", "text" to "저장된 답변 😀")),
                    ),
                ),
            ),
        )
        question.set("lastMutationId", "mutation-1")

        val paragraph = doc.createXmlElement("paragraph")
        doc.getXmlFragment("42").push(paragraph)
        paragraph.setAttr("index", 0)
        paragraph.setAttr("node_ids", listOf("n4"))
        val text = doc.createXmlText()
        paragraph.push(text)
        text.insert(0, "저장된 답변 😀")
        val selection = doc.createXmlElement("selectionOption")
        paragraph.push(selection)
        selection.setAttr("node_id", "선택-😀")
        selection.setAttr("selected", true)
        selection.setAttr("score", 1.5)

        assertUpstreamApplies(doc, "answer-document")
    }

    @Test
    fun upstreamYjsAppliesIncrementalNestedMapUpdateProducedByKotlin() {
        val doc = YDoc(clientId = 1)
        val profile = doc.createMap()
        doc.getMap("root").set("profile", profile)
        profile.set("name", "Ada")
        val baseline = encodeStateAsUpdate(doc)
        val baselineState = encodeStateVector(doc)

        profile.set("city", "Seoul")
        val incremental = encodeStateAsUpdate(doc, baselineState)

        assertUpstreamAppliesSequence("nested-map-update", baseline, incremental)
    }

    private fun assertUpstreamApplies(doc: YDoc, scenario: String) {
        assertUpstreamAppliesUpdate(encodeStateAsUpdate(doc), scenario)
    }

    private fun assertXml(doc: YDoc, cssClass: String, text: String) {
        val fragment = doc.getXmlFragment("xml")
        val paragraph = fragment.getType(0) as YXmlElementType
        val xmlText = paragraph.getType(0) as YXmlTextType
        assertEquals("p", paragraph.nodeName)
        assertEquals(mapOf("class" to cssClass), paragraph.getAttrs())
        assertEquals(YTextDelta().insert(text), xmlText.toDelta())
        assertEquals("<p class=\"$cssClass\">$text</p>", fragment.toString())
    }

    private fun assertStandardV1(update: ByteArray) {
        assertTrue(
            update.size < 4 || update[0] != 'Y'.code.toByte() || update[1] != 'K'.code.toByte() ||
                update[2] != 'S'.code.toByte(),
        )
    }

    private fun assertLegacyYks(update: ByteArray) {
        assertTrue(
            update.size >= 4 && update[0] == 'Y'.code.toByte() && update[1] == 'K'.code.toByte() &&
                update[2] == 'S'.code.toByte() &&
                update[3] in setOf(1.toByte(), 2.toByte(), 3.toByte(), 4.toByte()),
        )
    }

    private fun assertUpstreamAppliesUpdate(bytes: ByteArray, scenario: String) {
        val update = Files.createTempFile("yks-$scenario-v1-", ".bin")

        try {
            Files.write(update, bytes)
            val process = ProcessBuilder(
                "node",
                "interop/yjs-v1/verify-update.mjs",
                update.absolutePathString(),
                scenario,
            )
                .directory(projectDirectory.toFile())
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            assertTrue(exitCode == 0, "upstream Yjs rejected the Kotlin update:\n$output")
        } finally {
            Files.deleteIfExists(update)
        }
    }

    private fun assertUpstreamAppliesUpdateV2(bytes: ByteArray, scenario: String, label: String) {
        val update = Files.createTempFile("yks-$label-v2-", ".bin")
        try {
            Files.write(update, bytes)
            val process = ProcessBuilder(
                "node",
                "interop/yjs-v1/verify-update-v2.mjs",
                update.absolutePathString(),
                scenario,
            )
                .directory(projectDirectory.toFile())
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            assertTrue(process.waitFor() == 0, "upstream Yjs rejected the Kotlin V2 update:\n$output")
        } finally {
            Files.deleteIfExists(update)
        }
    }

    private fun assertUpstreamAppliesSequenceV2(scenario: String, vararg updates: ByteArray) {
        val paths = updates.mapIndexed { index, update ->
            Files.createTempFile("yks-$scenario-v2-$index-", ".bin").also { Files.write(it, update) }
        }
        try {
            val process = ProcessBuilder(
                listOf("node", "interop/yjs-v1/verify-update-sequence-v2.mjs", scenario) +
                    paths.map(Path::absolutePathString),
            )
                .directory(projectDirectory.toFile())
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            assertTrue(process.waitFor() == 0, "upstream Yjs rejected the Kotlin V2 sequence:\n$output")
        } finally {
            paths.forEach(Files::deleteIfExists)
        }
    }

    private fun assertUpstreamAppliesSequence(scenario: String, vararg updates: ByteArray) {
        val paths = updates.mapIndexed { index, update ->
            Files.createTempFile("yks-$scenario-v1-$index-", ".bin").also { path -> Files.write(path, update) }
        }
        try {
            val process = ProcessBuilder(
                listOf(
                    "node",
                    "interop/yjs-v1/verify-update-sequence.mjs",
                    scenario,
                ) + paths.map(Path::absolutePathString),
            )
                .directory(projectDirectory.toFile())
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            assertTrue(exitCode == 0, "upstream Yjs rejected the Kotlin update sequence:\n$output")
        } finally {
            paths.forEach(Files::deleteIfExists)
        }
    }

    private fun <T> permutations(values: List<T>): List<List<T>> = when {
        values.size <= 1 -> listOf(values)
        else -> values.flatMapIndexed { index, value ->
            permutations(values.filterIndexed { candidateIndex, _ -> candidateIndex != index })
                .map { rest -> listOf(value) + rest }
        }
    }
}
