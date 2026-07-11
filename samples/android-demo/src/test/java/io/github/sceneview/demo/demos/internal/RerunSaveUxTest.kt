package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for [RerunSaveUx] — the headless-testable core of the Rerun
 * Debug demo's "Save & Share recording" gating (issue
 * [#2658](https://github.com/sceneview/sceneview/issues/2658)).
 *
 * Pins two behaviours a user hit on a real wireless-debugging device without a
 * sidecar, without needing an emulator:
 *   - the CTA is disabled until the bridge is connected, so it never leads
 *     straight to a failure dialog ([rerunSaveActionUx]);
 *   - a failure never surfaces the bridge's internal reason string
 *     ([rerunSaveFailureMessage]).
 */
class RerunSaveUxTest {

    // ── rerunSaveActionUx: connection gating ────────────────────────────────

    @Test
    fun `disconnected disables the button and states why inline`() {
        val ux = rerunSaveActionUx(sharing = false, isConnected = false)
        assertFalse("save must be disabled with no reachable sidecar", ux.enabled)
        assertTrue(
            "the disabled label must name the reason so the CTA isn't a mystery",
            ux.label.contains("sidecar", ignoreCase = true),
        )
    }

    @Test
    fun `connected and idle enables the normal save action`() {
        val ux = rerunSaveActionUx(sharing = false, isConnected = true)
        assertTrue("save must be enabled once the sidecar is connected", ux.enabled)
        assertEquals("Save & Share recording", ux.label)
    }

    @Test
    fun `in-flight save is disabled and beats the connected state`() {
        // `sharing` wins over `isConnected` — no double-tap while a save is in flight.
        val ux = rerunSaveActionUx(sharing = true, isConnected = true)
        assertFalse(ux.enabled)
        assertEquals("Saving on dev machine…", ux.label)
    }

    // ── rerunSaveFailureMessage: never leak internal jargon ─────────────────

    @Test
    fun `bridge not-connected reason is replaced with actionable setup copy`() {
        // The exact internal string RerunBridge returns when it isn't connected.
        val msg = rerunSaveFailureMessage("bridge not connected — call connect() first")
        assertFalse(
            "must not leak the internal 'call connect() first' jargon",
            msg.contains("connect()"),
        )
        assertTrue(
            "must point the user at the sidecar / adb reverse setup",
            msg.contains("adb reverse") && msg.contains("sidecar", ignoreCase = true),
        )
    }

    @Test
    fun `control write failed reason never reaches the UI`() {
        val msg = rerunSaveFailureMessage("control write failed: Broken pipe")
        assertFalse(msg.contains("control write failed"))
        assertFalse(msg.contains("Broken pipe"))
        assertTrue(msg.contains("adb reverse"))
    }

    @Test
    fun `null and blank reasons degrade to the setup explanation`() {
        for (reason in listOf(null, "", "   ")) {
            val msg = rerunSaveFailureMessage(reason)
            assertTrue("reason=$reason should still be actionable", msg.contains("adb reverse"))
        }
    }

    @Test
    fun `live-mode sidecar gets its own targeted message`() {
        val msg = rerunSaveFailureMessage("sidecar is in live mode; restart with --save")
        assertTrue("live-mode failure must mention --save", msg.contains("--save"))
        assertFalse("still no internal jargon", msg.contains("connect()"))
    }
}
