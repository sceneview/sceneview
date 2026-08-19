package io.github.sceneview.compose

import dev.romainguy.kotlin.math.Float3
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OrbitCameraTest {

    @Test
    fun azimuth_zero_elevation_zero_looks_along_plus_z() {
        val eye = orbitEyePosition(Float3(0f, 0f, 0f), distance = 4f, azimuthDegrees = 0f, elevationDegrees = 0f)
        assertClose(0f, eye.x)
        assertClose(0f, eye.y)
        assertClose(4f, eye.z)
    }

    @Test
    fun azimuth_90_orbits_to_plus_x() {
        val eye = orbitEyePosition(Float3(0f, 0f, 0f), distance = 4f, azimuthDegrees = 90f, elevationDegrees = 0f)
        assertClose(4f, eye.x)
        assertClose(0f, eye.y)
        assertClose(0f, eye.z)
    }

    @Test
    fun elevation_raises_the_eye_and_shortens_the_horizontal() {
        val target = Float3(0f, 1f, 0f)
        val eye = orbitEyePosition(target, distance = 4f, azimuthDegrees = 0f, elevationDegrees = 30f)
        assertClose(0f, eye.x)
        assertTrue(eye.y > target.y)
        assertTrue(eye.z < 4f)
        val dx = eye.x - target.x
        val dy = eye.y - target.y
        val dz = eye.z - target.z
        assertClose(4f, sqrt(dx * dx + dy * dy + dz * dz))
    }

    private fun assertClose(expected: Float, actual: Float) {
        assertEquals(expected, actual, abs(expected) * 1e-5f + 1e-5f)
    }
}
