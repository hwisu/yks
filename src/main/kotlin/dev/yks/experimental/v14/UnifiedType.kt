@file:OptIn(ExperimentalYjs14Api::class)

package dev.yks.experimental.v14

import dev.yks.AbstractYType
import dev.yks.ItemContent
import dev.yks.RootKind
import dev.yks.StoreItem
import dev.yks.YArray
import dev.yks.YDoc
import dev.yks.YMap
import dev.yks.YText
import dev.yks.YUnopenedRoot
import dev.yks.YValue
import dev.yks.YXmlElementType
import dev.yks.YXmlFragment
import dev.yks.YXmlHook
import dev.yks.toXmlListValue

/**
 * Opt-in surface for the unified Type/DeltaBuilder direction in @y/y 14 release candidates.
 *
 * This API is source- and binary-isolated from the stable `dev.yks` surface because the upstream
 * contract is still an RC. It intentionally adapts to the existing Yjs-compatible wire model
 * instead of changing that model.
 */
@RequiresOptIn(
    message = "The @y/y 14 Type/DeltaBuilder compatibility surface follows a release candidate and may change.",
    level = RequiresOptIn.Level.WARNING,
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.TYPEALIAS,
)
public annotation class ExperimentalYjs14Api

/** A value that can be carried by a v14-style delta without falling back to `Any?`. */
@ExperimentalYjs14Api
public sealed interface DeltaValue {
    /** A lib0/Yjs data value. Shared types and subdocuments use their dedicated variants below. */
    public data class Data(val value: YValue) : DeltaValue {
        init {
            requirePortableDataValue(value)
        }
    }

    /** A real shared type. It retains identity and is integrated by the target YDoc on insertion. */
    public data class SharedType(val value: AbstractYType) : DeltaValue

    /** A real subdocument. It retains identity and lifecycle semantics. */
    public data class Subdocument(val value: YDoc) : DeltaValue

    public companion object {
        public fun data(value: YValue): DeltaValue = Data(value)

        public fun text(value: String): DeltaValue = Data(YValue.StringValue(value))

        public fun integer(value: Long): DeltaValue = Data(YValue.LongNumber(value))

        public fun bool(value: Boolean): DeltaValue = Data(YValue.Bool(value))

        public fun shared(type: AbstractYType): DeltaValue = SharedType(type)

        public fun subdocument(doc: YDoc): DeltaValue = Subdocument(doc)
    }
}

/** Tri-state formatting instruction used by retain/modify operations. */
@ExperimentalYjs14Api
public sealed interface FormatChange {
    /** Leave formatting unchanged. */
    public data object Unchanged : FormatChange

    /** Clear every active format in the addressed range. */
    public data object Clear : FormatChange

    /** Set values and remove keys whose value is null. */
    public class Patch internal constructor(values: Map<String, YValue?>) : FormatChange {
        public val values: Map<String, YValue?> = values.toSortedMap()

        override fun equals(other: Any?): Boolean = other is Patch && values == other.values

        override fun hashCode(): Int = values.hashCode()

        override fun toString(): String = "Patch(values=$values)"
    }
}

/** A child-list operation in the pinned @y/y 14 delta vocabulary. */
@ExperimentalYjs14Api
public sealed interface ChildOp {
    public class InsertText internal constructor(text: String, formats: Map<String, YValue>) : ChildOp {
        public val text: String = text
        public val formats: Map<String, YValue> = formats.toSortedMap()

        override fun equals(other: Any?): Boolean =
            other is InsertText && text == other.text && formats == other.formats

        override fun hashCode(): Int = 31 * text.hashCode() + formats.hashCode()

        override fun toString(): String = "InsertText(text=$text, formats=$formats)"
    }

    public class InsertValues internal constructor(
        values: List<DeltaValue>,
        formats: Map<String, YValue>,
    ) : ChildOp {
        public val values: List<DeltaValue> = values.toList()
        public val formats: Map<String, YValue> = formats.toSortedMap()

        override fun equals(other: Any?): Boolean =
            other is InsertValues && values == other.values && formats == other.formats

        override fun hashCode(): Int = 31 * values.hashCode() + formats.hashCode()

        override fun toString(): String = "InsertValues(values=$values, formats=$formats)"
    }

    @ConsistentCopyVisibility
    public data class Retain internal constructor(
        val length: Int,
        val formats: FormatChange,
    ) : ChildOp

    @ConsistentCopyVisibility
    public data class Delete internal constructor(val length: Int) : ChildOp

