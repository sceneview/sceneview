package io.github.sceneview.demo.demos

import io.github.sceneview.node.ContactShadowContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Reset demo" in the Contact Shadow Preview (#3728): the user orbits, turns Bounce motion off,
 * changes the scene, then resets.
 *
 * Before the fix the reset left the camera wherever it had been dragged — the orbit manipulator
 * was created inline with nothing a reset could write to — and silently turned Bounce motion back
 * on. The camera half is carried by [ContactShadowDemoState.cameraHomeGeneration], the key the
 * orbit manipulator is rebuilt on at its home pose; plain JVM, because the pose itself needs a
 * Filament engine and the generation is the whole contract between the reset and the camera.
 */
class ContactShadowDemoResetTest {

    private fun afterUserSession() = ContactShadowDemoState().apply {
        // The user orbited — the camera is somewhere other than home. Nothing in the state
        // tracks the orbit itself: a drag moves the manipulator, and only a new one goes home.
        motionEnabled = false
        shadowsEnabled = false
        intensityFactor = 0.25f
        wallContext = ContactShadowContext.TableTop
        bounceElapsedNanos = 1_234_567L
    }

    @Test
    fun reset_sends_the_camera_home() {
        val state = afterUserSession()
        val before = state.cameraHomeGeneration

        state.reset()

        assertEquals(before + 1, state.cameraHomeGeneration)
    }

    @Test
    fun every_reset_sends_the_camera_home_again() {
        val state = afterUserSession()
        state.reset()
        val afterFirst = state.cameraHomeGeneration

        state.reset()

        assertEquals(afterFirst + 1, state.cameraHomeGeneration)
    }

    @Test
    fun reset_leaves_bounce_motion_as_the_user_set_it() {
        val state = afterUserSession()

        state.reset()

        assertFalse(state.motionEnabled)
    }

    @Test
    fun reset_restores_the_scene_to_its_entry_state() {
        val state = afterUserSession()
        val fresh = ContactShadowDemoState()

        state.reset()

        assertTrue(state.shadowsEnabled)
        assertEquals(fresh.shadowsEnabled, state.shadowsEnabled)
        assertEquals(fresh.intensityFactor, state.intensityFactor, 0f)
        assertEquals(fresh.wallContext, state.wallContext)
        assertEquals(0L, state.bounceElapsedNanos)
    }
}
