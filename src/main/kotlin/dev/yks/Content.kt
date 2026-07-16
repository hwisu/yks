package dev.yks

const val contentDeletedRefNumber: Int = 1
const val contentJSONRefNumber: Int = 2
const val contentBinaryRefNumber: Int = 3
const val contentStringRefNumber: Int = 4
const val contentEmbedRefNumber: Int = 5
const val contentFormatRefNumber: Int = 6
const val contentTypeRefNumber: Int = 7
const val contentAnyRefNumber: Int = 8
const val contentDocRefNumber: Int = 9

abstract class AbstractContent {
    abstract fun getLength(): Long

    abstract fun getContent(): List<Any?>

    abstract fun isCountable(): Boolean

    abstract fun copy(): AbstractContent

    open fun splice(offset: Long): AbstractContent {
        error("content cannot be spliced")
    }

    open fun mergeWith(right: AbstractContent): Boolean = false

    abstract fun write(encoder: UpdateContentEncoder, offset: Long = 0, offsetEnd: Long = 0): UpdateContentEncoder

    abstract fun getRef(): Int
}

class ContentAny(
    arr: List<Any?>,
) : AbstractContent() {
    var arr: List<Any?> = arr.map(::copyContentValue)
        private set

    override fun getLength(): Long = arr.size.toLong()

    override fun getContent(): List<Any?> = arr.map(::copyContentValue)

    override fun isCountable(): Boolean = true

    override fun copy(): ContentAny = ContentAny(arr)

    override fun splice(offset: Long): ContentAny {
        val index = checkedOffset(offset, arr.size)
        val right = ContentAny(arr.drop(index))
        arr = arr.take(index)
        return right
    }

    override fun mergeWith(right: AbstractContent): Boolean {
        if (right !is ContentAny) return false
        arr = arr + right.arr
        return true
    }

    override fun write(encoder: UpdateContentEncoder, offset: Long, offsetEnd: Long): UpdateContentEncoder {
        val range = checkedWriteRange(offset, offsetEnd, arr.size)
        encoder.writeLen((range.lastExclusive - range.first).toLong())
        arr.subList(range.first, range.lastExclusive).forEach(encoder::writeAny)
        return encoder
    }

    override fun getRef(): Int = contentAnyRefNumber

    override fun equals(other: Any?): Boolean = other is ContentAny && contentValuesEqual(arr, other.arr)

    override fun hashCode(): Int = contentValuesHash(arr)

    override fun toString(): String = "ContentAny(arr=$arr)"
}

class ContentBinary(
    content: ByteArray,
) : AbstractContent() {
    private var bytes: ByteArray = content.copyOf()

    val content: ByteArray get() = bytes.copyOf()

    override fun getLength(): Long = 1

    override fun getContent(): List<Any?> = listOf(content)

    override fun isCountable(): Boolean = true

    override fun copy(): ContentBinary = ContentBinary(bytes)

    override fun mergeWith(right: AbstractContent): Boolean = false

    override fun write(encoder: UpdateContentEncoder, offset: Long, offsetEnd: Long): UpdateContentEncoder {
        checkedWriteRange(offset, offsetEnd, 1)
        encoder.writeBuf(bytes)
        return encoder
    }

    override fun getRef(): Int = contentBinaryRefNumber

    override fun equals(other: Any?): Boolean = other is ContentBinary && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = "ContentBinary(${bytes.size} bytes)"
}

class ContentDeleted(
    var len: Long,
) : AbstractContent() {
    init {
        require(len >= 0) { "len must be non-negative" }
    }

    override fun getLength(): Long = len

    override fun getContent(): List<Any?> = emptyList()

    override fun isCountable(): Boolean = false

    override fun copy(): ContentDeleted = ContentDeleted(len)

    override fun splice(offset: Long): ContentDeleted {
        checkedOffset(offset, len)
        val right = ContentDeleted(len - offset)
        len = offset
        return right
    }

    override fun mergeWith(right: AbstractContent): Boolean {
        if (right !is ContentDeleted) return false
        len += right.len
        return true
    }

    override fun write(encoder: UpdateContentEncoder, offset: Long, offsetEnd: Long): UpdateContentEncoder {
        checkedWriteRange(offset, offsetEnd, len)
        encoder.writeLen(len - offset - offsetEnd)
        return encoder
    }

    override fun getRef(): Int = contentDeletedRefNumber

    override fun equals(other: Any?): Boolean = other is ContentDeleted && len == other.len

    override fun hashCode(): Int = len.hashCode()

    override fun toString(): String = "ContentDeleted(len=$len)"
}

