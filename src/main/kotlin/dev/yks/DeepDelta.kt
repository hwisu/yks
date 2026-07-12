package dev.yks

sealed interface YDeepDelta {
    val attrs: Map<String, Any?>
}

data class YAttributeDelta(
    val value: Any?,
    val attributes: Map<String, Any?> = emptyMap(),
)

data class YDocDeepDelta(
    val roots: Map<String, YDeepDelta> = emptyMap(),
)

data class YArrayDeepDelta(
    override val attrs: Map<String, Any?> = emptyMap(),
    val delta: List<YArrayDeltaOp> = emptyList(),
) : YDeepDelta

data class YTextDeepDelta(
    override val attrs: Map<String, Any?> = emptyMap(),
    val delta: YTextDelta = YTextDelta(),
) : YDeepDelta

data class YMapDeepDelta(
    override val attrs: Map<String, Any?> = emptyMap(),
) : YDeepDelta

data class YXmlFragmentDeepDelta(
    override val attrs: Map<String, Any?> = emptyMap(),
    val delta: List<YArrayDeltaOp> = emptyList(),
) : YDeepDelta

data class YXmlElementDeepDelta(
    val nodeName: String,
    override val attrs: Map<String, Any?> = emptyMap(),
    val children: List<Any?> = emptyList(),
) : YDeepDelta

internal fun YDeepDelta.isEmptyDeepDelta(): Boolean = when (this) {
    is YArrayDeepDelta -> attrs.isEmpty() && delta.isEmpty()
    is YTextDeepDelta -> attrs.isEmpty() && delta.ops.isEmpty()
    is YMapDeepDelta -> attrs.isEmpty()
    is YXmlFragmentDeepDelta -> attrs.isEmpty() && delta.isEmpty()
    is YXmlElementDeepDelta -> attrs.isEmpty() && children.isEmpty()
}

internal data class DeepDeltaRenderOptions(
    val renderer: AbstractRenderer = baseRenderer,
    val itemsToRender: IdSet? = null,
    val retainInserts: Boolean = false,
    val retainDeletes: Boolean = false,
    val insertedItems: IdSet? = null,
    val modified: Map<AbstractYType, Set<String?>>? = null,
)

fun YDoc.toDeltaDeep(renderer: AbstractRenderer = baseRenderer): YDocDeepDelta =
    YDocDeepDelta(
        roots = rootNames().mapNotNull { name ->
            val type = rootType(name) ?: return@mapNotNull null
            name to type.renderDeepDelta(DeepDeltaRenderOptions(renderer = renderer))
        }.toMap().toSortedMap(),
    )

fun YDoc.applyDeltaDeep(delta: YDocDeepDelta, origin: Any? = null) {
    transact(origin = origin) {
        delta.roots.toSortedMap().forEach { (name, rootDelta) ->
            when (rootDelta) {
                is YArrayDeepDelta -> getArray(name).applyDeltaDeep(rootDelta)
                is YTextDeepDelta -> getText(name).applyDeltaDeep(rootDelta)
                is YMapDeepDelta -> getMap(name).applyDeltaDeep(rootDelta)
                is YXmlFragmentDeepDelta -> getXmlFragment(name).applyDeltaDeep(rootDelta)
                is YXmlElementDeepDelta -> error("XML element deep deltas cannot be document roots")
            }
        }
    }
}

fun diffDocsToDelta(
    previous: YDoc,
    next: YDoc,
    renderer: AbstractRenderer = createDiffRenderer(previous, next),
): YDocDeepDelta {
    val insertDiff = diffIdSet(
        createInsertSetFromDoc(next, filterDeleted = false),
        createInsertSetFromDoc(previous, filterDeleted = false),
    )
    val deleteDiff = diffIdSet(createDeleteSetFromDoc(next), createDeleteSetFromDoc(previous))
    val insertsOnly = diffIdSet(insertDiff, deleteDiff)
    val deletesOnly = diffIdSet(deleteDiff, insertDiff)
    val itemsToRender = mergeIdSets(listOf(insertsOnly, deleteDiff))
    val changedTypes = computeModifiedFromItems(next, itemsToRender)
    val options = DeepDeltaRenderOptions(
        renderer = renderer,
        itemsToRender = itemsToRender,
        retainDeletes = true,
        modified = changedTypes,
    )
    return YDocDeepDelta(
        roots = next.rootNames().mapNotNull { name ->
            val type = next.rootType(name) ?: return@mapNotNull null
            if (changedTypes[type] == null) return@mapNotNull null
            name to type.renderDeepDelta(options)
        }
            .toMap()
            .toSortedMap(),
    )
}

