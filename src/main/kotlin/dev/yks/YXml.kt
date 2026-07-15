package dev.yks

sealed class YXmlNode {
    internal abstract fun toValue(): YXmlNodeValue
    abstract fun clone(): YXmlNode
    abstract fun toJson(): Any?
    open fun toJSON(): Any? = toJson()
    abstract override fun toString(): String
}

class YXmlText(
    private val text: String,
    attributes: Map<String, Any?> = emptyMap(),
) : YXmlNode() {
    internal val attributes: Map<String, Any?> =
        attributes.filterValues { it != null }.toSortedMap()
    val length: Int get() = text.length
    val typeRef: Int get() = YXmlTextRefID
    val legacyTypeRef: Int get() = typeRef

    internal override fun toValue(): YXmlNodeValue =
        YXmlNodeValue.Text(text, normalizeTextAttributes(attributes))

    override fun clone(): YXmlText = YXmlText(text, attributes)

    override fun toJson(): String = text

    fun toDeltaDeep(): String = text

    override fun toString(): String = renderXmlText(text, attributes)
}

/**
 * Immutable XML-text view captured at a document snapshot.
 *
 * A live [YXmlTextType] may contain multiple formatted runs and embeds, which cannot be
 * represented by the single-run [YXmlText] value. Keeping the delta here preserves its XML
 * serialization while still returning a defensive [YXmlNode] from the snapshot APIs.
 */
internal class YXmlSnapshotText(
    private val delta: YTextDelta,
) : YXmlNode() {
    private val plainText: String = delta.ops.joinToString(separator = "") { op -> op.insert as? String ?: "" }

    internal override fun toValue(): YXmlNodeValue = YXmlNodeValue.Text(plainText)

    override fun clone(): YXmlNode = YXmlSnapshotText(YTextDelta(delta.ops))

    override fun toJson(): String = plainText

    override fun toJSON(): String = toString()

    override fun toString(): String = renderXmlTextDelta(delta)

    internal fun toDetachedType(): YXmlTextType = YXmlTextType().also { text ->
        text.applyDelta(YTextDelta(delta.ops))
    }
}

sealed class YXmlSharedType protected constructor(
    doc: YDoc,
    name: String,
    kind: RootKind,
) : AbstractYType(doc, name, kind)

