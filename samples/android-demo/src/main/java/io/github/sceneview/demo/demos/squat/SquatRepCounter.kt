package io.github.sceneview.demo.demos.squat

import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Where the user is inside one squat, as the coach sees it.
 *
 * Pure Kotlin on purpose: the counter is fed synthetic knee-angle sequences by
 * `SquatRepCounterTest`, with no camera, no MediaPipe and no Android in the loop.
 */
enum class SquatPhase {
    /** No usable leg in the frame yet, or lost for long enough that the rep was abandoned. */
    NO_BODY,

    /** Legs straight (knee angle above [SquatRepCounter.STAND_ANGLE]). */
    STANDING,

    /** Bending, not yet deep enough to count. */
    GOING_DOWN,

    /** Deep enough — the rep counts once the user stands back up. */
    DEEP_ENOUGH,
}

/** The one line of coaching the screen shows. Nothing to say → [NONE]. */
enum class SquatFeedback {
    NONE,

    /** Bending but still above the depth line: "Lower". */
    GO_LOWER,

    /** Depth reached: "Good depth — stand up". */
    STAND_UP,

    /** A rep was just counted. Held until the next descent. */
    REP_COUNTED,

    /** The user stood back up without reaching depth — that one did not count. */
    TOO_SHALLOW,
}

/** Snapshot of the coach, emitted after every pose sample. */
data class SquatCoachState(
    val reps: Int = 0,
    val phase: SquatPhase = SquatPhase.NO_BODY,
    val feedback: SquatFeedback = SquatFeedback.NONE,
    /** Smoothed knee angle in degrees, or `null` when no leg is visible. */
    val kneeAngle: Float? = null,
    /** 0 = standing straight, 1 = at (or past) the depth line. Drives the depth meter. */
    val depth: Float = 0f,
    /** Smallest knee angle reached during the last finished rep, for the log and the readout. */
    val lastRepBottomAngle: Float? = null,
)

/**
 * Counts squat repetitions from a stream of knee angles, with hysteresis.
 *
 * A rep is **stand → bend below [DEPTH_ANGLE] → stand back above [STAND_ANGLE]**. The
 * three thresholds are deliberately far apart so a noisy landmark cannot flicker the
 * state: entering a squat needs the knee under [DESCENT_ANGLE] (20° below straight),
 * finishing one needs it back above [STAND_ANGLE], and depth latches once reached — a
 * wobble at the bottom never un-reaches it. On top of that the raw angle goes through an
 * exponential moving average ([SMOOTHING]) before any threshold is read.
 *
 * Standing back up without having reached depth does not count and says so
 * ([SquatFeedback.TOO_SHALLOW]). Losing the legs for [LOST_SAMPLES_BEFORE_RESET] samples in
 * a row abandons the rep in progress without counting it, so walking out of frame
 * mid-squat and back in standing cannot produce a phantom rep.
 */
class SquatRepCounter {

    var state: SquatCoachState = SquatCoachState()
        private set

    private var smoothed: Float? = null
    private var reachedDepth = false
    private var bottomAngle = Float.MAX_VALUE
    private var lostSamples = 0

    /** Clears the count and every partial rep. */
    fun reset() {
        state = SquatCoachState()
        smoothed = null
        reachedDepth = false
        bottomAngle = Float.MAX_VALUE
        lostSamples = 0
    }

    /**
     * Feeds one pose sample: the knee angle in degrees, or `null` when no leg was visible
     * enough to measure. Returns the new state.
     */
    fun onSample(rawKneeAngle: Float?): SquatCoachState {
        if (rawKneeAngle == null) return onLost()
        lostSamples = 0

        val angle = smoothed?.let { it + SMOOTHING * (rawKneeAngle - it) } ?: rawKneeAngle
        smoothed = angle

        var reps = state.reps
        var feedback = state.feedback
        var lastBottom = state.lastRepBottomAngle
        val phase: SquatPhase

        when (state.phase) {
            SquatPhase.NO_BODY, SquatPhase.STANDING -> {
                if (angle < DESCENT_ANGLE) {
                    reachedDepth = angle <= DEPTH_ANGLE
                    bottomAngle = angle
                    phase = if (reachedDepth) SquatPhase.DEEP_ENOUGH else SquatPhase.GOING_DOWN
                    feedback = if (reachedDepth) SquatFeedback.STAND_UP else SquatFeedback.GO_LOWER
                } else {
                    phase = SquatPhase.STANDING
                }
            }
            SquatPhase.GOING_DOWN, SquatPhase.DEEP_ENOUGH -> {
                bottomAngle = minOf(bottomAngle, angle)
                if (angle <= DEPTH_ANGLE) reachedDepth = true
                if (angle >= STAND_ANGLE) {
                    phase = SquatPhase.STANDING
                    lastBottom = bottomAngle
                    if (reachedDepth) {
                        reps += 1
                        feedback = SquatFeedback.REP_COUNTED
                    } else {
                        feedback = SquatFeedback.TOO_SHALLOW
                    }
                    reachedDepth = false
                    bottomAngle = Float.MAX_VALUE
                } else {
                    phase = if (reachedDepth) SquatPhase.DEEP_ENOUGH else SquatPhase.GOING_DOWN
                    feedback = if (reachedDepth) SquatFeedback.STAND_UP else SquatFeedback.GO_LOWER
                }
            }
        }

        state = SquatCoachState(
            reps = reps,
            phase = phase,
            feedback = feedback,
            kneeAngle = angle,
            depth = depthOf(angle),
            lastRepBottomAngle = lastBottom,
        )
        return state
    }

