package dev.yks

private val updateMagicV3 = byteArrayOf('Y'.code.toByte(), 'K'.code.toByte(), 'S'.code.toByte(), 3)

internal data class DocumentUpdate(
    val items: List<StoreItem>,
    val deleteSet: DeleteSet,
    val parentItemIds: Map<String, Id> = emptyMap(),
    val parentKinds: Map<String, RootKind> = emptyMap(),
    val allowV1: Boolean = true,
) {
    val isEmpty: Boolean get() = items.isEmpty() && deleteSet.isEmpty
}

internal object LegacyUpdateCodec {
    fun encode(update: DocumentUpdate): ByteArray {
        val encoder = BinaryEncoder()
        write(encoder, update)
        return encoder.toByteArray()
    }

    fun write(encoder: BinaryEncoder, update: DocumentUpdate): BinaryEncoder {
        updateMagicV3.forEach { encoder.writeByte(it.toInt()) }
        encoder.writeVarUInt(update.items.size.toLong())
        update.items.sortedWith(compareBy<StoreItem> { it.id.client }.thenBy { it.id.clock }).forEach { item ->
            writeItem(encoder, item)
        }
        writeDeleteSet(encoder, update.deleteSet)
        return encoder
    }

    fun decode(bytes: ByteArray): DocumentUpdate =
        decode(BinaryDecoder(bytes))

    fun decode(decoder: BinaryDecoder): DocumentUpdate {
        check(decoder.readByte() == 'Y'.code && decoder.readByte() == 'K'.code && decoder.readByte() == 'S'.code) {
            "unsupported update format"
        }
        val version = decoder.readByte()
        check(version in 1..3) { "unsupported legacy update version: $version" }
        val itemCount = decoder.readVarUInt().toDecodedCount()
        val items = buildList {
            repeat(itemCount) {
                add(readItem(decoder, version))
            }
        }
        val deleteSet = readDeleteSet(decoder)
        check(!decoder.hasRemaining()) { "update has trailing bytes" }
        return DocumentUpdate(items, deleteSet)
    }

    private fun writeItem(encoder: BinaryEncoder, item: StoreItem) {
        encoder.writeVarUInt(item.id.client)
        encoder.writeVarUInt(item.id.clock)
        writeNullableId(encoder, item.origin)
        writeNullableId(encoder, item.rightOrigin)
        encoder.writeString(item.parent)
        encoder.writeBoolean(item.parentSub != null)
        item.parentSub?.let(encoder::writeString)
        encoder.writeBoolean(item.deleted)
        encoder.writeBoolean(item.requiresClockContinuity)
        encoder.writeBoolean(item.isGc)
        when (val unresolved = item.unresolvedParent) {
            null -> encoder.writeByte(0)
            is UnresolvedYjsParent.Nested -> {
                encoder.writeByte(1)
                encoder.writeVarUInt(unresolved.id.client)
                encoder.writeVarUInt(unresolved.id.clock)
            }
            is UnresolvedYjsParent.Inherit -> {
                encoder.writeByte(2)
                encoder.writeVarUInt(unresolved.id.client)
                encoder.writeVarUInt(unresolved.id.clock)
            }
        }
        when (val content = item.content) {
            is ItemContent.Value -> {
                encoder.writeByte(0)
                writeYValue(encoder, content.value)
            }
            is ItemContent.Text -> {
                if (content.kind == RootKind.Text) {
                    encoder.writeByte(1)
                } else {
                    encoder.writeByte(9)
                    writeRootKind(encoder, content.kind)
                }
                encoder.writeVarUInt(content.value.single().code.toLong())
                writeAttributes(encoder, content.attributes)
                writeAttributes(encoder, content.baseAttributes)
            }
            is ItemContent.MapEntry -> {
                encoder.writeByte(2)
                writeYValue(encoder, content.value)
            }
            is ItemContent.XmlNode -> {
                if (content.kind == RootKind.XmlFragment) {
                    encoder.writeByte(3)
                    writeXmlNodeValue(encoder, content.value)
                } else {
                    encoder.writeByte(8)
                    writeRootKind(encoder, content.kind)
                    writeXmlNodeValue(encoder, content.value)
                }
            }
            is ItemContent.TextEmbed -> {
                if (content.kind == RootKind.Text) {
                    encoder.writeByte(4)
                } else {
                    encoder.writeByte(10)
                    writeRootKind(encoder, content.kind)
                }
                writeYValue(encoder, content.value)
                writeAttributes(encoder, content.attributes)
                writeAttributes(encoder, content.baseAttributes)
            }
            is ItemContent.Deleted -> {
                encoder.writeByte(if (content.length == 1L) 5 else 13)
                writeRootKind(encoder, content.kind)
                if (content.length != 1L) encoder.writeVarUInt(content.length)
            }
            is ItemContent.TextFormat -> {
                if (content.kind == RootKind.Text) {
                    encoder.writeByte(6)
                } else {
                    encoder.writeByte(11)
                    writeRootKind(encoder, content.kind)
                }
                writeNullableId(encoder, content.target)
                encoder.writeVarUInt(content.length)
                writeAttributes(encoder, content.attributes)
                writeAttributes(encoder, content.afterAttributes)
                encoder.writeVarUInt(content.beforeAttributes.size.toLong())
                content.beforeAttributes.forEach { attributes -> writeAttributes(encoder, attributes) }
            }
            is ItemContent.NativeTextFormat -> {
                encoder.writeByte(12)
                writeRootKind(encoder, content.kind)
                encoder.writeString(content.key)
                writeYValue(encoder, content.value)
            }
            is ItemContent.XmlType -> {
                encoder.writeByte(7)
                writeRootKind(encoder, content.kind)
                writeYValue(encoder, content.ref)
                encoder.writeString(content.nodeName)
            }
        }
    }