internal fun AbstractYType.renderDeepDelta(): YDeepDelta =
    renderDeepDelta(DeepDeltaRenderOptions(renderer = activeRenderer))

internal fun AbstractYType.renderDeepDelta(options: DeepDeltaRenderOptions): YDeepDelta = when (this) {
    is YUnopenedRoot -> error("open root '$name' with a concrete getter before rendering a deep delta")
    is YArray -> renderArrayDeepDelta(this, options)
    is YText -> renderTextDeepDelta(this, options)
    is YMap -> renderMapDeepDelta(this, options)
    is YXmlFragment -> renderXmlFragmentDeepDelta(this, options)
    is YXmlElementType -> renderXmlElementDeepDelta(this, options)
}

internal fun renderArrayDeepDelta(type: YArray, options: DeepDeltaRenderOptions): YArrayDeepDelta =
    YArrayDeepDelta(
        attrs = renderTypeAttrs(type, options),
        delta = renderSequenceDelta(type, RootKind.Array, options) { rendered ->
            DeepDeltaInsertValue(
                type.doc.arrayItemValue(rendered.item)
                    .toDeepDeltaValue(options.nestedValueOptions(rendered.action)),
            )
        },
    )

internal fun renderTextDeepDelta(type: YText, options: DeepDeltaRenderOptions): YTextDeepDelta {
    val delta = YTextDelta()
    val formatAttributions = textFormatAttributionsByTarget(type, options)
    renderSequenceItems(type, type.kind, options).forEach { rendered ->
        val length = rendered.content.content.getLength().toInt()
        val attribution = createAttributionFromAttributionItems(rendered.content.attrs, rendered.content.deleted)
            .orEmpty()
            .mergeTextAttribution(formatAttributions[rendered.item.id].orEmpty())
        when (rendered.action) {
            RenderedDeltaAction.Insert -> {
                val attributes = rendered.item.content.textAttributesForDeepDelta(type.doc, options) + attribution
                when (val content = rendered.item.content) {
                    is ItemContent.Text -> delta.insert(content.value, attributes)
                    is ItemContent.TextEmbed -> {
                        delta.insertEmbed(
                            type.doc.valueToAny(content.value).toDeepDeltaValue(
                                options.nestedValueOptions(rendered.action),
                            ),
                            attributes,
                        )
                    }
                    is ItemContent.XmlType -> {
                        delta.insertEmbed(
                            type.doc.typeFromXmlType(content).toDeepDeltaValue(
                                options.nestedValueOptions(rendered.action),
                            ),
                            attributes,
                        )
                    }
                    else -> Unit
                }
            }
            RenderedDeltaAction.Retain -> {
                val attributes = rendered.item.content.textRetainAttributesForDeepDelta(
                    doc = type.doc,
                    hasTextFormatChange = options.hasTextFormatChange(type),
                    options = options,
                    attribution = attribution,
                    renderHasAttribution = rendered.content.deleted || rendered.content.attrs != null,
                )
                delta.retain(length, attributes)
            }
            RenderedDeltaAction.Delete -> delta.delete(length)
            RenderedDeltaAction.Skip -> Unit
        }
    }
    return YTextDeepDelta(
        attrs = renderTypeAttrs(type, options),
        delta = delta,
    )
}

