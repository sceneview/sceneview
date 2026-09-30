package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoyageStateTest {

    private val flight = CosmosFlight(CosmosRig())
    private var nanos = 1_000_000_000L

    /** Runs [seconds] of 100 ms frames (the longest step the state counts) through [state]. */
    private fun VoyageState.run(seconds: Float, counted: Boolean = true, each: VoyageState.() -> Unit = {}) {
        var left = seconds
        while (left > 1e-4f) {
            nanos += FRAME_NANOS
            step(nanos, counted)
            each()
            left -= FRAME_SECONDS
        }
    }

    private fun playing() = VoyageState(playing = true).apply { step(nanos) }

    @Test
    fun `a touch takes the camera and the voyage resumes after the calm`() {
        val state = playing()
        state.takeOver(flight)
        assertFalse(state.playing)
        state.run(VoyageState.IDLE_RESUME_SECONDS - 0.5f)
        assertFalse(state.dueToResume())
        // Another touch starts the calm over.
        state.takeOver(flight)
        state.run(VoyageState.IDLE_RESUME_SECONDS - 0.5f)
        assertFalse(state.dueToResume())
        state.run(1f)
        assertTrue(state.dueToResume())
    }

    @Test
    fun `resuming jumps away first, then arrives playing with the drag reset`() {
        val state = playing()
        state.takeOver(flight)
        state.drag(CosmosScene.Galaxy, 30f, 10f)
        state.run(VoyageState.IDLE_RESUME_SECONDS + 0.5f)
        state.resumeNow()
        assertEquals(0f, state.leaving, 0f)
        assertFalse(state.playing)
        assertFalse(state.dueToResume())
        // A second resume mid-jump does not restart it.
        state.leaving = 0.5f
        state.resumeNow()
        assertEquals(0.5f, state.leaving, 0f)
        state.arrive()
        assertTrue(state.playing)
        assertTrue(state.arrivedByWarp)
        assertTrue(state.leaving < 0f)
        assertEquals(0f, state.yaw, 0f)
        assertEquals(0f, state.pitch, 0f)
    }

    @Test
    fun `a touch mid-jump cancels it and waits for calm again`() {
        val state = playing()
        state.takeOver(flight)
        state.resumeNow()
        state.takeOver(flight)
        assertTrue(state.leaving < 0f)
        assertFalse(state.playing)
        state.run(VoyageState.IDLE_RESUME_SECONDS + 0.5f)
        assertTrue(state.dueToResume())
    }

    @Test
    fun `a dock tab shows the scene still, then the voyage takes off on its own`() {
        val state = playing()
        state.drag(CosmosScene.Galaxy, 30f, 10f)
        state.pick(flight)
        assertFalse(state.playing)
        assertEquals(0f, state.yaw, 0f)
        state.run(VoyageState.AUTO_START_SECONDS - 0.5f)
        assertFalse(state.dueToResume())
        state.run(1f)
        assertTrue(state.dueToResume())
    }

    @Test
    fun `a take-off after a dock tab plays the scene picked, after a touch the next one`() {
        val state = playing()
        state.pick(flight)
        assertTrue(state.inPlace)
        state.resumeNow()
        state.arrive()
        assertFalse(state.inPlace)
        state.takeOver(flight)
        assertFalse(state.inPlace)
        // A touch after a tab: the user has looked at the scene, the voyage moves on.
        state.pick(flight)
        state.takeOver(flight)
        assertFalse(state.inPlace)
    }

    @Test
    fun `a touch after a dock tab waits for the longer calm`() {
        val state = playing()
        state.pick(flight)
        state.run(1f)
        state.takeOver(flight)
        state.run(VoyageState.IDLE_RESUME_SECONDS - 0.5f)
        assertFalse(state.dueToResume())
        state.run(1f)
        assertTrue(state.dueToResume())
    }

    @Test
    fun `Stop keeps the voyage stopped until it is started again`() {
        val state = playing()
        state.stop(flight)
        assertFalse(state.playing)
        state.run(VoyageState.IDLE_RESUME_SECONDS * 3)
        assertFalse(state.dueToResume())
        state.resumeNow()
        assertEquals(0f, state.leaving, 0f)
    }

    @Test
    fun `a drag does not follow the user into another scene`() {
        val state = playing()
        state.takeOver(flight)
        state.drag(CosmosScene.Galaxy, 40f, 500f)
        assertEquals(40f, state.yaw, 0f)
        assertEquals(VoyageState.MAX_PITCH_DEGREES, state.pitch, 0f)
        // The dock switch to the flow field clears it; the flow field ignores new drags.
        state.pick(flight)
        state.drag(CosmosScene.Flow, 25f, 5f)
        assertEquals(0f, state.yaw, 0f)
        assertEquals(0f, state.pitch, 0f)
    }

    @Test
    fun `turning Animate off and on again waits for calm, then resumes`() {
        val state = playing()
        // Animate off: held every frame, never due however long.
        state.run(VoyageState.IDLE_RESUME_SECONDS * 2) { hold(flight) }
        assertFalse(state.playing)
        assertFalse(state.dueToResume())
        // Animate back: the calm counts from now.
        state.run(VoyageState.AUTO_START_SECONDS - 0.5f)
        assertFalse(state.dueToResume())
        state.run(1f)
        assertTrue(state.dueToResume())
    }

    @Test
    fun `holding a stopped voyage keeps it stopped`() {
        val state = playing()
        state.stop(flight)
        state.run(2f) { hold(flight) }
        state.run(VoyageState.IDLE_RESUME_SECONDS * 2)
        assertFalse(state.dueToResume())
    }

    @Test
    fun `frame pacing counts only the frames after the loading cover`() {
        val state = playing()
        state.run(1f, counted = false)
        assertTrue(state.shotPacing(CosmosScene.Galaxy).contains(": 0 frames"))
        state.run(1f)
        assertTrue(state.shotPacing(CosmosScene.Galaxy).contains(": 10 frames, 10.0 fps"))
    }

    @Test
    fun `a cut-short fade comes back up and the streaks go out`() {
        val state = playing()
        state.show(fade = 0f, streaks = 1f)
        state.run(3f) { settle(FRAME_SECONDS) }
        assertEquals(1f, state.fade, 1e-3f)
        assertEquals(0f, state.streaks, 0f)
    }

    private companion object {
        const val FRAME_SECONDS = 0.1f
        const val FRAME_NANOS = 100_000_000L
    }
}
