package io.github.sceneview.demo

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [arCameraInitScrimVisibility] — the scrim decision behind `ARCameraInitScrim`
 * (#2484, #3373).
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
            )
        )
    }
}
