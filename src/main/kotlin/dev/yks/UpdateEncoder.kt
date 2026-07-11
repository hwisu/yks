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
    open fun toByteArray(): ByteArray = restEncoder.toByteArray()

    open fun toUint8Array(): ByteArray = toByteArray()

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

open class UpdateEncoderV2(
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
        if (!hasOptimizedContent) return restEncoder.toByteArray()
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

    fun writeLeftID(id: Id) {
        hasOptimizedContent = true
        clients.write(id.client)
        leftClocks.write(id.clock)
    }

    fun writeRightID(id: Id) {
        hasOptimizedContent = true
        clients.write(id.client)
        rightClocks.write(id.clock)
    }

    fun writeClient(client: Long) {
        hasOptimizedContent = true
        clients.write(client)
    }

    fun writeInfo(info: Int) {
        require(info in 0..255) { "info must be an unsigned byte" }
        hasOptimizedContent = true
        infos.write(info)
    }

    override fun writeString(value: String) {
        hasOptimizedContent = true
        strings.write(value)
    }

    fun writeParentInfo(isYKey: Boolean) {
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