    private fun readItem(decoder: BinaryDecoder, version: Int): StoreItem {
        val id = Id(decoder.readVarUInt(), decoder.readVarUInt())
        val origin = readNullableId(decoder)
        val rightOrigin = readNullableId(decoder)
        val parent = decoder.readString()
        val parentSub = if (decoder.readBoolean()) decoder.readString() else null
        val deleted = decoder.readBoolean()
        val requiresClockContinuity = version >= 2 && decoder.readBoolean()
        val isGc = version >= 2 && decoder.readBoolean()
        val unresolvedParent = if (version >= 3) {
            when (val tag = decoder.readByte()) {
                0 -> null
                1 -> UnresolvedYjsParent.Nested(Id(decoder.readVarUInt(), decoder.readVarUInt()))
                2 -> UnresolvedYjsParent.Inherit(Id(decoder.readVarUInt(), decoder.readVarUInt()))
                else -> error("unknown unresolved parent tag: $tag")
            }
        } else {
            null
        }
        val content = when (val tag = decoder.readByte()) {
            0 -> ItemContent.Value(readYValue(decoder))
            1 -> {
                val value = decoder.readVarUInt().toDecodedCount().toChar().toString()
                val attributes = readAttributes(decoder)
                ItemContent.Text(value, attributes, readAttributes(decoder))
            }
            2 -> ItemContent.MapEntry(readYValue(decoder))
            3 -> ItemContent.XmlNode(readXmlNodeValue(decoder))
            4 -> {
                val value = readYValue(decoder)
                val attributes = readAttributes(decoder)
                ItemContent.TextEmbed(value, attributes, readAttributes(decoder))
            }
            5 -> ItemContent.Deleted(readRootKind(decoder))
            6 -> ItemContent.TextFormat(
                target = readNullableId(decoder) ?: error("text format target is missing"),
                length = decoder.readVarUInt(),
                attributes = readAttributes(decoder),
                afterAttributes = readAttributes(decoder),
                beforeAttributes = List(decoder.readVarUInt().toDecodedCount()) { readAttributes(decoder) },
            )
            7 -> {
                val kind = readRootKind(decoder)
                val ref = readYValue(decoder) as? YValue.TypeRef ?: error("XML type child ref is missing")
                ItemContent.XmlType(ref, decoder.readString(), kind)
            }
            8 -> {
                val kind = readRootKind(decoder)
                ItemContent.XmlNode(readXmlNodeValue(decoder), kind)
            }
            9 -> {
                val kind = readRootKind(decoder)
                val value = decoder.readVarUInt().toDecodedCount().toChar().toString()
                val attributes = readAttributes(decoder)
                ItemContent.Text(value, attributes, readAttributes(decoder), kind)
            }
            10 -> {
                val kind = readRootKind(decoder)
                val value = readYValue(decoder)
                val attributes = readAttributes(decoder)
                ItemContent.TextEmbed(value, attributes, readAttributes(decoder), kind)
            }
            11 -> {
                val kind = readRootKind(decoder)
                ItemContent.TextFormat(
                    target = readNullableId(decoder) ?: error("text format target is missing"),
                    length = decoder.readVarUInt(),
                    attributes = readAttributes(decoder),
                    afterAttributes = readAttributes(decoder),
                    beforeAttributes = List(decoder.readVarUInt().toDecodedCount()) { readAttributes(decoder) },
                    kind = kind,
                )
            }
            12 -> {
                val kind = readRootKind(decoder)
                ItemContent.NativeTextFormat(
                    key = decoder.readString(),
                    value = readYValue(decoder),
                    kind = kind,
                )
            }
            13 -> ItemContent.Deleted(readRootKind(decoder), decoder.readVarUInt())
            else -> error("unknown item content tag: $tag")
        }
        return StoreItem(
            id,
            origin,
            rightOrigin,
            parent,
            parentSub,
            content,
            deleted,
            requiresClockContinuity = requiresClockContinuity,
            isGc = isGc,
            unresolvedParent = unresolvedParent,
        )
    }

