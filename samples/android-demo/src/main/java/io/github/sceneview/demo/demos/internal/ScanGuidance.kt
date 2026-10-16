package io.github.sceneview.demo.demos.internal

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * How fast the phone turns and moves, from the camera's successive poses, smoothed over about
 * [SMOOTHING_NS]: what the coach says "Slow down" on, and what a look of [ScanCoverage] is worth.
 */
internal class ScanMotion {
    /** Turning speed, degrees per second, all axes together. */
    var degreesPerSecond = 0f
        private set

    /** Moving speed, metres per second. */
    var metresPerSecond = 0f
        private set

    private var last: DebugPose? = null
    private var lastNanos = 0L

    fun update(nanos: Long, pose: DebugPose) {
        val previous = last
        val dt = (nanos - lastNanos) / NANOS_PER_SECOND
        last = pose
        lastNanos = nanos
        if (previous == null || dt <= 0f) return
        if (dt > MAX_GAP_S) {
            // A gap (tracking came back): the jump since is not a motion.
            degreesPerSecond = 0f
            metresPerSecond = 0f
            return
        }
        val alpha = 1f - exp(-dt * NANOS_PER_SECOND / SMOOTHING_NS)
        degreesPerSecond += (angleDegrees(previous, pose) / dt - degreesPerSecond) * alpha
        metresPerSecond += (distance(previous, pose) / dt - metresPerSecond) * alpha
    }

    /**
     * What a look is worth at this speed, 0–1: whole up to [STEADY_DEG_S], falling to
     * [BLURRED_FACTOR] at [BLURRED_DEG_S] — a blurred, fast frame gives the scan less.
     */
    val lookFactor: Float
        get() {
            val t = ((degreesPerSecond - STEADY_DEG_S) / (BLURRED_DEG_S - STEADY_DEG_S)).coerceIn(0f, 1f)
            return 1f - t * (1f - BLURRED_FACTOR)
        }

    companion object {
        const val SMOOTHING_NS = 150_000_000L
        const val STEADY_DEG_S = 10f
        const val BLURRED_DEG_S = 40f
        const val BLURRED_FACTOR = 0.25f
        private const val MAX_GAP_S = 0.5f
        private const val NANOS_PER_SECOND = 1e9f

        /** The rotation between two poses, degrees. */
        fun angleDegrees(a: DebugPose, b: DebugPose): Float {
            val dot = abs(a.qx * b.qx + a.qy * b.qy + a.qz * b.qz + a.qw * b.qw).coerceAtMost(1f)
            return Math.toDegrees(2.0 * acos(dot.toDouble())).toFloat()
        }

        fun distance(a: DebugPose, b: DebugPose): Float {
            val dx = a.x - b.x
            val dy = a.y - b.y
            val dz = a.z - b.z
            return sqrt(dx * dx + dy * dy + dz * dz)
        }
    }
}

/**
 * A condition with hysteresis: it turns on once its `enter` test has held [enterNs], and off once
 * its `exit` test has held [exitNs]; between the two thresholds it stays as it is.
 */
internal class ScanLatch(private val enterNs: Long, private val exitNs: Long) {
    var on = false
        private set
    private var since = NOT_PENDING

    fun update(nanos: Long, enter: Boolean, exit: Boolean): Boolean {
        val pending = if (on) exit else enter
        if (!pending) {
            since = NOT_PENDING
            return on
        }
        if (since == NOT_PENDING) since = nanos
        val wait = if (on) exitNs else enterNs
        if (nanos - since >= wait) {
            on = !on
            since = NOT_PENDING
        }
        return on
    }

    fun reset() {
        on = false
        since = NOT_PENDING
    }

    private companion object {
        const val NOT_PENDING = Long.MIN_VALUE
    }
}

/** What the scan's one line of advice says, most urgent first: the order [ScanCoach] ranks them. */
internal enum class ScanAdvice {
    /** The camera moves too fast: the paint has stopped growing. */
    SlowDown,

