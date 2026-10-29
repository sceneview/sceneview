package io.github.sceneview.demo

import io.github.sceneview.math.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Unit coverage for [clampOrbitEyeHeight] — the showroom's lowest orbit, which keeps the eye
 * above the floor so the floor never reads as a line under the backdrop's seam.
 */
class OrbitEyeHeightClampTest {

    private fun distance(a: Position, b: Position): Float {
        val dx = a.x - b.x; val dy = a.y - b.y; val dz = a.z - b.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    @Test
    fun `an eye above the limit is left alone`() {
        val eye = Position(0f, 2f, 5f)
        assertSame(eye, clampOrbitEyeHeight(eye, Position(0f, 0.5f, 0f), minY = 0.1f))
    }

    @Test
    fun `an eye below the limit is lifted onto it at the same distance and bearing`() {
        val target = Position(0f, 0.7f, 0f)
        val eye = Position(3f, -0.4f, 4f)
        val clamped = clampOrbitEyeHeight(eye, target, minY = 0.05f)
        assertEquals(0.05f, clamped.y, 1e-5f)
        assertEquals(distance(eye, target), distance(clamped, target), 1e-4f)
        assertEquals(3f / 4f, clamped.x / clamped.z, 1e-4f)
    }

    @Test
    fun `a non-finite limit turns the clamp off`() {
        val eye = Position(0f, -1f, 5f)
        assertSame(eye, clampOrbitEyeHeight(eye, Position(0f, 0f, 0f), minY = Float.NaN))
    }

    @Test
    fun `a minimum elevation lifts a grazing eye even above the height limit`() {
        val target = Position(0f, 0.5f, 0f)
        val eye = Position(0f, 0.6f, 10f) // far away, barely above the target
        val clamped = clampOrbitEyeHeight(eye, target, minY = 0.05f, minElevationDegrees = 8f)
        val radius = distance(eye, target)
        assertEquals(radius, distance(clamped, target), 1e-4f)
        assertEquals(sin(Math.toRadians(8.0)).toFloat(), (clamped.y - target.y) / radius, 1e-4f)
    }

    @Test
    fun `an eye already above the minimum elevation is left alone`() {
        val eye = Position(0f, 3f, 5f)
        assertSame(eye, clampOrbitEyeHeight(eye, Position(0f, 0.5f, 0f), minY = 0.1f, minElevationDegrees = 8f))
    }
}