class YXmlElementType internal constructor(
    doc: YDoc,
    name: String,
    val nodeName: String = name,
    kind: RootKind = RootKind.XmlElement,
) : YXmlSharedType(doc, name, kind), Iterable<Any?> {
    constructor(nodeName: String = "UNDEFINED") : this(YDoc(), "", nodeName) {
        markDetached()
    }

    internal constructor(
        doc: YDoc,
        nodeName: String,
        kind: RootKind,
    ) : this(doc, nodeName, nodeName, kind)

    init {
        require(kind == RootKind.XmlElement || kind == RootKind.XmlHook) { "kind must be an XML element type ref" }
        require(nodeName.isNotBlank()) { "XML element name must not be blank" }
    }

    val length: Int
        get() {
            if (warnIfPreliminary()) return preliminaryList.size
            return xmlItems().size
        }

    val attrSize: Int get() = getAttrs().size

    val firstChild: Any? get() = get(0)

    val nextSibling: Any? get() = xmlSibling(this, offset = 1)

    val prevSibling: Any? get() = xmlSibling(this, offset = -1)

    fun setAttr(key: String, value: Any?): Any? {
        if (isPreliminary) {
            preliminaryMap[key] = value
            return value
        }
        return doc.setTypeAttribute(name, key, value)
    }

    fun setAttribute(key: String, value: Any?): Any? = setAttr(key, value)

    fun setAttrs(values: Map<String, Any?>): YXmlElementType {
        if (isPreliminary) {
            values.forEach { (key, value) -> preliminaryMap[key] = value }
            return this
        }
        doc.preflightNestedValue(values.values.toList())
        doc.transact {
            values.toSortedMap().forEach { (key, value) -> setAttr(key, value) }
        }
        return this
    }

    fun getAttr(key: String): Any? {
        if (warnIfPreliminary()) return null
        return doc.typeAttribute(name, key)
    }

    fun getAttr(key: String, snapshot: Snapshot): Any? =
        doc.mapValueAtSnapshot(this, key, snapshot)?.let(doc::valueToAny)

    fun getAttribute(key: String): Any? = getAttr(key)

    fun getAttribute(key: String, snapshot: Snapshot): Any? = getAttr(key, snapshot)

    fun getAttrs(): Map<String, Any?> {
        if (warnIfPreliminary()) return emptyMap()
        return doc.typeAttributes(name)
    }

    fun getAttributes(): Map<String, Any?> = getAttrs()

    fun getAttributes(snapshot: Snapshot): Map<String, Any?> = getAttrs(snapshot)

    fun getAttrs(snapshot: Snapshot): Map<String, Any?> =
        doc.mapAtSnapshot(this, snapshot).mapValues { (_, value) -> doc.valueToAny(value) }

    fun attrKeys(): Set<String> = getAttrs().keys

    fun attrValues(): Collection<Any?> = getAttrs().values

    fun attrEntries(): Set<Map.Entry<String, Any?>> = getAttrs().entries

    fun <T> mapAttrs(transform: (value: Any?, key: String) -> T): List<T> {
        return getAttrs().map { (key, value) -> transform(value, key) }
    }

    fun <T> mapAttrs(transform: (value: Any?, key: String, type: YXmlElementType) -> T): List<T> {
        return getAttrs().map { (key, value) -> transform(value, key, this) }
    }

    fun forEachAttr(action: (value: Any?, key: String) -> Unit) {
        getAttrs().forEach { (key, value) -> action(value, key) }
    }

    fun forEachAttr(action: (value: Any?, key: String, type: YXmlElementType) -> Unit) {
        getAttrs().forEach { (key, value) -> action(value, key, this) }
    }

    fun hasAttr(key: String): Boolean {
        if (warnIfPreliminary()) return false
        return doc.hasTypeAttribute(name, key)
    }

    fun hasAttr(key: String, snapshot: Snapshot): Boolean =
        doc.mapValueAtSnapshot(this, key, snapshot) != null

    fun hasAttribute(key: String): Boolean = hasAttr(key)

    fun hasAttribute(key: String, snapshot: Snapshot): Boolean = hasAttr(key, snapshot)

    fun deleteAttr(key: String) {
        if (isPreliminary) {
            preliminaryMap.remove(key)
            return
        }
        doc.deleteTypeAttribute(name, key)
    }

    fun deleteAttribute(key: String) {
        deleteAttr(key)
    }

    fun removeAttribute(key: String) {
        deleteAttr(key)
    }

    fun clearAttrs() {
        if (isPreliminary) {
            preliminaryMap.clear()
            return
        }
        doc.transact {
            getAttrs().keys.forEach(::deleteAttr)
        }
    }

    fun insert(index: Int, nodes: List<YXmlNode>) {
        val insertionValues = nodes.map(YXmlNode::toRichSnapshotInsertionValue)
        if (isPreliminary) {
            if (nodes.isEmpty()) return
            preliminaryList.addAll(index.coerceIn(0, preliminaryList.size), insertionValues)
            return
        }
        val start = index.coerceAtLeast(0)
        if (nodes.isEmpty()) {
            doc.transact {
                require(start <= length) { "insert index is out of bounds" }
            }
            return
        }
        require(start <= length) { "insert index is out of bounds" }
        if (insertionValues.any { child -> child is AbstractYType }) {
            doc.transact {
                var insertionIndex = start
                insertionValues.forEach { child ->
                    when (child) {
                        is AbstractYType -> insertType(insertionIndex, child)
                        is YXmlNode -> insert(insertionIndex, listOf(child))
                        else -> error("unsupported XML snapshot child: ${child::class.qualifiedName}")
                    }
                    insertionIndex++
                }
            }
            return
        }
        doc.transact {
            val anchors = doc.insertionAnchors(name, kind, start)
            var origin = anchors.first
            val rightOrigin = anchors.second
            nodes.forEach { node ->
                val item = StoreItem(
                    id = doc.nextId(),
                    origin = origin,
                    rightOrigin = rightOrigin,
                    parent = name,
                    parentSub = null,
                    content = ItemContent.XmlNode(node.toValue(), kind),
                )
                doc.integrateLocal(item)
                origin = item.id
            }
        }
    }

    fun insertType(index: Int, type: AbstractYType) {
        insertTypes(index, listOf(type))
    }

    fun insertTypes(index: Int, types: List<AbstractYType>) {
        if (isPreliminary) {
            if (types.isEmpty()) return
            require(types.none { type -> type === this }) { "shared type cannot contain itself" }
            preliminaryList.addAll(index.coerceIn(0, preliminaryList.size), types)
            return
        }
        val start = index.coerceAtLeast(0)
        if (types.isEmpty()) {
            doc.transact {
                require(start <= length) { "insert index is out of bounds" }
            }
            return
        }
        require(start <= length) { "insert index is out of bounds" }
        doc.preflightNestedValue(types)
        doc.transact {
            val anchors = doc.insertionAnchors(name, kind, start)
            var origin = anchors.first
            val rightOrigin = anchors.second
            types.forEach { type ->
                val ref = doc.storeValue(type, parent = name) as YValue.TypeRef
                val local = doc.valueToAny(ref) as AbstractYType
                val item = StoreItem(
                    id = doc.nextId(),
                    origin = origin,
                    rightOrigin = rightOrigin,
                    parent = name,
                    parentSub = null,
                    content = ItemContent.XmlType(ref, local.xmlNodeNameOrEmpty(), kind),
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

    fun push(vararg types: AbstractYType) {
        insertTypes(length, types.toList())
    }

    fun pushTypes(types: List<AbstractYType>) {
        insertTypes(length, types)
    }

    fun unshift(nodes: List<YXmlNode>) {
        insert(0, nodes)
    }

    fun unshift(vararg nodes: YXmlNode) {
        unshift(nodes.toList())
    }

    fun unshift(vararg types: AbstractYType) {
        insertTypes(0, types.toList())
    }

    fun unshiftTypes(types: List<AbstractYType>) {
        insertTypes(0, types)
    }

    fun delete(index: Int, length: Int = 1) {
        if (isPreliminary) {
            if (length <= 0 || preliminaryList.isEmpty()) return
            val start = index.coerceIn(0, preliminaryList.size)
            val end = start + minOf(length, preliminaryList.size - start)
            preliminaryList.subList(start, end).clear()
            return
        }
        doc.deleteVisible(name, index, length)
    }

    fun clear() {
        delete(0, length)
    }

    fun get(index: Int): Any? {
        if (index < 0) return null
        return toArray().getOrNull(index)
    }

    fun getType(index: Int): AbstractYType? {
        return get(index) as? AbstractYType
    }

    fun slice(start: Int = 0, end: Int = length): List<Any?> {
        val values = toArray()
        val normalizedStart = normalizeXmlSliceIndex(start, values.size)
        val normalizedEnd = normalizeXmlSliceIndex(end, values.size)
        if (normalizedEnd <= normalizedStart) return emptyList()
        return values.subList(normalizedStart, normalizedEnd)
    }

    fun toList(): List<Any?> = toArray()

    fun toArray(): List<Any?> {
        if (warnIfPreliminary()) return emptyList()
        return xmlItems().map { it.content.toXmlChild(doc) }
    }

    fun toDelta(): List<YArrayDeltaOp> = xmlChildrenToDelta(toArray())

    fun createTreeWalker(filter: (Any?) -> Boolean = { true }): Sequence<Any?> =
        xmlTreeWalker(toArray(), filter)

    fun querySelector(query: String): Any? = xmlQuerySelectorAll(this, query).firstOrNull()

    fun querySelectorAll(query: String): List<Any?> = xmlQuerySelectorAll(this, query)

    fun insertAfter(ref: Any?, content: List<Any?>) {
        xmlInsertAfter(this, ref, content)
    }

    fun toDeltaDeep(renderer: AbstractRenderer = activeRenderer): YXmlElementDeepDelta =
        renderXmlElementDeepDelta(this, DeepDeltaRenderOptions(renderer = renderer))

    fun applyDeltaDeep(delta: YXmlElementDeepDelta, origin: Any? = null) {
        require(delta.nodeName == nodeName) { "XML deep-delta nodeName '${delta.nodeName}' does not match '$nodeName'" }
        doc.transact(origin = origin) {
            clear()
            clearAttrs()
            setAttrs(delta.attrs.fromDeepDeltaValues(doc))
            val children = delta.children.map { child ->
                child.toXmlChildFromDeepDeltaValue(doc)
            }
            if (children.isNotEmpty()) {
                applyDelta(listOf(YArrayDeltaOp(insert = children)))
            }
        }
    }

    fun applyDelta(delta: List<YArrayDeltaOp>, origin: Any? = null) {
        if (!isPreliminary) doc.preflightSharedTypes(delta.map { op -> op.insert })
        doc.transact(origin = origin) {
            var index = 0
            delta.forEach { op ->
                when {
                    op.retain != null -> index += op.retain
                    op.delete != null -> delete(index, op.delete)
                    op.insert != null -> index += insertDeltaValues(index, op.insert, op.attributes)
                }
            }
        }
    }

    fun <T> map(transform: (Any?) -> T): List<T> = toArray().map(transform)

    fun <T> map(transform: (node: Any?, index: Int) -> T): List<T> =
        toArray().mapIndexed { index, node -> transform(node, index) }

    fun <T> map(transform: (node: Any?, index: Int, type: YXmlElementType) -> T): List<T> =
        toArray().mapIndexed { index, node -> transform(node, index, this) }

    fun forEach(action: (Any?) -> Unit) {
        toArray().forEach(action)
    }

    fun forEach(action: (node: Any?, index: Int) -> Unit) {
        toArray().forEachIndexed { index, node -> action(node, index) }
    }

    fun forEach(action: (node: Any?, index: Int, type: YXmlElementType) -> Unit) {
        toArray().forEachIndexed { index, node -> action(node, index, this) }
    }

    fun forEachIndexed(action: (Int, Any?) -> Unit) {
        toArray().forEachIndexed(action)
    }

    fun clone(): YXmlElementType =
        YXmlElementType(nodeName).also { cloned ->
            cloneXmlChildrenDetached(cloned)
            cloned.setAttrs(getAttrs().mapValues { (_, value) -> value.cloneValueDetached() })
        }

    fun clone(targetDoc: YDoc): YXmlElementType =
        targetDoc.createXmlElementType(nodeName, kind).also { cloned ->
            cloneXmlChildrenInto(cloned, targetDoc)
            cloned.setAttrs(getAttrs().mapValues { (_, value) -> value.cloneValueInto(targetDoc) })
        }

    internal fun toNode(): YXmlElement =
        YXmlElement(nodeName).also { element ->
            element.setAttrs(getAttrs())
            element.push(toNodeList())
        }

    override fun toJson(): Map<String, Any?> = linkedMapOf(
        "nodeName" to nodeName,
        "attributes" to getAttrs(),
        "children" to xmlItems().map { item -> item.content.toXmlEventJson(doc) },
    )

    override fun toJSON(): String = toString()

    fun toString(forceTag: Boolean): String {
        val tagName = nodeName.lowercase()
        val attrs = xmlAttrsToString(getAttrs())
        val body = xmlItems().joinToString(separator = "") { item -> item.content.toXmlString(doc) }
        if (body.isEmpty()) return "<$tagName$attrs></$tagName>"
        return "<$tagName$attrs>$body</$tagName>"
    }

    override fun toString(): String = toString(forceTag = false)

    override fun iterator(): Iterator<Any?> = toArray().iterator()

    private fun xmlItems(): List<StoreItem> =
        doc.visibleSequence(name).filter { it.content.isXmlSequenceChild() && it.content.kind == kind }

    private fun toNodeList(): List<YXmlNode> = xmlItems().map { it.content.toXmlNode(doc) }

    private fun cloneXmlChildrenInto(target: YXmlElementType, targetDoc: YDoc) {
        xmlItems().forEach { item ->
            when (val content = item.content) {
                is ItemContent.XmlNode -> target.push(content.value.toNode())
                is ItemContent.XmlType -> {
                    val cloned = doc.typeFromXmlType(content).cloneValueInto(targetDoc) as AbstractYType
                    target.push(cloned)
                }
                else -> Unit
            }
        }
    }

    private fun cloneXmlChildrenDetached(target: YXmlElementType) {
        xmlItems().forEach { item ->
            when (val content = item.content) {
                is ItemContent.XmlNode -> target.insert(target.preliminaryList.size, listOf(content.value.toNode()))
                is ItemContent.XmlType -> {
                    val cloned = doc.typeFromXmlType(content).cloneValueDetached() as AbstractYType
                    target.insertType(target.preliminaryList.size, cloned)
                }
                else -> Unit
            }
        }
    }

    private fun insertDeltaValues(
        index: Int,
        values: List<Any?>,
        attributes: Map<String, Any?> = emptyMap(),
    ): Int {
        var insertionIndex = index
        val textAttributes = xmlTextFormatAttributes(attributes)
        values.forEach { value ->
            when (value) {
                is AbstractYType -> insertType(insertionIndex, value)
                else -> insert(insertionIndex, listOf(xmlNodeFromDeltaValue(value, textAttributes)))
            }
            insertionIndex++
        }
        return values.size
    }
}

class YXmlTextType internal constructor(
    doc: YDoc,
    name: String = "",
) : YText(doc, name, RootKind.XmlText) {
    constructor(text: String = "") : this(YDoc(), "") {
        markDetached()
        if (text.isNotEmpty()) insert(0, text)
    }

    val nextSibling: Any? get() = xmlSibling(this, offset = 1)

    val prevSibling: Any? get() = xmlSibling(this, offset = -1)

    override fun clone(): YXmlTextType = YXmlTextType()
        .also { cloned ->
            cloned.applyDelta(toDelta().cloneDetached())
            cloned.setAttrs(getAttrs().mapValues { (_, value) -> value.cloneValueDetached() })
        }

    override fun clone(targetDoc: YDoc): YXmlTextType = targetDoc.createXmlTextType()
        .also { cloned ->
            cloned.applyDelta(toDelta().cloneInto(targetDoc))
            cloned.setAttrs(getAttrs().mapValues { (_, value) -> value.cloneValueInto(targetDoc) })
        }

    override fun toString(): String = renderXmlTextDelta(toDelta())
}

/**
 * A map-backed XML hook, matching `Y.XmlHook` in upstream Yjs.
 *
 * Hooks are XML children on the wire (type ref 5), but their content model is a Y.Map rather
 * than an XML child sequence. Rendering an unbound hook therefore uses JavaScript's ordinary
 * object string representation, while [toJSON] exposes its map properties.
 */
class YXmlHook internal constructor(
    doc: YDoc,
    name: String,
    val hookName: String,
) : YMap(doc, name, RootKind.XmlHook) {
    constructor(hookName: String) : this(YDoc(), "", hookName) {
        markDetached()
    }

    init {
        require(hookName.isNotBlank()) { "XML hook name must not be blank" }
    }

    override fun clone(): YXmlHook = YXmlHook(hookName).also { cloned ->
        cloned.setAttrs(toMap().mapValues { (_, value) -> value.cloneValueDetached() })
    }

    override fun clone(targetDoc: YDoc): YXmlHook = targetDoc.createXmlHook(hookName).also { cloned ->
        cloned.setAttrs(toMap().mapValues { (_, value) -> value.cloneValueInto(targetDoc) })
    }

    override fun toString(): String = "[object Object]"
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

    val firstChild: YXmlNode? get() = get(0)

    fun setAttr(name: String, value: Any?) {
        require(name.isNotBlank()) { "attribute name must not be blank" }
        attributes[name] = YValue.from(value)
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

    fun getAttributes(): Map<String, Any?> = getAttrs()

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
        require(index <= children.size - length) { "delete range is out of bounds" }
        children.subList(index, index + length).clear()
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

    fun toDelta(): List<YArrayDeltaOp> = xmlChildrenToDelta(toList())

    fun createTreeWalker(filter: (YXmlNode) -> Boolean = { true }): Sequence<YXmlNode> =
        xmlTreeWalker(toList()) { node -> node is YXmlNode && filter(node) }
            .filterIsInstance<YXmlNode>()

    fun toDeltaDeep(): YXmlElementDeepDelta = YXmlElementDeepDelta(
        nodeName = nodeName,
        attrs = getAttrs().mapValues { (_, value) -> value.toDeepDeltaValue() },
        children = toList().map { it.toXmlElementDeepDeltaChildValue() },
    )

    fun applyDelta(delta: List<YArrayDeltaOp>) {
        var index = 0
        delta.forEach { op ->
            when {
                op.retain != null -> index += op.retain
                op.delete != null -> delete(index, op.delete)
                op.insert != null -> {
                    val textAttributes = xmlTextFormatAttributes(op.attributes)
                    val nodes = op.insert.map { value -> xmlNodeFromDeltaValue(value, textAttributes) }
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

    fun <T> map(transform: (node: YXmlNode, index: Int, type: YXmlElement) -> T): List<T> =
        toList().mapIndexed { index, node -> transform(node, index, this) }

    fun forEach(action: (YXmlNode) -> Unit) {
        toList().forEach(action)
    }

    fun forEach(action: (node: YXmlNode, index: Int) -> Unit) {
        toList().forEachIndexed { index, node -> action(node, index) }
    }

    fun forEach(action: (node: YXmlNode, index: Int, type: YXmlElement) -> Unit) {
        toList().forEachIndexed { index, node -> action(node, index, this) }
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
        val tagName = nodeName.lowercase()
        val attrs = xmlAttrsToString(getAttrs())
        val body = children.joinToString(separator = "") { it.toString() }
        if (body.isEmpty()) {
            return "<$tagName$attrs></$tagName>"
        }
        return "<$tagName$attrs>$body</$tagName>"
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
    YXmlSharedType(doc, name, RootKind.XmlFragment),
    Iterable<Any?> {
    constructor() : this(YDoc(), "") {
        markDetached()
    }

    constructor(nodes: Collection<YXmlNode>) : this() {
        push(nodes.toList())
    }

    constructor(vararg nodes: YXmlNode) : this(nodes.toList())

    val length: Int
        get() {
            if (warnIfPreliminary()) return preliminaryList.size
            return xmlItems().size
        }

    val attrSize: Int get() = getAttrs().size

    val firstChild: Any? get() = get(0)

    fun insert(index: Int, nodes: List<YXmlNode>) {
        val insertionValues = nodes.map(YXmlNode::toRichSnapshotInsertionValue)
        if (isPreliminary) {
            if (nodes.isEmpty()) return
            preliminaryList.addAll(index.coerceIn(0, preliminaryList.size), insertionValues)
            return
        }
        val start = index.coerceAtLeast(0)
        if (nodes.isEmpty()) {
            doc.transact {
                require(start <= length) { "insert index is out of bounds" }
            }
            return
        }
        require(start <= length) { "insert index is out of bounds" }
        if (insertionValues.any { child -> child is AbstractYType }) {
            doc.transact {
                var insertionIndex = start
                insertionValues.forEach { child ->
                    when (child) {
                        is AbstractYType -> insertType(insertionIndex, child)
                        is YXmlNode -> insert(insertionIndex, listOf(child))
                        else -> error("unsupported XML snapshot child: ${child::class.qualifiedName}")
                    }
                    insertionIndex++
                }
            }
            return
        }
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

    fun insertType(index: Int, type: AbstractYType) {
        insertTypes(index, listOf(type))
    }

    fun insertTypes(index: Int, types: List<AbstractYType>) {
        if (isPreliminary) {
            if (types.isEmpty()) return
            require(types.none { type -> type === this }) { "shared type cannot contain itself" }
            preliminaryList.addAll(index.coerceIn(0, preliminaryList.size), types)
            return
        }
        val start = index.coerceAtLeast(0)
        if (types.isEmpty()) {
            doc.transact {
                require(start <= length) { "insert index is out of bounds" }
            }
            return
        }
        require(start <= length) { "insert index is out of bounds" }
        doc.preflightNestedValue(types)
        doc.transact {
            val anchors = doc.insertionAnchors(name, RootKind.XmlFragment, start)
            var origin = anchors.first
            val rightOrigin = anchors.second
            types.forEach { type ->
                val ref = doc.storeValue(type, parent = name) as YValue.TypeRef
                val local = doc.valueToAny(ref) as AbstractYType
                val item = StoreItem(
                    id = doc.nextId(),
                    origin = origin,
                    rightOrigin = rightOrigin,
                    parent = name,
                    parentSub = null,
                    content = ItemContent.XmlType(ref, local.xmlNodeNameOrEmpty(), RootKind.XmlFragment),
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

    fun push(vararg types: AbstractYType) {
        insertTypes(length, types.toList())
    }

    fun pushTypes(types: List<AbstractYType>) {
        insertTypes(length, types)
    }

    fun unshift(nodes: List<YXmlNode>) {
        insert(0, nodes)
    }

    fun unshift(vararg nodes: YXmlNode) {
        unshift(nodes.toList())
    }

    fun unshift(vararg types: AbstractYType) {
        insertTypes(0, types.toList())
    }

    fun unshiftTypes(types: List<AbstractYType>) {
        insertTypes(0, types)
    }

    fun delete(index: Int, length: Int = 1) {
        if (isPreliminary) {
            if (length <= 0 || preliminaryList.isEmpty()) return
            val start = index.coerceIn(0, preliminaryList.size)
            val end = start + minOf(length, preliminaryList.size - start)
            preliminaryList.subList(start, end).clear()
            return
        }
        doc.deleteVisible(name, index, length)
    }

    fun setAttr(key: String, value: Any?): Any? {
        if (isPreliminary) {
            preliminaryMap[key] = value
            return value
        }
        return doc.setTypeAttribute(name, key, value)
    }

    fun setAttribute(key: String, value: Any?): Any? = setAttr(key, value)

    fun setAttrs(values: Map<String, Any?>): YXmlFragment {
        if (isPreliminary) {
            values.forEach { (key, value) -> preliminaryMap[key] = value }
            return this
        }
        doc.preflightNestedValue(values.values.toList())
        doc.transact {
            values.toSortedMap().forEach { (key, value) -> setAttr(key, value) }
        }
        return this
    }

    fun getAttr(key: String): Any? {
        if (warnIfPreliminary()) return null
        return doc.typeAttribute(name, key)
    }

    fun getAttr(key: String, snapshot: Snapshot): Any? =
        doc.mapValueAtSnapshot(this, key, snapshot)?.let(doc::valueToAny)

    fun getAttribute(key: String): Any? = getAttr(key)

    fun getAttribute(key: String, snapshot: Snapshot): Any? = getAttr(key, snapshot)

    fun getAttrs(): Map<String, Any?> {
        if (warnIfPreliminary()) return emptyMap()
        return doc.typeAttributes(name)
    }

    fun getAttributes(): Map<String, Any?> = getAttrs()

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

    fun hasAttr(key: String): Boolean {
        if (warnIfPreliminary()) return false
        return doc.hasTypeAttribute(name, key)
    }

    fun hasAttr(key: String, snapshot: Snapshot): Boolean =
        doc.mapValueAtSnapshot(this, key, snapshot) != null

    fun hasAttribute(key: String): Boolean = hasAttr(key)

    fun hasAttribute(key: String, snapshot: Snapshot): Boolean = hasAttr(key, snapshot)

    fun deleteAttr(key: String) {
        if (isPreliminary) {
            preliminaryMap.remove(key)
            return
        }
        doc.deleteTypeAttribute(name, key)
    }

    fun deleteAttribute(key: String) {
        deleteAttr(key)
    }

    fun removeAttribute(key: String) {
        deleteAttr(key)
    }

    fun clearAttrs() {
        if (isPreliminary) {
            preliminaryMap.clear()
            return
        }
        doc.transact {
            getAttrs().keys.forEach(::deleteAttr)
        }
    }

    fun get(index: Int): Any? {
        if (index < 0) return null
        return toArray().getOrNull(index)
    }

    fun getType(index: Int): AbstractYType? {
        return get(index) as? AbstractYType
    }

    fun slice(start: Int = 0, end: Int = length): List<Any?> {
        val values = toArray()
        val normalizedStart = normalizeXmlSliceIndex(start, values.size)
        val normalizedEnd = normalizeXmlSliceIndex(end, values.size)
        if (normalizedEnd <= normalizedStart) return emptyList()
        return values.subList(normalizedStart, normalizedEnd)
    }

    fun toList(): List<Any?> = toArray()

    fun toArray(): List<Any?> {
        if (warnIfPreliminary()) return emptyList()
        return xmlItems().map { it.content.toXmlChild(doc) }
    }

    fun toDelta(): List<YArrayDeltaOp> = xmlChildrenToDelta(toArray())

    fun createTreeWalker(filter: (Any?) -> Boolean = { true }): Sequence<Any?> =
        xmlTreeWalker(toArray(), filter)

    fun querySelector(query: String): Any? = xmlQuerySelectorAll(this, query).firstOrNull()

    fun querySelectorAll(query: String): List<Any?> = xmlQuerySelectorAll(this, query)

    fun insertAfter(ref: Any?, content: List<Any?>) {
        xmlInsertAfter(this, ref, content)
    }

    fun toDeltaDeep(renderer: AbstractRenderer = activeRenderer): YXmlFragmentDeepDelta =
        renderXmlFragmentDeepDelta(this, DeepDeltaRenderOptions(renderer = renderer))

    fun applyDelta(
        delta: List<YArrayDeltaOp>,
        origin: Any? = null,
        renderer: AbstractRenderer = activeRenderer,
    ) {
        if (!isPreliminary) doc.preflightSharedTypes(delta.map { op -> op.insert })
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
                        val index = renderedSequenceIndexToVisibleIndex(this, renderedIndex, renderer)
                        renderedIndex += insertDeltaValues(index, op.insert, op.attributes)
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

    fun <T> map(transform: (Any?) -> T): List<T> = toArray().map(transform)

    fun <T> map(transform: (node: Any?, index: Int) -> T): List<T> =
        toArray().mapIndexed { index, node -> transform(node, index) }

    fun <T> map(transform: (node: Any?, index: Int, type: YXmlFragment) -> T): List<T> =
        toArray().mapIndexed { index, node -> transform(node, index, this) }

    fun forEach(action: (Any?) -> Unit) {
        toArray().forEach(action)
    }

    fun forEach(action: (node: Any?, index: Int) -> Unit) {
        toArray().forEachIndexed { index, node -> action(node, index) }
    }

    fun forEach(action: (node: Any?, index: Int, type: YXmlFragment) -> Unit) {
        toArray().forEachIndexed { index, node -> action(node, index, this) }
    }

    fun forEachIndexed(action: (Int, Any?) -> Unit) {
        toArray().forEachIndexed(action)
    }

    override fun iterator(): Iterator<Any?> = toArray().iterator()

    fun clone(): YXmlFragment {
        return YXmlFragment().also { cloned ->
            xmlItems().forEach { item ->
                when (val content = item.content) {
                    is ItemContent.XmlNode -> cloned.insert(
                        cloned.preliminaryList.size,
                        listOf(content.value.toNode()),
                    )
                    is ItemContent.XmlType -> {
                        val clonedChild = doc.typeFromXmlType(content).cloneValueDetached() as AbstractYType
                        cloned.insertType(cloned.preliminaryList.size, clonedChild)
                    }
                    else -> Unit
                }
            }
            cloned.setAttrs(getAttrs().mapValues { (_, value) -> value.cloneValueDetached() })
        }
    }

    fun clone(targetDoc: YDoc): YXmlFragment {
        return targetDoc.createXmlFragment().also { cloned ->
            xmlItems().forEach { item ->
                when (val content = item.content) {
                    is ItemContent.XmlNode -> cloned.push(content.value.toNode())
                    is ItemContent.XmlType -> {
                        val clonedChild = doc.typeFromXmlType(content).cloneValueInto(targetDoc) as AbstractYType
                        cloned.push(clonedChild)
                    }
                    else -> Unit
                }
            }
            cloned.setAttrs(getAttrs().mapValues { (_, value) -> value.cloneValueInto(targetDoc) })
        }
    }

    override fun toJson(): List<Any?> = xmlItems().map { item -> item.content.toXmlEventJson(doc) }

    override fun toJSON(): String = toString()

    fun toString(forceTag: Boolean): String =
        renderXmlFragmentString(name, getAttrs(), toArray(), forceTag = forceTag)

    override fun toString(): String = toString(forceTag = false)

    companion object {
        fun from(delta: List<YArrayDeltaOp>, doc: YDoc = YDoc(), name: String = ""): YXmlFragment {
            return doc.getXmlFragment(name).also { it.applyDelta(delta) }
        }

        fun from(delta: YXmlFragmentDeepDelta, doc: YDoc = YDoc(), name: String = ""): YXmlFragment {
            return doc.getXmlFragment(name).also { it.applyDeltaDeep(delta) }
        }
    }

    private fun xmlItems(): List<StoreItem> =
        doc.visibleSequence(name).filter { it.content.isXmlSequenceChild() && it.content.kind == RootKind.XmlFragment }

    private fun toNodeList(): List<YXmlNode> = xmlItems().map { it.content.toXmlNode(doc) }

    private fun insertDeltaValues(
        index: Int,
        values: List<Any?>,
        attributes: Map<String, Any?> = emptyMap(),
    ): Int {
        var insertionIndex = index
        val textAttributes = xmlTextFormatAttributes(attributes)
        values.forEach { value ->
            when (value) {
                is AbstractYType -> insertType(insertionIndex, value)
                else -> insert(insertionIndex, listOf(xmlNodeFromDeltaValue(value, textAttributes)))
            }
            insertionIndex++
        }
        return values.size
    }
}

internal sealed class YXmlNodeValue {
    data class Text(
        val text: String,
        val attributes: Map<String, YValue> = emptyMap(),
    ) : YXmlNodeValue()
    data class Element(
        val nodeName: String,
        val attributes: Map<String, YValue> = emptyMap(),
        val children: List<YXmlNodeValue> = emptyList(),
    ) : YXmlNodeValue()
}

internal fun writeXmlNodeValue(encoder: BinaryEncoder, value: YXmlNodeValue) {
    when (value) {
        is YXmlNodeValue.Text -> {
            encoder.writeByte(if (value.attributes.isEmpty()) 0 else 2)
            encoder.writeString(value.text)
            if (value.attributes.isNotEmpty()) {
                encoder.writeVarUInt(value.attributes.size.toLong())
                value.attributes.toSortedMap().forEach { (name, attrValue) ->
                    encoder.writeString(name)
                    writeYValue(encoder, attrValue)
                }
            }
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
            repeat(decoder.readVarUInt().toDecodedCount()) {
                put(decoder.readString(), readYValue(decoder))
            }
        }.toSortedMap()
        val children = List(decoder.readVarUInt().toDecodedCount()) { readXmlNodeValue(decoder) }
        YXmlNodeValue.Element(nodeName, attributes, children)
    }
    2 -> {
        val text = decoder.readString()
        val attributes = buildMap {
            repeat(decoder.readVarUInt().toDecodedCount()) {
                put(decoder.readString(), readYValue(decoder))
            }
        }.toSortedMap()
        YXmlNodeValue.Text(text, attributes)
    }
    else -> error("unknown XML node tag: $tag")
}

internal fun YXmlNodeValue.toEventJson(): Any? = toNode().toJson()

internal fun YXmlNodeValue.toNode(): YXmlNode = when (this) {
    is YXmlNodeValue.Text -> YXmlText(text, textAttributesToPublic(attributes))
    is YXmlNodeValue.Element -> YXmlElement(nodeName).also { element ->
        attributes.forEach { (name, value) -> element.setAttr(name, value) }
        element.push(children.map { it.toNode() })
    }
}

internal fun AbstractYType.xmlNodeNameOrEmpty(): String = when (this) {
    is YXmlElementType -> nodeName
    is YXmlHook -> hookName
    else -> ""
}

internal fun ItemContent.isXmlSequenceChild(): Boolean =
    this is ItemContent.XmlNode || this is ItemContent.XmlType

internal fun ItemContent.toXmlEventJson(doc: YDoc): Any? = when (this) {
    is ItemContent.XmlNode -> value.toNode().toJson()
    is ItemContent.XmlType -> doc.typeFromXmlType(this).toJson()
    else -> error("item content is not an XML child: ${this::class.simpleName}")
}

internal fun ItemContent.toXmlString(doc: YDoc): String = when (this) {
    is ItemContent.XmlNode -> value.toNode().toString()
    is ItemContent.XmlType -> doc.typeFromXmlType(this).toString()
    else -> error("item content is not an XML child: ${this::class.simpleName}")
}

internal fun ItemContent.toXmlChild(doc: YDoc): Any? = when (this) {
    is ItemContent.XmlNode -> value.toNode()
    is ItemContent.XmlType -> doc.typeFromXmlType(this)
    else -> error("item content is not an XML child: ${this::class.simpleName}")
}

internal fun ItemContent.toXmlNode(doc: YDoc): YXmlNode = when (this) {
    is ItemContent.XmlNode -> value.toNode()
    is ItemContent.XmlType -> when (val type = doc.typeFromXmlType(this)) {
        is YText -> YXmlText(type.toString())
        is YXmlElementType -> type.toNode()
        is YXmlTextType -> YXmlText(type.toString())
        is YXmlHook -> YXmlText(type.toString())
        is YXmlFragment -> YXmlText(type.toString())
        else -> YXmlText("[object Object]")
    }
    else -> error("item content is not an XML fragment child: ${this::class.simpleName}")
}

private fun YXmlNode.copyNode(): YXmlNode = clone()

/**
 * Rich snapshot text cannot be represented by the single-run static XML text value. Promote
 * only the snapshot subtree that needs it to detached live XML types before insertion.
 */
private fun YXmlNode.toRichSnapshotInsertionValue(): Any = when (this) {
    is YXmlSnapshotText -> toDetachedType()
    is YXmlText -> this
    is YXmlElement -> {
        val convertedChildren = toList().map(YXmlNode::toRichSnapshotInsertionValue)
        if (convertedChildren.none { child -> child is AbstractYType }) {
            this
        } else {
            YXmlElementType(nodeName).also { element ->
                element.setAttrs(getAttrs())
                convertedChildren.forEach { child ->
                    when (child) {
                        is AbstractYType -> element.push(child)
                        is YXmlNode -> element.push(child)
                        else -> error("unsupported XML snapshot child: ${child::class.qualifiedName}")
                    }
                }
            }
        }
    }
}

private fun xmlSibling(type: AbstractYType, offset: Int): Any? {
    val parent = type.parent ?: return null
    val children = when (parent) {
        is YXmlElementType -> parent.toArray()
        is YXmlFragment -> parent.toArray()
        else -> return null
    }
    val index = children.indexOfFirst { child -> child === type }
    if (index < 0) return null
    return children.getOrNull(index + offset)
}

private fun xmlTreeWalker(nodes: Iterable<Any?>, filter: (Any?) -> Boolean): Sequence<Any?> = sequence {
    nodes.forEach { node ->
        if (filter(node)) yield(node)
        when (node) {
            is YXmlElementType -> yieldAll(xmlTreeWalker(node.toArray(), filter))
            is YXmlFragment -> yieldAll(xmlTreeWalker(node.toArray(), filter))
            is YXmlElement -> yieldAll(xmlTreeWalker(node.toList(), filter))
        }
    }
}

private fun xmlQuerySelectorAll(parent: YXmlSharedType, query: String): List<Any?> {
    val children = when (parent) {
        is YXmlElementType -> if (parent.isPreliminary) parent.preliminaryList.toList() else parent.toArray()
        is YXmlFragment -> if (parent.isPreliminary) parent.preliminaryList.toList() else parent.toArray()
    }
    return xmlTreeWalker(children) { node ->
        when (node) {
            is YXmlElementType -> node.nodeName.equals(query, ignoreCase = true)
            is YXmlElement -> node.nodeName.equals(query, ignoreCase = true)
            else -> false
        }
    }.toList()
}

private fun xmlInsertAfter(parent: YXmlSharedType, ref: Any?, content: List<Any?>) {
    require(content.all { value -> value is AbstractYType || value is YXmlNode }) {
        "XML content must contain shared XML types or detached XML nodes"
    }
    require(content.none { value -> value === parent }) { "shared type cannot contain itself" }
    if (content.isEmpty()) return

    val insertionIndex = if (parent.isPreliminary) {
        if (ref == null) {
            0
        } else {
            val index = parent.preliminaryList.indexOfFirst { child -> child === ref }
            require(index >= 0) { "reference item not found" }
            index + 1
        }
    } else {
        when (ref) {
            null -> 0
            is AbstractYType -> {
                require(ref.doc === parent.doc) { "reference type must belong to the same document" }
                val id = parent.doc.typeRefItemId(ref) ?: error("reference type is not integrated")
                parent.visibleXmlInsertionIndexAfter(id)
            }
            is ItemStruct -> parent.visibleXmlInsertionIndexAfter(ref.id)
            else -> error("reference must be null, an Item, or an integrated XML type")
        }
    }

    if (!parent.isPreliminary) {
        parent.doc.preflightNestedValue(content.filterIsInstance<AbstractYType>())
    }
    val insertContent = {
        var index = insertionIndex
        content.forEach { value ->
            when (parent) {
                is YXmlElementType -> when (value) {
                    is AbstractYType -> parent.insertType(index, value)
                    is YXmlNode -> parent.insert(index, listOf(value))
                }
                is YXmlFragment -> when (value) {
                    is AbstractYType -> parent.insertType(index, value)
                    is YXmlNode -> parent.insert(index, listOf(value))
                }
            }
            index++
        }
    }
    if (parent.isPreliminary) insertContent() else parent.doc.transact(block = insertContent)
}

private fun YXmlSharedType.visibleXmlInsertionIndexAfter(id: Id): Int {
    val items = doc.sequence(name).filter { item -> item.content.isXmlSequenceChild() && item.content.kind == kind }
    val referenceIndex = items.indexOfFirst { item -> item.id == id }
    require(referenceIndex >= 0) { "reference item not found" }
    return items.take(referenceIndex + 1).count { item -> !item.deleted && item.countable }
}

private fun normalizeXmlSliceIndex(index: Int, size: Int): Int {
    val normalized = if (index < 0) size + index else index
    return normalized.coerceIn(0, size)
}

internal fun xmlNodeFromDeltaValue(
    value: Any?,
    attributes: Map<String, Any?> = emptyMap(),
): YXmlNode = when (value) {
    is YXmlText -> YXmlText(value.toJson(), value.attributes + attributes)
    is YXmlNode -> value.clone()
    is String -> YXmlText(value, attributes)
    is Char -> YXmlText(value.toString(), attributes)
    is Map<*, *> -> xmlNodeFromJsonMap(value)
    else -> error("unsupported XML delta value: ${value?.let { it::class.qualifiedName } ?: "null"}")
}

internal fun xmlChildrenToDelta(values: List<Any?>): List<YArrayDeltaOp> {
    val delta = mutableListOf<YArrayDeltaOp>()
    values.forEach { value ->
        val attributes = (value as? YXmlText)?.attributes.orEmpty()
        val insertValue = if (value is YXmlText) value.toJson() else value
        val last = delta.lastOrNull()
        if (last?.insert != null && last.attributes == attributes) {
            delta[delta.lastIndex] = last.copy(insert = last.insert + insertValue)
        } else {
            delta.add(YArrayDeltaOp(insert = listOf(insertValue), attributes = attributes))
        }
    }
    return delta
}

private fun xmlTextFormatAttributes(attributes: Map<String, Any?>): Map<String, Any?> =
    attributes
        .filterKeys { key -> key !in xmlDeltaAttributionKeys }
        .filterValues { it != null }
        .toSortedMap()

private val xmlDeltaAttributionKeys = setOf("insert", "delete", "format")

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
    nodes: List<Any?>,
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
        " $name=\"${xmlAttrValueToString(value)}\""
    }

internal fun xmlAttrValueToString(value: Any?): String = when (value) {
    null -> "null"
    Lib0Undefined,
    YValue.Undefined -> "undefined"
    is String -> value
    is Char -> value.toString()
    is Boolean -> value.toString()
    is Byte -> value.toString()
    is Short -> value.toString()
    is Int -> value.toString()
    is Long -> value.toString()
    is java.math.BigInteger -> value.toString()
    is Float -> value.toDouble().toYjsNumberString()
    is Double -> value.toYjsNumberString()
    is ByteArray -> value.joinToString(separator = ",") { byte -> byte.toUByte().toString() }
    is List<*> -> value.joinToString(separator = ",", transform = ::xmlArrayEntryToString)
    is Array<*> -> value.joinToString(separator = ",", transform = ::xmlArrayEntryToString)
    is Map<*, *> -> "[object Object]"
    is YText -> value.toString()
    is YXmlElementType -> value.toString()
    is YXmlFragment -> value.toString()
    is YXmlNode -> value.toString()
    is AbstractYType,
    is YDoc -> "[object Object]"
    else -> value.toString()
}

private fun xmlArrayEntryToString(value: Any?): String = when (value) {
    null,
    Lib0Undefined,
    YValue.Undefined -> ""
    else -> xmlAttrValueToString(value)
}

private fun Double.toYjsNumberString(): String {
    if (isNaN()) return "NaN"
    if (this == Double.POSITIVE_INFINITY) return "Infinity"
    if (this == Double.NEGATIVE_INFINITY) return "-Infinity"
    if (this == 0.0) return "0"

    val magnitude = kotlin.math.abs(this)
    if (magnitude >= 1e-6 && magnitude < 1e21) {
        return java.math.BigDecimal.valueOf(this).stripTrailingZeros().toPlainString()
    }

    val value = java.lang.Double.toString(this)
        .lowercase()
        .replace(".0e", "e")
    val exponentIndex = value.indexOf('e')
    if (exponentIndex < 0 || value.getOrNull(exponentIndex + 1) in listOf('+', '-')) return value
    return value.substring(0, exponentIndex + 1) + "+" + value.substring(exponentIndex + 1)
}

private fun renderXmlText(text: String, attributes: Map<String, Any?>): String {
    val formats = attributes.toSortedMap().filterValues { it != null }
    if (formats.isEmpty()) return text
    val opening = formats.entries.joinToString(separator = "") { (name, value) ->
        "<$name${xmlFormatAttrsToString(value)}>"
    }
    val closing = formats.keys.toList().asReversed().joinToString(separator = "") { name -> "</$name>" }
    return opening + text + closing
}

internal fun renderXmlTextDelta(delta: YTextDelta): String =
    delta.ops.joinToString(separator = "") { op ->
        when (val insert = op.insert) {
            is String -> renderXmlText(insert, op.attributes)
            null -> ""
            else -> renderXmlText(xmlAttrValueToString(insert), op.attributes)
        }
    }

private fun xmlFormatAttrsToString(value: Any?): String = when (value) {
    is Map<*, *> -> value.entries
        .filter { (key, _) -> key is String }
        .sortedBy { (key, _) -> key as String }
        .joinToString(separator = "") { (key, attrValue) ->
            " $key=\"${xmlAttrValueToString(attrValue)}\""
        }
    is String -> value.mapIndexed { index, char ->
        " $index=\"${xmlAttrValueToString(char)}\""
    }.joinToString(separator = "")
    is List<*> -> value.mapIndexed { index, nested ->
        " $index=\"${xmlAttrValueToString(nested)}\""
    }.joinToString(separator = "")
    is Array<*> -> value.mapIndexed { index, nested ->
        " $index=\"${xmlAttrValueToString(nested)}\""
    }.joinToString(separator = "")
    is ByteArray -> value.mapIndexed { index, byte ->
        " $index=\"${byte.toUByte()}\""
    }.joinToString(separator = "")
    else -> ""
}
