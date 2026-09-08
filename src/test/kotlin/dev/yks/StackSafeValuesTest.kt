package dev.yks

import kotlin.test.Test
import kotlin.test.assertEquals

class StackSafeValuesTest {
    @Test
    fun deepStandardValuesSurviveApplyReadMergeAndBothWireFormats() {
        val update = nestedUpdate(10_000)
        val doc = YDoc(clientId = 2)
        doc.getArray("values")
        applyUpdate(doc, update)
        assertDepth(doc.getArray("values").toList().single(), 10_000)
        val merged = mergeUpdates(listOf(update, encodeStateAsUpdate(doc)))
        val v2 = convertUpdateFormatV1ToV2(merged)
        decodeUpdateV2(v2)
        diffUpdateV2(v2, byteArrayOf(0))
        val copy = YDoc(clientId = 3)
        copy.getArray("values")
        applyUpdateV2(copy, v2)
        assertDepth(copy.getArray("values").toList().single(), 10_000)
        encodeStateAsUpdateV2(copy)
        doc.getMap("meta").set("copy", doc.getArray("values").toList().single())
        assertDepth(doc.getMap("meta").get("copy"), 10_000)
        applyUpdate(doc, update)
        doc.getMap("meta").delete("copy")
        doc.getMap("meta").set("after", true)
        assertEquals(true, doc.getMap("meta").get("after"))
    }

    @Test
    fun deepJsonAndPrivateValuesRoundTripWithoutARecursionLimit() {
        val json = "[".repeat(10_000) + "null" + "]".repeat(10_000)
        val parsed = parseJsonLiteral(json)
        assertDepth(parsed, 10_000)
        assertEquals(json, toJsonLiteral(parsed))
        val value = YValue.from(parsed)
        val encoder = BinaryEncoder()
        writeYValue(encoder, value)
        assertDepth(readYValue(BinaryDecoder(encoder.toByteArray())).toAny(), 10_000)
    }

    @Test
    fun deepObjectFormattingSurvivesDocumentEncodingAndReload() {
        val json = "{\"next\":".repeat(10_000) + "null" + "}".repeat(10_000)
        val value = parseJsonLiteral(json)
        val original = YDoc(clientId = 1)
        original.getText("body").insert(0, "xy")
        original.getText("body").format(0, 1, mapOf("deep" to value))
        val restored = YDoc(clientId = 2)
        restored.getText("body")
        applyUpdate(restored, encodeStateAsUpdate(original))
        original.getText("body").format(1, 1, mapOf("deep" to parseJsonLiteral(json)))
        applyUpdate(restored, encodeStateAsUpdate(original, restored.encodeStateVector()))
        assertEquals("xy", restored.getText("body").toString())
        encodeStateAsUpdateV2(restored)
        restored.getText("body").format(0, 2, mapOf("deep" to null))
        assertEquals("xy", restored.getText("body").toString())
    }

    @Test
    fun deepValueEqualityAndHashingRetainCollectionSemantics() {
        for ((open, close) in listOf("[" to "]", "{\"next\":" to "}")) {
            val json = open.repeat(10_000) + "null" + close.repeat(10_000)
            val left = YValue.from(parseJsonLiteral(json))
            val right = YValue.from(parseJsonLiteral(json))
            assertEquals(left, right)
            assertEquals(left.hashCode(), right.hashCode())
        }
        val list = YValue.ListValue(listOf(YValue.Null, YValue.StringValue("leaf")))
        assertEquals(list.value.hashCode(), list.hashCode())
        val map = YValue.MapValue(mapOf("a" to list, "b" to YValue.Null))
        assertEquals(map.value.hashCode(), map.hashCode())
        assertEquals(map, YValue.MapValue(mapOf("b" to YValue.Null, "a" to list)))
    }

    private fun nestedUpdate(depth: Int): ByteArray = BinaryEncoder().apply {
        writeVarUInt(1)
        writeVarUInt(1)
        writeVarUInt(1)
        writeVarUInt(0)
        writeByte(8)
        writeVarUInt(1)
        writeString("values")
        writeVarUInt(1)
        repeat(depth) { writeByte(117); writeVarUInt(1) }
        writeByte(126)
        writeVarUInt(0)
    }.toByteArray()

    private fun assertDepth(value: Any?, depth: Int) {
        var current = value
        repeat(depth) { current = (current as List<*>).single() }
        assertEquals(null, current)
    }
}
