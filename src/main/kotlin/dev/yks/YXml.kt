package dev.yks

sealed class YXmlNode {
    internal abstract fun toValue(): YXmlNodeValue
    abstract fun clone(): YXmlNode
    abstract fun toJson(): Any?
    open fun toJSON(): Any? = toJson()
    abstract override fun toString(): String
}

class YXmlText(private val text: String) : YXmlNode() {
    val length: Int get() = text.length
    val typeRef: Int get() = YXmlTextRefID
    val legacyTypeRef: Int get() = typeRef

    internal override fun toValue(): YXmlNodeValue = YXmlNodeValue.Text(text)

    override fun clone(): YXmlText = YXmlText(text)

    override fun toJson(): String = text

    fun toDeltaDeep(): String = text

    override fun toString(): String = escapeText(text)
}

internal class YXmlElementType internal constructor(
    doc: YDoc,
    val nodeName: String,
    kind: RootKind = RootKind.XmlElement,
) : AbstractYType(doc, nodeName, kind) {
    init {
        require(kind == RootKind.XmlElement || kind == RootKind.XmlHook) { "kind must be an XML element type ref" }
        require(nodeName.isNotBlank()) { "XML element name must not be blank" }
    }

    fun clone(targetDoc: YDoc = doc): YXmlElementType = YXmlElementType(targetDoc, nodeName, kind)

    override fun toJson(): Map<String, Any?> = linkedMapOf(
        "nodeName" to nodeName,
        "attributes" to emptyMap<String, Any?>(),
        "children" to emptyList<Any?>(),
    )

    override fun toJSON(): Map<String, Any?> = yTypeJsonObject(name = nodeName)

    override fun toString(): String = "<$nodeName />"
}

internal class YXmlTextType internal constructor(
    doc: YDoc,
) : AbstractYType(doc, "", RootKind.XmlText) {
    fun clone(targetDoc: YDoc = doc): YXmlTextType = YXmlTextType(targetDoc)

    override fun toJson(): String = ""

    override fun toJSON(): String = ""

    override fun toString(): String = ""
}

class YXmlElement(val nodeName: String) : YXmlNode(), Iterable<YXmlNode> {
    private val attributes = sortedMapOf<String, YValue>()
    private val children = mutableListOf<YXmlNode>()
    val typeRef: Int get() = YXmlElementRefID
    val legacyTypeRef: Int get() = typeRef

    init {
        require(nodeName.isNotBlank()) { "XML element name must not be blank" }
    }

    val length: Int get() = children.size

    val attrSize: Int get() = attributes.size

    fun setAttr(name: String, value: Any?) {
        require(name.isNotBlank()) { "attribute name must not be blank" }
        if (value == null) {
            attributes.remove(name)
        } else {
            attributes[name] = YValue.from(value)
        }
    }

    fun setAttribute(name: String, value: Any?) {
        setAttr(name, value)
    }

    fun setAttrs(values: Map<String, Any?>): YXmlElement {
        values.toSortedMap().forEach { (name, value) -> setAttr(name, value) }
        return this
    }

    fun getAttr(name: String): Any? = attributes[name]?.toAny()

    fun getAttribute(name: String): Any? = getAttr(name)

    fun hasAttr(name: String): Boolean = attributes.containsKey(name)

    fun hasAttribute(name: String): Boolean = hasAttr(name)

    fun deleteAttr(name: String) {
        attributes.remove(name)
    }

    fun deleteAttribute(name: String) {
        deleteAttr(name)
    }

    fun removeAttribute(name: String) {
        deleteAttr(name)
    }

    fun getAttrs(): Map<String, Any?> = attributes.mapValues { (_, value) -> value.toAny() }

    fun attrKeys(): Set<String> = getAttrs().keys

    fun attrValues(): Collection<Any?> = getAttrs().values

    fun attrEntries(): Set<Map.Entry<String, Any?>> = getAttrs().entries

