// Render-loop helpers for the `SceneView` composable. They live beside it rather than inside
// SceneView.kt because that file sits at detekt's `allowedFunctionsPerFile` ceiling (25) — one more
// declaration there fails the Lint leg, and raising the ceiling to fit a helper would relax the
// rule for every file in the module.
package io.github.sceneview

import androidx.compose.runtime.State
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first

/**
 * Suspends until [shouldRender] reads `true`, then returns. Returns immediately — without
 * allocating — when it already reads `true`, which is the per-frame hot path.
 *
 * This is the parked half of [SceneView]'s render loop, extracted so the parking *semantics* are
 * unit-testable without a Filament engine or a device (`FrameRatePolicyTest`).
 *
 * The distinction it pins is the whole point of #3108: pausing must **park** the coroutine, not
 * poll it. A `while (!value) delay(16)` loop stops the GPU work but keeps the CPU waking 60x a
 * second forever on a scene that never changes, which on the reporting devices is a large part of
 * what makes an idle 3D screen run hot. Reading through a [State] and suspending on
 * [snapshotFlow] means an idle scene schedules nothing at all until a snapshot apply writes the
 * flag back to `true`.
 *
 * [shouldRender] is deliberately wider than "the scene changed": the loop must also wake when the
 * current surface is still owed a frame, because a newly created swap chain holds no pixels and a
 * park would otherwise leave it blank indefinitely (#3109), and it must never park at all under a
 * policy that draws unconditionally. Pass a [State] that already folds those together — this
 * function only knows "may I sleep?".
 */
internal suspend fun awaitRenderingEnabled(shouldRender: State<Boolean>) {
    if (shouldRender.value) return
    snapshotFlow { shouldRender.value }.first { it }
}

/**
 * Folds the per-frame **pull** invalidation sources into the single `active` flag that
 * [FrameRateGate.shouldRender] consumes.
 *
 * Push sources (a touch, a transform write, a node added to the scene) call
 * [FrameRateGate.requestRender] once and are done. Pull sources cannot: they are *states* that stay
 * true across many frames — a finger on the screen, a glTF animation playing, a video decoding, an
 * async load still uploading textures. Asking each of them "are you still going?" once per frame is
 * what keeps the scene at full cadence for the whole gesture or animation and lets it settle
 * exactly once they all stop.
 *
 * Parameters are booleans rather than the objects themselves so this stays a pure function, unit
 * testable without a Filament engine (`FrameRateGateTest`).
 *
 * @param gestureInFlight A touch stream is live (between `ACTION_DOWN` and `ACTION_UP`/`CANCEL`).
 * @param cameraMoved     The camera manipulator wrote a different transform than the previous
 *                        frame — this is what keeps the fling after a flick alive, since no touch
 *                        event arrives while it decelerates. Computed from the single
 *                        `getTransform()` the loop already made: that call is allowed to have side
 *                        effects, so it is never issued a second time to answer this question.
 * @param cameraPending   The manipulator says it still owes frames although the camera is not
 *                        moving — an ease in flight, or a turntable counting down to hand the
 *                        camera back. See
 *                        [io.github.sceneview.gesture.CameraGestureDetector.CameraManipulator.isFrameActive]:
 *                        a motionless countdown advanced from `update()` would otherwise let the
 *                        loop park, which stops `update()`, which strands the countdown.
 * @param hasActiveNode   Any node in the tree reports [io.github.sceneview.node.Node.isFrameActive].
 * @param isLoading       [io.github.sceneview.loaders.ModelLoader.isLoading]: Filament finalises
 *                        texture uploads from inside the frame loop, so a parked scene would
 *                        render untextured. Not `progress < 1f` — see [isAsyncLoadPending] for why
 *                        that alone held every procedural scene at full cadence forever.
 * @param isMirroring     A [io.github.sceneview.utils.SurfaceMirrorer] has at least one target;
 *                        a recording that drops to 0 fps on an idle scene is a broken recording.
 * @param framingPending  Auto-center or auto-fit has not latched yet — both need presented frames
 *                        to converge on a content union that async loads are still growing.
 */
