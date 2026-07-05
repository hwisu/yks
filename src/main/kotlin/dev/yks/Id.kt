package dev.yks

data class Id(val client: Long, val clock: Long) : Comparable<Id> {
    init {
        require(client >= 0) { "client must be non-negative" }
        require(clock >= 0) { "clock must be non-negative" }
    }

    override fun compareTo(other: Id): Int {
        val clientOrder = client.compareTo(other.client)
        return if (clientOrder != 0) clientOrder else clock.compareTo(other.clock)
    }
}

typealias ID = Id

fun createID(client: Long, clock: Long): Id = Id(client, clock)

fun compareIDs(left: Id?, right: Id?): Boolean =
    left === right ||
        (left != null && right != null && left.client == right.client && left.clock == right.clock)

fun writeID(encoder: BinaryEncoder, id: Id): BinaryEncoder {
    encoder.writeVarUInt(id.client)
    encoder.writeVarUInt(id.clock)
    return encoder
}

fun writeID(encoder: IdSetEncoderV1, id: Id): IdSetEncoderV1 {
    writeID(encoder.restEncoder, id)
    return encoder
}

fun readID(decoder: BinaryDecoder): Id = Id(decoder.readVarUInt(), decoder.readVarUInt())

fun readID(decoder: IdSetDecoderV1): Id = readID(decoder.restDecoder)