class ContentDoc(
    val guid: String,
    opts: Map<String, Any?> = emptyMap(),
) : AbstractContent() {
    val opts: Map<String, Any?> = opts.mapValues { (_, value) -> copyContentValue(value) }
    var doc: YDoc? = null

    override fun getLength(): Long = 1

    override fun getContent(): List<Any?> = listOf(doc)

    override fun isCountable(): Boolean = true

    override fun copy(): ContentDoc = ContentDoc(guid, opts)

    override fun mergeWith(right: AbstractContent): Boolean = false

    override fun write(encoder: UpdateContentEncoder, offset: Long, offsetEnd: Long): UpdateContentEncoder {
        checkedWriteRange(offset, offsetEnd, 1)
        encoder.writeString(guid)
        encoder.writeAny(opts)
        return encoder
    }

    override fun getRef(): Int = contentDocRefNumber

    override fun equals(other: Any?): Boolean =
        other is ContentDoc && guid == other.guid && contentValuesEqual(opts, other.opts) && doc == other.doc

    override fun hashCode(): Int = 31 * guid.hashCode() + contentValuesHash(opts)

    override fun toString(): String = "ContentDoc(guid=$guid, opts=$opts)"
}

fun createContentDocFromDoc(doc: YDoc): ContentDoc {
    val opts = linkedMapOf<String, Any?>()
    if (!doc.gc) opts["gc"] = false
    if (doc.autoLoad) opts["autoLoad"] = true
    doc.meta?.let { opts["meta"] = it }
    return ContentDoc(doc.guid, opts).also { content -> content.doc = doc }
}

internal fun ContentDoc.toYDoc(): YDoc = doc ?: YDoc(
    guid = guid,
    collectionId = opts.stringOption("collectionId") ?: opts.stringOption("collectionid"),
    gc = opts.booleanOption("gc", default = true),
    meta = opts["meta"],
    shouldLoad = opts.booleanOption("shouldLoad") || opts.booleanOption("autoLoad"),
    autoLoad = opts.booleanOption("autoLoad"),
    isSuggestionDoc = opts.booleanOption("isSuggestionDoc"),
)

internal fun ContentDoc.toSubdocRef(): YValue.SubdocRef {
    doc?.let { return YValue.from(it) as YValue.SubdocRef }
    val autoLoad = opts.booleanOption("autoLoad")
    return YValue.SubdocRef(
        guid = guid,
        gc = opts.booleanOption("gc", default = true),
        shouldLoad = opts.booleanOption("shouldLoad") || autoLoad,
        autoLoad = autoLoad,
        instanceId = guid,
        collectionId = opts.stringOption("collectionId") ?: opts.stringOption("collectionid"),
        meta = YValue.from(opts["meta"]),
        isSuggestionDoc = opts.booleanOption("isSuggestionDoc"),
    )
}

private fun Map<String, Any?>.booleanOption(key: String, default: Boolean = false): Boolean =
    this[key] as? Boolean ?: default

private fun Map<String, Any?>.stringOption(key: String): String? =
    this[key] as? String

class ContentEmbed(
    val embed: Any?,
) : AbstractContent() {
    override fun getLength(): Long = 1

    override fun getContent(): List<Any?> = listOf(copyContentValue(embed))

    override fun isCountable(): Boolean = true

    override fun copy(): ContentEmbed = ContentEmbed(copyContentValue(embed))

    override fun mergeWith(right: AbstractContent): Boolean = false

    override fun write(encoder: UpdateContentEncoder, offset: Long, offsetEnd: Long): UpdateContentEncoder {
        checkedWriteRange(offset, offsetEnd, 1)
        encoder.writeJSON(embed)
        return encoder
    }

    override fun getRef(): Int = contentEmbedRefNumber

    override fun equals(other: Any?): Boolean = other is ContentEmbed && contentValuesEqual(embed, other.embed)

    override fun hashCode(): Int = contentValueHash(embed)

    override fun toString(): String = "ContentEmbed(embed=$embed)"
}

