package dev.yks

data class IdRange(val clock: Long, val len: Long) {
    init {
        require(clock >= 0) { "clock must be non-negative" }
        require(len >= 0) { "len must be non-negative" }
    }

    val end: Long get() = checkedClockAdd(clock, len, "id range end")

    val attrs: List<ContentAttribute> get() = emptyList()

    fun contains(clock: Long): Boolean = clock >= this.clock && clock < end

    fun copyWith(clock: Long, len: Long): IdRange = IdRange(clock, len)
}

data class MaybeIdRange(val clock: Long, val len: Long, val exists: Boolean)

fun createMaybeIdRange(clock: Long, len: Long, exists: Boolean): MaybeIdRange =
    MaybeIdRange(clock, len, exists)

class IdRanges(ids: List<IdRange> = emptyList()) {
    private val ranges = ids.toMutableList()

    fun copy(): IdRanges = IdRanges(getIds())

    fun add(clock: Long, len: Long) {
        require(clock >= 0) { "clock must be non-negative" }
        require(len >= 0) { "len must be non-negative" }
        if (len == 0L) return
        ranges.add(IdRange(clock, len))
    }

    fun delete(clock: Long, len: Long) {
        require(clock >= 0) { "clock must be non-negative" }
        require(len >= 0) { "len must be non-negative" }
        if (len == 0L) return
        val idSet = createIdSet()
        getIds().forEach { range -> idSet.add(0, range.clock, range.len) }
        idSet.delete(0, clock, len)
        ranges.clear()
        ranges.addAll(idSet.ranges(0))
    }

    fun getIds(): List<IdRange> {
        val idSet = createIdSet()
        ranges.forEach { range -> idSet.add(0, range.clock, range.len) }
        ranges.clear()
        ranges.addAll(idSet.ranges(0))
        return ranges.toList()
    }
}

fun findIndexInIdRanges(ranges: List<IdRange>, clock: Long): Int? {
    var left = 0
    var right = ranges.lastIndex
    while (left <= right) {
        val midIndex = (left + right) ushr 1
        val mid = ranges[midIndex]
        when {
            clock < mid.clock -> right = midIndex - 1
            clock >= mid.end -> left = midIndex + 1
            else -> return midIndex
        }
    }
    return null
}

fun findRangeStartInIdRanges(ranges: List<IdRange>, clock: Long): Int? {
    var left = 0
    var right = ranges.lastIndex
    while (left <= right) {
        val midIndex = (left + right) ushr 1
        val mid = ranges[midIndex]
        when {
            clock < mid.clock -> right = midIndex - 1
            clock >= mid.end -> left = midIndex + 1
            else -> return midIndex
        }
    }
    return if (left < ranges.size) left else null
}

