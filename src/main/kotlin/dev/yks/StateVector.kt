package dev.yks

typealias StateVector = Map<Long, Long>

fun getStateVector(doc: YDoc): StateVector = doc.stateVector()

fun getStateVector(store: StructStore): StateVector = store.stateVector()

fun getState(store: StructStore, client: Long): Long = store.getClock(client)

fun getState(doc: YDoc, client: Long): Long = getState(doc.store, client)

fun integrityCheck(doc: YDoc) {
    doc.integrityCheck()
}

fun integrityCheck(store: StructStore) {
    store.integrityCheck()
}

fun writeStateVector(encoder: BinaryEncoder, stateVector: StateVector): BinaryEncoder {
    requireYjsSafeStateVector(stateVector)
    encoder.writeVarUInt(stateVector.size.toLong())
    stateVector.toSortedMap(compareByDescending { it }).forEach { (client, clock) ->
        encoder.writeVarUInt(client)
        encoder.writeVarUInt(clock)
    }
    return encoder
}

fun writeStateVector(encoder: IdSetEncoderV1, stateVector: StateVector): IdSetEncoderV1 {
    requireYjsSafeStateVector(stateVector)
    encoder.restEncoder.writeVarUInt(stateVector.size.toLong())
    stateVector.toSortedMap(compareByDescending { it }).forEach { (client, clock) ->
        encoder.restEncoder.writeVarUInt(client)
        encoder.restEncoder.writeVarUInt(clock)
    }
    return encoder
}

internal fun requireYjsSafeStateVector(stateVector: StateVector) {
    stateVector.forEach { (client, clock) ->
        require(client.isYjsSafeVarUint()) {
            "state-vector client must be a JavaScript safe unsigned integer: $client"
        }
        require(clock.isYjsSafeVarUint()) {
            "state-vector clock must be a JavaScript safe unsigned integer: $clock"
        }
    }
}

fun writeDocumentStateVector(encoder: BinaryEncoder, doc: YDoc): BinaryEncoder =
    writeStateVector(encoder, getStateVector(doc))

fun writeDocumentStateVector(encoder: IdSetEncoderV1, doc: YDoc): IdSetEncoderV1 =
    writeStateVector(encoder, getStateVector(doc))

fun readStateVector(decoder: BinaryDecoder): StateVector {
    val count = decoder.readVarUInt().toDecodedCount()
    return buildMap {
        repeat(count) {
            put(decoder.readVarUInt(), decoder.readVarUInt())
        }
    }
}

fun readStateVector(decoder: IdSetDecoderV1): StateVector =
    readStateVector(decoder.restDecoder)

fun encodeStateVector(stateVector: StateVector): ByteArray {
    val encoder = BinaryEncoder()
    writeStateVector(encoder, stateVector)
    return encoder.toByteArray()
}

fun encodeStateVectorV2(stateVector: StateVector, encoder: IdSetEncoderV1 = IdSetEncoderV2()): ByteArray {
    writeStateVector(encoder, stateVector)
    return encoder.toByteArray()
}

fun encodeStateVectorV2(doc: YDoc, encoder: IdSetEncoderV1 = IdSetEncoderV2()): ByteArray {
    writeDocumentStateVector(encoder, doc)
    return encoder.toByteArray()
}

fun decodeStateVector(bytes: ByteArray): StateVector = decodeBoundary("Yjs state vector") {
    if (bytes.isEmpty()) return@decodeBoundary emptyMap()
    val decoder = BinaryDecoder(bytes)
    val stateVector = readStateVector(decoder)
    check(!decoder.hasRemaining()) { "state vector has trailing bytes" }
    stateVector
}

fun decodeStateVectorV2(bytes: ByteArray): StateVector = decodeStateVector(bytes)