    /** The image is too dark to see the room. */
    MoreLight,

    /** What the camera sees is closer than depth reads well. */
    StepBack,

    /** What the camera sees is too far to come out sharp. */
    MoveCloser,

    /** The camera sees too little texture to find points. */
    MoreDetail,

    /** The camera is steady over a patch still thin: holding it fills it in. */
    HoldHere,

    /** Enough of the room is in: the user can stop. */
    Covered,
}

/**
 * The scan's coach: what the user should change now, from what ARCore reports and what the
 * scan has seen. Each reason has its own [ScanLatch], so a speed that hovers on a threshold never
 * makes the line flicker; and once shown, a line stays [MIN_SHOWN_NS] unless a more urgent one
 * takes its place — the reason chip's rule (DESIGN.md).
 */
internal class ScanCoach {
    /** One frame's reading. `NaN` and `-1` say "not measured", and never raise a reason. */
    class Reading(
        val degreesPerSecond: Float,
        val metresPerSecond: Float,
        /** Median distance to what the latest look saw, metres. */
        val distanceM: Float = Float.NaN,
        /** Mean brightness of the camera image, 0–1. */
        val luma: Float = Float.NaN,
        /** Feature points ARCore gave this frame. */
        val points: Int = -1,
        /** Share of the latest look's cells still short of dense. */
        val poorShare: Float = 0f,
        /** ARCore says the camera moves too fast (EXCESSIVE_MOTION). */
        val excessiveMotion: Boolean = false,
        /** ARCore says the image is too dark (INSUFFICIENT_LIGHT). */
        val insufficientLight: Boolean = false,
        /** Enough of the room is covered. */
        val covered: Boolean = false,
    )

    private val fast = ScanLatch(enterNs = 250_000_000L, exitNs = 600_000_000L)
    private val dark = ScanLatch(enterNs = 1_000_000_000L, exitNs = 1_000_000_000L)
    private val close = ScanLatch(enterNs = 800_000_000L, exitNs = 500_000_000L)
    private val far = ScanLatch(enterNs = 1_200_000_000L, exitNs = 500_000_000L)
    private val plain = ScanLatch(enterNs = 1_500_000_000L, exitNs = 700_000_000L)
    private val hold = ScanLatch(enterNs = 700_000_000L, exitNs = 400_000_000L)
    private var holdSince = NEVER
    private var holdSpent = false

    /** What the line shows, or `null` when it has nothing to say. */
    var shown: ScanAdvice? = null
        private set
    private var shownSince = NEVER

    /** The camera moves too fast right now: the paint stops growing, whatever the line shows. */
    val isTooFast: Boolean get() = fast.on

    fun update(nanos: Long, r: Reading): ScanAdvice? {
        val fastNow = fast.update(
            nanos,
            enter = r.degreesPerSecond > FAST_DEG_S || r.metresPerSecond > FAST_M_S || r.excessiveMotion,
            exit = r.degreesPerSecond < CALM_DEG_S && r.metresPerSecond < CALM_M_S && !r.excessiveMotion,
        )
        val darkNow = dark.update(
            nanos,
            enter = r.insufficientLight || r.luma < DARK_LUMA,
            exit = !r.insufficientLight && !(r.luma <= LIT_LUMA),
        )
        val closeNow = close.update(nanos, enter = r.distanceM < CLOSE_M, exit = !(r.distanceM <= CLEAR_CLOSE_M))
        val farNow = far.update(nanos, enter = r.distanceM > FAR_M, exit = !(r.distanceM >= CLEAR_FAR_M))
        val plainNow = plain.update(
            nanos,
            enter = r.points in 0 until FEW_POINTS,
            exit = r.points < 0 || r.points >= ENOUGH_POINTS,
        )
        val holdNow = updateHold(nanos, r)

        val wanted = when {
            fastNow -> ScanAdvice.SlowDown
            darkNow -> ScanAdvice.MoreLight
            closeNow -> ScanAdvice.StepBack
            farNow -> ScanAdvice.MoveCloser
            plainNow -> ScanAdvice.MoreDetail
            holdNow -> ScanAdvice.HoldHere
            r.covered -> ScanAdvice.Covered
            else -> null
        }
        return show(nanos, wanted)
    }

