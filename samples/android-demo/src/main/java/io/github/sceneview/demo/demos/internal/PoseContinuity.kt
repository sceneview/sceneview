package io.github.sceneview.demo.demos.internal

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * What [PoseContinuity] made of one frame's camera pose.
 *
 * [integrates] says whether the frame may go into the scan at all: its pose, its photo, its depth.
 */
enum class PoseStep(val integrates: Boolean) {
    /** The phone moved as a phone can: integrate. */
    Continuous(true),

    /**
     * A small one-frame shift (centimetres, a few degrees): ARCore correcting its own drift. The
     * pose after it is the better one, and holding the scan back for it would stop it for good.
     */
    Corrected(true),

    /**
     * ARCore teleported the pose in one frame while still reporting TRACKING (a scan of
     * 2026-11-10 jumped 57 cm and 86° at 58.2 s). Nothing is integrated until it comes back.
     */
    Slipped(false),

    /** Still off the trajectory the scan was built on: nothing is integrated. */
    Held(false),

    /** The pose jumped back onto the trajectory the scan was built on: integrate again. */
    Recovered(true),
}

/**
 * The pose continuity guard of a room scan: flags a frame whose pose moved further, or turned
 * more, since the previous frame than a hand-held phone can, while ARCore still says TRACKING.
 *
 * A one-frame jump is read as an offset of the whole world (ARCore's map slipped under the phone).
 * Small ones are drift corrections ([PoseStep.Corrected]) and pass. Large ones stop the scan
 * ([PoseStep.Slipped]) until a later jump undoes them: the offsets composed since the slip come
 * back to identity within [recoverMetres] and [recoverDegrees], as the walk of 2026-11-10 did at
 * 61.1 s (84° back, 4 cm off where it left). Still off after [stuckAfterNanos], [isStuck] says so,
 * and the screen offers to finish or restart.
 *
 * Speeds are measured on the frames' own timestamps. Defaults tuned on that walk: the fastest
 * genuine step was 1.46 m/s and 110°/s; the slips were 14.8 to 17 m/s and 970 to 2,580°/s.
 *
 * Pure Kotlin: replayed on recorded sessions by the unit tests.
 */