class ContentFormat(
    val key: String,
    val value: Any?,
) : AbstractContent() {
    override fun getLength(): Long = 1

    override fun getContent(): List<Any?> = emptyList()

    override fun isCountable(): Boolean = false

    override fun copy(): ContentFormat = ContentFormat(key, copyContentValue(value))

    override fun mergeWith(right: AbstractContent): Boolean = false

    override fun write(encoder: UpdateContentEncoder, offset: Long, offsetEnd: Long): UpdateContentEncoder {
        checkedWriteRange(offset, offsetEnd, 1)
        encoder.writeKey(key)
        encoder.writeJSON(value)
        return encoder
    }

    override fun getRef(): Int = contentFormatRefNumber

    override fun equals(other: Any?): Boolean =
        other is ContentFormat && key == other.key && contentValuesEqual(value, other.value)

    override fun hashCode(): Int = 31 * key.hashCode() + contentValueHash(value)

    override fun toString(): String = "ContentFormat(key=$key, value=$value)"
}

data class ContentTextFormatRange(
    val target: Id,
    val len: Long,
    val attributes: Map<String, YValue>,
    val afterAttributes: Map<String, YValue>,
    val beforeAttributes: List<Map<String, YValue>> = emptyList(),
) : AbstractContent() {
    init {
        require(len > 0) { "text format length must be positive" }
    }

    override fun getLength(): Long = 1

    override fun getContent(): List<Any?> = emptyList()

    override fun isCountable(): Boolean = false

    override fun copy(): ContentTextFormatRange = copy(
        attributes = attributes.toSortedMap(),
        afterAttributes = afterAttributes.toSortedMap(),
        beforeAttributes = beforeAttributes.map { it.toSortedMap() },
    )

    override fun mergeWith(right: AbstractContent): Boolean = false

    override fun write(encoder: UpdateContentEncoder, offset: Long, offsetEnd: Long): UpdateContentEncoder {
        checkedWriteRange(offset, offsetEnd, 1)
        encoder.writeKey("__yks_text_format")
        encoder.writeJSON(
            mapOf(
                "target" to mapOf("client" to target.client, "clock" to target.clock),
                "length" to len,
                "attributes" to attributes.mapValues { (_, value) -> value.toAny() },
                "afterAttributes" to afterAttributes.mapValues { (_, value) -> value.toAny() },
                "beforeAttributes" to beforeAttributes.map { attrs ->
                    attrs.mapValues { (_, value) -> value.toAny() }
                },
            ),
        )
        return encoder
    }

    override fun getRef(): Int = contentFormatRefNumber
}

class ContentJSON(
    arr: List<Any?>,
) : AbstractContent() {
    var arr: List<Any?> = arr.map(::copyContentValue)
        private set

    override fun getLength(): Long = arr.size.toLong()

    override fun getContent(): List<Any?> = arr.map(::copyContentValue)

    override fun isCountable(): Boolean = true

    override fun copy(): ContentJSON = ContentJSON(arr)

    override fun splice(offset: Long): ContentJSON {
        val index = checkedOffset(offset, arr.size)
        val right = ContentJSON(arr.drop(index))
        arr = arr.take(index)
        return right
    }

    override fun mergeWith(right: AbstractContent): Boolean {
        if (right !is ContentJSON) return false
        arr = arr + right.arr
        return true
    }

    override fun write(encoder: UpdateContentEncoder, offset: Long, offsetEnd: Long): UpdateContentEncoder {
        val range = checkedWriteRange(offset, offsetEnd, arr.size)
        encoder.writeLen((range.lastExclusive - range.first).toLong())
        arr.subList(range.first, range.lastExclusive).forEach { value ->
            encoder.writeString(toJsonLiteral(value))
        }
        return encoder
    }

    override fun getRef(): Int = contentJSONRefNumber

    override fun equals(other: Any?): Boolean = other is ContentJSON && contentValuesEqual(arr, other.arr)

    override fun hashCode(): Int = contentValuesHash(arr)

    override fun toString(): String = "ContentJSON(arr=$arr)"
}