class IdSet(
    val clients: MutableMap<Long, MutableList<IdRange>> = linkedMapOf(),
) {
    fun isEmpty(): Boolean = clients.values.all { it.isEmpty() }

    fun forEach(action: (range: IdRange, client: Long) -> Unit) {
        ranges().forEach { (client, range) -> action(range, client) }
    }

    fun add(client: Long, clock: Long, len: Long) {
        require(client >= 0) { "client must be non-negative" }
        require(clock >= 0) { "clock must be non-negative" }
        require(len >= 0) { "len must be non-negative" }
        if (len == 0L) return
        clients.getOrPut(client) { mutableListOf() }.add(IdRange(clock, len))
        normalize(client)
    }

    fun add(id: Id, len: Long = 1) {
        add(id.client, id.clock, len)
    }

    fun delete(client: Long, clock: Long, len: Long) {
        require(len >= 0) { "len must be non-negative" }
        if (len == 0L) return
        val ranges = clients[client] ?: return
        val deleteEnd = clock + len
        val next = mutableListOf<IdRange>()
        ranges.forEach { range ->
            when {
                range.end <= clock || range.clock >= deleteEnd -> next.add(range)
                else -> {
                    if (range.clock < clock) {
                        next.add(IdRange(range.clock, clock - range.clock))
                    }
                    if (range.end > deleteEnd) {
                        next.add(IdRange(deleteEnd, range.end - deleteEnd))
                    }
                }
            }
        }
        if (next.isEmpty()) {
            clients.remove(client)
        } else {
            clients[client] = next
        }
    }

    fun has(client: Long, clock: Long): Boolean = clients[client]?.any { it.contains(clock) } == true

    fun hasId(id: Id): Boolean = has(id.client, id.clock)

    fun intersects(client: Long, clock: Long, len: Long): Boolean {
        require(len >= 0) { "len must be non-negative" }
        if (len == 0L) return false
        val end = clock + len
        return clients[client]?.any { range -> range.clock < end && range.end > clock } == true
    }

    fun intersects(id: Id, len: Long = 1): Boolean = intersects(id.client, id.clock, len)

    fun slice(client: Long, clock: Long, len: Long): List<MaybeIdRange> {
        require(len >= 0) { "len must be non-negative" }
        val end = clock + len
        val result = mutableListOf<MaybeIdRange>()
        var cursor = clock
        for (range in ranges(client)) {
            if (range.end <= cursor) continue
            if (range.clock >= end) break
            if (cursor < range.clock) {
                result.add(MaybeIdRange(cursor, minOf(range.clock, end) - cursor, false))
                cursor = range.clock
            }
            val overlapStart = maxOf(cursor, range.clock)
            val overlapEnd = minOf(end, range.end)
            if (overlapEnd > overlapStart) {
                result.add(MaybeIdRange(overlapStart, overlapEnd - overlapStart, true))
                cursor = overlapEnd
            }
            if (cursor >= end) break
        }
        if (cursor < end) {
            result.add(MaybeIdRange(cursor, end - cursor, false))
        }
        if (result.isEmpty()) {
            result.add(MaybeIdRange(clock, len, false))
        }
        return result
    }

    fun ranges(client: Long): List<IdRange> = clients[client].orEmpty()

    fun ranges(): List<Pair<Long, IdRange>> = clients.toSortedMap().flatMap { (client, ranges) ->
        ranges.map { client to it }
    }

    internal fun copy(): IdSet {
        return IdSet(clients.mapValuesTo(linkedMapOf()) { (_, ranges) -> ranges.toMutableList() })
    }

    private fun normalize(client: Long) {
        val ranges = clients[client] ?: return
        val sorted = ranges.filter { it.len > 0 }.sortedBy { it.clock }
        val merged = mutableListOf<IdRange>()
        sorted.forEach { range ->
            val last = merged.lastOrNull()
            if (last == null || range.clock > last.end) {
                merged.add(range)
            } else if (range.end > last.end) {
                merged[merged.lastIndex] = IdRange(last.clock, range.end - last.clock)
            }
        }
        if (merged.isEmpty()) {
            clients.remove(client)
        } else {
            clients[client] = merged
        }
    }
}

fun createIdSet(): IdSet = IdSet()

fun equalIdSets(left: IdSet, right: IdSet): Boolean = left.ranges() == right.ranges()

fun mergeIdSets(sets: List<IdSet>): IdSet {
    val merged = createIdSet()
    sets.forEach { set ->
        set.ranges().forEach { (client, range) -> merged.add(client, range.clock, range.len) }
    }
    return merged
}

fun insertIntoIdSet(dest: IdSet, src: IdSet) {
    src.ranges().forEach { (client, range) -> dest.add(client, range.clock, range.len) }
}

fun _deleteRangeFromIdSet(set: IdSet, client: Long, clock: Long, len: Long) {
    set.delete(client, clock, len)
}

fun _deleteRangeFromIdSet(set: IdMap, client: Long, clock: Long, len: Long) {
    set.delete(client, clock, len)
}

fun diffIdSet(set: IdSet, exclude: IdSet): IdSet {
    val result = createIdSet()
    set.ranges().forEach { (client, range) ->
        set.slice(client, range.clock, range.len)
            .filter { it.exists }
            .forEach { slice ->
                var cursor = slice.clock
                exclude.slice(client, slice.clock, slice.len).forEach { excluded ->
                    if (excluded.clock > cursor) {
                        result.add(client, cursor, excluded.clock - cursor)
                    }
                    if (!excluded.exists) {
                        result.add(client, excluded.clock, excluded.len)
                    }
                    cursor = excluded.clock + excluded.len
                }
            }
    }
    return result
}

fun diffIdSet(set: IdSet, exclude: IdMap): IdSet = diffIdSet(set, createIdSetFromIdMap(exclude))

fun _diffSet(set: IdSet, exclude: IdSet): IdSet = diffIdSet(set, exclude)

