package dev.yks

public data class Id(val client: Long, val clock: Long) : Comparable<Id> {
    init {
        require(client >= 0) { "client must be non-negative" }
        require(clock >= 0) { "clock must be non-negative" }
    }

    override fun compareTo(other: Id): Int {
        val clientOrder = client.compareTo(other.client)
        return if (clientOrder != 0) clientOrder else clock.compareTo(other.clock)
    }
}

public typealias ID = Id

public fun createID(client: Long, clock: Long): Id = Id(client, clock)

public fun compareIDs(left: Id?, right: Id?): Boolean =
    left === right ||
        (left != null && right != null && left.client == right.client && left.clock == right.clock)

public fun writeID(encoder: BinaryEncoder, id: Id): BinaryEncoder {
    encoder.writeVarUInt(id.client)
    encoder.writeVarUInt(id.clock)
    return encoder
}

public fun writeID(encoder: IdSetEncoderV1, id: Id): IdSetEncoderV1 {
    writeID(encoder.restEncoder, id)
    return encoder
}

public fun readID(decoder: BinaryDecoder): Id = Id(decoder.readVarUInt(), decoder.readVarUInt())

public fun readID(decoder: IdSetDecoderV1): Id = readID(decoder.restDecoder)
