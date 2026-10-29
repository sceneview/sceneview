package io.github.sceneview.camera

import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Float4
import dev.romainguy.kotlin.math.length
import dev.romainguy.kotlin.math.normalize
import io.github.sceneview.math.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Pure-JVM pins for [CameraShot]: the orbit convention, its inverse, and the short-arc lerp. */
class CameraShotTest {

    @Test
    fun eyeFollowsTheOrbitConvention() {
        // Azimuth 0 stands on +Z, elevation 0 is level: the eye is `distance` along +Z.
        val onZ = CameraShot(target = Position(1f, 2f, 3f), azimuthDegrees = 0f, elevationDegrees = 0f, distance = 4f)
        assertClose(Position(1f, 2f, 7f), onZ.eye)
        // Azimuth 90 turns towards +X.
        val onX = onZ.copy(azimuthDegrees = 90f)
        assertClose(Position(5f, 2f, 3f), onX.eye)
        // Elevation 90 (clamped at 89) is straight above: y gains the distance, x/z stay put.
        val above = onZ.copy(elevationDegrees = 90f)
        assertTrue(above.eye.y > 2f + 4f * 0.999f)
    }

    @Test
    fun lookingAtInvertsEye() {
        val shots = listOf(
            CameraShot(Position(0f, 0f, 0f), 35f, 32f, 3.2f),
            CameraShot(Position(-2f, 1f, 4f), -120f, 84f, 0.4f),
            CameraShot(Position(5f, -1f, -3f), 170f, -8f, 12f),
        )
        for (shot in shots) {
            val back = CameraShot.lookingAt(shot.eye, shot.target)
            assertEquals(shot.distance, back.distance, 1e-4f)
            assertEquals(shot.elevationDegrees, back.elevationDegrees, 1e-3f)
            assertEquals(0f, CameraShot.shortestDelta(shot.azimuthDegrees, back.azimuthDegrees), 1e-3f)
        }
    }

    @Test
    fun lookingAtAnEyeOnTheTargetIsAShotOfThatTarget() {
        val shot = CameraShot.lookingAt(Position(1f, 1f, 1f), Position(1f, 1f, 1f))
        assertClose(Position(1f, 1f, 1f), shot.target)
    }

    @Test
    fun transformLooksAtTheTarget() {
        val shot = CameraShot(Position(0f, 1f, 0f), 35f, 32f, 3f)
        val transform = shot.transform()
        // Column 3 is the eye; the camera looks down its local -Z (column 2 negated).
        val eye = Float3(transform[3].x, transform[3].y, transform[3].z)
        assertClose(shot.eye, eye)
        val forward = -Float3(transform[2].x, transform[2].y, transform[2].z)
        val toTarget = normalize(shot.target - shot.eye)
        assertClose(toTarget, normalize(forward))
    }

    @Test
    fun lerpTakesTheShortWayRound() {
        val from = CameraShot(azimuthDegrees = 350f)
        val to = CameraShot(azimuthDegrees = 10f)
        val half = CameraShot.lerp(from, to, 0.5f)
        assertEquals(360f, half.azimuthDegrees, 1e-4f)
        // Off, it goes the long way, as written.
        val long = CameraShot.lerp(from, to, 0.5f, shortestArc = false)
        assertEquals(180f, long.azimuthDegrees, 1e-4f)
    }

    @Test
    fun lerpEndsAreExactAndMiddleIsHalfway() {
        val from = CameraShot(Position(0f, 0f, 0f), 0f, 10f, 2f)
        val to = CameraShot(Position(2f, 4f, 6f), 90f, 50f, 6f)
        assertEquals(from, CameraShot.lerp(from, to, 0f))
        assertEquals(to, CameraShot.lerp(from, to, 1f))
        val half = CameraShot.lerp(from, to, 0.5f)
        assertClose(Position(1f, 2f, 3f), half.target)
        assertEquals(45f, half.azimuthDegrees, 1e-5f)
        assertEquals(30f, half.elevationDegrees, 1e-5f)
        assertEquals(4f, half.distance, 1e-5f)
        // Out-of-range fractions clamp.
        assertEquals(to, CameraShot.lerp(from, to, 2f))
    }

    @Test
    fun normalizeAndShortestDelta() {
        assertEquals(0f, CameraShot.normalizeDegrees(720f), 1e-5f)
        assertEquals(180f, CameraShot.normalizeDegrees(180f), 1e-5f)
        assertEquals(180f, CameraShot.normalizeDegrees(-180f), 1e-5f)
        assertEquals(-170f, CameraShot.normalizeDegrees(190f), 1e-5f)
        assertEquals(20f, CameraShot.shortestDelta(350f, 10f), 1e-5f)
        assertEquals(-20f, CameraShot.shortestDelta(10f, 350f), 1e-5f)
        assertEquals(0f, CameraShot.normalizeDegrees(Float.NaN), 0f)
    }

    @Test
    fun velocityBetweenIsTheMeanRate() {
        val from = CameraShot(Position(0f, 0f, 0f), 0f, 0f, 1f)
        val to = CameraShot(Position(1f, 0f, 0f), 90f, 45f, 3f)
        val v = CameraShotVelocity.between(from, to, 0.5f)
        assertEquals(180f, v.azimuthDegreesPerSecond, 1e-5f)
        assertEquals(90f, v.elevationDegreesPerSecond, 1e-5f)
        assertEquals(4f, v.distancePerSecond, 1e-5f)
        assertClose(Position(2f, 0f, 0f), v.targetPerSecond)
        assertEquals(CameraShotVelocity.Zero, CameraShotVelocity.between(from, to, 0f))
        assertTrue(CameraShotVelocity.Zero.isZero)
        assertTrue(!v.isZero)
    }

    private fun assertClose(expected: Float3, actual: Float3, tolerance: Float = 1e-4f) {
        assertTrue("expected $expected, was $actual", length(expected - actual) < tolerance)
    }

    @Suppress("unused")
    private fun Float4.xyz() = Float3(x, y, z)

    @Suppress("unused")
    private fun near(a: Float, b: Float) = abs(a - b) < 1e-4f
}
