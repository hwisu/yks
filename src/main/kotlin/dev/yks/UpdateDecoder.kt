package dev.yks

interface UpdateContentDecoder {
    fun readString(): String

    fun readTypeRef(): Int

    fun readLen(): Long

    fun readAny(): Any?

    fun readBuf(): ByteArray

    fun readJSON(): Any?

    fun readKey(): String
}

open class IdSetDecoderV1(
    val restDecoder: BinaryDecoder,
) {
    constructor(bytes: ByteArray) : this(BinaryDecoder(bytes))

    fun hasRemaining(): Boolean = restDecoder.hasRemaining()

    open fun resetDsCurVal() {
        // V1 reads absolute clocks.
    }

    open fun readDsClock(): Long = restDecoder.readVarUInt()

    open fun readDsLen(): Long = restDecoder.readVarUInt()
}

open class IdSetDecoderV2(
    restDecoder: BinaryDecoder,
) : IdSetDecoderV1(restDecoder) {
    constructor(bytes: ByteArray) : this(BinaryDecoder(bytes))

    private var dsCurrVal: Long = 0

    override fun resetDsCurVal() {
        dsCurrVal = 0
    }

    override fun readDsClock(): Long {
        dsCurrVal += restDecoder.readVarUInt()
        return dsCurrVal
    }

    override fun readDsLen(): Long {
        val len = restDecoder.readVarUInt() + 1
        dsCurrVal += len
        return len
    }
}

open class UpdateDecoderV1(
    restDecoder: BinaryDecoder,
) : IdSetDecoderV1(restDecoder), UpdateContentDecoder {
    constructor(bytes: ByteArray) : this(BinaryDecoder(bytes))

    fun readLeftID(): Id = readId()

    fun readRightID(): Id = readId()

    fun readClient(): Long = restDecoder.readVarUInt()

    fun readInfo(): Int = restDecoder.readByte()

    override fun readString(): String = restDecoder.readString()

    fun readParentInfo(): Boolean = restDecoder.readVarUInt() == 1L

    override fun readTypeRef(): Int = restDecoder.readVarUInt().toInt()

    override fun readLen(): Long = restDecoder.readVarUInt()

    override fun readAny(): Any? = readLib0Any(restDecoder)

    override fun readBuf(): ByteArray = restDecoder.readBytes()

    override fun readJSON(): Any? = parseJsonLiteral(restDecoder.readString())

    override fun readKey(): String = restDecoder.readString()

    private fun readId(): Id = Id(restDecoder.readVarUInt(), restDecoder.readVarUInt())
}

open class UpdateDecoderV2(
    restDecoder: BinaryDecoder,
) : IdSetDecoderV2(restDecoder), UpdateContentDecoder {
    constructor(bytes: ByteArray) : this(BinaryDecoder(bytes))

    fun readLeftID(): Id = readId()

    fun readRightID(): Id = readId()

    fun readClient(): Long = restDecoder.readVarUInt()

    fun readInfo(): Int = restDecoder.readByte()

    override fun readString(): String = restDecoder.readString()

    fun readParentInfo(): Boolean = restDecoder.readVarUInt() == 1L

    override fun readTypeRef(): Int = restDecoder.readVarUInt().toInt()

    override fun readLen(): Long = restDecoder.readVarUInt()

    override fun readAny(): Any? = readYValue(restDecoder).toAny()

    override fun readBuf(): ByteArray = restDecoder.readBytes()

    override fun readJSON(): Any? = readAny()

    override fun readKey(): String = restDecoder.readString()

    private fun readId(): Id = Id(restDecoder.readVarUInt(), restDecoder.readVarUInt())
}
