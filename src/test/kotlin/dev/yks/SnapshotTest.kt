package dev.yks

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SnapshotTest {
    @Test
    fun snapshotEncodingRoundTripsAndComparesStructurally() {
        val doc = YDoc(clientId = 1)
        doc.getArray("items").push(listOf("a", "b"))
        doc.getArray("items").delete(0)

        val snap = snapshot(doc)
        val decoded = decodeSnapshot(encodeSnapshot(snap))

        assertTrue(equalSnapshots(snap, decoded))
        assertFalse(equalSnapshots(emptySnapshot, snap))
    }

    @Test
    fun snapshotV1AndV2UseMatchingIdSetCodecs() {
        val deleteSet = createIdSet().also { ids ->
            ids.add(1, 2, 2)
            ids.add(1, 8, 1)
            ids.add(3, 1, 1)
        }.toDeleteSet()
        val snap = createSnapshot(deleteSet, mapOf(1L to 9L, 3L to 2L))

        val encodedV1 = encodeSnapshot(snap)
        val encodedV2 = encodeSnapshotV2(snap)

        assertContentEquals(encodedV1, encodeSnapshotV2(snap, IdSetEncoderV1()))
        assertFalse(encodedV1.contentEquals(encodedV2))
        assertTrue(equalSnapshots(snap, decodeSnapshot(encodedV1)))
        assertTrue(equalSnapshots(snap, decodeSnapshotV2(encodedV2)))
        assertTrue(equalSnapshots(snap, decodeSnapshot(IdSetDecoderV1(encodedV1))))
        assertTrue(equalSnapshots(snap, decodeSnapshotV2(IdSetDecoderV2(encodedV2))))
    }

    @Test
    fun snapshotAcceptsPublicIdSetDeleteMetadata() {
        val deleteIds = createIdSet().also { ids -> ids.add(1, 2, 2) }
        val snap = createSnapshot(deleteIds, mapOf(1L to 4L))

        deleteIds.add(1, 10, 1)

        val direct = Snapshot(snap.ds, snap.sv)

        assertTrue(snap.ds.hasId(Id(1, 2)))
        assertTrue(snap.ds.hasId(Id(1, 3)))
        assertFalse(snap.ds.hasId(Id(1, 10)))
        assertTrue(snap.deleteSet.contains(Id(1, 2)))
        assertTrue(equalSnapshots(snap, direct))
    }

    @Test
    fun snapshotContainsUpdateTracksStructAndDeleteCoverage() {
        val doc = YDoc(clientId = 1)
        val updates = mutableListOf<ByteArray>()
        doc.observeUpdates { update, _ -> updates.add(update) }
        val array = doc.getArray("items")

        val before = snapshot(doc)
        array.push(listOf("a"))
        val afterInsert = snapshot(doc)
        array.delete(0)
        val afterDelete = snapshot(doc)

        assertFalse(snapshotContainsUpdate(before, updates[0]))
        assertTrue(snapshotContainsUpdate(afterInsert, updates[0]))
        assertFalse(snapshotContainsUpdate(afterInsert, updates[1]))
        assertTrue(snapshotContainsUpdate(afterDelete, updates[0]))
        assertTrue(snapshotContainsUpdate(afterDelete, updates[1]))
    }

    @Test
    fun splitSnapshotAffectedStructsCachesSnapshotOnTransactionMeta() {
        val doc = YDoc(clientId = 1)
        val text = doc.getText("body")
        text.insert(0, "abc")
        text.delete(1)
        val snap = snapshot(doc)
        text.insert(text.length, "d")

        doc.transact({ transaction ->
            splitSnapshotAffectedStructs(transaction, snap)
            splitSnapshotAffectedStructs(transaction, snap)

            val seen = transaction.meta[::splitSnapshotAffectedStructs] as Set<*>
            assertEquals(1, seen.size)
            assertTrue(snap in seen)

            text.insert(text.length, "!")
        })

        assertEquals("acd!", text.toString())
    }

    @Test
    fun createDocFromSnapshotRestoresEarlierArrayState() {
        val doc = YDoc(clientId = 1, gc = false)
        val array = doc.getArray("items")
        array.push(listOf("world"))
        val snap = snapshot(doc)
        array.insert(0, listOf("hello"))
        array.delete(1)

        val restored = createDocFromSnapshot(doc, snap)

        assertEquals(listOf("world"), restored.getArray("items").toList())
        assertEquals(listOf("hello"), doc.getArray("items").toList())
    }

    @Test
    fun createDocFromSnapshotUsesRootsCapturedAtSnapshotTimeOnly() {
        val doc = YDoc(clientId = 1, gc = false)
        doc.getArray("before")
        val beforeLaterRoot = snapshot(doc)

        doc.getMap("later").set("value", true)
        val restored = createDocFromSnapshot(doc, beforeLaterRoot)

        assertEquals(mapOf("before" to emptyList<Any?>()), restored.toJson())
        assertEquals(null, restored.getOrNull("later"))
    }

    @Test
    fun createDocFromEmptySnapshotIgnoresZeroClockStateVectorEntries() {
        val doc = YDoc(clientId = 1, gc = false)
        val snap = snapshot(doc).copy(sv = mapOf(9999L to 0L))
        doc.getArray("").insert(0, listOf("world"))

        val restored = createDocFromSnapshot(doc, snap)
        val latestRestored = createDocFromSnapshot(doc, snapshot(doc))

        assertEquals(emptyList(), restored.getArray("").toArray())
        assertEquals(listOf("world"), doc.getArray("").toArray())
        assertEquals(listOf("world"), latestRestored.getArray("").toArray())
    }

    @Test
    fun createDocFromSnapshotRestoresNestedSubtypeState() {
        val doc = YDoc(clientId = 1, gc = false)
        val array = doc.getArray("array")
        val nested = doc.createMap()
        array.insert(0, listOf(nested))
        nested.setAttr("key1", "value1")
        val snap = snapshot(doc)

        nested.setAttr("key2", "value2")

        val restored = createDocFromSnapshot(doc, snap)
        val restoredNested = restored.getArray("array").get(0) as YMap

        assertEquals(mapOf("key1" to "value1"), restoredNested.getAttrs())
        assertEquals(mapOf("key1" to "value1", "key2" to "value2"), nested.getAttrs())
    }

    @Test
    fun createDocFromSnapshotUsesLosslessInternalWireForStaticXml() {
        val doc = YDoc(clientId = 1, gc = false)
        val xml = doc.getXmlFragment("xml")
        xml.push(YXmlElement("p").also { it.push(YXmlText("old")) })
        val snap = snapshot(doc)
        xml.clear()
        xml.push(YXmlText("new"))

        val restored = createDocFromSnapshot(doc, snap)

        assertEquals("<p>old</p>", restored.getXmlFragment("xml").toString())
    }

    @Test
    fun createDocFromSnapshotRestoresDependentChangesFromEitherReplica() {
        val left = YDoc(clientId = 1, gc = false)
        val right = YDoc(clientId = 2, gc = false)
        val leftArray = left.getArray("array")
        val rightArray = right.getArray("array")

        leftArray.insert(0, listOf("user1item1"))
        syncDocs(left, right)
        rightArray.insert(1, listOf("user2item1"))
        syncDocs(left, right)

        val snap = snapshot(left)

        leftArray.insert(2, listOf("user1item2"))
        syncDocs(left, right)
        rightArray.insert(3, listOf("user2item2"))
        syncDocs(left, right)

        assertEquals(
            listOf("user1item1", "user2item1"),
            createDocFromSnapshot(left, snap).getArray("array").toArray(),
        )
        assertEquals(
            listOf("user1item1", "user2item1"),
            createDocFromSnapshot(right, snap).getArray("array").toArray(),
        )
    }

    @Test
    fun createDocFromSnapshotRejectsGcEnabledOriginDocs() {
        val doc = YDoc(clientId = 1)
        doc.getArray("items").push(listOf("world"))
        val snap = snapshot(doc)

        val error = assertFailsWith<IllegalStateException> {
            createDocFromSnapshot(doc, snap)
        }

        assertEquals("Garbage-collection must be disabled in `originDoc`!", error.message)
    }

    @Test
    fun createDocFromSnapshotUsesMutableGcFlag() {
        val doc = YDoc(clientId = 1)
        val array = doc.getArray("items")
        array.push(listOf("world"))
        val snap = snapshot(doc)
        array.push(listOf("later"))

        doc.gc = false

        val restored = createDocFromSnapshot(doc, snap)

        assertEquals(listOf("world"), restored.getArray("items").toList())
    }

    @Test
    fun snapshotContainsUpdateRejectsUpdatesWithNewerDeletesOnly() {
        val local = YDoc(clientId = 1)
        val remote = YDoc(clientId = 2)
        local.getText("t").insert(0, "abcdefghij")
        local.getText("t").delete(0, 3)
        remote.applyUpdate(local.encodeStateAsUpdate())
        val snap = snapshot(remote)

        local.getText("t").delete(0, 3)
        val update = local.encodeStateAsUpdate()

        assertFalse(snapshotContainsUpdate(snap, update))
    }

    @Test
    fun typeMapSnapshotHelpersReadHistoricalKeyValues() {
        val doc = YDoc(clientId = 1, gc = false)
        val map = doc.getMap("meta")
        map.setAttr("title", "old")
        map.setAttr("nullable", null)
        val initial = snapshot(doc)

        map.setAttr("title", "new")
        map.setAttr("count", 2)
        val updated = snapshot(doc)

        map.deleteAttr("title")
        map.setAttr("nullable", "filled")
        val deleted = snapshot(doc)

        assertEquals("old", typeMapGetSnapshot(map, "title", initial))
        assertEquals(null, typeMapGetSnapshot(map, "nullable", initial))
        assertTrue(typeMapGetAllSnapshot(map, initial).containsKey("nullable"))
        assertEquals(mapOf("nullable" to null, "title" to "old"), typeMapGetAllSnapshot(map, initial))
        assertEquals("old", map.get("title", initial))
        assertEquals("old", map.getAttr("title", initial))
        assertTrue(map.has("title", initial))
        assertEquals(mapOf("nullable" to null, "title" to "old"), map.getAttrs(initial))

        assertEquals("new", typeMapGetSnapshot(map, "title", updated))
        assertEquals(mapOf("count" to 2L, "nullable" to null, "title" to "new"), typeMapGetAllSnapshot(map, updated))
        assertEquals(mapOf("count" to 2L, "nullable" to null, "title" to "new"), map.toMap(updated))

        assertEquals(null, typeMapGetSnapshot(map, "title", deleted))
        assertFalse(typeMapGetAllSnapshot(map, deleted).containsKey("title"))
        assertEquals(mapOf("count" to 2L, "nullable" to "filled"), typeMapGetAllSnapshot(map, deleted))
        assertFalse(map.hasAttr("title", deleted))
    }

    @Test
    fun sequenceSnapshotHelpersReadHistoricalArrayTextAndXml() {
        val doc = YDoc(clientId = 1, gc = false)
        val array = doc.getArray("items")
        val text = doc.getText("body")
        val xml = doc.getXmlFragment("xml")

        array.push("a", "b")
        array.setAttr("role", "old-list")
        text.insert(0, "hi", mapOf("bold" to true))
        text.insertEmbed(2, mapOf("image" to "one"))
        text.setAttr("lang", "en")
        xml.push(listOf(YXmlElement("p").also { it.push(listOf(YXmlText("old"))) }))
        xml.setAttr("kind", "old-xml")
        val initial = snapshot(doc)

        array.delete(0)
        array.push("c")
        array.setAttr("role", "new-list")
        text.format(0, 2, mapOf("bold" to null))
        text.delete(1, 1)
        text.deleteAttr("lang")
        xml.delete(0)
        xml.push(listOf(YXmlText("new")))
        xml.setAttr("kind", "new-xml")
        val updated = snapshot(doc)

        assertEquals(listOf("a", "b"), typeArrayToArraySnapshot(array, initial))
        assertEquals("old-list", array.getAttr("role", initial))
        assertEquals(mapOf("role" to "old-list"), array.getAttrs(initial))
        assertEquals(
            YTextDelta()
                .insert("hi", mapOf("bold" to true))
                .insertEmbed(mapOf("image" to "one")),
            typeTextToDeltaSnapshot(text, initial),
        )
        assertEquals("hi", typeTextToStringSnapshot(text, initial))
        assertEquals(listOf("h", "i", mapOf("image" to "one")), typeTextToArraySnapshot(text, initial))
        assertEquals("en", text.getAttribute("lang", initial))
        assertTrue(text.hasAttribute("lang", initial))
        assertEquals(
            listOf(mapOf(
                "nodeName" to "p",
                "attributes" to emptyMap<String, Any?>(),
                "children" to listOf("old"),
            )),
            typeXmlFragmentToJsonSnapshot(xml, initial),
        )
        assertEquals("<xml kind=\"old-xml\"><p>old</p></xml>", typeXmlFragmentToStringSnapshot(xml, initial))
        assertEquals("<p>old</p>", typeXmlFragmentToArraySnapshot(xml, initial).joinToString(separator = ""))
        assertEquals("<p>old</p>", typeXmlFragmentToDeltaSnapshot(xml, initial).single().insert!!.joinToString(separator = ""))
        assertEquals(mapOf("kind" to "old-xml"), xml.getAttrs(initial))

        assertEquals(listOf("b", "c"), typeArrayToArraySnapshot(array, updated))
        assertEquals("new-list", array.getAttr("role", updated))
        assertEquals(YTextDelta().insert("h").insertEmbed(mapOf("image" to "one")), typeTextToDeltaSnapshot(text, updated))
        assertEquals("h", typeTextToStringSnapshot(text, updated))
        assertEquals(listOf("h", mapOf("image" to "one")), typeTextToArraySnapshot(text, updated))
        assertFalse(text.hasAttr("lang", updated))
        assertEquals(listOf("new"), typeXmlFragmentToJsonSnapshot(xml, updated))
        assertEquals("<xml kind=\"new-xml\">new</xml>", typeXmlFragmentToStringSnapshot(xml, updated))
        assertEquals("new", typeXmlFragmentToArraySnapshot(xml, updated).joinToString(separator = ""))
        assertEquals("new", typeXmlFragmentToDeltaSnapshot(xml, updated).single().insert!!.joinToString(separator = ""))
        assertEquals("new-xml", xml.getAttr("kind", updated))
    }

    @Test
    fun xmlSnapshotArraysAndDeltasReturnDefensiveNodes() {
        val doc = YDoc(clientId = 1)
        val xml = doc.getXmlFragment("xml")
        xml.push(listOf(YXmlElement("p").also { it.push(listOf(YXmlText("old"))) }))
        val snap = snapshot(doc)

        val arrayNode = typeXmlFragmentToArraySnapshot(xml, snap).single() as YXmlElement
        val deltaNode = typeXmlFragmentToDeltaSnapshot(xml, snap).single().insert!!.single() as YXmlElement
        arrayNode.setAttr("changed", true)
        deltaNode.setAttr("changed", true)

        assertEquals("<p>old</p>", typeXmlFragmentToStringSnapshot(xml, snap))
        assertEquals("<xml><p>old</p></xml>", typeXmlFragmentToStringSnapshot(xml, snap, forceTag = true))
        assertEquals("<p>old</p>", xml.toString())
    }

    @Test
    fun xmlSnapshotsReadHistoricalLiveXmlElementAndTextChildren() {
        val doc = YDoc(clientId = 1, gc = false)
        val xml = doc.getXmlFragment("xml")
        val paragraph = doc.createXmlElement("p")
        val text = doc.createText()
        text.insert(0, "old")
        text.format(0, 3, mapOf("bold" to true))
        paragraph.setAttr("class", "initial")
        paragraph.push(text)
        xml.push(paragraph)
        val initial = snapshot(doc)

        paragraph.setAttr("class", "updated")
        text.delete(0, text.length)
        text.insert(0, "new")
        paragraph.push(doc.createXmlElement("br"))
        val updated = snapshot(doc)

        assertEquals("<p class=\"initial\">old</p>", typeXmlFragmentToStringSnapshot(xml, initial))
        assertEquals("<p class=\"updated\">new<br></br></p>", typeXmlFragmentToStringSnapshot(xml, updated))
        assertEquals(
            listOf(mapOf(
                "nodeName" to "p",
                "attributes" to mapOf("class" to "initial"),
                "children" to listOf("old"),
            )),
            typeXmlFragmentToJsonSnapshot(xml, initial),
        )
        assertEquals("<p class=\"updated\">new<br></br></p>", xml.toString())
    }

    @Test
    fun xmlSnapshotsReadHistoricalLiveXmlTextChildren() {
        val doc = YDoc(clientId = 1, gc = false)
        val xml = doc.getXmlFragment("xml")
        val text = doc.createXmlText()
        text.insert(0, "old", mapOf("bold" to true))
        xml.push(text)
        val initial = snapshot(doc)

        text.delete(0, text.length)
        text.insert(0, "new", mapOf("italic" to true))
        val updated = snapshot(doc)

        assertEquals(listOf("old"), typeXmlFragmentToJsonSnapshot(xml, initial))
        assertSame(text, typeXmlFragmentToArraySnapshot(xml, initial).single())
        assertSame(text, typeXmlFragmentToDeltaSnapshot(xml, initial).single().insert!!.single())
        assertEquals("<bold>old</bold>", typeXmlFragmentToStringSnapshot(xml, initial))

        assertEquals(listOf("new"), typeXmlFragmentToJsonSnapshot(xml, updated))
        assertSame(text, typeXmlFragmentToArraySnapshot(xml, updated).single())
        assertSame(text, typeXmlFragmentToDeltaSnapshot(xml, updated).single().insert!!.single())
        assertEquals("<italic>new</italic>", typeXmlFragmentToStringSnapshot(xml, updated))
        assertEquals(YTextDelta().insert("new", mapOf("italic" to true)), text.toDelta())
    }

    @Test
    fun xmlSnapshotSerializerPreservesLiveXmlTextFormattingAndEmbeds() {
        val doc = YDoc(clientId = 1, gc = false)
        val xml = doc.getXmlFragment("xml")
        val text = doc.createXmlText()
        text.insert(0, "A", mapOf("em" to emptyMap<String, Any?>()))
        text.insertEmbed(1, mapOf("image" to "old"), mapOf("strong" to emptyMap<String, Any?>()))
        xml.push(text)
        val initial = snapshot(doc)

        text.delete(0, text.length)
        text.insert(0, "new")

        val expected = "<em>A</em><strong>[object Object]</strong>"
        val snapshotValue = typeXmlFragmentToArraySnapshot(xml, initial).single()
        val deltaValue = typeXmlFragmentToDeltaSnapshot(xml, initial).single().insert!!.single()
        assertSame(text, snapshotValue)
        assertSame(text, deltaValue)
        assertEquals(expected, typeXmlFragmentToStringSnapshot(xml, initial))
        assertEquals("A", typeXmlFragmentToJsonSnapshot(xml, initial).single())
        assertEquals("new", xml.toString())

        val historicalXml = createDocFromSnapshot(doc, initial).getXmlFragment("xml")
        val historicalText = historicalXml.getType(0) as YXmlTextType
        assertEquals(expected, historicalXml.toString())
        val target = YDoc(clientId = 2)
        val targetXml = target.getXmlFragment("xml")
        targetXml.push(historicalText.clone(target))
        assertEquals(expected, targetXml.toString())
        assertEquals(
            YTextDelta()
                .insert("A", mapOf("em" to emptyMap<String, Any?>()))
                .insertEmbed(mapOf("image" to "old"), mapOf("strong" to emptyMap<String, Any?>())),
            (targetXml.getType(0) as YXmlTextType).toDelta(),
        )
    }

    @Test
    fun xmlSnapshotSerializerPreservesRichXmlTextNestedInAnElement() {
        val doc = YDoc(clientId = 1, gc = false)
        val xml = doc.getXmlFragment("xml")
        val paragraph = doc.createXmlElement("p")
        val text = doc.createXmlText()
        text.insert(0, "A", mapOf("em" to emptyMap<String, Any?>()))
        text.insertEmbed(1, listOf("x", null, 2), mapOf("strong" to emptyMap<String, Any?>()))
        paragraph.push(text)
        xml.push(paragraph)
        val initial = snapshot(doc)

        text.delete(0, text.length)
        text.insert(0, "new")

        val expected = "<p><em>A</em><strong>x,,2</strong></p>"
        assertSame(paragraph, typeXmlFragmentToArraySnapshot(xml, initial).single())
        assertSame(paragraph, typeXmlFragmentToDeltaSnapshot(xml, initial).single().insert!!.single())
        assertEquals(expected, typeXmlFragmentToStringSnapshot(xml, initial))

        val historicalXml = createDocFromSnapshot(doc, initial).getXmlFragment("xml")
        val historicalParagraph = historicalXml.getType(0) as YXmlElementType
        assertEquals(expected, historicalXml.toString())
        val target = YDoc(clientId = 2)
        val targetXml = target.getXmlFragment("xml")
        targetXml.push(historicalParagraph.clone(target))
        assertEquals(expected, targetXml.toString())
        val targetParagraph = targetXml.getType(0) as YXmlElementType
        assertTrue(targetParagraph.getType(0) is YXmlTextType)
    }

    @Test
    fun xmlSnapshotArraysAndDeltasPreserveGenericSharedTypeIdentity() {
        val doc = YDoc(clientId = 1, gc = false)
        val xml = doc.getXmlFragment("xml")
        val array = YArray(listOf("old"))
        val map = YMap(mapOf("version" to 1))
        xml.push(array, map)
        val before = snapshot(doc)

        array.push("new")
        map.set("version", 2)

        val snapshotValues = typeXmlFragmentToArraySnapshot(xml, before)
        val deltaValues = typeXmlFragmentToDeltaSnapshot(xml, before).flatMap { op -> op.insert.orEmpty() }

        assertSame(array, snapshotValues[0])
        assertSame(map, snapshotValues[1])
        assertSame(array, deltaValues[0])
        assertSame(map, deltaValues[1])
    }

    @Test
    fun xmlSnapshotDeltaPreservesFormattedStaticTextLikeUpstream() {
        val doc = YDoc(clientId = 1, gc = false)
        val xml = doc.getXmlFragment("xml")
        val emphasized = mapOf<String, Any?>("em" to emptyMap<String, Any?>())
        val emphasizedStrong = mapOf<String, Any?>(
            "em" to emptyMap<String, Any?>(),
            "strong" to emptyMap<String, Any?>(),
        )
        val initialDelta = listOf(
            YArrayDeltaOp(insert = listOf("A"), attributes = emphasizedStrong),
            YArrayDeltaOp(insert = listOf("B"), attributes = emphasized),
            YArrayDeltaOp(insert = listOf("C"), attributes = emphasizedStrong),
        )
        xml.applyDelta(initialDelta)
        val initial = snapshot(doc)

        xml.clear()
        xml.applyDelta(listOf(YArrayDeltaOp(insert = listOf("Z"))))

        assertEquals(initialDelta, typeXmlFragmentToDeltaSnapshot(xml, initial))
        assertEquals(listOf("A", "B", "C"), typeXmlFragmentToJsonSnapshot(xml, initial))
        assertEquals(
            "<em><strong>A</strong></em><em>B</em><em><strong>C</strong></em>",
            typeXmlFragmentToStringSnapshot(xml, initial),
        )
        assertEquals(listOf(YArrayDeltaOp(insert = listOf("Z"))), xml.toDelta())
    }

    private fun syncDocs(vararg docs: YDoc) {
        val updates = docs.map { doc -> doc.encodeStateAsUpdate() }
        docs.forEach { target ->
            updates.forEach { update -> target.applyUpdate(update) }
        }
    }
}
