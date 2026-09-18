package io.github.sceneview.demo

import io.github.sceneview.math.Position
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * How [HeroOrbitCameraManipulator] gives the camera back to its idle orbit once the user has let
 * go of it.
 *
 * Both are **continuous**: the frame after the hand-back shows the pose of the frame before it.
 * What the manipulator did until #3642 was neither — it dropped the user's pose and resumed on the
 * authored one, which is the "changement de position brutal" that issue reports on every demo
 * sharing the helper.
 */
enum class HeroOrbitResume {
    /**
     * The turntable carries on **from the pose the user left**: same pivot, same distance, same
     * elevation, and the yaw keeps turning from the user's own azimuth. Nothing moves but the
     * rotation starting again — the behaviour of every model viewer's auto-rotate, and the default,
     * because a demo that lets the user frame a detail and then flies away from it 3 s later reads
     * as a camera reset (#3640).
     */
    KeepUserFraming,

    /**
     * The camera **eases back** onto the authored path. For a scene whose idle camera is a
     * choreography rather than a turntable — the Materials wall sweep — where the authored framing
     * is the point and an orbit carried on from behind the wall would show nothing.
     */
    ReturnToAuthoredPath,
}

/**
 * A camera on an orbit: the point it pivots around, and the arm from that pivot to the eye in
 * spherical terms. [yawDegrees] swings around world +Y from +Z, [elevation] is in radians above
 * the horizon — the convention [HeroOrbitCameraManipulator]'s authored pose already uses.
 */
internal data class OrbitFraming(
    val pivot: Position,
    val yawDegrees: Float,
    val elevation: Float,
    val distance: Float,
) {
    fun eye(): Position {
        val yaw = Math.toRadians(yawDegrees.toDouble()).toFloat()
        val horizontal = cos(elevation) * distance
        return Position(
            x = pivot.x + sin(yaw) * horizontal,
            y = pivot.y + sin(elevation) * distance,
            z = pivot.z + cos(yaw) * horizontal,
        )
    }
}

/**
 * The idle orbit's own pose: an eye [radius] away horizontally and [height] above [target], at
 * [yawDegrees] — the authored framing every demo passes to the manipulator, restated as an
 * [OrbitFraming] so the user's offset can be applied to it.
 */
internal fun authoredOrbitFraming(
    yawDegrees: Float,
    radius: Float,
    height: Float,
    target: Position,
): OrbitFraming = OrbitFraming(
    pivot = target,
    yawDegrees = yawDegrees,
    elevation = atan2(height, radius),
    distance = sqrt(radius * radius + height * height),
)

/** The orbit an [eye] looking at [pivot] is on — the inverse of [OrbitFraming.eye]. */
internal fun orbitFramingOf(eye: Position, pivot: Position): OrbitFraming {
    val dx = eye.x - pivot.x
    val dy = eye.y - pivot.y
    val dz = eye.z - pivot.z
    val horizontal = sqrt(dx * dx + dz * dz)
    return OrbitFraming(
        pivot = pivot,
        yawDegrees = Math.toDegrees(atan2(dx, dz).toDouble()).toFloat(),
        elevation = atan2(dy, horizontal),
        distance = sqrt(dx * dx + dy * dy + dz * dz),
    )
}

/**
 * The point a camera at [eye] looking along [forward] is pivoting around, [distance] away.
 *
 * The stock manipulator does not publish its pivot, only its transform. For a camera that was
 * orbited and zoomed the pivot is exactly this point; for one that was also panned it is the point
 * on the view ray at the same depth, which keeps the hand-back just as continuous.
 */
internal fun lookPoint(eye: Position, forward: Position, distance: Float): Position {
    val length = sqrt(forward.x * forward.x + forward.y * forward.y + forward.z * forward.z)
    if (!length.isFinite() || length <= 1e-6f || !distance.isFinite()) return eye
    val k = distance / length
    return Position(eye.x + forward.x * k, eye.y + forward.y * k, eye.z + forward.z * k)
}

/**
 * What the user changed, measured against the authored framing **at the instant of the
 * hand-back**. Stored as offsets rather than as a pose so the orbit keeps living underneath them:
 * the yaw goes on turning, a demo that re-fits its radius keeps the user's zoom *ratio*.
 */
internal data class OrbitFramingOffset(
    val pivot: Position,
    val yawDegrees: Float,
    val elevation: Float,
    val distanceScale: Float,
)

/** The offset that turns [authored] into [user]. The yaw takes the short way round. */
internal fun orbitFramingOffset(user: OrbitFraming, authored: OrbitFraming): OrbitFramingOffset =
    OrbitFramingOffset(
        pivot = Position(
            user.pivot.x - authored.pivot.x,
            user.pivot.y - authored.pivot.y,
            user.pivot.z - authored.pivot.z,
        ),
        yawDegrees = wrapDegrees(user.yawDegrees - authored.yawDegrees),
        elevation = user.elevation - authored.elevation,
        distanceScale = if (authored.distance > 1e-6f && user.distance.isFinite()) {
            user.distance / authored.distance
        } else {
            1f
        },
    )

