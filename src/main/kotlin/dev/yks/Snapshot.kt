package dev.yks

public data class SnapshotRootType(
    val kind: RootKind,
    /** Node name for XmlElement, or hook name for XmlHook. */
    val xmlElementNodeName: String? = null,
)

public data class Snapshot(
    val ds: DeleteSet,
    val sv: StateVector,
    /** Local adaptation metadata; standard snapshot encoding intentionally contains only ds/sv. */
    val roots: Map<String, SnapshotRootType> = emptyMap(),
) {
    public constructor(deleteSet: IdSet, stateVector: StateVector) : this(deleteSet.toDeleteSet(), stateVector.toMap())

    val deleteSet: DeleteSet get() = ds
}

public fun createSnapshot(deleteSet: DeleteSet, stateVector: StateVector): Snapshot =
    Snapshot(deleteSet, stateVector)

public fun createSnapshot(deleteSet: IdSet, stateVector: StateVector): Snapshot =
    Snapshot(deleteSet.toDeleteSet(), stateVector.toMap())

public val emptySnapshot: Snapshot = createSnapshot(createIdSet(), emptyMap())

private object SplitSnapshotAffectedStructsMetaKey

public fun snapshot(doc: YDoc): Snapshot = doc.withDocumentAccess {
    Snapshot(
        ds = doc.deleteSet(),
        sv = doc.stateVector().toMap(),
        roots = doc.concreteRootMetadata(),
    )
}

@Suppress("UNCHECKED_CAST")
public fun splitSnapshotAffectedStructs(transaction: YTransaction, snapshot: Snapshot) {
    val seen = transaction.meta.getOrPut(SplitSnapshotAffectedStructsMetaKey, ::identitySnapshotSet) as MutableSet<Snapshot>
    if (!seen.add(snapshot)) return
    val store = transaction.doc.store
    snapshot.sv.forEach { (client, clock) ->
        if (clock < store.getClock(client)) {
            getItemCleanStart(transaction, Id(client, clock))
        }
    }
    snapshot.ds.clients.forEach { (client, ranges) ->
        ranges.forEach { range ->
            val start = Id(client, range.clock)
            if (store.contains(start)) {
                getItemCleanStart(transaction, start)
            }
            val end = Id(client, range.end - 1)
            if (store.contains(end)) {
                getItemCleanEnd(transaction, store, end)
            }
        }
    }
}

private fun identitySnapshotSet(): MutableSet<Snapshot> =
    java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Snapshot, Boolean>())

public fun createDocFromSnapshot(originDoc: YDoc, snapshot: Snapshot, newDoc: YDoc = YDoc()): YDoc {
    check(!originDoc.gc) { "Garbage-collection must be disabled in `originDoc`!" }
    newDoc.preMaterializeRoots(snapshot.roots)
    newDoc.applyUpdateLossless(originDoc.encodeSnapshotAsUpdate(snapshot), origin = "snapshot")
    return newDoc
}

public fun equalSnapshots(left: Snapshot, right: Snapshot): Boolean =
    left.sv == right.sv && equalDeleteSets(left.ds, right.ds)

public fun encodeSnapshot(snapshot: Snapshot): ByteArray =
    encodeSnapshotV2(snapshot, IdSetEncoderV1())

public fun encodeSnapshotV2(snapshot: Snapshot, encoder: IdSetEncoderV1 = IdSetEncoderV2()): ByteArray {
    val deleteIds = snapshot.ds.toIdSet()
    requireYjsSafeIdSet(deleteIds)
    requireYjsSafeStateVector(snapshot.sv)
    writeIdSet(encoder, deleteIds)
    writeStateVector(encoder, snapshot.sv)
    return encoder.toByteArray()
}

public fun decodeSnapshot(bytes: ByteArray): Snapshot =
    decodeSnapshotWithDecoder(IdSetDecoderV1(bytes))

public fun decodeSnapshot(decoder: IdSetDecoderV1): Snapshot =
    decodeSnapshotWithDecoder(decoder)

public fun decodeSnapshotV2(bytes: ByteArray): Snapshot =
    decodeSnapshotWithDecoder(IdSetDecoderV2(bytes))

public fun decodeSnapshotV2(decoder: IdSetDecoderV1): Snapshot =
    decodeSnapshotWithDecoder(decoder)

private fun decodeSnapshotWithDecoder(decoder: IdSetDecoderV1): Snapshot {
    val deleteSet = readIdSet(decoder)
    val stateVector = readStateVector(decoder)
    check(!decoder.hasRemaining()) { "snapshot has trailing bytes" }
    return createSnapshot(deleteSet, stateVector)
}

public fun snapshotContainsUpdate(snapshot: Snapshot, update: ByteArray): Boolean =
    snapshotContainsDecodedUpdate(snapshot, UpdateCodec.decodeStandard(update))

public fun snapshotContainsUpdateV2(snapshot: Snapshot, update: ByteArray): Boolean =
    snapshotContainsDecodedUpdate(snapshot, UpdateCodec.decodeStandardV2(update))

public fun snapshotContainsUpdateLossless(snapshot: Snapshot, update: ByteArray): Boolean =
    snapshotContainsDecodedUpdate(snapshot, UpdateCodec.decode(update))

public fun snapshotContainsUpdateV2Lossless(snapshot: Snapshot, update: ByteArray): Boolean =
    snapshotContainsDecodedUpdate(snapshot, UpdateCodec.decodeV2(update))

private fun snapshotContainsDecodedUpdate(snapshot: Snapshot, decoded: DocumentUpdate): Boolean {
    val structsCovered = decoded.items.all { item ->
        (snapshot.sv[item.id.client] ?: 0) >= checkedClockAdd(item.id.clock, item.length)
    }
    if (!structsCovered) return false

    val mergedDeletes = mergeDeleteSets(listOf(snapshot.ds, decoded.deleteSet))
    return equalDeleteSets(snapshot.ds, mergedDeletes)
}