fun _diffSet(set: IdSet, exclude: IdMap): IdSet = diffIdSet(set, exclude)

fun intersectSets(left: IdSet, right: IdSet): IdSet {
    val result = createIdSet()
    left.ranges().forEach { (client, range) ->
        right.slice(client, range.clock, range.len)
            .filter { it.exists }
            .forEach { result.add(client, it.clock, it.len) }
    }
    return result
}

fun intersectSets(left: IdSet, right: IdMap): IdSet = intersectSets(left, createIdSetFromIdMap(right))

fun _intersectSets(left: IdSet, right: IdSet): IdSet = intersectSets(left, right)

fun _intersectSets(left: IdSet, right: IdMap): IdSet = intersectSets(left, right)

fun writeIdSet(encoder: BinaryEncoder, idSet: IdSet) {
    requireYjsSafeIdSet(idSet)
    val clients = idSet.clients.filterValues { it.isNotEmpty() }.toSortedMap(compareByDescending { it })
    encoder.writeVarUInt(clients.size.toLong())
    clients.forEach { (client, ranges) ->
        encoder.writeVarUInt(client)
        encoder.writeVarUInt(ranges.size.toLong())
        ranges.forEach { range ->
            encoder.writeVarUInt(range.clock)
            encoder.writeVarUInt(range.len)
        }
    }
}

fun writeIdSet(encoder: IdSetEncoderV1, idSet: IdSet) {
    requireYjsSafeIdSet(idSet)
    val clients = idSet.clients.filterValues { it.isNotEmpty() }.toSortedMap(compareByDescending { it })
    encoder.restEncoder.writeVarUInt(clients.size.toLong())
    clients.forEach { (client, ranges) ->
        encoder.resetIdSetCurVal()
        encoder.restEncoder.writeVarUInt(client)
        encoder.restEncoder.writeVarUInt(ranges.size.toLong())
        ranges.forEach { range ->
            encoder.writeIdSetClock(range.clock)
            encoder.writeIdSetLen(range.len)
        }
    }
}

internal fun requireYjsSafeIdSet(idSet: IdSet) {
    idSet.ranges().forEach { (client, range) ->
        require(client.isYjsSafeVarUint()) {
            "id-set client must be a JavaScript safe unsigned integer: $client"
        }
        require(range.clock.isYjsSafeVarUint()) {
            "id-set clock must be a JavaScript safe unsigned integer: ${range.clock}"
        }
        require(range.len.isYjsSafeVarUint()) {
            "id-set length must be a JavaScript safe unsigned integer: ${range.len}"
        }
        require(range.end <= YJS_MAX_SAFE_INTEGER) {
            "id-set range end must be a JavaScript safe unsigned integer: ${range.end}"
        }
    }
}

fun encodeIdSet(idSet: IdSet): ByteArray {
    val encoder = IdSetEncoderV2()
    writeIdSet(encoder, idSet)
    return encoder.toUint8Array()
}

fun readIdSet(decoder: BinaryDecoder): IdSet {
    val idSet = createIdSet()
    repeat(decoder.readVarUInt().toDecodedCount()) {
        val client = decoder.readVarUInt()
        repeat(decoder.readVarUInt().toDecodedCount()) {
            idSet.add(client, decoder.readVarUInt(), decoder.readVarUInt())
        }
    }
    return idSet
}

fun readIdSet(decoder: IdSetDecoderV1): IdSet {
    val idSet = createIdSet()
    repeat(decoder.restDecoder.readVarUInt().toDecodedCount()) {
        decoder.resetDsCurVal()
        val client = decoder.restDecoder.readVarUInt()
        repeat(decoder.restDecoder.readVarUInt().toDecodedCount()) {
            idSet.add(client, decoder.readDsClock(), decoder.readDsLen())
        }
    }
    return idSet
}

fun readAndApplyDeleteSet(decoder: BinaryDecoder, doc: YDoc, origin: Any? = null): ByteArray? =
    applyKnownDeleteSetAndEncodeUnapplied(readIdSet(decoder), doc, origin)

fun readAndApplyDeleteSet(decoder: IdSetDecoderV1, doc: YDoc, origin: Any? = null): ByteArray? =
    applyKnownDeleteSetAndEncodeUnapplied(readIdSet(decoder), doc, origin)

