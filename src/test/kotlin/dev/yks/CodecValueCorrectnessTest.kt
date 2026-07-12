package dev.yks

import java.math.BigInteger
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.jupiter.api.assertTimeoutPreemptively

class CodecValueCorrectnessTest {
    @Test
    fun largeContentDeletedRangesPreserveTheirFullClockLength() {
        listOf(1L shl 32, (1L shl 32) + 1).forEach { length ->
            val doc = YDoc(clientId = 2)
            applyUpdate(doc, contentDeletedUpdate(length))

            assertEquals(mapOf(1L to length), decodeStateVector(encodeStateVector(doc)))
            assertEquals(0, doc.getArray("a").length)

            val relay = encodeStateAsUpdate(doc)
            val decoded = decodeUpdate(relay)
            assertEquals(length, decoded.structs.single().length)
            assertIs<ContentDeleted>(decoded.structs.single().content)

            val incremental = encodeStateAsUpdate(doc, encodeStateVector(mapOf(1L to length - 1)))
            val incrementalStruct = decodeUpdate(incremental).structs.single()
            assertEquals(Id(1, length - 1), incrementalStruct.id)
            assertEquals(1, incrementalStruct.length)

            val diff = diffUpdate(relay, encodeStateVector(mapOf(1L to length - 1)))
            assertEquals(1, decodeUpdate(diff).structs.single().length)
            val relayV2 = encodeStateAsUpdateV2(doc)
            val diffV2 = diffUpdateV2(relayV2, encodeStateVector(mapOf(1L to length - 1)))
            assertEquals(1, decodeUpdateV2(diffV2).structs.single().length)
        }
    }

    @Test
    fun largeGcRangesPreserveTheirFullClockLength() {
        listOf(1L shl 32, (1L shl 32) + 1).forEach { length ->
            val doc = YDoc(clientId = 2)
            applyUpdate(doc, gcUpdate(length))

            assertEquals(mapOf(1L to length), decodeStateVector(encodeStateVector(doc)))
            assertTrue(doc.rootNames().isEmpty())
            val relay = encodeStateAsUpdate(doc)
            assertTrue(!relay.hasYksMagic())
            assertEquals(length, decodeUpdate(relay).structs.single().length)
        }
    }

    @Test
    fun hugePendingDeleteRangeDoesNotEnumerateEveryClock() {
        val length = (1L shl 32) + 1
        val update = BinaryEncoder().also { encoder ->
            encoder.writeVarUInt(0)
            encoder.writeVarUInt(1)
            encoder.writeVarUInt(1)
            encoder.writeVarUInt(1)
            encoder.writeVarUInt(0)
            encoder.writeVarUInt(length)
        }.toByteArray()

        val doc = YDoc(clientId = 2)
        assertTimeoutPreemptively(Duration.ofSeconds(2)) { applyUpdate(doc, update) }
        assertTrue(doc.store.pendingDs != null)
    }

    @Test
    fun deleteRangeRejectsClockOverflow() {
        assertFailsWith<IllegalStateException> { DeleteRange(Long.MAX_VALUE, 1) }
    }

    @Test
    fun emojiUsesStandardV1AndV2AndRoundTrips() {
        val doc = YDoc(clientId = 1)
        doc.getText("body").insert(0, "A😀B")

        val v1 = encodeStateAsUpdate(doc)
        val v2 = encodeStateAsUpdateV2(doc)

        assertTrue(!v1.hasYksMagic())
        assertTrue(!v2.hasYksMagic())
        assertEquals("A😀B", createDocFromUpdate(v1).getText("body").toString())
        assertEquals("A😀B", createDocFromUpdateV2(v2).getText("body").toString())
    }