    @ConsistentCopyVisibility
    public data class Modify internal constructor(
        val delta: Delta,
        val formats: FormatChange,
    ) : ChildOp
}

/** An attribute operation in the pinned @y/y 14 delta vocabulary. */
@ExperimentalYjs14Api
public sealed interface AttributeOp {
    @ConsistentCopyVisibility
    public data class Set internal constructor(val value: DeltaValue) : AttributeOp

    public data object Delete : AttributeOp

    @ConsistentCopyVisibility
    public data class Modify internal constructor(val delta: Delta) : AttributeOp
}

/** Immutable result of [DeltaBuilder.done]. */
@ExperimentalYjs14Api
public class Delta internal constructor(
    public val name: String?,
    attributes: Map<String, AttributeOp>,
    children: List<ChildOp>,
) {
    public val attributes: Map<String, AttributeOp> = attributes.toSortedMap()
    public val children: List<ChildOp> = children.toList()

    public val isEmpty: Boolean get() = attributes.isEmpty() && children.isEmpty()

    override fun equals(other: Any?): Boolean =
        other is Delta && name == other.name && attributes == other.attributes && children == other.children

    override fun hashCode(): Int = 31 * (31 * (name?.hashCode() ?: 0) + attributes.hashCode()) + children.hashCode()

    override fun toString(): String = "Delta(name=$name, attributes=$attributes, children=$children)"
}

/**
 * Typed, single-use builder for the interoperable Type delta subset.
 *
 * Adjacent equivalent operations are coalesced while building. This matches lib0's builder shape
 * and avoids allocating one CRDT operation per character/value when the delta is applied.
 */
@ExperimentalYjs14Api
public class DeltaBuilder(public val name: String? = null) {
    private val attributes = linkedMapOf<String, AttributeOp>()
    private val children = mutableListOf<ChildOp>()
    private var completed = false

    public fun insert(text: String, formats: Map<String, YValue> = emptyMap()): DeltaBuilder = apply {
        ensureMutable()
        validateFormats(formats)
        if (text.isEmpty()) return@apply
        val normalized = normalizeDataFormats(formats)
        val previous = children.lastOrNull() as? ChildOp.InsertText
        if (previous != null && previous.formats == normalized) {
            children[children.lastIndex] = ChildOp.InsertText(previous.text + text, normalized)
        } else {
            children += ChildOp.InsertText(text, normalized)
        }
    }

    public fun insertValues(
        values: List<DeltaValue>,
        formats: Map<String, YValue> = emptyMap(),
    ): DeltaBuilder = apply {
        ensureMutable()
        validateFormats(formats)
        if (values.isEmpty()) return@apply
        val copied = values.toList()
        val normalized = normalizeDataFormats(formats)
        val previous = children.lastOrNull() as? ChildOp.InsertValues
        if (previous != null && previous.formats == normalized) {
            children[children.lastIndex] = ChildOp.InsertValues(previous.values + copied, normalized)
        } else {
            children += ChildOp.InsertValues(copied, normalized)
        }
    }

    public fun insertValue(
        value: DeltaValue,
        formats: Map<String, YValue> = emptyMap(),
    ): DeltaBuilder = insertValues(listOf(value), formats)

    public fun insertType(
        type: AbstractYType,
        formats: Map<String, YValue> = emptyMap(),
    ): DeltaBuilder = insertValue(DeltaValue.SharedType(type), formats)

    public fun retain(length: Int): DeltaBuilder = appendRetain(length, FormatChange.Unchanged)

    public fun retain(length: Int, formats: Map<String, YValue?>): DeltaBuilder {
        validateFormatPatch(formats)
        return appendRetain(length, FormatChange.Patch(normalizeFormatPatch(formats)))
    }

    public fun retainClearingFormats(length: Int): DeltaBuilder = appendRetain(length, FormatChange.Clear)

    public fun delete(length: Int): DeltaBuilder = apply {
        ensureMutable()
        require(length >= 0) { "delete length must be non-negative" }
        if (length == 0) return@apply
        val previous = children.lastOrNull() as? ChildOp.Delete
        if (previous != null) {
            children[children.lastIndex] = ChildOp.Delete(Math.addExact(previous.length, length))
        } else {
            children += ChildOp.Delete(length)
        }
    }

    public fun modify(delta: Delta): DeltaBuilder = appendModify(delta, FormatChange.Unchanged)

    public fun modify(delta: Delta, formats: Map<String, YValue?>): DeltaBuilder {
        validateFormatPatch(formats)
        return appendModify(delta, FormatChange.Patch(normalizeFormatPatch(formats)))
    }