private fun textFormatAttributionsByTarget(
    type: YText,
    options: DeepDeltaRenderOptions,
): Map<Id, Map<String, Any?>> {
    val textItems = type.doc.sequence(type.name)
        .filter { item ->
            item.content.kind == type.kind &&
                (item.content is ItemContent.Text ||
                    item.content is ItemContent.TextEmbed ||
                    item.content is ItemContent.XmlType)
        }
    if (textItems.isEmpty()) return emptyMap()
    val attributionsByTarget = linkedMapOf<Id, Map<String, Any?>>()

    type.doc.sequence(type.name)
        .filter { item -> item.content.kind == type.kind && item.content is ItemContent.TextFormat }
        .forEach { item ->
            val format = item.content as ItemContent.TextFormat
            val start = textItems.indexOfFirst { textItem -> textItem.id == format.target }
            if (start < 0) return@forEach
            val renderedFormatContents = item.readRenderedContents(type.doc, options)
            val end = (start + format.length.toInt()).coerceAtMost(textItems.size)
            for (index in start until end) {
                val changedKeys = format.changedKeysAt(index - start)
                if (changedKeys.isEmpty()) continue
                val renderedFormatAttribution = renderedFormatContents
                    .map { content -> format.formatAttribution(changedKeys, content.attrs, content.deleted) }
                    .fold(emptyMap<String, Any?>()) { merged, attribution ->
                        merged.mergeTextAttribution(attribution)
                    }
                if (renderedFormatAttribution.isEmpty()) continue
                val target = textItems[index].id
                attributionsByTarget[target] = attributionsByTarget[target]
                    .orEmpty()
                    .mergeTextAttribution(renderedFormatAttribution)
            }
        }

    val activeNativeAttributions = linkedMapOf<String, Map<String, Any?>>()
    type.doc.sequence(type.name).forEach { item ->
        if (item.content.kind != type.kind) return@forEach
        when (val content = item.content) {
            is ItemContent.NativeTextFormat -> {
                val attribution = if (content.value == YValue.Null) {
                    emptyMap()
                } else {
                    item.readRenderedContents(type.doc, options)
                        .map { rendered -> nativeFormatAttribution(content.key, rendered.attrs, rendered.deleted) }
                        .fold(emptyMap<String, Any?>()) { merged, value -> merged.mergeTextAttribution(value) }
                }
                activeNativeAttributions[content.key] = attribution
            }
            is ItemContent.Text,
            is ItemContent.TextEmbed,
            is ItemContent.XmlType -> {
                val attribution = activeNativeAttributions.values.fold(emptyMap<String, Any?>()) { merged, value ->
                    merged.mergeTextAttribution(value)
                }
                if (attribution.isNotEmpty()) {
                    attributionsByTarget[item.id] = attributionsByTarget[item.id]
                        .orEmpty()
                        .mergeTextAttribution(attribution)
                }
            }
            else -> Unit
        }
    }
    return attributionsByTarget
}

private fun nativeFormatAttribution(
    key: String,
    attrs: List<ContentAttribute>?,
    deleted: Boolean,
): Map<String, Any?> {
    if (attrs == null) return emptyMap()
    val by = attrs.mapNotNull { attr ->
        when (attr.name) {
            "insert" -> if (!deleted) attr.`val` else null
            "delete" -> if (deleted) attr.`val` else null
            else -> null
        }
    }
    return mapOf("format" to mapOf(key to by))
}

private fun ItemContent.TextFormat.formatAttribution(
    keys: Set<String>,
    attrs: List<ContentAttribute>?,
    deleted: Boolean,
): Map<String, Any?> {
    if (attrs == null) return emptyMap()
    if (keys.isEmpty()) return emptyMap()
    val by = mutableListOf<Any?>()
    attrs.forEach { attr ->
        when (attr.name) {
            "insert" -> if (!deleted) by.add(attr.`val`)
            "delete" -> if (deleted) by.add(attr.`val`)
        }
    }
    return mapOf(
        "format" to keys.sorted().associateWith { by.toList() },
    )
}

private fun ItemContent.TextFormat.changedKeysAt(offset: Int): Set<String> {
    val before = beforeAttributes.getOrNull(offset) ?: return attributes.keys
    return attributes.keys.filterTo(sortedSetOf()) { key ->
        (before[key] ?: YValue.Null) != (attributes[key] ?: YValue.Null)
    }
}

private fun Map<String, Any?>.mergeTextAttribution(other: Map<String, Any?>): Map<String, Any?> {
    if (isEmpty()) return other
    if (other.isEmpty()) return this
    val merged = toMutableMap()
    other.forEach { (key, value) ->
        if (key == "format") {
            val currentFormat = merged[key] as? Map<*, *>
            val nextFormat = value as? Map<*, *>
            if (currentFormat != null && nextFormat != null) {
                merged[key] = mergeFormatAttribution(currentFormat, nextFormat)
            } else {
                merged[key] = value
            }
        } else {
            merged[key] = value
        }
    }
    return merged.toSortedMap()
}

private fun mergeFormatAttribution(current: Map<*, *>, next: Map<*, *>): Map<String, Any?> =
    (current.keys + next.keys)
        .filterIsInstance<String>()
        .sorted()
        .associateWith { key ->
            val currentValues = current[key] as? List<*> ?: emptyList<Any?>()
            val nextValues = next[key] as? List<*> ?: emptyList<Any?>()
            (currentValues + nextValues).distinct()
        }

internal fun renderMapDeepDelta(type: YMap, options: DeepDeltaRenderOptions): YMapDeepDelta =
    YMapDeepDelta(attrs = renderTypeAttrs(type, options))

internal fun renderXmlFragmentDeepDelta(
    type: YXmlFragment,
    options: DeepDeltaRenderOptions,
): YXmlFragmentDeepDelta =
    YXmlFragmentDeepDelta(
        attrs = renderTypeAttrs(type, options),
        delta = renderSequenceDelta(type, RootKind.XmlFragment, options) { rendered ->
            rendered.item.content.toXmlDeepDeltaInsertValue(type.doc, options.nestedValueOptions(rendered.action))
        },
    )