class ContentString(
    var str: String,
) : AbstractContent() {
    override fun getLength(): Long = str.length.toLong()

    override fun getContent(): List<Any?> = str.map { it.toString() }

    override fun isCountable(): Boolean = true

    override fun copy(): ContentString = ContentString(str)

    override fun splice(offset: Long): ContentString {
        val index = checkedOffset(offset, str.length)
        val right = ContentString(str.substring(index))
        str = str.substring(0, index)
        if (index > 0 && str[index - 1].isHighSurrogate()) {
            str = str.substring(0, index - 1) + "\uFFFD"
            right.str = "\uFFFD" + right.str.drop(1)
        }
        return right
    }

    override fun mergeWith(right: AbstractContent): Boolean {
        if (right !is ContentString) return false
        str += right.str
        return true
    }

    override fun write(encoder: UpdateContentEncoder, offset: Long, offsetEnd: Long): UpdateContentEncoder {
        val range = checkedWriteRange(offset, offsetEnd, str.length)
        encoder.writeString(str.substring(range.first, range.lastExclusive))
        return encoder
    }

    override fun getRef(): Int = contentStringRefNumber

    override fun equals(other: Any?): Boolean = other is ContentString && str == other.str

    override fun hashCode(): Int = str.hashCode()

    override fun toString(): String = "ContentString(str=$str)"
}

class ContentType(
    val type: AbstractYType,
) : AbstractContent() {
    override fun getLength(): Long = 1

    override fun getContent(): List<Any?> = listOf(type)

    override fun isCountable(): Boolean = true

    override fun copy(): ContentType = ContentType(type.emptyContentTypeCopy())

    override fun mergeWith(right: AbstractContent): Boolean = false

    override fun write(encoder: UpdateContentEncoder, offset: Long, offsetEnd: Long): UpdateContentEncoder {
        checkedWriteRange(offset, offsetEnd, 1)
        writeYType(encoder, type)
        return encoder
    }

    override fun getRef(): Int = contentTypeRefNumber

    override fun equals(other: Any?): Boolean = other is ContentType && type === other.type

    override fun hashCode(): Int = System.identityHashCode(type)

    override fun toString(): String = "ContentType(type=${type.name})"
}

fun readContentType(decoder: UpdateContentDecoder): ContentType = ContentType(readYType(decoder))

fun readContentString(decoder: UpdateContentDecoder): ContentString = ContentString(decoder.readString())

fun readContentJSON(decoder: UpdateContentDecoder): ContentJSON {
    val len = decoder.readLen().toDecodedCount()
    require(len >= 0) { "content JSON length must be non-negative" }
    return ContentJSON(List(len) {
        when (val encoded = decoder.readString()) {
            "undefined" -> null
            else -> parseJsonLiteral(encoded)
        }
    })
}

fun readContentFormat(decoder: UpdateContentDecoder): ContentFormat =
    ContentFormat(decoder.readKey(), decoder.readJSON())

fun readContentEmbed(decoder: UpdateContentDecoder): ContentEmbed =
    ContentEmbed(decoder.readJSON())

fun readContentDoc(decoder: UpdateContentDecoder): ContentDoc =
    ContentDoc(decoder.readString(), decoder.readAny().asContentDocOpts())

fun readContentAny(decoder: UpdateContentDecoder): ContentAny {
    val len = decoder.readLen().toDecodedCount()
    require(len >= 0) { "content any length must be non-negative" }
    return ContentAny(List(len) { decoder.readAny() })
}

fun readContentBinary(decoder: UpdateContentDecoder): ContentBinary = ContentBinary(decoder.readBuf())

fun readContentDeleted(decoder: UpdateContentDecoder): ContentDeleted = ContentDeleted(decoder.readLen())

