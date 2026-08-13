@file:OptIn(ExperimentalYjs14Api::class)

package dev.yks.experimental.v14

import dev.yks.AbstractYType
import dev.yks.AbstractRenderer
import dev.yks.ItemContent
import dev.yks.RootKind
import dev.yks.StoreItem
import dev.yks.Subscription
import dev.yks.YArray
import dev.yks.YArrayDeltaOp
import dev.yks.YDoc
import dev.yks.YMap
import dev.yks.YText
import dev.yks.YTextDelta
import dev.yks.YTransaction
import dev.yks.YTransactionEvent
import dev.yks.YUnopenedRoot
import dev.yks.YValue
import dev.yks.YXmlElementType
import dev.yks.YXmlFragment
import dev.yks.YXmlHook
import dev.yks.attributionJsonSchema
import dev.yks.renderUnifiedAttributes
import dev.yks.renderUnifiedSequenceContent
import dev.yks.renderedSequenceIndexToVisibleIndex
import dev.yks.recordRendererAttributedDeletes
import dev.yks.rendererContentLength
import dev.yks.toItemStruct
import dev.yks.toXmlListValue
import java.util.UUID

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

    /** A deep settled snapshot that still retains the shared type identity needed when applied. */
    public data class SharedTypeState(val value: AbstractYType, val delta: Delta) : DeltaValue

    /** A real subdocument. It retains identity and lifecycle semantics. */
    public data class Subdocument(val value: YDoc) : DeltaValue

    /** Recursive data container used when shared types occur below a list value. */
    public class ListData internal constructor(values: List<DeltaValue>) : DeltaValue {
        public val values: List<DeltaValue> = values.toList()

        override fun equals(other: Any?): Boolean = other is ListData && values == other.values

        override fun hashCode(): Int = values.hashCode()

        override fun toString(): String = "ListData(values=$values)"
    }

    /** Recursive data container used when shared types occur below a map value. */
    public class MapData internal constructor(values: Map<String, DeltaValue>) : DeltaValue {
        public val values: Map<String, DeltaValue> = values.toSortedMap()

        override fun equals(other: Any?): Boolean = other is MapData && values == other.values

        override fun hashCode(): Int = values.hashCode()

        override fun toString(): String = "MapData(values=$values)"
    }

    public companion object {
        public fun data(value: YValue): DeltaValue = Data(value)

        public fun text(value: String): DeltaValue = Data(YValue.StringValue(value))

        public fun integer(value: Long): DeltaValue = Data(YValue.LongNumber(value))

        public fun bool(value: Boolean): DeltaValue = Data(YValue.Bool(value))

        public fun shared(type: AbstractYType): DeltaValue = SharedType(type)

        public fun sharedState(type: AbstractYType, delta: Delta): DeltaValue = SharedTypeState(type, delta)

        public fun subdocument(doc: YDoc): DeltaValue = Subdocument(doc)

        public fun list(values: List<DeltaValue>): DeltaValue = ListData(values.toList())

        public fun map(values: Map<String, DeltaValue>): DeltaValue = MapData(values.toSortedMap())
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

/** Immutable, schema-checked attribution stored on a settled insert/set/delete delta operation. */
@ExperimentalYjs14Api
public class DeltaAttribution private constructor(values: Map<String, YValue>) {
    public val values: Map<String, YValue> = values.toSortedMap()

    override fun equals(other: Any?): Boolean = other is DeltaAttribution && values == other.values

    override fun hashCode(): Int = values.hashCode()

    override fun toString(): String = "DeltaAttribution(values=$values)"

    public companion object {
        public fun of(values: Map<String, YValue>): DeltaAttribution {
            values.values.forEach(::requirePortableDataValue)
            val normalized = values.filterValues { value -> value != YValue.Null }.toSortedMap()
            require(attributionJsonSchema.check(normalized.mapValues { (_, value) -> value.toAny() })) {
                "attribution does not match the @y/y 14 attribution schema"
            }
            return DeltaAttribution(normalized)
        }
    }
}

/** Tri-state attribution instruction used by retain/modify operations. */
@ExperimentalYjs14Api
public sealed interface AttributionChange {
    /** Inherit the builder context, or leave attribution unchanged when no context is active. */
    public data object Unchanged : AttributionChange

    /** Clear all attribution in the addressed dimension. */
    public data object Clear : AttributionChange

    /** Set values and remove keys whose value is null. `format` merges one level deeper. */
    public class Patch internal constructor(values: Map<String, YValue?>) : AttributionChange {
        public val values: Map<String, YValue?> = normalizeAttributionPatch(values)

        init {
            validateAttributionPatch(this.values)
        }

        override fun equals(other: Any?): Boolean = other is Patch && values == other.values

        override fun hashCode(): Int = values.hashCode()

        override fun toString(): String = "Patch(values=$values)"
    }
}

/** A mark's terminal location within one delta node. */
@ExperimentalYjs14Api
public sealed interface DeltaMarkKey {
    @ConsistentCopyVisibility
    public data class Child internal constructor(val index: Int) : DeltaMarkKey {
        init {
            require(index >= 0) { "mark child index must be non-negative" }
        }
    }

    @ConsistentCopyVisibility
    public data class Attribute internal constructor(val key: String) : DeltaMarkKey {
        init {
            require(key.isNotEmpty()) { "mark attribute key must not be empty" }
        }
    }
}

/** Immutable cursor/selection anchor carried by a v14 delta tree. */
@ExperimentalYjs14Api
public class DeltaMark internal constructor(
    public val key: DeltaMarkKey,
    public val id: String,
    public val association: Int,
    attrs: Map<String, YValue>,
) {
    public val attrs: Map<String, YValue> = attrs.toSortedMap()

    init {
        require(id.isNotEmpty()) { "mark id must not be empty" }
        require(association == -1 || association == 1) { "mark association must be -1 or 1" }
        this.attrs.values.forEach(::requirePortableDataValue)
    }

    override fun equals(other: Any?): Boolean =
        other is DeltaMark &&
            key == other.key && id == other.id && association == other.association && attrs == other.attrs

    override fun hashCode(): Int =
        31 * (31 * (31 * key.hashCode() + id.hashCode()) + association) + attrs.hashCode()

    override fun toString(): String =
        "DeltaMark(key=$key, id=$id, association=$association, attrs=$attrs)"
}

/** A child-list operation in the pinned @y/y 14 delta vocabulary. */
@ExperimentalYjs14Api
public sealed interface ChildOp {
    public class InsertText internal constructor(
        text: String,
        formats: Map<String, YValue>,
        public val attribution: DeltaAttribution? = null,
    ) : ChildOp {
        public val text: String = text
        public val formats: Map<String, YValue> = formats.toSortedMap()

        override fun equals(other: Any?): Boolean =
            other is InsertText &&
                text == other.text && formats == other.formats && attribution == other.attribution

        override fun hashCode(): Int = 31 * (31 * text.hashCode() + formats.hashCode()) + (attribution?.hashCode() ?: 0)

        override fun toString(): String = "InsertText(text=$text, formats=$formats, attribution=$attribution)"
    }

    public class InsertValues internal constructor(
        values: List<DeltaValue>,
        formats: Map<String, YValue>,
        public val attribution: DeltaAttribution? = null,
    ) : ChildOp {
        public val values: List<DeltaValue> = values.toList()
        public val formats: Map<String, YValue> = formats.toSortedMap()

        override fun equals(other: Any?): Boolean =
            other is InsertValues &&
                values == other.values && formats == other.formats && attribution == other.attribution

        override fun hashCode(): Int =
            31 * (31 * values.hashCode() + formats.hashCode()) + (attribution?.hashCode() ?: 0)

        override fun toString(): String = "InsertValues(values=$values, formats=$formats, attribution=$attribution)"
    }

    @ConsistentCopyVisibility
    public data class Retain internal constructor(
        val length: Int,
        val formats: FormatChange,
        val attribution: AttributionChange = AttributionChange.Unchanged,
    ) : ChildOp

    @ConsistentCopyVisibility
    public data class Delete internal constructor(val length: Int) : ChildOp

    @ConsistentCopyVisibility
    public data class Modify internal constructor(
        val delta: Delta,
        val formats: FormatChange,
        val attribution: AttributionChange = AttributionChange.Unchanged,
    ) : ChildOp
}

/** An attribute operation in the pinned @y/y 14 delta vocabulary. */
@ExperimentalYjs14Api
public sealed interface AttributeOp {
    @ConsistentCopyVisibility
    public data class Set internal constructor(
        val value: DeltaValue,
        val attribution: DeltaAttribution? = null,
    ) : AttributeOp

    public data object Delete : AttributeOp

    @ConsistentCopyVisibility
    public data class DeleteAttributed internal constructor(
        val attribution: DeltaAttribution,
    ) : AttributeOp

    @ConsistentCopyVisibility
    public data class Modify internal constructor(
        val delta: Delta,
        val attribution: AttributionChange = AttributionChange.Unchanged,
    ) : AttributeOp
}

/** Immutable result of [DeltaBuilder.done]. */
@ExperimentalYjs14Api
public class Delta internal constructor(
    public val name: String?,
    attributes: Map<String, AttributeOp>,
    children: List<ChildOp>,
    marks: List<DeltaMark> = emptyList(),
    deletedMarkIds: Set<String> = emptySet(),
) {
    private var attributesValue: Map<String, AttributeOp> = attributes.toSortedMap()
    private var childrenValue: List<ChildOp> = children.toList()
    private var marksValue: List<DeltaMark> = marks.sortedBy(DeltaMark::id)
    private var deletedMarkIdsValue: Set<String> = deletedMarkIds.toSortedSet()

    public val attributes: Map<String, AttributeOp> get() = attributesValue
    public val children: List<ChildOp> get() = childrenValue
    public val marks: List<DeltaMark> get() = marksValue
    public val deletedMarkIds: Set<String> get() = deletedMarkIdsValue

    public val isEmpty: Boolean
        get() = attributes.isEmpty() && children.isEmpty() && marks.isEmpty() && deletedMarkIds.isEmpty()

    override fun equals(other: Any?): Boolean =
        other is Delta &&
            name == other.name && attributes == other.attributes && children == other.children &&
            marks == other.marks && deletedMarkIds == other.deletedMarkIds

    override fun hashCode(): Int =
        31 * (31 * (31 * (31 * (name?.hashCode() ?: 0) + attributes.hashCode()) + children.hashCode()) +
            marks.hashCode()) + deletedMarkIds.hashCode()

    override fun toString(): String =
        "Delta(name=$name, attributes=$attributes, children=$children, marks=$marks, deletedMarkIds=$deletedMarkIds)"

    /** Mutate only the maintained live-cache object; public deltas remain externally read-only. */
    internal fun replaceContents(other: Delta) {
        require(name == other.name) { "live delta node name cannot change" }
        attributesValue = other.attributes.toSortedMap()
        childrenValue = other.children.toList()
        marksValue = other.marks.sortedBy(DeltaMark::id)
        deletedMarkIdsValue = other.deletedMarkIds.toSortedSet()
    }
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
    private val marks = linkedMapOf<String, DeltaMark>()
    private val deletedMarkIds = linkedSetOf<String>()
    private var usedAttribution: Map<String, YValue?>? = null
    private var completed = false

    public fun useAttribution(attribution: DeltaAttribution?): DeltaBuilder = apply {
        ensureMutable()
        usedAttribution = attribution?.values
    }

    public fun useAttribution(change: AttributionChange): DeltaBuilder = apply {
        ensureMutable()
        usedAttribution = when (change) {
            AttributionChange.Unchanged -> usedAttribution
            AttributionChange.Clear -> null
            is AttributionChange.Patch -> change.values.ifEmpty { null }
        }
    }

    public fun updateUsedAttribution(key: String, value: YValue?): DeltaBuilder = apply {
        ensureMutable()
        require(key.isNotEmpty()) { "attribution key must not be empty" }
        val next = usedAttribution.orEmpty().toMutableMap()
        if (value == null || value == YValue.Null) next.remove(key) else next[key] = value
        usedAttribution = next.ifEmpty { null }?.toSortedMap()
    }

    public fun insert(text: String, formats: Map<String, YValue> = emptyMap()): DeltaBuilder =
        appendText(text, formats, AttributionChange.Unchanged)

    public fun insert(
        text: String,
        formats: Map<String, YValue>,
        attribution: DeltaAttribution?,
    ): DeltaBuilder = appendText(
        text,
        formats,
        attribution?.let { AttributionChange.Patch(it.values) } ?: AttributionChange.Clear,
    )

    public fun insertAttributed(
        text: String,
        formats: Map<String, YValue> = emptyMap(),
        attribution: AttributionChange,
    ): DeltaBuilder = appendText(text, formats, attribution)

    private fun appendText(
        text: String,
        formats: Map<String, YValue>,
        attribution: AttributionChange,
    ): DeltaBuilder = apply {
        ensureMutable()
        validateFormats(formats)
        if (text.isEmpty()) return@apply
        val normalized = normalizeDataFormats(formats)
        val resolvedAttribution = resolveDataAttribution(usedAttribution, attribution)
        val previous = children.lastOrNull() as? ChildOp.InsertText
        if (previous != null &&
            previous.formats == normalized && previous.attribution == resolvedAttribution
        ) {
            children[children.lastIndex] = ChildOp.InsertText(
                previous.text + text,
                normalized,
                resolvedAttribution,
            )
        } else {
            children += ChildOp.InsertText(text, normalized, resolvedAttribution)
        }
    }

    public fun insertValues(
        values: List<DeltaValue>,
        formats: Map<String, YValue> = emptyMap(),
    ): DeltaBuilder = appendValues(values, formats, AttributionChange.Unchanged)

    public fun insertValues(
        values: List<DeltaValue>,
        formats: Map<String, YValue>,
        attribution: DeltaAttribution?,
    ): DeltaBuilder = appendValues(
        values,
        formats,
        attribution?.let { AttributionChange.Patch(it.values) } ?: AttributionChange.Clear,
    )

    public fun insertValuesAttributed(
        values: List<DeltaValue>,
        formats: Map<String, YValue> = emptyMap(),
        attribution: AttributionChange,
    ): DeltaBuilder = appendValues(values, formats, attribution)

    private fun appendValues(
        values: List<DeltaValue>,
        formats: Map<String, YValue>,
        attribution: AttributionChange,
    ): DeltaBuilder = apply {
        ensureMutable()
        validateFormats(formats)
        if (values.isEmpty()) return@apply
        val copied = values.toList()
        val normalized = normalizeDataFormats(formats)
        val resolvedAttribution = resolveDataAttribution(usedAttribution, attribution)
        val previous = children.lastOrNull() as? ChildOp.InsertValues
        if (previous != null &&
            previous.formats == normalized && previous.attribution == resolvedAttribution
        ) {
            children[children.lastIndex] = ChildOp.InsertValues(
                previous.values + copied,
                normalized,
                resolvedAttribution,
            )
        } else {
            children += ChildOp.InsertValues(copied, normalized, resolvedAttribution)
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

    public fun retainWithAttribution(
        length: Int,
        attribution: AttributionChange,
    ): DeltaBuilder = appendRetain(length, FormatChange.Unchanged, attribution)

    public fun retainChanges(
        length: Int,
        formats: FormatChange = FormatChange.Unchanged,
        attribution: AttributionChange = AttributionChange.Unchanged,
    ): DeltaBuilder = appendRetain(length, formats, attribution)

    public fun retain(
        length: Int,
        formats: Map<String, YValue?>,
        attribution: AttributionChange,
    ): DeltaBuilder {
        validateFormatPatch(formats)
        return appendRetain(length, FormatChange.Patch(normalizeFormatPatch(formats)), attribution)
    }

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

    public fun modifyWithAttribution(
        delta: Delta,
        attribution: AttributionChange,
    ): DeltaBuilder = appendModify(delta, FormatChange.Unchanged, attribution)

    public fun modifyChanges(
        delta: Delta,
        formats: FormatChange = FormatChange.Unchanged,
        attribution: AttributionChange = AttributionChange.Unchanged,
    ): DeltaBuilder = appendModify(delta, formats, attribution)

    public fun modify(
        delta: Delta,
        formats: Map<String, YValue?>,
        attribution: AttributionChange,
    ): DeltaBuilder {
        validateFormatPatch(formats)
        return appendModify(delta, FormatChange.Patch(normalizeFormatPatch(formats)), attribution)
    }

    public fun setAttr(key: String, value: DeltaValue): DeltaBuilder = apply {
        ensureMutable()
        require(key.isNotEmpty()) { "attribute key must not be empty" }
        attributes[key] = AttributeOp.Set(value, resolveDataAttribution(usedAttribution, AttributionChange.Unchanged))
    }

    public fun setAttr(
        key: String,
        value: DeltaValue,
        attribution: DeltaAttribution?,
    ): DeltaBuilder = apply {
        ensureMutable()
        require(key.isNotEmpty()) { "attribute key must not be empty" }
        attributes[key] = AttributeOp.Set(value, attribution)
    }

    public fun setDataAttr(key: String, value: YValue): DeltaBuilder = setAttr(key, DeltaValue.Data(value))

    public fun setTypeAttr(key: String, value: AbstractYType): DeltaBuilder =
        setAttr(key, DeltaValue.SharedType(value))

    public fun deleteAttr(key: String): DeltaBuilder = apply {
        ensureMutable()
        require(key.isNotEmpty()) { "attribute key must not be empty" }
        val attribution = resolveDataAttribution(usedAttribution, AttributionChange.Unchanged)
        attributes[key] = if (attribution == null) AttributeOp.Delete else AttributeOp.DeleteAttributed(attribution)
    }

    public fun deleteAttr(key: String, attribution: DeltaAttribution?): DeltaBuilder = apply {
        ensureMutable()
        require(key.isNotEmpty()) { "attribute key must not be empty" }
        attributes[key] = if (attribution == null) AttributeOp.Delete else AttributeOp.DeleteAttributed(attribution)
    }

    public fun modifyAttr(key: String, delta: Delta): DeltaBuilder = apply {
        ensureMutable()
        require(key.isNotEmpty()) { "attribute key must not be empty" }
        attributes[key] = AttributeOp.Modify(delta, resolveInstructionAttribution(usedAttribution, AttributionChange.Unchanged))
    }

    public fun modifyAttr(
        key: String,
        delta: Delta,
        attribution: AttributionChange,
    ): DeltaBuilder = apply {
        ensureMutable()
        require(key.isNotEmpty()) { "attribute key must not be empty" }
        attributes[key] = AttributeOp.Modify(delta, resolveInstructionAttribution(usedAttribution, attribution))
    }

    public fun addMark(
        key: DeltaMarkKey,
        id: String = UUID.randomUUID().toString(),
        association: Int = 1,
        attrs: Map<String, YValue> = emptyMap(),
    ): DeltaBuilder = apply {
        ensureMutable()
        val mark = DeltaMark(key, id, association, attrs)
        marks[id] = mark
        deletedMarkIds.remove(id)
    }

    public fun addChildMark(
        index: Int,
        id: String = UUID.randomUUID().toString(),
        association: Int = 1,
        attrs: Map<String, YValue> = emptyMap(),
    ): DeltaBuilder = addMark(DeltaMarkKey.Child(index), id, association, attrs)

    public fun addAttributeMark(
        key: String,
        id: String = UUID.randomUUID().toString(),
        association: Int = 1,
        attrs: Map<String, YValue> = emptyMap(),
    ): DeltaBuilder = addMark(DeltaMarkKey.Attribute(key), id, association, attrs)

    public fun removeMark(id: String): DeltaBuilder = apply {
        ensureMutable()
        require(id.isNotEmpty()) { "mark id must not be empty" }
        marks.remove(id)
        deletedMarkIds += id
    }

    public fun done(): Delta {
        ensureMutable()
        completed = true
        return Delta(name, attributes, children, marks.values.toList(), deletedMarkIds)
    }

    private fun appendRetain(
        length: Int,
        formats: FormatChange,
        attribution: AttributionChange = AttributionChange.Unchanged,
    ): DeltaBuilder = apply {
        ensureMutable()
        require(length >= 0) { "retain length must be non-negative" }
        if (length == 0) return@apply
        val resolvedAttribution = resolveInstructionAttribution(usedAttribution, attribution)
        val previous = children.lastOrNull() as? ChildOp.Retain
        if (previous != null &&
            previous.formats == formats && previous.attribution == resolvedAttribution
        ) {
            children[children.lastIndex] = ChildOp.Retain(
                Math.addExact(previous.length, length),
                formats,
                resolvedAttribution,
            )
        } else {
            children += ChildOp.Retain(length, formats, resolvedAttribution)
        }
    }

    private fun appendModify(
        delta: Delta,
        formats: FormatChange,
        attribution: AttributionChange = AttributionChange.Unchanged,
    ): DeltaBuilder = apply {
        ensureMutable()
        children += ChildOp.Modify(
            delta,
            formats,
            resolveInstructionAttribution(usedAttribution, attribution),
        )
    }

    private fun ensureMutable() {
        check(!completed) { "a completed DeltaBuilder is read-only" }
    }
}

/** RDT-style event emitted by the experimental unified type facade. */
@ExperimentalYjs14Api
public data class TypeEvent(
    val name: String,
    val target: Type,
    val delta: Delta? = null,
    val origin: Any? = null,
    val transaction: YTransactionEvent? = null,
)

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

    /** A live renderer-aware cache. A held reference is updated in place until [clearCache]. */
    public val delta: Delta get() = state().liveDelta()

    public fun toDelta(): Delta = renderSnapshot(delegate.activeRenderer)

    public fun toDelta(renderer: AbstractRenderer): Delta = renderSnapshot(renderer)

    internal fun renderSnapshot(renderer: AbstractRenderer): Delta {
        val builder = DeltaBuilder(name)
        renderUnifiedAttributes(delegate, renderer).forEach { (key, rendered) ->
            builder.setAttr(
                key,
                deltaValueFromRenderedAny(rendered.value, renderer),
                rendered.attribution?.toDeltaAttribution(),
            )
        }
        when (val type = delegate) {
            is YMap -> Unit
            is YArray,
            is YText,
            is YXmlElementType,
            is YXmlFragment -> appendUnifiedSequenceDelta(builder, type, renderer)
            else -> error("unsupported shared type: ${type::class.qualifiedName}")
        }
        return builder.done()
    }

    public fun on(eventName: String, listener: (TypeEvent) -> Unit): Subscription =
        state().on(eventName, listener)

    public fun once(eventName: String, listener: (TypeEvent) -> Unit): Subscription {
        lateinit var subscription: Subscription
        subscription = on(eventName) { event ->
            subscription.close()
            listener(event)
        }
        return subscription
    }

    public fun off(eventName: String, listener: (TypeEvent) -> Unit) {
        state().off(eventName, listener)
    }

    public fun useRenderer(renderer: AbstractRenderer): Type = apply {
        delegate.useRenderer(renderer)
    }

    public fun applyDelta(delta: Delta, origin: Any? = null) {
        applyDeltaWithCorrection(delta, origin, delegate.activeRenderer)
    }

    /**
     * Apply a v14 delta and return the upstream-style correction when a rendered deleted node rejects
     * all or part of the change. The correction is measured against the caller's expected state.
     */
    public fun applyDeltaWithCorrection(
        delta: Delta,
        origin: Any? = null,
        renderer: AbstractRenderer = delegate.activeRenderer,
    ): Delta? {
        if (delta.isEmpty) return null
        val owner = doc.typeRefItemId(delegate)?.let(doc::getItem)?.toItemStruct(doc)
        if (owner?.deleted == true) {
            return if (rendererContentLength(renderer, owner) > 0) {
                inverseDelta(delta, toDelta(renderer), renderer).takeUnless(Delta::isEmpty)
            } else {
                null
            }
        }
        validateDelta(delegate, delta, renderer)
        preflightDeltaValues(delegate, delta)
        return doc.transact({ transaction ->
            val fix = DeltaBuilder()
            var hasFix = applyAttributes(delegate, delta.attributes, origin, renderer, fix)
            hasFix = applyChildren(delegate, delta.children, origin, renderer, transaction, fix) || hasFix
            if (hasFix) fix.done().takeUnless(Delta::isEmpty) else null
        }, origin = origin)
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
        state().clearCache()
        delegate.clearCache()
    }

    public fun destroy() {
        state().destroy(this)
        delegate.destroy()
        delegate.experimentalV14State = null
    }

    override fun equals(other: Any?): Boolean = other is Type && delegate === other.delegate

    override fun hashCode(): Int = System.identityHashCode(delegate)

    override fun toString(): String = "Type(kind=$kind, name=$name, storageName=$storageName)"

    private fun state(): UnifiedTypeState {
        val existing = delegate.experimentalV14State
        if (existing is UnifiedTypeState) return existing
        return UnifiedTypeState(delegate).also { created -> delegate.experimentalV14State = created }
    }
}

private class UnifiedTypeState(private val delegate: AbstractYType) {
    private var cache: Delta? = null
    private var listenerBaseline: Delta? = null
    private var deltaSubscription: Subscription? = null
    private var destroySubscription: Subscription? = null
    private val listeners = linkedMapOf<String, MutableList<(TypeEvent) -> Unit>>()
    private var destroying = false

    fun liveDelta(): Delta {
        val current = cache
        if (current != null) return current
        return Type(delegate).renderSnapshot(delegate.activeRenderer).also { rendered ->
            cache = rendered
            listenerBaseline = null
            ensureSubscriptions()
        }
    }

    fun on(eventName: String, listener: (TypeEvent) -> Unit): Subscription {
        listeners.getOrPut(eventName) { mutableListOf() }.add(listener)
        if (eventName == "delta" && cache == null && listenerBaseline == null) {
            listenerBaseline = Type(delegate).renderSnapshot(delegate.activeRenderer)
        }
        ensureSubscriptions()
        return Subscription { off(eventName, listener) }
    }

    fun off(eventName: String, listener: (TypeEvent) -> Unit) {
        val eventListeners = listeners[eventName] ?: return
        eventListeners.remove(listener)
        if (eventListeners.isEmpty()) listeners.remove(eventName)
        if (eventName == "delta" && listeners["delta"].orEmpty().isEmpty()) listenerBaseline = null
        releaseSubscriptionsIfIdle()
    }

    fun clearCache() {
        cache = null
        listenerBaseline = if (listeners["delta"].orEmpty().isNotEmpty()) {
            Type(delegate).renderSnapshot(delegate.activeRenderer)
        } else {
            null
        }
        releaseSubscriptionsIfIdle()
    }

    fun destroy(target: Type = Type(delegate)) {
        if (destroying) return
        destroying = true
        val callbacks = listeners["destroy"].orEmpty().toList()
        callbacks.forEach { listener -> listener(TypeEvent(name = "destroy", target = target)) }
        listeners.clear()
        cache = null
        listenerBaseline = null
        deltaSubscription?.close()
        destroySubscription?.close()
        deltaSubscription = null
        destroySubscription = null
        delegate.experimentalV14State = null
    }

    private fun ensureSubscriptions() {
        if (deltaSubscription == null && (cache != null || listenerBaseline != null)) {
            deltaSubscription = delegate.on("delta") { event ->
                refresh(event.origin, event.transaction)
            }
        }
        if (destroySubscription == null && (cache != null || listenerBaseline != null || listeners.isNotEmpty())) {
            destroySubscription = delegate.on("destroy") { destroy() }
        }
    }

    private fun releaseSubscriptionsIfIdle() {
        if (cache == null && listenerBaseline == null) {
            deltaSubscription?.close()
            deltaSubscription = null
        }
        if (cache == null && listenerBaseline == null && listeners.isEmpty()) {
            destroySubscription?.close()
            destroySubscription = null
        }
    }

    private fun refresh(origin: Any?, transaction: YTransactionEvent?) {
        val live = cache
        val current = live ?: listenerBaseline ?: return
        val next = Type(delegate).renderSnapshot(delegate.activeRenderer)
        val change = diffSettledDelta(current, next)
        if (live != null) {
            live.replaceContents(next)
        } else {
            listenerBaseline = next
        }
        if (change.isEmpty) return
        val event = TypeEvent(
            name = "delta",
            target = Type(delegate),
            delta = change,
            origin = origin,
            transaction = transaction,
        )
        listeners["delta"].orEmpty().toList().forEach { listener -> listener(event) }
    }
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

private fun normalizeAttributionPatch(values: Map<String, YValue?>): Map<String, YValue?> =
    values.mapValues { (_, value) -> if (value == YValue.Null) null else value }.toSortedMap()

private fun validateAttributionPatch(values: Map<String, YValue?>) {
    require(values.keys.none(String::isEmpty)) { "attribution key must not be empty" }
    values.values.filterNotNull().forEach(::requirePortableDataValue)
    val resolved = values.mapNotNull { (key, value) ->
        val canonical = when {
            value == null -> null
            key == "format" && value is YValue.MapValue -> YValue.MapValue(
                value.value.filterValues { nested -> nested != YValue.Null },
            ).takeIf { format -> format.value.isNotEmpty() }
            else -> value
        }
        canonical?.let { key to it.toAny() }
    }.toMap()
    require(attributionJsonSchema.check(resolved)) {
        "attribution patch does not match the @y/y 14 attribution schema"
    }
}

private fun mergeAttribution(
    base: Map<String, YValue?>?,
    update: Map<String, YValue?>,
    resolve: Boolean,
): Map<String, YValue?> {
    val merged = linkedMapOf<String, YValue?>()
    base.orEmpty().forEach { (key, rawValue) ->
        val value = if (rawValue == YValue.Null) null else rawValue
        when {
            value == null && resolve -> Unit
            key == "format" && value is YValue.MapValue && resolve -> {
                val inner = value.value.filterValues { nested -> nested != YValue.Null }
                if (inner.isNotEmpty()) merged[key] = YValue.MapValue(inner.toSortedMap())
            }
            else -> merged[key] = value
        }
    }
    update.forEach { (key, rawValue) ->
        val value = if (rawValue == YValue.Null) null else rawValue
        if (key == "format" && value is YValue.MapValue) {
            val current = (merged[key] as? YValue.MapValue)?.value.orEmpty().toMutableMap()
            value.value.forEach { (formatKey, rawFormatValue) ->
                if (rawFormatValue == YValue.Null) {
                    if (resolve) current.remove(formatKey) else current[formatKey] = YValue.Null
                } else {
                    current[formatKey] = rawFormatValue
                }
            }
            if (current.isEmpty()) merged.remove(key) else merged[key] = YValue.MapValue(current.toSortedMap())
        } else if (value == null) {
            if (resolve) merged.remove(key) else merged[key] = null
        } else {
            merged[key] = value
        }
    }
    return merged.toSortedMap()
}

private fun resolveDataAttribution(
    used: Map<String, YValue?>?,
    change: AttributionChange,
): DeltaAttribution? {
    val values = when (change) {
        AttributionChange.Unchanged -> mergeAttribution(null, used.orEmpty(), resolve = true)
        AttributionChange.Clear -> emptyMap()
        is AttributionChange.Patch -> mergeAttribution(used, change.values, resolve = true)
    }.mapNotNull { (key, value) -> value?.let { key to it } }.toMap()
    return values.takeIf(Map<String, YValue>::isNotEmpty)?.let(DeltaAttribution::of)
}

private fun resolveInstructionAttribution(
    used: Map<String, YValue?>?,
    change: AttributionChange,
): AttributionChange = when (change) {
    AttributionChange.Unchanged -> used
        ?.takeIf(Map<String, YValue?>::isNotEmpty)
        ?.let(AttributionChange::Patch)
        ?: AttributionChange.Unchanged
    AttributionChange.Clear -> AttributionChange.Clear
    is AttributionChange.Patch -> mergeAttribution(used, change.values, resolve = false)
        .takeIf(Map<String, YValue?>::isNotEmpty)
        ?.let(AttributionChange::Patch)
        ?: AttributionChange.Unchanged
}

private fun Map<String, Any?>.toDeltaAttribution(): DeltaAttribution =
    DeltaAttribution.of(mapValues { (_, value) -> YValue.from(value) })

private fun DeltaValue.toAny(): Any? = when (this) {
    is DeltaValue.Data -> value.toAny()
    is DeltaValue.SharedType -> value
    is DeltaValue.SharedTypeState -> value
    is DeltaValue.Subdocument -> value
    is DeltaValue.ListData -> values.map(DeltaValue::toAny)
    is DeltaValue.MapData -> values.mapValues { (_, value) -> value.toAny() }
}

private fun DeltaValue.sharedTypeOrNull(): AbstractYType? = when (this) {
    is DeltaValue.SharedType -> value
    is DeltaValue.SharedTypeState -> value
    else -> null
}

private fun DeltaValue.sharedDeltaOrNull(): Delta? = (this as? DeltaValue.SharedTypeState)?.delta

private fun deltaValueFromAny(value: Any?): DeltaValue = when (value) {
    is AbstractYType -> DeltaValue.SharedType(value)
    is YDoc -> DeltaValue.Subdocument(value)
    else -> DeltaValue.Data(YValue.from(value))
}

private fun deltaValueFromRenderedAny(value: Any?, renderer: AbstractRenderer): DeltaValue = when (value) {
    is AbstractYType -> DeltaValue.SharedTypeState(value, Type(value).renderSnapshot(renderer))
    is YDoc -> DeltaValue.Subdocument(value)
    is List<*> -> DeltaValue.ListData(value.map { nested -> deltaValueFromRenderedAny(nested, renderer) })
    is Array<*> -> DeltaValue.ListData(value.map { nested -> deltaValueFromRenderedAny(nested, renderer) })
    is Map<*, *> -> DeltaValue.MapData(value.entries.associate { (key, nested) ->
        require(key is String) { "delta map keys must be strings" }
        key to deltaValueFromRenderedAny(nested, renderer)
    }.toSortedMap())
    else -> DeltaValue.Data(YValue.from(value))
}

private fun formatDataFromAny(values: Map<String, Any?>): Map<String, YValue> =
    values.mapValues { (_, value) -> YValue.from(value).also(::requirePortableDataValue) }.toSortedMap()

private fun formatPatchToAny(change: FormatChange): Map<String, Any?>? = when (change) {
    FormatChange.Unchanged -> null
    FormatChange.Clear -> emptyMap()
    is FormatChange.Patch -> change.values.mapValues { (_, value) -> value?.toAny() }
}

private fun appendUnifiedSequenceDelta(
    builder: DeltaBuilder,
    type: AbstractYType,
    renderer: AbstractRenderer,
) {
    renderUnifiedSequenceContent(type, renderer).forEach { rendered ->
        val formats = formatDataFromAny(rendered.formats)
        val attribution = rendered.attribution?.toDeltaAttribution()
        if (rendered.text != null) {
            builder.insert(rendered.text, formats, attribution)
        } else {
            builder.insertValues(
                rendered.values.map { value -> deltaValueFromRenderedAny(value, renderer) },
                formats,
                attribution,
            )
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
                    value is DeltaValue.SharedType || value is DeltaValue.SharedTypeState -> {
                        flushPacked()
                        add(
                            ItemContent.XmlType(
                                stored as YValue.TypeRef,
                                (if (value is DeltaValue.SharedType) value.value else (value as DeltaValue.SharedTypeState).value)
                                    .let { shared ->
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

private fun validateDelta(
    target: AbstractYType,
    delta: Delta,
    renderer: AbstractRenderer = target.activeRenderer,
) {
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
            AttributeOp.Delete,
            is AttributeOp.DeleteAttributed -> Unit
            is AttributeOp.Modify -> {
                val child = renderUnifiedAttributes(target, renderer)[key]?.value as? AbstractYType
                    ?: error("modifyAttr '$key' must address a shared-type attribute")
                validateDelta(child, op.delta, renderer)
            }
        }
    }

    if (target is YMap) {
        require(delta.children.isEmpty()) { "map-backed type ${target.kind} cannot contain indexed children" }
        return
    }

    val virtualChildren = VirtualSequence(target, renderer)
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
                validateDelta(child, op.delta, renderer)
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
                    AttributeOp.Delete,
                    is AttributeOp.DeleteAttributed -> Unit
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

private class VirtualSequence(
    private val target: AbstractYType,
    renderer: AbstractRenderer = target.activeRenderer,
) {
    private sealed interface Segment {
        val length: Int

        data class Existing(val start: Int, override val length: Int) : Segment

        data class Values(val values: List<Any?>) : Segment {
            override val length: Int get() = values.size
        }

        data class Text(override val length: Int) : Segment
    }

    private val segments = mutableListOf<Segment>()
    private val existingValues: List<Any?> = if (target.isPreliminary) {
        (0 until sequenceLength(target)).map { index -> sequenceValueAt(target, index) }
    } else {
        renderUnifiedSequenceContent(target, renderer).flatMap { rendered ->
            rendered.text?.map(Char::toString) ?: rendered.values
        }
    }

    var length: Int = existingValues.size
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
                    is Segment.Existing -> existingValues[segment.start + offset]
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

private fun applyAttributes(
    target: AbstractYType,
    operations: Map<String, AttributeOp>,
    origin: Any?,
    renderer: AbstractRenderer,
    fix: DeltaBuilder,
): Boolean {
    var hasFix = false
    operations.forEach { (key, op) ->
        when (op) {
            is AttributeOp.Set -> setRawAttr(target, key, op.value.toAny())
            AttributeOp.Delete,
            is AttributeOp.DeleteAttributed -> deleteRawAttr(target, key)
            is AttributeOp.Modify -> {
                val child = renderUnifiedAttributes(target, renderer)[key]?.value as AbstractYType
                val childFix = Type(child).applyDeltaWithCorrection(op.delta, origin, renderer)
                if (childFix != null) {
                    fix.modifyAttr(key, childFix)
                    hasFix = true
                }
            }
        }
    }
    return hasFix
}

private fun applyChildren(
    target: AbstractYType,
    operations: List<ChildOp>,
    origin: Any?,
    renderer: AbstractRenderer,
    transaction: YTransaction,
    fix: DeltaBuilder,
): Boolean {
    var cursor = 0
    var expectedIndex = 0
    var fixLength = 0
    var hasFix = false

    fun appendModifyFix(
        childFix: Delta?,
        formats: FormatChange = FormatChange.Unchanged,
        attribution: AttributionChange = AttributionChange.Unchanged,
    ) {
        if (childFix == null && formats == FormatChange.Unchanged && attribution == AttributionChange.Unchanged) {
            return
        }
        if (expectedIndex > fixLength) fix.retain(expectedIndex - fixLength)
        fix.modifyChanges(childFix ?: DeltaBuilder().done(), formats, attribution)
        fixLength = expectedIndex + 1
        hasFix = true
    }

    operations.forEach { op ->
        when (op) {
            is ChildOp.InsertText -> {
                val visibleIndex = renderedSequenceIndexToVisibleIndex(target, cursor, renderer, clampToEnd = true)
                if (target is YText) {
                    target.insert(visibleIndex, op.text, op.formats.mapValues { (_, value) -> value.toAny() })
                } else {
                    insertUnifiedText(target, visibleIndex, op.text)
                }
                cursor += op.text.length
                expectedIndex += op.text.length
            }
            is ChildOp.InsertValues -> {
                val values = op.values.map(DeltaValue::toAny)
                val visibleIndex = renderedSequenceIndexToVisibleIndex(target, cursor, renderer, clampToEnd = true)
                when (target) {
                    is YArray -> target.insert(visibleIndex, values)
                    is YText -> if (op.formats.isEmpty()) {
                        insertUnifiedValues(target, visibleIndex, op.values)
                    } else {
                        var insertIndex = visibleIndex
                        values.forEach { value ->
                            target.insertEmbed(
                                insertIndex++,
                                value,
                                op.formats.mapValues { (_, format) -> format.toAny() },
                                origin,
                            )
                        }
                    }
                    is YXmlElementType -> insertUnifiedValues(target, visibleIndex, op.values)
                    is YXmlFragment -> insertUnifiedValues(target, visibleIndex, op.values)
                    else -> error("type ${target.kind} cannot contain indexed children")
                }
                cursor += values.size
                expectedIndex += values.size
            }
            is ChildOp.Retain -> {
                applyRenderedFormats(target, cursor, op.length, op.formats, renderer)
                cursor += op.length
                expectedIndex += op.length
            }
            is ChildOp.Delete -> deleteRendered(target, cursor, op.length, renderer, transaction, origin)
            is ChildOp.Modify -> {
                val slot = renderedSequenceSlots(target, renderer)[cursor]
                val child = slot.value as AbstractYType
                val owner = child.doc.typeRefItemId(child)?.let(child.doc::getItem)?.toItemStruct(child.doc)
                val childFix = Type(child).applyDeltaWithCorrection(op.delta, origin, renderer)
                if (owner?.deleted == true && rendererContentLength(renderer, owner) > 0) {
                    appendModifyFix(
                        childFix,
                        inverseFormatChange(slot.formats, op.formats),
                        inverseAttributionChange(slot.attribution, op.attribution),
                    )
                } else {
                    applyRenderedFormats(target, cursor, 1, op.formats, renderer)
                    appendModifyFix(childFix)
                }
                cursor++
                expectedIndex++
            }
        }
    }
    return hasFix
}

private data class RenderedSequenceSlot(
    val value: Any?,
    val formats: Map<String, YValue>,
    val attribution: DeltaAttribution?,
)

private fun renderedSequenceSlots(
    target: AbstractYType,
    renderer: AbstractRenderer,
): List<RenderedSequenceSlot> = renderUnifiedSequenceContent(target, renderer).flatMap { rendered ->
    val formats = formatDataFromAny(rendered.formats)
    val attribution = rendered.attribution?.toDeltaAttribution()
    rendered.text?.map { char -> RenderedSequenceSlot(char.toString(), formats, attribution) }
        ?: rendered.values.map { value -> RenderedSequenceSlot(value, formats, attribution) }
}

private fun deleteRendered(
    target: AbstractYType,
    cursor: Int,
    length: Int,
    renderer: AbstractRenderer,
    transaction: YTransaction,
    origin: Any?,
) {
    if (length == 0) return
    when (target) {
        is YArray -> target.applyDelta(
            listOf(YArrayDeltaOp(retain = cursor), YArrayDeltaOp(delete = length)),
            origin,
            renderer,
        )
        is YText -> target.applyDelta(
            YTextDelta().retain(cursor).delete(length),
            origin,
            renderer,
        )
        is YXmlFragment -> target.applyDelta(
            listOf(YArrayDeltaOp(retain = cursor), YArrayDeltaOp(delete = length)),
            origin,
            renderer,
        )
        is YXmlElementType -> {
            recordRendererAttributedDeletes(transaction, target, cursor, length, renderer)
            val start = renderedSequenceIndexToVisibleIndex(target, cursor, renderer)
            val end = renderedSequenceIndexToVisibleIndex(target, cursor + length, renderer, clampToEnd = true)
            target.delete(start, end - start)
        }
        else -> error("type ${target.kind} cannot contain indexed children")
    }
}

private fun applyRenderedFormats(
    target: AbstractYType,
    cursor: Int,
    length: Int,
    change: FormatChange,
    renderer: AbstractRenderer,
) {
    if (change == FormatChange.Unchanged || length == 0) return
    val start = renderedSequenceIndexToVisibleIndex(target, cursor, renderer)
    val end = renderedSequenceIndexToVisibleIndex(target, cursor + length, renderer, clampToEnd = true)
    applyFormats(target, start, end - start, change)
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

private fun applyFormatChange(
    stored: Map<String, YValue>,
    update: FormatChange,
): Map<String, YValue> = when (update) {
    FormatChange.Unchanged -> stored
    FormatChange.Clear -> emptyMap()
    is FormatChange.Patch -> stored.toMutableMap().apply {
        update.values.forEach { (key, value) ->
            if (value == null || value == YValue.Null) remove(key) else put(key, value)
        }
    }.toSortedMap()
}

private fun diffFormats(
    current: Map<String, YValue>,
    target: Map<String, YValue>,
): FormatChange {
    if (current == target) return FormatChange.Unchanged
    val patch = (current.keys + target.keys).sorted().associateWith { key ->
        target[key].takeIf { value -> value != current[key] }
    }.filter { (key, value) -> value != null || key !in target }
    return if (patch.isEmpty()) FormatChange.Unchanged else FormatChange.Patch(patch)
}

private fun inverseFormatChange(
    stored: Map<String, YValue>,
    update: FormatChange,
): FormatChange = diffFormats(applyFormatChange(stored, update), stored)

private fun attributionValues(attribution: DeltaAttribution?): Map<String, YValue> =
    attribution?.values.orEmpty()

private fun applyAttributionChange(
    stored: DeltaAttribution?,
    update: AttributionChange,
): DeltaAttribution? = when (update) {
    AttributionChange.Unchanged -> stored
    AttributionChange.Clear -> null
    is AttributionChange.Patch -> mergeAttribution(stored?.values, update.values, resolve = true)
        .mapNotNull { (key, value) -> value?.let { key to it } }
        .toMap()
        .takeIf(Map<String, YValue>::isNotEmpty)
        ?.let(DeltaAttribution::of)
}

private fun diffAttribution(
    current: DeltaAttribution?,
    target: DeltaAttribution?,
): AttributionChange {
    val currentValues = attributionValues(current)
    val targetValues = attributionValues(target)
    if (currentValues == targetValues) return AttributionChange.Unchanged
    val patch = linkedMapOf<String, YValue?>()
    (currentValues.keys + targetValues.keys).sorted().forEach { key ->
        if (key == "format") {
            val currentFormats = (currentValues[key] as? YValue.MapValue)?.value.orEmpty()
            val targetFormats = (targetValues[key] as? YValue.MapValue)?.value.orEmpty()
            if (currentFormats != targetFormats) {
                val formatPatch = linkedMapOf<String, YValue>()
                (currentFormats.keys + targetFormats.keys).sorted().forEach { formatKey ->
                    if (currentFormats[formatKey] != targetFormats[formatKey]) {
                        formatPatch[formatKey] = targetFormats[formatKey] ?: YValue.Null
                    }
                }
                if (formatPatch.isNotEmpty()) patch[key] = YValue.MapValue(formatPatch)
            }
        } else if (currentValues[key] != targetValues[key]) {
            patch[key] = targetValues[key]
        }
    }
    return if (patch.isEmpty()) AttributionChange.Unchanged else AttributionChange.Patch(patch)
}

private fun inverseAttributionChange(
    stored: DeltaAttribution?,
    update: AttributionChange,
): AttributionChange = diffAttribution(applyAttributionChange(stored, update), stored)

private data class SettledDeltaSlot(
    val text: String?,
    val value: DeltaValue?,
    val formats: Map<String, YValue>,
    val attribution: DeltaAttribution?,
)

private fun Delta.settledSlots(): List<SettledDeltaSlot> = buildList {
    children.forEach { op ->
        when (op) {
            is ChildOp.InsertText -> op.text.forEach { char ->
                add(SettledDeltaSlot(char.toString(), null, op.formats, op.attribution))
            }
            is ChildOp.InsertValues -> op.values.forEach { value ->
                add(SettledDeltaSlot(null, value, op.formats, op.attribution))
            }
            is ChildOp.Delete,
            is ChildOp.Modify,
            is ChildOp.Retain -> error("inverse base must be a settled insert-only delta")
        }
    }
}

private fun DeltaBuilder.insertSettledSlot(slot: SettledDeltaSlot) {
    if (slot.text != null) {
        insert(slot.text, slot.formats, slot.attribution)
    } else {
        insertValues(listOf(checkNotNull(slot.value)), slot.formats, slot.attribution)
    }
}

private fun DeltaValue.sameSettledContent(other: DeltaValue): Boolean {
    val shared = sharedTypeOrNull()
    val otherShared = other.sharedTypeOrNull()
    return if (shared != null || otherShared != null) {
        shared != null && shared === otherShared
    } else {
        this == other
    }
}

private fun SettledDeltaSlot.sameContent(other: SettledDeltaSlot): Boolean = when {
    text != null || other.text != null -> text == other.text
    value != null && other.value != null -> value.sameSettledContent(other.value)
    else -> false
}

private fun DeltaBuilder.appendAlignedChange(
    before: SettledDeltaSlot,
    after: SettledDeltaSlot,
) {
    val formats = diffFormats(before.formats, after.formats)
    val attribution = diffAttribution(before.attribution, after.attribution)
    val beforeShared = before.value?.sharedTypeOrNull()
    val afterShared = after.value?.sharedTypeOrNull()
    if (beforeShared != null && beforeShared === afterShared) {
        val beforeDelta = before.value?.sharedDeltaOrNull() ?: DeltaBuilder().done()
        val afterDelta = after.value?.sharedDeltaOrNull() ?: DeltaBuilder().done()
        val nested = diffSettledDelta(beforeDelta, afterDelta)
        if (!nested.isEmpty) {
            modifyChanges(nested, formats, attribution)
            return
        }
    }
    retainChanges(1, formats, attribution)
}

/** Produce a valid, deterministic change between two settled deep snapshots. */
private fun diffSettledDelta(before: Delta, after: Delta): Delta {
    val change = DeltaBuilder(if (before.name == after.name) before.name else null)
    (before.attributes.keys + after.attributes.keys).sorted().forEach { key ->
        val old = before.attributes[key] as? AttributeOp.Set
        val next = after.attributes[key] as? AttributeOp.Set
        when {
            next == null && old != null -> change.deleteAttr(key)
            old == null && next != null -> change.setAttr(key, next.value, next.attribution)
            old != null && next != null -> {
                val oldShared = old.value.sharedTypeOrNull()
                val nextShared = next.value.sharedTypeOrNull()
                if (oldShared != null && oldShared === nextShared) {
                    val nested = diffSettledDelta(
                        old.value.sharedDeltaOrNull() ?: DeltaBuilder().done(),
                        next.value.sharedDeltaOrNull() ?: DeltaBuilder().done(),
                    )
                    val attribution = diffAttribution(old.attribution, next.attribution)
                    if (!nested.isEmpty || attribution != AttributionChange.Unchanged) {
                        change.modifyAttr(key, nested, attribution)
                    }
                } else if (old.value != next.value || old.attribution != next.attribution) {
                    change.setAttr(key, next.value, next.attribution)
                }
            }
        }
    }

    val oldSlots = before.settledSlots()
    val newSlots = after.settledSlots()
    var prefix = 0
    while (prefix < oldSlots.size && prefix < newSlots.size && oldSlots[prefix].sameContent(newSlots[prefix])) {
        prefix++
    }
    var suffix = 0
    while (
        suffix < oldSlots.size - prefix &&
        suffix < newSlots.size - prefix &&
        oldSlots[oldSlots.lastIndex - suffix].sameContent(newSlots[newSlots.lastIndex - suffix])
    ) {
        suffix++
    }

    repeat(prefix) { index -> change.appendAlignedChange(oldSlots[index], newSlots[index]) }
    val oldMiddleLength = oldSlots.size - prefix - suffix
    if (oldMiddleLength > 0) change.delete(oldMiddleLength)
    val newMiddleEnd = newSlots.size - suffix
    for (index in prefix until newMiddleEnd) change.insertSettledSlot(newSlots[index])
    for (offset in 0 until suffix) {
        change.appendAlignedChange(
            oldSlots[oldSlots.size - suffix + offset],
            newSlots[newSlots.size - suffix + offset],
        )
    }
    return change.done()
}

/** Port of lib0/delta inverse for the typed subset represented by this adapter. */
private fun inverseDelta(
    change: Delta,
    base: Delta,
    renderer: AbstractRenderer,
): Delta {
    val inverse = DeltaBuilder(if (change.name == base.name) change.name else null)

    change.attributes.forEach { (key, op) ->
        val baseOp = base.attributes[key] as? AttributeOp.Set
        val baseShared = baseOp?.value?.sharedTypeOrNull()
        when {
            op is AttributeOp.Modify && baseOp != null && baseShared != null -> {
                val nestedBase = baseOp.value.sharedDeltaOrNull() ?: Type(baseShared).toDelta(renderer)
                inverse.modifyAttr(
                    key,
                    inverseDelta(op.delta, nestedBase, renderer),
                    inverseAttributionChange(baseOp.attribution, op.attribution),
                )
            }
            baseOp != null -> inverse.setAttr(key, baseOp.value, baseOp.attribution)
            op !== AttributeOp.Delete && op !is AttributeOp.DeleteAttributed -> inverse.deleteAttr(key)
        }
    }

    val baseSlots = base.settledSlots()
    var baseIndex = 0
    change.children.forEach { op ->
        when (op) {
            is ChildOp.InsertText -> inverse.delete(op.text.length)
            is ChildOp.InsertValues -> inverse.delete(op.values.size)
            is ChildOp.Retain -> repeat(op.length) {
                val slot = baseSlots.getOrNull(baseIndex++)
                if (slot == null) {
                    inverse.retain(1)
                } else {
                    inverse.retainChanges(
                        1,
                        inverseFormatChange(slot.formats, op.formats),
                        inverseAttributionChange(slot.attribution, op.attribution),
                    )
                }
            }
            is ChildOp.Modify -> {
                val slot = baseSlots.getOrNull(baseIndex++)
                val shared = slot?.value?.sharedTypeOrNull()
                if (slot == null || shared == null) {
                    inverse.retain(1)
                } else {
                    inverse.modifyChanges(
                        inverseDelta(
                            op.delta,
                            slot.value?.sharedDeltaOrNull() ?: Type(shared).toDelta(renderer),
                            renderer,
                        ),
                        inverseFormatChange(slot.formats, op.formats),
                        inverseAttributionChange(slot.attribution, op.attribution),
                    )
                }
            }
            is ChildOp.Delete -> repeat(op.length) {
                baseSlots.getOrNull(baseIndex++)?.let(inverse::insertSettledSlot)
            }
        }
    }
    return inverse.done()
}