internal fun renderXmlElementDeepDelta(
    type: YXmlElementType,
    options: DeepDeltaRenderOptions,
): YXmlElementDeepDelta =
    YXmlElementDeepDelta(
        nodeName = type.nodeName,
        attrs = renderTypeAttrs(type, options),
        children = renderXmlElementChildrenDeepDelta(type, options),
    )

private fun renderXmlElementChildrenDeepDelta(
    type: YXmlElementType,
    options: DeepDeltaRenderOptions,
): List<Any?> =
    buildList {
        renderSequenceItems(type, type.kind, options).forEach { rendered ->
            when (rendered.action) {
                RenderedDeltaAction.Insert -> add(
                    rendered.item.content.toXmlElementDeepDeltaChildValue(
                        type.doc,
                        options.nestedValueOptions(rendered.action),
                    ),
                )
                RenderedDeltaAction.Retain -> rendered.item.content
                    .modifiedXmlChildDeepDeltaValue(type.doc, options)
                    ?.let(::add)
                RenderedDeltaAction.Delete,
                RenderedDeltaAction.Skip -> Unit
            }
        }
    }

internal fun Any?.toDeepDeltaValue(): Any? = toDeepDeltaValue(DeepDeltaRenderOptions())

internal fun Any?.toDeepDeltaValue(options: DeepDeltaRenderOptions): Any? = when (this) {
    is YAttributeDelta -> copy(
        value = value.toDeepDeltaValue(options),
        attributes = attributes.mapValues { (_, value) -> value.toDeepDeltaValue(options) }.toSortedMap(),
    )
    is YArray -> renderArrayDeepDelta(this, options)
    is YText -> renderTextDeepDelta(this, options)
    is YMap -> renderMapDeepDelta(this, options)
    is YXmlFragment -> renderXmlFragmentDeepDelta(this, options)
    is YXmlElementType -> renderXmlElementDeepDelta(this, options)
    is YXmlElement -> toDeltaDeep()
    is YXmlText -> toJson()
    is List<*> -> map { it.toDeepDeltaValue(options) }
    is Array<*> -> map { it.toDeepDeltaValue(options) }
    is Map<*, *> -> entries.associate { (key, value) ->
        require(key is String) { "YValue map keys must be strings" }
        key to value.toDeepDeltaValue(options)
    }.toSortedMap()
    is ByteArray -> copyOf()
    else -> this
}

private fun ItemContent.toXmlDeepDeltaValue(doc: YDoc, options: DeepDeltaRenderOptions): Any? = when (this) {
    is ItemContent.XmlNode -> value.toNode().toDeepDeltaValue(options)
    is ItemContent.XmlType -> when (val type = doc.typeFromXmlType(this)) {
        is YText -> renderTextDeepDelta(type, options)
        is YXmlElementType -> renderXmlElementDeepDelta(type, options)
        else -> type.toDeepDeltaValue(options)
    }
    else -> error("item content is not an XML sequence child: ${this::class.simpleName}")
}

internal fun YXmlNode.toXmlElementDeepDeltaChildValue(
    options: DeepDeltaRenderOptions = DeepDeltaRenderOptions(),
): Any? = when (this) {
    is YXmlSnapshotText -> clone()
    is YXmlText -> {
        val text = toJson()
        val deepAttrs = deepDeltaTextAttributes(options)
        if (deepAttrs.isEmpty()) text else YAttributeDelta(text, deepAttrs)
    }
    is YXmlElement -> toDeltaDeep()
}

private fun ItemContent.toXmlElementDeepDeltaChildValue(
    doc: YDoc,
    options: DeepDeltaRenderOptions,
): Any? = when (this) {
    is ItemContent.XmlNode -> value.toNode().toXmlElementDeepDeltaChildValue(options)
    is ItemContent.XmlType -> toXmlDeepDeltaValue(doc, options)
    else -> error("item content is not an XML sequence child: ${this::class.simpleName}")
}

private fun ItemContent.modifiedXmlChildDeepDeltaValue(doc: YDoc, options: DeepDeltaRenderOptions): Any? {
    val modified = options.modified ?: return null
    if (this !is ItemContent.XmlType) return null
    val type = doc.typeFromXmlType(this)
    if (modified[type] == null) return null
    return toXmlDeepDeltaValue(doc, options)
}

private enum class RenderedDeltaAction {
    Insert,
    Retain,
    Delete,
    Skip,
}