val contentRefs: List<(UpdateContentDecoder) -> AbstractContent> = listOf(
    { error("GC is not item content") },
    ::readContentDeleted,
    ::readContentJSON,
    ::readContentBinary,
    ::readContentString,
    ::readContentEmbed,
    ::readContentFormat,
    ::readContentType,
    ::readContentAny,
    ::readContentDoc,
    { error("Skip is not item content") },
)

fun readItemContent(decoder: UpdateContentDecoder, info: Int): AbstractContent {
    val ref = info and 0x1f
    val reader = contentRefs.getOrNull(ref) ?: error("unknown item content ref: $ref")
    return reader(decoder)
}

fun readYType(decoder: UpdateContentDecoder, doc: YDoc = YDoc()): AbstractYType {
    return when (val typeRef = decoder.readTypeRef()) {
        YArrayRefID -> doc.createArray()
        YMapRefID -> doc.createMap()
        YTextRefID -> doc.createText()
        YXmlFragmentRefID -> doc.createXmlFragment()
        YXmlElementRefID -> {
            val nodeName = decoder.readKey()
            doc.createXmlElementType(
                nodeName = nodeName,
                kind = RootKind.XmlElement,
            )
        }
        YXmlHookRefID -> doc.createXmlHook(decoder.readKey())
        YXmlTextRefID -> doc.createXmlTextType()
        else -> error("unknown type ref: $typeRef")
    }
}

fun writeYType(encoder: UpdateContentEncoder, type: AbstractYType): UpdateContentEncoder {
    encoder.writeTypeRef(type.typeRef)
    when (type) {
        is YXmlElementType -> encoder.writeKey(type.nodeName)
        is YXmlHook -> encoder.writeKey(type.hookName)
        else -> Unit
    }
    return encoder
}

fun writeItemContent(
    encoder: UpdateContentEncoder,
    content: AbstractContent,
    offset: Long = 0,
    offsetEnd: Long = 0,
): UpdateContentEncoder = content.write(encoder, offset, offsetEnd)

internal fun ItemContent.toContent(doc: YDoc): AbstractContent = when (this) {
    is ItemContent.Value -> value.toContent(doc)
    is ItemContent.Text -> ContentString(value)
    is ItemContent.TextEmbed -> ContentEmbed(doc.valueToAny(value))
    is ItemContent.TextFormat -> ContentTextFormatRange(target, length, attributes, afterAttributes, beforeAttributes)
    is ItemContent.NativeTextFormat -> ContentFormat(key, doc.valueToAny(value))
    is ItemContent.MapEntry -> value.toContent(doc)
    is ItemContent.XmlNode -> ContentAny(listOf(value.toEventJson()))
    is ItemContent.XmlType -> ContentType(doc.typeFromXmlType(this))
    is ItemContent.Deleted -> ContentDeleted(length)
}

private fun YValue.toContent(doc: YDoc): AbstractContent {
    val value = doc.valueToAny(this)
    return when (value) {
        is ByteArray -> ContentBinary(value)
        is AbstractYType -> ContentType(value)
        is YDoc -> createContentDocFromDoc(value)
        else -> ContentAny(listOf(value))
    }
}

private fun checkedOffset(offset: Long, size: Int): Int {
    require(offset >= 0) { "offset must be non-negative" }
    require(offset <= size) { "offset is out of bounds" }
    return offset.toInt()
}

private fun checkedOffset(offset: Long, size: Long) {
    require(offset >= 0) { "offset must be non-negative" }
    require(offset <= size) { "offset is out of bounds" }
}

private data class WriteRange(val first: Int, val lastExclusive: Int)

private fun checkedWriteRange(offset: Long, offsetEnd: Long, size: Int): WriteRange {
    val first = checkedWriteStartAndEnd(offset, offsetEnd, size.toLong())
    return WriteRange(first.first.toInt(), first.second.toInt())
}

private fun checkedWriteRange(offset: Long, offsetEnd: Long, size: Long): Pair<Long, Long> =
    checkedWriteStartAndEnd(offset, offsetEnd, size)

private fun checkedWriteStartAndEnd(offset: Long, offsetEnd: Long, size: Long): Pair<Long, Long> {
    require(offset >= 0) { "offset must be non-negative" }
    require(offsetEnd >= 0) { "offsetEnd must be non-negative" }
    require(offset <= size) { "offset is out of bounds" }
    require(offsetEnd <= size - offset) { "offsetEnd is out of bounds" }
    return offset to (size - offsetEnd)
}

