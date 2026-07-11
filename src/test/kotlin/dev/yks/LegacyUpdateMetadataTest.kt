package dev.yks

import kotlin.test.Test
import kotlin.test.assertFalse
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

        val pendingItem = LegacyUpdateCodec.decode(checkNotNull(doc.pendingStructsView()).update).items.single()
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
}