    public fun modifyClearingFormats(delta: Delta): DeltaBuilder = appendModify(delta, FormatChange.Clear)

    public fun setAttr(key: String, value: DeltaValue): DeltaBuilder = apply {
        ensureMutable()
        require(key.isNotEmpty()) { "attribute key must not be empty" }
        attributes[key] = AttributeOp.Set(value)
    }

    public fun setDataAttr(key: String, value: YValue): DeltaBuilder = setAttr(key, DeltaValue.Data(value))

    public fun setTypeAttr(key: String, value: AbstractYType): DeltaBuilder =
        setAttr(key, DeltaValue.SharedType(value))

    public fun deleteAttr(key: String): DeltaBuilder = apply {
        ensureMutable()
        require(key.isNotEmpty()) { "attribute key must not be empty" }
        attributes[key] = AttributeOp.Delete
    }

    public fun modifyAttr(key: String, delta: Delta): DeltaBuilder = apply {
        ensureMutable()
        require(key.isNotEmpty()) { "attribute key must not be empty" }
        attributes[key] = AttributeOp.Modify(delta)
    }

    public fun done(): Delta {
        ensureMutable()
        completed = true
        return Delta(name, attributes, children)
    }

    private fun appendRetain(length: Int, formats: FormatChange): DeltaBuilder = apply {
        ensureMutable()
        require(length >= 0) { "retain length must be non-negative" }
        if (length == 0) return@apply
        val previous = children.lastOrNull() as? ChildOp.Retain
        if (previous != null && previous.formats == formats) {
            children[children.lastIndex] = ChildOp.Retain(Math.addExact(previous.length, length), formats)
        } else {
            children += ChildOp.Retain(length, formats)
        }
    }

    private fun appendModify(delta: Delta, formats: FormatChange): DeltaBuilder = apply {
        ensureMutable()
        children += ChildOp.Modify(delta, formats)
    }

    private fun ensureMutable() {
        check(!completed) { "a completed DeltaBuilder is read-only" }
    }
}

/**
 * A v14-shaped view over an existing YKS shared type.
 *
 * The concrete [kind] remains explicit: legacy Yjs clients still interpret roots through one of
 * the established Array/Map/Text/XML projections. Mixing an incompatible content family fails in
 * preflight before the YDoc is changed.
 */
@ExperimentalYjs14Api
public class Type(public val delegate: AbstractYType) {
    init {
        require(delegate !is YUnopenedRoot) {
            "open the root with getType(name, kind); a Yjs 13 update cannot infer a root kind on the wire"
        }
    }

    public val kind: RootKind get() = delegate.kind

    /** @y/y node name. Root storage keys remain available as [storageName]. */
    public val name: String?
        get() = when (val type = delegate) {
            is YXmlElementType -> type.nodeName
            is YXmlHook -> type.hookName
            else -> null
        }

    public val storageName: String get() = delegate.name

    public val doc: YDoc get() = delegate.doc

    public val parent: Type? get() = delegate.parent?.let(::Type)

    public val length: Int
        get() = when (val type = delegate) {
            is YArray -> type.length
            is YText -> type.length
            is YXmlElementType -> type.length
            is YXmlFragment -> type.length
            is YMap -> 0
            else -> error("unsupported shared type: ${type::class.qualifiedName}")
        }

    public val change: DeltaBuilder get() = DeltaBuilder()

    /** A stable shallow snapshot. Use [delegate] when the existing deep-delta/renderer API is needed. */
    public val delta: Delta get() = toDelta()

    public fun toDelta(): Delta {
        val builder = DeltaBuilder(name)
        getAttrs().forEach { (key, value) -> builder.setAttr(key, value) }
        when (val type = delegate) {
            is YMap -> Unit
            is YArray,
            is YText,
            is YXmlElementType,
            is YXmlFragment -> appendUnifiedSequenceDelta(builder, type)
            else -> error("unsupported shared type: ${type::class.qualifiedName}")
        }
        return builder.done()
    }

    public fun applyDelta(delta: Delta, origin: Any? = null) {
        validateDelta(delegate, delta)
        preflightDeltaValues(delegate, delta)
        doc.transact(origin = origin) {
            applyAttributes(delegate, delta.attributes, origin)
            applyChildren(delegate, delta.children, origin)
        }
    }

    public fun insert(index: Int, text: String, formats: Map<String, YValue> = emptyMap(), origin: Any? = null) {
        applyDelta(DeltaBuilder().retain(index).insert(text, formats).done(), origin)
    }

