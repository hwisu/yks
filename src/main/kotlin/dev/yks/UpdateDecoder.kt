package dev.yks

public interface UpdateContentDecoder {
    public fun readString(): String

    public fun readTypeRef(): Int

    public fun readLen(): Long

    public fun readAny(): Any?

    public fun readBuf(): ByteArray

    public fun readJSON(): Any?

    public fun readKey(): String
}

public open class IdSetDecoderV1(
    public val restDecoder: BinaryDecoder,
) {
    public constructor(bytes: ByteArray) : this(BinaryDecoder(bytes))

    public fun hasRemaining(): Boolean = restDecoder.hasRemaining()

    public open fun resetDsCurVal() {
        // V1 reads absolute clocks.
    }

    public open fun readDsClock(): Long = restDecoder.readVarUInt()

    public open fun readDsLen(): Long = restDecoder.readVarUInt()
}

public open class IdSetDecoderV2(
    restDecoder: BinaryDecoder,
) : IdSetDecoderV1(restDecoder) {
    public constructor(bytes: ByteArray) : this(BinaryDecoder(bytes))

    private var dsCurrVal: Long = 0

    override fun resetDsCurVal() {
        dsCurrVal = 0
    }

    override fun readDsClock(): Long {
        dsCurrVal = checkedClockAdd(dsCurrVal, restDecoder.readVarUInt(), "delete clock")
        return dsCurrVal
    }

    override fun readDsLen(): Long {
        val len = checkedClockAdd(restDecoder.readVarUInt(), 1, "delete length")
        dsCurrVal = checkedClockAdd(dsCurrVal, len, "delete end")
        return len
    }
}

public open class UpdateDecoderV1(
    restDecoder: BinaryDecoder,
) : IdSetDecoderV1(restDecoder), UpdateContentDecoder {
    public constructor(bytes: ByteArray) : this(BinaryDecoder(bytes))

    public fun readLeftID(): Id = readId()

    public fun readRightID(): Id = readId()

    public fun readClient(): Long = restDecoder.readVarUInt()

    public fun readInfo(): Int = restDecoder.readByte()

    override fun readString(): String = restDecoder.readString()

    public fun readParentInfo(): Boolean = restDecoder.readVarUInt() == 1L

    override fun readTypeRef(): Int = restDecoder.readVarUInt().toDecodedCount("type ref")

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
    // Yjs currently emits zero, but UpdateDecoderV2 deliberately reads and ignores this feature
    // field. Preserve that forward-compatible acceptance behavior for non-zero values.
    decoder.readVarUInt()
    val encoded = List(9) { decoder.readDecoderView() }
    return V2DecoderStreams(
        rest = decoder.readRemainingDecoderView(),
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

public open class UpdateDecoderV2 private constructor(
    private val streams: V2DecoderStreams,
) : IdSetDecoderV2(streams.rest), UpdateContentDecoder {
    internal val usesLegacyRest: Boolean get() = false
    private val keys = mutableListOf<String>()
    public constructor(bytes: ByteArray) : this(readV2DecoderStreams(bytes))

    public constructor(decoder: BinaryDecoder) : this(decoder.readRemainingBytes())

    public fun readLeftID(): Id = Id(streams.clients.read(), streams.leftClocks.read())

    public fun readRightID(): Id = Id(streams.clients.read(), streams.rightClocks.read())

    public fun readClient(): Long = streams.clients.read()

    public fun readInfo(): Int = streams.infos.read()

    override fun readString(): String = streams.strings.read()

    public fun readParentInfo(): Boolean = streams.parentInfos.read() == 1

    override fun readTypeRef(): Int = streams.typeRefs.read().toDecodedCount("type ref")

    override fun readLen(): Long = streams.lengths.read()

    override fun readAny(): Any? = readLib0Any(restDecoder)

    override fun readBuf(): ByteArray = restDecoder.readBytes()

    override fun readJSON(): Any? = readAny()

    override fun readKey(): String {
        val clock = streams.keyClocks.read().toDecodedCount("key clock")
        if (clock < keys.size) return keys[clock]
        return streams.strings.read().also(keys::add)
    }
}
