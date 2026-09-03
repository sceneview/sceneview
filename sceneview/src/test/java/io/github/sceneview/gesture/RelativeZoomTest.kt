package io.github.sceneview.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlin.math.abs
import org.junit.Test

/**
 * Pins for the relative pinch-dolly (#3403 / #3426).
 *
 * Filament's `OrbitManipulator::scroll` translates the eye by a **fixed** `zoomSpeed · scrolldelta`
 * world units, which is why the shipped zoom felt "hyper lent" on anything far away and punched the
 * camera through its own orbit pivot on anything close. These tests hold the two properties that
 * replace it: the step is a *ratio* of the current distance, and the clamps are hard.
 */
class RelativeZoomTest {

    private val speed = CameraGestureDetector.DefaultCameraManipulator.DEFAULT_PINCH_ZOOM_SPEED
    private val damping = CameraGestureDetector.DefaultCameraManipulator.DEFAULT_PINCH_ZOOM_DAMPING

    /** A pinch of [pixels] px: fingers coming together (zoom out) for a positive value. */
    private fun pinch(distance: Float, pixels: Float, min: Float = 1e-4f, max: Float = 1e6f) =
        zoomedDistanceForPinch(
            distance = distance,
            prevSeparation = 200f,
            currSeparation = 200f - pixels,
            minDistance = min,
            maxDistance = max,
        )

    // ── The defect the issue names ────────────────────────────────────────────────────────────

    @Test
    fun oneFullScreenPinchAboutHalvesOrDoublesTheDistance() {
        // The tuning target: a 200 px pinch — roughly a full-screen gesture on a phone — is one
        // comfortable "step" of zoom. Anything less and the user pinches ten times to get
        // anywhere, which is exactly what #3426 reports.
        val out = pinch(distance = 5f, pixels = 200f)
        val `in` = pinch(distance = 5f, pixels = -200f)
        assertEquals("pinching in must roughly double the distance", 10f, out, 1.2f)
        assertEquals("pinching out must roughly halve it", 2.5f, `in`, 0.4f)
    }

    @Test
    fun theSameGestureCoversTheSameFractionAtEveryScale() {
        // The property the absolute translation lacked: a 5 cm bee and a 155 m landscape must
        // respond identically to the same fingers. Under the old model the bee teleported and the
        // landscape did not visibly move.
        val ratios = listOf(0.05f, 1f, 5f, 155f).map { pinch(it, 120f) / it }
        ratios.forEach { assertEquals(ratios.first(), it, 1e-4f) }
    }

    @Test
    fun theLegacyAbsoluteStepWasTheProblemNotTheDamping() {
        // Documents the measured "before" so the fix is not re-tuned away. Filament moves the eye
        // by `zoomSpeed · scrolldelta`; with the old 1/18 gain a 200 px pinch produced ~11 cm of
        // travel, whatever the subject.
        val legacyDelta = pinchZoomDelta(200f, 0f, speed = 1f / 18f, damping = damping)
        val legacyMetres = legacyDelta *
            CameraGestureDetector.DefaultCameraManipulator.DEFAULT_ORBIT_ZOOM_SPEED
        assertEquals(0.115f, legacyMetres, 0.02f)
        // On a scene framed at 5 m that is 40+ full pinches just to halve the distance.
        assertTrue("the legacy step was pathologically small at 5 m", 2.5f / legacyMetres > 20f)
    }

    // ── The clamps that stop #3403's inversion ────────────────────────────────────────────────

    @Test
    fun theCameraCanNeverReachLetAloneCrossThePivot() {
        // `OrbitManipulator::scroll` flips `mFlipped` when the eye passes the pivot, and the next
        // orbit drag then rebuilds the view from a NEGATIVE bookmark distance — the camera ends up
        // looking away from the subject. A hard floor is what makes that unreachable. Fingers
        // spreading apart (separation grows) is zoom IN, so the distance shrinks to the floor.
        var d = 3f
        repeat(200) { d = zoomedDistanceForPinch(d, 200f, 400f, minDistance = 0.45f, maxDistance = 12f) }
        assertEquals(0.45f, d, 1e-4f)
        assertTrue("distance must stay strictly positive", d > 0f)
    }

    @Test
    fun zoomingOutIsBoundedToo() {
        // Fingers coming together is zoom OUT — the distance grows to the ceiling.
        var d = 3f
        repeat(200) { d = zoomedDistanceForPinch(d, 400f, 200f, minDistance = 0.45f, maxDistance = 12f) }
        assertEquals(12f, d, 1e-4f)
    }

    @Test
    fun aPinchAndItsExactReverseReturnToTheStartingDistance() {
        // Exponential in the gesture ⇒ symmetric. A linear-in-metres step is not, which is part of
        // why the old zoom drifted the framing.
        val start = 2.5f
        val out = zoomedDistance(start, 0.4f, 1e-4f, 1e6f)
        val back = zoomedDistance(out, -0.4f, 1e-4f, 1e6f)
        assertEquals(start, back, 1e-3f)
    }

    @Test
    fun degenerateInputsCannotProduceANaNCamera() {
        assertEquals(0.5f, zoomedDistance(Float.NaN, 0.2f, 0.5f, 10f), 1e-6f)
        assertEquals(0.5f, zoomedDistance(0f, 0.2f, 0.5f, 10f), 1e-6f)
        assertEquals(2f, zoomedDistance(2f, Float.NaN, 0.5f, 10f), 1e-6f)
        assertTrue(zoomedDistance(2f, 1e9f, 0.5f, 10f).isFinite())
        assertTrue(zoomedDistance(2f, -1e9f, 0.5f, 10f).isFinite())
        // A nonsensical range must still yield a usable distance rather than NaN.
        assertTrue(zoomedDistance(2f, 0.1f, minDistance = -1f, maxDistance = -5f).isFinite())
    }

    // ── Round-tripping through Filament's absolute scroll unit ────────────────────────────────

    @Test
    fun theScrollDeltaHandedToFilamentLandsOnTheRequestedDistance() {
        // `scroll` moves the eye by `zoomSpeed · scrolldelta` along the gaze, so the distance
        // changes by exactly that. Inverting it must be exact, whatever the zoomSpeed.
        for (zoomSpeed in listOf(0.01f, 0.05f, 0.5f)) {
            for (from in listOf(0.2f, 3f, 90f)) {
                val to = from * 0.7f
                val delta = dollyScrollDelta(from, to, zoomSpeed)
                assertEquals(to, from + delta * zoomSpeed, abs(to) * 1e-4f)
            }
        }
    }

    @Test
    fun anUnusableZoomSpeedYieldsNoMovementRatherThanInfinity() {
        assertEquals(0f, dollyScrollDelta(3f, 1f, 0f), 0f)
        assertEquals(0f, dollyScrollDelta(3f, 1f, Float.NaN), 0f)
    }

    // ── The gain constant itself ──────────────────────────────────────────────────────────────

    @Test
    fun thePinchGainMatchesItsDocumentedTarget() {
        // `DEFAULT_PINCH_ZOOM_SPEED` is documented as "one 200 px pinch ≈ ln 2". Assert the doc.
        val delta = pinchZoomDelta(200f, 0f, speed, damping)
        assertEquals(kotlin.math.ln(2.0).toFloat(), delta, 0.12f)
    }
}
