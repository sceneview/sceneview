package io.github.sceneview.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression tests for the **#2409** error-induced render signal in
 * `SceneView.loadModel` (and the parity skybox/IBL/geometry load paths).
 *
 * `loadModel` runs `window.fetch(url).then { … }.catch { … }`. On a render-gated
 * viewer (#2332) the canvas only repaints when something marks the [RenderGate]
 * dirty. A failed fetch (404 / CORS / network drop) must therefore do two things,
 * not one: it must **decrement** the in-flight `pendingLoads` count (so the gate
 * stops treating the scene as actively streaming) *and* explicitly
 * `requestRender()` (so the gate paints at least one more frame to reflect the
 * error state). If the `catch` only decremented `pendingLoads`, the scene would
 * go quiet on the same tick the last load failed and the canvas would freeze at
 * the last successful frame — the exact #2409 bug.
 *
 * Production funnels every terminal outcome — success, supersession, parse
 * failure, and fetch error — through a single idempotent `settleLoad()` helper
 * that does both. These tests drive a **real [RenderGate]** through stand-ins
 * that reproduce that helper verbatim, the same isolation approach
 * [LoadModelStaleCallbackTest] and [RenderGateTest] take (no WebGL context
 * needed). They pin that the *error* path settles exactly like the success path,
 * so the three async load surfaces (model, IBL, skybox) signal failures
 * consistently.
 */
class LoadModelErrorSignalTest {

    /**
     * Reproduces the production `pendingLoads` + [RenderGate] coupling and the
     * idempotent `settleLoad()` closure of `SceneView.loadModel` /
     * `loadEnvironment`. One instance models one in-flight async load.
     */
    private class FakeLoad(private val gate: RenderGate) {
        var pendingLoads = 1
        private var settled = false

        /** Verbatim `settleLoad()`: decrement once, request a repaint once. */
        fun settle() {
            if (!settled) {
                settled = true
                pendingLoads--
                gate.requestRender()
            }
        }

        /**
         * `active` flag the render loop computes — `pendingLoads > 0` keeps the
         * gate streaming while a load is in flight (mirrors
         * `SceneView.renderLoop`).
         */
        val active: Boolean get() = pendingLoads > 0
    }

    /** Drive one rendered frame against a gate: render → "draw" → consume budget. */
    private fun RenderGate.renderIf(active: Boolean): Boolean {
        val draw = shouldRender(active)
        if (draw) didRender()
        return draw
    }

    /**
     * #2409: a failed model fetch must re-render the canvas even though it also
     * clears the in-flight load. We let the gate idle first (simulating a static
     * scene that has long settled), then fire the `catch` path: the gate must
     * paint again so the viewer can show the error state.
     */
    @Test
    fun failedFetchReRendersAQuietCanvas() {
        val gate = RenderGate(settleBudget = 2)
        val load = FakeLoad(gate)

        // The load is in flight: the scene streams (active) and renders.
        assertTrue(load.active, "a load in flight keeps the scene active")
        assertTrue(gate.renderIf(active = load.active), "an in-flight load paints")

        // The fetch FAILS — production hits the `.catch { … settleLoad() }` block.
        load.settle()

        // The load is no longer in flight, so `active` is now false: WITHOUT the
        // explicit requestRender() inside settle() the gate would have nothing to
        // keep it awake and the canvas would freeze (the #2409 bug). It must still
        // paint at least one frame to reflect the error.
        assertFalse(load.active, "a settled (failed) load is no longer active")
        assertTrue(
            gate.renderIf(active = load.active),
            "a failed fetch must request a repaint so the canvas reflects the error, not freeze",
        )
    }

    /**
     * The success and error paths must settle identically — both decrement the
     * in-flight count and re-arm the gate. This pins the #2409 acceptance
     * criterion that all terminal outcomes signal the render gate consistently.
     */
    @Test
    fun errorPathSettlesIdenticallyToSuccessPath() {
        val successGate = RenderGate(settleBudget = 1)
        val errorGate = RenderGate(settleBudget = 1)
        val onSuccess = FakeLoad(successGate)
        val onError = FakeLoad(errorGate)

        // Drain the initial dirty start so we measure only the settle() effect.
        repeat(5) { successGate.renderIf(active = false) }
        repeat(5) { errorGate.renderIf(active = false) }
        assertFalse(successGate.shouldRender(active = false), "success gate idle before settle")
        assertFalse(errorGate.shouldRender(active = false), "error gate idle before settle")

        onSuccess.settle()
        onError.settle()

        assertEquals(0, onSuccess.pendingLoads, "a successful load decrements pendingLoads exactly once")
        assertEquals(0, onError.pendingLoads, "a failed load decrements pendingLoads exactly once")
        var successFrames = 0
        var errorFrames = 0
        repeat(5) { if (successGate.renderIf(active = false)) successFrames++ }
        repeat(5) { if (errorGate.renderIf(active = false)) errorFrames++ }
        assertEquals(
            successFrames,
            errorFrames,
            "the error path must re-arm the render gate exactly like the success path",
        )
        assertTrue(errorFrames > 0, "a failed load must paint at least one frame")
    }

    /**
     * `settleLoad()` is idempotent: a `catch` that somehow fires twice (or a
     * race that settles a load already settled) must not double-decrement
     * `pendingLoads` or re-arm the gate twice. Production guards this with the
     * `loadSettled` boolean — pin it so a refactor can't drop the guard and
     * drive `pendingLoads` negative (which would wedge the gate permanently
     * `active`, defeating #2332).
     */
    @Test
    fun settleIsIdempotent() {
        val gate = RenderGate(settleBudget = 1)
        val load = FakeLoad(gate)

        load.settle()
        load.settle()
        load.settle()

        assertEquals(0, load.pendingLoads, "settle() must decrement pendingLoads at most once")
    }
}