private fun Any?.asContentDocOpts(): Map<String, Any?> = when (this) {
    null -> emptyMap()
    is Map<*, *> -> entries.associate { (key, value) ->
        require(key is String) { "ContentDoc opts keys must be strings" }
        key to copyContentValue(value)
    }.toSortedMap()
    else -> error("ContentDoc opts must be an object")
}

internal fun parseJsonLiteral(source: String): Any? = JsonLiteralParser(source).parse()

internal fun toJsonLiteral(value: Any?): String = when (value) {
    null -> "null"
    is Boolean -> value.toString()
    is Byte,
    is Short,
    is Int,
    is Long -> (value as Number).toLong().toString()
    is Float -> value.toDouble().toJsonNumber()
    is Double -> value.toJsonNumber()
    is String -> value.toJsonString()
    is List<*> -> value.joinToString(separator = ",", prefix = "[", postfix = "]") { nested -> toJsonLiteral(nested) }
    is Array<*> -> value.joinToString(separator = ",", prefix = "[", postfix = "]") { nested -> toJsonLiteral(nested) }
    is Map<*, *> -> value.entries.joinToString(separator = ",", prefix = "{", postfix = "}") { (key, nested) ->
        require(key is String) { "JSON object keys must be strings" }
        "${key.toJsonString()}:${toJsonLiteral(nested)}"
    }
    else -> error("unsupported JSON value: ${value::class.qualifiedName}")
}

private fun Double.toJsonNumber(): String {
    require(isFinite()) { "JSON numbers must be finite" }
    return toString()
}

private fun String.toJsonString(): String {
    val out = StringBuilder("\"")
    forEach { char ->
        when (char) {
            '"' -> out.append("\\\"")
            '\\' -> out.append("\\\\")
            '\b' -> out.append("\\b")
            '\u000C' -> out.append("\\f")
            '\n' -> out.append("\\n")
            '\r' -> out.append("\\r")
            '\t' -> out.append("\\t")
            else -> {
                if (char.code < 0x20) {
                    out.append("\\u")
                    out.append(char.code.toString(16).padStart(4, '0'))
                } else {
                    out.append(char)
                }
            }
        }
    }
    out.append('"')
    return out.toString()
}

private class JsonLiteralParser(private val source: String) {
    private var index = 0
    private val decodeBudget = DecodeBudget()

    fun parse(): Any? {
        val value = parseValue()
        skipWhitespace()
        check(index == source.length) { "invalid trailing JSON content" }
        return value
    }

    private fun parseValue(): Any? {
        decodeBudget.consumeNode()
        skipWhitespace()
        check(index < source.length) { "unexpected end of JSON input" }
        return when (source[index]) {
            'n' -> {
                expect("null")
                null
            }
            't' -> {
                expect("true")
                true
            }
            'f' -> {
                expect("false")
                false
            }
            '"' -> parseString()
            '[' -> decodeBudget.nested(::parseArray)
            '{' -> decodeBudget.nested(::parseObject)
            else -> parseNumber()
        }
    }

    private fun parseString(): String {
        expect('"')
        val out = StringBuilder()
        while (index < source.length) {
            val c = source[index++]
            when (c) {
                '"' -> return out.toString()
                '\\' -> out.append(parseEscape())
                else -> {
                    check(c.code >= 0x20) { "unescaped control character in JSON string" }
                    out.append(c)
                }
            }
        }
        error("unterminated JSON string")
    }

    private fun parseEscape(): Char {
        check(index < source.length) { "unterminated JSON escape" }
        return when (val escaped = source[index++]) {
            '"' -> '"'
            '\\' -> '\\'
            '/' -> '/'
            'b' -> '\b'
            'f' -> '\u000C'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'u' -> parseUnicodeEscape()
            else -> error("invalid JSON escape: $escaped")
        }
    }

