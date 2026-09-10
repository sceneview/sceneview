package io.github.sceneview.ar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the two [PlacementScene] rules the device QA of v4.34.0 caught.
 *
 * - [placementHitSource] — the plane-first / instant-fallback precedence that makes a tap
 *   actually place something before a plane has converged (#3571). The composable used to feed
 *   `frame.hitTest(event)` straight into the acceptance filter with `instantPlacement = true`,
 *   but ARCore only ever returns an `InstantPlacementPoint` from `hitTestInstantPlacement`, so
 *   that branch was unreachable and taps were dropped in silence.
 * - [shouldShowReticle] — the gate that keeps the reticle off screen until the camera is
 *   tracking, so it can no longer render flat and un-rotated at the world origin (#3569).
 */
class PlacementSceneTapFallbackTest {

    @Test
    fun `a plane hit always wins, even with instant placement on`() {
        assertEquals(
            PlacementHitSource.PLANE,
            placementHitSource(hasPlaneHit = true, instantEnabled = true, hasInstantHit = true)
        )
    }

    @Test
    fun `no plane falls back to the instant hit when instant is on`() {
        assertEquals(
            PlacementHitSource.INSTANT,
            placementHitSource(hasPlaneHit = false, instantEnabled = true, hasInstantHit = true)
        )
    }

    @Test
    fun `no plane and instant off drops the tap`() {
        assertEquals(
            PlacementHitSource.NONE,
            placementHitSource(hasPlaneHit = false, instantEnabled = false, hasInstantHit = true)
        )
    }

    @Test
    fun `an instant hit is never consulted while instant placement is off`() {
        assertEquals(
            PlacementHitSource.PLANE,
            placementHitSource(hasPlaneHit = true, instantEnabled = false, hasInstantHit = true)
        )
    }

    @Test
    fun `nothing under the finger drops the tap`() {
        assertEquals(
            PlacementHitSource.NONE,
            placementHitSource(hasPlaneHit = false, instantEnabled = true, hasInstantHit = false)
        )
    }

    @Test
    fun `the ARCore approximate distance is the documented one metre`() {
        assertEquals(1.0f, INSTANT_APPROXIMATE_DISTANCE_M, 0.0f)
    }

    @Test
    fun `the reticle shows only when asked for, measured and tracking`() {
        assertTrue(
            shouldShowReticle(showReticle = true, viewportMeasured = true, cameraTracking = true)
        )
    }

    @Test
    fun `the reticle stays hidden before the camera tracks`() {
        // The #3569 repro: composed, viewport measured, but no ARCore pose yet — the node would
        // otherwise render its geometry at the identity pose, flat, over the camera feed.
        assertFalse(
            shouldShowReticle(showReticle = true, viewportMeasured = true, cameraTracking = false)
        )
    }

    @Test
    fun `the reticle stays hidden until the viewport is measured`() {
        assertFalse(
            shouldShowReticle(showReticle = true, viewportMeasured = false, cameraTracking = true)
        )
    }

    @Test
    fun `showReticle false always wins`() {
        assertFalse(
            shouldShowReticle(showReticle = false, viewportMeasured = true, cameraTracking = true)
        )
    }
}