    @Test
    fun lib0SpecialValuesAndObjectKeyOrderRoundTripLosslessly() {
        val values = linkedMapOf<String, Any?>(
            "b" to 1,
            "a" to 2,
            "10" to 10,
            "2" to 2,
            "undef" to YValue.Undefined,
            "big" to BigInteger("9007199254740993"),
            "negzero" to -0.0,
            "nan" to Double.NaN,
            "positiveInfinity" to Double.POSITIVE_INFINITY,
            "negativeInfinity" to Double.NEGATIVE_INFINITY,
        )
        val doc = YDoc(clientId = 1)
        doc.getArray("values").push(listOf(values))

        val update = encodeStateAsUpdate(doc)
        assertTrue(!update.hasYksMagic())
        val roundTrip = createDocFromUpdate(update).getArray("values").get(0) as Map<*, *>

        assertEquals(
            listOf("2", "10", "b", "a", "undef", "big", "negzero", "nan", "positiveInfinity", "negativeInfinity"),
            roundTrip.keys.toList(),
        )
        assertSame(YValue.Undefined, roundTrip["undef"])
        assertEquals(BigInteger("9007199254740993"), roundTrip["big"])
        assertTrue(java.lang.Double.doubleToRawLongBits(roundTrip["negzero"] as Double) < 0)
        assertTrue((roundTrip["nan"] as Double).isNaN())
        assertEquals(Double.POSITIVE_INFINITY, roundTrip["positiveInfinity"])
        assertEquals(Double.NEGATIVE_INFINITY, roundTrip["negativeInfinity"])
    }

    @Test
    fun userFormatKeyMatchingLegacySentinelRemainsNativeFormatting() {
        val doc = YDoc(clientId = 1)
        doc.getText("body").insert(0, "x", mapOf("__yks_text_format" to true))

        val update = encodeStateAsUpdate(doc)
        val target = createDocFromUpdate(update)

        assertEquals(
            YTextDelta().insert("x", mapOf("__yks_text_format" to true)),
            target.getText("body").toDelta(),
        )
    }

    @Test
    fun nestedSubdocUsesLosslessPrivateFallback() {
        val parent = YDoc(clientId = 1)
        val child = YDoc(clientId = 2, guid = "child", shouldLoad = false)
        parent.getArray("items").push(listOf(listOf(child)))

        val update = encodeStateAsUpdate(parent)
        assertTrue(update.hasYksMagic())

        val target = createDocFromUpdate(update)
        assertEquals(setOf("child"), target.getSubdocGuids())
        val nested = (target.getArray("items").get(0) as List<*>).single()
        assertIs<YDoc>(nested)
        assertEquals("child", nested.guid)
    }

    @Test
    fun directDefaultSubdocUsesStandardWireOptions() {
        val parent = YDoc(clientId = 1)
        parent.getArray("subs").push(listOf(YDoc(clientId = 2, guid = "default-child")))

        val v1 = encodeStateAsUpdate(parent)
        val v2 = encodeStateAsUpdateV2(parent)
        assertTrue(!v1.hasYksMagic())
        assertTrue(!v2.hasYksMagic())

        val childV1 = createDocFromUpdate(v1).getArray("subs").get(0) as YDoc
        val childV2 = createDocFromUpdateV2(v2).getArray("subs").get(0) as YDoc
        assertEquals("default-child", childV1.guid)
        assertEquals("default-child", childV2.guid)
        assertTrue(!childV1.shouldLoad)
        assertTrue(!childV2.shouldLoad)
    }

    private fun contentDeletedUpdate(length: Long): ByteArray = BinaryEncoder().also { encoder ->
        encoder.writeVarUInt(1)
        encoder.writeVarUInt(1)
        encoder.writeVarUInt(1)
        encoder.writeVarUInt(0)
        encoder.writeByte(contentDeletedRefNumber)
        encoder.writeVarUInt(1)
        encoder.writeString("a")
        encoder.writeVarUInt(length)
        encoder.writeVarUInt(0)
    }.toByteArray()

    private fun gcUpdate(length: Long): ByteArray = BinaryEncoder().also { encoder ->
        encoder.writeVarUInt(1)
        encoder.writeVarUInt(1)
        encoder.writeVarUInt(1)
        encoder.writeVarUInt(0)
        encoder.writeByte(structGCRefNumber)
        encoder.writeVarUInt(length)
        encoder.writeVarUInt(0)
    }.toByteArray()

    private fun ByteArray.hasYksMagic(): Boolean =
        size >= 4 && this[0] == 'Y'.code.toByte() && this[1] == 'K'.code.toByte() && this[2] == 'S'.code.toByte()
}
