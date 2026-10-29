package io.github.sceneview.demo.demos.internal

import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The capture's one count as the user reads it (#4717): it only grows. A raw count that drops —
 * the dense map coarsening a region it left to make room ([DenseFusion]'s compaction) — moves
 * nothing on screen; what comes after the drop adds on from there. So the figure is every point
 * the capture took in, not what the map holds after it merged some: decimation stays out of it.
 */
class MonotonicCount {
    private var last = 0

    /** The figure shown: the sum of every rise of the raw count. */
    var shown = 0
        private set

    /** Feeds the raw count now and returns [shown]. */
    fun update(raw: Int): Int {
        val now = raw.coerceAtLeast(0)
        if (now > last) shown += now - last
        last = now
        return shown
    }

    fun reset() {
        last = 0
        shown = 0
    }
}

/**
 * The confidence a capture keeps a depth pixel or a feature point at, relaxed while nothing
 * passes it (#4717). A grey wall, a tiled floor, the sky: the sensor sees something, but not one
 * sample clears [base], and a capture that gains nothing for seconds reads as frozen.
 *
 * - **Relax.** When fewer than [minPassed] samples have passed for [starveNanos] (about a second),
 *   the threshold steps down by [step] every [stepNanos], never under [floor].
 * - **Snap back.** As soon as a frame has [minPassed] samples at [base] again, the threshold is
 *   [base] again: a textured surface never pays for a plain one.
 * - **Gaps.** A pause or a lost track leaves a gap between two frames longer than [starveNanos];
 *   the wait starts again from the next frame, so a resume is never counted as starving.
 *
 * [lowTexture] is what the coaching line reads: the capture is starving, or is getting by only on
 * relaxed samples — "move closer" is the fix in both cases.
 *
 * Fed from one worker thread at a time; [threshold] and [lowTexture] are read from the main one.
 */
class AdaptiveConfidence(
    val base: Float,
    val floor: Float,
    val step: Float,
    val minPassed: Int,
    private val starveNanos: Long = STARVE_NANOS,
    private val stepNanos: Long = STEP_NANOS,
) {
    init {
        require(floor in 0f..base && step > 0f) { "floor $floor, base $base, step $step" }
    }

    /** The confidence a sample needs now, in [floor]..[base]. */
    @Volatile
    var threshold = base
        private set

    /** Whether the capture is starving, or getting by on relaxed samples: "move closer". */
    @Volatile
    var lowTexture = false
        private set

    /** Whether [threshold] is under [base]. */
    val relaxed: Boolean get() = threshold < base

    private var lastAt = Long.MIN_VALUE
    private var passedAt = Long.MIN_VALUE
    private var steppedAt = Long.MIN_VALUE

    /**
     * One frame at [nanos]: [passed] samples cleared [threshold], [passedAtBase] of them cleared
     * [base] too. Returns the threshold for the next frame.
     */
    fun record(nanos: Long, passed: Int, passedAtBase: Int = passed): Float {
        if (lastAt == Long.MIN_VALUE || nanos < lastAt || nanos - lastAt > starveNanos) {
            // The first frame, a clock that went back, or a resume after a gap: start waiting now.
            passedAt = nanos
            steppedAt = nanos
        }
        lastAt = nanos
        if (passedAtBase >= minPassed) {
            threshold = base
            passedAt = nanos
            lowTexture = false
            return threshold
        }
        if (passed >= minPassed) {
            // Getting by on relaxed samples: hold the threshold here, keep saying "move closer".
            passedAt = nanos
            lowTexture = relaxed
            return threshold
        }
        val starving = nanos - passedAt >= starveNanos
        if (starving && threshold > floor) {
            // The first step once starving, then one every stepNanos while it lasts.
            val due = if (steppedAt <= passedAt) passedAt + starveNanos else steppedAt + stepNanos
            if (nanos >= due) {
                threshold = (threshold - step).coerceAtLeast(floor)
                steppedAt = nanos
            }
        }
        lowTexture = starving || relaxed
        return threshold
    }

    fun reset() {
        threshold = base
        lowTexture = false
        lastAt = Long.MIN_VALUE
        passedAt = Long.MIN_VALUE
        steppedAt = Long.MIN_VALUE
    }

    companion object {
        /** Nothing passing for this long reads as frozen: the issue's "about a second". */
        const val STARVE_NANOS = 1_000_000_000L

        /** One step down every half second: [DENSE_BASE] reaches [DENSE_FLOOR] 2.5 s after the last sample. */
        const val STEP_NANOS = 500_000_000L

        /**
         * ARCore's raw-depth confidence, 0–255. [DepthBackProjection.MIN_CONFIDENCE] (128, its
         * "medium" half) is the base; a plain surface relaxes it in steps of 16 down to 64 — a
         * quarter of the scale. Under that a raw-depth pixel is mostly the sensor's guess.
         */
        const val DENSE_BASE = DepthBackProjection.MIN_CONFIDENCE
        const val DENSE_FLOOR = 64
        const val DENSE_STEP = 16

        /** Fewer surfels than this in a depth frame is nothing: a frame of a wall gives thousands. */
        const val DENSE_MIN_PASSED = 64

        /**
         * A relaxed frame is kept up to this depth only, where the [DenseFusion] default reaches
         * 5 m: ARCore's depth error grows with the square of the distance, and the doubtful far
         * pixels are the noise a lower threshold would let in.
         */
        const val RELAXED_FAR_M = 2.5f

        /**
         * ARCore's feature-point confidence, 0–1: [ArDebugTrace.MIN_POINT_CONFIDENCE] (0.2) is
         * the base, halved at most.
         */
        const val SPARSE_BASE = ArDebugTrace.MIN_POINT_CONFIDENCE
        const val SPARSE_FLOOR = 0.1f
        const val SPARSE_STEP = 0.025f

        /** Fewer feature points than this in a frame is nothing. */
        const val SPARSE_MIN_PASSED = 8

        /** A depth scan's threshold, on ARCore's 0–255 scale. */
        fun dense(): AdaptiveConfidence =
            AdaptiveConfidence(DENSE_BASE.toFloat(), DENSE_FLOOR.toFloat(), DENSE_STEP.toFloat(), DENSE_MIN_PASSED)

        /** A feature-point scan's threshold, on ARCore's 0–1 scale. */
        fun sparse(): AdaptiveConfidence = AdaptiveConfidence(SPARSE_BASE, SPARSE_FLOOR, SPARSE_STEP, SPARSE_MIN_PASSED)
    }
}

/** How the live map says what is covered (#4717): a surface, which only grows, never a share. */
object ScanAreaCopy {
    /** `0.4 m²`, `7.5 m²`, `38 m²`: a decimal under 10 m², where a step adds a tenth. */
    fun area(squareMetres: Float): String {
        val m2 = if (squareMetres.isFinite()) squareMetres.coerceAtLeast(0f) else 0f
        return if (m2 < DECIMAL_UNDER) {
            val tenths = (m2 * TENTHS).roundToInt()
            if (tenths >= DECIMAL_UNDER * TENTHS) "${DECIMAL_UNDER.toInt()} m²" else "${tenths / TENTHS_INT}.${tenths % TENTHS_INT} m²"
        } else {
            "${m2.roundToInt()} m²"
        }
    }

    private const val DECIMAL_UNDER = 10f
    private const val TENTHS = 10f
    private const val TENTHS_INT = 10
}

/**
 * This pose turned by [degrees] about the vertical, in place: what a phone held still and swung
 * round does. QA only (#4718): the emulator's phone never moves, so a paused capture turns it
 * this way to show the live view follow it while the cloud stays where it is in the room.
 */
internal fun DebugPose.turnedBy(degrees: Float): DebugPose {
    val half = Math.toRadians(degrees.toDouble()).toFloat() / 2f
    val s = sin(half)
    val c = cos(half)
    // q' = yaw * q, with yaw = (0, s, 0, c): the turn is about the world's vertical.
    return copy(
        qx = c * qx + s * qz,
        qy = c * qy + s * qw,
        qz = c * qz - s * qx,
        qw = c * qw - s * qy,
    )
}
