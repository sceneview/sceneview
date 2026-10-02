package io.github.sceneview.demo.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The notification pre-prompt's frequency rules: when it may show, and when never again. */
class PushPromptPolicyTest {

    private class MemoryStore : PushPromptStore {
        override var homeReturns = 0
        override var timesShown = 0
        override var snoozedUntil = 0L
        override var settled = false
    }

    private var now = 1_000_000L
    private val store = MemoryStore()
    private val policy = PushPromptPolicy(store) { now }

    @Test
    fun `never on first launch - waits for the second return to Home`() {
        assertFalse(policy.onReturnedHome(eligible = true))
        assertTrue(policy.onReturnedHome(eligible = true))
    }

    @Test
    fun `never when not eligible (API below 33, already granted, no push, setting off)`() {
        repeat(5) { assertFalse(policy.onReturnedHome(eligible = false)) }
    }

    @Test
    fun `not now hides it for seven days`() {
        repeat(2) { policy.onReturnedHome(eligible = true) }
        policy.onShown()
        policy.onLater()
        assertFalse(policy.onReturnedHome(eligible = true))
        now += PushPromptPolicy.SNOOZE_MILLIS - 1
        assertFalse(policy.onReturnedHome(eligible = true))
        now += 1
        assertTrue(policy.onReturnedHome(eligible = true))
    }

    @Test
    fun `not now starts the returns over - an expired snooze alone never shows it`() {
        // Returns piled up during the snooze (the sheet dismissed without onShown resetting
        // them, e.g. a swipe) must not carry over: after "Not now" the count restarts at zero.
        store.homeReturns = 5
        policy.onLater()
        assertEquals(0, store.homeReturns)
        now += PushPromptPolicy.SNOOZE_MILLIS
        assertFalse(policy.onReturnedHome(eligible = true))
        assertTrue(policy.onReturnedHome(eligible = true))
    }

    @Test
    fun `showing resets the return count`() {
        repeat(2) { policy.onReturnedHome(eligible = true) }
        policy.onShown()
        assertEquals(0, store.homeReturns)
        policy.onLater()
        now += PushPromptPolicy.SNOOZE_MILLIS
        assertFalse(policy.onReturnedHome(eligible = true))
    }

    @Test
    fun `shown at most three times, then never again`() {
        repeat(PushPromptPolicy.MAX_SHOWS) {
            assertFalse(policy.onReturnedHome(eligible = true))
            assertTrue(policy.onReturnedHome(eligible = true))
            policy.onShown()
            policy.onLater()
            now += PushPromptPolicy.SNOOZE_MILLIS
        }
        now += PushPromptPolicy.SNOOZE_MILLIS * 10
        repeat(10) { assertFalse(policy.onReturnedHome(eligible = true)) }
    }

    @Test
    fun `an answer at the system dialog settles it for good`() {
        repeat(2) { policy.onReturnedHome(eligible = true) }
        policy.onShown()
        policy.onAnswered()
        repeat(10) { assertFalse(policy.onReturnedHome(eligible = true)) }
    }

    @Test
    fun `never while the usage-statistics consent is unknown in the zone`() {
        val settled = TelemetryConsent.pushPromptAllowed(
            state = ConsentState.Unknown,
            inZone = true,
            answeredThisSession = false,
        )
        repeat(5) { assertFalse(policy.onReturnedHome(eligible = true, consentSettled = settled)) }
    }

    @Test
    fun `returns counted while the consent was owed show it in the next session`() {
        repeat(2) { assertFalse(policy.onReturnedHome(eligible = true, consentSettled = false)) }
        // Next launch, consent answered in an earlier session: the first return is enough.
        val settled = TelemetryConsent.pushPromptAllowed(
            state = ConsentState.Denied,
            inZone = true,
            answeredThisSession = false,
        )
        assertTrue(policy.onReturnedHome(eligible = true, consentSettled = settled))
    }

    @Test
    fun `never in the session that answered the consent`() {
        val settled = TelemetryConsent.pushPromptAllowed(
            state = ConsentState.Granted,
            inZone = true,
            answeredThisSession = true,
        )
        repeat(5) { assertFalse(policy.onReturnedHome(eligible = true, consentSettled = settled)) }
    }

    @Test
    fun `outside the zone an unanswered consent does not hold it back`() {
        val settled = TelemetryConsent.pushPromptAllowed(
            state = ConsentState.Unknown,
            inZone = false,
            answeredThisSession = false,
        )
        assertFalse(policy.onReturnedHome(eligible = true, consentSettled = settled))
        assertTrue(policy.onReturnedHome(eligible = true, consentSettled = settled))
    }
}
