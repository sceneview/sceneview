package io.github.sceneview.demo.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The decisions behind the AR camera gate (#4139), without a device. */
class ArDemoPermissionGateTest {

    @Test
    fun `granted permission shows the AR demo`() {
        assertEquals(
            ArDemoPermissionUiState.ShowDemo,
            arDemoPermissionUiState(granted = true, blocked = false),
        )
    }

    @Test
    fun `a refusal Android still asks about offers to ask`() {
        assertEquals(
            ArDemoPermissionUiState.AskPermission,
            arDemoPermissionUiState(granted = false, blocked = false),
        )
    }

    @Test
    fun `a blocked permission opens system settings`() {
        assertEquals(
            ArDemoPermissionUiState.OpenSettings,
            arDemoPermissionUiState(granted = false, blocked = true),
        )
    }

    /** #4139 (c): back from settings with the camera on, no tap in between. */
    @Test
    fun `grant after a block shows the demo without another tap`() {
        assertEquals(
            ArDemoPermissionUiState.ShowDemo,
            arDemoPermissionUiState(granted = true, blocked = true),
        )
    }

    @Test
    fun `a first visit opens the system dialog by itself`() {
        assertTrue(prompt())
    }

    /** An activity recreated behind the dialog must not ask a second time. */
    @Test
    fun `a prompt already launched is not launched again`() {
        assertFalse(prompt(promptLaunched = true))
    }

    /** Coming back after one "Don't allow": the explanation and a button, not the dialog. */
    @Test
    fun `a return visit after one refusal waits for a tap`() {
        assertFalse(prompt(shouldShowRationale = true))
    }

    @Test
    fun `nothing is asked once granted or blocked`() {
        assertFalse(prompt(granted = true))
        assertFalse(prompt(blocked = true))
    }

    /** A dialog dismissed with Back looks like a permanent denial to the rationale flag alone. */
    @Test
    fun `a dismissed dialog keeps the offer to ask`() {
        assertFalse(blocked(elapsedMs = 3_000))
    }

    /**
     * #4460: two Back presses in a row turned the card into "Open settings" on a Pixel 4a,
     * with no `USER_FIXED` flag on the permission. There is nothing left to count: a
     * dismissal is judged the same the first time and the tenth.
     */
    @Test
    fun `a dialog dismissed again and again never sends to settings`() {
        var rationale = false
        repeat(10) {
            val isBlocked = blocked(rationaleBefore = rationale, rationaleAfter = rationale, elapsedMs = 1_500)
            assertFalse("dismissal ${it + 1}", isBlocked)
        }
        // The same after one real "Don't allow": the flag is up, and a dismissal leaves it up.
        rationale = true
        repeat(10) {
            assertFalse(blocked(rationaleBefore = rationale, rationaleAfter = rationale, elapsedMs = 1_500))
        }
    }

    @Test
    fun `a first refusal that Android wants explained keeps the offer to ask`() {
        assertFalse(blocked(rationaleAfter = true, elapsedMs = 3_000))
        // Even answered at once: the flag says Android asks again.
        assertFalse(blocked(rationaleAfter = true, elapsedMs = 0))
    }

    @Test
    fun `the second refusal is a block`() {
        assertTrue(blocked(rationaleBefore = true, elapsedMs = 3_000))
    }

    @Test
    fun `an answer with no dialog is a block`() {
        assertTrue(blocked(elapsedMs = CAMERA_PROMPT_INSTANT_RETURN_MS - 1))
        // Measured on a Pixel 4a: a blocked request answers in 290 ms idle, 510 ms under load.
        assertTrue(blocked(elapsedMs = 290))
        assertTrue(blocked(elapsedMs = 510))
    }

    /** The library's threshold (#4452): 500 ms called a blocked request at 510 ms a dismissal. */
    @Test
    fun `the instant-return threshold is one second`() {
        assertEquals(1_000L, CAMERA_PROMPT_INSTANT_RETURN_MS)
        assertFalse(blocked(elapsedMs = CAMERA_PROMPT_INSTANT_RETURN_MS))
    }

    @Test
    fun `a grant is never a block`() {
        assertFalse(blocked(granted = true, rationaleBefore = true, elapsedMs = 0))
    }

    @Test
    fun `the card gives the reason only to someone who refused`() {
        assertEquals(ArCameraAskReason.NotAnswered, arCameraAskReason(shouldShowRationale = false))
        assertEquals(ArCameraAskReason.Refused, arCameraAskReason(shouldShowRationale = true))
    }

    private fun prompt(
        granted: Boolean = false,
        blocked: Boolean = false,
        shouldShowRationale: Boolean = false,
        promptLaunched: Boolean = false,
    ) = shouldAutoPromptForCamera(granted, blocked, shouldShowRationale, promptLaunched)

    private fun blocked(
        granted: Boolean = false,
        rationaleBefore: Boolean = false,
        rationaleAfter: Boolean = false,
        elapsedMs: Long,
    ) = cameraPromptBlocked(granted, rationaleBefore, rationaleAfter, elapsedMs)
}
