package dev.yks

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DecoderDepthSecurityTest {
    private var previousLimit: String? = null

    @BeforeTest
    fun configureDepthLimit() {
        previousLimit = System.getProperty("dev.yks.maxDecodedNestingDepth")
        System.setProperty("dev.yks.maxDecodedNestingDepth", "64")
    }

    @AfterTest
    fun restoreDepthLimit() {
        previousLimit?.let { System.setProperty("dev.yks.maxDecodedNestingDepth", it) }
            ?: System.clearProperty("dev.yks.maxDecodedNestingDepth")
    }

    @Test
    fun deeplyNestedLib0ValuesAreRejectedBeforeStackExhaustion() {
        val encoder = BinaryEncoder()
        repeat(10_000) {
            encoder.writeByte(117)
            encoder.writeVarUInt(1)
        }
        encoder.writeByte(126)
        val failure = assertFailsWith<IllegalStateException> {
            UpdateDecoderV1(encoder.toByteArray()).readAny()
        }
        assertTrue(failure.message.orEmpty().contains("nesting exceeds limit"))
    }

    @Test
    fun deeplyNestedJsonValuesAreRejectedBeforeStackExhaustion() {
        val encoder = BinaryEncoder().also {
            it.writeString("[".repeat(10_000) + "null" + "]".repeat(10_000))
        }
        val failure = assertFailsWith<IllegalStateException> {
            UpdateDecoderV1(encoder.toByteArray()).readJSON()
        }
        assertTrue(failure.message.orEmpty().contains("nesting exceeds limit"))
    }

    @Test
    fun ordinaryNestedValuesStillRoundTrip() {
        val value = mapOf("answer" to listOf(mapOf("content" to listOf("text", null, 42L))))
        val encoder = UpdateEncoderV1()
        encoder.writeAny(value)
        assertEquals(value, UpdateDecoderV1(encoder.toByteArray()).readAny())
    }
}
