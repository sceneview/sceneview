package io.github.sceneview.demo

import io.github.sceneview.math.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Unit coverage for [clampOrbitEyePitch] — the orbit-flip guard on the Explore
 * 3D viewer's hero camera (#2487). Pure JVM math, no Android / Filament needed.
 */
class OrbitEyePitchClampTest {

    private fun radius(eye: Position, target: Position): Float {
        val dx = eye.x - target.x; val dy = eye.y - target.y; val dz = eye.z - target.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /** Polar angle (degrees from world +Y) of the `target → eye` direction. */
    private fun polarDegrees(eye: Position, target: Position): Float {
        val dx = eye.x - target.x; val dy = eye.y - target.y; val dz = eye.z - target.z
        val r = sqrt(dx * dx + dy * dy + dz * dz)
        return Math.toDegrees(acos((dy / r).coerceIn(-1f, 1f)).toDouble()).toFloat()
    }

    @Test fun eyeInRange_returnsSameInstanceUnchanged() {
        // 45° above the horizon → comfortably within [1°, 179°].
        val eye = Position(2f, 2f, 0f)
        val target = Position(0f, 0f, 0f)
        val out = clampOrbitEyePitch(eye, target)
        assertSame("in-range eye must be returned unchanged (fast path)", eye, out)
    }

    @Test fun eyeOverTopPole_isClampedToFloor() {
        // Eye almost straight above the target (polar ≈ 0°) → clamp to the 1° floor.
        val target = Position(0f, 0f, 0f)
        val out = clampOrbitEyePitch(Position(0.001f, 3f, 0f), target)
        assertEquals("eye pushed to the min polar floor", 1f, polarDegrees(out, target), 1e-2f)
    }

    @Test fun eyeUnderBottomPole_isClampedToCeiling() {
        // Eye almost straight below the target (polar ≈ 180°) → clamp to the 179° ceiling.
        val target = Position(0f, 0f, 0f)
        val out = clampOrbitEyePitch(Position(0.001f, -3f, 0f), target)
        assertEquals("eye pushed to the max polar ceiling", 179f, polarDegrees(out, target), 1e-2f)
    }

    @Test fun clampPreservesRadius() {
        val target = Position(1f, 2f, -3f)
        val eye = Position(1f, 7f, -3.001f) // nearly straight up, radius ~5
        val before = radius(eye, target)
        val out = clampOrbitEyePitch(eye, target)
        assertEquals("orbit radius preserved through the clamp", before, radius(out, target), 1e-3f)
    }

    @Test fun clampPreservesAzimuth() {
        // Eye nearly above target but biased toward +X → after clamp the horizontal
        // direction must still point toward +X (azimuth preserved, only pitch lowered).
        val target = Position(0f, 0f, 0f)
        val out = clampOrbitEyePitch(Position(0.05f, 5f, 0f), target)
        assertTrue("horizontal X stays positive (azimuth preserved)", out.x > 0f)
        assertEquals("no Z azimuth introduced", 0f, out.z, 1e-4f)
        assertEquals("eye sits at the 1° floor", 1f, polarDegrees(out, target), 1e-2f)
    }

    @Test fun eyeExactlyOnAxis_liftsOffSingularity() {
        // Eye exactly on the +Y axis has no azimuth to preserve; the clamp must still
        // move it off the pole to a well-defined polar angle, keeping the radius.
        val target = Position(0f, 0f, 0f)
        val out = clampOrbitEyePitch(Position(0f, 4f, 0f), target)
        assertEquals("lifts off the exact pole to the 1° floor", 1f, polarDegrees(out, target), 1e-2f)
        assertEquals("radius preserved off the pole", 4f, radius(out, target), 1e-3f)
    }

    @Test fun eyeEqualsTarget_isNoOp() {
        val eye = Position(1f, 1f, 1f)
        val out = clampOrbitEyePitch(eye, Position(1f, 1f, 1f))
        assertSame("degenerate (eye==target) returns the eye untouched", eye, out)
    }

    @Test fun nonFiniteEye_isNoOp() {
        val eye = Position(Float.NaN, 5f, 0f)
        val out = clampOrbitEyePitch(eye, Position(0f, 0f, 0f))
        assertSame("non-finite eye returned untouched", eye, out)
    }

    @Test fun customBounds_areHonoured() {
        // Tighten to [30°, 150°]: a 10° eye must rise to the 30° floor.
        val target = Position(0f, 0f, 0f)
        val r = 2f
        val tenDeg = Math.toRadians(10.0)
        val eye = Position(
            (r * sin(tenDeg)).toFloat(),
            (r * cos(tenDeg)).toFloat(),
            0f,
        )
        val out = clampOrbitEyePitch(eye, target, minPolarDegrees = 30f, maxPolarDegrees = 150f)
        assertEquals(30f, polarDegrees(out, target), 1e-2f)
    }
}
