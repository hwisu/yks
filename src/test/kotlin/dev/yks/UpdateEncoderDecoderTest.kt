package dev.yks

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class UpdateEncoderDecoderTest {
    @Test
    fun updateEncoderV1RoundTripsPrimitiveFields() {
        val encoder = UpdateEncoderV1()
        encoder.writeLeftID(Id(1, 2))
        encoder.writeRightID(Id(3, 4))
        encoder.writeClient(5)
        encoder.writeInfo(255)
        encoder.writeString("text")
        encoder.writeParentInfo(true)
        encoder.writeTypeRef(6)
        encoder.writeLen(7)
        encoder.writeAny(mapOf("count" to 1, "ok" to true))
        encoder.writeBuf(byteArrayOf(1, 2, 3))
        encoder.writeJSON(listOf("json", 2))
        encoder.writeKey("bold")

        val decoder = UpdateDecoderV1(encoder.toByteArray())

        assertEquals(Id(1, 2), decoder.readLeftID())
        assertEquals(Id(3, 4), decoder.readRightID())
        assertEquals(5L, decoder.readClient())
        assertEquals(255, decoder.readInfo())
        assertEquals("text", decoder.readString())
        assertTrue(decoder.readParentInfo())
        assertEquals(6, decoder.readTypeRef())
        assertEquals(7L, decoder.readLen())
        assertEquals(mapOf("count" to 1L, "ok" to true), decoder.readAny())
        assertContentEquals(byteArrayOf(1, 2, 3), decoder.readBuf())
        assertEquals(listOf("json", 2L), decoder.readJSON())
        assertEquals("bold", decoder.readKey())
        assertFalse(decoder.hasRemaining())
        assertFailsWith<IllegalArgumentException> { UpdateEncoderV1().writeInfo(256) }
    }

    @Test
    fun updateEncoderV1WritesJsonAsVarString() {
        val encoder = UpdateEncoderV1()
        encoder.writeJSON(linkedMapOf("kind" to "v1", "n" to 1, "ok" to true))

        val rawDecoder = BinaryDecoder(encoder.toByteArray())
        assertEquals("""{"kind":"v1","n":1,"ok":true}""", rawDecoder.readString())
        assertFalse(rawDecoder.hasRemaining())

        val decoder = UpdateDecoderV1(encoder.toByteArray())
        assertEquals(mapOf("kind" to "v1", "n" to 1L, "ok" to true), decoder.readJSON())
        assertFalse(decoder.hasRemaining())
    }

    @Test
    fun updateEncoderV2RoundTripsPrimitiveFields() {
        val encoder = UpdateEncoderV2()
        encoder.writeLeftID(Id(8, 9))
        encoder.writeRightID(Id(10, 11))
        encoder.writeClient(12)
        encoder.writeInfo(13)
        encoder.writeString("value")
        encoder.writeParentInfo(false)
        encoder.writeTypeRef(14)
        encoder.writeLen(15)
        encoder.writeAny(listOf("any", 3))
        encoder.writeBuf(byteArrayOf(4, 5))
        encoder.writeJSON(mapOf("kind" to "json"))
        encoder.writeKey("italic")

        assertContentEquals(
            hex("00010003080a0c01120116010d0e0b76616c75656974616c696305060100010e010f75027703616e797d030204057601046b696e6477046a736f6e"),
            encoder.toUint8Array(),
        )

        val decoder = UpdateDecoderV2(encoder.toUint8Array())

        assertEquals(Id(8, 9), decoder.readLeftID())
        assertEquals(Id(10, 11), decoder.readRightID())
        assertEquals(12L, decoder.readClient())
        assertEquals(13, decoder.readInfo())
        assertEquals("value", decoder.readString())
        assertFalse(decoder.readParentInfo())
        assertEquals(14, decoder.readTypeRef())
        assertEquals(15L, decoder.readLen())
        assertEquals(listOf("any", 3L), decoder.readAny())
        assertContentEquals(byteArrayOf(4, 5), decoder.readBuf())
        assertEquals(mapOf("kind" to "json"), decoder.readJSON())
        assertEquals("italic", decoder.readKey())
        assertFalse(decoder.hasRemaining())
    }

    @Test
    fun idSetEncodersBackPublicIdAndContentMetadataHelpers() {
        val ids = createContentIds(
            inserts = idSet(1, 0, 2, 1, 4, 1),
            deletes = idSet(2, 1, 1),
        )
        val idsEncoder = IdSetEncoderV2()
        writeContentIds(idsEncoder, ids)
        val decodedIds = readContentIds(IdSetDecoderV2(idsEncoder.toByteArray()))

        assertContentIdsEquals(ids, decodedIds)

        val contentMap = createContentMapFromContentIds(
            ids,
            insertAttrs = listOf(createContentAttribute("source", "local")),
            deleteAttrs = listOf(createContentAttribute("op", "delete")),
        )
        val mapEncoder = IdSetEncoderV2()
        writeContentMap(mapEncoder, contentMap)
        val decodedMap = readContentMap(IdSetDecoderV2(mapEncoder.toUint8Array()))

        assertContentMapEquals(contentMap, decodedMap)
    }

    @Test
    fun idSetEncoderV1UsesAbsoluteClockEncoding() {
        val idSet = idSet(3, 2, 2, 3, 7, 1)
        val encoder = IdSetEncoderV1()
        writeIdSet(encoder, idSet)

        assertIdSetEquals(idSet, readIdSet(IdSetDecoderV1(encoder.toByteArray())))
    }

    private fun idSet(vararg triples: Long): IdSet {
        require(triples.size % 3 == 0)
        val idSet = createIdSet()
        triples.asList().chunked(3).forEach { (client, clock, len) -> idSet.add(client, clock, len) }
        return idSet
    }

    private fun hex(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun assertIdSetEquals(expected: IdSet, actual: IdSet) {
        assertTrue(equalIdSets(expected, actual), "expected ${expected.ranges()} but was ${actual.ranges()}")
    }

    private fun assertContentIdsEquals(expected: ContentIds, actual: ContentIds) {
        assertIdSetEquals(expected.inserts, actual.inserts)
        assertIdSetEquals(expected.deletes, actual.deletes)
    }

    private fun assertContentMapEquals(expected: ContentMap, actual: ContentMap) {
        assertTrue(equalIdMaps(expected.inserts, actual.inserts), "expected inserts ${expected.inserts.ranges()} but was ${actual.inserts.ranges()}")
        assertTrue(equalIdMaps(expected.deletes, actual.deletes), "expected deletes ${expected.deletes.ranges()} but was ${actual.deletes.ranges()}")
    }
}
