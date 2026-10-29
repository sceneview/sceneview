package io.github.sceneview.demo.demos

import org.junit.Assert.assertEquals
import org.junit.Test

/** Back from the floor plan returns to the screen whose export sheet opened it (#4599). */
class PlanReturnTest {

    @Test
    fun `opened from the replay, back returns to the replay even when a result exists`() {
        assertEquals(RerunScreen.Replay, planReturnOf(RerunScreen.Replay, hasResult = true))
    }

    @Test
    fun `opened from the result, back returns to the result`() {
        assertEquals(RerunScreen.Model, planReturnOf(RerunScreen.Model, hasResult = true))
    }

    @Test
    fun `opened with no opener, back goes to the result when there is one, else the replay`() {
        assertEquals(RerunScreen.Model, planReturnOf(null, hasResult = true))
        assertEquals(RerunScreen.Replay, planReturnOf(null, hasResult = false))
    }

    @Test
    fun `the plan never returns to itself`() {
        assertEquals(RerunScreen.Replay, planReturnOf(RerunScreen.Plan, hasResult = false))
    }
}
