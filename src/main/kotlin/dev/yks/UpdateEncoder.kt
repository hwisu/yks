package dev.yks

public interface UpdateContentEncoder {
    public fun writeString(value: String)

    public fun writeTypeRef(info: Int)

    public fun writeLen(len: Long)

    public fun writeAny(value: Any?)

    public fun writeBuf(value: ByteArray)

    public fun writeJSON(value: Any?)

    public fun writeKey(key: String)
}

public open class IdSetEncoderV1(
    public val restEncoder: BinaryEncoder = BinaryEncoder(),
) {
    public open fun toByteArray(): ByteArray = restEncoder.toByteArray()

    public open fun toUint8Array(): ByteArray = toByteArray()

    public open fun resetIdSetCurVal() {
        // V1 stores absolute clocks.
    }

    public open fun writeIdSetClock(clock: Long) {
        restEncoder.writeVarUInt(clock)
    }

    public open fun writeIdSetLen(len: Long) {
        restEncoder.writeVarUInt(len)
    }
}

public open class IdSetEncoderV2(
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

public open class UpdateEncoderV1(
    restEncoder: BinaryEncoder = BinaryEncoder(),
) : IdSetEncoderV1(restEncoder), UpdateContentEncoder {
    public fun writeLeftID(id: Id) {
        writeId(id)
    }

    public fun writeRightID(id: Id) {
        writeId(id)
    }

    public fun writeClient(client: Long) {
        restEncoder.writeVarUInt(client)
    }

    public fun writeInfo(info: Int) {
        require(info in 0..255) { "info must be an unsigned byte" }
        restEncoder.writeByte(info)
    }

    override fun writeString(value: String) {
        restEncoder.writeString(value)
    }

    public fun writeParentInfo(isYKey: Boolean) {
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
        writeLib0Any(restEncoder, value)
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

public open class UpdateEncoderV2(
    restEncoder: BinaryEncoder = BinaryEncoder(),
) : IdSetEncoderV2(restEncoder), UpdateContentEncoder {
    private val keyClocks = Lib0IntDiffOptRleEncoder()
    private val clients = Lib0UintOptRleEncoder()
    private val leftClocks = Lib0IntDiffOptRleEncoder()
    private val rightClocks = Lib0IntDiffOptRleEncoder()
    private val infos = Lib0ByteRleEncoder()
    private val strings = Lib0StringEncoder()
    private val parentInfos = Lib0ByteRleEncoder()
    private val typeRefs = Lib0UintOptRleEncoder()
    private val lengths = Lib0UintOptRleEncoder()
    private var keyClock = 0L
    private var hasOptimizedContent = false
    private var encodedUpdate: ByteArray? = null

    internal fun forceV2Envelope() {
        hasOptimizedContent = true
    }

    internal fun setEncodedUpdate(bytes: ByteArray) {
        encodedUpdate = bytes.copyOf()
    }

    override fun toByteArray(): ByteArray {
        encodedUpdate?.let { return it.copyOf() }
        return BinaryEncoder().also { encoder ->
        encoder.writeVarUInt(0)
        encoder.writeBytes(keyClocks.toByteArray())
        encoder.writeBytes(clients.toByteArray())
        encoder.writeBytes(leftClocks.toByteArray())
        encoder.writeBytes(rightClocks.toByteArray())
        encoder.writeBytes(infos.toByteArray())
        encoder.writeBytes(strings.toByteArray())
        encoder.writeBytes(parentInfos.toByteArray())
        encoder.writeBytes(typeRefs.toByteArray())
        encoder.writeBytes(lengths.toByteArray())
        encoder.writeRawBytes(restEncoder.toByteArray())
        }.toByteArray()
    }

    override fun toUint8Array(): ByteArray = toByteArray()

    public fun writeLeftID(id: Id) {
        hasOptimizedContent = true
        clients.write(id.client)
        leftClocks.write(id.clock)
    }

    public fun writeRightID(id: Id) {
        hasOptimizedContent = true
        clients.write(id.client)
        rightClocks.write(id.clock)
    }

    public fun writeClient(client: Long) {
        hasOptimizedContent = true
        clients.write(client)
    }

    public fun writeInfo(info: Int) {
        require(info in 0..255) { "info must be an unsigned byte" }
        hasOptimizedContent = true
        infos.write(info)
    }

    override fun writeString(value: String) {
        hasOptimizedContent = true
        strings.write(value)
    }

    public fun writeParentInfo(isYKey: Boolean) {
        hasOptimizedContent = true
        parentInfos.write(if (isYKey) 1 else 0)
    }

    override fun writeTypeRef(info: Int) {
        require(info >= 0) { "type ref must be non-negative" }
        hasOptimizedContent = true
        typeRefs.write(info.toLong())
    }

    override fun writeLen(len: Long) {
        hasOptimizedContent = true
        lengths.write(len)
    }

    override fun writeAny(value: Any?) {
        writeLib0Any(restEncoder, value)
    }

    override fun writeBuf(value: ByteArray) {
        restEncoder.writeBytes(value)
    }

    override fun writeJSON(value: Any?) {
        writeAny(value)
    }

    override fun writeKey(key: String) {
        hasOptimizedContent = true
        keyClocks.write(keyClock++)
        strings.write(key)
    }
}
