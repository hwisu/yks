package dev.yks

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PublicApiParityTest {
    @Test
    fun yjsClassAliasesResolveToLocalDocumentAndTypeObjects() {
        val doc = Doc(clientId = 1)
        val type: Type = doc.getText("body")
        val ytype: YType = type
        val transactions = mutableListOf<TransactionEvent>()

        doc.observeAfterTransactions { transaction -> transactions.add(transaction) }
        (type as YText).insert(0, "x")

        assertEquals("body", type.name)
        assertSame(type, ytype)
        assertEquals(doc, type.doc)
        assertEquals(listOf(doc), transactions.map { it.doc })

        val generatedClientId = generateNewClientId()
        assertTrue(generatedClientId > 0)
        assertTrue(generatedClientId <= 0xffff_ffffL)
        assertEquals(generatedClientId, Doc(clientId = generatedClientId).clientId)
    }

    @Test
    fun yjsTypeMarkersAndRefIdsAreExported() {
        val doc = Doc(clientId = 1)
        val array: Type = doc.getArray("items")
        val map: Type = doc.getMap("meta")
        val text: Type = doc.getText("body")
        val xml: Type = doc.getXmlFragment("xml")
        val xmlElement = YXmlElement("p")
        val xmlText = YXmlText("hello")

        assertEquals(0, YArrayRefID)
        assertEquals(1, YMapRefID)
        assertEquals(2, YTextRefID)
        assertEquals(3, YXmlElementRefID)
        assertEquals(4, YXmlFragmentRefID)
        assertEquals(5, YXmlHookRefID)
        assertEquals(6, YXmlTextRefID)
        assertEquals(YArrayRefID, array.typeRef)
        assertEquals(YMapRefID, map.legacyTypeRef)
        assertEquals(YTextRefID, typeRefId(text))
        assertEquals(YXmlFragmentRefID, xml.typeRef)
        assertEquals(YXmlElementRefID, xmlElement.typeRef)
        assertEquals(YXmlTextRefID, xmlText.legacyTypeRef)
        assertSame(`$ydoc`, doc.`$type`)
        assertTrue(doc.`$type`(doc))
        assertTrue(`$ytypeAny`(array))
        assertTrue(`$ytype`()(text))
        assertTrue(`$ydoc`(doc))
        assertFalse(`$ytypeAny`(xmlElement))
        assertFalse(`$ytype`()("not a type"))
        assertFalse(`$ydoc`(array))
    }

    @Test
    fun documentShareAndGenericGetExposeConcreteRootTypes() {
        val doc = YDoc(clientId = 1)
        val array = doc.getArray("items")
        val map = doc.getMap("meta")
        val text = doc.getText("body")
        val xml = doc.getXmlFragment("xml")
        val element = doc.getXmlElement("paragraph", "p")

        assertEquals(setOf("body", "items", "meta", "paragraph", "xml"), doc.rootNames())
        assertSame(array, doc.get("items"))
        assertSame(map, doc.get("meta"))
        assertSame(text, doc.get("body"))
        assertSame(xml, doc.get("xml"))
        assertSame(element, doc.get("paragraph"))
        assertNull(doc.getOrNull("missing"))
        val missing = doc.get("missing")
        assertTrue(missing is YUnopenedRoot)
        assertSame(missing, doc.get("missing"))
        assertSame(missing, doc.getOrNull("missing"))
        assertEquals(setOf("body", "items", "meta", "missing", "paragraph", "xml"), doc.rootNames())
        assertEquals(doc.rootNames(), doc.share.keys)
        assertTrue(doc.share["items"] is YArray)
        assertTrue(doc.share["meta"] is YMap)
        assertTrue(doc.share["body"] is YText)
        assertTrue(doc.share["xml"] is YXmlFragment)
        assertTrue(doc.share["paragraph"] is YXmlElementType)
    }

    @Test
    fun typedDocumentGetMaterializesRootsFromKindOrLegacyTypeRef() {
        val doc = YDoc(clientId = 1)

        val array = doc.get("items", RootKind.Array)
        val map = doc.get("meta", RootKind.Map)
        val text = doc.get("body", YTextRefID)
        val xml = doc.get("xml", YXmlFragmentRefID)
        val element = doc.get("element", YXmlElementRefID)

        assertTrue(array is YArray)
        assertTrue(map is YMap)
        assertTrue(text is YText)
        assertTrue(xml is YXmlFragment)
        assertTrue(element is YXmlElementType)
        assertEquals("UNDEFINED", element.nodeName)
        assertSame(array, doc.get("items", YArrayRefID))
        assertSame(map, doc.get("meta", YMapRefID))
        assertSame(text, doc.get("body", RootKind.Text))
        assertSame(xml, doc.get("xml", RootKind.XmlFragment))
        assertSame(element, doc.get("element", RootKind.XmlElement))
        assertEquals(RootKind.XmlElement, rootKindFromTypeRefId(YXmlElementRefID))
        assertEquals(RootKind.XmlHook, rootKindFromTypeRefId(YXmlHookRefID))
        assertEquals(RootKind.XmlText, rootKindFromTypeRefId(YXmlTextRefID))
        assertFailsWith<IllegalArgumentException> { doc.get("items", RootKind.Map) }
        assertFailsWith<IllegalStateException> { doc.get("hook", YXmlHookRefID) }
        assertFailsWith<IllegalStateException> { rootKindFromTypeRefId(99) }
    }

    @Test
    fun documentGetXmlElementCreatesRootElementLikeUpstream() {
        val source = YDoc(clientId = 1)
        val paragraph = source.getXmlElement("paragraph", "p")
        paragraph.setAttr("id", "intro")
        paragraph.push(YXmlText("hello"), YXmlElement("br"))

        assertEquals("p", paragraph.nodeName)
        assertSame(paragraph, source.get("paragraph", RootKind.XmlElement))
        assertEquals("<p id=\"intro\">hello<br></br></p>", paragraph.toString())
        assertTrue(source.share["paragraph"] is YXmlElementType)

        val target = createDocFromUpdate(source.encodeStateAsUpdateLossless())
        val synced = target.getXmlElement("paragraph", "p")

        assertEquals("<p id=\"intro\">hello<br></br></p>", synced.toString())
        assertEquals(
            mapOf(
                "nodeName" to "p",
                "attributes" to mapOf("id" to "intro"),
                "children" to listOf("hello", mapOf("nodeName" to "br", "attributes" to emptyMap<String, Any?>(), "children" to emptyList<Any?>())),
            ),
            synced.toJson(),
        )
    }

    @Test
    fun noArgDocGetMaterializesTheDefaultSharedRoot() {
        val source = YDoc(clientId = 1)
        val root = source.get()
        root.push("hello")
        root.setAttr("kind", "default")

        assertSame(root, source.get())
        assertSame(root, source.share[""])
        assertEquals(setOf(""), source.rootNames())
        assertEquals(mapOf("" to listOf("hello")), source.toJSON())

        val target = createDocFromUpdate(source.encodeStateAsUpdate())

        assertEquals(listOf("hello"), target.get().toArray())
        assertEquals("default", target.get().getAttr("kind"))
        assertTrue(target.share[""] is YArray)
    }

    @Test
    fun afterTransactionCanRenderJsonForLargeNestedDefaultRoot() {
        val doc = YDoc(clientId = 1)
        val root = doc.get()
        val renderedSizes = mutableListOf<Int>()

        doc.observeAfterTransactions { transaction ->
            if (transaction.origin == "test") {
                val rootJson = doc.toJSON()[""] as List<*>
                renderedSizes.add(rootJson.size)
            }
        }

        doc.transact(origin = "test") {
            root.insert(0, List(15_000) { doc.createXmlElement("a") })
        }

        assertEquals(listOf(15_000), renderedSizes)
        assertEquals(15_000, root.length)
    }

    @Test
    fun shareViewDoesNotMaterializeRemoteRootTypesFromUpdates() {
        val source = YDoc(clientId = 1)
        source.getArray("items").push("a")
        source.getMap("meta").set("title", "hello")
        source.getText("body").insert(0, "text")
        source.getXmlFragment("xml").push(YXmlText("x"))

        val target = YDoc(clientId = 2)
        val share: Map<String, AbstractYType> = target.share
        assertTrue(share.isEmpty())
        target.applyUpdate(source.encodeStateAsUpdateLossless())

        assertEquals(setOf("body", "items", "meta", "xml"), target.rootNames())
        assertEquals(target.rootNames(), share.keys)
        assertEquals(emptyMap(), target.toJSON())
        val unopenedItems = assertIs<YUnopenedRoot>(share["items"])
        assertIs<YUnopenedRoot>(share["meta"])
        assertIs<YUnopenedRoot>(share["body"])
        assertIs<YUnopenedRoot>(share["xml"])
        assertSame(unopenedItems, target.getOrNull("items"))
        assertSame(unopenedItems, target.get("items"))

        val items = target.getArray("items")
        target.getMap("meta")
        target.getText("body")
        target.getXmlFragment("xml")
        assertEquals(setOf("body", "items", "meta", "xml"), share.keys)
        assertSame(items, share["items"])
        assertEquals("text", (share["body"] as YText).toString())
        assertEquals("x", (share["xml"] as YXmlFragment).toString())
    }

    @Test
    fun topLevelTransactDelegatesToDocTransactionAndKeepsOrigin() {
        val doc = YDoc(clientId = 1)
        val updates = mutableListOf<Pair<ByteArray, Any?>>()
        val locals = mutableListOf<Boolean>()
        doc.observeUpdates { update, origin -> updates.add(update to origin) }
        doc.observeAfterTransactions { event -> locals.add(event.local) }

        val result = transact(doc, origin = "batch", local = false) {
            doc.getMap("meta").setAttr("title", "hello")
            doc.getMap("meta").setAttr("count", 1)
            doc.getMap("meta").size
        }

        assertEquals(2, result)
        assertEquals(1, updates.size)
        assertEquals("batch", updates.single().second)
        assertEquals(listOf(false), locals)
        assertEquals(
            mapOf("count" to 1L, "title" to "hello"),
            createDocFromUpdate(updates.single().first).getMap("meta").toMap(),
        )
    }

    @Test
    fun topLevelTransactCanPassActiveTransactionToCallback() {
        val doc = YDoc(clientId = 1)
        val text = doc.getText("body")
        lateinit var observed: YTransactionEvent
        doc.observeAfterTransactions { event -> observed = event }

        val result = transact(
            doc,
            { transaction ->
                transaction.meta["api"] = "callback"
                text.insert(0, "hi")
                assertEquals("api", transaction.origin)
                assertFalse(transaction.local)
                assertTrue(transaction.adds(1, 0))
                transaction.addedItemCount
            },
            origin = "api",
            local = false,
        )

        assertEquals(1, result)
        assertEquals("hi", text.toString())
        assertEquals("callback", observed.meta["api"])
        assertFalse(observed.local)
    }

    @Test
    fun topLevelDeleteTextDelegatesToTextDeletionAndKeepsOrigin() {
        val doc = YDoc(clientId = 1)
        val text = doc.getText("body")
        val origins = mutableListOf<Any?>()
        doc.observeAfterTransactions { event -> origins.add(event.origin) }
        text.insert(0, "abcd")

        deleteText(text, 1, 2, origin = "helper")

        assertEquals("ad", text.toString())
        assertEquals(listOf<Any?>(null, "helper"), origins)
    }

    @Test
    fun callTypeObserversDispatchesDirectAndDeepTypeEvents() {
        val doc = YDoc(clientId = 1)
        val root = doc.getArray("root")
        val nested = doc.createMap()
        val directEvents = mutableListOf<YEvent>()
        val deepEvents = mutableListOf<YEvent>()
        root.push(nested)
        nested.observe { event -> directEvents.add(event) }
        root.observeDeep { event -> deepEvents.add(event) }

        val mapDelta = YMapDelta().setAttr("title", "hello")
        val event = YEvent(
            target = nested,
            origin = "manual",
            update = ByteArray(0),
            keysChanged = setOf("title"),
            mapChanges = mapOf("title" to YMapChange(YMapChangeAction.Add, null, "hello")),
            mapDelta = mapDelta,
        )

        callTypeObservers(nested, event)

        val direct = directEvents.single()
        assertSame(nested, direct.target)
        assertSame(nested, direct.currentTarget)
        assertSame(nested, direct.changedTarget)
        assertEquals("manual", direct.origin)
        assertEquals(mapDelta, direct.delta)

        val deep = deepEvents.single()
        val nestedEvent = deep.deepEvents.single()
        assertSame(root, deep.target)
        assertSame(root, deep.currentTarget)
        assertSame(nested, deep.changedTarget)
        assertEquals(listOf(0), deep.path)
        assertSame(nested, nestedEvent.target)
        assertSame(root, nestedEvent.currentTarget)
        assertSame(nested, nestedEvent.changedTarget)
        assertEquals(listOf(0), nestedEvent.path)
        assertEquals(mapDelta, nestedEvent.delta)
    }

    @Test
    fun addChangedTypeToTransactionMarksExistingTypesAndSuppressesNewlyInsertedTypes() {
        val doc = YDoc(clientId = 1)
        val meta = doc.getMap("meta")
        val body = doc.getText("body")
        val root = doc.getArray("root")
        val nested = doc.createMap()
        val events = mutableListOf<YTransactionEvent>()
        doc.observeAfterTransactions { event -> events.add(event) }

        transact(doc, { transaction ->
            addChangedTypeToTransaction(transaction, meta, "title")
            assertEquals(setOf("meta"), transaction.changedParents)
            body.insert(0, "x")
        })

        assertEquals(setOf("meta", "body"), events.single().changedParents)

        events.clear()
        transact(doc, { transaction ->
            root.push(nested)
            addChangedTypeToTransaction(transaction, nested, "title")
        })

        assertEquals(setOf("root"), events.single().changedParents)
    }

    @Test
    fun topLevelPathAndRootKeyHelpersMirrorTypeMethods() {
        val doc = YDoc(clientId = 1)
        val root = doc.getArray("root")
        val nestedMap = doc.createMap()
        val nestedText = doc.createText()
        root.push(nestedMap)
        nestedMap.setAttr("body", nestedText)

        assertEquals("root", findRootTypeKey(root))
        assertEquals(listOf(0, "body"), getPathTo(root, nestedText))
        assertFailsWith<IllegalStateException> { findRootTypeKey(nestedMap) }
    }

    @Test
    fun findTypeInOtherDocResolvesRootAndNestedTypesByHistoryIdentity() {
        val source = YDoc(clientId = 1)
        val root = source.getArray("root")
        val nestedMap = source.createMap()
        val nestedText = source.createText()
        root.push(nestedMap)
        nestedMap.setAttr("body", nestedText)
        nestedText.insert(0, "hello")
        val target = cloneDoc(source)

        val targetRoot = findTypeInOtherDoc(root, target)
        val targetMap = findTypeInOtherDoc(nestedMap, target) as YMap
        val targetText = findTypeInOtherDoc(nestedText, target) as YText

        assertSame(target.getArray("root"), targetRoot)
        assertEquals(listOf("body"), targetMap.keys().toList())
        assertSame(targetMap.getAttr("body"), targetText)
        assertEquals("hello", targetText.toString())
        assertFailsWith<IllegalStateException> {
            findTypeInOtherDoc(nestedText, YDoc(clientId = 2))
        }
    }

    @Test
    fun getTypeChildrenExposesStructuralSequenceItemsIncludingDeleted() {
        val doc = YDoc(clientId = 1)
        val text = doc.getText("body")
        text.insert(0, "abc")
        text.delete(1)

        val children = getTypeChildren(text)

        assertEquals(listOf(Id(1, 0), Id(1, 1), Id(1, 2)), children.map { it.id })
        assertEquals(listOf(false, true, false), children.map { it.deleted })
        assertEquals(setOf(RootKind.Text), children.map { it.kind }.toSet())
        assertEquals("body", children.single { it.id == Id(1, 1) }.parent)
    }

    @Test
    fun logTypeSummarizesChildrenAndVisibleContent() {
        val doc = YDoc(clientId = 1)
        val text = doc.getText("body")
        text.insert(0, "ab", mapOf("bold" to true))
        text.insertEmbed(2, mapOf("src" to "image"), mapOf("kind" to "asset"))
        text.delete(1)

        val log = logType(text)

        assertEquals(
            """
            Children[1:0:Text(parent=body, parentSub=null, deleted=false, length=1), 1:1:Text(parent=body, parentSub=null, deleted=false, length=1), 1:2:Text(parent=body, parentSub=null, deleted=true, length=1), 1:3:Text(parent=body, parentSub=null, deleted=false, length=1), 1:4:Text(parent=body, parentSub=null, deleted=false, length=1), 1:5:Text(parent=body, parentSub=null, deleted=false, length=1), 1:6:Text(parent=body, parentSub=null, deleted=false, length=1)]
            ChildrenContent[NativeTextFormat(key="bold", value=true), Text(value="a", attrs={"bold"=true}), NativeTextFormat(key="bold", value=null), NativeTextFormat(key="kind", value="asset"), TextEmbed(value={"src"="image"}, attrs={"kind"="asset"}), NativeTextFormat(key="kind", value=null)]
            """.trimIndent(),
            log,
        )
    }

    @Test
    fun typeChildTypesAndIsParentOfUseVisibleNestedReferences() {
        val doc = YDoc(clientId = 1)
        val root = doc.getArray("root")
        val nestedMap = doc.createMap()
        val nestedText = doc.createText()
        val deletedMap = doc.createMap()
        root.push(nestedMap, deletedMap)
        nestedMap.setAttr("body", nestedText)
        root.delete(1)

        assertEquals(listOf(nestedMap), getTypeChildTypes(root))
        assertEquals(listOf(nestedText), getTypeChildTypes(nestedMap))
        assertEquals(emptyList(), getTypeChildTypes(nestedText))
        assertEquals(true, isParentOf(root, nestedMap))
        assertEquals(true, isParentOf(root, nestedText))
        assertEquals(false, isParentOf(nestedMap, root))
        assertEquals(false, isParentOf(root, deletedMap))
    }

    @Test
    fun computeModifiedFromItemsMarksDirectKeysAndBubblesNestedParents() {
        val doc = YDoc(clientId = 1)
        val arrayRoot = doc.getArray("arrayRoot")
        val arrayNested = doc.createMap()
        arrayRoot.push(arrayNested)
        val arrayEvents = mutableListOf<YTransactionEvent>()
        doc.observeAfterTransactions { event -> arrayEvents.add(event) }

        arrayNested.setAttr("title", "hello")

        val arrayModified = computeModifiedFromItems(doc, arrayEvents.single().insertSet)

        assertEquals(setOf("title"), arrayModified[arrayNested])
        assertEquals(linkedSetOf<String?>(null), arrayModified[arrayRoot])
        assertEquals(setOf(arrayNested, arrayRoot), arrayModified.keys)

        val mapRoot = doc.getMap("mapRoot")
        val mapNested = doc.createMap()
        mapRoot.setAttr("child", mapNested)
        val mapEvents = mutableListOf<YTransactionEvent>()
        doc.observeAfterTransactions { event -> mapEvents.add(event) }

        mapNested.setAttr("title", "hello")

        val mapModified = computeModifiedFromItems(doc, mapEvents.single().insertSet)

        assertEquals(setOf("title"), mapModified[mapNested])
        assertEquals(setOf("child"), mapModified[mapRoot])
        assertEquals(setOf(mapNested, mapRoot), mapModified.keys)
    }
}
