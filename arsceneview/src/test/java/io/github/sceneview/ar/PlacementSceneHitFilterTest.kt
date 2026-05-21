package io.github.sceneview.ar

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM regression test for the tap-to-place hit-acceptance rule landed by #1765 on
 * [PlacementScene] (the Sceneform `ArFragment` parity bundle).
 *
 * `PlacementScene` runs at the AR render thread and the underlying ARCore `HitResult` /
 * `Trackable` types are JNI-only — their constructors take native handles. So the rule is split
 * the same way #1772 split `streetscapeGeometryPasses`: the production `HitResult` overload of
 * [isPlacementHit] maps the JNI types into [PlacementTrackableKind] + primitives, then calls the
 * pure-JVM [isPlacementHit] overload that this test pins.
 *
 * What we pin:
 *  - A plane hit inside the polygon is accepted.
 *  - A plane hit outside the polygon is rejected (no anchoring off a half-grown plane edge).
 *  - An instant-placement hit is accepted only when instant placement is enabled.
 *  - Points / depth points are rejected — `PlacementScene` is a plane-or-instant bundle.
 *  - A non-tracking trackable is rejected.
 *  - Hits beyond [MAX_PLACEMENT_DISTANCE] are rejected.
 */
class PlacementSceneHitFilterTest {

    @Test
    fun `plane hit inside polygon is accepted`() {
        assertTrue(
            isPlacementHit(
                trackableKind = PlacementTrackableKind.PLANE_IN_POLYGON,
                trackableTracking = true,
                distance = 1.0f,
                instantPlacement = true,
            )
        )
    }

    @Test
    fun `plane hit inside polygon is accepted even with instant placement off`() {
        assertTrue(
            isPlacementHit(
                trackableKind = PlacementTrackableKind.PLANE_IN_POLYGON,
                trackableTracking = true,
                distance = 1.0f,
                instantPlacement = false,
            )
        )
    }

    @Test
    fun `plane hit outside polygon is rejected`() {
        assertFalse(
            isPlacementHit(
                trackableKind = PlacementTrackableKind.PLANE_OUT_OF_POLYGON,
                trackableTracking = true,
                distance = 1.0f,
                instantPlacement = true,
            )
        )
    }

    @Test
    fun `instant placement hit is accepted when instant placement enabled`() {
        assertTrue(
            isPlacementHit(
                trackableKind = PlacementTrackableKind.INSTANT_PLACEMENT,
                trackableTracking = true,
                distance = 1.0f,
                instantPlacement = true,
            )
        )
    }

    @Test
    fun `instant placement hit is rejected when instant placement disabled`() {
        assertFalse(
            isPlacementHit(
                trackableKind = PlacementTrackableKind.INSTANT_PLACEMENT,
                trackableTracking = true,
                distance = 1.0f,
                instantPlacement = false,
            )
        )
    }

    @Test
    fun `point and depth-point trackables are always rejected`() {
        // PlacementTrackableKind.OTHER covers Point / DepthPoint — PlacementScene is a
        // plane-or-instant bundle; depth-surface placement is DepthHitResultNode's job.
        listOf(true, false).forEach { instant ->
            assertFalse(
                isPlacementHit(
                    trackableKind = PlacementTrackableKind.OTHER,
                    trackableTracking = true,
                    distance = 1.0f,
                    instantPlacement = instant,
                )
            )
        }
    }

    @Test
    fun `non-tracking trackable is rejected regardless of kind`() {
        PlacementTrackableKind.entries.forEach { kind ->
            assertFalse(
                "non-tracking $kind must be rejected",
                isPlacementHit(
                    trackableKind = kind,
                    trackableTracking = false,
                    distance = 1.0f,
                    instantPlacement = true,
                )
            )
        }
    }

    @Test
    fun `hit beyond max placement distance is rejected`() {
        assertFalse(
            isPlacementHit(
                trackableKind = PlacementTrackableKind.PLANE_IN_POLYGON,
                trackableTracking = true,
                distance = MAX_PLACEMENT_DISTANCE + 0.01f,
                instantPlacement = true,
            )
        )
    }

    @Test
    fun `hit exactly at max placement distance is accepted`() {
        assertTrue(
            isPlacementHit(
                trackableKind = PlacementTrackableKind.PLANE_IN_POLYGON,
                trackableTracking = true,
                distance = MAX_PLACEMENT_DISTANCE,
                instantPlacement = true,
            )
        )
    }
}
