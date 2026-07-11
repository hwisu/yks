package dev.yks

private const val INFO_CONTENT_MASK = 0x1f
private const val INFO_HAS_PARENT_SUB = 0x20
private const val INFO_HAS_RIGHT_ORIGIN = 0x40
private const val INFO_HAS_ORIGIN = 0x80

/** Byte-exact implementation of the uncompressed Yjs update V1 envelope. */
internal object UpdateCodec {
    fun encode(update: DocumentUpdate): ByteArray = BinaryEncoder()
        .also { encoder -> write(encoder, update) }
        .toByteArray()

    fun write(encoder: BinaryEncoder, update: DocumentUpdate): BinaryEncoder {
        if (!update.allowV1 || !update.isSupportedV1Update()) {
            return LegacyUpdateCodec.write(encoder, update)
        }
        return writeV1(encoder, update)
    }

    private fun writeV1(encoder: BinaryEncoder, update: DocumentUpdate): BinaryEncoder {
        val itemsByClient = update.items
            .groupBy { item -> item.id.client }
            .mapValues { (_, items) -> items.sortedBy { item -> item.id.clock } }
            .filterValues { items -> items.isNotEmpty() }
        val parentItems = update.parentItemIds + update.items.mapNotNull { item ->
            item.content.directTypeRef()?.name?.let { name -> name to item.id }
        }.toMap()

        encoder.writeVarUInt(itemsByClient.size.toLong())
        itemsByClient.toSortedMap(compareByDescending { it }).forEach { (client, items) ->
            val structs = items.withClockSkips()
            encoder.writeVarUInt(structs.size.toLong())
            encoder.writeVarUInt(client)
            encoder.writeVarUInt(structs.first().clock)
            structs.forEach { struct ->
                when (struct) {
                    is EncodedStruct.Skip -> {
                        encoder.writeByte(structSkipRefNumber)
                        encoder.writeVarUInt(struct.length)
                    }
                    is EncodedStruct.Item -> writeItem(encoder, struct.item, parentItems)
                }
            }
        }
        writeDeleteSet(encoder, update.deleteSet)
        return encoder
    }

    fun decode(bytes: ByteArray): DocumentUpdate {
        if (bytes.hasLegacyMagic()) return LegacyUpdateCodec.decode(bytes)
        return decodeV1(BinaryDecoder(bytes))
    }

    fun decode(decoder: BinaryDecoder): DocumentUpdate = decode(decoder.readRemainingBytes())

    private fun decodeV1(decoder: BinaryDecoder): DocumentUpdate {
        val structs = mutableListOf<DecodedWireItem>()
        repeat(decoder.readVarUInt().toInt()) {
            val numberOfStructs = decoder.readVarUInt().toInt()
            val client = decoder.readVarUInt()
            var clock = decoder.readVarUInt()
            repeat(numberOfStructs) {
                val info = decoder.readByte()
                when (info and INFO_CONTENT_MASK) {
                    structGCRefNumber -> error("Yjs GC structs are not supported yet")
                    structSkipRefNumber -> clock += decoder.readVarUInt()
                    else -> {
                        val id = Id(client, clock)
                        val origin = if ((info and INFO_HAS_ORIGIN) != 0) decoder.readId() else null
                        val rightOrigin = if ((info and INFO_HAS_RIGHT_ORIGIN) != 0) decoder.readId() else null
                        val parent = if (origin == null && rightOrigin == null) {
                            if (decoder.readVarUInt() == 1L) {
                                ParentReference.Root(decoder.readString())
                            } else {
                                ParentReference.Nested(decoder.readId())
                            }
                        } else {
                            ParentReference.Inherit(origin ?: checkNotNull(rightOrigin))
                        }
                        val parentSub = if (
                            origin == null && rightOrigin == null && (info and INFO_HAS_PARENT_SUB) != 0
                        ) {
                            decoder.readString()
                        } else {
                            null
                        }
                        val content = readWireContent(decoder, info and INFO_CONTENT_MASK, id)
                        structs.add(DecodedWireItem(id, origin, rightOrigin, parent, parentSub, content))
                        clock += content.length
                    }
                }
            }
        }
        val deleteSet = readDeleteSet(decoder)
        check(!decoder.hasRemaining()) { "update has trailing bytes" }
        return DocumentUpdate(structs.toStoreItems(), deleteSet)
    }

