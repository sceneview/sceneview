package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the timeline of the Rerun demo's in-app 3D view (#3950): live by default, a scrub pauses
 * on the finger, play runs forward and catches up to live — and the chrome's wording.
 */
class ArDebugSessionTest {

    private fun tenSeconds() = ArDebugTrace().apply {
        for (i in 0..100) addPose(1_000_000_000L + i * 100_000_000L, DebugPose(i * 0.02f, 0f, 0f))
    }

    @Test
    fun `live follows the head of the session`() {
        val session = ArDebugSession(tenSeconds())

        assertTrue(session.live)
        assertEquals(10f, session.time, 1e-3f)
    }

    @Test
    fun `a scrub pauses on the finger, clamped to the session`() {
        val session = ArDebugSession(tenSeconds())
        session.scrubTo(4f)

        assertFalse(session.live)
        assertFalse(session.playing)
        assertEquals(4f, session.time, 1e-4f)

        session.scrubTo(99f)
        assertEquals(10f, session.time, 1e-4f)
        session.tick(1f)
        assertEquals(10f, session.time, 1e-4f) // paused: time stands
    }

    @Test
    fun `play runs forward, then goes live at the end`() {
        val session = ArDebugSession(tenSeconds())
        session.scrubTo(9f)
        session.togglePlay()
        assertTrue(session.playing)

        session.tick(0.2f)
        assertEquals(9.2f, session.time, 1e-4f)
        repeat(10) { session.tick(0.2f) }
        assertTrue(session.live)
    }

    @Test
    fun `play from the end restarts from the beginning, pause from live freezes the head`() {
        val session = ArDebugSession(tenSeconds())
        session.togglePlay() // live → paused on the head
        assertFalse(session.live)
        assertEquals(10f, session.time, 1e-4f)

        session.togglePlay() // at the end → replay from 0
        assertTrue(session.playing)
        assertEquals(0f, session.time, 1e-4f)

        session.togglePlay()
        assertFalse(session.playing)
    }

    @Test
    fun `a session played without the 3D view counts where its cursor stands`() {
        val session = ArDebugSession(tenSeconds())
        // Nothing has drawn it yet: the timeline has no length to scrub.
        assertEquals(0f, session.stats.duration, 0f)
        session.scrubTo(4f)
        session.count(session.trace.frameAt(session.time))

        assertEquals(10f, session.stats.duration, 1e-3f)
        assertEquals(4f, session.stats.time, 1e-3f)
        // 2 cm every tenth of a second: 80 cm walked in four seconds.
        assertEquals(0.8f, session.stats.pathMetres, 1e-3f)
        assertTrue(session.stats.tracking)
        assertEquals(null, session.stats.room)

        session.scrubTo(9f)
        session.count(session.trace.frameAt(session.time), points = 1234, floorY = 0f)
        assertEquals(9f, session.stats.time, 1e-3f)
        assertEquals(1234, session.stats.mapPoints)
    }

    @Test
    fun `a frame hitch does not jump the replay`() {
        val session = ArDebugSession(tenSeconds())
        session.scrubTo(1f)
        session.togglePlay()
        session.tick(3f)

        assertEquals(1.25f, session.time, 1e-4f)
    }

    @Test
    fun `layers toggle, the ground does not`() {
        val session = ArDebugSession()
        session.toggle(DebugGroup.Points)
        session.toggle(DebugGroup.Stage)

        assertFalse(session.isVisible(DebugGroup.Points))
        assertTrue(session.isVisible(DebugGroup.Stage))
        session.toggle(DebugGroup.Points)
        assertTrue(session.isVisible(DebugGroup.Points))
    }

    @Test
    fun `stats count what the frame shows`() {
        val trace = tenSeconds()
        trace.addPoints(10_000_000_000L, floatArrayOf(0f, 0f, 0f, 1f, 1f, 1f))
        val stats = ArDebugStats.of(trace.frameAt(trace.duration), trace.duration)

        assertEquals(2f, stats.pathMetres, 1e-3f)
        assertEquals(2, stats.mapPoints)
        assertTrue(stats.tracking)
        assertEquals(ArDebugStats.Empty.copy(duration = 0f), ArDebugStats.of(ArDebugTrace().frameAt(0f), 0f))
    }

    @Test
    fun `the chrome reads as plain figures`() {
        assertEquals("0:00", ArDebugFormat.clock(0f))
        assertEquals("1:12", ArDebugFormat.clock(72.4f))
        assertEquals("0:00", ArDebugFormat.clock(Float.NaN))

        assertEquals("0 m", ArDebugFormat.distance(0f))
        assertEquals("42 cm", ArDebugFormat.distance(0.42f))
        assertEquals("14.2 m", ArDebugFormat.distance(14.236f))
        assertEquals("1,234 m", ArDebugFormat.distance(1234.5f))

        assertEquals("3,812", ArDebugFormat.count(3812))
    }
}