    fun <T> mapAttrs(transform: (value: Any?, key: String) -> T): List<T> {
        return getAttrs().map { (key, value) -> transform(value, key) }
    }

    fun <T> mapAttrs(transform: (value: Any?, key: String, type: YXmlElement) -> T): List<T> {
        return getAttrs().map { (key, value) -> transform(value, key, this) }
    }

    fun forEachAttr(action: (value: Any?, key: String) -> Unit) {
        getAttrs().forEach { (key, value) -> action(value, key) }
    }

    fun forEachAttr(action: (value: Any?, key: String, type: YXmlElement) -> Unit) {
        getAttrs().forEach { (key, value) -> action(value, key, this) }
    }

    fun clearAttrs() {
        attributes.clear()
    }

    fun insert(index: Int, nodes: List<YXmlNode>) {
        if (nodes.isEmpty()) return
        val start = index.coerceAtLeast(0)
        require(start <= children.size) { "insert index is out of bounds" }
        children.addAll(start, nodes.map { it.copyNode() })
    }

    fun push(nodes: List<YXmlNode>) {
        insert(children.size, nodes)
    }

    fun push(vararg nodes: YXmlNode) {
        push(nodes.toList())
    }

    fun unshift(nodes: List<YXmlNode>) {
        insert(0, nodes)
    }

    fun unshift(vararg nodes: YXmlNode) {
        unshift(nodes.toList())
    }

    fun delete(index: Int, length: Int = 1) {
        require(index >= 0) { "index must be non-negative" }
        require(length >= 0) { "length must be non-negative" }
        require(index + length <= children.size) { "delete range is out of bounds" }
        repeat(length) { children.removeAt(index) }
    }

    fun clear() {
        delete(0, length)
    }

    fun get(index: Int): YXmlNode? {
        if (index < 0) return null
        return children.getOrNull(index)?.copyNode()
    }

    fun slice(start: Int = 0, end: Int = length): List<YXmlNode> {
        val normalizedStart = normalizeXmlSliceIndex(start, children.size)
        val normalizedEnd = normalizeXmlSliceIndex(end, children.size)
        if (normalizedEnd <= normalizedStart) return emptyList()
        return children.subList(normalizedStart, normalizedEnd).map { it.copyNode() }
    }

    fun toList(): List<YXmlNode> = children.map { it.copyNode() }

    fun toArray(): List<YXmlNode> = toList()

    fun toDelta(): List<YArrayDeltaOp> {
        val nodes = toList()
        return if (nodes.isEmpty()) emptyList() else listOf(YArrayDeltaOp(insert = nodes))
    }

    fun toDeltaDeep(): YXmlElementDeepDelta = YXmlElementDeepDelta(
        nodeName = nodeName,
        attrs = getAttrs().mapValues { (_, value) -> value.toDeepDeltaValue() },
        children = toList().map { it.toDeepDeltaValue() },
    )

    fun applyDelta(delta: List<YArrayDeltaOp>) {
        var index = 0
        delta.forEach { op ->
            when {
                op.retain != null -> index += op.retain
                op.delete != null -> delete(index, op.delete)
                op.insert != null -> {
                    val nodes = op.insert.map(::xmlNodeFromDeltaValue)
                    insert(index, nodes)
                    index += nodes.size
                }
            }
        }
    }

    fun applyDeltaDeep(delta: YXmlElementDeepDelta, doc: YDoc = YDoc()) {
        require(delta.nodeName == nodeName) { "XML deep-delta nodeName '${delta.nodeName}' does not match '$nodeName'" }
        clear()
        clearAttrs()
        setAttrs(delta.attrs.fromDeepDeltaValues(doc))
        push(delta.children.map { child -> child.toXmlNodeFromDeepDeltaValue(doc) })
    }

    fun <T> map(transform: (YXmlNode) -> T): List<T> = toList().map(transform)

    fun <T> map(transform: (node: YXmlNode, index: Int) -> T): List<T> =
        toList().mapIndexed { index, node -> transform(node, index) }

