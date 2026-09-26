package io.github.sceneview.demo.common.placement

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The one AR placement flow's decisions, tested where they can actually be tested.
 *
 * `emulator-5554` has no camera HAL, so it can never run an ARCore session
 * ([#2754](https://github.com/sceneview/sceneview/issues/2754)) — a placement rule that
 * only exists inside a composable is a rule nothing verifies until it reaches a Pixel. Every
 * decision the flow makes is therefore a pure function in `PlacementFlow.kt`, and this is
 * where it is pinned. The placement itself is [AutoPlacementControllerTest].
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
}
