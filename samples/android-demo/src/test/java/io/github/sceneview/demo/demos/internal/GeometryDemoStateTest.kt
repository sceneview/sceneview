package io.github.sceneview.demo.demos.internal

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [GeometryDemoState]: what each control changes, and — as important on a screen
 * with a Reset and a Recenter — what it leaves alone.
 */
class GeometryDemoStateTest {

    private fun afterUserSession() = GeometryDemoState().apply {
        toggle(GeometryShape.Cone)
        toggle(GeometryShape.Plane)
        metallic = 1f
        roughness = 0f
        spinning = false
    }

    @Test
    fun `opens with every shape shown, turning, on the default material`() {
        val state = GeometryDemoState()
        assertEquals(GeometryShape.entries.toSet(), state.visibleShapes)
        assertTrue(state.spinning)
        assertEquals(GeometryDemoState.DEFAULT_METALLIC, state.metallic, 0f)
        assertEquals(GeometryDemoState.DEFAULT_ROUGHNESS, state.roughness, 0f)
        assertEquals(0, state.cameraHomeGeneration)
    }

    @Test
    fun `toggle hides a shown shape and shows it again`() {
        val state = GeometryDemoState()
        state.toggle(GeometryShape.Torus)
        assertFalse(state.isVisible(GeometryShape.Torus))
        assertEquals(GeometryShape.entries.size - 1, state.visibleShapes.size)
        state.toggle(GeometryShape.Torus)
        assertTrue(state.isVisible(GeometryShape.Torus))
        assertEquals(GeometryShape.entries.toSet(), state.visibleShapes)
    }

    @Test
    fun `toggling a shape never asks the camera to move`() {
        val state = GeometryDemoState()
        GeometryShape.entries.forEach(state::toggle)
        assertTrue(state.visibleShapes.isEmpty())
        assertEquals(0, state.cameraHomeGeneration)
    }

    @Test
    fun `recenter sends the camera home and leaves the scene as the user set it`() {
        val state = afterUserSession()
        state.recenter()
        assertEquals(1, state.cameraHomeGeneration)
        assertFalse(state.isVisible(GeometryShape.Cone))
        assertFalse(state.isVisible(GeometryShape.Plane))
        assertEquals(1f, state.metallic, 0f)
        assertEquals(0f, state.roughness, 0f)
        assertFalse(state.spinning)
    }

    @Test
    fun `reset restores everything the screen opens with, camera included`() {
        val state = afterUserSession()
        state.reset()
        val fresh = GeometryDemoState()
        assertEquals(fresh.visibleShapes, state.visibleShapes)
        assertEquals(fresh.metallic, state.metallic, 0f)
        assertEquals(fresh.roughness, state.roughness, 0f)
        assertEquals(fresh.spinning, state.spinning)
        assertEquals(1, state.cameraHomeGeneration)
    }

    @Test
    fun `a rotation keeps what the user changed and takes the camera from home`() {
        val before = afterUserSession().apply { recenter() }
        val saved = with(GeometryDemoState.Saver) { SaverScope { true }.save(before) }
        val restored = GeometryDemoState.Saver.restore(checkNotNull(saved))
        checkNotNull(restored)
        assertEquals(before.visibleShapes, restored.visibleShapes)
        assertEquals(before.metallic, restored.metallic, 0f)
        assertEquals(before.roughness, restored.roughness, 0f)
        assertEquals(before.spinning, restored.spinning)
        assertEquals(0, restored.cameraHomeGeneration)
    }

    @Test
    fun `each recenter and reset is a new camera generation`() {
        val state = GeometryDemoState()
        state.recenter()
        state.reset()
        state.recenter()
        assertEquals(3, state.cameraHomeGeneration)
    }
}
