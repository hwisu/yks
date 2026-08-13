package dev.yks.experimental.v14

import dev.yks.RootKind
import dev.yks.YDoc
import dev.yks.YMap
import dev.yks.YTextDeltaOp
import dev.yks.YUnopenedRoot
import dev.yks.YValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalYjs14Api::class)
class UnifiedTypeTest {
    @Test
    fun builderCoalescesRunsAndBecomesReadOnlyAtDone() {
        val builder = DeltaBuilder("p")
            .insert("a")
            .insert("b")
            .insertValues(listOf(DeltaValue.integer(1)))
            .insertValues(listOf(DeltaValue.bool(true)))
            .retain(1)
            .retain(2)
            .delete(1)
            .delete(2)

        val delta = builder.done()

        assertEquals("p", delta.name)
        assertEquals(4, delta.children.size)
        assertEquals("ab", assertIs<ChildOp.InsertText>(delta.children[0]).text)
        assertEquals(2, assertIs<ChildOp.InsertValues>(delta.children[1]).values.size)
        assertEquals(3, assertIs<ChildOp.Retain>(delta.children[2]).length)
        assertEquals(3, assertIs<ChildOp.Delete>(delta.children[3]).length)
        assertFailsWith<IllegalStateException> { builder.insert("late") }
    }

    @Test
    fun textFacadeAppliesTypedDeltaAndPreservesFormattingSemantics() {
        val doc = YDoc(clientId = 1)
        val text = doc.getType("body", RootKind.Text)
        text.insert(0, "abcd", mapOf("bold" to YValue.Bool(true)))
        text.applyDelta(
            DeltaBuilder()
                .retain(1)
                .retainClearingFormats(2)
                .delete(1)
                .insert("!", mapOf("italic" to YValue.Bool(true)))
                .setDataAttr("lang", YValue.StringValue("ko"))
                .done(),
        )

        assertEquals("abc!", text.delegate.toString())
        assertEquals(DeltaValue.text("ko"), text.getAttr("lang"))
        assertEquals(
            listOf(
                YTextDeltaOp(insert = "a", attributes = mapOf("bold" to true)),
                YTextDeltaOp(insert = "bc"),
                YTextDeltaOp(insert = "!", attributes = mapOf("italic" to true)),
            ),
            doc.getText("body").toDelta().ops,
        )

        val snapshot = text.toDelta()
        assertEquals(null, snapshot.name)
        assertEquals(AttributeOp.Set(DeltaValue.text("ko")), snapshot.attributes["lang"])
        assertEquals(3, snapshot.children.size)
    }

    @Test
    fun arraysRetainSharedTypeIdentityAndSupportNestedModify() {
        val doc = YDoc(clientId = 1)
        val items = doc.getType("items", RootKind.Array)
        val nested = YMap()
        nested.setAttr("count", 1)

        items.applyDelta(
            DeltaBuilder()
                .insertValue(DeltaValue.text("first"))
                .insertType(nested)
                .done(),
        )
        items.applyDelta(
            DeltaBuilder()
                .retain(1)
                .modify(DeltaBuilder().setDataAttr("count", YValue.LongNumber(2)).done())
                .done(),
        )

        assertEquals(2, items.length)
        assertSame(nested, (items.get(1) as DeltaValue.SharedType).value)
        assertEquals(2L, nested.getAttr("count"))
    }

    @Test
    fun nullDataRemainsDistinctFromMissingValues() {
        val doc = YDoc(clientId = 1)
        val items = doc.getType("items", RootKind.Array)
        val meta = doc.getType("meta", RootKind.Map)
        items.applyDelta(DeltaBuilder().insertValue(DeltaValue.Data(YValue.Null)).done())
        meta.applyDelta(DeltaBuilder().setDataAttr("nullable", YValue.Null).done())

        assertEquals(DeltaValue.Data(YValue.Null), items.get(0))
        assertEquals(null, items.get(1))
        assertEquals(DeltaValue.Data(YValue.Null), meta.getAttr("nullable"))
        assertEquals(null, meta.getAttr("missing"))
    }

    @Test
    fun unifiedFacadeSupportsMixedTextAndDataWithoutChangingStableTypeAbi() {
        val doc = YDoc(clientId = 1)
        val root = doc.getType("mixed", RootKind.Array)
        root.applyDelta(
            DeltaBuilder()
                .insert("A😀")
                .insertValues(listOf(DeltaValue.Data(YValue.Null), DeltaValue.integer(7)))
                .done(),
        )

        assertEquals("A😀".length + 2, root.length)
        assertEquals(DeltaValue.text("A"), root.get(0))
        assertEquals(DeltaValue.Data(YValue.Null), root.get("A😀".length))
        assertEquals(
            listOf(
                ChildOp.InsertText("A😀", emptyMap()),
                ChildOp.InsertValues(
                    listOf(DeltaValue.Data(YValue.Null), DeltaValue.integer(7)),
                    emptyMap(),
                ),
            ),
            root.toDelta().children,
        )
    }

    @Test
    fun incompatibleProjectionFailsBeforeAnyMutation() {
        val doc = YDoc(clientId = 1)
        val map = doc.getType("meta", RootKind.Map)
        val incompatible = DeltaBuilder()
            .setDataAttr("should-not-commit", YValue.Bool(true))
            .insert("text-content")
            .done()

        assertFailsWith<IllegalArgumentException> { map.applyDelta(incompatible) }

        assertFalse(map.hasAttr("should-not-commit"))
        assertEquals(0, map.length)
    }

    @Test
    fun valueOwnershipFailureIsPreflightedBeforeAttributesMutate() {
        val targetDoc = YDoc(clientId = 1)
        val target = targetDoc.getType("items", RootKind.Array)
        val otherDoc = YDoc(clientId = 2)
        val alreadyAttached = otherDoc.getMap("attached")
        val delta = DeltaBuilder()
            .setDataAttr("should-not-commit", YValue.Bool(true))
            .insertType(alreadyAttached)
            .done()

        assertFailsWith<IllegalArgumentException> { target.applyDelta(delta) }
        assertFalse(target.hasAttr("should-not-commit"))
        assertEquals(0, target.length)
    }

    @Test
    fun rootKindMustBeExplicitWhenWireMetadataIsUnavailable() {
        val source = YDoc(clientId = 1)
        source.getText("body").insert(0, "remote")
        val target = YDoc(clientId = 2)
        target.applyUpdate(source.encodeStateAsUpdate())

        assertTrue(target["body"] is YUnopenedRoot)
        assertFailsWith<IllegalArgumentException> { Type(target["body"]) }
        assertEquals("remote", target.getType("body", RootKind.Text).delegate.toString())
    }

    @Test
    fun syntheticTypeReferencesAreRejectedInFavorOfIdentitySafeValues() {
        assertFailsWith<IllegalStateException> {
            DeltaValue.Data(YValue.TypeRef(RootKind.Map, "synthetic"))
        }
    }
}