/**
 * [this] authored framing with the user's [offset] applied at [weight]: `1` is the pose the user
 * left, `0` the authored pose, and everything between travels **around** the subject — the blend
 * is spherical, so easing back never cuts through the model the way a straight line between two
 * eyes on opposite sides would.
 */
internal fun OrbitFraming.offsetBy(offset: OrbitFramingOffset, weight: Float): OrbitFraming {
    val w = weight.coerceIn(0f, 1f)
    return OrbitFraming(
        pivot = Position(
            pivot.x + offset.pivot.x * w,
            pivot.y + offset.pivot.y * w,
            pivot.z + offset.pivot.z * w,
        ),
        yawDegrees = yawDegrees + offset.yawDegrees * w,
        // Shy of the poles, like the user-control clamp: straight up or down, `lookAt`'s fixed
        // world-up has no horizon left to hold on to and the picture rolls over.
        elevation = (elevation + offset.elevation * w).coerceIn(-MAX_ELEVATION, MAX_ELEVATION),
        distance = distance * (1f + (offset.distanceScale - 1f) * w),
    )
}

/** Folds an angle into `(-180, 180]`, so a blend over it turns the short way. */
internal fun wrapDegrees(degrees: Float): Float {
    if (!degrees.isFinite()) return 0f
    val folded = ((degrees % 360f) + 360f) % 360f
    return if (folded > 180f) folded - 360f else folded
}

/**
 * How much of the user's offset is left, [elapsedSeconds] into an ease back that lasts
 * [durationSeconds]: `1` at the start, `0` at the end, smoothstep between — zero velocity at both
 * ends, so the hand-back neither lurches off nor thuds onto the authored path.
 */
internal fun resumeBlendWeight(elapsedSeconds: Float, durationSeconds: Float): Float {
    if (durationSeconds <= 0f || !elapsedSeconds.isFinite()) return 0f
    val t = (elapsedSeconds / durationSeconds).coerceIn(0f, 1f)
    return 1f - t * t * (3f - 2f * t)
}

private val MAX_ELEVATION: Float = Math.toRadians(89.0).toFloat()

/** Default length of [HeroOrbitResume.ReturnToAuthoredPath]'s ease back onto the authored path. */
const val DEFAULT_RESUME_BLEND_MILLIS: Long = 1_200L

/**
 * How long the ease back over [offset] really takes when [millis] was asked for: the same, stretched
 * until the way home is not covered at a whip. A camera left a quarter turn away came back in
 * 1.2 s — continuous, eased at both ends, and still ~100°/s in the middle, which reads as a jump.
 * Smoothstep peaks at 1.5× its mean speed, so the turn is held under
 * [RESUME_PEAK_DEGREES_PER_SECOND] and the dolly under [RESUME_PEAK_ZOOM_PER_SECOND] — up to
 * [MAX_RESUME_STRETCH]× [millis], past which the camera would seem not to answer at all.
 */
internal fun resumeBlendMillisFor(offset: OrbitFramingOffset, millis: Long): Long {
    if (millis <= 0L) return millis
    val turnDegrees = max(
        abs(offset.yawDegrees),
        abs(Math.toDegrees(offset.elevation.toDouble()).toFloat()),
    )
    val zoom = offset.distanceScale.takeIf { it.isFinite() && it > 0f }?.let { abs(ln(it)) } ?: 0f
    val seconds = SMOOTHSTEP_PEAK *
        max(turnDegrees / RESUME_PEAK_DEGREES_PER_SECOND, zoom / RESUME_PEAK_ZOOM_PER_SECOND)
    if (!seconds.isFinite()) return millis
    return (seconds * 1_000f).toLong().coerceIn(millis, millis * MAX_RESUME_STRETCH)
}

/** Peak speed of a smoothstep, in units of its mean speed. */
private const val SMOOTHSTEP_PEAK = 1.5f

/** The fastest a hand-back turns the camera round the subject, at the middle of its ease. */
internal const val RESUME_PEAK_DEGREES_PER_SECOND = 45f

/** The fastest a hand-back dollies, in natural-log distance per second (≈ ×2.2 each second). */
internal const val RESUME_PEAK_ZOOM_PER_SECOND = 0.8f

/** A hand-back never lasts more than this many times the length it was given. */
internal const val MAX_RESUME_STRETCH = 3L

/**
 * The user's framing while the idle orbit carries it: held at full weight for good, or easing
 * away. The ease runs on [nanoTime] rather than on frame deltas so that a manipulator nobody is
 * drawing from — Materials swaps between two — still finishes its way home in the meantime.
 */
internal class CarriedFraming(private val nanoTime: () -> Long) {
    private var offset: OrbitFramingOffset? = null
    private var easeStartNanos = 0L
    private var easeNanos = 0L

    val isEmpty: Boolean get() = offset == null

    val isEasing: Boolean get() = offset != null && easeNanos > 0L

