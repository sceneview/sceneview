package io.github.sceneview.demo.common

import io.github.sceneview.ar.ArGuidanceCue
import io.github.sceneview.ar.ArGuidanceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pins the coaching of the demos that search for a surface themselves ([SurfaceScanCoach]). */
class SurfaceScanCoachTest {

    private fun SurfaceScanCoach.at(
        now: Long,
        searching: Boolean,
        cameraLost: Boolean = false,
        cameraReady: Boolean = true,
        arUnavailable: Boolean = false,
    ): ArGuidanceCue {
        update(searching, cameraLost, cameraReady, arUnavailable, now)
        return cue
    }

    @Test
    fun `searching shows the scan glyph`() {
        assertEquals(ArGuidanceCue.SCAN, SurfaceScanCoach().at(0, searching = true))
    }

    @Test
    fun `nothing before the first camera frame, nothing once AR is ruled out`() {
        val coach = SurfaceScanCoach()
        assertEquals(ArGuidanceCue.NONE, coach.at(0, searching = true, cameraReady = false))
        assertEquals(ArGuidanceCue.NONE, coach.at(10, searching = true, arUnavailable = true))
    }

    @Test
    fun `the first surface gets a short found beat, then nothing`() {
        val coach = SurfaceScanCoach()
        coach.at(0, searching = true)
        assertEquals(ArGuidanceCue.SURFACE_FOUND, coach.at(1_000, searching = false))
        assertEquals(ArGuidanceState.FOUND_HOLD_MS, coach.nextTransitionDelayMillis(1_000))
        assertEquals(
            ArGuidanceCue.NONE,
            coach.at(1_000 + ArGuidanceState.FOUND_HOLD_MS, searching = false),
        )
        assertNull(coach.nextTransitionDelayMillis(2_000))
    }

    @Test
    fun `a demo that starts with a surface already found says nothing`() {
        assertEquals(ArGuidanceCue.NONE, SurfaceScanCoach().at(0, searching = false))
    }

    @Test
    fun `losing the room after the surface was found asks to slow down`() {
        val coach = SurfaceScanCoach()
        coach.at(0, searching = true)
        coach.at(100, searching = false)
        assertEquals(ArGuidanceCue.TRACKING_LIMITED, coach.at(5_000, searching = false, cameraLost = true))
        assertEquals(ArGuidanceCue.NONE, coach.at(6_000, searching = false, cameraLost = false))
    }

    @Test
    fun `a camera hiccup while still searching keeps the scan glyph`() {
        // ARCore reports "not enough detail" during a normal start: that is the search itself,
        // not a pause — saying "Paused" before anything started would read as a fault.
        assertEquals(ArGuidanceCue.SCAN, SurfaceScanCoach().at(0, searching = true, cameraLost = true))
    }

    @Test
    fun `searching again after a find scans again`() {
        val coach = SurfaceScanCoach()
        coach.at(0, searching = true)
        coach.at(100, searching = false)
        assertEquals(ArGuidanceCue.SCAN, coach.at(300, searching = true))
    }
}
