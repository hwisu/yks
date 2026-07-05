package dev.yks

data class Snapshot(
    val ds: IdSet,
    val sv: StateVector,
) {
    constructor(deleteSet: DeleteSet, stateVector: StateVector) : this(deleteSet.toIdSet(), stateVector.toMap())

    val deleteSet: DeleteSet get() = ds.toDeleteSet()
}

fun createSnapshot(deleteSet: DeleteSet, stateVector: StateVector): Snapshot =
    Snapshot(deleteSet.toIdSet(), stateVector.toMap())

fun createSnapshot(deleteSet: IdSet, stateVector: StateVector): Snapshot =
    Snapshot(deleteSet.copy(), stateVector.toMap())

val emptySnapshot: Snapshot = createSnapshot(createIdSet(), emptyMap())

fun snapshot(doc: YDoc): Snapshot = createSnapshot(doc.deleteSet(), doc.stateVector())

@Suppress("UNCHECKED_CAST")
fun splitSnapshotAffectedStructs(transaction: YTransaction, snapshot: Snapshot) {
    val seen = transaction.meta.getOrPut(::splitSnapshotAffectedStructs) { linkedSetOf<Snapshot>() } as MutableSet<Snapshot>
    if (!seen.add(snapshot)) return
    snapshot.sv.forEach { (client, clock) ->
        if (clock < transaction.doc.store.getClock(client)) {
            getItemCleanStart(transaction.doc, Id(client, clock))
        }
    }
    iterateStructsByIdSet(transaction.doc, snapshot.ds) { _, _, _ -> }
}

fun createDocFromSnapshot(originDoc: YDoc, snapshot: Snapshot, newDoc: YDoc = YDoc()): YDoc {
    check(!originDoc.gc) { "Garbage-collection must be disabled in `originDoc`!" }
    newDoc.applyUpdate(originDoc.encodeSnapshotAsUpdate(snapshot), origin = "snapshot")
    return newDoc
}

fun equalSnapshots(left: Snapshot, right: Snapshot): Boolean =
    left.sv == right.sv && equalIdSets(left.ds, right.ds)

fun encodeSnapshot(snapshot: Snapshot): ByteArray =
    encodeSnapshotV2(snapshot, IdSetEncoderV1())

fun encodeSnapshotV2(snapshot: Snapshot, encoder: IdSetEncoderV1 = IdSetEncoderV2()): ByteArray {
    writeIdSet(encoder, snapshot.ds)
    writeStateVector(encoder, snapshot.sv)
    return encoder.toByteArray()
}

fun decodeSnapshot(bytes: ByteArray): Snapshot =
    decodeSnapshotWithDecoder(IdSetDecoderV1(bytes))

fun decodeSnapshot(decoder: IdSetDecoderV1): Snapshot =
    decodeSnapshotWithDecoder(decoder)

fun decodeSnapshotV2(bytes: ByteArray): Snapshot =
    decodeSnapshotWithDecoder(IdSetDecoderV2(bytes))

fun decodeSnapshotV2(decoder: IdSetDecoderV1): Snapshot =
    decodeSnapshotWithDecoder(decoder)

private fun decodeSnapshotWithDecoder(decoder: IdSetDecoderV1): Snapshot {
    val deleteSet = readIdSet(decoder)
    val stateVector = readStateVector(decoder)
    check(!decoder.hasRemaining()) { "snapshot has trailing bytes" }
    return createSnapshot(deleteSet, stateVector)
}

fun snapshotContainsUpdate(snapshot: Snapshot, update: ByteArray): Boolean = snapshotContainsUpdateV2(snapshot, update)

fun snapshotContainsUpdateV2(snapshot: Snapshot, update: ByteArray): Boolean {
    val decoded = UpdateCodec.decode(update)
    val structsCovered = decoded.items.all { item ->
        (snapshot.sv[item.id.client] ?: 0) >= item.id.clock + item.length
    }
    if (!structsCovered) return false

    val mergedDeletes = mergeIdSets(listOf(snapshot.ds, decoded.deleteSet.toIdSet()))
    return equalIdSets(snapshot.ds, mergedDeletes)
}
