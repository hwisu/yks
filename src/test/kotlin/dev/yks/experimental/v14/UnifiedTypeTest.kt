package dev.yks.experimental.v14

import dev.yks.RootKind
import dev.yks.TwosetRenderer
import dev.yks.YDoc
import dev.yks.YMap
import dev.yks.YTextDeltaOp
import dev.yks.YUnopenedRoot
import dev.yks.YValue
import dev.yks.createContentAttribute
import dev.yks.createIdMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertNull
import kotlin.test.assertNotSame
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

    @Test
    fun builderKeepsAttributionSeparateAndCarriesMarks() {
        val insertedBy = DeltaAttribution.of(
            mapOf("insert" to YValue.ListValue(listOf(YValue.StringValue("alice")))),
        )
        val delta = DeltaBuilder()
            .useAttribution(insertedBy)
            .insert("a")
            .insert("b")
            .retainWithAttribution(
                1,
                AttributionChange.Patch(
                    mapOf(
                        "format" to YValue.MapValue(
                            mapOf("bold" to YValue.ListValue(listOf(YValue.StringValue("bob")))),
                        ),
                    ),
                ),
            )
            .addChildMark(
                index = 1,
                id = "cursor-1",
                association = -1,
                attrs = mapOf("color" to YValue.StringValue("blue")),
            )
            .done()

        val insert = assertIs<ChildOp.InsertText>(delta.children[0])
        assertEquals("ab", insert.text)
        assertEquals(insertedBy, insert.attribution)
        assertEquals(
            AttributionChange.Patch(
                insertedBy.values + (
                    "format" to YValue.MapValue(
                        mapOf("bold" to YValue.ListValue(listOf(YValue.StringValue("bob")))),
                    )
                ),
            ),
            assertIs<ChildOp.Retain>(delta.children[1]).attribution,
        )
        assertEquals(DeltaMarkKey.Child(1), delta.marks.single().key)
        assertEquals("cursor-1", delta.marks.single().id)
        assertEquals(-1, delta.marks.single().association)
    }

    @Test
    fun rendererSnapshotPreservesDeletedContentAttributionAsItsOwnDimension() {
        val doc = YDoc(clientId = 1, gc = false)
        val text = doc.getText("body")
        text.insert(0, "ab")
        text.delete(1, 1)
        val renderer = TwosetRenderer(
            inserts = createIdMap(),
            deletes = createIdMap().also { ids ->
                ids.add(1, 1, 1, listOf(createContentAttribute("delete", "bob")))
            },
        )

        val delta = text.asV14Type().toDelta(renderer)

        assertEquals(2, delta.children.size, delta.toString())
        assertEquals("a", assertIs<ChildOp.InsertText>(delta.children[0]).text)
        val deleted = assertIs<ChildOp.InsertText>(delta.children[1])
        assertEquals("b", deleted.text)
        assertEquals(
            DeltaAttribution.of(
                mapOf("delete" to YValue.ListValue(listOf(YValue.StringValue("bob")))),
            ),
            deleted.attribution,
        )
        assertTrue(deleted.formats.isEmpty())
    }

    @Test
    fun nestedModifyOfRenderedDeletedTypeReturnsCorrectionWithoutMutation() {
        val doc = YDoc(clientId = 1, gc = false)
        val outer = doc.getArray("items")
        val nested = YMap()
        nested.setAttr("count", 1)
        outer.push(nested)
        val owner = checkNotNull(doc.typeRefItemId(nested))
        val countItem = doc.mapItemOrder(nested.name, "count").single().id
        outer.delete(0, 1)
        val renderer = TwosetRenderer(
            inserts = createIdMap(),
            deletes = createIdMap().also { ids ->
                ids.add(owner.client, owner.clock, 1, listOf(createContentAttribute("delete", "bob")))
                ids.add(
                    countItem.client,
                    countItem.clock,
                    1,
                    listOf(createContentAttribute("delete", "bob")),
                )
            },
        )
        val requested = DeltaBuilder()
            .modify(DeltaBuilder().setDataAttr("count", YValue.LongNumber(2)).done())
            .done()

        val correction = outer.asV14Type().applyDeltaWithCorrection(requested, renderer = renderer)

        assertEquals(
            AttributeOp.Set(DeltaValue.integer(1), DeltaAttribution.of(
                mapOf("delete" to YValue.ListValue(listOf(YValue.StringValue("bob")))),
            )),
            nested.asV14Type().toDelta(renderer).attributes["count"],
        )
        val modify = assertIs<ChildOp.Modify>(checkNotNull(correction).children.single())
        assertEquals(
            AttributeOp.Set(
                DeltaValue.integer(1),
                DeltaAttribution.of(
                    mapOf("delete" to YValue.ListValue(listOf(YValue.StringValue("bob")))),
                ),
            ),
            modify.delta.attributes["count"],
        )
        assertNull(outer.asV14Type().applyDeltaWithCorrection(DeltaBuilder().done(), renderer = renderer))
    }

    @Test
    fun heldDeltaReferenceStaysLiveAndClearCacheDetachesIt() {
        val doc = YDoc(clientId = 1)
        val text = doc.getText("body")
        val type = text.asV14Type()
        text.insert(0, "ab")
        val held = type.delta

        text.insert(2, "!")

        assertSame(held, type.delta)
        assertEquals("ab!", assertIs<ChildOp.InsertText>(held.children.single()).text)

        type.clearCache()
        text.insert(3, "?")

        val rematerialized = type.delta
        assertNotSame(held, rematerialized)
        assertEquals("ab!", assertIs<ChildOp.InsertText>(held.children.single()).text)
        assertEquals("ab!?", assertIs<ChildOp.InsertText>(rematerialized.children.single()).text)
    }

    @Test
    fun deltaEventEmitsAChangeAndRefreshesNestedStateInPlace() {
        val doc = YDoc(clientId = 1)
        val outer = doc.getArray("items")
        val nested = YMap()
        nested.setAttr("count", 1)
        outer.push(nested)
        val type = outer.asV14Type()
        val held = type.delta
        val events = mutableListOf<TypeEvent>()
        type.on("delta") { event -> events += event }

        doc.transact(origin = "local") { nested.setAttr("count", 2) }

        assertSame(held, type.delta)
        assertEquals(1, events.size)
        assertEquals("local", events.single().origin)
        val eventModify = assertIs<ChildOp.Modify>(checkNotNull(events.single().delta).children.single())
        assertEquals(
            AttributeOp.Set(DeltaValue.integer(2)),
            eventModify.delta.attributes["count"],
        )
        val childState = assertIs<DeltaValue.SharedTypeState>(
            assertIs<ChildOp.InsertValues>(held.children.single()).values.single(),
        )
        assertSame(nested, childState.value)
        assertEquals(
            AttributeOp.Set(DeltaValue.integer(2)),
            childState.delta.attributes["count"],
        )
        assertTrue(events.single().transaction != null)

        type.clearCache()
        nested.setAttr("count", 3)

        assertEquals(2, events.size)
        assertEquals(
            AttributeOp.Set(DeltaValue.integer(3)),
            assertIs<ChildOp.Modify>(checkNotNull(events.last().delta).children.single())
                .delta.attributes["count"],
        )
    }

    @Test
    fun rendererSwitchRefreshesLiveDeltaAndEmitsRendererOnlyChange() {
        val doc = YDoc(clientId = 1)
        val text = doc.getText("body")
        text.insert(0, "ab")
        val type = text.asV14Type()
        val held = type.delta
        val events = mutableListOf<TypeEvent>()
        type.on("delta") { event -> events += event }
        val renderer = object : dev.yks.BaseRenderer() {
            override val attributed = dev.yks.createIdSet().also { ids -> ids.add(1, 0, 1) }

            override fun readContent(
                contents: MutableList<dev.yks.AttributedContent>,
                client: Long,
                clock: Long,
                deleted: Boolean,
                content: dev.yks.AbstractContent,
                renderBehavior: Int,
            ) {
                if (client == 1L && clock == 0L) return
                super.readContent(contents, client, clock, deleted, content, renderBehavior)
            }
        }

        type.useRenderer(renderer)

        assertSame(held, type.delta)
        assertEquals("b", assertIs<ChildOp.InsertText>(held.children.single()).text)
        assertEquals(1, events.size)
        assertNull(events.single().origin)
        assertNull(events.single().transaction)
        assertEquals(1, assertIs<ChildOp.Delete>(checkNotNull(events.single().delta).children.first()).length)
    }

    @Test
    fun deepSnapshotPreservesSharedTypesInsideDataContainers() {
        val doc = YDoc(clientId = 1)
        val root = doc.getArray("root")
        val nested = YMap()
        nested.setAttr("name", "Ada")
        root.push(mapOf("children" to listOf(nested)))

        val value = assertIs<DeltaValue.MapData>(
            assertIs<ChildOp.InsertValues>(root.asV14Type().toDelta().children.single()).values.single(),
        )
        val list = assertIs<DeltaValue.ListData>(value.values["children"])
        val child = assertIs<DeltaValue.SharedTypeState>(list.values.single())

        assertSame(nested, child.value)
        assertEquals(
            AttributeOp.Set(DeltaValue.text("Ada")),
            child.delta.attributes["name"],
        )
    }

    @Test
    fun arrayFormatsApplyAcrossTextAndDataWithoutChangingLegacyValues() {
        val doc = YDoc(clientId = 1)
        val type = doc.getType("mixed", RootKind.Array)
        type.applyDelta(
            DeltaBuilder()
                .insert("AB", mapOf("bold" to YValue.Bool(true)))
                .insertValues(
                    listOf(DeltaValue.integer(1), DeltaValue.integer(2)),
                    mapOf("color" to YValue.StringValue("red")),
                )
                .done(),
        )
        type.applyDelta(
            DeltaBuilder()
                .retain(1)
                .retain(
                    2,
                    mapOf(
                        "bold" to null,
                        "italic" to YValue.Bool(true),
                    ),
                )
                .done(),
        )

        assertEquals(listOf("A", "B", 1L, 2L), doc.getArray("mixed").toArray())
        assertEquals(
            listOf(
                ChildOp.InsertText("A", mapOf("bold" to YValue.Bool(true))),
                ChildOp.InsertText("B", mapOf("italic" to YValue.Bool(true))),
                ChildOp.InsertValues(
                    listOf(DeltaValue.integer(1)),
                    mapOf(
                        "color" to YValue.StringValue("red"),
                        "italic" to YValue.Bool(true),
                    ),
                ),
                ChildOp.InsertValues(
                    listOf(DeltaValue.integer(2)),
                    mapOf("color" to YValue.StringValue("red")),
                ),
            ),
            type.toDelta().children,
        )

        type.applyDelta(DeltaBuilder().retainChanges(3, FormatChange.Clear).done())

        assertEquals(
            listOf(
                ChildOp.InsertText("AB", emptyMap()),
                ChildOp.InsertValues(listOf(DeltaValue.integer(1)), emptyMap()),
                ChildOp.InsertValues(
                    listOf(DeltaValue.integer(2)),
                    mapOf("color" to YValue.StringValue("red")),
                ),
            ),
            type.toDelta().children,
        )
    }

    @Test
    fun generalizedFormatsRoundTripThroughStandardYjsUpdates() {
        val source = YDoc(clientId = 1)
        val sourceType = source.getType("items", RootKind.Array)
        sourceType.applyDelta(
            DeltaBuilder()
                .insertValue(
                    DeltaValue.text("x"),
                    mapOf("tag" to YValue.StringValue("one")),
                )
                .done(),
        )
        val target = YDoc(clientId = 2)
        target.applyUpdate(source.encodeStateAsUpdate())

        assertEquals(listOf("x"), target.getArray("items").toArray())
        assertEquals(
            listOf(
                ChildOp.InsertValues(
                    listOf(DeltaValue.text("x")),
                    mapOf("tag" to YValue.StringValue("one")),
                ),
            ),
            target.getType("items", RootKind.Array).toDelta().children,
        )
    }

    @Test
    fun arrayFormatAttributionRemainsSeparateFromTheFormatValue() {
        val doc = YDoc(clientId = 1, gc = false)
        val type = doc.getType("items", RootKind.Array)
        type.applyDelta(
            DeltaBuilder()
                .insertValue(
                    DeltaValue.text("x"),
                    mapOf("bold" to YValue.Bool(true)),
                )
                .done(),
        )
        val marker = doc.sequence("items").first { item ->
            item.content is dev.yks.ItemContent.NativeTextFormat &&
                (item.content as dev.yks.ItemContent.NativeTextFormat).value != YValue.Null
        }
        val renderer = TwosetRenderer(
            inserts = createIdMap().also { ids ->
                ids.add(
                    marker.id.client,
                    marker.id.clock,
                    marker.length,
                    listOf(createContentAttribute("insert", "alice")),
                )
            },
            deletes = createIdMap(),
        )

        val insert = assertIs<ChildOp.InsertValues>(type.toDelta(renderer).children.single())

        assertEquals(mapOf("bold" to YValue.Bool(true)), insert.formats)
        assertEquals(
            DeltaAttribution.of(
                mapOf(
                    "format" to YValue.MapValue(
                        mapOf(
                            "bold" to YValue.ListValue(listOf(YValue.StringValue("alice"))),
                        ),
                    ),
                ),
            ),
            insert.attribution,
        )
    }
}