private fun applyKnownDeleteSetAndEncodeUnapplied(deleteIds: IdSet, doc: YDoc, origin: Any?): ByteArray? {
    val known = createIdSet()
    val unapplied = createIdSet()
    deleteIds.ranges().forEach { (client, range) ->
        val state = doc.store.getClock(client)
        if (range.clock < state) {
            val knownEnd = minOf(range.end, state)
            if (knownEnd > range.clock) {
                known.add(client, range.clock, knownEnd - range.clock)
            }
            if (range.end > state) {
                unapplied.add(client, state, range.end - state)
            }
        } else {
            unapplied.add(client, range.clock, range.len)
        }
    }
    if (!known.isEmpty()) {
        doc.applyUpdate(DocumentUpdate(emptyList(), known.toDeleteSet()), origin)
    }
    return if (unapplied.isEmpty()) {
        null
    } else {
        UpdateCodec.encode(DocumentUpdate(emptyList(), unapplied.toDeleteSet()))
    }
}

fun decodeIdSet(bytes: ByteArray): IdSet {
    val decoder = IdSetDecoderV2(bytes)
    val idSet = readIdSet(decoder)
    check(!decoder.hasRemaining()) { "IdSet has trailing bytes" }
    return idSet
}

data class ContentAttribute(
    val name: String,
    val value: YValue,
) {
    val `val`: Any? get() = value.toAny()

    fun toAny(): Any? = value.toAny()

    fun hash(): String {
        val encoder = BinaryEncoder()
        encoder.writeString(name)
        writeYValue(encoder, value)
        return java.util.Base64.getEncoder().encodeToString(encoder.toByteArray())
    }
}

fun createContentAttribute(name: String, value: Any?): ContentAttribute = ContentAttribute(name, YValue.from(value))

data class AttrRange(
    val clock: Long,
    val len: Long,
    val attrs: List<ContentAttribute>,
) {
    val end: Long get() = clock + len

    fun copyWith(clock: Long, len: Long): AttrRange = AttrRange(clock, len, attrs)
}

data class MaybeAttrRange(
    val clock: Long,
    val len: Long,
    val attrs: List<ContentAttribute>?,
)

fun createMaybeAttrRange(
    clock: Long,
    len: Long,
    attrs: List<ContentAttribute>?,
): MaybeAttrRange = MaybeAttrRange(clock, len, attrs)

class AttrRanges(ids: List<AttrRange> = emptyList()) {
    private val ranges = ids.toMutableList()

    fun copy(): AttrRanges = AttrRanges(getIds())

    fun add(clock: Long, len: Long, attrs: List<ContentAttribute>) {
        require(clock >= 0) { "clock must be non-negative" }
        require(len >= 0) { "len must be non-negative" }
        if (len == 0L) return
        ranges.add(AttrRange(clock, len, attrs))
    }

    fun getIds(): List<AttrRange> {
        val idMap = createIdMap()
        ranges.forEach { range -> idMap.add(0, range.clock, range.len, range.attrs) }
        ranges.clear()
        ranges.addAll(idMap.ranges(0))
        return ranges.toList()
    }
}

