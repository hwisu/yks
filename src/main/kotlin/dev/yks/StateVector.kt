package dev.yks

public typealias StateVector = Map<Long, Long>

public fun getStateVector(doc: YDoc): StateVector = doc.stateVector()

public fun getStateVector(store: StructStore): StateVector = store.stateVector()

public fun getState(store: StructStore, client: Long): Long = store.getClock(client)

public fun getState(doc: YDoc, client: Long): Long = getState(doc.store, client)

public fun integrityCheck(doc: YDoc) {
    doc.integrityCheck()
}

public fun integrityCheck(store: StructStore) {
    store.integrityCheck()
}

public fun writeStateVector(encoder: BinaryEncoder, stateVector: StateVector): BinaryEncoder {
    requireYjsSafeStateVector(stateVector)
    encoder.writeVarUInt(stateVector.size.toLong())
    stateVector.toSortedMap(compareByDescending { it }).forEach { (client, clock) ->
        encoder.writeVarUInt(client)
        encoder.writeVarUInt(clock)
    }
    return encoder
}

public fun writeStateVector(encoder: IdSetEncoderV1, stateVector: StateVector): IdSetEncoderV1 {
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

public fun writeDocumentStateVector(encoder: BinaryEncoder, doc: YDoc): BinaryEncoder =
    writeStateVector(encoder, getStateVector(doc))

public fun writeDocumentStateVector(encoder: IdSetEncoderV1, doc: YDoc): IdSetEncoderV1 =
    writeStateVector(encoder, getStateVector(doc))

public fun readStateVector(decoder: BinaryDecoder): StateVector {
    val count = decoder.readVarUInt().toDecodedCount()
    return buildMap {
        repeat(count) {
            put(decoder.readVarUInt(), decoder.readVarUInt())
        }
    }
}

public fun readStateVector(decoder: IdSetDecoderV1): StateVector =
    readStateVector(decoder.restDecoder)

public fun encodeStateVector(stateVector: StateVector): ByteArray {
    val encoder = BinaryEncoder()
    writeStateVector(encoder, stateVector)
    return encoder.toByteArray()
}

public fun encodeStateVectorV2(stateVector: StateVector, encoder: IdSetEncoderV1 = IdSetEncoderV2()): ByteArray {
    writeStateVector(encoder, stateVector)
    return encoder.toByteArray()
}

public fun encodeStateVectorV2(doc: YDoc, encoder: IdSetEncoderV1 = IdSetEncoderV2()): ByteArray {
    writeDocumentStateVector(encoder, doc)
    return encoder.toByteArray()
}

public fun decodeStateVector(bytes: ByteArray): StateVector = decodeBoundary("Yjs state vector") {
    if (bytes.isEmpty()) return@decodeBoundary emptyMap()
    val decoder = BinaryDecoder(bytes)
    val stateVector = readStateVector(decoder)
    check(!decoder.hasRemaining()) { "state vector has trailing bytes" }
    stateVector
}

public fun decodeStateVectorV2(bytes: ByteArray): StateVector = decodeStateVector(bytes)
