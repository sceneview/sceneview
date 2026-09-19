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
 * @param isLoading       `modelLoader.progress < 1f`: Filament finalises texture uploads from
 *                        inside the frame loop, so a parked scene would render untextured.
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

/** Half a 60 Hz vsync. See [shouldPresentAtCap]. */
private const val HALF_VSYNC_NANOS = 8_000_000L

/**
 * Whether a [FrameRatePolicy.Capped] scene may present at [frameTimeNanos], given the timestamp of
 * the last presented frame (`0L` = none yet).
 *
 * The half-vsync of slack matters: a 30 fps cap on a 60 Hz display asks for one frame every
 * 33.3 ms while vsyncs land every 16.7 ms. Comparing strictly would reject the vsync at 33.3 ms by
 * a rounding hair on most frames and settle at one frame per *three* vsyncs — 20 fps, not the 30
 * that was asked for. The slack accepts the vsync that is within half a period of the deadline,
 * which is the standard way to phase-lock a cap onto a refresh rate it does not divide evenly.
 */
internal fun shouldPresentAtCap(fps: Int, frameTimeNanos: Long, lastPresentNanos: Long): Boolean {
    if (lastPresentNanos == 0L || fps <= 0) return true
    return frameTimeNanos - lastPresentNanos >= 1_000_000_000L / fps - HALF_VSYNC_NANOS
}

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
    is FrameRatePolicy.Capped -> policy.fps.toFloat()
    FrameRatePolicy.Continuous -> maxRefreshRate ?: 0f
    FrameRatePolicy.OnDemand -> if (active) maxRefreshRate ?: 0f else 0f
}
