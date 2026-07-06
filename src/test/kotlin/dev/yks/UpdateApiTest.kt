package dev.yks

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class UpdateApiTest {
    @Test
    fun topLevelApplyAndEncodeHelpersMirrorDocumentMethods() {
        val source = YDoc(clientId = 1)
        source.getText("body").insert(0, "hi")

        val target = YDoc(clientId = 2)
        applyUpdate(target, encodeStateAsUpdate(source))

        assertEquals("hi", target.getText("body").toString())
        assertEquals(decodeStateVector(source.encodeStateVector()), decodeStateVector(encodeStateVector(source)))
    }

    @Test
    fun topLevelReadUpdateAliasesApplyUpdatesAndKeepOrigin() {
        val source = YDoc(clientId = 1)
        source.getText("body").insert(0, "hi")

        val target = YDoc(clientId = 2)
        val origins = mutableListOf<Any?>()
        target.observeAfterTransactions { event -> origins.add(event.origin) }

        readUpdate(target, encodeStateAsUpdate(source), origin = "read")

        val targetV2 = YDoc(clientId = 3)
        val v2Origins = mutableListOf<Any?>()
        targetV2.observeAfterTransactions { event -> v2Origins.add(event.origin) }

        readUpdateV2(targetV2, encodeStateAsUpdateV2(source), origin = "read-v2")

        val decoderTarget = YDoc(clientId = 4)
        val decoderOrigins = mutableListOf<Any?>()
        decoderTarget.observeAfterTransactions { event -> decoderOrigins.add(event.origin) }

        readUpdate(BinaryDecoder(encodeStateAsUpdate(source)), decoderTarget, origin = "decoder-read")

        val decoderV2Target = YDoc(clientId = 5)
        val decoderV2Origins = mutableListOf<Any?>()
        decoderV2Target.observeAfterTransactions { event -> decoderV2Origins.add(event.origin) }

        readUpdateV2(UpdateDecoderV2(encodeStateAsUpdateV2(source)), decoderV2Target, origin = "decoder-read-v2")

        assertEquals("hi", target.getText("body").toString())
        assertEquals(listOf<Any?>("read"), origins)
        assertEquals("hi", targetV2.getText("body").toString())
        assertEquals(listOf<Any?>("read-v2"), v2Origins)
        assertEquals("hi", decoderTarget.getText("body").toString())
        assertEquals(listOf<Any?>("decoder-read"), decoderOrigins)
        assertEquals("hi", decoderV2Target.getText("body").toString())
        assertEquals(listOf<Any?>("decoder-read-v2"), decoderV2Origins)
    }

    @Test
    fun mergeUpdatesCombinesIndependentClients() {
        val left = YDoc(clientId = 1)
        val right = YDoc(clientId = 2)
        left.getArray("items").push(listOf("left"))
        right.getArray("items").push(listOf("right"))

        val merged = mergeUpdates(listOf(encodeStateAsUpdate(left), encodeStateAsUpdate(right)))
        val target = YDoc(clientId = 3)
        applyUpdate(target, merged)

        assertEquals(listOf("left", "right"), target.getArray("items").toList())
        assertEquals(mapOf(2L to 1L, 1L to 1L), decodeStateVector(encodeStateVectorFromUpdate(merged)))
    }

    @Test
    fun mergeUpdatesReturnsSingleInputUpdateUnchanged() {
        val source = YDoc(clientId = 1)
        source.getText("body").insert(0, "x")
        val update = source.encodeStateAsUpdate()

        assertSame(update, mergeUpdates(listOf(update)))
        assertSame(update, mergeUpdatesV2(listOf(update)))
    }

    @Test
    fun mergeUpdatesHandlesOverlappingIncrementalBatchesLikeUpstream() {
        val source = YDoc(clientId = 1, gc = false)
        val updates = mutableListOf<ByteArray>()
        source.observeUpdates { update, _ -> updates.add(update) }
        val array = source.get()

        array.insert(0, listOf(1))
        array.insert(0, listOf(2))
        array.insert(0, listOf(3))
        array.insert(0, listOf(4))

        assertMergedIncrementalUpdateCases(source, updates)
    }

    @Test
    fun mergeUpdatesHandlesOverlappingIncrementalBatchesWithDeletesLikeUpstream() {
        val source = YDoc(clientId = 1, gc = false)
        val updates = mutableListOf<ByteArray>()
        source.observeUpdates { update, _ -> updates.add(update) }
        val array = source.get()

        array.insert(0, listOf(1, 2))
        array.delete(1)
        array.insert(0, listOf(3, 4))
        array.delete(1, 2)

        assertMergedIncrementalUpdateCases(source, updates)
    }

    @Test
    fun mergePendingIncrementalUpdatesResolvesOutOfOrderLikeUpstream() {
        val source = YDoc(clientId = 1)
        val serverUpdates = mutableListOf<ByteArray>()
        source.observeUpdates { update, _ -> serverUpdates.add(update) }
        val text = source.getText("textBlock")

        listOf("r", "o", "n", "e", "n").forEach { letter ->
            text.applyDelta(YTextDelta().insert(letter))
        }

        val doc1 = YDoc(clientId = 2)
        applyUpdate(doc1, serverUpdates[0])
        val update1 = encodeStateAsUpdate(doc1)

        val doc2 = YDoc(clientId = 3)
        applyUpdate(doc2, update1)
        applyUpdate(doc2, serverUpdates[1])
        val update2 = encodeStateAsUpdate(doc2)

        val doc3 = YDoc(clientId = 4)
        applyUpdate(doc3, update2)
        applyUpdate(doc3, serverUpdates[3])
        val update3 = encodeStateAsUpdate(doc3)

        val doc4 = YDoc(clientId = 5)
        applyUpdate(doc4, update3)
        applyUpdate(doc4, serverUpdates[2])
        val update4 = encodeStateAsUpdate(doc4)

        val doc5 = YDoc(clientId = 6)
        applyUpdate(doc5, update4)
        applyUpdate(doc5, serverUpdates[4])

        assertEquals("nenor", doc5.getText("textBlock").toString())
        assertNull(doc5.store.pendingStructs)
        assertNull(doc5.store.pendingDs)
    }

    @Test
    fun diffUpdateOnlyIncludesStructsMissingFromStateVector() {
        val source = YDoc(clientId = 1)
        val text = source.getText("body")
        text.insert(0, "a")
        val firstUpdate = encodeStateAsUpdate(source)
        val stateAfterFirst = encodeStateVector(source)
        text.insert(1, "b")

        val diff = diffUpdate(encodeStateAsUpdate(source), stateAfterFirst)
        val target = YDoc(clientId = 2)
        applyUpdate(target, firstUpdate)
        applyUpdate(target, diff)

        assertEquals("ab", target.getText("body").toString())
        assertEquals(emptyMap(), decodeStateVector(encodeStateVectorFromUpdate(diff)))
    }

    @Test
    fun encodeStateVectorFromUpdateIsEmptyForSingleDiffUpdateMissingPriorOps() {
        val source = YDoc(clientId = 1)
        var diffUpdate: ByteArray? = null
        val array = source.get()

        array.insert(0, listOf("a"))
        source.observeUpdates { update, _ -> diffUpdate = update }
        array.insert(0, listOf("a"))

        val stateVector = decodeStateVector(encodeStateVectorFromUpdate(checkNotNull(diffUpdate)))

        assertEquals(emptyMap(), stateVector)
        assertContentEquals(byteArrayOf(0), encodeStateVectorFromUpdate(checkNotNull(diffUpdate)))
    }

    @Test
    fun encodeStateVectorFromUpdateStopsAtGapsInMergedUpdatesLikeUpstream() {
        val source = YDoc(clientId = 1)
        val updates = mutableListOf<ByteArray>()
        source.observeUpdates { update, _ -> updates.add(update) }
        val array = source.get()

        array.insert(0, listOf("a"))
        array.insert(0, listOf("b"))
        array.insert(0, listOf("c"))

        val updateWithGap = mergeUpdates(listOf(updates[0], updates[2]))
        val stateVector = decodeStateVector(encodeStateVectorFromUpdate(updateWithGap))

        assertEquals(mapOf(1L to 1L), stateVector)
    }

    @Test
    fun encodeStateVectorFromUpdateOnlyCountsContiguousClientPrefixes() {
        val fullClient = YDoc(clientId = 1)
        fullClient.getText("body").insert(0, "ab")
        val partialClient = YDoc(clientId = 2)
        val partialText = partialClient.getText("body")
        partialText.insert(0, "x")
        val partialState = partialClient.encodeStateVector()
        partialText.insert(1, "y")
        val partialDiff = diffUpdate(partialClient.encodeStateAsUpdate(), partialState)

        val merged = mergeUpdates(listOf(fullClient.encodeStateAsUpdate(), partialDiff))

        assertEquals(mapOf(1L to 2L), decodeStateVector(encodeStateVectorFromUpdate(merged)))
        assertEquals(decodeStateVector(encodeStateVectorFromUpdateV2(merged)), decodeStateVector(encodeStateVectorFromUpdate(merged)))
    }

    @Test
    fun writeStateAsUpdateWritesToProvidedEncoder() {
        val source = YDoc(clientId = 1)
        val text = source.getText("body")
        text.insert(0, "a")
        val stateAfterFirst = decodeStateVector(source.encodeStateVector())
        text.insert(1, "b")
        text.delete(0)

        val fullEncoder = BinaryEncoder()
        val returned = writeStateAsUpdate(fullEncoder, source)

        val diffEncoder = UpdateEncoderV2()
        val returnedDiff = writeStateAsUpdate(diffEncoder, source, stateAfterFirst)

        assertSame(fullEncoder, returned)
        assertContentEquals(source.encodeStateAsUpdate(), fullEncoder.toByteArray())
        assertSame(diffEncoder, returnedDiff)
        assertContentEquals(
            source.encodeStateAsUpdate(encodeStateVector(stateAfterFirst)),
            diffEncoder.toByteArray(),
        )
        assertContentEquals(
            diffEncoder.toByteArray(),
            writeStateAsUpdateV2(UpdateEncoderV2(), source, stateAfterFirst).toByteArray(),
        )
    }

    @Test
    fun writeClientsStructsWritesOnlyStateVectorDiffStructs() {
        val source = YDoc(clientId = 1)
        val text = source.getText("body")
        text.insert(0, "a")
        val initialUpdate = source.encodeStateAsUpdate()
        val stateAfterFirst = decodeStateVector(source.encodeStateVector())
        text.insert(1, "b")
        text.delete(0)
        val encoder = BinaryEncoder()
        val returned = writeClientsStructs(encoder, source.store, stateAfterFirst)
        val decoded = decodeUpdate(encoder.toByteArray())

        assertSame(encoder, returned)
        assertEquals(listOf(Id(1, 1)), decoded.structs.map { it.id })
        assertTrue(decoded.deleteSet.isEmpty)

        val target = createDocFromUpdate(initialUpdate)
        applyUpdate(target, encoder.toByteArray())

        assertEquals("ab", target.getText("body").toString())
    }

    @Test
    fun writeClientsStructsSupportsYjsShapedEncoders() {
        val source = YDoc(clientId = 1)
        source.getText("body").insert(0, "xy")
        val encoder = UpdateEncoderV2()

        assertSame(encoder, writeClientsStructs(encoder, source.store))

        val target = createDocFromUpdate(encoder.toUint8Array())
        assertEquals("xy", target.getText("body").toString())
        assertTrue(decodeUpdate(encoder.toByteArray()).deleteSet.isEmpty)
    }

    @Test
    fun decodeUpdateExposesStructAndDeleteMetadata() {
        val source = YDoc(clientId = 1)
        val text = source.getText("body")
        text.insert(0, "abc")
        text.delete(1)

        val decoded = decodeUpdate(source.encodeStateAsUpdate())

        assertEquals(3, decoded.structs.size)
        assertEquals(Id(1, 0), decoded.structs[0].id)
        assertEquals(RootKind.Text, decoded.structs[0].kind)
        assertEquals("body", decoded.structs[0].parent)
        assertEquals("a", (decoded.structs[0].content as ContentString).str)
        assertEquals(contentStringRefNumber, decoded.structs[0].content.getRef())
        assertEquals(1, decoded.structs.count { it.deleted })
        assertTrue(decoded.deleteSet.contains(Id(1, 1)))
        assertSame(decoded.deleteSet, decoded.ds)

        val decodedV2 = decodeUpdateV2(encodeStateAsUpdateV2(source))
        assertEquals(decoded.structs, decodedV2.structs)
        assertTrue(decodedV2.deleteSet.contains(Id(1, 1)))
        assertSame(decodedV2.deleteSet, decodedV2.ds)
    }

    @Test
    fun lazyStructReaderIteratesDecodedStructsAndExposesDeleteSet() {
        val source = YDoc(clientId = 1)
        val text = source.getText("body")
        text.insert(0, "abc")
        text.delete(1)
        val update = source.encodeStateAsUpdate()
        val reader = LazyStructReader(update)
        val seen = mutableListOf<AbstractStruct>()

        var current = reader.curr
        while (current != null) {
            seen.add(current)
            current = reader.next()
        }

        assertEquals(listOf(Id(1, 0), Id(1, 1), Id(1, 2)), seen.map { it.id })
        assertEquals(listOf(false, true, false), seen.map { it.deleted })
        assertTrue(reader.done)
        assertNull(reader.curr)
        assertTrue(reader.deleteSet.contains(Id(1, 1)))
        assertTrue(reader.ds.contains(Id(1, 1)))

        val decoderReader = LazyStructReader(UpdateDecoderV1(update))
        assertEquals(Id(1, 0), decoderReader.curr?.id)
        assertEquals(Id(1, 0), LazyStructReader(UpdateDecoderV2(encodeStateAsUpdateV2(source))).curr?.id)
        assertEquals(Id(1, 0), LazyStructReader(BinaryDecoder(update)).curr?.id)
    }

    @Test
    fun lazyStructWriterEncodesCollectedStructsAsLocalUpdate() {
        val source = YDoc(clientId = 1)
        source.getText("body").insert(0, "ab")
        val update = source.encodeStateAsUpdate()
        val reader = LazyStructReader(update)
        val updateEncoder = UpdateEncoderV1()
        val writer = LazyStructWriter(updateEncoder)

        var current = reader.curr
        while (current != null) {
            assertSame(writer, writeStructToLazyStructWriter(writer, current))
            current = reader.next()
        }
        assertSame(writer.encoder, finishLazyStructWriting(writer))

        val target = createDocFromUpdate(updateEncoder.toByteArray())

        assertEquals("ab", target.getText("body").toString())
        assertEquals(2, writer.written)
        assertEquals(1L, writer.currClient)
        assertEquals(Id(1, 0), writer.clientStructs.first().id)
        assertContentEquals(updateEncoder.toByteArray(), writer.toUint8Array())
        assertTrue(decodeUpdate(updateEncoder.toByteArray()).deleteSet.isEmpty)
    }

    @Test
    fun lazyStructWriterAcceptsDecodedStructsAndRejectsUnsupportedStructs() {
        val source = YDoc(clientId = 1)
        source.getArray("items").push("x")
        val decoded = decodeUpdate(source.encodeStateAsUpdate())
        val writer = LazyStructWriter()

        writeStructToLazyStructWriter(writer, decoded.structs.single())

        assertEquals(listOf("x"), createDocFromUpdate(writer.toByteArray()).getArray("items").toList())
        assertFailsWith<IllegalStateException> {
            writeStructToLazyStructWriter(LazyStructWriter(), GC(Id(1, 0), 1))
        }
        assertFailsWith<IllegalArgumentException> {
            writeStructToLazyStructWriter(LazyStructWriter(), decoded.structs.single(), offset = 1)
        }
    }

    @Test
    fun lazyStructWriterSlicesTextStructsWithOffsets() {
        val struct = DecodedUpdateStruct(
            id = Id(1, 0),
            origin = null,
            rightOrigin = null,
            parent = "body",
            parentSub = null,
            kind = RootKind.Text,
            deleted = false,
            length = 4,
            content = ContentString("abcd"),
        )
        val baseWriter = LazyStructWriter()
        val sliceWriter = LazyStructWriter()

        writeStructToLazyStructWriter(baseWriter, struct, offset = 0, offsetEnd = 3)
        writeStructToLazyStructWriter(sliceWriter, struct, offset = 1, offsetEnd = 1)
        val decoded = decodeUpdate(sliceWriter.toByteArray())
        val target = createDocFromUpdate(baseWriter.toByteArray())
        applyUpdate(target, sliceWriter.toByteArray())

        assertEquals("abc", target.getText("body").toString())
        assertEquals(1, sliceWriter.written)
        assertEquals(1L, sliceWriter.currClient)
        assertEquals(Id(1, 1), sliceWriter.clientStructs.single().id)
        assertEquals(2, sliceWriter.clientStructs.single().length)
        assertEquals(listOf(Id(1, 1), Id(1, 2)), decoded.structs.map { it.id })
        assertEquals(listOf(Id(1, 0), Id(1, 1)), decoded.structs.map { it.origin })
        assertEquals(listOf("b", "c"), decoded.structs.map { (it.content as ContentString).str })
    }

    @Test
    fun lazyStructWriterSlicesArrayStructsWithOffsets() {
        val struct = DecodedUpdateStruct(
            id = Id(2, 0),
            origin = null,
            rightOrigin = null,
            parent = "items",
            parentSub = null,
            kind = RootKind.Array,
            deleted = false,
            length = 3,
            content = ContentAny(listOf("a", "b", "c")),
        )
        val baseWriter = LazyStructWriter()
        val sliceWriter = LazyStructWriter()

        writeStructToLazyStructWriter(baseWriter, struct, offset = 0, offsetEnd = 2)
        writeStructToLazyStructWriter(sliceWriter, struct, offset = 1, offsetEnd = 1)
        val target = createDocFromUpdate(baseWriter.toByteArray())
        applyUpdate(target, sliceWriter.toByteArray())

        assertEquals(listOf("a", "b"), target.getArray("items").toList())
        assertEquals(listOf(Id(2, 1)), decodeUpdate(sliceWriter.toByteArray()).structs.map { it.id })
        assertEquals(Id(2, 1), sliceWriter.clientStructs.single().id)
        assertEquals(1, sliceWriter.clientStructs.single().length)
    }

    @Test
    fun updateFormatConversionsAreStableForLocalUnifiedCodec() {
        val source = YDoc(clientId = 1)
        source.getArray("items").push(listOf("x"))
        val update = source.encodeStateAsUpdate()

        val asV2 = convertUpdateFormatV1ToV2(update)
        val backToV1 = convertUpdateFormatV2ToV1(asV2)

        assertContentEquals(update, asV2)
        assertContentEquals(update, backToV1)

        asV2[0] = (asV2[0].toInt() xor 1).toByte()
        assertFalse(update.contentEquals(asV2))
    }

    @Test
    fun convertUpdateFormatTransformsDecodedStructsAndPreservesMetadata() {
        val source = YDoc(clientId = 1)
        val text = source.getText("body")
        text.insert(0, "ab", mapOf("bold" to true))
        text.delete(1)
        val update = source.encodeStateAsUpdate()

        assertEquals(
            source.getText("body").toDelta(),
            createDocFromUpdate(convertUpdateFormat(update)).getText("body").toDelta(),
        )

        val converted = convertUpdateFormat(update) { struct ->
            if (struct.content is ContentString && struct.content.str == "a") {
                struct.copy(content = ContentString("z"))
            } else {
                struct
            }
        }
        val convertedDoc = createDocFromUpdate(converted)
        val convertedDecoded = decodeUpdate(converted)

        assertEquals(YTextDelta().insert("z", mapOf("bold" to true)), convertedDoc.getText("body").toDelta())
        assertTrue(convertedDecoded.deleteSet.contains(Id(1, 1)))
        assertEquals(
            decodeUpdate(update).structs.map { it.structuralFields() },
            convertedDecoded.structs.map { it.structuralFields() },
        )
    }

    @Test
    fun convertUpdateFormatExpandsTransformedStructContent() {
        val source = YDoc(clientId = 1)
        source.getText("body").insert(0, "a", mapOf("bold" to true))

        val converted = convertUpdateFormat(source.encodeStateAsUpdate()) { struct ->
            if (struct.content is ContentString) {
                struct.copy(length = 2, content = ContentString("xy"))
            } else {
                struct
            }
        }
        val decoded = decodeUpdate(converted)

        assertEquals(
            YTextDelta().insert("xy", mapOf("bold" to true)),
            createDocFromUpdate(converted).getText("body").toDelta(),
        )
        assertEquals(listOf(Id(1, 0), Id(1, 1)), decoded.structs.map { it.id })
        assertEquals(listOf(null, Id(1, 0)), decoded.structs.map { it.origin })
        assertEquals(mapOf(1L to 2L), decodeStateVector(encodeStateVectorFromUpdate(converted)))
    }

    @Test
    fun obfuscateUpdatePreservesStructureAndDeleteSetWhileReplacingContent() {
        val source = YDoc(clientId = 1)
        val text = source.getText("body")
        text.insert(0, "secret", mapOf("author" to "alice", "bold" to true))
        text.delete(1)
        source.getMap("meta").setAttrs(mapOf(
            "title" to "private",
            "count" to 42,
            "nested" to mapOf("token" to "hidden"),
        ))
        source.getArray("items").push(listOf(
            "visible",
            7,
            listOf("nested"),
            mapOf("field" to "value"),
            byteArrayOf(1, 2, 3),
        ))
        source.getXmlFragment("xml").push(listOf(
            YXmlElement("p").also { element ->
                element.setAttr("title", "hidden")
                element.push(listOf(YXmlText("hello")))
            },
        ))
        val update = source.encodeStateAsUpdate()

        val obfuscated = obfuscateUpdate(update)
        val decoded = decodeUpdate(update)
        val decodedObfuscated = decodeUpdate(obfuscated)
        val obfuscatedDoc = createDocFromUpdate(obfuscated)

        assertEquals(
            decoded.structs.map { it.structuralFields() },
            decodedObfuscated.structs.map { it.structuralFields() },
        )
        assertNotEquals(decoded.structs.first().content, decodedObfuscated.structs.first().content)
        assertTrue(decoded.deleteSet.structurallyEquals(decodedObfuscated.deleteSet))
        assertTrue(decodedObfuscated.deleteSet.contains(Id(1, 1)))
        assertEquals(
            decodeStateVector(encodeStateVectorFromUpdate(update)),
            decodeStateVector(encodeStateVectorFromUpdate(obfuscated)),
        )
        assertNotEquals(source.toJson(), obfuscatedDoc.toJson())
        assertEquals("00000", obfuscatedDoc.getText("body").toString())
        assertEquals(
            mapOf("count" to 0L, "nested" to mapOf("token" to "000000"), "title" to "0000000"),
            obfuscatedDoc.getMap("meta").toMap(),
        )
        assertEquals(
            listOf("0000000", 0L, listOf("000000"), mapOf("field" to "00000")),
            obfuscatedDoc.getArray("items").toList().dropLast(1),
        )
        assertContentEquals(ByteArray(3), obfuscatedDoc.getArray("items").get(4) as ByteArray)
        assertEquals("<typename-2 title=\"000000\">00000</typename-2>", obfuscatedDoc.getXmlFragment("xml").toString())
    }

    @Test
    fun obfuscateUpdateV2UsesTheSameLocalUnifiedCodec() {
        val source = YDoc(clientId = 1)
        source.getText("body").insert(0, "secret")
        val update = source.encodeStateAsUpdate()

        assertContentEquals(obfuscateUpdate(update), obfuscateUpdateV2(update))
    }

    @Test
    fun obfuscateUpdateReplacesTextEmbedPayloadsAndAttributes() {
        val source = YDoc(clientId = 1)
        val embed = mapOf("url" to "private", "count" to 42L)
        source.getText("body").insertEmbed(0, embed, mapOf("label" to "hero", "visible" to true))

        val obfuscated = createDocFromUpdate(obfuscateUpdate(source.encodeStateAsUpdate()))

        assertEquals(
            YTextDelta().insertEmbed(
                mapOf("count" to 0L, "url" to "0000000"),
                mapOf("0" to "0000", "1" to false),
            ),
            obfuscated.getText("body").toDelta(),
        )
    }

    @Test
    fun obfuscateUpdatePreservesOrReplacesFormattedXmlTextAttributes() {
        val source = YDoc(clientId = 1)
        source.getXmlFragment("xml").applyDelta(listOf(
            YArrayDeltaOp(
                insert = listOf("secret"),
                attributes = mapOf("bold" to true, "label" to "private"),
            ),
        ))

        val obfuscated = createDocFromUpdate(obfuscateUpdate(source.encodeStateAsUpdate()))
        val preservedFormatting = createDocFromUpdate(
            obfuscateUpdate(
                source.encodeStateAsUpdate(),
                ObfuscatorOptions(formatting = false),
            ),
        )

        assertEquals(
            listOf(YArrayDeltaOp(
                insert = listOf("000000"),
                attributes = mapOf("0" to false, "1" to "0000000"),
            )),
            obfuscated.getXmlFragment("xml").toDelta(),
        )
        assertEquals(
            listOf(YArrayDeltaOp(
                insert = listOf("000000"),
                attributes = mapOf("bold" to true, "label" to "private"),
            )),
            preservedFormatting.getXmlFragment("xml").toDelta(),
        )
    }

    @Test
    fun obfuscateUpdateOptionsCanPreserveFormattingSubdocsAndNames() {
        val source = YDoc(clientId = 1)
        val subdoc = YDoc(guid = "subdoc-secret")
        val liveElement = source.createXmlElement("section")
        liveElement.push(YXmlText("live"))
        source.getText("body").insert(0, "x", mapOf("label" to "secret"))
        source.getArray("items").push(listOf(subdoc))
        source.getXmlFragment("xml").push(YXmlElement("paragraph").also { it.push(YXmlText("hidden")) })
        source.getXmlFragment("live-xml").push(liveElement)

        val obfuscated = createDocFromUpdate(
            obfuscateUpdate(
                source.encodeStateAsUpdate(),
                ObfuscatorOptions(formatting = false, subdocs = false, name = false),
            ),
        )

        assertEquals(YTextDelta().insert("0", mapOf("label" to "secret")), obfuscated.getText("body").toDelta())
        assertEquals("subdoc-secret", (obfuscated.getArray("items").get(0) as YDoc).guid)
        assertEquals("<paragraph>000000</paragraph>", obfuscated.getXmlFragment("xml").toString())
        assertEquals("<section>0000</section>", obfuscated.getXmlFragment("live-xml").toString())
    }

    @Test
    fun logUpdateSummarizesStructsAndDeleteSet() {
        val source = YDoc(clientId = 1)
        val text = source.getText("body")
        text.insert(0, "ab")
        text.delete(0)

        val log = logUpdate(source.encodeStateAsUpdate())

        assertTrue("Structs[" in log)
        assertTrue("1:0:Text" in log)
        assertTrue("DeleteSet[" in log)
        assertTrue("1=[0..1]" in log)
        assertEquals(log, logUpdateV2(encodeStateAsUpdateV2(source)))
    }

    @Test
    fun writeStructsEncodesSelectedDocumentRangesWithoutDeleteSet() {
        val source = YDoc(clientId = 1)
        source.getText("body").insert(0, "abc")
        val selected = createIdSet().also { idSet ->
            idSet.add(1, 1, 1)
        }

        val byRange = writeStructs(source, 1, listOf(IdRange(1, 1)))
        val byIdSet = writeStructsFromIdSet(source, selected)
        val decoded = decodeUpdate(byRange)

        assertContentEquals(byRange, byIdSet)
        assertEquals(listOf(Id(1, 1)), decoded.structs.map { it.id })
        assertEquals("b", (decoded.structs.single().content as ContentString).str)
        assertTrue(decoded.deleteSet.isEmpty)
        assertContentEquals(byRange, writeStructsV2(source, 1, listOf(IdRange(1, 1))))
        assertContentEquals(byIdSet, writeStructsFromIdSetV2(source, selected))
    }

    @Test
    fun writeStructsFromTransactionEncodesOnlyInsertedStructs() {
        val source = YDoc(clientId = 1)
        lateinit var insertEvent: YTransactionEvent
        source.observeAfterTransactions { event -> insertEvent = event }

        source.getText("body").insert(0, "xy")

        val structsOnly = writeStructsFromTransaction(insertEvent)
        val decoded = decodeUpdate(structsOnly)
        assertEquals(listOf(Id(1, 0), Id(1, 1)), decoded.structs.map { it.id })
        assertTrue(decoded.deleteSet.isEmpty)
        assertContentEquals(structsOnly, encodeStructsFromTransaction(insertEvent))
        assertContentEquals(structsOnly, writeStructsFromTransactionV2(insertEvent))
        assertContentEquals(structsOnly, encodeStructsFromTransactionV2(insertEvent))
    }

    @Test
    fun writeUpdateMessageFromTransactionReturnsFullTransactionUpdate() {
        val source = YDoc(clientId = 1)
        lateinit var insertEvent: YTransactionEvent
        lateinit var deleteEvent: YTransactionEvent
        source.observeAfterTransactions { event ->
            if (event.insertSet.isEmpty()) deleteEvent = event else insertEvent = event
        }

        val text = source.getText("body")
        text.insert(0, "xy")
        text.delete(0)

        val target = YDoc(clientId = 2)
        applyUpdate(target, writeUpdateMessageFromTransaction(insertEvent)!!)
        applyUpdate(target, writeUpdateMessageFromTransaction(deleteEvent)!!)

        assertEquals("y", target.getText("body").toString())
        assertContentEquals(insertEvent.update, encodeUpdateMessageFromTransaction(insertEvent)!!)
        assertContentEquals(deleteEvent.update, writeUpdateMessageFromTransactionV2(deleteEvent)!!)
        assertContentEquals(deleteEvent.update, encodeUpdateMessageFromTransactionV2(deleteEvent)!!)
    }

    @Test
    fun writeUpdateMessageFromTransactionReturnsNullForEmptyBeforeTransaction() {
        val source = YDoc(clientId = 1)
        lateinit var beforeEvent: YTransactionEvent
        source.observeBeforeTransactions { event -> beforeEvent = event }

        source.getText("body").insert(0, "x")

        assertNull(writeUpdateMessageFromTransaction(beforeEvent))
        assertNull(encodeUpdateMessageFromTransaction(beforeEvent))
        assertTrue(decodeUpdate(writeStructsFromTransaction(beforeEvent)).structs.isEmpty())
    }

    private fun DecodedUpdateStruct.structuralFields(): List<Any?> = listOf(
        id,
        origin,
        rightOrigin,
        parent,
        parentSub,
        kind,
        deleted,
        length,
    )

    private fun assertMergedIncrementalUpdateCases(source: YDoc, updates: List<ByteArray>) {
        require(updates.size >= 4)
        val expected = source.get().toArray()
        val expectedState = decodeStateVector(encodeStateVector(source))
        val cases = listOf(
            mergeUpdates(updates),
            mergeUpdates(listOf(
                mergeUpdates(updates.drop(2)),
                mergeUpdates(updates.take(2)),
            )),
            mergeUpdates(listOf(
                mergeUpdates(updates.drop(2)),
                mergeUpdates(updates.subList(1, 3)),
                updates[0],
            )),
            mergeUpdates(listOf(
                mergeUpdates(listOf(updates[0], updates[2])),
                mergeUpdates(listOf(updates[1], updates[3])),
                mergeUpdates(updates.drop(4)),
            )),
        )
        val duplicated = mergeUpdates(cases)

        (cases + duplicated).forEach { merged ->
            val target = YDoc(clientId = 2, gc = false)
            applyUpdate(target, merged)

            assertEquals(expected, target.get().toArray())
            assertEquals(expectedState, decodeStateVector(encodeStateVector(target)))
            assertEquals(expectedState, decodeStateVector(encodeStateVectorFromUpdate(merged)))

            for (index in 1 until updates.size) {
                val partMerged = mergeUpdates(updates.drop(index))
                val targetStateVector = encodeStateVectorFromUpdate(mergeUpdates(updates.take(index)))
                val diffed = diffUpdate(merged, targetStateVector)

                assertEquals(
                    createContentIdsFromUpdate(partMerged).inserts.ranges(),
                    createContentIdsFromUpdate(diffed).inserts.ranges(),
                )
            }
        }
    }
}