    fun forEach(action: (YXmlNode) -> Unit) {
        toList().forEach(action)
    }

    fun forEach(action: (node: YXmlNode, index: Int) -> Unit) {
        toList().forEachIndexed { index, node -> action(node, index) }
    }

    fun forEachIndexed(action: (Int, YXmlNode) -> Unit) {
        toList().forEachIndexed(action)
    }

    override fun iterator(): Iterator<YXmlNode> = toList().iterator()

    override fun clone(): YXmlElement = YXmlElement(nodeName).also { cloned ->
        attributes.forEach { (name, value) -> cloned.setAttr(name, value.toAny()) }
        cloned.push(children.map { it.clone() })
    }

    internal override fun toValue(): YXmlNodeValue = YXmlNodeValue.Element(
        nodeName = nodeName,
        attributes = attributes.toMap(),
        children = children.map { it.toValue() },
    )

    override fun toJson(): Map<String, Any?> = linkedMapOf(
        "nodeName" to nodeName,
        "attributes" to getAttrs(),
        "children" to children.map { it.toJson() },
    )

    override fun toJSON(): Map<String, Any?> = yTypeJsonObject(
        name = nodeName,
        children = children.map { toYTypeJsonValue(it) },
        attrs = getAttrs().mapValues { (_, value) -> toYTypeJsonValue(value) },
    )

    fun toString(forceTag: Boolean): String {
        val attrs = xmlAttrsToString(getAttrs())
        val body = children.joinToString(separator = "") { it.toString() }
        if (body.isEmpty()) {
            return "<$nodeName$attrs />"
        }
        return "<$nodeName$attrs>$body</$nodeName>"
    }

    override fun toString(): String = toString(forceTag = false)

    companion object {
        fun from(nodeName: String, delta: List<YArrayDeltaOp>): YXmlElement {
            return YXmlElement(nodeName).also { it.applyDelta(delta) }
        }

        fun from(delta: YXmlElementDeepDelta): YXmlElement = delta.toXmlElement(YDoc())
    }
}

