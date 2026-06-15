package io.github.sceneview.demo.common.placement

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for [PlacementHitPolicy.accept] — the single source of truth for the
 * tap filter AND the reticle filter
 * ([#2482](https://github.com/sceneview/sceneview/issues/2482)). They were hand-duplicated
 * in the original `ARPlacementDemo`; this test pins the contract so they cannot drift.
 *
 * The matrix: plane in/out of polygon × `snapToPlane` on/off × tracking/not × distance
 * 4.9 m vs 5.1 m, mirroring the #1883 free-placement semantics and the polygon gate that
 * both the tap and reticle share.
 */
class PlacementHitPolicyTest {

    private val near = 4.9f
    private val far = 5.1f

    // ── Non-tracking trackable is always rejected ────────────────────────────

    @Test
    fun `non-tracking trackable is rejected regardless of everything else`() {
        assertFalse(
            PlacementHitPolicy.accept(
                isPlane = true,
                isPoseInPolygon = true,
                isTrackableTracking = false,
                distanceMeters = near,
                snapToPlane = true,
            ),
        )
        assertFalse(
            PlacementHitPolicy.accept(
                isPlane = false,
                isPoseInPolygon = false,
                isTrackableTracking = false,
                distanceMeters = near,
                snapToPlane = false,
            ),
        )
    }

    // ── Distance gate at MAX_HIT_DISTANCE_M (5.0 m) ──────────────────────────

    @Test
    fun `hit just beyond the max distance is rejected`() {
        assertFalse(
            PlacementHitPolicy.accept(
                isPlane = true,
                isPoseInPolygon = true,
                isTrackableTracking = true,
                distanceMeters = far,
                snapToPlane = true,
            ),
        )
    }

    @Test
    fun `hit just within the max distance is accepted`() {
        assertTrue(
            PlacementHitPolicy.accept(
                isPlane = true,
                isPoseInPolygon = true,
                isTrackableTracking = true,
                distanceMeters = near,
                snapToPlane = true,
            ),
        )
    }

    @Test
    fun `the max distance boundary itself is accepted`() {
        assertTrue(
            PlacementHitPolicy.accept(
                isPlane = true,
                isPoseInPolygon = true,
                isTrackableTracking = true,
                distanceMeters = PlacementHitPolicy.MAX_HIT_DISTANCE_M,
                snapToPlane = true,
            ),
        )
    }

    // ── snapToPlane ON: only in-polygon plane hits ───────────────────────────

    @Test
    fun `snap on accepts an in-polygon plane hit`() {
        assertTrue(
            PlacementHitPolicy.accept(
                isPlane = true,
                isPoseInPolygon = true,
                isTrackableTracking = true,
                distanceMeters = near,
                snapToPlane = true,
            ),
        )
    }

    @Test
    fun `snap on rejects a plane hit outside the polygon`() {
        assertFalse(
            PlacementHitPolicy.accept(
                isPlane = true,
                isPoseInPolygon = false,
                isTrackableTracking = true,
                distanceMeters = near,
                snapToPlane = true,
            ),
        )
    }

    @Test
    fun `snap on rejects a non-plane hit`() {
        assertFalse(
            PlacementHitPolicy.accept(
                isPlane = false,
                isPoseInPolygon = false,
                isTrackableTracking = true,
                distanceMeters = near,
                snapToPlane = true,
            ),
        )
    }

    // ── snapToPlane OFF: any tracked hit, plane hits still polygon-gated (#1883)

    @Test
    fun `snap off accepts a non-plane hit`() {
        assertTrue(
            PlacementHitPolicy.accept(
                isPlane = false,
                isPoseInPolygon = false,
                isTrackableTracking = true,
                distanceMeters = near,
                snapToPlane = false,
            ),
        )
    }

    @Test
    fun `snap off accepts an in-polygon plane hit`() {
        assertTrue(
            PlacementHitPolicy.accept(
                isPlane = true,
                isPoseInPolygon = true,
                isTrackableTracking = true,
                distanceMeters = near,
                snapToPlane = false,
            ),
        )
    }

    @Test
    fun `snap off still rejects a plane hit outside the polygon`() {
        // The #1883 free-placement path must NOT let a plane hit anchor off the edge
        // of a half-converged plane — non-plane geometry is the only escape hatch.
        assertFalse(
            PlacementHitPolicy.accept(
                isPlane = true,
                isPoseInPolygon = false,
                isTrackableTracking = true,
                distanceMeters = near,
                snapToPlane = false,
            ),
        )
    }

    @Test
    fun `snap off rejects a non-plane hit that is too far`() {
        assertFalse(
            PlacementHitPolicy.accept(
                isPlane = false,
                isPoseInPolygon = false,
                isTrackableTracking = true,
                distanceMeters = far,
                snapToPlane = false,
            ),
        )
    }
}
