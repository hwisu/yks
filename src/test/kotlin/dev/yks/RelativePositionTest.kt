package dev.yks

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RelativePositionTest {
    @Test
    fun relativePositionTracksInsertionBeforeAnchor() {
        val doc = YDoc(clientId = 1)
        val text = doc.getText("text")
        text.insert(0, "12")

        val rightAssociated = createRelativePositionFromTypeIndex(text, 1, assoc = 0)
        val leftAssociated = createRelativePositionFromTypeIndex(text, 1, assoc = -1)

        text.insert(1, "x")

        assertEquals(2, createAbsolutePositionFromRelativePosition(rightAssociated, doc)?.index)
        assertEquals(1, createAbsolutePositionFromRelativePosition(leftAssociated, doc)?.index)
    }

    @Test
    fun endpointRelativePositionsResolveToBeginningAndEnd() {
        val doc = YDoc(clientId = 1)
        val array = doc.getArray("items")
        array.push(listOf("a", "b"))

        val beginning = createRelativePositionFromTypeIndex(array, 0, assoc = -1)
        val end = createRelativePositionFromTypeIndex(array, 2)

        assertEquals(0, createAbsolutePositionFromRelativePosition(beginning, doc)?.index)
        assertEquals(2, createAbsolutePositionFromRelativePosition(end, doc)?.index)
    }

    @Test
    fun relativePositionBinaryAndJsonRoundTrip() {
        val doc = YDoc(clientId = 1)
        val text = doc.getText("text")
        text.insert(0, "abc")
        val original = createRelativePositionFromTypeIndex(text, 2, assoc = -1)

        val decoded = decodeRelativePosition(encodeRelativePosition(original))
        val fromJson = createRelativePositionFromJSON(relativePositionToJSON(original))

        assertTrue(compareRelativePositions(original, fromJson))
        assertEquals(original.item, decoded.item)
        assertEquals(original.assoc, decoded.assoc)
        assertEquals(2, assertNotNull(createAbsolutePositionFromRelativePosition(decoded, doc)).index)
    }

    @Test
    fun relativePositionHelpersHonorRendererContentLength() {
        val doc = YDoc(clientId = 1)
        val text = doc.getText("text")
        text.insert(0, "abc")
        val renderer = object : BaseRenderer() {
            override val attributed: IdSet = createIdSet().also { it.add(1, 0, 1) }

            override fun contentLength(item: ItemStruct): Long =
                if (item.id == Id(1, 0)) 0 else super.contentLength(item)
        }

        val renderedStart = createRelativePositionFromTypeIndex(text, 0, renderer = renderer)
        val renderedEnd = createRelativePositionFromTypeIndex(text, 2, renderer = renderer)
        val beyondEnd = createRelativePositionFromTypeIndex(text, 3, renderer = renderer)
        val beyondLeftAssociated = createRelativePositionFromTypeIndex(text, 3, assoc = -1, renderer = renderer)

        assertEquals(Id(1, 1), renderedStart.item)
        assertEquals(0, createAbsolutePositionFromRelativePosition(renderedStart, doc, renderer = renderer)?.index)
        assertEquals(1, createAbsolutePositionFromRelativePosition(renderedStart, doc)?.index)
        assertEquals(null, renderedEnd.item)
        assertEquals(2, createAbsolutePositionFromRelativePosition(renderedEnd, doc, renderer = renderer)?.index)
        assertEquals(null, beyondEnd.item)
        assertEquals(2, createAbsolutePositionFromRelativePosition(beyondEnd, doc, renderer = renderer)?.index)
        assertEquals(Id(1, 2), beyondLeftAssociated.item)
        assertEquals(2, createAbsolutePositionFromRelativePosition(beyondLeftAssociated, doc, renderer = renderer)?.index)
    }

    @Test
    fun relativePositionJsonMirrorsUpstreamNullAndEmptyRootShape() {
        val empty = createRelativePositionFromJSON(emptyMap())
        val emptyRootEndpoint = RelativePosition(tname = "")

        assertEquals(RelativePosition(), empty)
        assertEquals(mapOf("assoc" to 0), relativePositionToJSON(empty))
        assertEquals(mapOf("assoc" to 0), relativePositionToJSON(emptyRootEndpoint))
        assertEquals(
            RelativePosition(tname = "", assoc = -1),
            createRelativePositionFromJSON(mapOf("tname" to "", "assoc" to -1)),
        )
    }

    @Test
    fun allNullRelativePositionFailsWhenResolvedOrEncoded() {
        val doc = YDoc(clientId = 1)
        val relativePosition = RelativePosition()

        assertFailsWith<IllegalStateException> {
            createAbsolutePositionFromRelativePosition(relativePosition, doc)
        }
        assertFailsWith<IllegalStateException> {
            encodeRelativePosition(relativePosition)
        }
    }

    @Test
    fun idAndRelativePositionLowLevelCodecsRoundTrip() {
        val idEncoder = BinaryEncoder()
        writeID(idEncoder, Id(7, 9))
        val idDecoder = BinaryDecoder(idEncoder.toByteArray())

        assertEquals(Id(7, 9), readID(idDecoder))
        assertFalse(idDecoder.hasRemaining())

        val relativePosition = RelativePosition(tname = "text", assoc = -1)
        val positionEncoder = BinaryEncoder()
        writeRelativePosition(positionEncoder, relativePosition)
        val positionDecoder = BinaryDecoder(positionEncoder.toByteArray())

        assertEquals(relativePosition, readRelativePosition(positionDecoder))
        assertFalse(positionDecoder.hasRemaining())

        val wrappedPositionEncoder = UpdateEncoderV2()
        writeRelativePosition(wrappedPositionEncoder, relativePosition)

        assertEquals(relativePosition, readRelativePosition(UpdateDecoderV2(wrappedPositionEncoder.toByteArray())))

        val legacyEncoder = BinaryEncoder()
        legacyEncoder.writeVarUInt(1)
        legacyEncoder.writeString("legacy")

        assertEquals(RelativePosition(tname = "legacy"), readRelativePosition(BinaryDecoder(legacyEncoder.toByteArray())))
    }

    @Test
    fun absoluteAndRelativePositionConstructorsMirrorUpstreamHelpers() {
        val doc = YDoc(clientId = 1)
        val text = doc.getText("text")
        text.insert(0, "ab")

        val absolute = createAbsolutePosition(text, 1, assoc = -1)
        val uncheckedAbsolute = createAbsolutePosition(text, -1, assoc = 1)
        val relative = createRelativePosition(text, Id(1, 0), assoc = -1)

        assertEquals(AbsolutePosition(text, 1, -1), absolute)
        assertEquals(AbsolutePosition(text, -1, 1), uncheckedAbsolute)
        assertEquals(RelativePosition(tname = "text", item = Id(1, 0), assoc = -1), relative)
        assertEquals(1, createAbsolutePositionFromRelativePosition(relative, doc)?.index)
    }

    @Test
    fun relativePositionCanFollowTextRestoredByUndo() {
        val doc = YDoc(clientId = 1)
        val text = doc.getText("text")
        text.insert(0, "hello world")
        val position = createRelativePositionFromTypeIndex(text, 1)
        val undoManager = UndoManager(text, UndoManagerOptions(captureTimeoutMillis = 0))

        text.delete(0, 6)

        assertEquals("world", text.toString())
        assertEquals(0, createAbsolutePositionFromRelativePosition(position, doc)?.index)

        undoManager.undo()

        assertEquals("hello world", text.toString())
        assertEquals(1, createAbsolutePositionFromRelativePosition(position, doc)?.index)
        assertEquals(
            6,
            createAbsolutePositionFromRelativePosition(position, doc, followUndoneDeletions = false)?.index,
        )

        val cloned = createDocFromUpdate(doc.encodeStateAsUpdate())
        assertEquals("hello world", cloned.getText("text").toString())
        assertEquals(6, createAbsolutePositionFromRelativePosition(position, cloned)?.index)
        assertEquals(
            6,
            createAbsolutePositionFromRelativePosition(position, cloned, followUndoneDeletions = false)?.index,
        )
    }

    @Test
    fun relativePositionResolvesInsideNestedText() {
        val doc = YDoc(clientId = 1)
        val root = doc.getArray("root")
        val nested = doc.createText()
        nested.insert(0, "abc")
        root.push(nested)
        val position = createRelativePositionFromTypeIndex(nested, 2)

        nested.insert(1, "x")

        assertEquals("axbc", nested.toString())
        assertEquals(3, createAbsolutePositionFromRelativePosition(position, doc)?.index)

        val cloned = createDocFromUpdate(doc.encodeStateAsUpdate())
        assertEquals("axbc", (cloned.getArray("root").get(0) as YText).toString())
        assertEquals(3, createAbsolutePositionFromRelativePosition(position, cloned)?.index)
    }

    @Test
    fun relativePositionTypeIdResolvesNestedTypeEndpoints() {
        val doc = YDoc(clientId = 1)
        val root = doc.getArray("root")
        val nested = doc.createText()
        nested.insert(0, "abc")
        root.push(nested)
        val typeId = decodeUpdate(doc.encodeStateAsUpdate()).structs.single {
            it.parent == "root" && it.parentSub == null
        }.id

        val beginning = RelativePosition(type = typeId, assoc = -1)
        val end = RelativePosition(type = typeId)
        val createdBeginning = createRelativePositionFromTypeIndex(nested, 0, assoc = -1)
        val createdEnd = createRelativePositionFromTypeIndex(nested, nested.length)

        assertEquals(0, createAbsolutePositionFromRelativePosition(beginning, doc)?.index)
        assertEquals(3, createAbsolutePositionFromRelativePosition(end, doc)?.index)
        assertEquals(typeId, createdBeginning.type)
        assertEquals(null, createdBeginning.tname)
        assertEquals(typeId, createdEnd.type)
        assertEquals(null, createdEnd.tname)

        val cloned = createDocFromUpdate(doc.encodeStateAsUpdate())
        assertEquals(0, createAbsolutePositionFromRelativePosition(beginning, cloned)?.index)
        assertEquals(3, createAbsolutePositionFromRelativePosition(end, cloned)?.index)
        assertEquals(0, createAbsolutePositionFromRelativePosition(createdBeginning, cloned)?.index)
        assertEquals(3, createAbsolutePositionFromRelativePosition(createdEnd, cloned)?.index)
    }

    @Test
    fun relativePositionFromNestedTypeIndexUsesTypeIdForItemAnchors() {
        val doc = YDoc(clientId = 1)
        val root = doc.getArray("root")
        val nested = doc.createText()
        nested.insert(0, "abc")
        root.push(nested)
        val typeId = decodeUpdate(doc.encodeStateAsUpdate()).structs.single {
            it.parent == "root" && it.parentSub == null
        }.id

        val position = createRelativePositionFromTypeIndex(nested, 1)
        val json = relativePositionToJSON(position)

        assertEquals(typeId, position.type)
        assertEquals(null, position.tname)
        assertNotNull(position.item)
        assertEquals(mapOf("client" to typeId.client, "clock" to typeId.clock), json["type"])
        assertFalse(json.containsKey("tname"))
        assertEquals(1, createAbsolutePositionFromRelativePosition(position, doc)?.index)
    }
}