class YXmlFragment internal constructor(doc: YDoc, name: String) :
    AbstractYType(doc, name, RootKind.XmlFragment),
    Iterable<YXmlNode> {
    constructor() : this(YDoc(), "")

    constructor(nodes: Collection<YXmlNode>) : this() {
        push(nodes.toList())
    }

    constructor(vararg nodes: YXmlNode) : this(nodes.toList())

    val length: Int get() = xmlItems().size

    val attrSize: Int get() = getAttrs().size

    fun insert(index: Int, nodes: List<YXmlNode>) {
        if (nodes.isEmpty()) return
        val start = index.coerceAtLeast(0)
        require(start <= length) { "insert index is out of bounds" }
        doc.transact {
            val anchors = doc.insertionAnchors(name, RootKind.XmlFragment, start)
            var origin = anchors.first
            val rightOrigin = anchors.second
            nodes.forEach { node ->
                val item = StoreItem(
                    id = doc.nextId(),
                    origin = origin,
                    rightOrigin = rightOrigin,
                    parent = name,
                    parentSub = null,
                    content = ItemContent.XmlNode(node.toValue()),
                )
                doc.integrateLocal(item)
                origin = item.id
            }
        }
    }

    fun push(nodes: List<YXmlNode>) {
        insert(length, nodes)
    }

    fun push(vararg nodes: YXmlNode) {
        push(nodes.toList())
    }

    fun unshift(nodes: List<YXmlNode>) {
        insert(0, nodes)
    }

    fun unshift(vararg nodes: YXmlNode) {
        unshift(nodes.toList())
    }

    fun delete(index: Int, length: Int = 1) {
        doc.deleteVisible(name, index, length)
    }

    fun setAttr(key: String, value: Any?): Any? = doc.setTypeAttribute(name, key, value)

    fun setAttribute(key: String, value: Any?): Any? = setAttr(key, value)

    fun setAttrs(values: Map<String, Any?>): YXmlFragment {
        doc.transact {
            values.toSortedMap().forEach { (key, value) -> setAttr(key, value) }
        }
        return this
    }

    fun getAttr(key: String): Any? = doc.typeAttribute(name, key)

    fun getAttr(key: String, snapshot: Snapshot): Any? =
        doc.mapValueAtSnapshot(this, key, snapshot)?.let(doc::valueToAny)

    fun getAttribute(key: String): Any? = getAttr(key)

    fun getAttribute(key: String, snapshot: Snapshot): Any? = getAttr(key, snapshot)

    fun getAttrs(): Map<String, Any?> = doc.typeAttributes(name)

    fun getAttrs(snapshot: Snapshot): Map<String, Any?> =
        doc.mapAtSnapshot(this, snapshot).mapValues { (_, value) -> doc.valueToAny(value) }

    fun attrKeys(): Set<String> = getAttrs().keys

    fun attrValues(): Collection<Any?> = getAttrs().values

    fun attrEntries(): Set<Map.Entry<String, Any?>> = getAttrs().entries

    fun <T> mapAttrs(transform: (value: Any?, key: String) -> T): List<T> {
        return getAttrs().map { (key, value) -> transform(value, key) }
    }

    fun <T> mapAttrs(transform: (value: Any?, key: String, type: YXmlFragment) -> T): List<T> {
        return getAttrs().map { (key, value) -> transform(value, key, this) }
    }

    fun forEachAttr(action: (value: Any?, key: String) -> Unit) {
        getAttrs().forEach { (key, value) -> action(value, key) }
    }

    fun forEachAttr(action: (value: Any?, key: String, type: YXmlFragment) -> Unit) {
        getAttrs().forEach { (key, value) -> action(value, key, this) }
    }

    fun hasAttr(key: String): Boolean = doc.hasTypeAttribute(name, key)

    fun hasAttr(key: String, snapshot: Snapshot): Boolean =
        doc.mapValueAtSnapshot(this, key, snapshot) != null

    fun hasAttribute(key: String): Boolean = hasAttr(key)

    fun hasAttribute(key: String, snapshot: Snapshot): Boolean = hasAttr(key, snapshot)

    fun deleteAttr(key: String) {
        doc.deleteTypeAttribute(name, key)
    }

    fun deleteAttribute(key: String) {
        deleteAttr(key)
    }

    fun removeAttribute(key: String) {
        deleteAttr(key)
    }

    fun clearAttrs() {
        doc.transact {
            getAttrs().keys.forEach(::deleteAttr)
        }
    }

    fun get(index: Int): YXmlNode? {
        if (index < 0) return null
        return toList().getOrNull(index)
    }

    fun slice(start: Int = 0, end: Int = length): List<YXmlNode> {
        val nodes = toList()
        val normalizedStart = normalizeXmlSliceIndex(start, nodes.size)
        val normalizedEnd = normalizeXmlSliceIndex(end, nodes.size)
        if (normalizedEnd <= normalizedStart) return emptyList()
        return nodes.subList(normalizedStart, normalizedEnd)
    }

    fun toList(): List<YXmlNode> = xmlItems().map { (it.content as ItemContent.XmlNode).value.toNode() }

    fun toArray(): List<YXmlNode> = toList()

    fun toDelta(): List<YArrayDeltaOp> {
        val nodes = toList()
        return if (nodes.isEmpty()) emptyList() else listOf(YArrayDeltaOp(insert = nodes))
    }

    fun toDeltaDeep(renderer: AbstractRenderer = activeRenderer): YXmlFragmentDeepDelta =
        renderXmlFragmentDeepDelta(this, DeepDeltaRenderOptions(renderer = renderer))

    fun applyDelta(
        delta: List<YArrayDeltaOp>,
        origin: Any? = null,
        renderer: AbstractRenderer = activeRenderer,
    ) {
        doc.transact({ transaction ->
            var renderedIndex = 0
            delta.forEach { op ->
                when {
                    op.retain != null -> renderedIndex += op.retain
                    op.delete != null -> {
                        if (op.delete <= 0) return@forEach
                        val startRendered = renderedIndex.coerceAtLeast(0)
                        recordRendererAttributedDeletes(transaction, this, startRendered, op.delete, renderer)
                        val index = renderedSequenceIndexToVisibleIndex(this, startRendered, renderer)
                        val rawEnd = renderedSequenceIndexToVisibleIndex(
                            this,
                            startRendered + op.delete,
                            renderer,
                            clampToEnd = true,
                        )
                        delete(index, rawEnd - index)
                    }
                    op.insert != null -> {
                        val nodes = op.insert.map(::xmlNodeFromDeltaValue)
                        if (nodes.isEmpty()) return@forEach
                        val index = renderedSequenceIndexToVisibleIndex(this, renderedIndex, renderer)
                        insert(index, nodes)
                        renderedIndex += nodes.size
                    }
                }
            }
        }, origin = origin)
    }

    fun applyDeltaDeep(delta: YXmlFragmentDeepDelta, origin: Any? = null) {
        doc.transact(origin = origin) {
            clear()
            clearAttrs()
            setAttrs(delta.attrs.fromDeepDeltaValues(doc))
            applyDelta(delta.delta.fromXmlDeepDeltaValues(doc))
        }
    }

    fun clear() {
        delete(0, length)
    }

    fun <T> map(transform: (YXmlNode) -> T): List<T> = toList().map(transform)

    fun <T> map(transform: (node: YXmlNode, index: Int) -> T): List<T> =
        toList().mapIndexed { index, node -> transform(node, index) }

    fun forEach(action: (YXmlNode) -> Unit) {
        toList().forEach(action)
    }

    fun forEach(action: (node: YXmlNode, index: Int) -> Unit) {
        toList().forEachIndexed { index, node -> action(node, index) }
    }

    fun forEachIndexed(action: (Int, YXmlNode) -> Unit) {
        toList().forEachIndexed(action)
    }

    override fun iterator(): Iterator<YXmlNode> = toList().iterator()

    fun clone(targetDoc: YDoc = doc): YXmlFragment {
        return targetDoc.createXmlFragment().also { cloned ->
            cloned.push(toList().map { it.clone() })
            cloned.setAttrs(getAttrs().mapValues { (_, value) -> value.cloneValueInto(targetDoc) })
        }
    }

    override fun toJson(): List<Any?> = toList().map { it.toJson() }

    override fun toJSON(): Map<String, Any?> = yTypeJsonObject(
        children = toList().map(::toYTypeJsonValue),
        attrs = getAttrs().mapValues { (_, value) -> toYTypeJsonValue(value) },
    )

    fun toString(forceTag: Boolean): String =
        renderXmlFragmentString(name, getAttrs(), toList(), forceTag = forceTag)

    override fun toString(): String = toString(forceTag = false)

    companion object {
        fun from(delta: List<YArrayDeltaOp>, doc: YDoc = YDoc(), name: String = ""): YXmlFragment {
            return doc.getXmlFragment(name).also { it.applyDelta(delta) }
        }
    }

    private fun xmlItems(): List<StoreItem> = doc.visibleSequence(name).filter { it.content is ItemContent.XmlNode }
}