    private fun writeRootKind(encoder: BinaryEncoder, kind: RootKind) {
        encoder.writeByte(kind.ordinal)
    }

    private fun readRootKind(decoder: BinaryDecoder): RootKind {
        val ordinal = decoder.readByte()
        return RootKind.entries.getOrNull(ordinal) ?: error("unknown root kind ordinal: $ordinal")
    }

    private fun writeNullableId(encoder: BinaryEncoder, id: Id?) {
        encoder.writeBoolean(id != null)
        if (id != null) {
            encoder.writeVarUInt(id.client)
            encoder.writeVarUInt(id.clock)
        }
    }

    private fun readNullableId(decoder: BinaryDecoder): Id? {
        if (!decoder.readBoolean()) return null
        return Id(decoder.readVarUInt(), decoder.readVarUInt())
    }

    private fun writeAttributes(encoder: BinaryEncoder, attributes: Map<String, YValue>) {
        encoder.writeVarUInt(attributes.size.toLong())
        attributes.toSortedMap().forEach { (key, value) ->
            encoder.writeString(key)
            writeYValue(encoder, value)
        }
    }

    private fun readAttributes(decoder: BinaryDecoder): Map<String, YValue> {
        val count = decoder.readVarUInt().toDecodedCount()
        return buildMap {
            repeat(count) {
                put(decoder.readString(), readYValue(decoder))
            }
        }.toSortedMap()
    }

    private fun writeDeleteSet(encoder: BinaryEncoder, deleteSet: DeleteSet) {
        val clients = deleteSet.clients.filterValues { it.isNotEmpty() }
        encoder.writeVarUInt(clients.size.toLong())
        clients.toSortedMap(compareByDescending { it }).forEach { (client, ranges) ->
            encoder.writeVarUInt(client)
            encoder.writeVarUInt(ranges.size.toLong())
            ranges.sortedBy { it.clock }.forEach { range ->
                encoder.writeVarUInt(range.clock)
                encoder.writeVarUInt(range.length)
            }
        }
    }

    private fun readDeleteSet(decoder: BinaryDecoder): DeleteSet {
        val deleteSet = DeleteSet.empty()
        val clientCount = decoder.readVarUInt().toDecodedCount()
        repeat(clientCount) {
            val client = decoder.readVarUInt()
            val rangeCount = decoder.readVarUInt().toDecodedCount()
            repeat(rangeCount) {
                val clock = decoder.readVarUInt()
                val length = decoder.readVarUInt()
                deleteSet.clients.getOrPut(client) { mutableListOf() }.add(DeleteRange(clock, length))
            }
        }
        return deleteSet
    }
}
