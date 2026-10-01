package io.github.sceneview.demo.telemetry

import org.junit.Assert.assertEquals
import org.junit.Test

/** `sample_close.duration_s` counts the time a sample was visible, not the time it was open. */
class ActiveClockTest {

    private var now = 10_000L
    private val clock = ActiveClock { now }

    @Test
    fun `counts while running`() {
        now += 4_000
        assertEquals(4_000, clock.elapsedMillis())
    }

    @Test
    fun `background time (ON_STOP to ON_START) is not counted`() {
        now += 3_000
        clock.pause()
        now += 60_000
        clock.resume()
        now += 2_000
        assertEquals(5_000, clock.elapsedMillis())
    }

    @Test
    fun `replayed lifecycle events do not double count`() {
        clock.resume() // ON_START replayed when the observer registers
        now += 1_000
        clock.pause()
        clock.pause()
        now += 9_000
        assertEquals(1_000, clock.elapsedMillis())
        clock.resume()
        clock.resume()
        now += 1_000
        assertEquals(2_000, clock.elapsedMillis())
    }
}
