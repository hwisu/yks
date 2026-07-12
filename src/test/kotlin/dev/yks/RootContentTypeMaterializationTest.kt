package dev.yks

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RootContentTypeMaterializationTest {
    @Test
    fun unopenedAmbiguousRootsStayUndecidedUntilAConcreteGetterIsUsed() {
        val source = YDoc(clientId = 1)
        source.getText("body").insertEmbed(0, YMap(mapOf("label" to "mention")))
        val target = createDocFromUpdate(source.encodeStateAsUpdate())

        val placeholder = assertIs<YUnopenedRoot>(target.share["body"])
        assertSame(placeholder, target.getOrNull("body"))
        assertSame(placeholder, target["body"])
        assertEquals(emptyMap(), target.toJSON())

        val body = target.getText("body")
        assertSame(body, target.share["body"])
        assertEquals(mapOf("label" to "mention"), assertIs<YMap>(body.toDelta().ops.single().insert).toMap())
    }

    @Test
    fun destroyingDocAlsoDestroysItsUnopenedRootPlaceholderExactlyOnce() {
        val source = YDoc(clientId = 1)
        source.getArray("items").push("value")
        val target = createDocFromUpdate(source.encodeStateAsUpdate())
        val placeholder = assertIs<YUnopenedRoot>(target.share["items"])
        var destroyEvents = 0
        placeholder.on("destroy") { event ->
            assertSame(placeholder, event.target)
            destroyEvents++
        }

        target.destroy()
        target.destroy()

        assertTrue(target.isDestroyed)
        assertTrue(placeholder.isDestroyed)
        assertEquals(1, destroyEvents)
    }

    @Test
    fun applyBeforeGetNormalizesContentTypeOnlyRootsInV1AndV2() {
        val source = YDoc(clientId = 1)
        source.getText("text").insertEmbed(
            0,
            YMap(mapOf("label" to "mention")),
        )
        source.getArray("array").push(YXmlElementType("section"))
        source.getXmlFragment("xml").push(YArray(listOf("inside")))
        source.getText("formatted").also { text ->
            text.insertEmbed(0, YMap(mapOf("id" to 7)))
            text.insert(1, "x")
            text.format(1, 1, mapOf("bold" to true))
        }

        val targets = listOf(
            createDocFromUpdate(source.encodeStateAsUpdate()),
            createDocFromUpdateV2(encodeStateAsUpdateV2(source)),
        )

        targets.forEach { target ->
            val textEmbed = assertIs<YMap>(target.getText("text").toDelta().ops.single().insert)
            assertEquals(mapOf("label" to "mention"), textEmbed.toMap())

            val arrayChild = assertIs<YXmlElementType>(target.getArray("array").get(0))
            assertEquals("section", arrayChild.nodeName)

            val xmlChild = assertIs<YArray>(target.getXmlFragment("xml").get(0))
            assertEquals(listOf("inside"), xmlChild.toList())

            val formatted = target.getText("formatted").toDelta().ops
            assertEquals(mapOf("id" to 7L), assertIs<YMap>(formatted[0].insert).toMap())
            assertEquals("x", formatted[1].insert)
            assertEquals(mapOf("bold" to true), formatted[1].attributes)
        }
    }

    @Test
    fun cloneDocPreMaterializesConcreteRootsAndElementNodeNames() {
        val source = YDoc(clientId = 1)
        source.getArray("empty")
        val article = source.getXmlElement("root-element", "article")
        article.push(YXmlTextType().also { text -> text.insert(0, "body") })
        source.getText("text").insertEmbed(0, YMap(mapOf("id" to 7)))

        val cloned = cloneDoc(source)

        assertIs<YArray>(cloned.getOrNull("empty"))
        val clonedArticle = cloned.getXmlElement("root-element")
        assertEquals("article", clonedArticle.nodeName)
        assertEquals("<article>body</article>", clonedArticle.toString())
        val clonedEmbed = assertIs<YMap>(cloned.getText("text").toDelta().ops.single().insert)
        assertEquals(mapOf("id" to 7L), clonedEmbed.toMap())
    }

    @Test
    fun createDocFromSnapshotPreMaterializesConcreteRootsAndElementNodeNames() {
        val source = YDoc(clientId = 1, gc = false)
        source.getMap("empty")
        val article = source.getXmlElement("root-element", "article")
        val articleText = YXmlTextType().also { text -> text.insert(0, "old") }
        article.push(articleText)
        val metadata = YMap(mapOf("version" to "old"))
        source.getText("text").insertEmbed(0, metadata)
        val before = snapshot(source)

        articleText.delete(0, articleText.length)
        articleText.insert(0, "new")
        metadata.set("version", "new")

        val restored = createDocFromSnapshot(source, before)

        assertIs<YMap>(restored.getOrNull("empty"))
        val restoredArticle = restored.getXmlElement("root-element")
        assertEquals("article", restoredArticle.nodeName)
        assertEquals("<article>old</article>", restoredArticle.toString())
        val restoredEmbed = assertIs<YMap>(restored.getText("text").toDelta().ops.single().insert)
        assertEquals(mapOf("version" to "old"), restoredEmbed.toMap())
    }
}
