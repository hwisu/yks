package dev.yks

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StandardTransactionSafetyTest {
    @Test
    fun standardListenerAtomicallyRejectsStaticXmlWithoutAnyObserverEvent() {
        val doc = YDoc(clientId = 1)
        val fragment = doc.getXmlFragment("xml")
        val beforeStateVector = doc.encodeStateVector()
        val beforeUpdate = doc.encodeStateAsUpdate()
        var typeEvents = 0
        var standardEvents = 0
        var losslessEvents = 0
        fragment.observe { typeEvents++ }
        doc.observeUpdates { _, _ -> standardEvents++ }
        doc.observeUpdatesLossless { _, _ -> losslessEvents++ }

        assertFailsWith<UnsupportedYjsStandardUpdateException> {
            fragment.push(YXmlElement("p"))
        }

        assertContentEquals(beforeStateVector, doc.encodeStateVector())
        assertContentEquals(beforeUpdate, doc.encodeStateAsUpdate())
        assertEquals("", fragment.toString())
        assertEquals(0, fragment.length)
        assertEquals(0, typeEvents)
        assertEquals(0, standardEvents)
        assertEquals(0, losslessEvents)
        assertEquals(null, doc.store.pendingStructs)
        assertEquals(null, doc.store.pendingDs)
    }

    @Test
    fun requireStandardPolicyRollsBackNonstandardSubdocOptionsWithoutAListener() {
        val doc = standardDoc()
        val subdocs = doc.getArray("subdocs")
        val child = YDoc(guid = "private-child", collectionId = "private", shouldLoad = false)
        val beforeStateVector = doc.encodeStateVector()
        val beforeUpdate = doc.encodeStateAsUpdate()

        assertFailsWith<UnsupportedYjsStandardUpdateException> {
            subdocs.push(child)
        }

        assertTrue(subdocs.toArray().isEmpty())
        assertTrue(doc.getSubdocs().isEmpty())
        assertContentEquals(beforeStateVector, doc.encodeStateVector())
        assertContentEquals(beforeUpdate, doc.encodeStateAsUpdate())
        assertFalse(child.isDestroyed)
    }

    @Test
    fun mixedNestedTransactionRollsBackStandardAndPrivateMutationsTogether() {
        val doc = standardDoc()
        val text = doc.getText("body")
        val fragment = doc.getXmlFragment("xml")
        text.insert(0, "keep")
        val standardEvents = mutableListOf<ByteArray>()
        val losslessEvents = mutableListOf<ByteArray>()
        doc.onUpdate { update, _, _, _ -> standardEvents.add(update) }
        doc.onUpdateLossless { update, _, _, _ -> losslessEvents.add(update) }
        val beforeUpdate = doc.encodeStateAsUpdate()

        assertFailsWith<UnsupportedYjsStandardUpdateException> {
            doc.transact {
                text.delete(1, 2)
                text.insert(text.length, " must rollback")
                fragment.push(YXmlElement("p"))
            }
        }

        assertEquals("keep", text.toString())
        assertEquals("", fragment.toString())
        assertContentEquals(beforeUpdate, doc.encodeStateAsUpdate())
        assertTrue(standardEvents.isEmpty())
        assertTrue(losslessEvents.isEmpty())
    }

    @Test
    fun rejectedPreliminaryGraphRestoresItsDetachedKotlinState() {
        val doc = standardDoc()
        val root = doc.getArray("root")
        val nested = YXmlFragment().also { fragment ->
            fragment.push(YXmlElement("private"))
        }

        assertFailsWith<UnsupportedYjsStandardUpdateException> {
            root.push(nested)
        }

        assertTrue(root.toArray().isEmpty())
        assertTrue(nested.isPreliminary)
        assertEquals(1, nested.preliminaryList.size)

        val losslessDoc = YDoc(clientId = 2)
        losslessDoc.getArray("root").push(nested)
        assertEquals(1, nested.length)
        assertTrue(losslessDoc.encodeStateAsUpdateLossless().isNotEmpty())
    }

    private fun standardDoc(): YDoc = YDoc(
        YDocOptions(clientId = 1),
        YDocRuntimeOptions(standardUpdatePolicy = YStandardUpdatePolicy.REQUIRE_STANDARD),
    )
}