private fun renderTypeAttrs(type: AbstractYType, options: DeepDeltaRenderOptions): Map<String, Any?> {
    val visibleAttrs = when (type) {
        is YUnopenedRoot -> error("open root '${type.name}' before rendering attributes")
        is YArray -> type.getAttrs()
        is YText -> type.getAttrs()
        is YMap -> type.toMap()
        is YXmlFragment -> type.getAttrs()
        is YXmlElementType -> type.getAttrs()
    }
    val modifiedKeys = options.modified?.get(type)?.filterNotNull()?.toSortedSet()
    val keys = modifiedKeys ?: (visibleAttrs.keys + type.doc.renderableMapKeys(type.name, options)).toSortedSet()
    return keys
        .mapNotNull { key ->
            renderTypeAttr(type, key, visibleAttrs[key], visibleAttrs.containsKey(key), options)?.let { key to it }
        }
        .toMap()
        .toSortedMap()
}

private fun renderTypeAttr(
    type: AbstractYType,
    key: String,
    visibleValue: Any?,
    hasVisibleValue: Boolean,
    options: DeepDeltaRenderOptions,
): Any? {
    val rendered = type.doc.mapItemOrder(type.name, key)
        .mapNotNull { item ->
            val value = when (val content = item.content) {
                is ItemContent.MapEntry -> type.doc.valueToAny(content.value)
                is ItemContent.XmlType -> type.doc.typeFromXmlType(content)
                else -> return@mapNotNull null
            }
            val renderedContent = item.readRenderedContents(type.doc, options).lastOrNull()
                ?: return@mapNotNull null
            if (renderedContent.deleted && renderedContent.attrs == null) return@mapNotNull null
            val attribution = createAttributionFromAttributionItems(
                renderedContent.attrs,
                renderedContent.deleted,
            ).orEmpty()
            value.toDeepDeltaValue(options) to attribution
        }
        .lastOrNull()

    if (rendered != null) {
        val (value, attribution) = rendered
        return if (attribution.isEmpty()) value else YAttributeDelta(value, attribution.toSortedMap())
    }
    return if (hasVisibleValue) visibleValue.toDeepDeltaValue(options) else null
}

private fun YDoc.renderableMapKeys(parent: String, options: DeepDeltaRenderOptions): Set<String> =
    mapItemKeys(parent)
        .filter { key ->
            mapItemOrder(parent, key).any { item -> options.renderer.hasItem(item.toItemStruct(this)) }
        }
        .toSortedSet()

private data class RenderedSequenceItem(
    val item: StoreItem,
    val content: AttributedContent,
    val action: RenderedDeltaAction,
)

private data class DeepDeltaInsertValue(
    val value: Any?,
    val attributes: Map<String, Any?> = emptyMap(),
)

private fun renderSequenceDelta(
    type: AbstractYType,
    kind: RootKind,
    options: DeepDeltaRenderOptions,
    value: (RenderedSequenceItem) -> DeepDeltaInsertValue,
): List<YArrayDeltaOp> {
    val delta = mutableListOf<YArrayDeltaOp>()
    renderSequenceItems(type, kind, options).forEach { rendered ->
        val length = rendered.content.content.getLength().toInt()
        when (rendered.action) {
            RenderedDeltaAction.Insert -> {
                val insert = value(rendered)
                delta.appendInsert(insert.value, (insert.attributes + rendered.attribution()).toSortedMap())
            }
            RenderedDeltaAction.Retain -> delta.appendRetain(length, rendered.attribution())
            RenderedDeltaAction.Delete -> delta.appendDelete(length, rendered.attribution())
            RenderedDeltaAction.Skip -> Unit
        }
    }
    return delta
}

private fun renderSequenceItems(
    type: AbstractYType,
    kind: RootKind,
    options: DeepDeltaRenderOptions,
): List<RenderedSequenceItem> =
    type.doc.sequence(type.name)
        .filter { item -> item.content.kind == kind && item.countable }
        .flatMap { item ->
            item.readRenderedContents(type.doc, options).map { content ->
                RenderedSequenceItem(item, content, content.renderedAction(options))
            }
        }

