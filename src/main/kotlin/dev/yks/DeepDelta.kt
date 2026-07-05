package dev.yks

sealed interface YDeepDelta {
    val attrs: Map<String, Any?>
}

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
    val attrs: Map<String, Any?> = emptyMap(),
    val children: List<Any?> = emptyList(),
)

internal fun YDeepDelta.isEmptyDeepDelta(): Boolean = when (this) {
    is YArrayDeepDelta -> attrs.isEmpty() && delta.isEmpty()
    is YTextDeepDelta -> attrs.isEmpty() && delta.ops.isEmpty()
    is YMapDeepDelta -> attrs.isEmpty()
    is YXmlFragmentDeepDelta -> attrs.isEmpty() && delta.isEmpty()
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
    is YArray -> renderArrayDeepDelta(this, options)
    is YText -> renderTextDeepDelta(this, options)
    is YMap -> renderMapDeepDelta(this, options)
    is YXmlFragment -> renderXmlFragmentDeepDelta(this, options)
    is YXmlElementType,
    is YXmlTextType -> error("detached XML node type refs do not support deep delta rendering")
}

internal fun renderArrayDeepDelta(type: YArray, options: DeepDeltaRenderOptions): YArrayDeepDelta =
    YArrayDeepDelta(
        attrs = renderTypeAttrs(type, options),
        delta = renderSequenceDelta(type, RootKind.Array, options) { rendered ->
            val value = (rendered.item.content as ItemContent.Value).value
            type.doc.valueToAny(value).toDeepDeltaValue(options.nestedValueOptions(rendered.action))
        },
    )

internal fun renderTextDeepDelta(type: YText, options: DeepDeltaRenderOptions): YTextDeepDelta {
    val delta = YTextDelta()
    renderSequenceItems(type, RootKind.Text, options).forEach { rendered ->
        val length = rendered.content.content.getLength().toInt()
        val attribution = createAttributionFromAttributionItems(rendered.content.attrs, rendered.content.deleted).orEmpty()
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
                    else -> Unit
                }
            }
            RenderedDeltaAction.Retain -> {
                val attributes = if (rendered.content.deleted || rendered.content.attrs != null) attribution else emptyMap()
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

internal fun renderMapDeepDelta(type: YMap, options: DeepDeltaRenderOptions): YMapDeepDelta =
    YMapDeepDelta(attrs = renderTypeAttrs(type, options))

internal fun renderXmlFragmentDeepDelta(
    type: YXmlFragment,
    options: DeepDeltaRenderOptions,
): YXmlFragmentDeepDelta =
    YXmlFragmentDeepDelta(
        attrs = renderTypeAttrs(type, options),
        delta = renderSequenceDelta(type, RootKind.XmlFragment, options) { rendered ->
            (rendered.item.content as ItemContent.XmlNode).value.toNode().toDeepDeltaValue(
                options.nestedValueOptions(rendered.action),
            )
        },
    )

internal fun Any?.toDeepDeltaValue(): Any? = toDeepDeltaValue(DeepDeltaRenderOptions())

internal fun Any?.toDeepDeltaValue(options: DeepDeltaRenderOptions): Any? = when (this) {
    is YArray -> renderArrayDeepDelta(this, options)
    is YText -> renderTextDeepDelta(this, options)
    is YMap -> renderMapDeepDelta(this, options)
    is YXmlFragment -> renderXmlFragmentDeepDelta(this, options)
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

private enum class RenderedDeltaAction {
    Insert,
    Retain,
    Delete,
    Skip,
}

private fun renderTypeAttrs(type: AbstractYType, options: DeepDeltaRenderOptions): Map<String, Any?> {
    val attrs = when (type) {
        is YArray -> type.getAttrs()
        is YText -> type.getAttrs()
        is YMap -> type.toMap()
        is YXmlFragment -> type.getAttrs()
        is YXmlElementType,
        is YXmlTextType -> emptyMap()
    }
    val modifiedKeys = options.modified?.get(type)?.filterNotNull()
        ?: return attrs.mapValues { (_, value) -> value.toDeepDeltaValue(options) }.toSortedMap()
    return modifiedKeys
        .filter { key -> attrs.containsKey(key) }
        .associateWith { key -> attrs[key].toDeepDeltaValue(options) }
        .toSortedMap()
}

private data class RenderedSequenceItem(
    val item: StoreItem,
    val content: AttributedContent,
    val action: RenderedDeltaAction,
)

private fun renderSequenceDelta(
    type: AbstractYType,
    kind: RootKind,
    options: DeepDeltaRenderOptions,
    value: (RenderedSequenceItem) -> Any?,
): List<YArrayDeltaOp> {
    val delta = mutableListOf<YArrayDeltaOp>()
    renderSequenceItems(type, kind, options).forEach { rendered ->
        val length = rendered.content.content.getLength().toInt()
        when (rendered.action) {
            RenderedDeltaAction.Insert -> delta.appendInsert(value(rendered))
            RenderedDeltaAction.Retain -> delta.appendRetain(length)
            RenderedDeltaAction.Delete -> delta.appendDelete(length)
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
        .filter { item -> item.content.kind == kind }
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
    else -> emptyMap()
}.mapValues { (_, value) -> doc.valueToAny(value).toDeepDeltaValue(options) }.toSortedMap()

private fun MutableList<YArrayDeltaOp>.appendInsert(value: Any?) {
    val last = lastOrNull()
    if (last?.insert != null) {
        this[lastIndex] = last.copy(insert = last.insert + value)
    } else {
        add(YArrayDeltaOp(insert = listOf(value)))
    }
}

private fun MutableList<YArrayDeltaOp>.appendRetain(length: Int) {
    if (length <= 0) return
    val last = lastOrNull()
    if (last?.retain != null) {
        this[lastIndex] = last.copy(retain = last.retain + length)
    } else {
        add(YArrayDeltaOp(retain = length))
    }
}

private fun MutableList<YArrayDeltaOp>.appendDelete(length: Int) {
    if (length <= 0) return
    val last = lastOrNull()
    if (last?.delete != null) {
        this[lastIndex] = last.copy(delete = last.delete + length)
    } else {
        add(YArrayDeltaOp(delete = length))
    }
}

internal fun List<YArrayDeltaOp>.toDeepDeltaValues(
    options: DeepDeltaRenderOptions = DeepDeltaRenderOptions(),
): List<YArrayDeltaOp> =
    map { op ->
        if (op.insert == null) {
            op
        } else {
            op.copy(insert = op.insert.map { value -> value.toDeepDeltaValue(options) })
        }
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
        if (op.insert == null) {
            op
        } else {
            op.copy(insert = op.insert.map { value -> value.fromDeepDeltaValue(doc) })
        }
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

internal fun Any?.toXmlNodeFromDeepDeltaValue(doc: YDoc): YXmlNode = when (this) {
    is YXmlNode -> clone()
    is YXmlElementDeepDelta -> toXmlElement(doc)
    is String -> YXmlText(this)
    is Char -> YXmlText(toString())
    else -> error("unsupported XML deep-delta value: ${this?.let { it::class.qualifiedName } ?: "null"}")
}

internal fun List<YArrayDeltaOp>.fromXmlDeepDeltaValues(doc: YDoc): List<YArrayDeltaOp> =
    map { op ->
        if (op.insert == null) {
            op
        } else {
            op.copy(insert = op.insert.map { value -> value.toXmlNodeFromDeepDeltaValue(doc) })
        }
    }