    private fun writeItem(
        encoder: BinaryEncoder,
        item: StoreItem,
        parentItems: Map<String, Id>,
    ) {
        val ref = item.content.yjsContentRef()
        val info = ref or
            (if (item.origin != null) INFO_HAS_ORIGIN else 0) or
            (if (item.rightOrigin != null) INFO_HAS_RIGHT_ORIGIN else 0) or
            (if (item.parentSub != null) INFO_HAS_PARENT_SUB else 0)
        encoder.writeByte(info)
        item.origin?.let(encoder::writeId)
        item.rightOrigin?.let(encoder::writeId)
        if (item.origin == null && item.rightOrigin == null) {
            val parentItem = parentItems[item.parent]
            encoder.writeVarUInt(if (parentItem == null) 1 else 0)
            if (parentItem == null) {
                encoder.writeString(item.parent)
            } else {
                encoder.writeId(parentItem)
            }
            item.parentSub?.let(encoder::writeString)
        }
        writeContent(encoder, item.content)
    }

    private fun writeContent(encoder: BinaryEncoder, content: ItemContent) {
        when (content) {
            is ItemContent.Text -> encoder.writeString(content.value)
            is ItemContent.TextEmbed -> encoder.writeString(toJsonLiteral(content.value.toAny()))
            is ItemContent.TextFormat -> {
                encoder.writeString("__yks_text_format")
                encoder.writeString(toJsonLiteral(content.toWireValue()))
            }
            is ItemContent.Value -> writeValueContent(encoder, content.value)
            is ItemContent.MapEntry -> writeValueContent(encoder, content.value)
            is ItemContent.XmlNode -> {
                encoder.writeVarUInt(1)
                writeLib0Any(encoder, content.value.toEventJson())
            }
            is ItemContent.XmlType -> writeTypeContent(encoder, content.ref.kind, content.nodeName)
            is ItemContent.Deleted -> encoder.writeVarUInt(1)
        }
    }

    private fun writeValueContent(encoder: BinaryEncoder, value: YValue) {
        when (value) {
            is YValue.BinaryValue -> encoder.writeBytes(value.bytes())
            is YValue.TypeRef -> writeTypeContent(encoder, value.kind, value.name)
            is YValue.SubdocRef -> {
                encoder.writeString(value.guid)
                writeLib0Any(
                    encoder,
                    mapOf(
                        "gc" to value.gc,
                        "autoLoad" to value.autoLoad,
                        "meta" to value.meta.toAny(),
                    ),
                )
            }
            else -> {
                encoder.writeVarUInt(1)
                writeLib0Any(encoder, value.toAny())
            }
        }
    }

    private fun writeTypeContent(encoder: BinaryEncoder, kind: RootKind, nodeName: String = "") {
        encoder.writeVarUInt(kind.typeRefId().toLong())
        if (kind == RootKind.XmlElement || kind == RootKind.XmlHook) {
            encoder.writeString(nodeName)
        }
    }

    private fun readWireContent(decoder: BinaryDecoder, ref: Int, id: Id): WireContent = when (ref) {
        contentDeletedRefNumber -> WireContent.Deleted(decoder.readVarUInt())
        contentJSONRefNumber -> WireContent.Json(
            List(decoder.readVarUInt().toInt()) {
                when (val json = decoder.readString()) {
                    "undefined" -> null
                    else -> parseJsonLiteral(json)
                }
            },
        )
        contentBinaryRefNumber -> WireContent.Binary(decoder.readBytes())
        contentStringRefNumber -> WireContent.StringContent(decoder.readString())
        contentEmbedRefNumber -> WireContent.Embed(parseJsonLiteral(decoder.readString()))
        contentFormatRefNumber -> WireContent.Format(decoder.readString(), parseJsonLiteral(decoder.readString()))
        contentTypeRefNumber -> {
            val kind = rootKindFromTypeRefId(decoder.readVarUInt().toInt())
            val nodeName = if (kind == RootKind.XmlElement || kind == RootKind.XmlHook) decoder.readString() else ""
            WireContent.Type(kind, nestedName(id), nodeName)
        }
        contentAnyRefNumber -> WireContent.AnyContent(
            List(decoder.readVarUInt().toInt()) { readLib0Any(decoder) },
        )
        contentDocRefNumber -> WireContent.Doc(decoder.readString(), readLib0Any(decoder))
        else -> error("unknown Yjs item content ref: $ref")
    }

