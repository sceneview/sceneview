package io.github.sceneview.ar

import io.github.sceneview.math.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** The surface an AR drag coasts along, from the camera rays at release. */
class ARMoveCoastSpaceTest {

    private val eye = Position(0f, 1.5f, 0f)
    private val floor = Position(0f, 0f, 0f)
    private val up = Position(0f, 1f, 0f)

    @Test
    fun `a ray looking down meets the floor ahead`() {
        val hit = ARMoveCoastSpace.rayPlane(eye, Position(0f, -1f, -1f), floor, up)
        assertNotNull(hit)
        assertEquals(0f, hit!!.y, 1e-5f)
        assertEquals(-1.5f, hit.z, 1e-5f)
    }

    @Test
    fun `a ray parallel to the floor or looking away never meets it`() {
        assertNull(ARMoveCoastSpace.rayPlane(eye, Position(0f, 0f, -1f), floor, up))
        assertNull(ARMoveCoastSpace.rayPlane(eye, Position(0f, 1f, -1f), floor, up))
    }

    @Test
    fun `a wall works the same as a floor`() {
        val hit = ARMoveCoastSpace.rayPlane(eye, Position(0f, 0f, -1f), Position(0f, 0f, -2f), Position(0f, 0f, 1f))
        assertEquals(Position(0f, 1.5f, -2f), hit)
    }

    @Test
    fun `the surface maps one pixel to the floor travel seen at release`() {
        val origin = Position(0f, 0f, -1.5f)
        val surface = ARMoveCoastSpace.surfaceOf(
            origin,
            alongX = origin + Position(0.016f, 0f, 0f),
            alongY = origin + Position(0f, 0f, 0.024f),
        )
        assertNotNull(surface)
        assertEquals(0.002f, surface!!.perPixelX.x, 1e-6f)
        assertEquals(0.003f, surface.perPixelY.z, 1e-6f)
    }

    @Test
    fun `a floor seen edge-on stays in screen space`() {
        val origin = Position(0f, 0f, -10f)
        assertNull(
            ARMoveCoastSpace.surfaceOf(origin, origin + Position(0.01f, 0f, 0f), origin + Position(0f, 0f, -4f)),
        )
    }
}
