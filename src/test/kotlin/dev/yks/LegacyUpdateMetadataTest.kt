package dev.yks

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LegacyUpdateMetadataTest {
    @Test
    fun privateUpdateV2PreservesGcAndClockContinuityMetadata() {
        val item = StoreItem(
            id = Id(1, 4),
            origin = null,
            rightOrigin = null,
            parent = "__yjs_gc__:1",
            parentSub = null,
            content = ItemContent.Deleted(RootKind.Array),
            deleted = true,
            requiresClockContinuity = true,
            isGc = true,
        )

        val decoded = LegacyUpdateCodec.decode(LegacyUpdateCodec.encode(DocumentUpdate(listOf(item), DeleteSet.empty())))
            .items.single()

        assertTrue(decoded.deleted)
        assertTrue(decoded.requiresClockContinuity)
        assertTrue(decoded.isGc)
    }

    @Test
    fun pendingStructViewRoundTripPreservesGcMetadata() {
        val item = StoreItem(
            id = Id(7, 3),
            origin = Id(7, 2),
            rightOrigin = null,
            parent = "__yjs_gc__:7",
            parentSub = null,
            content = ItemContent.Deleted(RootKind.Array),
            deleted = true,
            requiresClockContinuity = true,
            isGc = true,
        )
        val encoded = LegacyUpdateCodec.encode(DocumentUpdate(listOf(item), DeleteSet.empty()))
        val doc = YDoc(clientId = 1)

        doc.setPendingStructsView(PendingStructs(mapOf(7L to 0L), encoded))

        val pendingItem = UpdateCodec.decode(checkNotNull(doc.pendingStructsView()).update).items.single()
        assertTrue(pendingItem.requiresClockContinuity)
        assertTrue(pendingItem.isGc)
    }

    @Test
    fun privateUpdateV1RemainsDecodableWithDefaultMetadata() {
        val bytes = BinaryEncoder().apply {
            listOf('Y', 'K', 'S').forEach { writeByte(it.code) }
            writeByte(1)
            writeVarUInt(1) // items
            writeVarUInt(1) // client
            writeVarUInt(0) // clock
            writeBoolean(false) // origin
            writeBoolean(false) // right origin
            writeString("legacy")
            writeBoolean(false) // parentSub
            writeBoolean(true) // deleted
            writeByte(5) // ItemContent.Deleted
            writeByte(RootKind.Array.ordinal)
            writeVarUInt(0) // delete-set clients
        }.toByteArray()

        val decoded = LegacyUpdateCodec.decode(bytes).items.single()

        assertTrue(decoded.deleted)
        assertFalse(decoded.requiresClockContinuity)
        assertFalse(decoded.isGc)
    }

    @Test
    fun privateUpdateV2RemainsDecodableWithoutUnresolvedParentMetadata() {
        val bytes = BinaryEncoder().apply {
            listOf('Y', 'K', 'S').forEach { writeByte(it.code) }
            writeByte(2)
            writeVarUInt(1) // items
            writeVarUInt(1) // client
            writeVarUInt(0) // clock
            writeBoolean(false) // origin
            writeBoolean(false) // right origin
            writeString("legacy-v2")
            writeBoolean(false) // parentSub
            writeBoolean(true) // deleted
            writeBoolean(true) // requiresClockContinuity
            writeBoolean(false) // isGc
            writeByte(5) // ItemContent.Deleted
            writeByte(RootKind.Array.ordinal)
            writeVarUInt(0) // delete-set clients
        }.toByteArray()

        val decoded = LegacyUpdateCodec.decode(bytes).items.single()

        assertTrue(decoded.deleted)
        assertTrue(decoded.requiresClockContinuity)
        assertFalse(decoded.isGc)
        assertNull(decoded.unresolvedParent)
    }

    @Test
    fun privateUpdateV3PreservesExplicitUnresolvedParentMetadata() {
        for (unresolved in listOf(
            UnresolvedYjsParent.Nested(Id(7, 2)),
            UnresolvedYjsParent.Inherit(Id(8, 3)),
        )) {
            val item = StoreItem(
                id = Id(9, 4),
                origin = unresolved.id,
                rightOrigin = null,
                parent = "display-only-alias",
                parentSub = null,
                content = ItemContent.Value(YValue.StringValue("pending")),
                requiresClockContinuity = true,
                unresolvedParent = unresolved,
            )

            val encoded = LegacyUpdateCodec.encode(DocumentUpdate(listOf(item), DeleteSet.empty()))
            val decoded = LegacyUpdateCodec.decode(encoded).items.single()

            assertEquals(3, encoded[3].toInt())
            assertEquals(unresolved, decoded.unresolvedParent)
            assertEquals("display-only-alias", decoded.parent)
        }
    }

    @Test
    fun pendingStructViewRoundTripPreservesExplicitUnresolvedParentMetadata() {
        val unresolved = UnresolvedYjsParent.Inherit(Id(7, 2))
        val item = StoreItem(
            id = Id(8, 3),
            origin = unresolved.id,
            rightOrigin = null,
            parent = "__yjs_inherit__:7:2",
            parentSub = null,
            content = ItemContent.Value(YValue.StringValue("pending")),
            requiresClockContinuity = true,
            unresolvedParent = unresolved,
        )
        val doc = YDoc(clientId = 1)

        doc.setPendingStructsView(
            PendingStructs(
                missing = mapOf(7L to 0L),
                update = LegacyUpdateCodec.encode(DocumentUpdate(listOf(item), DeleteSet.empty())),
            ),
        )

        val pending = UpdateCodec.decode(checkNotNull(doc.pendingStructsView()).update).items.single()
        assertEquals(unresolved, pending.unresolvedParent)
    }

    @Test
    fun syntheticLookingLegacyRootNameIsNotTreatedAsUnresolvedParent() {
        val rootName = "__yjs_inherit__:1:0"
        val source = YDoc(clientId = 2)
        source.getXmlFragment(rootName).push(YXmlElement("p"))
        val update = encodeStateAsUpdate(source)
        val target = YDoc(clientId = 3)

        assertEquals(3, update[3].toInt())
        applyUpdate(target, update)

        assertEquals("<p></p>", target.getXmlFragment(rootName).toString())
        assertNull(target.store.pendingStructs)
    }
}