class IdMap(
    val clients: MutableMap<Long, MutableList<AttrRange>> = linkedMapOf(),
    val attrsH: MutableMap<String, ContentAttribute> = linkedMapOf(),
    val attrs: MutableSet<ContentAttribute> = linkedSetOf(),
) {
    init {
        clients.values.flatten().forEach { range ->
            range.attrs.forEach(::cacheAttribute)
        }
    }

    fun isEmpty(): Boolean = clients.values.all { it.isEmpty() }

    fun forEach(action: (range: AttrRange, client: Long) -> Unit) {
        ranges().forEach { (client, range) -> action(range, client) }
    }

    fun add(client: Long, clock: Long, len: Long, attrs: List<ContentAttribute>) {
        require(client >= 0) { "client must be non-negative" }
        require(clock >= 0) { "clock must be non-negative" }
        require(len >= 0) { "len must be non-negative" }
        if (len == 0L) return
        val checkedAttrs = attrs.map(::cacheAttribute).distinct()
        clients.getOrPut(client) { mutableListOf() }.add(AttrRange(clock, len, checkedAttrs))
        normalize(client)
    }

    fun delete(client: Long, clock: Long, len: Long) {
        require(len >= 0) { "len must be non-negative" }
        if (len == 0L) return
        val oldRanges = clients[client] ?: return
        val deleteEnd = clock + len
        val materialized = materialize(oldRanges)
            .filterKeys { it < clock || it >= deleteEnd }
        val normalized = rangesFromMaterialized(materialized)
        if (normalized.isEmpty()) {
            clients.remove(client)
        } else {
            clients[client] = normalized.toMutableList()
        }
    }

    fun has(client: Long, clock: Long): Boolean = clients[client]?.any { clock >= it.clock && clock < it.end } == true

    fun hasId(id: Id): Boolean = has(id.client, id.clock)

    fun slice(client: Long, clock: Long, len: Long): List<MaybeAttrRange> {
        require(len >= 0) { "len must be non-negative" }
        val byClock = materialize(clients[client].orEmpty())
        val result = mutableListOf<MaybeAttrRange>()
        var cursor = clock
        val end = clock + len
        while (cursor < end) {
            val attrs = byClock[cursor]
            val start = cursor
            cursor++
            while (cursor < end && byClock[cursor] == attrs) cursor++
            result.add(MaybeAttrRange(start, cursor - start, attrs))
        }
        if (result.isEmpty()) {
            result.add(MaybeAttrRange(clock, len, null))
        }
        return result
    }

    fun sliceId(id: Id, len: Long): List<MaybeAttrRange> = slice(id.client, id.clock, len)

    fun ranges(client: Long): List<AttrRange> = clients[client].orEmpty()

    fun ranges(): List<Pair<Long, AttrRange>> = clients.toSortedMap().flatMap { (client, ranges) ->
        ranges.map { client to it }
    }

    internal fun copy(): IdMap {
        return IdMap(clients.mapValuesTo(linkedMapOf()) { (_, ranges) -> ranges.toMutableList() })
    }

    private fun cacheAttribute(attr: ContentAttribute): ContentAttribute {
        val hash = attr.hash()
        val existing = attrsH[hash]
        if (existing != null) return existing
        attrsH[hash] = attr
        attrs.add(attr)
        return attr
    }

    private fun normalize(client: Long) {
        val materialized = materialize(clients[client].orEmpty())
        val normalized = rangesFromMaterialized(materialized)
        if (normalized.isEmpty()) {
            clients.remove(client)
        } else {
            clients[client] = normalized.toMutableList()
        }
    }

    private fun materialize(ranges: List<AttrRange>): MutableMap<Long, List<ContentAttribute>> {
        val byClock = sortedMapOf<Long, List<ContentAttribute>>()
        ranges.sortedWith(compareBy<AttrRange> { it.clock }.thenBy { it.len }).forEach { range ->
            for (clock in range.clock until range.end) {
                byClock[clock] = joinAttrs(byClock[clock].orEmpty(), range.attrs)
            }
        }
        return byClock
    }

    private fun rangesFromMaterialized(byClock: Map<Long, List<ContentAttribute>>): List<AttrRange> {
        if (byClock.isEmpty()) return emptyList()
        val result = mutableListOf<AttrRange>()
        val clocks = byClock.keys.sorted()
        var start = clocks.first()
        var previous = start
        var attrs = byClock.getValue(start)
        for (clock in clocks.drop(1)) {
            val currentAttrs = byClock.getValue(clock)
            if (clock == previous + 1 && currentAttrs == attrs) {
                previous = clock
            } else {
                result.add(AttrRange(start, previous - start + 1, attrs))
                start = clock
                previous = clock
                attrs = currentAttrs
            }
        }
        result.add(AttrRange(start, previous - start + 1, attrs))
        return result.filter { it.len > 0 }
    }
}

fun createIdMap(): IdMap = IdMap()

fun createIdMapFromIdSet(idSet: IdSet, attrs: List<ContentAttribute>): IdMap {
    val idMap = createIdMap()
    idSet.ranges().forEach { (client, range) -> idMap.add(client, range.clock, range.len, attrs) }
    return idMap
}

fun createIdSetFromIdMap(idMap: IdMap): IdSet {
    val idSet = createIdSet()
    idMap.ranges().forEach { (client, range) -> idSet.add(client, range.clock, range.len) }
    return idSet
}

fun equalIdMaps(left: IdMap, right: IdMap): Boolean = left.ranges() == right.ranges()

