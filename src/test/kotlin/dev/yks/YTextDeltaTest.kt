package dev.yks

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class YTextDeltaTest {
    @Test
    fun insertWithAttributesRendersAsDelta() {
        val doc = YDoc(clientId = 1)
        val text = doc.getText("body")

        text.insert(0, "abc", mapOf("bold" to true))

        assertEquals("abc", text.toString())
        assertEquals(YTextDelta().insert("abc", mapOf("bold" to true)), text.toDelta())
    }

    @Test
    fun formatAppliesAndClearsAttributesAcrossRanges() {
        val doc = YDoc(clientId = 1)
        val text = doc.getText("body")
        text.insert(0, "abcde", mapOf("bold" to false))

        text.format(1, 3, mapOf("bold" to true))
        assertEquals(
            YTextDelta()
                .insert("a", mapOf("bold" to false))
                .insert("bcd", mapOf("bold" to true))
                .insert("e", mapOf("bold" to false)),
            text.toDelta(),
        )

        text.format(2, 1, mapOf("bold" to null))
        assertEquals(
            YTextDelta()
                .insert("a", mapOf("bold" to false))
                .insert("b", mapOf("bold" to true))
                .insert("c")
                .insert("d", mapOf("bold" to true))
                .insert("e", mapOf("bold" to false)),
            text.toDelta(),
        )
    }

    @Test
    fun formatMatchesYjsRetainEdges() {
        val doc = YDoc(clientId = 1)
        val text = doc.getText("body")
        val origins = mutableListOf<Any?>()
        doc.observeAfterTransactions { transaction -> origins.add(transaction.origin) }
        text.insert(0, "ab")
        origins.clear()

        text.format(-1, 1, mapOf("bold" to true))

        assertEquals(
            YTextDelta()
                .insert("a", mapOf("bold" to true))
                .insert("b"),
            text.toDelta(),
        )

        text.format(0, -1, mapOf("italic" to true))
        text.format(1, 1, emptyMap())

        assertEquals(
            YTextDelta()
                .insert("a", mapOf("bold" to true))
                .insert("b"),
            text.toDelta(),
        )
        assertEquals(listOf<Any?>(null), origins)

        text.formatText(1, 1, mapOf("italic" to true), origin = "format-text")

        assertEquals(
            YTextDelta()
                .insert("a", mapOf("bold" to true))
                .insert("b", mapOf("italic" to true)),
            text.toDelta(),
        )
        assertEquals(listOf<Any?>(null, "format-text"), origins)

        text.formatText(0, 0, mapOf("underline" to true), origin = "noop")

        assertEquals(listOf<Any?>(null, "format-text"), origins)
        assertFailsWith<IllegalArgumentException> { text.format(3, 0, mapOf("code" to true)) }
        assertFailsWith<IllegalArgumentException> { text.format(0, 99, mapOf("code" to true)) }
        assertFailsWith<IllegalArgumentException> { text.formatText(3, 1, emptyMap(), origin = "overflow") }
    }

    @Test
    fun upstreamTextAliasesPreserveOriginAndTextSemantics() {
        val doc = YDoc(clientId = 1)
        val text = doc.getText("body")
        val eventOrigins = mutableListOf<Any?>()
        val transactionOrigins = mutableListOf<Any?>()
        text.observe { event -> eventOrigins.add(event.origin) }
        doc.observeAfterTransactions { event -> transactionOrigins.add(event.origin) }

        text.insertText(0, "abcd", mapOf("bold" to true), origin = "insert-text")
        text.formatText(1, 2, mapOf("bold" to null, "italic" to true), origin = "format-text")
        text.insertEmbed(3, mapOf("image" to "hero"), mapOf("alt" to "Hero"), origin = "insert-embed")
        text.deleteText(0, 1, origin = "delete-text")

        assertEquals("bc\uFFFCd", text.toString())
        assertEquals(
            YTextDelta()
                .insert("bc", mapOf("italic" to true))
                .insertEmbed(mapOf("image" to "hero"), mapOf("alt" to "Hero"))
                .insert("d", mapOf("bold" to true)),
            text.toDelta(),
        )
        assertEquals(
            listOf<Any?>("insert-text", "format-text", "insert-embed", "delete-text"),
            transactionOrigins,
        )
        assertEquals(transactionOrigins, eventOrigins)
    }

    @Test
    fun applyDeltaSupportsRetainDeleteInsertAndFormat() {
        val doc = YDoc(clientId = 1)
        val text = doc.getText("body")
        text.insert(0, "hello world")

        text.applyDelta(
            YTextDelta()
                .retain(5, mapOf("bold" to true))
                .delete(6)
                .insert("!"),
        )

        assertEquals("hello!", text.toString())
        assertEquals(
            YTextDelta()
                .insert("hello", mapOf("bold" to true))
                .insert("!"),
            text.toDelta(),
        )
    }

    @Test
    fun applyDeltaRetainNullAttributeRemovesFormatting() {
        val doc = YDoc(clientId = 1)
        val text = doc.getText("body")
        text.insert(0, "ab", mapOf("bold" to true))

        text.applyDelta(
            YTextDelta()
                .retain(1)
                .retain(1, mapOf("bold" to null)),
        )

        assertEquals(
            YTextDelta()
                .insert("a", mapOf("bold" to true))
                .insert("b"),
            text.toDelta(),
        )
    }

    @Test
    fun basicInsertDeleteEventDeltaMirrorsUpstreamNetChanges() {
        val doc = YDoc(clientId = 1)
        val text = doc.getText("text")
        var eventDelta = YTextDelta()
        text.observe { event -> eventDelta = event.textDelta }

        text.delete(0, 0)
        text.insert(0, "abc")

        assertEquals("abc", text.toString())
        assertEquals(YTextDelta().insert("abc"), eventDelta)

        text.delete(0, 1)

        assertEquals("bc", text.toString())
        assertEquals(YTextDelta().delete(1), eventDelta)

        text.delete(1, 1)

        assertEquals("b", text.toString())
        assertEquals(YTextDelta().retain(1).delete(1), eventDelta)

        doc.transact {
            text.insert(0, "1")
            text.delete(0, 1)
        }

        assertEquals("b", text.toString())
        assertEquals(YTextDelta(), eventDelta)
    }

    @Test
    fun falsyFormatsAndEventsMirrorUpstream() {
        val doc = YDoc(clientId = 1)
        val text = doc.getText("text")
        var eventDelta = text.toDelta()
        text.observe { event -> eventDelta = event.textDelta }

        text.insert(0, "abcde", mapOf("falsy" to false))

        assertEquals(YTextDelta().insert("abcde", mapOf("falsy" to false)), text.toDelta())
        assertEquals(YTextDelta().insert("abcde", mapOf("falsy" to false)), eventDelta)

        text.format(1, 3, mapOf("falsy" to true))

        assertEquals(
            YTextDelta()
                .insert("a", mapOf("falsy" to false))
                .insert("bcd", mapOf("falsy" to true))
                .insert("e", mapOf("falsy" to false)),
            text.toDelta(),
        )
        assertEquals(YTextDelta().retain(1).retain(3, mapOf("falsy" to true)), eventDelta)

        text.format(2, 1, mapOf("falsy" to false))

        assertEquals(
            YTextDelta()
                .insert("a", mapOf("falsy" to false))
                .insert("b", mapOf("falsy" to true))
                .insert("c", mapOf("falsy" to false))
                .insert("d", mapOf("falsy" to true))
                .insert("e", mapOf("falsy" to false)),
            text.toDelta(),
        )
        assertEquals(YTextDelta().retain(2).retain(1, mapOf("falsy" to false)), eventDelta)
    }

    @Test
    fun multilineFormattingAndDeletePreserveLineAttributesLikeUpstream() {
        val doc = YDoc(clientId = 1)
        val text = doc.getText("test")
        text.insert(0, "Test\nMulti-line\nFormatting")

        text.applyDelta(
            YTextDelta()
                .retain(4, mapOf("bold" to true))
                .retain(1)
                .retain(10, mapOf("bold" to true))
                .retain(1)
                .retain(10, mapOf("bold" to true)),
        )

        assertEquals(
            YTextDelta()
                .insert("Test", mapOf("bold" to true))
                .insert("\n")
                .insert("Multi-line", mapOf("bold" to true))
                .insert("\n")
                .insert("Formatting", mapOf("bold" to true)),
            text.toDelta(),
        )

        val lines = doc.getText("lines")
        lines.applyDelta(
            YTextDelta()
                .insert("Text")
                .insert("\n", mapOf("title" to true))
                .insert("\n"),
        )
        lines.applyDelta(
            YTextDelta()
                .retain(4)
                .delete(1)
                .retain(1, mapOf("title" to true)),
        )

        assertEquals(
            YTextDelta()
                .insert("Text")
                .insert("\n", mapOf("title" to true)),
            lines.toDelta(),
        )

        val emptyLines = doc.getText("empty-lines")
        emptyLines.applyDelta(
            YTextDelta()
                .insert("Text")
                .insert("\n", mapOf("title" to true))
                .insert("\nText")
                .insert("\n", mapOf("title" to true)),
        )

        assertEquals(
            YTextDelta()
                .insert("Text")
                .insert("\n", mapOf("title" to true))
                .insert("\nText")
                .insert("\n", mapOf("title" to true)),
            emptyLines.toDelta(),
        )
    }

    @Test
    fun applyDeltaUsesActiveRendererForTextIndexes() {
        val doc = YDoc(clientId = 1)
        val text = doc.getText("body")
        val renderer = object : BaseRenderer() {
            override val attributed: IdSet = createIdSet().also { it.add(1, 0, 1) }

            override fun contentLength(item: ItemStruct): Long =
                if (item.id == Id(1, 0)) 0 else super.contentLength(item)
        }
        text.insert(0, "abc")
        text.useRenderer(renderer)

        text.applyDelta(YTextDelta().retain(1).insert("!").delete(1))

        assertEquals("ab!", text.toString())
    }

    @Test
    fun applyDeltaRendererArgumentOverridesActiveRenderer() {
        val doc = YDoc(clientId = 1)
        val text = doc.getText("body")
        val renderer = object : BaseRenderer() {
            override val attributed: IdSet = createIdSet().also { it.add(1, 0, 1) }

            override fun contentLength(item: ItemStruct): Long =
                if (item.id == Id(1, 0)) 0 else super.contentLength(item)
        }
        text.insert(0, "ab")
        text.useRenderer(renderer)

        text.applyDelta(YTextDelta().retain(1).insert("!"), renderer = baseRenderer)

        assertEquals("a!b", text.toString())
    }

    @Test
    fun embedsAreLengthOneDeltaContent() {
        val doc = YDoc(clientId = 1)
        val text = doc.getText("body")
        val image = mapOf("image" to "https://example.test/a.png", "width" to 640L)
        val events = mutableListOf<YTextDelta>()
        text.observe { event -> events.add(event.textDelta) }

        text.insert(0, "a")
        events.clear()
        text.insertEmbed(1, image, mapOf("alt" to "hero"))
        text.insert(2, "b")

        assertEquals(3, text.length)
        assertEquals("a\uFFFCb", text.toString())
        assertEquals(YTextDelta().retain(1).insertEmbed(image, mapOf("alt" to "hero")), events.first())
        assertEquals(
            YTextDelta()
                .insert("a")
                .insertEmbed(image, mapOf("alt" to "hero"))
                .insert("b"),
            text.toDelta(),
        )

        text.format(1, 1, mapOf("alt" to "updated", "selected" to true))

        assertEquals(
            YTextDelta()
                .insert("a")
                .insertEmbed(image, mapOf("alt" to "updated", "selected" to true))
                .insert("b"),
            text.toDelta(),
        )
    }

    @Test
    fun itemTextListPositionAndInsertContentHelpersUseLocalTextStorage() {
        val doc = YDoc(clientId = 1)
        val text = doc.getText("body")
        val origins = mutableListOf<Any?>()
        doc.observeAfterTransactions { event -> origins.add(event.origin) }
        text.insert(0, "abc")
        origins.clear()

        val position = createItemTextListPosition(text, 1)
        assertEquals(Id(1, 0), position.left?.id)
        assertEquals(Id(1, 1), position.right?.id)

        position.forward()

        assertEquals(2, position.index)
        assertEquals(Id(1, 2), position.right?.id)

        val insertPosition = createItemTextListPosition(text, 1, linkedMapOf("bold" to true))
        val nested = doc.createMap()

        insertContent(text, insertPosition, ContentString("X"), mapOf("italic" to true), origin = "content")
        insertContent(
            text,
            insertPosition,
            ContentEmbed(mapOf("emoji" to "wave")),
            mapOf("embed" to true),
            origin = "embed",
        )
        insertContentHelper(text, insertPosition, listOf(nested, "!"), mapOf("tail" to true), origin = "helper")

        assertEquals(5, insertPosition.index)
        assertEquals("aX\uFFFC\uFFFC!bc", text.toString())
        assertSame(nested, text.get(3))
        assertEquals(
            YTextDelta()
                .insert("a")
                .insert("X", mapOf("italic" to true))
                .insertEmbed(mapOf("emoji" to "wave"), mapOf("embed" to true))
                .insertEmbed(nested, mapOf("tail" to true))
                .insert("!", mapOf("tail" to true))
                .insert("bc"),
            text.toDelta(),
        )
        assertEquals(listOf<Any?>("content", "embed", "helper"), origins)
        assertEquals(true, insertPosition.currentFormats["italic"])
        assertEquals(true, insertPosition.currentFormats["embed"])
        assertEquals(true, insertPosition.currentFormats["tail"])
    }

    @Test
    fun listInsertSupportsEmbedsWithAndWithoutAttributes() {
        val doc = YDoc(clientId = 1)
        val withAttrs = doc.getText("withAttrs")
        val withoutAttrs = doc.getText("withoutAttrs")
        val image = mapOf("image" to "imageSrc.png")

        withAttrs.insert(0, "ab", mapOf("bold" to true))
        withAttrs.insert(1, listOf(image), mapOf("width" to 100))

        assertEquals(
            YTextDelta()
                .insert("a", mapOf("bold" to true))
                .insertEmbed(image, mapOf("width" to 100L))
                .insert("b", mapOf("bold" to true)),
            withAttrs.toDelta(),
        )

        withoutAttrs.insert(0, "ab", mapOf("bold" to true))
        withoutAttrs.insert(1, listOf(image))

        assertEquals(
            YTextDelta()
                .insert("a", mapOf("bold" to true))
                .insertEmbed(image)
                .insert("b", mapOf("bold" to true)),
            withoutAttrs.toDelta(),
        )
    }

    @Test
    fun listInsertSupportsMixedTextAndEmbeds() {
        val doc = YDoc(clientId = 1)
        val text = doc.getText("body")
        val mention = mapOf("mention" to "Ada")

        text.insert(0, listOf("hi", mention, '!', " ok"), mapOf("source" to "mixed"))

        assertEquals("hi\uFFFC! ok", text.toString())
        assertEquals(
            YTextDelta()
                .insert("hi", mapOf("source" to "mixed"))
                .insertEmbed(mention, mapOf("source" to "mixed"))
                .insert("! ok", mapOf("source" to "mixed")),
            text.toDelta(),
        )
    }

    @Test
    fun textToArrayExpandsCharactersAndEmbeds() {
        val doc = YDoc(clientId = 1)
        val text = doc.getText("body")
        val embed = mapOf("image" to "imageSrc.png")

        text.insert(0, listOf("ab", embed, 'c'))

        assertEquals(listOf("a", "b", embed, "c"), text.toArray())
        assertEquals(listOf("0:a", "1:b", "2:{image=imageSrc.png}", "3:c"), text.mapIndexedForTest())
    }

    @Test
    fun textPushUnshiftAndSliceMirrorGenericListHelpers() {
        val doc = YDoc(clientId = 1)
        val text = doc.getText("body")
        val embed = mapOf("image" to "one")

        text.push("bc", mapOf("tail" to true))
        text.unshift(listOf("a", embed), mapOf("head" to true))

        assertEquals(listOf("a", embed, "b", "c"), text.toArray())
        assertEquals(listOf(embed, "b"), text.slice(1, -1))
        assertEquals(listOf("b", "c"), text.slice(-2))
        assertEquals(
            YTextDelta()
                .insert("a", mapOf("head" to true))
                .insertEmbed(embed, mapOf("head" to true))
                .insert("bc", mapOf("tail" to true)),
            text.toDelta(),
        )
    }

    @Test
    fun textPushAndUnshiftConvergeThroughUpdates() {
        val left = YDoc(clientId = 1)
        val right = YDoc(clientId = 2)
        val text = left.getText("body")

        text.push("middle")
        text.unshift("start-")
        text.push(listOf("-", mapOf("kind" to "embed"), "!"))

        right.applyUpdate(left.encodeStateAsUpdate())

        assertEquals(listOf("s", "t", "a", "r", "t", "-", "m", "i", "d", "d", "l", "e", "-", mapOf("kind" to "embed"), "!"), right.getText("body").toArray())
        assertEquals("start-middle-\uFFFC!", right.getText("body").toString())
    }

    @Test
    fun splitSurrogateTextRoundTripsThroughUpdates() {
        val alien = "\uD83D\uDC7E"

        run {
            val left = YDoc(clientId = 1)
            val right = YDoc(clientId = 2)
            val text = left.getText("body")
            text.insert(0, alien)
            text.insert(1, "hi!")

            right.applyUpdate(left.encodeStateAsUpdate())

            assertEquals("\uD83Dhi!\uDC7E", text.toString())
            assertEquals(text.toString(), right.getText("body").toString())
        }

        run {
            val left = YDoc(clientId = 3)
            val right = YDoc(clientId = 4)
            val text = left.getText("body")
            text.insert(0, alien + alien)
            text.delete(1, 2)

            right.applyUpdate(left.encodeStateAsUpdate())

            assertEquals(alien, text.toString())
            assertEquals(text.toString(), right.getText("body").toString())
        }

        run {
            val left = YDoc(clientId = 5)
            val right = YDoc(clientId = 6)
            val text = left.getText("body")
            text.insert(0, alien + alien)
            text.format(1, 2, mapOf("bold" to true))

            right.applyUpdate(left.encodeStateAsUpdate())

            assertEquals(text.toString(), right.getText("body").toString())
            assertEquals(text.toDelta(), right.getText("body").toDelta())
        }
    }

    @Test
    fun textToArrayMaterializesSharedTypesInsideEmbeds() {
        val doc = YDoc(clientId = 1)
        val text = doc.getText("body")
        val nested = doc.createArray()
        nested.push("x")

        text.insert(0, listOf(mapOf("node" to nested)))

        val embed = text.toArray().single() as Map<*, *>
        assertEquals(nested, embed["node"])
    }

    @Test
    fun textGetReturnsCharactersAndEmbedsByIndex() {
        val doc = YDoc(clientId = 1)
        val text = doc.getText("body")
        val nested = doc.createMap()
        nested.setAttr("kind", "button")
        text.insert(0, listOf("a", nested, "b"))

        assertEquals("a", text.get(0))
        assertSame(nested, text.get(1))
        assertEquals("b", text.get(2))
    }

    @Test
    fun applyDeltaSupportsEmbedsAndUpdates() {
        val left = YDoc(clientId = 1)
        val right = YDoc(clientId = 2)
        val video = mapOf("video" to "clip", "duration" to 12L)

        left.getText("body").applyDelta(
            YTextDelta()
                .insert("before")
                .insertEmbed(video, mapOf("kind" to "media"))
                .insert("after"),
        )
        right.applyUpdate(left.encodeStateAsUpdate())

        assertEquals("before\uFFFCafter", right.getText("body").toString())
        assertEquals(left.getText("body").toDelta(), right.getText("body").toDelta())
    }

    @Test
    fun applyDeltaSupportsUpstreamStyleListInserts() {
        val doc = YDoc(clientId = 1)
        val text = doc.getText("body")
        val image = mapOf("image" to "imageSrc.png")

        text.applyDelta(
            YTextDelta()
                .insert("a", mapOf("bold" to true))
                .insert(listOf(image), mapOf("width" to 100))
                .insert("b", mapOf("bold" to true)),
        )

        assertEquals("a\uFFFCb", text.toString())
        assertEquals(
            YTextDelta()
                .insert("a", mapOf("bold" to true))
                .insertEmbed(image, mapOf("width" to 100L))
                .insert("b", mapOf("bold" to true)),
            text.toDelta(),
        )
    }

    @Test
    fun applyDeltaDirectListOpSupportsMixedTextAndEmbeds() {
        val doc = YDoc(clientId = 1)
        val text = doc.getText("body")
        val mention = mapOf("mention" to "Ada")

        text.applyDelta(YTextDelta(listOf(YTextDeltaOp(insert = listOf("hi", mention, '!', " ok")))))

        assertEquals("hi\uFFFC! ok", text.toString())
        assertEquals(listOf("h", "i", mention, "!", " ", "o", "k"), text.toArray())
    }

    @Test
    fun formattedTextConvergesThroughUpdates() {
        val left = YDoc(clientId = 1)
        val right = YDoc(clientId = 2)

        left.getText("body").insert(0, "abcd")
        right.applyUpdate(left.encodeStateAsUpdate())
        left.getText("body").format(1, 2, mapOf("italic" to true))
        right.applyUpdate(left.encodeStateAsUpdate(right.encodeStateVector()))

        assertEquals(left.getText("body").toString(), right.getText("body").toString())
        assertEquals(left.getText("body").toDelta(), right.getText("body").toDelta())
        assertEquals(
            YTextDelta()
                .insert("a")
                .insert("bc", mapOf("italic" to true))
                .insert("d"),
            right.getText("body").toDelta(),
        )
    }

    @Test
    fun concurrentFormattingConvergesAndEmitsRetainDelta() {
        val left = YDoc(clientId = 1)
        val right = YDoc(clientId = 2)
        val leftText = left.getText("body")
        val rightText = right.getText("body")

        leftText.insert(0, "abcde")
        syncDocs(left, right)

        leftText.format(0, 3, mapOf("bold" to true))
        rightText.format(2, 2, mapOf("bold" to true))

        val remoteDeltas = mutableListOf<YTextDelta>()
        rightText.observe { event ->
            if (event.textDelta.ops.isNotEmpty()) {
                remoteDeltas.add(event.textDelta)
            }
        }

        right.applyUpdate(left.encodeStateAsUpdate(right.encodeStateVector()))
        left.applyUpdate(right.encodeStateAsUpdate(left.encodeStateVector()))

        assertEquals(leftText.toDelta(), rightText.toDelta())
        assertEquals(
            YTextDelta()
                .insert("abc", mapOf("bold" to true))
                .insert("de"),
            rightText.toDelta(),
        )
        assertEquals(
            YTextDelta()
                .retain(2, mapOf("bold" to true))
                .retain(1)
                .retain(1, mapOf("bold" to null)),
            remoteDeltas.single(),
        )
    }

    @Test
    fun deletingTextFormattingSubrangeConvergesLikeUpstream() {
        val left = YDoc(clientId = 1)
        val right = YDoc(clientId = 2)
        val leftText = left.getText("")
        val rightText = right.getText("")

        leftText.insert(0, "Attack ships on fire off the shoulder of Orion.")
        right.applyUpdate(left.encodeStateAsUpdate())

        leftText.format(13, 7, mapOf("bold" to true))
        right.applyUpdate(left.encodeStateAsUpdate(right.encodeStateVector()))

        leftText.format(16, 4, mapOf("bold" to null))
        right.applyUpdate(left.encodeStateAsUpdate(right.encodeStateVector()))

        val expected = YTextDelta()
            .insert("Attack ships ")
            .insert("on ", mapOf("bold" to true))
            .insert("fire off the shoulder of Orion.")
        assertEquals(expected, leftText.toDelta())
        assertEquals(expected, rightText.toDelta())
    }

    @Test
    fun formattingDeltaDoesNotReportUnnecessaryAttributeChanges() {
        val left = YDoc(clientId = 1)
        val right = YDoc(clientId = 2)
        val leftText = left.getText("")
        val rightText = right.getText("")
        val deltas = mutableListOf<YTextDelta>()

        leftText.insert(0, "\n", mapOf("PARAGRAPH_STYLES" to "normal", "LIST_STYLES" to "bullet"))
        leftText.insert(1, "abc", mapOf("PARAGRAPH_STYLES" to "normal"))
        syncDocs(left, right)

        leftText.observe { event ->
            if (event.textDelta.ops.isNotEmpty()) {
                deltas.add(event.textDelta)
            }
        }
        rightText.observe { event ->
            if (event.textDelta.ops.isNotEmpty()) {
                deltas.add(event.textDelta)
            }
        }

        rightText.format(0, 1, mapOf("LIST_STYLES" to "number"))
        syncDocs(left, right)

        val expected = YTextDelta().retain(1, mapOf("LIST_STYLES" to "number"))
        assertEquals(listOf(expected, expected), deltas)
    }

    @Test
    fun inconsistentFormattingMergeMatchesUpstreamLocallyAndAfterSync() {
        fun initializeDoc(): YDoc {
            val doc = YDoc(clientId = 1, gc = false)
            val text = doc.getText("text")
            text.insert(0, " After", mapOf("type" to "text", "italic" to true))
            text.insert(0, "Test", mapOf("type" to "text"))
            text.insert(0, "Merge ", mapOf("type" to "text", "bold" to true))
            return doc
        }

        fun assertMerge(doc: YDoc) {
            val text = doc.getText("text")
            text.format(0, 6, mapOf("bold" to null))
            text.format(6, 4, mapOf("type" to "text"))

            assertEquals(
                YTextDelta()
                    .insert("Merge Test", mapOf("type" to "text"))
                    .insert(" After", mapOf("italic" to true, "type" to "text")),
                text.toDelta(),
            )
        }

        assertMerge(initializeDoc())

        val synced = YDoc(clientId = 2, gc = false)
        synced.applyUpdate(initializeDoc().encodeStateAsUpdate())

        assertMerge(synced)
    }

    @Test
    fun overlappingUrlFormattingConvergesLikeUpstreamFormattingBugRegression() {
        val left = YDoc(clientId = 1)
        val right = YDoc(clientId = 2)
        val text = left.getText("")

        text.insert(0, "\n\n\n")
        text.format(0, 3, mapOf("url" to "http://example.com"))
        text.format(1, 1, mapOf("url" to "http://docs.yjs.dev"))
        right.applyUpdate(left.encodeStateAsUpdate())

        val expected = YTextDelta()
            .insert("\n", mapOf("url" to "http://example.com"))
            .insert("\n", mapOf("url" to "http://docs.yjs.dev"))
            .insert("\n", mapOf("url" to "http://example.com"))

        assertEquals(expected, text.toDelta())
        assertEquals(expected, right.getText("").toDelta())
    }

    @Test
    fun cleanupYTextFormattingIsNoopForCanonicalTextStorage() {
        val doc = YDoc(clientId = 1)
        val text = doc.getText("body")
        text.insert(0, "abc", mapOf("bold" to true))
        text.format(1, 1, mapOf("bold" to null, "italic" to true))
        val before = text.toDelta()

        assertEquals(0, cleanupYTextFormatting(text))

        assertEquals(before, text.toDelta())
    }

    @Test
    fun repeatedNativeFormattingStaysCanonicalWithoutCleanup() {
        val doc = YDoc(clientId = 1)
        val text = doc.getText("body")
        val cleanupTransactions = mutableListOf<YTransactionEvent>()
        text.insert(0, "abc")
        text.format(0, 3, mapOf("bold" to true))
        text.format(0, 3, mapOf("bold" to true))
        val before = text.toDelta()
        doc.observeAfterTransactions { event -> cleanupTransactions.add(event) }

        assertEquals(2, text.liveFormatItemCountForTest())
        assertEquals(0, cleanupYTextFormatting(text))

        assertEquals(before, text.toDelta())
        assertEquals(YTextDelta().insert("abc", mapOf("bold" to true)), text.toDelta())
        assertEquals(2, text.liveFormatItemCountForTest())
        assertEquals(0, cleanupTransactions.size)
    }

    @Test
    fun overwrittenNativeFormattingStaysCanonicalWithoutCleanup() {
        val doc = YDoc(clientId = 1)
        val text = doc.getText("body")
        text.insert(0, "abc")
        text.format(0, 3, mapOf("bold" to true))
        text.format(0, 3, mapOf("bold" to false))
        val before = text.toDelta()

        assertEquals(2, text.liveFormatItemCountForTest())
        assertEquals(0, cleanupYTextFormatting(text))

        assertEquals(before, text.toDelta())
        assertEquals(YTextDelta().insert("abc", mapOf("bold" to false)), text.toDelta())
        assertEquals(2, text.liveFormatItemCountForTest())
    }

    @Test
    fun cleanupYTextFormattingRespectsSuggestionDocFlag() {
        val doc = YDoc(clientId = 1, isSuggestionDoc = true)
        val text = doc.getText("body")
        text.insert(0, "abc", mapOf("bold" to true))

        assertEquals(0, cleanupYTextFormatting(text))
        assertEquals(YTextDelta().insert("abc", mapOf("bold" to true)), text.toDelta())
    }

    @Test
    fun formattingGapCleanupHelpersAreNoopsForCanonicalTextStorage() {
        val doc = YDoc(clientId = 1)
        val text = doc.getText("body")
        val events = mutableListOf<YTransactionEvent>()
        doc.observeAfterTransactions { event -> events.add(event) }
        text.insert(0, "abc", mapOf("bold" to true))
        text.format(1, 1, mapOf("bold" to null, "italic" to true))
        val item = getTypeStructs(text)[1]
        val currFormats = linkedMapOf<String, Any?>("bold" to true)
        val before = text.toDelta()

        assertEquals(0, cleanupContextlessFormattingGap(doc, item))
        assertEquals(0, cleanupContextlessFormattingGap(events.last(), item))
        assertEquals(0, cleanupFormattingGap(doc, item, null, mapOf("bold" to true), currFormats))
        assertEquals(0, cleanupFormattingGap(events.last(), item, null, mapOf("bold" to true), currFormats))
        assertEquals(0, cleanupYTextAfterTransaction(events.last()))

        assertEquals(linkedMapOf<String, Any?>("bold" to true), currFormats)
        assertEquals(before, text.toDelta())
    }

    private fun YText.mapIndexedForTest(): List<String> {
        val values = mutableListOf<String>()
        forEachIndexed { index, value -> values.add("$index:$value") }
        return values
    }

    private fun YText.liveFormatItemCountForTest(): Int =
        getTypeChildren(this).count { child -> !child.deleted } - length

    private fun syncDocs(vararg docs: YDoc) {
        val updates = docs.map { doc -> doc.encodeStateAsUpdate() }
        docs.forEach { target ->
            updates.forEach { update -> target.applyUpdate(update) }
        }
    }
}