    public fun insertValues(
        index: Int,
        values: List<DeltaValue>,
        formats: Map<String, YValue> = emptyMap(),
        origin: Any? = null,
    ) {
        applyDelta(DeltaBuilder().retain(index).insertValues(values, formats).done(), origin)
    }

    public fun delete(index: Int, length: Int = 1, origin: Any? = null) {
        applyDelta(DeltaBuilder().retain(index).delete(length).done(), origin)
    }

    public fun format(index: Int, length: Int, formats: Map<String, YValue?>, origin: Any? = null) {
        applyDelta(DeltaBuilder().retain(index).retain(length, formats).done(), origin)
    }

    public fun setAttr(key: String, value: DeltaValue, origin: Any? = null) {
        applyDelta(DeltaBuilder().setAttr(key, value).done(), origin)
    }

    public fun deleteAttr(key: String, origin: Any? = null) {
        applyDelta(DeltaBuilder().deleteAttr(key).done(), origin)
    }

    public fun get(index: Int): DeltaValue? {
        if (index !in 0 until length) return null
        return deltaValueFromAny(sequenceValueAt(delegate, index))
    }

    public fun getAttr(key: String): DeltaValue? =
        if (hasAttr(key)) deltaValueFromAny(getRawAttr(delegate, key)) else null

    public fun hasAttr(key: String): Boolean = when (val type = delegate) {
        is YArray -> type.hasAttr(key)
        is YText -> type.hasAttr(key)
        is YXmlElementType -> type.hasAttr(key)
        is YXmlFragment -> type.hasAttr(key)
        is YMap -> type.hasAttr(key)
        else -> error("unsupported shared type: ${type::class.qualifiedName}")
    }

    public fun getAttrs(): Map<String, DeltaValue> = getRawAttrs(delegate)
        .mapValues { (_, value) -> deltaValueFromAny(value) }
        .toSortedMap()

    public fun toJson(): Any? = delegate.toJson()

    public fun clearCache() {
        delegate.clearCache()
    }

    public fun destroy() {
        delegate.destroy()
    }

    override fun equals(other: Any?): Boolean = other is Type && delegate === other.delegate

    override fun hashCode(): Int = System.identityHashCode(delegate)

    override fun toString(): String = "Type(kind=$kind, name=$name, storageName=$storageName)"
}

/** Open a root with an explicit legacy projection, then expose the experimental unified facade. */
@ExperimentalYjs14Api
public fun YDoc.getType(name: String, kind: RootKind): Type = Type(get(name, kind))

/** Expose an already concrete stable YKS type through the experimental unified facade. */
@ExperimentalYjs14Api
public fun AbstractYType.asV14Type(): Type = Type(this)

private fun requirePortableDataValue(value: YValue) {
    when (value) {
        is YValue.TypeRef -> error("use DeltaValue.SharedType so shared-type identity is preserved")
        is YValue.SubdocRef -> error("use DeltaValue.Subdocument so subdocument identity is preserved")
        is YValue.ListValue -> value.value.forEach(::requirePortableDataValue)
        is YValue.MapValue -> value.value.values.forEach(::requirePortableDataValue)
        else -> Unit
    }
}

private fun validateFormats(formats: Map<String, YValue>) {
    require(formats.keys.none(String::isEmpty)) { "format key must not be empty" }
    formats.values.forEach(::requirePortableDataValue)
}

private fun validateFormatPatch(formats: Map<String, YValue?>) {
    require(formats.keys.none(String::isEmpty)) { "format key must not be empty" }
    formats.values.filterNotNull().forEach(::requirePortableDataValue)
}

private fun normalizeDataFormats(formats: Map<String, YValue>): Map<String, YValue> =
    formats.filterValues { value -> value != YValue.Null }.toSortedMap()

private fun normalizeFormatPatch(formats: Map<String, YValue?>): Map<String, YValue?> =
    formats.mapValues { (_, value) -> if (value == YValue.Null) null else value }.toSortedMap()

private fun DeltaValue.toAny(): Any? = when (this) {
    is DeltaValue.Data -> value.toAny()
    is DeltaValue.SharedType -> value
    is DeltaValue.Subdocument -> value
}

private fun deltaValueFromAny(value: Any?): DeltaValue = when (value) {
    is AbstractYType -> DeltaValue.SharedType(value)
    is YDoc -> DeltaValue.Subdocument(value)
    else -> DeltaValue.Data(YValue.from(value))
}

private fun formatDataFromAny(values: Map<String, Any?>): Map<String, YValue> =
    values.mapValues { (_, value) -> YValue.from(value).also(::requirePortableDataValue) }.toSortedMap()

