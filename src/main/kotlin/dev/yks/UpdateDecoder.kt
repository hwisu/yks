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

private data class V2DecoderStreams(
    val rest: BinaryDecoder,
    val keyClocks: Lib0IntDiffOptRleDecoder,
    val clients: Lib0UintOptRleDecoder,
    val leftClocks: Lib0IntDiffOptRleDecoder,
    val rightClocks: Lib0IntDiffOptRleDecoder,
    val infos: Lib0ByteRleDecoder,
    val strings: Lib0StringDecoder,
    val parentInfos: Lib0ByteRleDecoder,
    val typeRefs: Lib0UintOptRleDecoder,
    val lengths: Lib0UintOptRleDecoder,
)

private fun readV2DecoderStreams(bytes: ByteArray): V2DecoderStreams {
    val decoder = BinaryDecoder(bytes)
    val feature = decoder.readVarUInt()
    if (feature != 0L) {
        val empty = ByteArray(0)
        return V2DecoderStreams(
            rest = BinaryDecoder(bytes),
            keyClocks = Lib0IntDiffOptRleDecoder(empty),
            clients = Lib0UintOptRleDecoder(empty),
            leftClocks = Lib0IntDiffOptRleDecoder(empty),
            rightClocks = Lib0IntDiffOptRleDecoder(empty),
            infos = Lib0ByteRleDecoder(empty),
            strings = Lib0StringDecoder(byteArrayOf(0)),
            parentInfos = Lib0ByteRleDecoder(empty),
            typeRefs = Lib0UintOptRleDecoder(empty),
            lengths = Lib0UintOptRleDecoder(empty),
        )
    }
    val encoded = List(9) { decoder.readBytes() }
    return V2DecoderStreams(
        rest = BinaryDecoder(decoder.readRemainingBytes()),
        keyClocks = Lib0IntDiffOptRleDecoder(encoded[0]),
        clients = Lib0UintOptRleDecoder(encoded[1]),
        leftClocks = Lib0IntDiffOptRleDecoder(encoded[2]),
        rightClocks = Lib0IntDiffOptRleDecoder(encoded[3]),
        infos = Lib0ByteRleDecoder(encoded[4]),
        strings = Lib0StringDecoder(encoded[5]),
        parentInfos = Lib0ByteRleDecoder(encoded[6]),
        typeRefs = Lib0UintOptRleDecoder(encoded[7]),
        lengths = Lib0UintOptRleDecoder(encoded[8]),
    )
}

open class UpdateDecoderV2 private constructor(
    private val streams: V2DecoderStreams,
) : IdSetDecoderV2(streams.rest), UpdateContentDecoder {
    private val keys = mutableListOf<String>()
    constructor(bytes: ByteArray) : this(readV2DecoderStreams(bytes))

    constructor(decoder: BinaryDecoder) : this(decoder.readRemainingBytes())

    fun readLeftID(): Id = Id(streams.clients.read(), streams.leftClocks.read())

    fun readRightID(): Id = Id(streams.clients.read(), streams.rightClocks.read())

    fun readClient(): Long = streams.clients.read()

    fun readInfo(): Int = streams.infos.read()

    override fun readString(): String = streams.strings.read()

    fun readParentInfo(): Boolean = streams.parentInfos.read() == 1

    override fun readTypeRef(): Int = streams.typeRefs.read().toInt()

    override fun readLen(): Long = streams.lengths.read()

    override fun readAny(): Any? = readLib0Any(restDecoder)

    override fun readBuf(): ByteArray = restDecoder.readBytes()

    override fun readJSON(): Any? = readAny()

    override fun readKey(): String {
        val clock = streams.keyClocks.read().toInt()
        if (clock < keys.size) return keys[clock]
        return streams.strings.read().also(keys::add)
    }
}
