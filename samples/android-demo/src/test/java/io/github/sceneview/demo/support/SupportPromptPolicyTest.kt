package io.github.sceneview.demo.support

import io.github.sceneview.demo.support.SupportPrompt.MIN_INTERVAL_MILLIS
import io.github.sceneview.demo.support.SupportPrompt.MIN_SESSIONS
import io.github.sceneview.demo.support.SupportPrompt.QUIET_AFTER_SUPPORT_MILLIS
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The moderated Support interstitial's rule: when it may ask, and every case it must not. */
class SupportPromptPolicyTest {

    private val now = 1_800_000_000_000L

    private fun offer(
        sessions: Int = MIN_SESSIONS,
        valueMoment: Boolean = true,
        optedOut: Boolean = false,
        lastShownAt: Long = 0L,
        lastSupportedAt: Long = 0L,
    ) = SupportPrompt.shouldOffer(sessions, valueMoment, optedOut, lastShownAt, lastSupportedAt, now)

    @Test fun `asks from the third session after a moment of value`() = assertTrue(offer())

    @Test fun `never on the first launches`() {
        assertFalse(offer(sessions = 1))
        assertFalse(offer(sessions = MIN_SESSIONS - 1))
    }

    @Test fun `never without a moment of value`() = assertFalse(offer(valueMoment = false))

    @Test fun `don't ask again is final`() = assertFalse(offer(sessions = 50, optedOut = true))

    @Test fun `at most once every fourteen days`() {
        assertFalse(offer(lastShownAt = now - MIN_INTERVAL_MILLIS + 1))
        assertTrue(offer(lastShownAt = now - MIN_INTERVAL_MILLIS))
    }

    @Test fun `quiet for a while after a contribution`() {
        assertFalse(offer(lastSupportedAt = now - QUIET_AFTER_SUPPORT_MILLIS + 1))
        assertTrue(offer(lastSupportedAt = now - QUIET_AFTER_SUPPORT_MILLIS))
    }
}