private fun formatPatchToAny(change: FormatChange): Map<String, Any?>? = when (change) {
    FormatChange.Unchanged -> null
    FormatChange.Clear -> emptyMap()
    is FormatChange.Patch -> change.values.mapValues { (_, value) -> value?.toAny() }
}

private fun appendUnifiedSequenceDelta(builder: DeltaBuilder, type: AbstractYType) {
    type.doc.sequence(type.name).forEach { item ->
        if (item.deleted || !item.countable) return@forEach
        val formats = when (item.content) {
            is ItemContent.Text,
            is ItemContent.TextEmbed,
            is ItemContent.XmlType -> type.doc.renderedTextAttributes(item)
                .filterValues { value -> value != YValue.Null }
                .toSortedMap()
            else -> emptyMap()
        }
        when (val content = item.content) {
            is ItemContent.Text -> builder.insert(content.value, formats)
            is ItemContent.TextEmbed -> builder.insertValue(
                deltaValueFromAny(type.doc.valueToAny(content.value)),
                formats,
            )
            is ItemContent.Value -> builder.insertValue(deltaValueFromAny(type.doc.valueToAny(content.value)))
            is ItemContent.ArrayValues -> builder.insertValues(
                content.values.map { value -> deltaValueFromAny(type.doc.valueToAny(value)) },
            )
            is ItemContent.XmlType -> builder.insertType(type.doc.typeFromXmlType(content), formats)
            is ItemContent.XmlNode -> error(
                "private static XML content has no @y/y 14 delta representation; use live XML shared types",
            )
            is ItemContent.Deleted,
            is ItemContent.MapEntries,
            is ItemContent.MapEntry,
            is ItemContent.NativeTextFormat,
            is ItemContent.TextFormat -> Unit
        }
    }
}

private fun unifiedSequenceValueAt(target: AbstractYType, index: Int): Any? {
    val (item, offsetLong) = target.doc.visibleSequencePositionAt(target.name, index) ?: return null
    val offset = offsetLong.toInt()
    return when (val content = item.content) {
        is ItemContent.Text -> content.value[offset].toString()
        is ItemContent.TextEmbed -> target.doc.valueToAny(content.value)
        is ItemContent.Value -> target.doc.valueToAny(content.value)
        is ItemContent.ArrayValues -> target.doc.valueToAny(content.values[offset])
        is ItemContent.XmlType -> target.doc.typeFromXmlType(content)
        is ItemContent.XmlNode -> content.toXmlListValue(target.doc, offset)
        else -> error("item content is not an indexed child: ${content::class.simpleName}")
    }
}

private fun insertUnifiedText(target: AbstractYType, index: Int, text: String) {
    require(!target.isPreliminary) { "integrate an XML type before inserting direct v14 text" }
    target.doc.transact {
        val (origin, rightOrigin) = target.doc.insertionAnchors(target.name, index)
        target.doc.integrateLocal(
            StoreItem(
                id = target.doc.nextId(),
                origin = origin,
                rightOrigin = rightOrigin,
                parent = target.name,
                parentSub = null,
                content = ItemContent.Text(text, kind = target.kind),
            ),
        )
    }
}

private fun insertUnifiedValues(target: AbstractYType, index: Int, values: List<DeltaValue>) {
    require(!target.isPreliminary) { "integrate a shared type before inserting v14 values" }
    val rawValues = values.map(DeltaValue::toAny)
    target.doc.preflightNestedValue(rawValues)
    target.doc.transact {
        val (initialOrigin, rightOrigin) = target.doc.insertionAnchors(target.name, index)
        var origin = initialOrigin
        val contents = buildList {
            val packed = mutableListOf<YValue>()
            fun flushPacked() {
                when (packed.size) {
                    0 -> Unit
                    1 -> add(ItemContent.Value(packed.single(), target.kind))
                    else -> add(ItemContent.ArrayValues(packed.toList(), target.kind))
                }
                packed.clear()
            }
            values.zip(rawValues).forEach { (value, raw) ->
                val stored = target.doc.storeValue(raw, parent = target.name)
                when {
                    value is DeltaValue.SharedType -> {
                        flushPacked()
                        add(
                            ItemContent.XmlType(
                                stored as YValue.TypeRef,
                                value.value.let { shared ->
                                    when (shared) {
                                        is YXmlElementType -> shared.nodeName
                                        is YXmlHook -> shared.hookName
                                        else -> ""
                                    }
                                },
                                target.kind,
                            ),
                        )
                    }
                    value is DeltaValue.Subdocument || stored is YValue.BinaryValue -> {
                        flushPacked()
                        add(ItemContent.Value(stored, target.kind))
                    }
                    else -> packed += stored
                }
            }
            flushPacked()
        }
        contents.forEach { content ->
            val item = StoreItem(
                id = target.doc.nextId(),
                origin = origin,
                rightOrigin = rightOrigin,
                parent = target.name,
                parentSub = null,
                content = content,
            )
            target.doc.integrateLocal(item)
            origin = item.lastId
        }
    }
}

