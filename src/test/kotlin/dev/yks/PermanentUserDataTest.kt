package dev.yks

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PermanentUserDataTest {
    @Test
    fun mapsMultipleClientIdsToUserDescriptions() {
        val doc = YDoc(clientId = 1)
        val permanentUserData = PermanentUserData(doc)

        permanentUserData.setUserMapping(doc, 1, "alice")
        permanentUserData.setUserMapping(doc, 2, "bob")

        assertEquals("alice", permanentUserData.getUserByClientId(1))
        assertEquals("bob", permanentUserData.getUserByClientId(2))
        assertNull(permanentUserData.getUserByClientId(3))
        assertEquals(mapOf(1L to "alice", 2L to "bob"), permanentUserData.clients)

        val users = doc.getMap("users")
        assertTrue((users.get("alice") as YMap).get("ids") is YArray)
        assertTrue((users.get("alice") as YMap).get("ds") is YArray)
        permanentUserData.close()
    }

    @Test
    fun attributesLocalDeletesAndHonorsTheTransactionFilter() {
        val doc = YDoc(clientId = 7, gc = false)
        val permanentUserData = PermanentUserData(doc)
        permanentUserData.setUserMapping(doc, 7, "alice")
        val text = doc.getText("body")
        text.insert(0, "abc")
        val deletedId = getTypeStructs(text)[1].id

        text.delete(1, 1)

        assertEquals("alice", permanentUserData.getUserByDeletedId(deletedId))
        assertTrue(permanentUserData.dss.getValue("alice").contains(deletedId))

        val filteredDoc = YDoc(clientId = 8, gc = false)
        val filtered = PermanentUserData(filteredDoc)
        filtered.setUserMapping(filteredDoc, 8, "ignored") { _, _ -> false }
        val filteredText = filteredDoc.getText("body")
        filteredText.insert(0, "x")
        val filteredId = getTypeStructs(filteredText).single().id
        filteredText.delete(0)

        assertNull(filtered.getUserByDeletedId(filteredId))
        permanentUserData.close()
        filtered.close()
    }

    @Test
    fun mappingsAndDeleteAttributionSurviveUpdateRoundtrip() {
        val source = YDoc(clientId = 11, gc = false)
        val sourceUserData = PermanentUserData(source)
        sourceUserData.setUserMapping(source, 11, "alice")
        sourceUserData.setUserMapping(source, 22, "bob")
        val text = source.getText("body")
        text.insert(0, "hello")
        val deletedId = getTypeStructs(text)[1].id
        text.delete(1, 1)

        val update = encodeStateAsUpdate(source)
        assertFalse(update.size >= 3 && update[0] == 'Y'.code.toByte() && update[1] == 'K'.code.toByte() && update[2] == 'S'.code.toByte())
        val target = YDoc(clientId = 33, gc = false)
        applyUpdate(target, update)
        val targetUserData = PermanentUserData(target)

        assertEquals("alice", targetUserData.getUserByClientId(11))
        assertEquals("bob", targetUserData.getUserByClientId(22))
        assertEquals("alice", targetUserData.getUserByDeletedId(deletedId))
        assertTrue(targetUserData.dss.getValue("alice").contains(deletedId))
        sourceUserData.close()
        targetUserData.close()
    }
}
