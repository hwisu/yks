package dev.yks

import dev.yks.experimental.v14.ExperimentalYjs14Api
import dev.yks.experimental.v14.Type
import dev.yks.experimental.v14.Yjs14SchemaMarkers
import dev.yks.experimental.v14.Node
import dev.yks.experimental.v14.asV14Node
import dev.yks.experimental.v14.getNode
import dev.yks.experimental.v14.getType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class YSchemaTest {
    @OptIn(ExperimentalYjs14Api::class)
    @Test
    fun experimentalNodeAliasesPreserveIdentityAndCheckNames() {
        val doc = YDoc(clientId = 1)
        val node: Node = doc.getNode("body", RootKind.Text)
        val paragraph = YXmlElementType("p").asV14Node()

        node.insert(0, "Hello")
        assertSame(doc.getText("body"), node.delegate)
        assertEquals("Hello", doc.getText("body").toString())
        assertEquals(node.toArray(), doc.getType("body", RootKind.Text).toArray())
        assertSame(Yjs14SchemaMarkers.`$ydoc`, Yjs14SchemaMarkers.`$doc`)
        assertEquals("y:renderer", Yjs14SchemaMarkers.`$renderer`.name)
        assertEquals("y:r", rendererSchema.name)
        assertSame(Yjs14SchemaMarkers.`$nodeAny`, node.`$type`)
        assertEquals("y:node", node.`$type`.name)
        assertTrue(Yjs14SchemaMarkers.`$nodeAny`.check(paragraph))
        assertTrue(Yjs14SchemaMarkers.`$node`().check(node))
        assertTrue(Yjs14SchemaMarkers.`$node`().check(paragraph))
        assertTrue(Yjs14SchemaMarkers.`$node`("p").check(paragraph))
        assertFalse(Yjs14SchemaMarkers.`$node`("p").check(node))
        assertFalse(Yjs14SchemaMarkers.`$node`("q").check(paragraph))
        assertFalse(Yjs14SchemaMarkers.`$nodeAny`.check(node.delegate))
        assertFalse(Yjs14SchemaMarkers.`$nodeAny`.check(doc))
    }

    @Test
    fun typedDocumentMarkerNarrowsValuesWithoutBreakingLegacyFunctionIdentity() {
        val doc = YDoc(clientId = 1)

        val narrowed: YDoc = ydocSchema.expect(doc)

        assertSame(doc, narrowed)
        assertTrue(ydocSchema.validate(doc))
        assertFalse(ydocSchema.check(doc.getArray("items")))
        assertSame(ydocSchema, `$ydoc`)
        assertSame(ydocSchema, doc.`$type`)
        assertEquals("y:doc", ydocSchema.name)
        assertFailsWith<IllegalArgumentException> { ydocSchema.cast("not a doc") }
    }

    @Test
    fun sharedTypeRendererAndAttributionSchemasAreTyped() {
        val doc = YDoc(clientId = 1)
        val text = doc.getText("body")
        val attribution = mapOf("insert" to listOf("alice"))

        val narrowedType: AbstractYType = ytypeAnySchema.expect(text)
        val narrowedRenderer: AbstractRenderer = rendererSchema.expect(baseRenderer)
        val narrowedAttribution: Attribution = attributionJsonSchema.expect(attribution)

        assertSame(text, narrowedType)
        assertSame(baseRenderer, narrowedRenderer)
        assertSame(rendererSchema, baseRenderer.schema)
        assertEquals(attribution, narrowedAttribution)
        assertTrue(attributionJsonSchema.check(mapOf("extension" to true)))
        assertFalse(ytypeAnySchema.check(doc))
        assertFalse(attributionJsonSchema.check(mapOf("insert" to listOf(1))))
    }

    @OptIn(ExperimentalYjs14Api::class)
    @Test
    fun experimentalV14MarkersExposeUpstreamSchemaRoles() {
        val doc = YDoc(clientId = 1)
        val type = doc.getType("body", RootKind.Text)

        val narrowedDoc: YDoc = Yjs14SchemaMarkers.`$ydoc`.expect(doc)
        val narrowedType: Type = Yjs14SchemaMarkers.`$ytype`().expect(type)
        val narrowedRenderer: AbstractRenderer = Yjs14SchemaMarkers.`$renderer`.expect(baseRenderer)

        assertSame(doc, narrowedDoc)
        assertSame(type, narrowedType)
        assertSame(baseRenderer, narrowedRenderer)
        assertSame(Yjs14SchemaMarkers.`$ytypeAny`, Yjs14SchemaMarkers.`$ytype`())
        assertFalse(Yjs14SchemaMarkers.`$ytypeAny`.check(type.delegate))
    }
}