private fun getRawAttrs(type: AbstractYType): Map<String, Any?> = when (type) {
    is YArray -> type.getAttrs()
    is YText -> type.getAttrs()
    is YXmlElementType -> type.getAttrs()
    is YXmlFragment -> type.getAttrs()
    is YMap -> type.getAttrs()
    else -> error("unsupported shared type: ${type::class.qualifiedName}")
}

private fun getRawAttr(type: AbstractYType, key: String): Any? = when (type) {
    is YArray -> type.getAttr(key)
    is YText -> type.getAttr(key)
    is YXmlElementType -> type.getAttr(key)
    is YXmlFragment -> type.getAttr(key)
    is YMap -> type.getAttr(key)
    else -> error("unsupported shared type: ${type::class.qualifiedName}")
}

private fun setRawAttr(type: AbstractYType, key: String, value: Any?) {
    when (type) {
        is YArray -> type.setAttr(key, value)
        is YText -> type.setAttr(key, value)
        is YXmlElementType -> type.setAttr(key, value)
        is YXmlFragment -> type.setAttr(key, value)
        is YMap -> type.setAttr(key, value)
        else -> error("unsupported shared type: ${type::class.qualifiedName}")
    }
}

private fun deleteRawAttr(type: AbstractYType, key: String) {
    when (type) {
        is YArray -> type.deleteAttr(key)
        is YText -> type.deleteAttr(key)
        is YXmlElementType -> type.deleteAttr(key)
        is YXmlFragment -> type.deleteAttr(key)
        is YMap -> type.deleteAttr(key)
        else -> error("unsupported shared type: ${type::class.qualifiedName}")
    }
}

private fun validateDelta(target: AbstractYType, delta: Delta) {
    val targetName = when (target) {
        is YXmlElementType -> target.nodeName
        is YXmlHook -> target.hookName
        else -> null
    }
    require(delta.name == null || delta.name == targetName) {
        "delta node name '${delta.name}' does not match target node name '$targetName'"
    }

    delta.attributes.forEach { (key, op) ->
        when (op) {
            is AttributeOp.Set -> Unit
            AttributeOp.Delete -> Unit
            is AttributeOp.Modify -> {
                val child = getRawAttr(target, key) as? AbstractYType
                    ?: error("modifyAttr '$key' must address a shared-type attribute")
                validateDelta(child, op.delta)
            }
        }
    }

    if (target is YMap) {
        require(delta.children.isEmpty()) { "map-backed type ${target.kind} cannot contain indexed children" }
        return
    }

    val virtualChildren = VirtualSequence(target)
    var cursor = 0
    delta.children.forEach { op ->
        when (op) {
            is ChildOp.InsertText -> {
                require(target !is YMap) { "map-backed type ${target.kind} cannot contain indexed children" }
                if (target !is YText) {
                    require(op.formats.isEmpty()) {
                        "formatted text insertion currently requires a Text or XmlText projection"
                    }
                }
                virtualChildren.insertText(cursor, op.text.length)
                cursor = Math.addExact(cursor, op.text.length)
            }
            is ChildOp.InsertValues -> {
                validateValueInsertion(target, op.values, op.formats)
                virtualChildren.insertValues(cursor, op.values.map(DeltaValue::toAny))
                cursor = Math.addExact(cursor, op.values.size)
            }
            is ChildOp.Retain -> {
                val end = Math.addExact(cursor, op.length)
                require(end <= virtualChildren.length) { "retain exceeds target length" }
                validateFormatChange(target, op.formats)
                cursor = end
            }
            is ChildOp.Delete -> {
                val end = Math.addExact(cursor, op.length)
                require(end <= virtualChildren.length) { "delete exceeds target length" }
                virtualChildren.delete(cursor, op.length)
            }
            is ChildOp.Modify -> {
                require(cursor < virtualChildren.length) { "modify exceeds target length" }
                val child = virtualChildren.valueAt(cursor) as? AbstractYType
                    ?: error("modify must address a shared-type child")
                validateFormatChange(target, op.formats)
                validateDelta(child, op.delta)
                cursor = Math.addExact(cursor, 1)
            }
        }
    }
}