    private fun parseUnicodeEscape(): Char {
        check(index + 4 <= source.length) { "unterminated JSON unicode escape" }
        val encoded = source.substring(index, index + 4)
        check(encoded.all(Char::isJsonHexDigit)) { "invalid JSON unicode escape" }
        val code = encoded.toInt(16)
        index += 4
        return code.toChar()
    }

    private fun parseArray(): List<Any?> {
        expect('[')
        skipWhitespace()
        if (consume(']')) return emptyList()
        val values = mutableListOf<Any?>()
        while (true) {
            values.add(parseValue())
            skipWhitespace()
            when {
                consume(']') -> return values
                consume(',') -> Unit
                else -> error("expected ',' or ']' in JSON array")
            }
        }
    }

    private fun parseObject(): Map<String, Any?> {
        expect('{')
        skipWhitespace()
        if (consume('}')) return emptyMap()
        val values = linkedMapOf<String, Any?>()
        while (true) {
            skipWhitespace()
            val key = parseString()
            skipWhitespace()
            expect(':')
            values[key] = parseValue()
            skipWhitespace()
            when {
                consume('}') -> return values
                consume(',') -> Unit
                else -> error("expected ',' or '}' in JSON object")
            }
        }
    }

    private fun parseNumber(): Number {
        val start = index
        consume('-')
        when {
            consume('0') -> Unit
            index < source.length && source[index] in '1'..'9' -> readDigits()
            else -> error("expected JSON digit")
        }
        var isFloating = false
        if (consume('.')) {
            isFloating = true
            readDigits()
        }
        if (index < source.length && (source[index] == 'e' || source[index] == 'E')) {
            isFloating = true
            index++
            if (index < source.length && (source[index] == '+' || source[index] == '-')) index++
            readDigits()
        }
        check(index > start) { "expected JSON number" }
        val raw = source.substring(start, index)
        return if (isFloating) raw.toDouble() else raw.toLongOrNull() ?: raw.toDouble()
    }

    private fun readDigits() {
        val start = index
        while (index < source.length && source[index] in '0'..'9') index++
        check(index > start) { "expected JSON digit" }
    }

    private fun skipWhitespace() {
        while (index < source.length && source[index].isJsonWhitespace()) index++
    }

    private fun expect(token: String) {
        check(source.startsWith(token, index)) { "expected '$token'" }
        index += token.length
    }

    private fun expect(char: Char) {
        check(index < source.length && source[index] == char) { "expected '$char'" }
        index++
    }

    private fun consume(char: Char): Boolean {
        if (index >= source.length || source[index] != char) return false
        index++
        return true
    }
}

private fun Char.isJsonWhitespace(): Boolean = this == ' ' || this == '\t' || this == '\n' || this == '\r'

private fun Char.isJsonHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

private fun Char.isHighSurrogate(): Boolean = code in 0xD800..0xDBFF

private fun copyContentValue(value: Any?): Any? = when (value) {
    is ByteArray -> value.copyOf()
    is List<*> -> value.map(::copyContentValue)
    is Map<*, *> -> value.entries.associate { (key, nested) -> key to copyContentValue(nested) }
    else -> value
}

private fun contentValuesEqual(left: Any?, right: Any?): Boolean = when {
    left is ByteArray && right is ByteArray -> left.contentEquals(right)
    left is List<*> && right is List<*> ->
        left.size == right.size && left.indices.all { index -> contentValuesEqual(left[index], right[index]) }
    left is Map<*, *> && right is Map<*, *> ->
        left.keys == right.keys && left.keys.all { key -> contentValuesEqual(left[key], right[key]) }
    else -> left == right
}

private fun contentValueHash(value: Any?): Int = when (value) {
    is ByteArray -> value.contentHashCode()
    is List<*> -> contentValuesHash(value)
    is Map<*, *> -> contentValuesHash(value)
    else -> value.hashCode()
}

private fun contentValuesHash(values: Iterable<Any?>): Int =
    values.fold(1) { acc, value -> 31 * acc + contentValueHash(value) }

private fun contentValuesHash(values: Map<*, *>): Int =
    values.entries.sortedBy { (key, _) -> key.toString() }
        .fold(1) { acc, (key, value) -> 31 * (31 * acc + contentValueHash(key)) + contentValueHash(value) }
