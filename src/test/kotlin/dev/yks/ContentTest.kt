package dev.yks

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ContentTest {
    @Test
    fun contentClassesExposeYjsLikeRefsLengthCountabilityCopySplitAndMerge() {
        val any = ContentAny(listOf("a", 1, byteArrayOf(1, 2)))
        val anyRight = any.splice(2)
        val json = ContentJSON(listOf(mapOf("x" to 1), null))
        val jsonRight = json.splice(1)
        val string = ContentString("ab")
        val stringRight = string.splice(1)
        val deleted = ContentDeleted(3)
        val deletedRight = deleted.splice(1)

        assertEquals(contentAnyRefNumber, any.getRef())
        assertEquals(contentJSONRefNumber, json.getRef())
        assertEquals(contentStringRefNumber, string.getRef())
        assertEquals(contentDeletedRefNumber, deleted.getRef())
        assertTrue(any.isCountable())
        assertFalse(deleted.isCountable())
        assertEquals(2L, any.getLength())
        assertEquals(1L, anyRight.getLength())
        assertEquals(listOf("a", 1), ContentAny(listOf("a", 1)).getContent())
        assertContentEquals(byteArrayOf(1, 2), anyRight.getContent().single() as ByteArray)
        assertEquals(listOf(mapOf("x" to 1)), json.getContent())
        assertEquals(listOf(null), jsonRight.getContent())
        assertEquals("a", string.str)
        assertEquals("b", stringRight.str)
        assertEquals(1L, deleted.len)
        assertEquals(2L, deletedRight.len)

        assertTrue(any.mergeWith(anyRight))
        assertTrue(json.mergeWith(jsonRight))
        assertTrue(string.mergeWith(stringRight))
        assertTrue(deleted.mergeWith(deletedRight))
        assertEquals(3L, any.getLength())
        assertEquals(2L, json.getLength())
        assertEquals("ab", string.str)
        assertEquals(3L, deleted.len)
        assertEquals(ContentString("ab"), string.copy())
    }

    @Test
    fun scalarContentClassesMirrorStableUpstreamShape() {
        val binary = ContentBinary(byteArrayOf(3, 4))
        val embed = ContentEmbed(mapOf("src" to "image"))
        val format = ContentFormat("bold", true)
        val doc = YDoc(guid = "sub", gc = false, autoLoad = true, meta = mapOf("role" to "child"))
        val docContent = createContentDocFromDoc(doc)
        val type = YDoc(clientId = 1).getArray("items").also { it.push("x") }
        val typeContent = ContentType(type)
        val copiedType = typeContent.copy()

        assertEquals(contentBinaryRefNumber, binary.getRef())
        assertContentEquals(byteArrayOf(3, 4), binary.content)
        assertContentEquals(byteArrayOf(3, 4), binary.copy().content)
        assertEquals(contentEmbedRefNumber, embed.getRef())
        assertEquals(listOf(mapOf("src" to "image")), embed.getContent())
        assertEquals(contentFormatRefNumber, format.getRef())
        assertFalse(format.isCountable())
        assertEquals(emptyList(), format.getContent())
        assertEquals(contentDocRefNumber, docContent.getRef())
        assertEquals("sub", docContent.guid)
        assertEquals(false, docContent.opts["gc"])
        assertEquals(true, docContent.opts["autoLoad"])
        assertEquals(doc, docContent.doc)
        assertEquals(contentTypeRefNumber, typeContent.getRef())
        assertEquals(listOf(type), typeContent.getContent())
        assertIs<ContentType>(copiedType)
        assertEquals(listOf("x"), (copiedType.type as YArray).toList())
        assertFalse(typeContent.mergeWith(ContentType(type)))
    }

    @Test
    fun contentRefsAndReadersMirrorYjsStructReferenceTable() {
        assertEquals(11, contentRefs.size)
        assertFailsWith<IllegalStateException> { contentRefs[structGCRefNumber](UpdateDecoderV1(ByteArray(0))) }
        assertFailsWith<IllegalStateException> { contentRefs[structSkipRefNumber](UpdateDecoderV1(ByteArray(0))) }

        val deleted = contentRefs[contentDeletedRefNumber](decoder { writeLen(3) })
        assertEquals(ContentDeleted(3), deleted)

        val json = contentRefs[contentJSONRefNumber](
            decoder {
                writeLen(4)
                writeString("""{"n":1,"ok":true}""")
                writeString("""["x",null]""")
                writeString(""""s"""")
                writeString("undefined")
            },
        ) as ContentJSON
        assertEquals(
            listOf(mapOf("n" to 1L, "ok" to true), listOf("x", null), "s", null),
            json.getContent(),
        )

        val binary = contentRefs[contentBinaryRefNumber](decoder { writeBuf(byteArrayOf(4, 5)) }) as ContentBinary
        assertContentEquals(byteArrayOf(4, 5), binary.content)

        assertEquals(
            ContentString("hi"),
            readItemContent(decoder { writeString("hi") }, contentStringRefNumber or 0x80),
        )
        assertEquals(
            ContentEmbed(mapOf("src" to "image")),
            contentRefs[contentEmbedRefNumber](decoder { writeJSON(mapOf("src" to "image")) }),
        )
        assertEquals(
            ContentFormat("bold", true),
            contentRefs[contentFormatRefNumber](decoder {
                writeKey("bold")
                writeJSON(true)
            }),
        )

        val typeContent = contentRefs[contentTypeRefNumber](decoder { writeTypeRef(YTextRefID) }) as ContentType
        assertIs<YText>(typeContent.type)
        assertEquals(YTextRefID, typeContent.type.typeRef)
        val xmlElementContent = contentRefs[contentTypeRefNumber](
            decoder {
                writeTypeRef(YXmlElementRefID)
                writeKey("paragraph")
            },
        ) as ContentType
        assertEquals(YXmlElementRefID, xmlElementContent.type.typeRef)
        assertEquals("paragraph", xmlElementContent.type.name)
        val xmlHookContent = contentRefs[contentTypeRefNumber](
            decoder {
                writeTypeRef(YXmlHookRefID)
                writeKey("hook")
            },
        ) as ContentType
        assertEquals(YXmlHookRefID, xmlHookContent.type.typeRef)
        assertEquals("hook", xmlHookContent.type.name)
        val xmlTextContent = contentRefs[contentTypeRefNumber](decoder { writeTypeRef(YXmlTextRefID) }) as ContentType
        assertEquals(YXmlTextRefID, xmlTextContent.type.typeRef)

        assertEquals(
            ContentAny(listOf("a", 2L, false)),
            contentRefs[contentAnyRefNumber](decoder {
                writeLen(3)
                writeAny("a")
                writeAny(2)
                writeAny(false)
            }),
        )
        assertEquals(
            ContentDoc("subdoc", mapOf("role" to "child")),
            contentRefs[contentDocRefNumber](decoder {
                writeString("subdoc")
                writeAny(mapOf("role" to "child"))
            }),
        )
    }

    @Test
    fun contentReadersAcceptV2Decoders() {
        val encoder = UpdateEncoderV2()
        encoder.writeString("v2")

        assertEquals(ContentString("v2"), readContentString(UpdateDecoderV2(encoder.toByteArray())))
    }

    @Test
    fun contentWriteMethodsRoundTripThroughReaderTable() {
        assertEquals(ContentString("ell"), roundTrip(ContentString("hello"), offset = 1, offsetEnd = 1))
        assertEquals(ContentDeleted(2), roundTrip(ContentDeleted(5), offset = 1, offsetEnd = 2))
        assertEquals(ContentAny(listOf(2L, "c")), roundTrip(ContentAny(listOf("a", 2, "c", false)), offset = 1, offsetEnd = 1))
        assertEquals(
            ContentJSON(listOf(mapOf("n" to 1L), listOf(true, null))),
            roundTrip(ContentJSON(listOf("skip", mapOf("n" to 1), listOf(true, null), "drop")), offset = 1, offsetEnd = 1),
        )
        assertEquals(ContentEmbed(mapOf("src" to "image", "width" to 100L)), roundTrip(ContentEmbed(mapOf("src" to "image", "width" to 100))))
        assertEquals(ContentFormat("bold", true), roundTrip(ContentFormat("bold", true)))
        assertEquals(ContentDoc("subdoc", mapOf("role" to "child")), roundTrip(ContentDoc("subdoc", mapOf("role" to "child"))))

        val binary = roundTrip(ContentBinary(byteArrayOf(9, 8))) as ContentBinary
        assertContentEquals(byteArrayOf(9, 8), binary.content)

        val type = YDoc(clientId = 1).createText()
        val typeContent = roundTrip(ContentType(type)) as ContentType
        assertIs<YText>(typeContent.type)
        assertEquals(YTextRefID, typeContent.type.typeRef)

        val xmlElementContent = roundTrip(ContentType(YXmlElementType(YDoc(clientId = 1), "p"))) as ContentType
        assertEquals(YXmlElementRefID, xmlElementContent.type.typeRef)
        assertEquals("p", xmlElementContent.type.name)
        val xmlHookContent = roundTrip(ContentType(YXmlElementType(YDoc(clientId = 1), "hook", RootKind.XmlHook))) as ContentType
        assertEquals(YXmlHookRefID, xmlHookContent.type.typeRef)
        assertEquals("hook", xmlHookContent.type.name)
        val xmlTextContent = roundTrip(ContentType(YXmlTextType(YDoc(clientId = 1)))) as ContentType
        assertEquals(YXmlTextRefID, xmlTextContent.type.typeRef)
    }

    @Test
    fun contentWriteMethodsAcceptV2Encoders() {
        val encoder = UpdateEncoderV2()

        writeItemContent(encoder, ContentString("v2"))

        assertEquals(ContentString("v2"), readItemContent(UpdateDecoderV2(encoder.toByteArray()), contentStringRefNumber))
    }

    private fun decoder(block: UpdateEncoderV1.() -> Unit): UpdateDecoderV1 {
        val encoder = UpdateEncoderV1()
        encoder.block()
        return UpdateDecoderV1(encoder.toByteArray())
    }

    private fun roundTrip(content: AbstractContent, offset: Long = 0, offsetEnd: Long = 0): AbstractContent {
        val encoder = UpdateEncoderV1()
        writeItemContent(encoder, content, offset, offsetEnd)
        return readItemContent(UpdateDecoderV1(encoder.toByteArray()), content.getRef())
    }
}
