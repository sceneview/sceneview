package io.github.sceneview.demo.ai

import com.google.mlkit.genai.common.GenAiException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the Point & Ask failure classification (#3343) — the layer that
 * replaced "every failure is one generic string" with a named cause per ML Kit error code.
 */
class AskFailureTest {

    @Test
    fun `unsupported device codes are terminal and named`() {
        listOf(8 /* NOT_AVAILABLE */, 16 /* NOT_SUPPORTED */, -101 /* AICORE_INCOMPATIBLE */)
            .forEach { code ->
                val failure = AskFailure.ofErrorCode(code)
                assertEquals("code $code", AskFailure.ModelUnavailable, failure)
                assertTrue("code $code must retire the retry CTA", failure.isTerminal)
            }
    }

    @Test
    fun `transient codes stay retryable`() {
        listOf(
            9 to AskFailure.Busy,
            27 to AskFailure.Busy,
            30 to AskFailure.Busy,
            12 to AskFailure.RequestTooLarge,
            -100 to AskFailure.RequestTooLarge,
            -102 to AskFailure.InvalidImage,
            501 to AskFailure.NotEnoughDiskSpace,
        ).forEach { (code, expected) ->
            val failure = AskFailure.ofErrorCode(code)
            assertEquals("code $code", expected, failure)
            assertFalse("code $code must stay retryable", failure.isTerminal)
        }
    }

    @Test
    fun `a stale AICore needs a system update and is terminal`() {
        val failure = AskFailure.ofErrorCode(604)
        assertEquals(AskFailure.NeedsSystemUpdate, failure)
        assertTrue(failure.isTerminal)
    }

    @Test
    fun `an unrecognised code degrades to Unknown rather than guessing`() {
        assertEquals(AskFailure.Unknown, AskFailure.ofErrorCode(9_999))
        assertFalse(AskFailure.Unknown.isTerminal)
    }

    @Test
    fun `a GenAiException is classified by its error code`() {
        assertEquals(
            AskFailure.ModelUnavailable,
            AskFailure.of(GenAiException(IllegalStateException("boom"), 8)),
        )
    }

    @Test
    fun `a wrapped GenAiException is found through the cause chain`() {
        val wrapped = IllegalStateException(
            "adapter",
            GenAiException(RuntimeException("aicore"), -102),
        )
        assertEquals(AskFailure.InvalidImage, AskFailure.of(wrapped))
    }

    @Test
    fun `a non-ML-Kit throwable is Unknown, and null is too`() {
        assertEquals(AskFailure.Unknown, AskFailure.of(IllegalStateException("nope")))
        assertEquals(AskFailure.Unknown, AskFailure.of(null))
    }

    @Test
    fun `a self-referential cause chain terminates`() {
        // A throwable whose cause is itself would spin a naive walk forever.
        val looping = object : RuntimeException("loop") {
            override val cause: Throwable get() = this
        }
        assertEquals(AskFailure.Unknown, AskFailure.of(looping))
    }

    @Test
    fun `every failure carries a distinct message`() {
        val messages = AskFailure.entries.map { it.messageRes }
        assertEquals(
            "each failure must read differently on screen",
            messages.size,
            messages.distinct().size,
        )
    }
}
