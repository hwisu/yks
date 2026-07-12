package dev.yks

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EmptyInsertParityTest {
    @Test
    fun integratedEmptyArrayAndXmlInsertsEmitTransactionsAndValidateBounds() {
        val doc = YDoc(clientId = 1)
        val array = doc.getArray("array")
        val element = doc.getXmlElement("element", "section")
        val fragment = doc.getXmlFragment("fragment")
        val lifecycle = mutableListOf<String>()
        var updates = 0
        doc.observeBeforeTransactions { lifecycle.add("before") }
        doc.observeBeforeObserverCalls { lifecycle.add("observers") }
        doc.observeAfterTransactions { lifecycle.add("after") }
        doc.observeAfterTransactionCleanup { lifecycle.add("cleanup") }
        doc.observeUpdates { _, _ -> updates++ }

        array.insert(0, emptyList())
        element.insert(0, emptyList())
        element.insertTypes(0, emptyList())
        fragment.insert(0, emptyList())
        fragment.insertTypes(0, emptyList())

        assertEquals(
            List(5) { listOf("before", "observers", "after", "cleanup") }.flatten(),
            lifecycle,
        )
        assertEquals(0, updates)

        assertFailsWith<IllegalArgumentException> { array.insert(1, emptyList()) }
        assertFailsWith<IllegalArgumentException> { element.insert(1, emptyList()) }
        assertFailsWith<IllegalArgumentException> { element.insertTypes(1, emptyList()) }
        assertFailsWith<IllegalArgumentException> { fragment.insert(1, emptyList()) }
        assertFailsWith<IllegalArgumentException> { fragment.insertTypes(1, emptyList()) }
        assertEquals(
            List(10) { listOf("before", "observers", "after", "cleanup") }.flatten(),
            lifecycle,
        )
    }

    @Test
    fun xmlElementAttributesSupportSnapshotAlias() {
        val doc = YDoc(clientId = 2, gc = false)
        val element = doc.getXmlElement("element", "section")
        element.setAttr("state", "before")
        val before = snapshot(doc)
        element.setAttr("state", "after")

        assertEquals(mapOf("state" to "before"), element.getAttributes(before))
        assertEquals(mapOf("state" to "after"), element.getAttributes())
    }
}