    private fun writeDeleteSet(encoder: BinaryEncoder, deleteSet: DeleteSet) {
        val clients = deleteSet.clients.filterValues { ranges -> ranges.isNotEmpty() }
        encoder.writeVarUInt(clients.size.toLong())
        clients.toSortedMap(compareByDescending { it }).forEach { (client, ranges) ->
            encoder.writeVarUInt(client)
            encoder.writeVarUInt(ranges.size.toLong())
            ranges.sortedBy { range -> range.clock }.forEach { range ->
                encoder.writeVarUInt(range.clock)
                encoder.writeVarUInt(range.length)
            }
        }
    }

    private fun readDeleteSet(decoder: BinaryDecoder): DeleteSet {
        val deleteSet = DeleteSet.empty()
        repeat(decoder.readVarUInt().toInt()) {
            val client = decoder.readVarUInt()
            repeat(decoder.readVarUInt().toInt()) {
                deleteSet.add(Id(client, decoder.readVarUInt()), decoder.readVarUInt())
            }
        }
        return deleteSet
    }
}

private fun DocumentUpdate.isSupportedV1Update(): Boolean {
    if (!deleteSet.isEmpty) return false
    if (items.isEmpty()) return true
    val clients = items.groupBy { item -> item.id.client }
    if (clients.size != 1) return false
    val sorted = clients.values.single().sortedBy { item -> item.id.clock }
    if (sorted.first().id.client !in 0..9_007_199_254_740_992L) return false
    val startClock = sorted.first().id.clock
    if (sorted.map { item -> item.id.clock } != sorted.indices.map { index -> startClock + index }) return false
    val ids = sorted.mapTo(hashSetOf()) { item -> item.id }
    val parentItems = parentItemIds + sorted.mapNotNull { item ->
        item.content.directTypeRef()?.name?.let { name -> name to item.id }
    }.toMap()
    val parentKinds = this.parentKinds + sorted.mapNotNull { item ->
        item.content.directTypeRef()?.let { ref -> ref.name to ref.kind }
    }.toMap()
    return sorted.all { item ->
        !item.deleted &&
            item.content.isSupportedV1Content() &&
            item.hasCompatibleV1ParentKind(sorted, parentKinds) &&
            (startClock > 0 || item.origin == null || item.origin in ids) &&
            (startClock > 0 || item.rightOrigin == null || item.rightOrigin in ids) &&
            item.hasResolvableV1Parent(parentItems) &&
            item.hasConsistentInheritedMetadata(sorted)
    }
}

private fun StoreItem.hasResolvableV1Parent(parentItems: Map<String, Id>): Boolean {
    val parentItemId = parentItems[parent]
    if (parentItemId == null) {
        return !parent.startsWith("__yks_nested__:") && !parent.startsWith("__yjs_nested__:")
    }
    // A nested type must be introduced before content can target it on Yjs wire.
    return parentItemId.client != id.client || parentItemId.clock < id.clock
}

private fun StoreItem.hasConsistentInheritedMetadata(items: List<StoreItem>): Boolean {
    val anchor = origin ?: rightOrigin ?: return true
    val anchorItem = items.firstOrNull { candidate -> candidate.id == anchor } ?: return true
    return anchorItem.parent == parent && anchorItem.parentSub == parentSub
}

private fun StoreItem.hasCompatibleV1ParentKind(
    items: List<StoreItem>,
    parentKinds: Map<String, RootKind>,
): Boolean {
    val nestedKind = parentKinds[parent]
    return when (content) {
        is ItemContent.MapEntry -> nestedKind?.let { kind -> kind == RootKind.Map }
            ?: items.filter { item -> item.parent == parent }.all { item -> item.content is ItemContent.MapEntry }
        is ItemContent.Value -> nestedKind?.let { kind -> kind == RootKind.Array }
            ?: items.filter { item -> item.parent == parent }.all { item -> item.content is ItemContent.Value }
        is ItemContent.Text -> nestedKind?.let { kind -> kind == RootKind.Text }
            ?: items.filter { item -> item.parent == parent }.all { item -> item.content is ItemContent.Text }
        else -> false
    }
}