fun idmapAttrsEqual(left: List<ContentAttribute>, right: List<ContentAttribute>): Boolean =
    left.size == right.size && left.all { attr -> right.contains(attr) }

fun mergeIdMaps(maps: List<IdMap>): IdMap {
    val merged = createIdMap()
    maps.forEach { idMap ->
        idMap.ranges().forEach { (client, range) -> merged.add(client, range.clock, range.len, range.attrs) }
    }
    return merged
}

fun insertIntoIdMap(dest: IdMap, src: IdMap) {
    src.ranges().forEach { (client, range) -> dest.add(client, range.clock, range.len, range.attrs) }
}

fun insertIntoIdMap(dest: IdMap, src: IdSet) {
    src.ranges().forEach { (client, range) -> dest.add(client, range.clock, range.len, range.attrs) }
}

fun diffIdMap(idMap: IdMap, exclude: IdSet): IdMap {
    val result = createIdMap()
    idMap.ranges().forEach { (client, range) ->
        exclude.slice(client, range.clock, range.len).forEach { slice ->
            if (!slice.exists) {
                val attrs = idMap.slice(client, slice.clock, slice.len).firstOrNull()?.attrs.orEmpty()
                result.add(client, slice.clock, slice.len, attrs)
            }
        }
    }
    return result
}

fun diffIdMap(idMap: IdMap, exclude: IdMap): IdMap = diffIdMap(idMap, createIdSetFromIdMap(exclude))

fun _diffSet(set: IdMap, exclude: IdSet): IdMap = diffIdMap(set, exclude)

fun _diffSet(set: IdMap, exclude: IdMap): IdMap = diffIdMap(set, exclude)

fun intersectMaps(left: IdMap, right: IdMap): IdMap {
    val result = createIdMap()
    left.ranges().forEach { (client, range) ->
        right.slice(client, range.clock, range.len).forEach { slice ->
            val rightAttrs = slice.attrs
            if (rightAttrs != null) {
                val leftAttrs = left.slice(client, slice.clock, slice.len).firstOrNull()?.attrs.orEmpty()
                result.add(client, slice.clock, slice.len, joinAttrs(leftAttrs, rightAttrs))
            }
        }
    }
    return result
}

fun _intersectSets(left: IdMap, right: IdMap): IdMap = intersectMaps(left, right)

fun intersectMaps(left: IdMap, right: IdSet): IdMap {
    val result = createIdMap()
    left.ranges().forEach { (client, range) ->
        right.slice(client, range.clock, range.len)
            .filter { it.exists }
            .forEach { result.add(client, it.clock, it.len, range.attrs) }
    }
    return result
}

fun _intersectSets(left: IdMap, right: IdSet): IdMap = intersectMaps(left, right)

fun filterIdMap(idMap: IdMap, predicate: (List<ContentAttribute>) -> Boolean): IdMap {
    val filtered = createIdMap()
    idMap.ranges().forEach { (client, range) ->
        if (predicate(range.attrs)) {
            filtered.add(client, range.clock, range.len, range.attrs)
        }
    }
    return filtered
}

fun writeIdMap(encoder: BinaryEncoder, idMap: IdMap) {
    val clients = idMap.clients.filterValues { it.isNotEmpty() }.toSortedMap()
    encoder.writeVarUInt(clients.size.toLong())
    clients.forEach { (client, ranges) ->
        encoder.writeVarUInt(client)
        encoder.writeVarUInt(ranges.size.toLong())
        ranges.forEach { range ->
            encoder.writeVarUInt(range.clock)
            encoder.writeVarUInt(range.len)
            encoder.writeVarUInt(range.attrs.size.toLong())
            range.attrs.forEach { attr ->
                encoder.writeString(attr.name)
                writeYValue(encoder, attr.value)
            }
        }
    }
}

