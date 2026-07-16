package dev.yks

data class IdRange(val clock: Long, val len: Long) {
    init {
        require(clock >= 0) { "clock must be non-negative" }
        require(len >= 0) { "len must be non-negative" }
    }

    val end: Long = checkedClockAdd(clock, len, "id range end")

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
    clients: Map<Long, List<IdRange>> = emptyMap(),
) {
    private val clientRanges: MutableMap<Long, MutableList<IdRange>> = linkedMapOf()

    init {
        clients.forEach { (client, ranges) ->
            ranges.forEach { range -> add(client, range.clock, range.len) }
        }
    }

    val clients: Map<Long, List<IdRange>>
        get() = clientRanges.mapValues { (_, ranges) -> ranges.toList() }

    fun isEmpty(): Boolean = clientRanges.values.all { it.isEmpty() }

    fun forEach(action: (range: IdRange, client: Long) -> Unit) {
        ranges().forEach { (client, range) -> action(range, client) }
    }

    fun add(client: Long, clock: Long, len: Long) {
        require(client >= 0) { "client must be non-negative" }
        require(clock >= 0) { "clock must be non-negative" }
        require(len >= 0) { "len must be non-negative" }
        if (len == 0L) return
        clientRanges.getOrPut(client) { mutableListOf() }.addAndMerge(IdRange(clock, len))
    }

    fun add(id: Id, len: Long = 1) {
        add(id.client, id.clock, len)
    }

    fun delete(client: Long, clock: Long, len: Long) {
        require(clock >= 0) { "clock must be non-negative" }
        require(len >= 0) { "len must be non-negative" }
        if (len == 0L) return
        val ranges = clientRanges[client] ?: return
        val deleteEnd = checkedClockAdd(clock, len, "id-set delete end")
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
            clientRanges.remove(client)
        } else {
            clientRanges[client] = next
        }
    }

    fun has(client: Long, clock: Long): Boolean =
        clientRanges[client]?.let { ranges -> findIndexInIdRanges(ranges, clock) != null } == true

    fun hasId(id: Id): Boolean = has(id.client, id.clock)

    fun intersects(client: Long, clock: Long, len: Long): Boolean {
        require(clock >= 0) { "clock must be non-negative" }
        require(len >= 0) { "len must be non-negative" }
        if (len == 0L) return false
        val end = checkedClockAdd(clock, len, "id-set intersection end")
        val ranges = clientRanges[client] ?: return false
        val index = findRangeStartInIdRanges(ranges, clock) ?: return false
        return ranges[index].clock < end
    }

    fun intersects(id: Id, len: Long = 1): Boolean = intersects(id.client, id.clock, len)

    fun slice(client: Long, clock: Long, len: Long): List<MaybeIdRange> {
        require(clock >= 0) { "clock must be non-negative" }
        require(len >= 0) { "len must be non-negative" }
        val end = checkedClockAdd(clock, len, "id-set slice end")
        val result = mutableListOf<MaybeIdRange>()
        var cursor = clock
        val ranges = ranges(client)
        val firstRangeIndex = findRangeStartInIdRanges(ranges, clock) ?: ranges.size
        for (index in firstRangeIndex until ranges.size) {
            val range = ranges[index]
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

    fun ranges(client: Long): List<IdRange> = clientRanges[client]?.toList().orEmpty()

    fun ranges(): List<Pair<Long, IdRange>> = buildList {
        clientRanges.keys.sorted().forEach { client ->
            clientRanges.getValue(client).forEach { range -> add(client to range) }
        }
    }

    internal fun copy(): IdSet =
        IdSet(clientRanges.mapValuesTo(linkedMapOf()) { (_, ranges) -> ranges.toList() })

    internal fun replaceWith(other: IdSet) {
        clientRanges.clear()
        other.clientRanges.forEach { (client, ranges) ->
            clientRanges[client] = ranges.toMutableList()
        }
    }
}

private fun MutableList<IdRange>.addAndMerge(incoming: IdRange) {
    var low = 0
    var high = size
    while (low < high) {
        val middle = (low + high) ushr 1
        if (this[middle].end < incoming.clock) low = middle + 1 else high = middle
    }

    val firstAffected = low
    var mergedStart = incoming.clock
    var mergedEnd = incoming.end
    while (low < size && this[low].clock <= mergedEnd) {
        mergedStart = minOf(mergedStart, this[low].clock)
        mergedEnd = maxOf(mergedEnd, this[low].end)
        low++
    }

    if (firstAffected < low) subList(firstAffected, low).clear()
    add(firstAffected, IdRange(mergedStart, mergedEnd - mergedStart))
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
        exclude.slice(client, range.clock, range.len).forEach { excluded ->
            if (!excluded.exists) {
                result.add(client, excluded.clock, excluded.len)
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
    init {
        require(clock >= 0) { "clock must be non-negative" }
        require(len >= 0) { "len must be non-negative" }
    }

    val end: Long = checkedClockAdd(clock, len, "attribute range end")

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
    clients: Map<Long, List<AttrRange>> = emptyMap(),
    attrsH: Map<String, ContentAttribute> = emptyMap(),
    attrs: Set<ContentAttribute> = emptySet(),
) {
    private val clientRanges: MutableMap<Long, MutableList<AttrRange>> = linkedMapOf()
    private val attributeHashes: MutableMap<String, ContentAttribute> = linkedMapOf()
    private val attributes: MutableSet<ContentAttribute> = linkedSetOf()

    init {
        attrsH.values.forEach(::cacheAttribute)
        attrs.forEach(::cacheAttribute)
        clients.forEach { (client, ranges) ->
            ranges.forEach { range -> add(client, range.clock, range.len, range.attrs) }
        }
    }

    val clients: Map<Long, List<AttrRange>>
        get() = clientRanges.mapValues { (_, ranges) -> ranges.toList() }

    val attrsH: Map<String, ContentAttribute>
        get() = attributeHashes.toMap()

    val attrs: Set<ContentAttribute>
        get() = attributes.toSet()

    fun isEmpty(): Boolean = clientRanges.values.all { it.isEmpty() }

    fun forEach(action: (range: AttrRange, client: Long) -> Unit) {
        ranges().forEach { (client, range) -> action(range, client) }
    }

    fun add(client: Long, clock: Long, len: Long, attrs: List<ContentAttribute>) {
        require(client >= 0) { "client must be non-negative" }
        require(clock >= 0) { "clock must be non-negative" }
        require(len >= 0) { "len must be non-negative" }
        if (len == 0L) return
        val range = AttrRange(clock, len, attrs)
        val checkedAttrs = attrs.map(::cacheAttribute).distinct()
        val storedRange = range.copy(attrs = checkedAttrs)
        val ranges = clientRanges.getOrPut(client) { mutableListOf() }
        if (!ranges.addIfDisjoint(storedRange)) {
            ranges.add(storedRange)
            normalize(client)
        }
    }

    fun delete(client: Long, clock: Long, len: Long) {
        require(clock >= 0) { "clock must be non-negative" }
        require(len >= 0) { "len must be non-negative" }
        if (len == 0L) return
        val oldRanges = clientRanges[client] ?: return
        val deleteEnd = checkedClockAdd(clock, len, "id-map delete end")
        val remaining = buildList {
            oldRanges.forEach { range ->
                when {
                    range.end <= clock || range.clock >= deleteEnd -> add(range)
                    else -> {
                        if (range.clock < clock) add(range.copyWith(range.clock, clock - range.clock))
                        if (range.end > deleteEnd) add(range.copyWith(deleteEnd, range.end - deleteEnd))
                    }
                }
            }
        }
        if (remaining.isEmpty()) {
            clientRanges.remove(client)
        } else {
            clientRanges[client] = remaining.toMutableList()
        }
    }

    fun has(client: Long, clock: Long): Boolean = clientRanges[client]?.findContainingIndex(clock) != null

    fun hasId(id: Id): Boolean = has(id.client, id.clock)

    fun slice(client: Long, clock: Long, len: Long): List<MaybeAttrRange> {
        require(clock >= 0) { "clock must be non-negative" }
        require(len >= 0) { "len must be non-negative" }
        val end = checkedClockAdd(clock, len, "id-map slice end")
        if (len == 0L) return listOf(MaybeAttrRange(clock, 0, null))

        val result = mutableListOf<MaybeAttrRange>()
        var cursor = clock
        val ranges = clientRanges[client].orEmpty()
        val firstRangeIndex = ranges.findRangeStartIndex(clock) ?: ranges.size
        for (index in firstRangeIndex until ranges.size) {
            val range = ranges[index]
            if (range.clock >= end) break
            if (range.end <= cursor) continue
            if (cursor < range.clock) {
                result.append(MaybeAttrRange(cursor, minOf(range.clock, end) - cursor, null))
            }
            val overlapStart = maxOf(cursor, range.clock)
            val overlapEnd = minOf(end, range.end)
            if (overlapEnd > overlapStart) {
                result.append(MaybeAttrRange(overlapStart, overlapEnd - overlapStart, range.attrs))
                cursor = overlapEnd
            }
            if (cursor >= end) break
        }
        if (cursor < end) result.append(MaybeAttrRange(cursor, end - cursor, null))
        return result
    }

    fun sliceId(id: Id, len: Long): List<MaybeAttrRange> = slice(id.client, id.clock, len)

    fun ranges(client: Long): List<AttrRange> = clientRanges[client]?.toList().orEmpty()

    fun ranges(): List<Pair<Long, AttrRange>> = buildList {
        clientRanges.keys.sorted().forEach { client ->
            clientRanges.getValue(client).forEach { range -> add(client to range) }
        }
    }

    internal fun copy(): IdMap {
        return IdMap(clientRanges.mapValuesTo(linkedMapOf()) { (_, ranges) -> ranges.toList() })
    }

    private fun cacheAttribute(attr: ContentAttribute): ContentAttribute {
        val hash = attr.hash()
        val existing = attributeHashes[hash]
        if (existing != null) return existing
        attributeHashes[hash] = attr
        attributes.add(attr)
        return attr
    }

    private fun normalize(client: Long) {
        val normalized = normalizeAttrRanges(clientRanges[client].orEmpty())
        if (normalized.isEmpty()) {
            clientRanges.remove(client)
        } else {
            clientRanges[client] = normalized.toMutableList()
        }
    }
}

private data class IndexedAttrRange(
    val order: Int,
    val range: AttrRange,
)

private fun MutableList<AttrRange>.addIfDisjoint(incoming: AttrRange): Boolean {
    val last = lastOrNull()
    if (last == null) {
        add(incoming)
        return true
    }
    if (last.end <= incoming.clock) {
        if (last.end == incoming.clock && last.attrs == incoming.attrs) {
            this[lastIndex] = last.copyWith(
                last.clock,
                checkedClockAdd(last.len, incoming.len, "merged attribute range length"),
            )
        } else {
            add(incoming)
        }
        return true
    }

    var low = 0
    var high = size
    while (low < high) {
        val middle = (low + high) ushr 1
        if (this[middle].clock < incoming.clock) low = middle + 1 else high = middle
    }
    var insertionIndex = low
    val left = getOrNull(insertionIndex - 1)
    val right = getOrNull(insertionIndex)
    if (left?.let { range -> range.end > incoming.clock } == true) return false
    if (right?.let { range -> incoming.end > range.clock } == true) return false

    var merged = incoming
    if (left != null && left.end == merged.clock && left.attrs == merged.attrs) {
        merged = left.copyWith(
            left.clock,
            checkedClockAdd(left.len, merged.len, "merged attribute range length"),
        )
        removeAt(--insertionIndex)
    }
    val next = getOrNull(insertionIndex)
    if (next != null && merged.end == next.clock && merged.attrs == next.attrs) {
        merged = merged.copyWith(
            merged.clock,
            checkedClockAdd(merged.len, next.len, "merged attribute range length"),
        )
        removeAt(insertionIndex)
    }
    add(insertionIndex, merged)
    return true
}

private fun normalizeAttrRanges(ranges: List<AttrRange>): List<AttrRange> {
    val indexedRanges = ranges.filter { range -> range.len > 0 }.mapIndexed(::IndexedAttrRange)
    if (indexedRanges.isEmpty()) return emptyList()

    val starts = mutableMapOf<Long, MutableList<IndexedAttrRange>>()
    val ends = mutableMapOf<Long, MutableList<IndexedAttrRange>>()
    val boundaries = sortedSetOf<Long>()
    indexedRanges.forEach { indexed ->
        starts.getOrPut(indexed.range.clock) { mutableListOf() }.add(indexed)
        ends.getOrPut(indexed.range.end) { mutableListOf() }.add(indexed)
        boundaries.add(indexed.range.clock)
        boundaries.add(indexed.range.end)
    }

    val active = java.util.TreeSet(
        compareBy<IndexedAttrRange> { indexed -> indexed.range.clock }
            .thenBy { indexed -> indexed.range.len }
            .thenBy { indexed -> indexed.order },
    )
    val orderedBoundaries = boundaries.toList()
    val normalized = mutableListOf<AttrRange>()
    for (index in 0 until orderedBoundaries.lastIndex) {
        val boundary = orderedBoundaries[index]
        ends[boundary]?.forEach(active::remove)
        starts[boundary]?.forEach(active::add)
        val nextBoundary = orderedBoundaries[index + 1]
        if (active.isEmpty() || nextBoundary == boundary) continue

        val attrs = active.fold(emptyList<ContentAttribute>()) { combined, indexed ->
            joinAttrs(combined, indexed.range.attrs)
        }
        normalized.append(AttrRange(boundary, nextBoundary - boundary, attrs))
    }
    return normalized
}

private fun MutableList<AttrRange>.append(range: AttrRange) {
    val previous = lastOrNull()
    if (previous != null && previous.end == range.clock && previous.attrs == range.attrs) {
        this[lastIndex] = previous.copyWith(
            previous.clock,
            checkedClockAdd(previous.len, range.len, "merged attribute range length"),
        )
    } else {
        add(range)
    }
}

private fun List<AttrRange>.findContainingIndex(clock: Long): Int? {
    var low = 0
    var high = lastIndex
    while (low <= high) {
        val middle = (low + high) ushr 1
        val range = this[middle]
        when {
            clock < range.clock -> high = middle - 1
            clock >= range.end -> low = middle + 1
            else -> return middle
        }
    }
    return null
}

private fun List<AttrRange>.findRangeStartIndex(clock: Long): Int? {
    var low = 0
    var high = lastIndex
    while (low <= high) {
        val middle = (low + high) ushr 1
        val range = this[middle]
        when {
            clock < range.clock -> high = middle - 1
            clock >= range.end -> low = middle + 1
            else -> return middle
        }
    }
    return low.takeIf { index -> index < size }
}

private fun MutableList<MaybeAttrRange>.append(range: MaybeAttrRange) {
    if (range.len == 0L) return
    val previous = lastOrNull()
    if (
        previous != null &&
        checkedClockAdd(previous.clock, previous.len, "maybe attribute range end") == range.clock &&
        previous.attrs == range.attrs
    ) {
        this[lastIndex] = previous.copy(
            len = checkedClockAdd(previous.len, range.len, "merged maybe attribute range length"),
        )
    } else {
        add(range)
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
                result.add(client, slice.clock, slice.len, range.attrs)
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
                result.add(client, slice.clock, slice.len, joinAttrs(range.attrs, rightAttrs))
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
