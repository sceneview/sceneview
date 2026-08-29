package io.github.sceneview.demo

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [arCameraInitScrimVisibility] — the scrim decision behind `ARCameraInitScrim`
 * (#2484, #3373, #3341).
 */
class ArCameraInitScrimVisibilityTest {

    @Test
    fun `camera ready hides the scrim entirely`() {
        assertEquals(
            ArCameraInitScrimVisibility.Hidden,
            arCameraInitScrimVisibility(
                initializing = false,
                timedOut = false,
                qaBackdropEnabled = false,
                arCoreUnavailable = false,
            )
        )
    }

    @Test
    fun `camera ready hides the scrim even if the timeout already fired`() {
        assertEquals(
            ArCameraInitScrimVisibility.Hidden,
            arCameraInitScrimVisibility(
                initializing = false,
                timedOut = true,
                qaBackdropEnabled = false,
                arCoreUnavailable = false,
            )
        )
    }

    @Test
    fun `still starting shows backdrop and spinner`() {
        assertEquals(
            ArCameraInitScrimVisibility.BackdropAndSpinner,
            arCameraInitScrimVisibility(
                initializing = true,
                timedOut = false,
                qaBackdropEnabled = false,
                arCoreUnavailable = false,
            )
        )
    }

    /**
     * #3373: after the defensive timeout the spinner must stop, but the backdrop must not —
     * otherwise the never-initialised AR viewport shows through the "AR couldn't start"
     * fallback as a parasitic coloured band.
     */
    @Test
    fun `timed out keeps the backdrop and drops the spinner`() {
        assertEquals(
            ArCameraInitScrimVisibility.BackdropOnly,
            arCameraInitScrimVisibility(
                initializing = true,
                timedOut = true,
                qaBackdropEnabled = false,
                arCoreUnavailable = false,
            )
        )
    }

    /** #3308: the QA room-photo backdrop is deliberate content and must stay visible. */
    @Test
    fun `timed out with the QA backdrop dismisses completely`() {
        assertEquals(
            ArCameraInitScrimVisibility.Hidden,
            arCameraInitScrimVisibility(
                initializing = true,
                timedOut = true,
                qaBackdropEnabled = true,
                arCoreUnavailable = false,
            )
        )
    }

    /** The QA backdrop must not short-circuit the normal, still-progressing start. */
    @Test
    fun `QA backdrop before the timeout still shows the spinner`() {
        assertEquals(
            ArCameraInitScrimVisibility.BackdropAndSpinner,
            arCameraInitScrimVisibility(
                initializing = true,
                timedOut = false,
                qaBackdropEnabled = true,
                arCoreUnavailable = false,
            )
        )
    }

    /**
     * #3341: an unsupported device (every emulator — #2754) never delivers a first frame, so
     * `initializing` stays true forever and the scrim used to settle on a permanent black
     * cover. The SDK's own `ARCoreAvailabilityOverlay` draws inside the `ARSceneView`, i.e.
     * *under* this scrim, so the verdict has to dismiss the scrim or the explanation is never
     * seen.
     */
    @Test
    fun `an ARCore verdict hides the scrim even though the camera never started`() {
        assertEquals(
            ArCameraInitScrimVisibility.Hidden,
            arCameraInitScrimVisibility(
                initializing = true,
                timedOut = true,
                qaBackdropEnabled = false,
                arCoreUnavailable = true,
            )
        )
    }

    /**
     * The verdict wins over the spinner phase: waiting is pointless the moment ARCore says
     * the session cannot start, so the user should not watch a progress indicator for the
     * eight seconds it takes the defensive timeout to fire.
     */
    @Test
    fun `an ARCore verdict hides the scrim before the timeout fires`() {
        assertEquals(
            ArCameraInitScrimVisibility.Hidden,
            arCameraInitScrimVisibility(
                initializing = true,
                timedOut = false,
                qaBackdropEnabled = false,
                arCoreUnavailable = true,
            )
        )
    }

    /** The QA backdrop and the verdict agree here — both want the viewport uncovered. */
    @Test
    fun `an ARCore verdict hides the scrim with the QA backdrop on`() {
        assertEquals(
            ArCameraInitScrimVisibility.Hidden,
            arCameraInitScrimVisibility(
                initializing = true,
                timedOut = false,
                qaBackdropEnabled = true,
                arCoreUnavailable = true,
            )
        )
    }
}