private fun ItemContent.isSupportedV1Content(): Boolean = when (this) {
    is ItemContent.Value -> kind == RootKind.Array && value.isSupportedV1Value(topLevel = true)
    is ItemContent.MapEntry -> value.isSupportedV1Value(topLevel = true)
    is ItemContent.Text ->
        kind == RootKind.Text &&
            !Character.isSurrogate(value.single()) &&
            attributes.isEmpty() &&
            baseAttributes.isEmpty()
    is ItemContent.TextEmbed -> false
    is ItemContent.TextFormat -> false
    is ItemContent.XmlNode -> false
    is ItemContent.XmlType -> false
    is ItemContent.Deleted -> false
}

private fun YValue.isSupportedV1Value(topLevel: Boolean): Boolean = when (this) {
    YValue.Null,
    is YValue.Bool,
    is YValue.StringValue,
    is YValue.BinaryValue -> true
    is YValue.LongNumber -> value.toDouble().let { number ->
        kotlin.math.abs(number) <= 9_007_199_254_740_992.0 && number.toLong() == value
    }
    is YValue.DoubleNumber -> value.isFinite() && value % 1.0 != 0.0
    is YValue.ListValue -> value.all { nested -> nested.isSupportedV1Value(topLevel = false) }
    is YValue.MapValue -> value.values.all { nested -> nested.isSupportedV1Value(topLevel = false) }
    is YValue.TypeRef -> topLevel && kind in setOf(RootKind.Array, RootKind.Map, RootKind.Text)
    is YValue.SubdocRef -> false
}

private sealed interface EncodedStruct {
    val clock: Long

    data class Item(val item: StoreItem) : EncodedStruct {
        override val clock: Long get() = item.id.clock
    }

    data class Skip(override val clock: Long, val length: Long) : EncodedStruct
}

private sealed interface ParentReference {
    data class Root(val name: String) : ParentReference
    data class Nested(val id: Id) : ParentReference
    data class Inherit(val id: Id) : ParentReference
}

private data class DecodedWireItem(
    val id: Id,
    val origin: Id?,
    val rightOrigin: Id?,
    val parent: ParentReference,
    val parentSub: String?,
    val content: WireContent,
)

private sealed interface WireContent {
    val length: Long

    data class Deleted(override val length: Long) : WireContent
    data class Json(val values: List<Any?>) : WireContent { override val length: Long get() = values.size.toLong() }
    data class Binary(val value: ByteArray) : WireContent { override val length: Long = 1 }
    data class StringContent(val value: String) : WireContent { override val length: Long get() = value.length.toLong() }
    data class Embed(val value: Any?) : WireContent { override val length: Long = 1 }
    data class Format(val key: String, val value: Any?) : WireContent { override val length: Long = 1 }
    data class Type(val kind: RootKind, val name: String, val nodeName: String) : WireContent { override val length: Long = 1 }
    data class AnyContent(val values: List<Any?>) : WireContent { override val length: Long get() = values.size.toLong() }
    data class Doc(val guid: String, val options: Any?) : WireContent { override val length: Long = 1 }
}

private fun List<StoreItem>.withClockSkips(): List<EncodedStruct> {
    val result = mutableListOf<EncodedStruct>()
    var nextClock = first().id.clock
    forEach { item ->
        if (item.id.clock > nextClock) {
            result.add(EncodedStruct.Skip(nextClock, item.id.clock - nextClock))
        }
        if (item.id.clock >= nextClock) {
            result.add(EncodedStruct.Item(item))
            nextClock = item.id.clock + item.length
        }
    }
    return result
}