private fun StoreItem.readRenderedContents(
    doc: YDoc,
    options: DeepDeltaRenderOptions,
): List<AttributedContent> {
    val rendered = mutableListOf<AttributedContent>()
    val item = toItemStruct(doc)
    val itemsToRender = options.itemsToRender
    if (!options.renderer.hasItem(item)) {
        if (itemsToRender == null) {
            baseRenderer.readContent(rendered, id.client, id.clock, deleted, item.content, renderBehavior = 1)
            return rendered
        }
        val ranges = itemsToRender.slice(id.client, id.clock, item.length)
        var remaining = if (ranges.size == 1) item.content else item.content.copy()
        ranges.forEachIndexed { index, range ->
            val current = remaining
            if (index != ranges.lastIndex && range.len < current.getLength()) {
                remaining = current.splice(range.len)
            }
            baseRenderer.readContent(
                rendered,
                id.client,
                range.clock,
                deleted,
                current,
                renderBehavior = if (range.exists) 2 else 0,
            )
        }
        return rendered
    }
    if (itemsToRender == null) {
        options.renderer.readContent(rendered, id.client, id.clock, deleted, item.content, renderBehavior = 1)
        return rendered
    }

    val ranges = itemsToRender.slice(id.client, id.clock, item.length)
    var remaining = if (ranges.size == 1) item.content else item.content.copy()
    ranges.forEachIndexed { index, range ->
        val current = remaining
        if (index != ranges.lastIndex && range.len < current.getLength()) {
            remaining = current.splice(range.len)
        }
        if (!deleted || options.insertedItems == null || !range.exists) {
            options.renderer.readContent(
                rendered,
                id.client,
                range.clock,
                deleted,
                current,
                renderBehavior = if (range.exists) 2 else 0,
            )
        } else {
            val freshRanges = options.insertedItems.slice(id.client, range.clock, range.len)
            var freshRemaining = if (freshRanges.size == 1) current else current.copy()
            freshRanges.forEachIndexed { freshIndex, freshRange ->
                val freshCurrent = freshRemaining
                if (freshIndex != freshRanges.lastIndex && freshRange.len < freshCurrent.getLength()) {
                    freshRemaining = freshCurrent.splice(freshRange.len)
                }
                options.renderer.readContent(
                    rendered,
                    id.client,
                    freshRange.clock,
                    deleted,
                    freshCurrent,
                    renderBehavior = if (freshRange.exists) 3 else 2,
                )
            }
        }
    }
    return rendered
}

private fun AttributedContent.renderedAction(options: DeepDeltaRenderOptions): RenderedDeltaAction {
    val renderContent = render && (!deleted || attrs != null)
    val renderDelete = render && deleted
    val retainContent = !render && (!deleted || attrs != null)
    return when {
        renderContent -> if (
            (!deleted && options.retainInserts) ||
            (deleted && options.retainDeletes && !fresh)
        ) {
            RenderedDeltaAction.Retain
        } else {
            RenderedDeltaAction.Insert
        }
        renderDelete -> RenderedDeltaAction.Delete
        retainContent -> RenderedDeltaAction.Retain
        else -> RenderedDeltaAction.Skip
    }
}

private fun DeepDeltaRenderOptions.nestedValueOptions(action: RenderedDeltaAction): DeepDeltaRenderOptions =
    if (action == RenderedDeltaAction.Insert) {
        copy(
            itemsToRender = null,
            retainInserts = false,
            retainDeletes = false,
            insertedItems = null,
            modified = null,
        )
    } else {
        this
    }

private fun ItemContent.textAttributesForDeepDelta(
    doc: YDoc,
    options: DeepDeltaRenderOptions,
): Map<String, Any?> = when (this) {
    is ItemContent.Text -> attributes
    is ItemContent.TextEmbed -> attributes
    is ItemContent.XmlType -> attributes
    else -> emptyMap()
}.mapValues { (_, value) -> doc.valueToAny(value).toDeepDeltaValue(options) }.toSortedMap()

private fun ItemContent.TextFormat.formatAttributesForDeepDelta(doc: YDoc): Map<String, Any?> =
    attributes.mapValues { (_, value) -> doc.valueToAny(value) }.toSortedMap()

private fun ItemContent.textRetainAttributesForDeepDelta(
    doc: YDoc,
    hasTextFormatChange: Boolean,
    options: DeepDeltaRenderOptions,
    attribution: Map<String, Any?>,
    renderHasAttribution: Boolean,
): Map<String, Any?> = when (this) {
    is ItemContent.Text -> {
        val formatAttributes = if (hasTextFormatChange) {
            textAttributeDiffForDeepDelta(doc, baseAttributes, attributes, options)
        } else {
            emptyMap()
        }
        formatAttributes + attribution
    }
    is ItemContent.TextEmbed -> {
        val formatAttributes = if (hasTextFormatChange) {
            textAttributeDiffForDeepDelta(doc, baseAttributes, attributes, options)
        } else {
            emptyMap()
        }
        formatAttributes + attribution
    }
    is ItemContent.XmlType -> {
        val formatAttributes = if (hasTextFormatChange) {
            textAttributeDiffForDeepDelta(doc, baseAttributes, attributes, options)
        } else {
            emptyMap()
        }
        formatAttributes + attribution
    }
    is ItemContent.TextFormat -> formatAttributesForDeepDelta(doc) + attribution
    else -> if (renderHasAttribution) attribution else emptyMap()
}.toSortedMap()

