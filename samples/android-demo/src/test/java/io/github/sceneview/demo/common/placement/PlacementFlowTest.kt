package io.github.sceneview.demo.common.placement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one AR placement flow's decisions, tested where they can actually be tested.
 *
 * `emulator-5554` has no camera HAL, so it can never run an ARCore session
 * ([#2754](https://github.com/sceneview/sceneview/issues/2754)) — a placement rule that
 * only exists inside a composable is a rule nothing verifies until it reaches a Pixel. Every
 * decision #3405 added is therefore a pure function in `PlacementFlow.kt`, and this is where
 * it is pinned.
 */
class PlacementFlowTest {

    // ---------------------------------------------------------------- back ladder

    @Test
    fun `back out of the camera returns to the chooser, not out of the demo`() {
        assertEquals(
            PlacementBackAction.RETURN_TO_CHOOSER,
            placementBackAction(PlacementFlowPhase.PLACING),
        )
    }

    @Test
    fun `back on the chooser leaves the demo, because it is the flow's ground floor`() {
        assertEquals(
            PlacementBackAction.LEAVE_DEMO,
            placementBackAction(PlacementFlowPhase.CHOOSING),
        )
    }

    // ------------------------------------------------------------- placement mode

    @Test
    fun `instant placement is on for exactly one mode`() {
        assertFalse(instantPlacementEnabled(PlacementMode.PLANE))
        assertTrue(instantPlacementEnabled(PlacementMode.INSTANT))
    }

    // -------------------------------------------------------------- hit precedence

    @Test
    fun `a real plane hit always wins, even with instant placement on`() {
        // This is the defect the retired `ar-instant-placement` demo shipped: it branched
        // exclusively on the flag, so turning the feature on made an accurate plane anchor
        // unreachable for the whole session.
        assertEquals(
            PlacementHitSource.PLANE,
            placementHitSource(hasPlaneHit = true, instantEnabled = true, hasInstantHit = true),
        )
    }

    @Test
    fun `instant is the fallback when no plane accepted the tap`() {
        assertEquals(
            PlacementHitSource.INSTANT,
            placementHitSource(hasPlaneHit = false, instantEnabled = true, hasInstantHit = true),
        )
    }

    @Test
    fun `an instant hit is ignored while the mode is off`() {
        assertEquals(
            PlacementHitSource.NONE,
            placementHitSource(hasPlaneHit = false, instantEnabled = false, hasInstantHit = true),
        )
    }

    @Test
    fun `a tap with nothing under it is dropped rather than anchored somewhere`() {
        assertEquals(
            PlacementHitSource.NONE,
            placementHitSource(hasPlaneHit = false, instantEnabled = true, hasInstantHit = false),
        )
    }

    // ------------------------------------------------------- instant tracking badge

    @Test
    fun `a plane-anchored placement has no approximation to report`() {
        assertNull(instantTrackingLabel(isInstantPoint = false, isFullTracking = false))
        assertNull(instantTrackingLabel(isInstantPoint = false, isFullTracking = true))
    }

    @Test
    fun `an instant point reports the refinement it is in`() {
        assertEquals(
            InstantTrackingLabel.APPROXIMATING,
            instantTrackingLabel(isInstantPoint = true, isFullTracking = false),
        )
        assertEquals(
            InstantTrackingLabel.TRACKED,
            instantTrackingLabel(isInstantPoint = true, isFullTracking = true),
        )
    }

    // ------------------------------------------------------------ coaching under instant

    @Test
    fun `instant placement stops the screen asking for an aim it does not need`() {
        assertEquals(
            TapToPlaceUxState.READY,
            effectivePlacementUxState(TapToPlaceUxState.AIMING, instantEnabled = true),
        )
    }

    @Test
    fun `plane mode keeps AIMING, because there a tap really is refused`() {
        assertEquals(
            TapToPlaceUxState.AIMING,
            effectivePlacementUxState(TapToPlaceUxState.AIMING, instantEnabled = false),
        )
    }

    @Test
    fun `scanning is never promoted, so the discovery guide keeps its phase`() {
        // An instant placement made before any plane exists is the least accurate one
        // available; the guide that gets the user a real surface still earns its screen time.
        assertEquals(
            TapToPlaceUxState.SCANNING,
            effectivePlacementUxState(TapToPlaceUxState.SCANNING, instantEnabled = true),
        )
    }

    @Test
    fun `no other state is touched by the mode`() {
        val untouched = listOf(
            TapToPlaceUxState.INITIALIZING,
            TapToPlaceUxState.TRACKING_LOST,
            TapToPlaceUxState.SCANNING,
            TapToPlaceUxState.READY,
        )
        untouched.forEach { state ->
            assertEquals(state, effectivePlacementUxState(state, instantEnabled = true))
            assertEquals(state, effectivePlacementUxState(state, instantEnabled = false))
        }
    }

    // ------------------------------------------------------------------- the CTA gate

    @Test
    fun `the CTA waits while ARCore availability is still resolving`() {
        assertEquals(
            PlacementCtaState.CHECKING,
            placementCtaState(arSupported = null, hasArmedModel = true),
        )
    }

    @Test
    fun `an unsupported device is told that, not told to pick a model`() {
        // Precedence matters: "pick a model first" on a device that can never open the
        // camera sends the user to fix the wrong thing.
        assertEquals(
            PlacementCtaState.AR_UNSUPPORTED,
            placementCtaState(arSupported = false, hasArmedModel = false),
        )
        assertEquals(
            PlacementCtaState.AR_UNSUPPORTED,
            placementCtaState(arSupported = false, hasArmedModel = true),
        )
    }

    @Test
    fun `an empty catalogue refuses rather than entering AR with nothing armed`() {
        assertEquals(
            PlacementCtaState.NO_MODEL,
            placementCtaState(arSupported = true, hasArmedModel = false),
        )
    }

    @Test
    fun `a supported device with a model armed is the only way in`() {
        assertEquals(
            PlacementCtaState.READY,
            placementCtaState(arSupported = true, hasArmedModel = true),
        )
    }

    @Test
    fun `every non-ready CTA state has something to say`() {
        // The silent-refusal class: a disabled control that explains nothing is the app
        // saying no without saying why. READY is the only state allowed to be quiet.
        val speaks = PlacementCtaState.entries.filter { it != PlacementCtaState.READY }
        assertEquals(3, speaks.size)
    }

    // --------------------------------------------------------------------- constants

    @Test
    fun `the instant approximate distance is ARCore's documented starting point`() {
        // Also the value the retired demo used — kept so the folded mode behaves exactly
        // like the screen it replaced.
        assertEquals(1.0f, INSTANT_APPROXIMATE_DISTANCE_M, 0f)
    }
}