private fun List<DecodedWireItem>.toStoreItems(): List<StoreItem> {
    val byId = associateBy { item -> item.id }
    val resolvedParents = mutableMapOf<Id, String>()
    val resolvedKinds = mutableMapOf<Id, RootKind>()

    fun resolveParent(item: DecodedWireItem, seen: Set<Id> = emptySet()): String {
        resolvedParents[item.id]?.let { return it }
        check(item.id !in seen) { "cyclic Yjs parent reference at ${item.id}" }
        val parent = when (val reference = item.parent) {
            is ParentReference.Root -> reference.name
            is ParentReference.Nested -> byId[reference.id]?.content
                ?.let { content -> (content as? WireContent.Type)?.name }
                ?: nestedName(reference.id)
            is ParentReference.Inherit -> byId[reference.id]
                ?.let { anchor -> resolveParent(anchor, seen + item.id) }
                ?: inheritedParentName(reference.id)
        }
        resolvedParents[item.id] = parent
        return parent
    }

    fun resolveKind(item: DecodedWireItem, seen: Set<Id> = emptySet()): RootKind {
        resolvedKinds[item.id]?.let { return it }
        check(item.id !in seen) { "cyclic Yjs kind reference at ${item.id}" }
        val kind = when (val reference = item.parent) {
            is ParentReference.Nested -> (byId[reference.id]?.content as? WireContent.Type)?.kind
            is ParentReference.Inherit -> byId[reference.id]?.let { anchor -> resolveKind(anchor, seen + item.id) }
            is ParentReference.Root -> null
        } ?: item.content.inferRootKind(item.parentSub)
        resolvedKinds[item.id] = kind
        return kind
    }

    return flatMap { item ->
        val parent = resolveParent(item)
        val kind = resolveKind(item)
        item.content.toItemContents(kind).mapIndexed { index, content ->
            StoreItem(
                id = Id(item.id.client, item.id.clock + index),
                origin = if (index == 0) item.origin else Id(item.id.client, item.id.clock + index - 1),
                rightOrigin = item.rightOrigin,
                parent = parent,
                parentSub = item.parentSub,
                content = content,
            )
        }
    }
}

private fun WireContent.inferRootKind(parentSub: String?): RootKind = when {
    parentSub != null -> RootKind.Map
    this is WireContent.StringContent || this is WireContent.Embed || this is WireContent.Format -> RootKind.Text
    this is WireContent.Type && kind in setOf(RootKind.XmlElement, RootKind.XmlHook, RootKind.XmlText) -> RootKind.XmlFragment
    else -> RootKind.Array
}

private fun WireContent.toItemContents(kind: RootKind): List<ItemContent> = when (this) {
    is WireContent.Deleted -> List(length.toInt()) { ItemContent.Deleted(kind) }
    is WireContent.StringContent -> value.map { char -> ItemContent.Text(char.toString(), kind = kind) }
    is WireContent.Embed -> listOf(ItemContent.TextEmbed(YValue.from(value), kind = kind))
    is WireContent.Format -> listOf(toTextFormat(kind))
    is WireContent.Binary -> listOf(value.toSequenceContent(kind))
    is WireContent.Json -> values.map { value -> value.toSequenceContent(kind) }
    is WireContent.AnyContent -> values.map { value -> value.toSequenceContent(kind) }
    is WireContent.Type -> listOf(
        if (kind == RootKind.XmlFragment || kind == RootKind.XmlElement || kind == RootKind.XmlHook) {
            ItemContent.XmlType(YValue.TypeRef(this.kind, name), nodeName, kind)
        } else {
            val value = YValue.TypeRef(this.kind, name)
            if (kind == RootKind.Map) ItemContent.MapEntry(value) else ItemContent.Value(value)
        },
    )
    is WireContent.Doc -> listOf(options.toSubdocValue(guid).toSequenceContent(kind))
}

private fun WireContent.Format.toTextFormat(kind: RootKind): ItemContent {
    require(key == "__yks_text_format") { "standard Y.Text format items are not supported yet" }
    val data = value as? Map<*, *> ?: error("invalid YKS text format payload")
    val target = data["target"] as? Map<*, *> ?: error("text format target is missing")
    fun number(name: String, source: Map<*, *> = data): Long =
        (source[name] as? Number)?.toLong() ?: error("text format $name is missing")
    fun attributes(name: String): Map<String, YValue> = (data[name] as? Map<*, *>).orEmpty().entries.associate { (key, value) ->
        key.toString() to YValue.from(value)
    }.toSortedMap()
    val before = (data["beforeAttributes"] as? List<*>).orEmpty().map { raw ->
        (raw as? Map<*, *>).orEmpty().entries.associate { (key, value) -> key.toString() to YValue.from(value) }.toSortedMap()
    }
    return ItemContent.TextFormat(
        target = Id(number("client", target), number("clock", target)),
        length = number("length"),
        attributes = attributes("attributes"),
        afterAttributes = attributes("afterAttributes"),
        beforeAttributes = before,
        kind = kind,
    )
}

