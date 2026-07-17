package io.github.sceneview.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression tests for [RenderGate] — the on-demand render gate that lets
 * SceneView Web skip the expensive GPU frame submit on a static scene (#2332).
 *
 * The headline safety property is that the gate **over-renders, never
 * under-renders**: it starts dirty (the first frame always paints), re-arms a
 * short settle tail after every change (so async uploads flush), and only goes
 * quiet once nothing has changed for that whole tail. A wrong decision can cost
 * a stale frame, never a frozen canvas. These tests pin that without a WebGL
 * context — same isolation approach as [AutoCenterGateTest].
 *
 * A small `settleBudget` is injected so the frame counts are deterministic and
 * obvious; production uses [RenderGate.SETTLE_FRAMES].
 */
class RenderGateTest {

    /** Drive one rendered frame: gate says render → we "draw" → consume budget. */
    private fun RenderGate.renderIf(active: Boolean): Boolean {
        val draw = shouldRender(active)
        if (draw) didRender()
        return draw
    }

    @Test
    fun freshGatePaintsTheFirstFrameEvenWithNoActivity() {
        val gate = RenderGate(settleBudget = 3)
        assertTrue(
            gate.shouldRender(active = false),
            "a fresh gate starts dirty so the very first frame must always paint",
        )
    }

    @Test
    fun idleSceneRendersExactlySettleBudgetFramesThenStops() {
        val gate = RenderGate(settleBudget = 3)
        var rendered = 0
        // Run well past the budget; an idle scene must stop on its own.
        repeat(10) { if (gate.renderIf(active = false)) rendered++ }
        assertEquals(3, rendered, "one dirty start must yield exactly settleBudget rendered frames")
        assertFalse(gate.shouldRender(active = false), "after the settle tail an idle scene is quiet")
    }

    @Test
    fun requestRenderReArmsAnIdleGate() {
        val gate = RenderGate(settleBudget = 2)
        repeat(5) { gate.renderIf(active = false) }
        assertFalse(gate.shouldRender(active = false), "gate should be idle before re-arming")

        gate.requestRender()
        var rendered = 0
        repeat(5) { if (gate.renderIf(active = false)) rendered++ }
        assertEquals(2, rendered, "requestRender() must re-arm a fresh settle budget")
    }

    @Test
    fun continuousActivityRendersEveryFrame() {
        val gate = RenderGate(settleBudget = 3)
        var rendered = 0
        repeat(100) { if (gate.renderIf(active = true)) rendered++ }
        assertEquals(100, rendered, "while active every frame must render — no gaps")
    }

    @Test
    fun activityStopFlushesASettleTailThenIdles() {
        val gate = RenderGate(settleBudget = 4)
        repeat(20) { gate.renderIf(active = true) }
        // Activity stops; the gate must keep painting a bounded tail, then quit.
        var tail = 0
        repeat(50) { if (gate.renderIf(active = false)) tail++ }
        assertTrue(tail in 1..4, "a stopped animation must flush a short settle tail (was $tail)")
        assertFalse(gate.shouldRender(active = false), "the gate must eventually idle after activity stops")
    }

    @Test
    fun pacingSkippedFrameDoesNotBurnSettleBudget() {
        // beginFrame() returning false (Filament frame-pacing) means no didRender:
        // the owed frame must survive so a streamed upload still gets its paint.
        val gate = RenderGate(settleBudget = 1)
        assertTrue(gate.shouldRender(active = false), "first owed frame")
        // …Filament skips the submit, so we DON'T call didRender().
        assertTrue(
            gate.shouldRender(active = false),
            "a frame skipped for pacing must not consume the settle budget",
        )
        gate.didRender()
        assertFalse(gate.shouldRender(active = false), "after the real submit the budget is spent")
    }

    @Test
    fun defaultSettleBudgetIsAGenerousPositiveTail() {
        assertEquals(30, RenderGate.SETTLE_FRAMES, "default settle tail")
        assertTrue(RenderGate.SETTLE_FRAMES > 0, "the settle tail must be positive or the gate could miss uploads")
    }
}