    private fun onLost(): SquatCoachState {
        lostSamples += 1
        if (lostSamples < LOST_SAMPLES_BEFORE_RESET) {
            // Brief dropout (a hand across the knee, one bad frame): hold the rep in progress.
            state = state.copy(kneeAngle = null)
            return state
        }
        smoothed = null
        reachedDepth = false
        bottomAngle = Float.MAX_VALUE
        state = state.copy(
            phase = SquatPhase.NO_BODY,
            feedback = SquatFeedback.NONE,
            kneeAngle = null,
            depth = 0f,
        )
        return state
    }

    companion object {
        /** Knee straighter than this = standing. Finishing a rep needs this. */
        const val STAND_ANGLE = 160f

        /** Knee more bent than this = a squat has started. */
        const val DESCENT_ANGLE = 140f

        /**
         * Knee at or under this = deep enough to count. Thighs parallel to the floor is about
         * 90° at the knee; 100° leaves room for the 2D projection of a phone that is not
         * perfectly side-on.
         */
        const val DEPTH_ANGLE = 100f

        /** Weight of a new sample in the moving average. */
        const val SMOOTHING = 0.5f

        /** Consecutive samples without a leg before the rep in progress is abandoned. */
        const val LOST_SAMPLES_BEFORE_RESET = 12

        /** 0 at [STAND_ANGLE], 1 at [DEPTH_ANGLE], clamped. */
        fun depthOf(angle: Float): Float =
            ((STAND_ANGLE - angle) / (STAND_ANGLE - DEPTH_ANGLE)).coerceIn(0f, 1f)
    }
}

/** One 2D landmark in upright-image space, with MediaPipe's visibility score. */
data class PosePoint(val x: Float, val y: Float, val visibility: Float)

/** Hip, knee and ankle of one leg. */
data class Leg(val hip: PosePoint, val knee: PosePoint, val ankle: PosePoint) {
    /** The weakest of the three — a leg is only as visible as its least visible joint. */
    val visibility: Float get() = minOf(hip.visibility, knee.visibility, ankle.visibility)
}

/** Least visibility a leg needs before its angle is trusted. */
const val MIN_LEG_VISIBILITY = 0.5f

/**
 * Knee angle in degrees (180 = straight leg) of whichever leg is more visible, or `null`
 * when neither clears [MIN_LEG_VISIBILITY].
 *
 * The landmarks are normalised separately on each axis (`x` over the image width, `y` over
 * its height), so a raw angle would be skewed on any non-square image. [aspect] (width /
 * height of the upright image) brings `x` back to the same unit as `y` first.
 */
fun kneeAngleDegrees(left: Leg?, right: Leg?, aspect: Float): Float? {
    val best = listOfNotNull(left, right)
        .filter { it.visibility >= MIN_LEG_VISIBILITY }
        .maxByOrNull { it.visibility }
        ?: return null
    return angleAt(best.knee, best.hip, best.ankle, aspect)
}

/** Angle in degrees at [vertex] between [a] and [b], with `x` scaled by [aspect]. */
fun angleAt(vertex: PosePoint, a: PosePoint, b: PosePoint, aspect: Float): Float? {
    val ax = (a.x - vertex.x) * aspect
    val ay = a.y - vertex.y
    val bx = (b.x - vertex.x) * aspect
    val by = b.y - vertex.y
    val la = sqrt(ax * ax + ay * ay)
    val lb = sqrt(bx * bx + by * by)
    if (la < 1e-6f || lb < 1e-6f) return null
    val cos = ((ax * bx + ay * by) / (la * lb)).coerceIn(-1f, 1f)
    return Math.toDegrees(acos(cos).toDouble()).toFloat()
}