private fun preflightDeltaValues(target: AbstractYType, delta: Delta) {
    val values = buildList {
        fun collect(current: Delta) {
            current.attributes.values.forEach { op ->
                when (op) {
                    is AttributeOp.Set -> add(op.value.toAny())
                    AttributeOp.Delete -> Unit
                    is AttributeOp.Modify -> collect(op.delta)
                }
            }
            current.children.forEach { op ->
                when (op) {
                    is ChildOp.InsertValues -> addAll(op.values.map(DeltaValue::toAny))
                    is ChildOp.Modify -> collect(op.delta)
                    is ChildOp.Delete,
                    is ChildOp.InsertText,
                    is ChildOp.Retain -> Unit
                }
            }
        }
        collect(delta)
    }
    if (values.isNotEmpty()) target.doc.preflightNestedValue(values)
}

private fun validateValueInsertion(
    target: AbstractYType,
    values: List<DeltaValue>,
    formats: Map<String, YValue>,
) {
    if (formats.isNotEmpty()) {
        require(target is YText) { "insert formats require a Text or XmlText projection" }
        require(values.none { value -> value is DeltaValue.Data && value.value == YValue.Null }) {
            "formatted null values are not representable by the legacy Text projection"
        }
    }
}

private fun validateFormatChange(target: AbstractYType, change: FormatChange) {
    if (change != FormatChange.Unchanged) {
        require(target is YText) { "format changes require a Text or XmlText projection" }
    }
}

private fun sequenceLength(target: AbstractYType): Int = when (target) {
    is YArray -> if (target.isPreliminary) target.preliminaryList.size else target.length
    is YText -> if (target.isPreliminary) 0 else target.length
    is YXmlElementType -> if (target.isPreliminary) target.preliminaryList.size else target.length
    is YXmlFragment -> if (target.isPreliminary) target.preliminaryList.size else target.length
    else -> error("type ${target.kind} has no indexed children")
}

private fun sequenceValueAt(target: AbstractYType, index: Int): Any? = when (target) {
    is YArray -> if (target.isPreliminary) target.preliminaryList[index] else target.get(index)
    is YText -> if (target.isPreliminary) null else unifiedSequenceValueAt(target, index)
    is YXmlElementType ->
        if (target.isPreliminary) target.preliminaryList[index] else unifiedSequenceValueAt(target, index)
    is YXmlFragment ->
        if (target.isPreliminary) target.preliminaryList[index] else unifiedSequenceValueAt(target, index)
    else -> error("type ${target.kind} has no indexed children")
}

private class VirtualSequence(private val target: AbstractYType) {
    private sealed interface Segment {
        val length: Int

        data class Existing(val start: Int, override val length: Int) : Segment

        data class Values(val values: List<Any?>) : Segment {
            override val length: Int get() = values.size
        }

        data class Text(override val length: Int) : Segment
    }

    private val segments = mutableListOf<Segment>()

    var length: Int = sequenceLength(target)
        private set

    init {
        if (length > 0) segments += Segment.Existing(start = 0, length = length)
    }

    fun insertText(index: Int, insertedLength: Int) {
        if (insertedLength == 0) return
        val boundary = splitAt(index)
        segments.add(boundary, Segment.Text(insertedLength))
        length = Math.addExact(length, insertedLength)
    }

    fun insertValues(index: Int, values: List<Any?>) {
        if (values.isEmpty()) return
        val boundary = splitAt(index)
        segments.add(boundary, Segment.Values(values.toList()))
        length = Math.addExact(length, values.size)
    }

    fun delete(index: Int, deletedLength: Int) {
        if (deletedLength == 0) return
        val end = Math.addExact(index, deletedLength)
        val startBoundary = splitAt(index)
        val endBoundary = splitAt(end)
        segments.subList(startBoundary, endBoundary).clear()
        length -= deletedLength
    }

    fun valueAt(index: Int): Any? {
        require(index in 0 until length) { "index is out of bounds" }
        var position = 0
        segments.forEach { segment ->
            val end = position + segment.length
            if (index < end) {
                val offset = index - position
                return when (segment) {
                    is Segment.Existing -> sequenceValueAt(target, segment.start + offset)
                    is Segment.Values -> segment.values[offset]
                    is Segment.Text -> TextSlot
                }
            }
            position = end
        }
        error("virtual sequence index could not be resolved")
    }

