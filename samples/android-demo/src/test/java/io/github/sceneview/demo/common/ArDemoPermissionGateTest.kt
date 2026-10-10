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

    /** #4139 (c): back from settings with the camera on, no "Try again" tap in between. */
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
    fun `a first dismissed dialog keeps Try again`() {
        assertFalse(blocked(elapsedMs = 3_000))
    }

    @Test
    fun `a first refusal that Android wants explained keeps Try again`() {
        assertFalse(blocked(rationaleAfter = true, elapsedMs = 3_000))
    }

    @Test
    fun `the second refusal is a block`() {
        assertTrue(blocked(rationaleBefore = true, elapsedMs = 3_000))
    }

    @Test
    fun `an answer with no dialog is a block`() {
        assertTrue(blocked(elapsedMs = CAMERA_PROMPT_INSTANT_RETURN_MS - 1))
    }

    @Test
    fun `a second unexplained refusal in a row is a block`() {
        assertTrue(blocked(elapsedMs = 3_000, earlierUnexplainedRefusals = 1))
    }

    @Test
    fun `a grant is never a block`() {
        assertFalse(blocked(granted = true, rationaleBefore = true, elapsedMs = 0))
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
        earlierUnexplainedRefusals: Int = 0,
    ) = cameraPromptBlocked(granted, rationaleBefore, rationaleAfter, elapsedMs, earlierUnexplainedRefusals)
}
