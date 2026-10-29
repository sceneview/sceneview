package io.github.sceneview.camera

import dev.romainguy.kotlin.math.length
import io.github.sceneview.animation.Easing
import kotlin.math.PI
import kotlin.math.sin

/**
 * The easings a camera move reads well with. Each is a `(Float) -> Float` over `[0, 1]`, the
 * same shape as [io.github.sceneview.animation.Easing], so any of those fits too.
 */
object CameraEasing {
    /**
     * `ease-expressive` of DESIGN.md — `cubic-bezier(0.2, 0, 0, 1)`: leaves gently, lands softly.
     * The default of every camera move here; a reveal, a fly-to and a plan toggle all use it.
     */
    val Expressive: (Float) -> Float = Easing.cubicBezier(0.2f, 0f, 0f, 1f)

    /** Constant speed: a tour that must not slow down between its keyframes. */
    val Linear: (Float) -> Float = Easing.Linear

    /** Symmetric in and out: a move that starts and ends at rest, with no overshoot. */
    val InOut: (Float) -> Float = Easing.EaseInOutSine
}

/**
 * One camera move, from a shot to another over a duration.
 *
 * Between two shots of the same subject the camera travels the arc around it. Between two
 * subjects — another room, another object — it also **flies over**: it pulls back by
 * [flyOver] times the distance between the two targets at mid-flight and comes back down, the
 * way a navigation app zooms out to show the way before it lands, so the move never cuts
 * through walls and the eye can see where it is going. Set [flyOver] to `0` for a straight
 * carry.
 *
 * ```kotlin
 * val move = CameraTransition(from = current, to = plan, durationSeconds = 0.7f)
 * cameraNode.transform = move.shotAt(seconds).transform()
 * ```
 *
 * @property easing  Progress curve over `[0, 1]`; [CameraEasing.Expressive] by default.
 * @property flyOver Share of the targets' separation added to the distance at mid-flight.
 */
data class CameraTransition(
    val from: CameraShot,
    val to: CameraShot,
    val durationSeconds: Float,
    val easing: (Float) -> Float = CameraEasing.Expressive,
    val flyOver: Float = DEFAULT_FLY_OVER,
) {
    /** The shot [seconds] into the move; the ends are held outside `[0, duration]`. */
    fun shotAt(seconds: Float): CameraShot {
        val progress = if (durationSeconds <= 0f) 1f else (seconds / durationSeconds).coerceIn(0f, 1f)
        return cameraTransition(from, to, progress, easing, flyOver)
    }

    /** `true` once [seconds] is past the end. */
    fun isFinishedAt(seconds: Float): Boolean = seconds >= durationSeconds

    companion object {
        /** Half the targets' separation, pulled back at mid-flight: enough to read a room change. */
        const val DEFAULT_FLY_OVER = 0.5f
    }
}

/**
 * The shot [progress] (`0..1`) of the way from [from] to [to] — the pure maths of
 * [CameraTransition], for a caller that drives progress itself (a scroll, a slider).
 *
 * [easing] shapes the progress; [flyOver] pulls the camera back at mid-flight by that share of
 * the distance between the two targets (a sine bump, zero at both ends, so the ends are exact).
 */
fun cameraTransition(
    from: CameraShot,
    to: CameraShot,
    progress: Float,
    easing: (Float) -> Float = CameraEasing.Expressive,
    flyOver: Float = CameraTransition.DEFAULT_FLY_OVER,
): CameraShot {
    val t = easing(progress.coerceIn(0f, 1f)).coerceIn(0f, 1f)
    val shot = CameraShot.lerp(from, to, t)
    val travel = length(to.target - from.target)
    if (flyOver <= 0f || travel <= 0f) return shot
    val lift = flyOver * travel * sin(PI * t).toFloat()
    return shot.copy(distance = shot.distance + lift)
}