    private fun splitAt(index: Int): Int {
        require(index in 0..length) { "index is out of bounds" }
        if (index == length) return segments.size
        var position = 0
        segments.forEachIndexed { segmentIndex, segment ->
            val end = position + segment.length
            when {
                index == position -> return segmentIndex
                index < end -> {
                    val offset = index - position
                    val left = segment.slice(0, offset)
                    val right = segment.slice(offset, segment.length)
                    segments[segmentIndex] = left
                    segments.add(segmentIndex + 1, right)
                    return segmentIndex + 1
                }
            }
            position = end
        }
        error("virtual sequence boundary could not be resolved")
    }

    private fun Segment.slice(from: Int, to: Int): Segment = when (this) {
        is Segment.Existing -> Segment.Existing(start + from, to - from)
        is Segment.Values -> Segment.Values(values.subList(from, to).toList())
        is Segment.Text -> Segment.Text(to - from)
    }

    private data object TextSlot
}

private fun applyAttributes(target: AbstractYType, operations: Map<String, AttributeOp>, origin: Any?) {
    operations.forEach { (key, op) ->
        when (op) {
            is AttributeOp.Set -> setRawAttr(target, key, op.value.toAny())
            AttributeOp.Delete -> deleteRawAttr(target, key)
            is AttributeOp.Modify -> {
                val child = getRawAttr(target, key) as AbstractYType
                Type(child).applyDelta(op.delta, origin)
            }
        }
    }
}

private fun applyChildren(target: AbstractYType, operations: List<ChildOp>, origin: Any?) {
    var cursor = 0
    operations.forEach { op ->
        when (op) {
            is ChildOp.InsertText -> {
                if (target is YText) {
                    target.insert(cursor, op.text, op.formats.mapValues { (_, value) -> value.toAny() })
                } else {
                    insertUnifiedText(target, cursor, op.text)
                }
                cursor += op.text.length
            }
            is ChildOp.InsertValues -> {
                val values = op.values.map(DeltaValue::toAny)
                when (target) {
                    is YArray -> target.insert(cursor, values)
                    is YText -> if (op.formats.isEmpty()) {
                        insertUnifiedValues(target, cursor, op.values)
                    } else {
                        values.forEach { value ->
                            target.insertEmbed(
                                cursor++,
                                value,
                                op.formats.mapValues { (_, format) -> format.toAny() },
                                origin,
                            )
                        }
                    }
                    is YXmlElementType -> insertUnifiedValues(target, cursor, op.values)
                    is YXmlFragment -> insertUnifiedValues(target, cursor, op.values)
                    else -> error("type ${target.kind} cannot contain indexed children")
                }
                if (target !is YText || op.formats.isEmpty()) cursor += values.size
            }
            is ChildOp.Retain -> {
                applyFormats(target, cursor, op.length, op.formats)
                cursor += op.length
            }
            is ChildOp.Delete -> when (target) {
                is YArray -> target.delete(cursor, op.length)
                is YText -> target.delete(cursor, op.length)
                is YXmlElementType -> target.delete(cursor, op.length)
                is YXmlFragment -> target.delete(cursor, op.length)
                else -> error("type ${target.kind} cannot contain indexed children")
            }
            is ChildOp.Modify -> {
                val child = when (target) {
                    is YArray -> target.get(cursor)
                    is YText -> target.get(cursor)
                    is YXmlElementType -> target.get(cursor)
                    is YXmlFragment -> target.get(cursor)
                    else -> null
                } as AbstractYType
                Type(child).applyDelta(op.delta, origin)
                applyFormats(target, cursor, 1, op.formats)
                cursor++
            }
        }
    }
}

private fun applyFormats(target: AbstractYType, index: Int, length: Int, change: FormatChange) {
    if (change == FormatChange.Unchanged || length == 0) return
    val text = target as YText
    when (change) {
        FormatChange.Unchanged -> Unit
        is FormatChange.Patch -> text.format(index, length, checkNotNull(formatPatchToAny(change)))
        FormatChange.Clear -> clearTextFormats(text, index, length)
    }
}

private fun clearTextFormats(text: YText, index: Int, length: Int) {
    val end = Math.addExact(index, length)
    var position = 0
    text.toDelta().ops.forEach { op ->
        val opLength = when (val insert = op.insert) {
            is String -> insert.length
            null -> 0
            else -> 1
        }
        val overlapStart = maxOf(index, position)
        val overlapEnd = minOf(end, position + opLength)
        if (overlapStart < overlapEnd && op.attributes.isNotEmpty()) {
            text.format(
                overlapStart,
                overlapEnd - overlapStart,
                op.attributes.keys.associateWith { null },
            )
        }
        position += opLength
    }
}