internal sealed class YXmlNodeValue {
    data class Text(val text: String) : YXmlNodeValue()
    data class Element(
        val nodeName: String,
        val attributes: Map<String, YValue> = emptyMap(),
        val children: List<YXmlNodeValue> = emptyList(),
    ) : YXmlNodeValue()
}

internal fun writeXmlNodeValue(encoder: BinaryEncoder, value: YXmlNodeValue) {
    when (value) {
        is YXmlNodeValue.Text -> {
            encoder.writeByte(0)
            encoder.writeString(value.text)
        }
        is YXmlNodeValue.Element -> {
            encoder.writeByte(1)
            encoder.writeString(value.nodeName)
            encoder.writeVarUInt(value.attributes.size.toLong())
            value.attributes.toSortedMap().forEach { (name, attrValue) ->
                encoder.writeString(name)
                writeYValue(encoder, attrValue)
            }
            encoder.writeVarUInt(value.children.size.toLong())
            value.children.forEach { writeXmlNodeValue(encoder, it) }
        }
    }
}

internal fun readXmlNodeValue(decoder: BinaryDecoder): YXmlNodeValue = when (val tag = decoder.readByte()) {
    0 -> YXmlNodeValue.Text(decoder.readString())
    1 -> {
        val nodeName = decoder.readString()
        val attributes = buildMap {
            repeat(decoder.readVarUInt().toInt()) {
                put(decoder.readString(), readYValue(decoder))
            }
        }.toSortedMap()
        val children = List(decoder.readVarUInt().toInt()) { readXmlNodeValue(decoder) }
        YXmlNodeValue.Element(nodeName, attributes, children)
    }
    else -> error("unknown XML node tag: $tag")
}