private fun DeepDeltaRenderOptions.hasTextFormatChange(type: YText): Boolean {
    val items = itemsToRender ?: return false
    return type.doc.sequence(type.name).any { item ->
        (item.content is ItemContent.TextFormat || item.content is ItemContent.NativeTextFormat) &&
            items.intersects(item.id, item.length)
    }
}

private fun textAttributeDiffForDeepDelta(
    doc: YDoc,
    before: Map<String, YValue>,
    after: Map<String, YValue>,
    options: DeepDeltaRenderOptions,
): Map<String, Any?> =
    (before.keys + after.keys).sorted().mapNotNull { key ->
        val beforeValue = before[key]
        val afterValue = after[key]
        if (beforeValue == afterValue) {
            null
        } else {
            key to afterValue?.let { value -> doc.valueToAny(value).toDeepDeltaValue(options) }
        }
    }.toMap()

private fun RenderedSequenceItem.attribution(): Map<String, Any?> =
    createAttributionFromAttributionItems(content.attrs, content.deleted).orEmpty()

private fun ItemContent.toXmlDeepDeltaInsertValue(
    doc: YDoc,
    options: DeepDeltaRenderOptions,
): DeepDeltaInsertValue = when (this) {
    is ItemContent.XmlNode -> {
        val node = value.toNode()
        DeepDeltaInsertValue(
            value = node.toDeepDeltaValue(options),
            attributes = (node as? YXmlText)?.deepDeltaTextAttributes(options).orEmpty(),
        )
    }
    is ItemContent.XmlType -> DeepDeltaInsertValue(
        value = when (val type = doc.typeFromXmlType(this)) {
            is YText -> renderTextDeepDelta(type, options)
            is YXmlElementType -> renderXmlElementDeepDelta(type, options)
            else -> type.toDeepDeltaValue(options)
        },
    )
    else -> error("item content is not an XML sequence child: ${this::class.simpleName}")
}

private fun YXmlText.deepDeltaTextAttributes(options: DeepDeltaRenderOptions): Map<String, Any?> =
    attributes.mapValues { (_, value) -> value.toDeepDeltaValue(options) }.toSortedMap()

private fun MutableList<YArrayDeltaOp>.appendInsert(value: Any?, attributes: Map<String, Any?> = emptyMap()) {
    val last = lastOrNull()
    if (last?.insert != null && last.attributes == attributes) {
        this[lastIndex] = last.copy(insert = last.insert + value)
    } else {
        add(YArrayDeltaOp(insert = listOf(value), attributes = attributes))
    }
}

private fun MutableList<YArrayDeltaOp>.appendRetain(length: Int, attributes: Map<String, Any?> = emptyMap()) {
    if (length <= 0) return
    val last = lastOrNull()
    if (last?.retain != null && last.attributes == attributes) {
        this[lastIndex] = last.copy(retain = last.retain + length)
    } else {
        add(YArrayDeltaOp(retain = length, attributes = attributes))
    }
}

private fun MutableList<YArrayDeltaOp>.appendDelete(length: Int, attributes: Map<String, Any?> = emptyMap()) {
    if (length <= 0) return
    val last = lastOrNull()
    if (last?.delete != null && last.attributes == attributes) {
        this[lastIndex] = last.copy(delete = last.delete + length)
    } else {
        add(YArrayDeltaOp(delete = length, attributes = attributes))
    }
}

internal fun List<YArrayDeltaOp>.toDeepDeltaValues(
    options: DeepDeltaRenderOptions = DeepDeltaRenderOptions(),
): List<YArrayDeltaOp> =
    map { op ->
        op.copy(
            insert = op.insert?.map { value -> value.toDeepDeltaValue(options) },
            attributes = op.attributes.mapValues { (_, value) -> value.toDeepDeltaValue(options) }.toSortedMap(),
        )
    }

internal fun YTextDelta.toDeepDeltaValues(
    options: DeepDeltaRenderOptions = DeepDeltaRenderOptions(),
): YTextDelta =
    YTextDelta(ops.map { op ->
        op.copy(
            insert = when (val insert = op.insert) {
                is String,
                null -> insert
                else -> insert.toDeepDeltaValue(options)
            },
            attributes = op.attributes.mapValues { (_, value) -> value.toDeepDeltaValue(options) }.toSortedMap(),
        )
    })

internal fun YMapDelta.toDeepDeltaValues(
    options: DeepDeltaRenderOptions = DeepDeltaRenderOptions(),
): YMapDelta =
    YMapDelta().also { deep ->
        ops.toSortedMap().forEach { (key, op) ->
            when (op.action) {
                YMapDeltaAction.Set -> deep.setAttr(
                    key,
                    op.value.toDeepDeltaValue(options),
                    op.previousValue.toDeepDeltaValue(options),
                )
                YMapDeltaAction.Delete -> deep.deleteAttr(key, op.previousValue.toDeepDeltaValue(options))
            }
        }
    }

