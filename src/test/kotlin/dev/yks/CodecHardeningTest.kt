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
            writeVarUInt(Long.MAX_VALUE) // start clock
            writeByte(0) // GC struct
            writeVarUInt(1) // GC length
            writeVarUInt(0) // empty delete set
        }.toByteArray()

        val error = assertFailsWith<IllegalStateException> { UpdateCodec.decode(update) }

        assertTrue(error.message.orEmpty().contains("clock overflow"))
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