fun writeIdMap(encoder: IdSetEncoderV1, idMap: IdMap) {
    val clients = idMap.clients.filterValues { it.isNotEmpty() }.toSortedMap()
    encoder.restEncoder.writeVarUInt(clients.size.toLong())
    var lastWrittenClientId = 0L
    val visitedAttributes = linkedMapOf<ContentAttribute, Long>()
    val visitedAttrNames = linkedMapOf<String, Long>()
    clients.forEach { (client, _) ->
        encoder.resetIdSetCurVal()
        require(client >= lastWrittenClientId) { "IdMap clients must be written in ascending order" }
        encoder.restEncoder.writeVarUInt(client - lastWrittenClientId)
        lastWrittenClientId = client
        val ranges = idMap.ranges(client)
        encoder.restEncoder.writeVarUInt(ranges.size.toLong())
        ranges.forEach { range ->
            encoder.writeIdSetClock(range.clock)
            encoder.writeIdSetLen(range.len)
            encoder.restEncoder.writeVarUInt(range.attrs.size.toLong())
            range.attrs.forEach { attr ->
                val attrId = visitedAttributes[attr]
                if (attrId != null) {
                    encoder.restEncoder.writeVarUInt(attrId)
                } else {
                    val newAttrId = visitedAttributes.size.toLong()
                    visitedAttributes[attr] = newAttrId
                    encoder.restEncoder.writeVarUInt(newAttrId)
                    val attrNameId = visitedAttrNames[attr.name]
                    if (attrNameId != null) {
                        encoder.restEncoder.writeVarUInt(attrNameId)
                    } else {
                        val newAttrNameId = visitedAttrNames.size.toLong()
                        visitedAttrNames[attr.name] = newAttrNameId
                        encoder.restEncoder.writeVarUInt(newAttrNameId)
                        encoder.restEncoder.writeString(attr.name)
                    }
                    writeYValue(encoder.restEncoder, attr.value)
                }
            }
        }
    }
}

fun encodeIdMap(idMap: IdMap): ByteArray {
    val encoder = IdSetEncoderV2()
    writeIdMap(encoder, idMap)
    return encoder.toUint8Array()
}

fun readIdMap(decoder: BinaryDecoder): IdMap {
    val idMap = createIdMap()
    repeat(decoder.readVarUInt().toDecodedCount()) {
        val client = decoder.readVarUInt()
        repeat(decoder.readVarUInt().toDecodedCount()) {
            val clock = decoder.readVarUInt()
            val len = decoder.readVarUInt()
            val attrs = List(decoder.readVarUInt().toDecodedCount()) {
                ContentAttribute(decoder.readString(), readYValue(decoder))
            }
            idMap.add(client, clock, len, attrs)
        }
    }
    return idMap
}

fun readIdMap(decoder: IdSetDecoderV1): IdMap {
    val idMap = createIdMap()
    val visitedAttributes = mutableListOf<ContentAttribute>()
    val visitedAttrNames = mutableListOf<String>()
    var lastClientId = 0L
    repeat(decoder.restDecoder.readVarUInt().toDecodedCount()) {
        decoder.resetDsCurVal()
        val client = lastClientId + decoder.restDecoder.readVarUInt()
        lastClientId = client
        repeat(decoder.restDecoder.readVarUInt().toDecodedCount()) {
            val clock = decoder.readDsClock()
            val len = decoder.readDsLen()
            val attrs = List(decoder.restDecoder.readVarUInt().toDecodedCount()) {
                val attrId = decoder.restDecoder.readVarUInt().toDecodedCount()
                if (attrId >= visitedAttributes.size) {
                    val attrNameId = decoder.restDecoder.readVarUInt().toDecodedCount()
                    if (attrNameId >= visitedAttrNames.size) {
                        visitedAttrNames.add(decoder.restDecoder.readString())
                    }
                    val attrName = visitedAttrNames.getOrNull(attrNameId)
                        ?: error("unknown IdMap attribute name id: $attrNameId")
                    visitedAttributes.add(ContentAttribute(attrName, readYValue(decoder.restDecoder)))
                }
                visitedAttributes.getOrNull(attrId) ?: error("unknown IdMap attribute id: $attrId")
            }
            idMap.add(client, clock, len, attrs)
        }
    }
    return idMap
}

fun decodeIdMap(bytes: ByteArray): IdMap {
    val decoder = IdSetDecoderV2(bytes)
    val idMap = readIdMap(decoder)
    check(!decoder.hasRemaining()) { "IdMap has trailing bytes" }
    return idMap
}

private fun joinAttrs(left: List<ContentAttribute>, right: List<ContentAttribute>): List<ContentAttribute> {
    val result = left.toMutableList()
    right.forEach { attr ->
        if (result.none { it == attr }) {
            result.add(attr)
        }
    }
    return result
}