internal fun YXmlNodeValue.toEventJson(): Any? = toNode().toJson()

internal fun YXmlNodeValue.toNode(): YXmlNode = when (this) {
    is YXmlNodeValue.Text -> YXmlText(text)
    is YXmlNodeValue.Element -> YXmlElement(nodeName).also { element ->
        attributes.forEach { (name, value) -> element.setAttr(name, value) }
        element.push(children.map { it.toNode() })
    }
}

private fun YXmlNode.copyNode(): YXmlNode = toValue().toNode()

private fun normalizeXmlSliceIndex(index: Int, size: Int): Int {
    val normalized = if (index < 0) size + index else index
    return normalized.coerceIn(0, size)
}

internal fun xmlNodeFromDeltaValue(value: Any?): YXmlNode = when (value) {
    is YXmlNode -> value.clone()
    is String -> YXmlText(value)
    is Char -> YXmlText(value.toString())
    is Map<*, *> -> xmlNodeFromJsonMap(value)
    else -> error("unsupported XML delta value: ${value?.let { it::class.qualifiedName } ?: "null"}")
}

private fun xmlNodeFromJsonMap(value: Map<*, *>): YXmlElement {
    val nodeName = value["nodeName"]
    require(nodeName is String) { "XML element delta maps must include a string nodeName" }
    val element = YXmlElement(nodeName)
    val attributes = value["attributes"]
    if (attributes != null) {
        require(attributes is Map<*, *>) { "XML element attributes must be a map" }
        attributes.entries.forEach { (key, attrValue) ->
            require(key is String) { "XML element attribute keys must be strings" }
            element.setAttr(key, attrValue)
        }
    }
    val children = value["children"]
    if (children != null) {
        require(children is List<*>) { "XML element children must be a list" }
        element.push(children.map(::xmlNodeFromDeltaValue))
    }
    return element
}

internal fun renderXmlFragmentString(
    name: String,
    attrs: Map<String, Any?>,
    nodes: List<YXmlNode>,
    forceTag: Boolean = false,
): String {
    val body = nodes.joinToString(separator = "") { it.toString() }
    if (!forceTag && attrs.isEmpty()) return body

    val attrString = xmlAttrsToString(attrs)
    if (body.isEmpty()) {
        return "<$name$attrString />"
    }
    return "<$name$attrString>$body</$name>"
}

internal fun xmlAttrsToString(attrs: Map<String, Any?>): String =
    attrs.toSortedMap().entries.joinToString(separator = "") { (name, value) ->
        " $name=${xmlAttrValueToString(value)}"
    }

internal fun xmlAttrValueToString(value: Any?): String = when (value) {
    is YXmlElement -> value.toString(forceTag = true)
    is YXmlFragment -> value.toString(forceTag = true)
    else -> toJsonLiteral(value)
}

private fun escapeText(value: String): String = value
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
