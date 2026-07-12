package dev.yks

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PreliminaryTypeParityTest {
    @Test
    fun publicConstructorsBufferWritesWithoutCreatingStructs() {
        val array = YArray().also { it.push("a", "b") }
        val map = YMap().also { it.set("k", "v") }
        val text = YText("ab").also { it.delete(0, 1) }
        val fragment = YXmlFragment().also { it.push(YXmlText("x")) }
        val element = YXmlElementType("DIV").also {
            it.push(YXmlTextType().also { child -> child.insert(0, "x") })
            it.setAttr("class", "box")
        }
        val xmlText = YXmlTextType().also { it.insert(0, "x") }
        val hook = YXmlHook("widget").also { it.set("k", "v") }
        val types = listOf(array, map, text, fragment, element, xmlText, hook)

        types.forEach { type ->
            assertTrue(type.binding is YTypeBinding.Detached)
            assertTrue(type.doc.store.stateVector().isEmpty())
        }
        assertEquals(0, array.length)
        assertEquals(emptyList(), array.toArray())
        assertEquals(0, map.size)
        assertEquals(emptyMap(), map.toJSON())
        assertEquals(0, text.length)
        assertEquals("", text.toString())
        assertEquals(1, fragment.length)
        assertEquals(null, fragment.firstChild)
        assertEquals("", fragment.toString())
        assertEquals(1, element.length)
        assertEquals(null, element.firstChild)
        assertEquals("<div></div>", element.toString())
        assertEquals(0, xmlText.length)
        assertEquals("", xmlText.toString())
        assertEquals(0, hook.size)
        assertEquals(emptyMap(), hook.toJSON())
    }

    @Test
    fun docCreateReservesWithoutWritingAndAttachesTheSameInstance() {
        val doc = YDoc(clientId = 1, gc = false)
        val updates = mutableListOf<ByteArray>()
        doc.onUpdate { update, _, _, _ -> updates.add(update) }
        val child = doc.createMap()

        child.set("k", "v")
        assertTrue(child.binding is YTypeBinding.Reserved)
        assertTrue(doc.store.stateVector().isEmpty())
        assertTrue(updates.isEmpty())

        val root = doc.getArray("root")
        root.push(child)

        assertSame(child, root.get(0))
        assertTrue(child.binding is YTypeBinding.Nested)
        assertEquals(mapOf("k" to "v"), child.toMap())
        assertEquals(mapOf(1L to 2L), doc.store.stateVector())
        assertEquals(1, updates.size)
        assertStandardV1(updates.single())
    }

    @Test
    fun compositeAttachIsOwnerFirstSingleTransactionAndPreservesIdentity() {
        val doc = YDoc(clientId = 1, gc = false)
        val root = doc.getArray("root")
        val outer = YArray()
        val inner = YMap().also { it.set("answer", 42) }
        val element = YXmlElementType("P")
        val xmlText = YXmlTextType().also { it.insert(0, "abc") }
        element.push(xmlText)
        element.setAttr("class", "intro")
        val hook = YXmlHook("widget").also { it.set("enabled", true) }
        outer.push(listOf("before", inner, element, hook, "after"))
        var innerEvents = 0
        inner.observe { innerEvents++ }
        val updates = mutableListOf<ByteArray>()
        doc.onUpdate { update, _, _, _ -> updates.add(update) }

        root.push(outer)

        assertSame(outer, root.get(0))
        assertSame(inner, outer.get(1))
        assertSame(element, outer.get(2))
        assertSame(hook, outer.get(3))
        assertSame(xmlText, element.get(0))
        assertEquals(Id(1, 0), doc.typeRefItemId(outer))
        assertEquals(Id(1, 2), doc.typeRefItemId(inner))
        assertEquals(Id(1, 4), doc.typeRefItemId(element))
        assertEquals(Id(1, 5), doc.typeRefItemId(xmlText))
        assertEquals(Id(1, 10), doc.typeRefItemId(hook))
        assertEquals(mapOf(1L to 13L), doc.store.stateVector())
        assertEquals(0, innerEvents)
        assertEquals(1, updates.size)
        assertStandardV1(updates.single())

        inner.set("next", 43)
        assertEquals(1, innerEvents)
    }

    @Test
    fun sequenceTypesReplayFinalStateWhileTextReplaysOrderedHistory() {
        val arrayDoc = YDoc(clientId = 1, gc = false)
        val array = YArray().also {
            it.push("a", "b")
            it.delete(0)
        }
        arrayDoc.getArray("root").push(array)
        assertEquals(listOf("b"), array.toList())
        assertEquals(mapOf(1L to 2L), arrayDoc.store.stateVector())

        val mapDoc = YDoc(clientId = 1, gc = false)
        val map = YMap().also {
            it.set("k", "a")
            it.set("k", "b")
        }
        mapDoc.getArray("root").push(map)
        assertEquals(mapOf("k" to "b"), map.toMap())
        assertEquals(mapOf(1L to 2L), mapDoc.store.stateVector())

        val textDoc = YDoc(clientId = 1, gc = false)
        val text = YText("ab").also { it.delete(0, 1) }
        textDoc.getArray("root").push(text)
        assertEquals("b", text.toString())
        assertEquals(mapOf(1L to 3L), textDoc.store.stateVector())
        assertTrue(textDoc.deleteSet().contains(Id(1, 1)))
    }

    @Test
    fun failedPendingTextOperationDoesNotAbortOwnerIntegration() {
        val doc = YDoc(clientId = 1, gc = false)
        val root = doc.getArray("root")
        val text = YText().also { it.delete(99, 1) }

        root.push(text)

        assertSame(text, root.get(0))
        assertEquals("", text.toString())
        assertEquals(mapOf(1L to 1L), doc.store.stateVector())

        text.insert(0, "ok")
        assertEquals("ok", text.toString())
        assertEquals(mapOf(1L to 3L), doc.store.stateVector())
    }

    @Test
    fun mapCanOwnLiveXmlTypesWithoutLosingTheirWireNames() {
        val doc = YDoc(clientId = 1, gc = false)
        val root = doc.getMap("root")
        val element = YXmlElementType("SECTION").also {
            it.push(YXmlTextType().also { text -> text.insert(0, "body") })
        }
        val updates = mutableListOf<ByteArray>()
        doc.onUpdate { update, _, _, _ -> updates.add(update) }

        root.set("element", element)

        assertSame(element, root.get("element"))
        assertEquals("SECTION", (root.get("element") as YXmlElementType).nodeName)
        assertEquals("<section>body</section>", element.toString())
        assertEquals(1, updates.size)
        assertStandardV1(updates.single())
    }

    @Test
    fun textCanOwnAFormattedSharedTypeEmbed() {
        val doc = YDoc(clientId = 1, gc = false)
        val child = YArray().also { it.push("nested") }
        val text = doc.getText("body")
        val updates = mutableListOf<ByteArray>()
        doc.onUpdate { update, _, _, _ -> updates.add(update) }

        text.insertEmbed(0, child, mapOf("bold" to true))

        assertSame(child, text.get(0))
        assertEquals(
            YTextDelta().insertEmbed(child, mapOf("bold" to true)),
            text.toDelta(),
        )
        assertEquals(1, updates.size)
        assertStandardV1(updates.single())
    }

    @Test
    fun preliminaryLengthDependentWritesMatchUpstream() {
        val array = YArray().also {
            it.push("a")
            it.insert(it.length, listOf("b"))
        }
        val text = YText("a").also { it.insert(it.length, "b") }
        val fragment = YXmlFragment().also {
            it.push(YXmlText("a"))
            it.push(YXmlText("b"))
        }
        val doc = YDoc(clientId = 1, gc = false)
        val root = doc.getArray("root")

        root.push(listOf(array, text, fragment))

        assertEquals(listOf("b", "a"), array.toList())
        assertEquals("ba", text.toString())
        assertEquals("ab", fragment.toString())
    }

    @Test
    fun invalidIdentityGraphsAreRejectedBeforeStoreMutation() {
        val duplicateDoc = YDoc(clientId = 1, gc = false)
        val duplicateRoot = duplicateDoc.getArray("root")
        val duplicate = YArray()
        var duplicateUpdates = 0
        duplicateDoc.onUpdate { _, _, _, _ -> duplicateUpdates++ }
        assertFailsWith<IllegalArgumentException> { duplicateRoot.push(listOf(duplicate, duplicate)) }
        assertTrue(duplicateDoc.store.stateVector().isEmpty())
        assertTrue(duplicateRoot.toList().isEmpty())
        assertTrue(duplicate.binding is YTypeBinding.Detached)
        assertEquals(0, duplicateUpdates)

        val cycleDoc = YDoc(clientId = 2, gc = false)
        val cycle = YArray()
        assertFailsWith<IllegalArgumentException> { cycle.push(cycle) }
        assertTrue(cycleDoc.store.stateVector().isEmpty())
        assertTrue(cycle.binding is YTypeBinding.Detached)

        val first = YDoc(clientId = 3, gc = false)
        val reserved = first.createMap().also { it.set("k", "v") }
        val second = YDoc(clientId = 4, gc = false)
        assertFailsWith<IllegalArgumentException> { second.getArray("root").push(reserved) }
        assertTrue(first.store.stateVector().isEmpty())
        assertTrue(second.store.stateVector().isEmpty())
        assertTrue((reserved.binding as YTypeBinding.Reserved).doc === first)
    }

    @Test
    fun deletedOrAttachedTypesCannotBeReinsertedWithoutExplicitClone() {
        val doc = YDoc(clientId = 1, gc = false)
        val root = doc.getArray("root")
        val child = YMap().also { it.set("k", "v") }
        root.push(child)
        root.delete(0)
        val stateBefore = doc.store.stateVector()
        var updates = 0
        doc.onUpdate { _, _, _, _ -> updates++ }

        assertFailsWith<IllegalArgumentException> { root.push(child) }

        assertEquals(stateBefore, doc.store.stateVector())
        assertTrue(root.toList().isEmpty())
        assertEquals(0, updates)
        val clone = child.clone(doc)
        root.push(clone)
        assertSame(clone, root.get(0))
        assertFalse(clone === child)
    }

    private fun assertStandardV1(update: ByteArray) {
        assertTrue(
            update.size < 4 || update[0] != 'Y'.code.toByte() || update[1] != 'K'.code.toByte() ||
                update[2] != 'S'.code.toByte(),
        )
    }
}