    /**
     * "Hold here" while the camera is steady over a thin patch — at most [HOLD_MAX_NS] in a row:
     * a patch that does not fill in by then will not, and the line has said what it could.
     */
    private fun updateHold(nanos: Long, r: Reading): Boolean {
        val steady = r.degreesPerSecond < STEADY_DEG_S && r.metresPerSecond < STEADY_M_S
        if (r.degreesPerSecond > MOVING_DEG_S) holdSpent = false
        val on = hold.update(
            nanos,
            enter = steady && !holdSpent && r.poorShare > THIN_SHARE,
            exit = !steady || r.poorShare < FILLED_SHARE || holdSpent,
        )
        if (!on) {
            holdSince = NEVER
            return false
        }
        if (holdSince == NEVER) holdSince = nanos
        if (nanos - holdSince >= HOLD_MAX_NS) {
            holdSpent = true
            hold.reset()
            holdSince = NEVER
            return false
        }
        return true
    }

    /** A line stays at least [MIN_SHOWN_NS]; a more urgent one takes its place at once. */
    private fun show(nanos: Long, wanted: ScanAdvice?): ScanAdvice? {
        val current = shown
        if (wanted == current) return current
        val urgent = wanted != null && (current == null || wanted.ordinal < current.ordinal)
        if (urgent || current == null || nanos - shownSince >= MIN_SHOWN_NS) {
            shown = wanted
            shownSince = nanos
        }
        return shown
    }

    fun reset() {
        listOf(fast, dark, close, far, plain, hold).forEach { it.reset() }
        holdSince = NEVER
        holdSpent = false
        shown = null
        shownSince = NEVER
    }

    companion object {
        /** The reason chip's floor: a line stays this long once shown (DESIGN.md). */
        const val MIN_SHOWN_NS = 1_500_000_000L
        const val FAST_DEG_S = 50f
        const val CALM_DEG_S = 30f
        const val FAST_M_S = 0.75f
        const val CALM_M_S = 0.45f
        const val DARK_LUMA = 0.16f
        const val LIT_LUMA = 0.22f
        const val CLOSE_M = 0.5f
        const val CLEAR_CLOSE_M = 0.7f
        const val FAR_M = 3.5f
        const val CLEAR_FAR_M = 3f
        const val FEW_POINTS = 40
        const val ENOUGH_POINTS = 80
        const val STEADY_DEG_S = 12f
        const val STEADY_M_S = 0.15f
        const val MOVING_DEG_S = 20f
        const val THIN_SHARE = 0.5f
        const val FILLED_SHARE = 0.25f
        const val HOLD_MAX_NS = 4_000_000_000L
        private const val NEVER = Long.MIN_VALUE
    }
}

/** The camera image's brightness, read on a sparse grid: what "Turn on more light" is decided on. */
internal object ScanLight {
    private const val COLUMNS = 8
    private const val ROWS = 6

    /** Mean luma of [frame], 0–1, over a [COLUMNS] × [ROWS] grid of its pixels. */
    fun meanLuma(frame: YuvFrame): Float {
        if (frame.width <= 0 || frame.height <= 0) return Float.NaN
        var sum = 0
        for (row in 0 until ROWS) {
            val py = (row * 2 + 1) * frame.height / (ROWS * 2)
            for (column in 0 until COLUMNS) sum += frame.luma((column * 2 + 1) * frame.width / (COLUMNS * 2), py)
        }
        return sum / (COLUMNS * ROWS * 255f)
    }
}
