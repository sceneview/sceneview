package io.github.sceneview.web

import kotlin.math.abs
import kotlin.math.max

/**
 * Union-diagonal-stability gate for the library-level `autoCenterContent` pass.
 *
 * The auto-centre pass ([SceneView.refreshContentCentering]) must re-frame the
 * scene every time the loaded content's union bounding box materially grows —
 * i.e. each time an async-loaded model finishes and adds to the combined
 * extent — and may stop running (latch) only once that union diagonal has
 * settled across consecutive render frames.
 *
 * ## Why a first-stable-frame latch is wrong (#1540)
 *
 * The previous design latched `didCenter` on the **first** render frame with
 * non-degenerate bounds, and relied on [reset] being called from
 * `loadModel`/`addGeometry` at *call* time to re-arm. That works for models
 * that are all loaded up front, but an async model that finishes *after* a
 * sibling has already centred never re-centres: by the time its bounds are
 * populated the gate is already latched and no further `reset()` happens. The
 * result is the multi-model "bunched-in-the-corner" regression that #1391
 * fixed on iOS.
 *
 * ## The #1391 port
 *
 * This gate mirrors iOS's `lastFramedDiagonal` + `framingStabilityEpsilon`
 * logic: every frame the pass measures the union diagonal and calls
 * [shouldFrame]. The pass re-frames whenever the diagonal moved by more than
 * [STABILITY_EPSILON] (relative) since the last fitted diagonal — a freshly
 * streamed model always grows the union materially, so it always re-frames.
 * [recordFraming] latches `didCenter` only once a frame sees the diagonal
 * stable versus the previous fitted diagonal, so deferred async models keep
 * the pass alive until the whole scene has settled.
 *
 * ## Why an unconditional ceiling is also needed (#1633)
 *
 * The diagonal-stability latch alone is unbounded: a scene whose union diagonal
 * jitters by *more* than [STABILITY_EPSILON] on every single frame — an
 * animated / skeletal model, a physics demo, or two async models alternately
 * growing the union — satisfies [shouldFrame] forever and **never** latches.
 * The pass would then re-apply the camera / centroid every frame indefinitely,
 * fighting user interaction and burning a content-bounds walk per frame. So
 * [recordFraming] also latches unconditionally once it has framed
 * [MAX_FRAMING_PASSES] times: a scene that has not settled by then is genuinely
 * animated, and re-framing it every frame is wrong. This is the web port of the
 * Android `FramingGate` ceiling (#1629) — kept in parity.
 *
 * Isolating the state machine from any Filament.js binding keeps it directly
 * unit-testable without a WebGL context.
 */
internal class AutoCenterGate {

    /**
     * Fraction of the union diagonal below which a frame-to-frame change is
     * treated as sub-millimetre jitter rather than a genuine content change.
     * Tolerant enough to ignore floating-point noise, tight enough that a
     * freshly streamed model (which grows the union materially) always
     * re-frames. Mirrors iOS `framingStabilityEpsilon`.
     */
    companion object {
        const val STABILITY_EPSILON: Double = 0.01

        /**
         * Hard ceiling on how many times the gate will (re-)frame before
         * latching unconditionally. The diagonal-stability check handles a
         * scene that genuinely settles; this ceiling caps the pathological case
         * where the union diagonal jitters above [STABILITY_EPSILON] every
         * frame (animated / skeletal models, physics demos, alternating async
         * loads) and would otherwise re-frame forever. Generous enough to
         * absorb a handful of staggered async model loads (the #1391 case the
         * gate exists for), small enough that an animated scene stops fighting
         * the user almost immediately. Mirrors Android `FramingGate`'s
         * `MAX_FRAMING_PASSES` (#1629) — kept in parity.
         */
        const val MAX_FRAMING_PASSES: Int = 10
    }

    /** `true` once the union diagonal has stabilised and the pass has latched. */
    var didCenter: Boolean = false
        private set

    /**
     * Union diagonal of the content the pass last framed, or `< 0` when no
     * frame has run yet. Compared against the current diagonal to decide
     * whether a streamed model just landed.
     */
    private var lastFramedDiagonal: Double = -1.0

    /**
     * How many times [recordFraming] has framed the content since the last
     * [reset]. Once this reaches [MAX_FRAMING_PASSES] the gate latches
     * unconditionally so a perpetually-jittering scene stops re-framing.
     */
    private var framingPasses: Int = 0

    /**
     * Whether the centring pass should run this frame.
     *
     * Returns `false` once the pass has latched ([didCenter]) so subsequent
     * frames are a cheap no-op, or when the consumer disabled the feature /
     * no content is loaded.
     *
     * @param enabled the consumer-facing `autoCenterContent` toggle
     * @param hasContent whether any model is currently loaded
     */
    fun shouldRun(enabled: Boolean, hasContent: Boolean): Boolean =
        enabled && hasContent && !didCenter

    /**
     * Whether the pass should re-apply the centre + dolly for the given union
     * [diagonal] this frame.
     *
     * A freshly streamed model grows the union, moving the diagonal by more
     * than [STABILITY_EPSILON] — that always re-frames. Once the diagonal is
     * within tolerance of [lastFramedDiagonal] the scene has settled, so the
     * frame is skipped (the latch happens in [recordFraming]).
     */
    fun shouldFrame(diagonal: Double): Boolean {
        if (lastFramedDiagonal < 0.0) return true
        val delta = abs(diagonal - lastFramedDiagonal)
        return delta > STABILITY_EPSILON * max(diagonal, lastFramedDiagonal)
    }

    /**
     * Record that the pass framed the content at union [diagonal]. Latches
     * [didCenter] once either:
     *
     * - this diagonal is stable versus the previously recorded one — i.e. the
     *   scene has settled across consecutive ticks and no more async models are
     *   growing the union; or
     * - the gate has now framed [MAX_FRAMING_PASSES] times — a hard ceiling that
     *   stops a scene whose diagonal jitters above [STABILITY_EPSILON] every
     *   frame (animated / skeletal models, physics demos) from re-framing
     *   forever and fighting user interaction.
     *
     * Until one of those triggers the pass keeps running so deferred async
     * models re-frame the scene.
     */
    fun recordFraming(diagonal: Double) {
        val stable = lastFramedDiagonal >= 0.0 &&
            abs(diagonal - lastFramedDiagonal) <=
            STABILITY_EPSILON * max(diagonal, lastFramedDiagonal)
        lastFramedDiagonal = diagonal
        framingPasses++
        if (stable || framingPasses >= MAX_FRAMING_PASSES) didCenter = true
    }

    /**
     * Start a fresh content generation — clears the latch, the recorded
     * diagonal and the framing-pass counter so the next frame re-frames from
     * scratch.
     *
     * With the #1391 diagonal-stability logic the pass already re-frames
     * automatically whenever an async model grows the union, so an explicit
     * `reset()` from `loadModel`/`addGeometry` is no longer required for the
     * deferred-async case. It is still called when content is *replaced* so a
     * shrinking union (which would otherwise look stable) re-frames.
     */
    fun reset() {
        didCenter = false
        lastFramedDiagonal = -1.0
        framingPasses = 0
    }
}
