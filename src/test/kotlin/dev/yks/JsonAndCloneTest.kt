package dev.yks

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JsonAndCloneTest {
    @Test
    fun documentToJsonSerializesRootTypes() {
        val doc = YDoc(clientId = 1)
        doc.getArray("array").push(listOf("a", null, 2))
        doc.getMap("map").set("title", "hello")
        doc.getMap("map").set("published", true)
        doc.getText("text").insert(0, "body")

        assertEquals(
            mapOf(
                "array" to listOf("a", null, 2L),
                "map" to mapOf("published" to true, "title" to "hello"),
                "text" to "body",
            ),
            doc.toJson(),
        )
    }

    @Test
    fun documentToJsonDiscoversRootsFromRemoteUpdates() {
        val source = YDoc(clientId = 1)
        source.getArray("array").push(listOf("remote"))
        source.getMap("map").set("count", 1)
        source.getText("text").insert(0, "hi")

        val target = YDoc(clientId = 2)
        target.applyUpdate(source.encodeStateAsUpdate())

        assertEquals(source.toJson(), target.toJson())
    }

    @Test
    fun arrayAccessorsExposeVisibleContent() {
        val doc = YDoc(clientId = 1)
        val array = doc.getArray("items")
        array.push(listOf("a", null, "c"))
        array.insert(2, listOf("b"))

        assertEquals(4, array.length)
        assertEquals(null, array.get(1))
        assertEquals(listOf(null, "b"), array.slice(1, 3))
        assertEquals(array.toList(), array.toJson())
    }

    @Test
    fun mapAccessorsExposeVisibleEntries() {
        val doc = YDoc(clientId = 1)
        val map = doc.getMap("meta")
        map.setAttr("title", "old")
        map.set("title", "new")
        map.set("nullable", null)

        assertEquals(2, map.size)
        assertTrue(map.has("title"))
        assertTrue(map.has("nullable"))
        assertFalse(map.has("missing"))
        assertEquals("new", map.getAttr("title"))
        assertEquals(setOf("nullable", "title"), map.keys())
        assertEquals(mapOf("nullable" to null, "title" to "new"), map.toJson())

        map.deleteAttr("title")
        assertEquals(mapOf("nullable" to null), map.toMap())
    }

    @Test
    fun cloneDocCopiesCurrentStateWithoutSharingFutureMutations() {
        val source = YDoc(clientId = 1)
        source.getText("body").insert(0, "hello", mapOf("bold" to true))
        source.getArray("items").push(listOf("a", "b"))
        source.getArray("items").delete(0)

        val clone = cloneDoc(source)

        assertEquals(source.toJson(), clone.toJson())
        assertEquals(source.getText("body").toDelta(), clone.getText("body").toDelta())

        source.getText("body").insert(5, "!")
        assertEquals("hello", clone.getText("body").toString())
        assertEquals("hello!", source.getText("body").toString())
    }

    @Test
    fun cloneDocNormalizesIntegralNumbersLikeLib0AndPreservesNegativeZero() {
        val source = YDoc(clientId = 1)
        source.getArray("numbers").push(listOf(-0.0, 1.0, 1.5))

        val values = cloneDoc(source).getArray("numbers").toList()

        assertEquals(listOf(-0.0, 1L, 1.5), values)
        assertEquals(
            java.lang.Double.doubleToRawLongBits(-0.0),
            java.lang.Double.doubleToRawLongBits(values[0] as Double),
        )
    }

    @Test
    fun documentConstructionHelpersAcceptDocOptions() {
        val source = YDoc(clientId = 1)
        source.getText("body").insert(0, "hello")
        val options = YDocOptions(
            clientId = 7,
            guid = "created-doc",
            collectionId = "collection",
            gc = false,
            gcFilter = { false },
            meta = mapOf("role" to "mirror"),
            shouldLoad = false,
            autoLoad = true,
            isSuggestionDoc = true,
        )

        val created = createDocFromUpdate(source.encodeStateAsUpdate(), options)
        val createdV2 = createDocFromUpdateV2(source.encodeStateAsUpdate(), options.copy(clientId = 8, guid = "created-v2"))
        val cloned = cloneDoc(source, options.copy(clientId = 9, guid = "cloned-doc"))

        listOf(created, createdV2, cloned).forEach { doc ->
            assertEquals(source.toJson(), doc.toJson())
            assertEquals("collection", doc.collectionid)
            assertFalse(doc.gc)
            assertFalse(doc.gcFilter(GC(Id(1, 0), 1)))
            assertEquals(mapOf("role" to "mirror"), doc.meta)
            assertFalse(doc.shouldLoad)
            assertTrue(doc.autoLoad)
            assertTrue(doc.isSuggestionDoc)
            assertFalse(doc.cleanupFormatting)
        }
        assertEquals(7, created.clientID)
        assertEquals("created-doc", created.guid)
        assertEquals(8, createdV2.clientID)
        assertEquals("created-v2", createdV2.guid)
        assertEquals(9, cloned.clientID)
        assertEquals("cloned-doc", cloned.guid)
    }

    @Test
    fun createDocFromUpdateBuildsADocumentFromMergedUpdates() {
        val left = YDoc(clientId = 1)
        val right = YDoc(clientId = 2)
        left.getArray("items").push(listOf("left"))
        right.getArray("items").push(listOf("right"))

        val doc = createDocFromUpdate(mergeUpdates(listOf(left.encodeStateAsUpdate(), right.encodeStateAsUpdate())))

        assertEquals(mapOf("items" to listOf("left", "right")), doc.toJson())
    }
}