    /** Carry [offset] at full weight until told otherwise. */
    fun hold(offset: OrbitFramingOffset) {
        this.offset = offset
        easeNanos = 0L
    }

    /**
     * Start easing what is carried away over [millis] — or, with [paced], over as long as the way
     * home needs ([resumeBlendMillisFor]). A demo that times the ease against an animation of its
     * own asks for the exact length. Nothing carried, nothing to ease.
     */
    fun easeBack(millis: Long, paced: Boolean = false) {
        val held = offset ?: return
        easeStartNanos = nanoTime()
        easeNanos = (if (paced) resumeBlendMillisFor(held, millis) else millis) * NANOS_PER_MILLI
    }

    fun clear() {
        offset = null
        easeNanos = 0L
    }

    /**
     * The [authored] framing with what is left of the user's on top — or `null` when nothing is,
     * so the caller can stay on its own exact authored formula. [authored] is only evaluated when
     * there is something to put on it.
     */
    fun over(authored: () -> OrbitFraming): OrbitFraming? {
        val held = offset ?: return null
        if (easeNanos <= 0L) return authored().offsetBy(held, 1f)
        val elapsed = nanoTime() - easeStartNanos
        if (elapsed >= easeNanos) {
            // Landed: the camera is on the authored path again.
            clear()
            return null
        }
        val weight = resumeBlendWeight(elapsed / NANOS_PER_SECOND, easeNanos / NANOS_PER_SECOND)
        return authored().offsetBy(held, weight)
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
        const val NANOS_PER_SECOND = 1e9f
    }
}

internal const val FULL_TURN_DEGREES: Float = 360f

/**
 * The idle turntable's yaw, integrated **by the camera's own frame clock** with an eased angular
 * speed.
 *
 * It replaces a Compose `Animatable` looping a linear tween, which had two defects every demo
 * sharing the helper showed on screen:
 *
 * - **Velocity cuts.** A linear tween is at full speed from its first frame and at zero the frame
 *   it is cancelled, so the orbit lurched off at open, after every hand-back from a gesture, and
 *   stopped dead on pause. [advance] eases the speed towards its goal instead — the position was
 *   already continuous (#3642), now the velocity is too.
 * - **A second clock.** The tween ticked in its own coroutine, so the render loop read a yaw one
 *   frame stale: a long frame moved one tick and the *next* short one carried the catch-up. Here
 *   the yaw advances in the manipulator's `update(deltaTime)`, the frame that draws it.
 *
 * Pausing keeps the angle, exactly like the tween it replaces (#3640): only [reset] re-zeroes it.
 */
class OrbitSpin(private val easeSeconds: Float = DEFAULT_SPIN_EASE_SECONDS) {
    /** Current yaw, in degrees, folded into `[0, 360)`. */
    var yawDegrees: Float = 0f
        private set

    /** Current angular speed, in degrees per second. */
    var degreesPerSecond: Float = 0f
        private set

    /**
     * Move on by [deltaSeconds], easing the speed towards [goalDegreesPerSecond] (`0` to pause).
     * A frame longer than [MAX_STEP_SECONDS] — the app coming back from the background — counts as
     * that much and no more, so the orbit never leaps on resume.
     */
    fun advance(deltaSeconds: Float, goalDegreesPerSecond: Float) {
        if (!deltaSeconds.isFinite() || deltaSeconds <= 0f) return
        val dt = deltaSeconds.coerceAtMost(MAX_STEP_SECONDS)
        val goal = if (goalDegreesPerSecond.isFinite()) goalDegreesPerSecond else 0f
        val before = degreesPerSecond
        // Exact solution of "the speed closes a fixed share of its gap per unit of time", and its
        // exact integral: the pose depends on the clock, not on how the frames sliced it.
        val decay = if (easeSeconds <= 0f) 0f else kotlin.math.exp(-dt / easeSeconds)
        degreesPerSecond = goal + (before - goal) * decay
        val turned = yawDegrees + goal * dt + (before - goal) * easeSeconds.coerceAtLeast(0f) * (1f - decay)
        yawDegrees = ((turned % FULL_TURN_DEGREES) + FULL_TURN_DEGREES) % FULL_TURN_DEGREES
    }

    /** Back to [yawDegrees], at rest. Only for a camera nobody is looking through. */
    fun reset(yawDegrees: Float = 0f) {
        this.yawDegrees = ((yawDegrees % FULL_TURN_DEGREES) + FULL_TURN_DEGREES) % FULL_TURN_DEGREES
        degreesPerSecond = 0f
    }

    companion object {
        /** Time constant of the speed ease: ~95 % of the way to the goal after three of these. */
        const val DEFAULT_SPIN_EASE_SECONDS: Float = 0.45f

        const val MAX_STEP_SECONDS: Float = 0.25f

        /** Angular speed of one full turn per [fullTurnMillis]. */
        fun degreesPerSecond(fullTurnMillis: Int): Float =
            if (fullTurnMillis > 0) FULL_TURN_DEGREES * 1_000f / fullTurnMillis else 0f
    }
}
