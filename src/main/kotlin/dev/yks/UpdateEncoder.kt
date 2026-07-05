package dev.yks

interface UpdateContentEncoder {
    fun writeString(value: String)

    fun writeTypeRef(info: Int)

    fun writeLen(len: Long)

    fun writeAny(value: Any?)

    fun writeBuf(value: ByteArray)

    fun writeJSON(value: Any?)

    fun writeKey(key: String)
}

open class IdSetEncoderV1(
    val restEncoder: BinaryEncoder = BinaryEncoder(),
) {
    fun toByteArray(): ByteArray = restEncoder.toByteArray()

    fun toUint8Array(): ByteArray = toByteArray()

    open fun resetIdSetCurVal() {
        // V1 stores absolute clocks.
    }

    open fun writeIdSetClock(clock: Long) {
        restEncoder.writeVarUInt(clock)
    }

    open fun writeIdSetLen(len: Long) {
        restEncoder.writeVarUInt(len)
    }
}

open class IdSetEncoderV2(
    restEncoder: BinaryEncoder = BinaryEncoder(),
) : IdSetEncoderV1(restEncoder) {
    private var dsCurrVal: Long = 0

    override fun resetIdSetCurVal() {
        dsCurrVal = 0
    }

    override fun writeIdSetClock(clock: Long) {
        require(clock >= dsCurrVal) { "id-set clocks must be written in ascending order" }
        restEncoder.writeVarUInt(clock - dsCurrVal)
        dsCurrVal = clock
    }

    override fun writeIdSetLen(len: Long) {
        require(len > 0) { "id-set len must be positive" }
        restEncoder.writeVarUInt(len - 1)
        dsCurrVal += len
    }
}

open class UpdateEncoderV1(
    restEncoder: BinaryEncoder = BinaryEncoder(),
) : IdSetEncoderV1(restEncoder), UpdateContentEncoder {
    fun writeLeftID(id: Id) {
        writeId(id)
    }

    fun writeRightID(id: Id) {
        writeId(id)
    }

    fun writeClient(client: Long) {
        restEncoder.writeVarUInt(client)
    }

    fun writeInfo(info: Int) {
        require(info in 0..255) { "info must be an unsigned byte" }
        restEncoder.writeByte(info)
    }

    override fun writeString(value: String) {
        restEncoder.writeString(value)
    }

    fun writeParentInfo(isYKey: Boolean) {
        restEncoder.writeVarUInt(if (isYKey) 1 else 0)
    }

    override fun writeTypeRef(info: Int) {
        require(info >= 0) { "type ref must be non-negative" }
        restEncoder.writeVarUInt(info.toLong())
    }

    override fun writeLen(len: Long) {
        restEncoder.writeVarUInt(len)
    }

    override fun writeAny(value: Any?) {
        writeYValue(restEncoder, YValue.from(value))
    }

    override fun writeBuf(value: ByteArray) {
        restEncoder.writeBytes(value)
    }

    override fun writeJSON(value: Any?) {
        restEncoder.writeString(toJsonLiteral(value))
    }

    override fun writeKey(key: String) {
        restEncoder.writeString(key)
    }

    private fun writeId(id: Id) {
        restEncoder.writeVarUInt(id.client)
        restEncoder.writeVarUInt(id.clock)
    }
}

open class UpdateEncoderV2(
    restEncoder: BinaryEncoder = BinaryEncoder(),
) : IdSetEncoderV2(restEncoder), UpdateContentEncoder {
    fun writeLeftID(id: Id) {
        writeId(id)
    }

    fun writeRightID(id: Id) {
        writeId(id)
    }

    fun writeClient(client: Long) {
        restEncoder.writeVarUInt(client)
    }

    fun writeInfo(info: Int) {
        require(info in 0..255) { "info must be an unsigned byte" }
        restEncoder.writeByte(info)
    }

    override fun writeString(value: String) {
        restEncoder.writeString(value)
    }

    fun writeParentInfo(isYKey: Boolean) {
        restEncoder.writeVarUInt(if (isYKey) 1 else 0)
    }

    override fun writeTypeRef(info: Int) {
        require(info >= 0) { "type ref must be non-negative" }
        restEncoder.writeVarUInt(info.toLong())
    }

    override fun writeLen(len: Long) {
        restEncoder.writeVarUInt(len)
    }

    override fun writeAny(value: Any?) {
        writeYValue(restEncoder, YValue.from(value))
    }

    override fun writeBuf(value: ByteArray) {
        restEncoder.writeBytes(value)
    }

    override fun writeJSON(value: Any?) {
        writeAny(value)
    }

    override fun writeKey(key: String) {
        restEncoder.writeString(key)
    }

    private fun writeId(id: Id) {
        restEncoder.writeVarUInt(id.client)
        restEncoder.writeVarUInt(id.clock)
    }
}