private fun Any?.toSequenceContent(kind: RootKind): ItemContent {
    val value = YValue.from(this)
    return when (kind) {
        RootKind.Map -> ItemContent.MapEntry(value)
        RootKind.Text,
        RootKind.XmlText -> ItemContent.TextEmbed(value, kind = kind)
        RootKind.XmlFragment,
        RootKind.XmlElement,
        RootKind.XmlHook -> ItemContent.XmlNode(xmlNodeFromDeltaValue(this).toValue(), kind)
        RootKind.Array -> ItemContent.Value(value)
    }
}

private fun Any?.toSubdocValue(guid: String): YValue.SubdocRef {
    val options = this as? Map<*, *>
    return YValue.SubdocRef(
        guid = guid,
        gc = options?.get("gc") as? Boolean ?: true,
        shouldLoad = options?.get("autoLoad") as? Boolean ?: false,
        autoLoad = options?.get("autoLoad") as? Boolean ?: false,
        instanceId = guid,
        collectionId = null,
        meta = YValue.from(options?.get("meta")),
        isSuggestionDoc = false,
    )
}

private fun ItemContent.yjsContentRef(): Int = when (this) {
    is ItemContent.Deleted -> contentDeletedRefNumber
    is ItemContent.Text -> contentStringRefNumber
    is ItemContent.TextEmbed -> contentEmbedRefNumber
    is ItemContent.TextFormat -> contentFormatRefNumber
    is ItemContent.XmlType -> contentTypeRefNumber
    is ItemContent.XmlNode -> contentAnyRefNumber
    is ItemContent.Value -> value.yjsContentRef()
    is ItemContent.MapEntry -> value.yjsContentRef()
}

private fun YValue.yjsContentRef(): Int = when (this) {
    is YValue.BinaryValue -> contentBinaryRefNumber
    is YValue.TypeRef -> contentTypeRefNumber
    is YValue.SubdocRef -> contentDocRefNumber
    else -> contentAnyRefNumber
}

private fun ItemContent.TextFormat.toWireValue(): Map<String, Any?> = mapOf(
    "target" to mapOf("client" to target.client, "clock" to target.clock),
    "length" to length,
    "attributes" to attributes.mapValues { (_, value) -> value.toAny() },
    "afterAttributes" to afterAttributes.mapValues { (_, value) -> value.toAny() },
    "beforeAttributes" to beforeAttributes.map { attributes -> attributes.mapValues { (_, value) -> value.toAny() } },
)

private fun RootKind.typeRefId(): Int = when (this) {
    RootKind.Array -> YArrayRefID
    RootKind.Map -> YMapRefID
    RootKind.Text -> YTextRefID
    RootKind.XmlElement -> YXmlElementRefID
    RootKind.XmlFragment -> YXmlFragmentRefID
    RootKind.XmlHook -> YXmlHookRefID
    RootKind.XmlText -> YXmlTextRefID
}

private fun BinaryEncoder.writeId(id: Id) {
    writeVarUInt(id.client)
    writeVarUInt(id.clock)
}

private fun BinaryDecoder.readId(): Id = Id(readVarUInt(), readVarUInt())

private fun nestedName(id: Id): String = "__yjs_nested__:${id.client}:${id.clock}"
private fun inheritedParentName(id: Id): String = "__yjs_inherit__:${id.client}:${id.clock}"

private fun ByteArray.hasLegacyMagic(): Boolean =
    size >= 4 && this[0] == 'Y'.code.toByte() && this[1] == 'K'.code.toByte() &&
        this[2] == 'S'.code.toByte() && this[3] == 1.toByte()