internal fun Any?.fromDeepDeltaValue(doc: YDoc): Any? = when (this) {
    is YAttributeDelta -> value.fromDeepDeltaValue(doc)
    is YArrayDeepDelta -> doc.createArray().also { it.applyDeltaDeep(this) }
    is YTextDeepDelta -> doc.createText().also { it.applyDeltaDeep(this) }
    is YMapDeepDelta -> doc.createMap().also { it.applyDeltaDeep(this) }
    is YXmlFragmentDeepDelta -> doc.createXmlFragment().also { it.applyDeltaDeep(this) }
    is YXmlElementDeepDelta -> toXmlElement(doc)
    is List<*> -> map { it.fromDeepDeltaValue(doc) }
    is Array<*> -> map { it.fromDeepDeltaValue(doc) }
    is Map<*, *> -> entries.associate { (key, value) ->
        require(key is String) { "YValue map keys must be strings" }
        key to value.fromDeepDeltaValue(doc)
    }.toSortedMap()
    is ByteArray -> copyOf()
    else -> this
}

internal fun Map<String, Any?>.fromDeepDeltaValues(doc: YDoc): Map<String, Any?> =
    mapValues { (_, value) -> value.fromDeepDeltaValue(doc) }.toSortedMap()

internal fun List<YArrayDeltaOp>.fromDeepDeltaValues(doc: YDoc): List<YArrayDeltaOp> =
    map { op ->
        op.copy(
            insert = op.insert?.map { value -> value.fromDeepDeltaValue(doc) },
            attributes = op.attributes.fromDeepDeltaValues(doc),
        )
    }

internal fun YTextDelta.fromDeepDeltaValues(doc: YDoc): YTextDelta =
    YTextDelta(ops.map { op ->
        op.copy(
            insert = when (val insert = op.insert) {
                is String,
                null -> insert
                else -> insert.fromDeepDeltaValue(doc)
            },
            attributes = op.attributes.fromDeepDeltaValues(doc),
        )
    })

internal fun YXmlElementDeepDelta.toXmlElement(doc: YDoc): YXmlElement =
    YXmlElement(nodeName).also { element ->
        element.setAttrs(attrs.fromDeepDeltaValues(doc))
        element.push(children.map { child -> child.toXmlNodeFromDeepDeltaValue(doc) })
    }

internal fun YXmlElementDeepDelta.toXmlElementType(doc: YDoc): YXmlElementType =
    doc.createXmlElement(nodeName).also { element ->
        element.applyDeltaDeep(this)
    }

internal fun Any?.toXmlNodeFromDeepDeltaValue(doc: YDoc): YXmlNode = when (this) {
    is YXmlNode -> clone()
    is YXmlElementDeepDelta -> toXmlElement(doc)
    is YAttributeDelta -> attributedXmlTextNode(doc)
    is String -> YXmlText(this)
    is Char -> YXmlText(toString())
    else -> error("unsupported XML deep-delta value: ${this?.let { it::class.qualifiedName } ?: "null"}")
}

internal fun Any?.toXmlChildFromDeepDeltaValue(doc: YDoc): Any? = when (this) {
    is AbstractYType -> cloneValueInto(doc)
    is YXmlNode -> clone()
    is YTextDeepDelta -> doc.createXmlText().also { it.applyDeltaDeep(this) }
    is YXmlElementDeepDelta -> toXmlElementType(doc)
    is YAttributeDelta -> attributedXmlTextNode(doc)
    is String -> YXmlText(this)
    is Char -> YXmlText(toString())
    else -> error("unsupported XML deep-delta value: ${this?.let { it::class.qualifiedName } ?: "null"}")
}

private fun YAttributeDelta.attributedXmlTextNode(doc: YDoc): YXmlText {
    val text = when (val unwrapped = value.fromDeepDeltaValue(doc)) {
        is String -> unwrapped
        is Char -> unwrapped.toString()
        is YXmlText -> unwrapped.toJson()
        else -> error("XML deep-delta attributes can only be applied to text children")
    }
    return YXmlText(text, attributes.fromDeepDeltaValues(doc))
}

internal fun List<YArrayDeltaOp>.fromXmlDeepDeltaValues(doc: YDoc): List<YArrayDeltaOp> =
    map { op ->
        if (op.insert == null) {
            op
        } else {
            op.copy(insert = op.insert.map { value -> value.toXmlChildFromDeepDeltaValue(doc) })
        }
    }