class PoseContinuity(
    private val maxMetresPerSecond: Float = MAX_METRES_PER_SECOND,
    private val maxDegreesPerSecond: Float = MAX_DEGREES_PER_SECOND,
    private val slipMetres: Float = SLIP_METRES,
    private val slipDegrees: Float = SLIP_DEGREES,
    private val recoverMetres: Float = RECOVER_METRES,
    private val recoverDegrees: Float = RECOVER_DEGREES,
    private val stuckAfterNanos: Long = STUCK_AFTER_NANOS,
) {
    private var last: DebugPose? = null
    private var lastNanos = 0L
    private var lastNow = 0L

    /** The last pose integrated before the slip, and the world's offset since then. */
    private var anchor: DebugPose? = null
    private var offset = Offset.IDENTITY
    private var slipNanos = 0L

    /** Whether the scan is held off a slipped pose. */
    val isSlipped: Boolean get() = anchor != null

    /** Whether the scan has been held for [stuckAfterNanos] or more, as of the last frame. */
    val isStuck: Boolean get() = anchor != null && lastNow - slipNanos >= stuckAfterNanos

    /** How many frames were let through as drift corrections, and how many slips stopped the scan. */
    var corrections = 0
        private set
    var slips = 0
        private set

    /** Starts over, for a new capture. */
    fun reset() {
        last = null
        lastNanos = 0L
        lastNow = 0L
        anchor = null
        offset = Offset.IDENTITY
        slipNanos = 0L
        corrections = 0
        slips = 0
    }

    /** Reads the pose of the frame taken at [nanos]: whether it may be integrated, and why. */
    fun step(nanos: Long, pose: DebugPose): PoseStep {
        val previous = last
        if (previous == null) {
            last = pose
            lastNanos = nanos
            lastNow = nanos
            return PoseStep.Continuous
        }
        // The same frame handed twice: what was said of it still holds.
        if (nanos <= lastNanos) return if (isSlipped) PoseStep.Held else PoseStep.Continuous
        val dt = max(nanos - lastNanos, MIN_DT_NANOS) / NANOS_PER_SECOND
        val metres = distance(previous, pose)
        val degrees = angleDegrees(previous, pose)
        val jump = metres / dt > maxMetresPerSecond || degrees / dt > maxDegreesPerSecond
        val gap = nanos - lastNanos > GAP_NANOS
        last = pose
        lastNanos = nanos
        lastNow = nanos

        val held = anchor
        if (held == null) {
            return when {
                !jump -> PoseStep.Continuous
                metres < slipMetres && degrees < slipDegrees -> {
                    corrections++
                    PoseStep.Corrected
                }
                else -> {
                    anchor = previous
                    offset = Offset.between(previous, pose)
                    slipNanos = nanos
                    slips++
                    PoseStep.Slipped
                }
            }
        }
        // Held: a jump, or a frame after a gap in tracking (ARCore relocalising), may undo the slip.
        if (!jump && !gap) return PoseStep.Held
        val composed = Offset.between(previous, pose).then(offset)
        if (composed.degrees() < recoverDegrees && composed.displacementAt(held) < recoverMetres) {
            anchor = null
            offset = Offset.IDENTITY
            return PoseStep.Recovered
        }
        if (!jump) return PoseStep.Held
        offset = composed
        return PoseStep.Slipped
    }

    /** A rigid transform of the world: rotation (qx, qy, qz, qw), then translation (tx, ty, tz). */
    private data class Offset(
        val qx: Float, val qy: Float, val qz: Float, val qw: Float,
        val tx: Float, val ty: Float, val tz: Float,
    ) {
        /** This offset applied after [first]. */
        fun then(first: Offset): Offset {
            val q = mul(qx, qy, qz, qw, first.qx, first.qy, first.qz, first.qw)
            val r = rotation().rotate(first.tx, first.ty, first.tz)
            return Offset(q[0], q[1], q[2], q[3], r.x + tx, r.y + ty, r.z + tz)
        }

        fun degrees(): Float = angleOf(qw)

        /** How far this offset moves [pose]'s position. */
        fun displacementAt(pose: DebugPose): Float {
            val r = rotation().rotate(pose.x, pose.y, pose.z)
            val dx = r.x + tx - pose.x
            val dy = r.y + ty - pose.y
            val dz = r.z + tz - pose.z
            return sqrt(dx * dx + dy * dy + dz * dz)
        }

        private fun rotation() = DebugPose(0f, 0f, 0f, qx, qy, qz, qw)

        companion object {
            val IDENTITY = Offset(0f, 0f, 0f, 1f, 0f, 0f, 0f)

            /** The offset taking pose [from] onto pose [to]: `to = O · from`. */
            fun between(from: DebugPose, to: DebugPose): Offset {
                val q = mul(to.qx, to.qy, to.qz, to.qw, -from.qx, -from.qy, -from.qz, from.qw)
                val r = DebugPose(0f, 0f, 0f, q[0], q[1], q[2], q[3]).rotate(from.x, from.y, from.z)
                return Offset(q[0], q[1], q[2], q[3], to.x - r.x, to.y - r.y, to.z - r.z)
            }
        }
    }

    companion object {
        /** Faster than this between two frames is not a hand-held phone (genuine peak: 1.46 m/s). */
        const val MAX_METRES_PER_SECOND = 2.5f

        /** Nor is turning faster than this (genuine peak: 110°/s). */
        const val MAX_DEGREES_PER_SECOND = 360f

        /** A jump under both of these is a drift correction; at or over either, a slip. */
        const val SLIP_METRES = 0.3f
        const val SLIP_DEGREES = 10f

        /** How close to the pre-slip trajectory the pose must come back to integrate again. */
        const val RECOVER_METRES = 0.2f
        const val RECOVER_DEGREES = 10f

        /** Held this long, the scan offers to finish or restart. */
        const val STUCK_AFTER_NANOS = 4_000_000_000L

        /** No two frames are closer than this for the speed: 60 Hz. */
        private const val MIN_DT_NANOS = 16_666_667L

        /** A pause in the poses this long (tracking lost then found) may be ARCore relocalising. */
        private const val GAP_NANOS = 250_000_000L

        private const val NANOS_PER_SECOND = 1e9f

        fun distance(a: DebugPose, b: DebugPose): Float {
            val dx = a.x - b.x
            val dy = a.y - b.y
            val dz = a.z - b.z
            return sqrt(dx * dx + dy * dy + dz * dz)
        }

        /** The angle between the two poses' orientations, in degrees. */
        fun angleDegrees(a: DebugPose, b: DebugPose): Float {
            val na = norm(a)
            val nb = norm(b)
            if (na == 0f || nb == 0f) return 0f
            val dot = (a.qx * b.qx + a.qy * b.qy + a.qz * b.qz + a.qw * b.qw) / (na * nb)
            return angleOf(dot)
        }

        private fun norm(p: DebugPose) = sqrt(p.qx * p.qx + p.qy * p.qy + p.qz * p.qz + p.qw * p.qw)

        private fun angleOf(cosHalf: Float): Float =
            Math.toDegrees(2.0 * acos(min(1f, abs(cosHalf)).toDouble())).toFloat()

        private fun mul(
            ax: Float, ay: Float, az: Float, aw: Float,
            bx: Float, by: Float, bz: Float, bw: Float,
        ): FloatArray = floatArrayOf(
            aw * bx + ax * bw + ay * bz - az * by,
            aw * by - ax * bz + ay * bw + az * bx,
            aw * bz + ax * by - ay * bx + az * bw,
            aw * bw - ax * bx - ay * by - az * bz,
        )
    }
}