internal fun isSceneFrameActive(
    gestureInFlight: Boolean,
    cameraMoved: Boolean,
    cameraPending: Boolean,
    hasActiveNode: Boolean,
    isLoading: Boolean,
    isMirroring: Boolean,
    framingPending: Boolean
): Boolean = gestureInFlight || cameraMoved || cameraPending || hasActiveNode || isLoading ||
        isMirroring || framingPending

/**
 * The `framingPending` argument of [isSceneFrameActive]: whether a framing pass still owes work
 * that only a presented frame can advance.
 *
 * A guard on a *pending* state has one rule, and both halves of this expression exist because it
 * was broken: **its condition must be exactly the condition of the work it waits for.** A framing
 * pass that never runs never latches, and a guard that only asks "has it latched?" then answers
 * "pending" for the lifetime of the view — which re-arms the settle budget every tick, holds the
 * scene at full cadence and votes the display maximum, with nothing visibly wrong on screen.
 *
 * Two ways that happened here:
 *
 * - **auto-fit with a camera manipulator.** `SceneView` only runs the auto-fit pass when there is
 *   no manipulator — an orbit manipulator owns the camera transform every frame, so a static fit
 *   cannot coexist with it. But `rememberCameraManipulator()` is the *default*, so every scene
 *   that set `autoFitContent = true` waited forever on a pass that was never going to run. Hence
 *   [hasCameraManipulator].
 * - **a pass with nothing to frame.** An empty scene, a scene whose nodes are all
 *   `isVisible = false`, a lone light: the pass bails out before latching, every tick, forever —
 *   and `autoCenterContent` is on by default. Hence the states report
 *   [SceneAutoCenterState.isFramingPending] rather than `!didCenter`; see [FramingGate.isPending].
 *   Content arriving is a push invalidation, so nothing is lost by resting.
 */
internal fun isFramingPending(
    autoCenterContent: Boolean,
    autoCenterPending: Boolean,
    autoFitContent: Boolean,
    hasCameraManipulator: Boolean,
    autoFitPending: Boolean
): Boolean = (autoCenterContent && autoCenterPending) ||
        (autoFitContent && !hasCameraManipulator && autoFitPending)

/**
 * The `isLoading` argument of [isSceneFrameActive]: whether an asynchronous resource load still
 * owes work that only a presented frame can advance.
 *
 * Same rule as [isFramingPending], broken the same way: **a pending guard's condition must be
 * exactly the condition of the work it waits for.** This one was written as
 * `modelLoader.progress < 1f` alone, and Filament's `ResourceLoader.asyncGetLoadProgress()` returns
 * **0** — not 1 — for a loader that was never asked to load anything. So every scene built from
 * geometry and materials rather than from a glTF file (the `materials` and `debug-overlay` demos,
 * any procedural scene, any scene whose models are already resident) read "0 % loaded, still
 * loading" for the lifetime of the view: the settle budget was re-armed every tick, the scene held
 * full cadence, and the display vote stayed at the panel maximum — with nothing visibly wrong on
 * screen, because the picture was correct, just redrawn 60 times a second for no reason.
 *
 * A progress fraction cannot answer "is a load in flight?" on its own, because 0 is both "nothing
 * started" and "started, nothing done yet". [loadStarted] is the missing half, latched by
 * [io.github.sceneview.loaders.ModelLoader] across `asyncBeginLoad` / `asyncUpdateLoad`.
 */
internal fun isAsyncLoadPending(loadStarted: Boolean, progress: Float): Boolean =
    loadStarted && progress < 1f

/**
 * The vsync period, in nanoseconds, for a display running at [refreshRate] Hz — or `0` when the
 * rate is unknown (no display attached yet), which [shouldPresentAtCap] reads as "compare the
 * deadline strictly, no phase lock".
 */
