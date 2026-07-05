package dev.yks

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IdApiTest {
    @Test
    fun createIDAndCompareIDsMirrorYjsNaming() {
        val id: ID = createID(1, 2)
        val v1Encoder = UpdateEncoderV1()
        val v2Encoder = UpdateEncoderV2()

        assertEquals(Id(1, 2), id)
        assertTrue(compareIDs(id, Id(1, 2)))
        assertTrue(compareIDs(null, null))
        assertFalse(compareIDs(id, Id(1, 3)))
        assertFalse(compareIDs(id, null))
        assertEquals(v1Encoder, writeID(v1Encoder, id))
        assertEquals(v2Encoder, writeID(v2Encoder, Id(3, 4)))
        assertEquals(id, readID(UpdateDecoderV1(v1Encoder.toByteArray())))
        assertEquals(Id(3, 4), readID(UpdateDecoderV2(v2Encoder.toByteArray())))
        assertFailsWith<IllegalArgumentException> { createID(-1, 0) }
    }
}
