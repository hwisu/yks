package dev.yks

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CodecHardeningTest {
    @Test
    fun zeroLengthGcAndSkipAreRejectedByEveryStandardUpdateUtility() {
        for (v2 in listOf(false, true)) {
            val target = YDoc(clientId = 5, gc = false)
            target.getText("body").insert(0, "unchanged")
            val before = target.encodeStateAsUpdate()
            val empty = if (v2) encodeStateAsUpdateV2(YDoc()) else encodeStateAsUpdate(YDoc())
            val operations: Map<String, (ByteArray) -> Any?> = if (v2) {
                mapOf(
                    "decode" to { decodeUpdateV2(it) },
                    "apply" to { applyUpdateV2(target, it) },
                    "merge single" to { mergeUpdatesV2(listOf(it)) },
                    "merge multiple" to { mergeUpdatesV2(listOf(empty, it)) },
                    "diff" to { diffUpdateV2(it, byteArrayOf(0)) },
                    "convert" to { convertUpdateFormatV2ToV1(it) },
                    "metadata" to { parseUpdateMetaV2(it) },
                    "state vector" to { encodeStateVectorFromUpdateV2(it) },
                    "content ids" to { createContentIdsFromUpdateV2(it) },
                    "intersect" to { intersectUpdateWithContentIdsV2(it, createContentIds()) },
                    "obfuscate" to { obfuscateUpdateV2(it) },
                )
            } else {
                mapOf(
                    "decode" to { decodeUpdate(it) },
                    "apply" to { applyUpdate(target, it) },
                    "merge single" to { mergeUpdates(listOf(it)) },
                    "merge multiple" to { mergeUpdates(listOf(empty, it)) },
                    "diff" to { diffUpdate(it, byteArrayOf(0)) },
                    "convert" to { convertUpdateFormatV1ToV2(it) },
                    "metadata" to { parseUpdateMeta(it) },
                    "state vector" to { encodeStateVectorFromUpdate(it) },
                    "content ids" to { createContentIdsFromUpdate(it) },
                    "intersect" to { intersectUpdateWithContentIds(it, createContentIds()) },
                    "obfuscate" to { obfuscateUpdate(it) },
                )
            }
            for (ref in listOf(structGCRefNumber, structSkipRefNumber)) {
                for (validPrefix in listOf(false, true)) {
                    val update = emptyBlockUpdate(v2, ref, validPrefix)
                    for ((name, operation) in operations) {
                        val error = assertFailsWith<YksDecodingException>("v2=$v2 ref=$ref prefix=$validPrefix $name") {
                            operation(update)
                        }
                        assertTrue(error.message.orEmpty().contains("struct length must be positive"))
                        assertContentEquals(before, target.encodeStateAsUpdate())
                    }
                }
            }
        }
    }

    @Test
    fun emptyClientListingsRemainValidNoOps() {
        val v1 = byteArrayOf(1, 0, 96, 0, 0)
        val v2 = UpdateEncoderV2().apply {
            restEncoder.writeVarUInt(1)
            restEncoder.writeVarUInt(0)
            writeClient(96)
            restEncoder.writeVarUInt(0)
            restEncoder.writeVarUInt(0)
        }.toByteArray()
        val doc = YDoc(clientId = 5)
        doc.getText("body").insert(0, "unchanged")
        val before = doc.encodeStateAsUpdate()
        applyUpdate(doc, v1)
        applyUpdateV2(doc, v2)
        assertContentEquals(before, doc.encodeStateAsUpdate())
        assertTrue(decodeUpdate(convertUpdateFormatV2ToV1(v2)).structs.isEmpty())
        assertTrue(decodeUpdateV2(convertUpdateFormatV1ToV2(v1)).structs.isEmpty())
    }

    @Test
    fun truncatedAndOverlongV2VarintsFailWithoutChangingTheDocument() {
        val doc = YDoc(clientId = 5)
        doc.getText("body").insert(0, "unchanged")
        val before = doc.encodeStateAsUpdate()
        val malformed = listOf(byteArrayOf(0x80.toByte(), 0xff.toByte()), byteArrayOf(0, 0x80.toByte())) +
            (1..40).flatMap { length ->
                val prefix = byteArrayOf(0) + ByteArray(length) { 0xff.toByte() }
                listOf(prefix, prefix + byteArrayOf(1))
            }
        for (update in malformed) {
            assertFailsWith<YksDecodingException> { applyUpdateV2(doc, update) }
            assertContentEquals(before, doc.encodeStateAsUpdate())
        }
    }

    private fun emptyBlockUpdate(v2: Boolean, ref: Int, validPrefix: Boolean): ByteArray {
        val encoder = if (v2) UpdateEncoderV2() else null
        val rest = encoder?.restEncoder ?: BinaryEncoder()
        rest.writeVarUInt(1)
        rest.writeVarUInt(if (validPrefix) 2 else 1)
        if (encoder != null) encoder.writeClient(96) else rest.writeVarUInt(96)
        rest.writeVarUInt(0)
        if (validPrefix) {
            if (encoder != null) {
                encoder.writeInfo(structGCRefNumber)
                encoder.writeLen(1)
            } else {
                rest.writeByte(structGCRefNumber)
                rest.writeVarUInt(1)
            }
        }
        if (encoder != null) encoder.writeInfo(ref) else rest.writeByte(ref)
        if (encoder != null && ref == structGCRefNumber) encoder.writeLen(0) else rest.writeVarUInt(0)
        rest.writeVarUInt(0)
        return encoder?.toByteArray() ?: rest.toByteArray()
    }

    @Test
    fun rejectsCountsOutsideTheJvmCollectionRangeBeforeAllocation() {
        val stateVector = BinaryEncoder().apply {
            writeVarUInt(Int.MAX_VALUE.toLong() + 1)
        }.toByteArray()

        val error = assertFailsWith<IllegalStateException> { decodeStateVector(stateVector) }

        assertTrue(error.message.orEmpty().contains("exceeds limit"))
    }

    @Test
    fun rejectsStringAndBufferLengthsLargerThanTheAvailableJvmByteArray() {
        val encodedLength = BinaryEncoder().apply {
            writeVarUInt(Int.MAX_VALUE.toLong())
        }.toByteArray()

        assertFailsWith<IllegalStateException> { BinaryDecoder(encodedLength).readString() }
        assertFailsWith<IllegalStateException> { BinaryDecoder(encodedLength).readBytes() }
    }

    @Test
    fun rejectsClockOverflowInV1StructRanges() {
        val update = BinaryEncoder().apply {
            writeVarUInt(1) // clients
            writeVarUInt(1) // structs
            writeVarUInt(1) // client
            writeVarUInt(1) // start clock
            writeByte(0) // GC struct
            writeVarUInt(Long.MAX_VALUE) // GC length
            writeVarUInt(0) // empty delete set
        }.toByteArray()

        val error = assertFailsWith<IllegalStateException> { UpdateCodec.decode(update) }

        assertTrue(error.message.orEmpty().contains("clock overflow"))
    }

    @Test
    fun clientsAndClocksOutsideTheJavaScriptSafeRangeAreRejectedBeforeDocumentMutation() {
        val unsafe = YJS_MAX_SAFE_INTEGER + 1
        val cases: Map<String, (Boolean) -> ByteArray> = mapOf(
            "struct client" to { v2 -> wireUpdate(v2, client = unsafe) },
            "struct clock" to { v2 -> wireUpdate(v2, clock = unsafe) },
            "struct end clock" to { v2 -> wireUpdate(v2, clock = YJS_MAX_SAFE_INTEGER) },
            "origin client" to { v2 -> wireUpdate(v2, origin = Id(unsafe, 0)) },
            "origin clock" to { v2 -> wireUpdate(v2, origin = Id(7, unsafe)) },
            "delete client" to { v2 -> wireUpdate(v2, delete = Triple(unsafe, 0L, 1L)) },
            "delete clock" to { v2 -> wireUpdate(v2, delete = Triple(7L, unsafe, 1L)) },
            "delete end" to { v2 -> wireUpdate(v2, delete = Triple(7L, YJS_MAX_SAFE_INTEGER, 1L)) },
        )
        for (v2 in listOf(false, true)) {
            // A standard update listener and REQUIRE_STANDARD mirror a relay server's document.
            val target = YDoc(
                YDocOptions(clientId = 5, gc = false),
                YDocRuntimeOptions(standardUpdatePolicy = YStandardUpdatePolicy.REQUIRE_STANDARD),
            )
            target.getText("body").insert(0, "unchanged")
            val emitted = mutableListOf<ByteArray>()
            target.observeUpdates { update, _ -> emitted.add(update) }
            val before = target.encodeStateAsUpdate()
            val empty = if (v2) encodeStateAsUpdateV2(YDoc()) else encodeStateAsUpdate(YDoc())
            val operations: Map<String, (ByteArray) -> Any?> = if (v2) {
                mapOf(
                    "decode" to { decodeUpdateV2(it) },
                    "apply" to { applyUpdateV2(target, it) },
                    "merge single" to { mergeUpdatesV2(listOf(it)) },
                    "merge multiple" to { mergeUpdatesV2(listOf(empty, it)) },
                    "convert" to { convertUpdateFormatV2ToV1(it) },
                    "metadata" to { parseUpdateMetaV2(it) },
                    "state vector" to { encodeStateVectorFromUpdateV2(it) },
                )
            } else {
                mapOf(
                    "decode" to { decodeUpdate(it) },
                    "apply" to { applyUpdate(target, it) },
                    "merge single" to { mergeUpdates(listOf(it)) },
                    "merge multiple" to { mergeUpdates(listOf(empty, it)) },
                    "convert" to { convertUpdateFormatV1ToV2(it) },
                    "metadata" to { parseUpdateMeta(it) },
                    "state vector" to { encodeStateVectorFromUpdate(it) },
                )
            }
            for ((case, build) in cases) {
                val update = build(v2)
                for ((name, operation) in operations) {
                    val error = assertFailsWith<YksDecodingException>("v2=$v2 $case $name") { operation(update) }
                    assertTrue(
                        error.message.orEmpty().contains("JavaScript safe integer range"),
                        "v2=$v2 $case $name: ${error.message}",
                    )
                }
                assertContentEquals(before, target.encodeStateAsUpdate(), "v2=$v2 $case")
                assertEquals(before.size, mergeUpdates(listOf(before, target.encodeStateAsUpdate())).size)
                assertTrue(target.encodeStateVector().isNotEmpty())
                assertTrue(emitted.isEmpty(), "v2=$v2 $case emitted an update")
            }
        }
    }

    @Test
    fun largestJavaScriptSafeClientAndClockRemainAcceptedAndEncodable() {
        for (v2 in listOf(false, true)) {
            val apply: (YDoc, ByteArray) -> Unit = { doc, bytes -> if (v2) applyUpdateV2(doc, bytes) else applyUpdate(doc, bytes) }
            val encode: (YDoc) -> ByteArray = { doc -> if (v2) encodeStateAsUpdateV2(doc) else encodeStateAsUpdate(doc) }
            val doc = YDoc(clientId = 5)
            apply(doc, wireUpdate(v2, client = YJS_MAX_SAFE_INTEGER))
            apply(doc, wireUpdate(v2, client = 7, clock = YJS_MAX_SAFE_INTEGER - 1))
            apply(doc, wireUpdate(v2, delete = Triple(YJS_MAX_SAFE_INTEGER, YJS_MAX_SAFE_INTEGER - 1, 1L)))
            assertEquals(mapOf(YJS_MAX_SAFE_INTEGER to 1L), decodeStateVector(doc.encodeStateVector()))
            val reloaded = YDoc(clientId = 6)
            apply(reloaded, encode(doc))
            assertContentEquals(doc.encodeStateVector(), reloaded.encodeStateVector())
            assertContentEquals(encode(doc), encode(reloaded))
        }
    }

    /** A one-client update holding either a GC struct, an item with [origin], or only a delete range. */
    private fun wireUpdate(
        v2: Boolean,
        client: Long = 96,
        clock: Long = 0,
        origin: Id? = null,
        delete: Triple<Long, Long, Long>? = null,
    ): ByteArray {
        val encoder = if (v2) UpdateEncoderV2() else null
        val rest = encoder?.restEncoder ?: BinaryEncoder()
        if (delete == null) {
            rest.writeVarUInt(1)
            rest.writeVarUInt(1)
            if (encoder != null) encoder.writeClient(client) else rest.writeVarUInt(client)
            rest.writeVarUInt(clock)
            if (origin == null) {
                if (encoder != null) {
                    encoder.writeInfo(structGCRefNumber)
                    encoder.writeLen(1)
                } else {
                    rest.writeByte(structGCRefNumber)
                    rest.writeVarUInt(1)
                }
            } else {
                val info = 0x80 or 4 // origin flag, ContentString
                if (encoder != null) {
                    encoder.writeInfo(info)
                    encoder.writeLeftID(origin)
                    encoder.writeString("a")
                } else {
                    rest.writeByte(info)
                    rest.writeVarUInt(origin.client)
                    rest.writeVarUInt(origin.clock)
                    rest.writeString("a")
                }
            }
            rest.writeVarUInt(0)
        } else {
            val (deleteClient, deleteClock, deleteLength) = delete
            rest.writeVarUInt(0)
            rest.writeVarUInt(1)
            rest.writeVarUInt(deleteClient)
            rest.writeVarUInt(1)
            if (encoder != null) {
                encoder.resetIdSetCurVal()
                encoder.writeIdSetClock(deleteClock)
                encoder.writeIdSetLen(deleteLength)
            } else {
                rest.writeVarUInt(deleteClock)
                rest.writeVarUInt(deleteLength)
            }
        }
        return encoder?.toByteArray() ?: rest.toByteArray()
    }

    @Test
    fun acceptsValueNestingBeyondTheFormerYksSpecificLimit() {
        val encoded = BinaryEncoder().apply {
            repeat(257) {
                writeByte(7) // YValue.ListValue
                writeVarUInt(1)
            }
            writeByte(0) // YValue.Null
        }.toByteArray()

        var value = readYValue(BinaryDecoder(encoded))
        repeat(257) { value = (value as YValue.ListValue).value.single() }
        assertEquals(YValue.Null, value)
    }

    @Test
    fun strictJsonRejectsRawControlCharactersLikeUpstreamJsonParse() {
        val error = assertFailsWith<IllegalStateException> { parseJsonLiteral("\"\n\"") }

        assertEquals("unescaped control character in JSON string", error.message)
        listOf(
            "\"\\x\"",
            "\"\\uZZZZ\"",
            "[1,]",
            "{\"a\":1,}",
            "01",
            "-01",
            "true false",
            "\u00A0true",
        ).forEach { malformed ->
            assertFailsWith<IllegalStateException>("accepted malformed JSON: $malformed") {
                parseJsonLiteral(malformed)
            }
        }
    }
}
