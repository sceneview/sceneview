package io.github.sceneview.web

/**
 * On-demand render gate for SceneView Web (#2332).
 *
 * The Filament render loop ([SceneView.renderLoop]) still ticks every
 * `requestAnimationFrame` — input handling, camera/animation bookkeeping and
 * `engine.execute()` run unconditionally so nothing is ever missed — but the
 * *expensive* part, the GPU frame submit (`beginFrame` / `renderView` /
 * `endFrame`, i.e. the full SSAO + bloom + TAA post-processing pipeline), is
 * gated on whether anything actually changed. A genuinely static scene then
 * costs one near-empty rAF callback per frame instead of a full pipeline pass
 * 60 times a second.
 *
 * ## Why this can never freeze the canvas
 *
 * Two independent safety properties keep the #1 risk — a frozen / blank canvas
 * — off the table:
 *
 * 1. **The rAF loop itself is never gated.** Only the draw call is conditional,
 *    so a wrongly-`false` gate decision costs at most one stale frame; the very
 *    next tick re-evaluates and paints the instant anything moves.
 * 2. **It starts dirty and over-renders, never under-renders.** A fresh gate is
 *    [dirty], so the first frame always paints. After *any* change the gate owes
 *    the GPU [settleBudget] further frames (a short tail) so asynchronous
 *    Filament texture/buffer uploads kicked off by `engine.execute()` have time
 *    to flush onto a freshly-drawn surface before the gate goes quiet.
 *
 * Isolating the decision from every Filament.js binding keeps it directly
 * unit-testable without a WebGL context — same approach as [AutoCenterGate].
 */
internal class RenderGate(private val settleBudget: Int = SETTLE_FRAMES) {

    companion object {
        /**
         * Frames the gate keeps drawing after the last change before it idles.
         *
         * A short over-render tail (~half a second at 60 fps) covers the window
         * where `engine.execute()` is still flushing asynchronous texture /
         * vertex-buffer uploads for content that just settled — without it the
         * gate could stop one frame before a late upload lands, leaving an
         * untextured model on screen until the next interaction. Erring toward a
         * few extra frames is cheap and removes that class of bug entirely.
         */
        const val SETTLE_FRAMES: Int = 30
    }

    /** Set by [requestRender]; forces at least one more frame plus the tail. */
    private var dirty: Boolean = true

    /** Frames still owed to the GPU. `> 0` ⇒ [shouldRender] returns `true`. */
    private var owed: Int = settleBudget

    /**
     * Mark the scene dirty so the loop submits at least one more GPU frame (plus
     * the [settleBudget] tail). Cheap and idempotent — a single boolean — so
     * callers never need to debounce. Call from any mutation that changes what
     * the next frame should look like.
     */
    fun requestRender() {
        dirty = true
    }

    /**
     * Decide whether to submit a GPU frame this tick.
     *
     * @param active `true` when something is *currently* changing every frame —
     *   the camera moved, an animation is playing, an async load is in flight,
     *   the auto-center pass is still running, or a resize was just detected.
     *   While `active` (or [dirty]) the gate re-arms its full [settleBudget],
     *   so continuous activity renders continuously and only the trailing idle
     *   frames count down.
     * @return `true` if this frame should be drawn.
     */
    fun shouldRender(active: Boolean): Boolean {
        if (dirty || active) {
            owed = settleBudget
            dirty = false
        }
        return owed > 0
    }

    /**
     * Record that a GPU frame was actually submitted, consuming one owed frame.
     * Call only on a successful `beginFrame` — a frame Filament skipped for
     * pacing must not burn the settle budget, or a streamed upload could be
     * dropped before it paints.
     */
    fun didRender() {
        if (owed > 0) owed--
    }
}
