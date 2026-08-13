package dev.yks

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RootSchemaRegistryTest {
    @Test
    fun schemasMaterializeAllAmbiguousRootKindsBeforeV1AndV2Integration() {
        val sources = rootSchemaSources()
        val registry = rootSchemaRegistry()
        listOf(false, true).forEachIndexed { index, isV2 ->
            val target = YDoc(YDocOptions(clientId = 20L + index), registry)
            assertTrue(target.rootNames().isEmpty())

            sources.forEach { source ->
                val update = try {
                    if (isV2) source.encodeStateAsUpdateV2() else source.encodeStateAsUpdate()
                } catch (error: UnsupportedYjsStandardUpdateException) {
                    throw AssertionError("root ${source.rootNames()} is not standard-wire representable", error)
                }
                if (isV2) target.applyUpdateV2(update) else target.applyUpdate(update)
            }

            assertIs<YArray>(target.share["items"])
            assertIs<YMap>(target.share["meta"])
            assertIs<YText>(target.share["body"])
            assertIs<YXmlFragment>(target.share["xml"])
            assertIs<YXmlElementType>(target.share["article"])
            assertIs<YXmlHook>(target.share["hook"])
            assertIs<YXmlTextType>(target.share["xml-text"])
            assertEquals(listOf("a"), target.getArray("items").toArray())
            assertEquals("hello", target.getText("body").toString())
            assertEquals("<p>x</p>", target.getXmlFragment("xml").toString())
            assertEquals("<article>root</article>", target.getXmlElement("article", "article").toString())
            assertEquals(1L, target.getXmlHook("hook", "widget").get("count"))
            assertEquals("rich", target.getXmlText("xml-text").toString())
        }
    }

    @Test
    fun registeringAnAbsentSchemaIsLazyUntilRootAccessOrUpdate() {
        val doc = YDoc(clientId = 1)

        assertSame(doc, doc.registerRootSchema("body", YRootSchema.Text))
        assertTrue(doc.rootNames().isEmpty())
        assertTrue(doc.share.isEmpty())

        assertIs<YText>(doc["body"])
        assertEquals(setOf("body"), doc.rootNames())
    }

    @Test
    fun installingSchemasMaterializesExistingUnopenedRootsWithoutChangingWireState() {
        val source = YDoc(clientId = 1).also { it.getText("body").insert(0, "hello") }
        val target = YDoc(clientId = 2)
        target.applyUpdate(source.encodeStateAsUpdate())
        val before = target.encodeStateAsUpdate()

        assertIs<YUnopenedRoot>(target.share["body"])
        target.installRootSchemas(YRootSchemaRegistry(mapOf("body" to YRootSchema.Text)))

        assertIs<YText>(target.share["body"])
        assertEquals("hello", target.getText("body").toString())
        assertTrue(before.contentEquals(target.encodeStateAsUpdate()))
    }

    @Test
    fun resolverRunsOncePerRootAndNeverReceivesNestedAliases() {
        val source = YDoc(clientId = 1)
        val nested = source.createMap().also { map -> map.set("name", "Ada") }
        source.getArray("items").push(nested)
        val firstState = source.encodeStateVector()
        val first = source.encodeStateAsUpdate()
        source.getArray("items").push("tail")
        val second = source.encodeStateAsUpdate(firstState)
        val calls = mutableListOf<String>()
        val registry = YRootSchemaRegistry(
            resolver = YRootSchemaResolver { name ->
                calls.add(name)
                if (name == "items") YRootSchema.Array else null
            },
        )
        val target = YDoc(YDocOptions(clientId = 2), registry)

        target.applyUpdate(first)
        target.applyUpdate(second)

        assertEquals(listOf("items"), calls)
        assertEquals("Ada", (target.getArray("items").get(0) as YMap).get("name"))
        assertEquals("tail", target.getArray("items").get(1))
    }

    @Test
    fun conflictsFailBeforeChangingTheConfiguredRegistryOrDocumentState() {
        val doc = YDoc(clientId = 1)
        val text = doc.getText("body")
        text.insert(0, "safe")
        val originalRegistry = doc.rootSchemas

        assertFailsWith<YRootSchemaConflictException> {
            doc.installRootSchemas(YRootSchemaRegistry(mapOf("body" to YRootSchema.Map)))
        }
        assertSame(originalRegistry, doc.rootSchemas)
        assertSame(text, doc.getText("body"))
        assertEquals("safe", text.toString())

        val typed = YDoc(
            YDocOptions(clientId = 2),
            YRootSchemaRegistry(mapOf("article" to YRootSchema.XmlElement("article"))),
        )
        assertEquals("article", typed.getXmlElement("article").nodeName)
        assertFailsWith<YRootSchemaConflictException> { typed.getXmlElement("article", "section") }
        assertFailsWith<YRootSchemaConflictException> { typed.getArray("article") }
        assertEquals(setOf("article"), typed.rootNames())

        val unopened = YDoc(
            YDocOptions(clientId = 3),
            YRootSchemaRegistry(mapOf("article" to YRootSchema.XmlElement("article"))),
        )
        assertFailsWith<YRootSchemaConflictException> { unopened.getXmlElement("article", "section") }
        assertTrue(unopened.rootNames().isEmpty())
    }

    private fun rootSchemaSources(): List<YDoc> = listOf(
        YDoc(clientId = 10, gc = false).also { it.getArray("items").push("a") },
        YDoc(clientId = 11, gc = false).also { it.getMap("meta").set("title", "schema") },
        YDoc(clientId = 12, gc = false).also { it.getText("body").insert(0, "hello") },
        YDoc(clientId = 13, gc = false).also { source ->
            val paragraph = source.createXmlElement("p")
            source.getXmlFragment("xml").push(paragraph)
            val text = source.createXmlText()
            paragraph.push(text)
            text.insert(0, "x")
        },
        YDoc(clientId = 14, gc = false).also { source ->
            val text = source.createXmlText()
            source.getXmlElement("article", "article").push(text)
            text.insert(0, "root")
        },
        YDoc(clientId = 15, gc = false).also { it.getXmlHook("hook", "widget").set("count", 1) },
        YDoc(clientId = 16, gc = false).also { it.getXmlText("xml-text").insert(0, "rich") },
    )

    private fun rootSchemaRegistry(): YRootSchemaRegistry = YRootSchemaRegistry(
        mapOf(
            "items" to YRootSchema.Array,
            "meta" to YRootSchema.Map,
            "body" to YRootSchema.Text,
            "xml" to YRootSchema.XmlFragment,
            "article" to YRootSchema.XmlElement("article"),
            "hook" to YRootSchema.XmlHook("widget"),
            "xml-text" to YRootSchema.XmlText,
        ),
    )
}
