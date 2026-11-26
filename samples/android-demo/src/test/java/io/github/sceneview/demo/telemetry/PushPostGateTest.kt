package io.github.sceneview.demo.telemetry

import io.github.sceneview.demo.telemetry.PushPostGate.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A received push posts nothing past any opt-out, and broadcast pushes stop at 2 a month. */
class PushPostGateTest {

    @Test
    fun `posts only when every opt-out allows it`() {
        assertEquals(Verdict.Post, decide())
    }

    @Test
    fun `the whats_new channel turned off in system settings posts nothing`() {
        assertEquals(Verdict.ChannelOff, decide(channelBlocked = true))
    }

    @Test
    fun `the in-app switch, then the system switch or permission, win first`() {
        assertEquals(Verdict.SwitchOff, decide(switchOn = false, systemAllows = false, channelBlocked = true))
        assertEquals(Verdict.SystemOff, decide(systemAllows = false, channelBlocked = true))
    }

    @Test
    fun `over the cadence posts nothing`() {
        assertEquals(Verdict.OverCadence, decide(cadenceAllows = false))
    }

    private fun decide(
        switchOn: Boolean = true,
        systemAllows: Boolean = true,
        channelBlocked: Boolean = false,
        cadenceAllows: Boolean = true,
    ) = PushPostGate.decide(switchOn, systemAllows, channelBlocked, cadenceAllows)

    private class MemoryCadence : PushCadenceStore {
        override var pushPostedAt: List<Long> = emptyList()
    }

    private var now = 10_000_000_000L
    private val store = MemoryCadence()
    private val cadence = PushCadence(store) { now }

    @Test
    fun `at most two broadcast pushes in 30 days`() {
        assertTrue(cadence.allows())
        cadence.record()
        now += DAY
        assertTrue(cadence.allows())
        cadence.record()
        now += DAY
        assertFalse(cadence.allows())
        // 30 days after the first one, a slot opens again.
        now += PushCadence.WINDOW_MILLIS - 2 * DAY + 1
        assertTrue(cadence.allows())
        cadence.record()
        assertFalse(cadence.allows())
        assertEquals("only the posts inside the window are kept", 2, store.pushPostedAt.size)
    }

    @Test
    fun `only topic broadcasts count - qa topic and single-device tests do not`() {
        assertTrue(PushCadence.counts("/topics/new_samples"))
        assertTrue(PushCadence.counts("/topics/all"))
        assertFalse(PushCadence.counts("/topics/qa"))
        assertFalse(PushCadence.counts("123456789012"))
        assertFalse(PushCadence.counts(null))
    }

    private companion object {
        const val DAY = 24L * 60 * 60 * 1000
    }
}