internal fun vsyncPeriodNanos(refreshRate: Float?): Long =
    if (refreshRate != null && refreshRate > 0f) (1_000_000_000.0 / refreshRate).toLong() else 0L

/**
 * Whether a [FrameRatePolicy.Capped] scene may present at [frameTimeNanos], given the timestamp of
 * the last presented frame (`0L` = none yet) and the display's real [vsyncPeriodNanos].
 *
 * A cap can only ever be met by presenting on a **whole number of vsyncs**: nothing else exists to
 * present on. So the requested period is rounded to a whole number of vsyncs, and it is rounded
 * **up**, because `Capped(fps)` promises "never faster than fps". `Capped(90)` on a 120 Hz panel
 * therefore runs at 60, not at 120: 90 is not reachable there, and of the two reachable neighbours
 * only 60 honours the promise.
 *
 * Rounding up needs a jitter tolerance or it would round the wrong way on the common exact cases:
 * a 30 fps cap asks for 33.333 ms while two vsyncs of a "60 Hz" panel measure 33.334 ms — or
 * 33.367 ms on a panel that is really 59.94 Hz — and a strict `ceil` would push those to *three*
 * vsyncs, i.e. 20 fps from a 30 fps request. A quarter of a vsync absorbs that and nothing more.
 *
 * This used to be a hard-coded `8_000_000L`, "half a 60 Hz vsync", subtracted from the requested
 * period. On a 120 Hz panel that slack (8 ms) was larger than the whole vsync (8.33 ms), so every
 * vsync cleared the deadline and the cap capped nothing: `Capped(90)` rendered 120, `Capped(60)`
 * on a 90 Hz panel rendered 90. The rule was general — a constant tolerance stops working as soon
 * as `fps > refresh / 2` — and the tests only ever ran it at 60 Hz.
 */
internal fun shouldPresentAtCap(
    fps: Int,
    frameTimeNanos: Long,
    lastPresentNanos: Long,
    vsyncPeriodNanos: Long
): Boolean {
    if (lastPresentNanos == 0L || fps <= 0) return true
    val requestedPeriod = 1_000_000_000L / fps
    val elapsed = frameTimeNanos - lastPresentNanos
    if (vsyncPeriodNanos <= 0L) return elapsed >= requestedPeriod
    val jitter = vsyncPeriodNanos / 4
    val vsyncs = ceilDiv(requestedPeriod - jitter, vsyncPeriodNanos).coerceAtLeast(1L)
    return elapsed >= vsyncs * vsyncPeriodNanos - jitter
}

private fun ceilDiv(value: Long, divisor: Long): Long =
    if (value <= 0L) 0L else (value + divisor - 1L) / divisor

/**
 * The cadence, in frames per second, that the scene asks the display for — see
 * [SceneRenderer.setFrameRateVote], which turns this into a `Surface.setFrameRate` call.
 *
 * `0f` means **no preference**: the vote is withdrawn and the system picks, which on a variable
 * refresh rate panel is what lets it drop to its idle mode. It is the resting value, not a request
 * for zero frames.
 *
 * @param maxRefreshRate The highest mode the display advertises, or `null` when unknown — a
 *                       [FrameRatePolicy.Continuous] scene on an unknown display votes nothing
 *                       rather than guessing a number the panel may not support.
 */
internal fun frameRateVote(
    policy: FrameRatePolicy,
    active: Boolean,
    maxRefreshRate: Float?
): Float = when (policy) {
    // Never vote above what the panel can do: `Capped(240)` on a 120 Hz display is a request the
    // system would clamp anyway, and an out-of-range vote is worth no more than an honest one.
    is FrameRatePolicy.Capped ->
        maxRefreshRate?.let { minOf(policy.fps.toFloat(), it) } ?: policy.fps.toFloat()
    FrameRatePolicy.Continuous -> maxRefreshRate ?: 0f
    FrameRatePolicy.OnDemand -> if (active) maxRefreshRate ?: 0f else 0f
}
