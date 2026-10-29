package io.github.sceneview.demo.demos.internal

import io.github.sceneview.math.Position
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The flight of 3D Scan's save (#4719, #4742), planned once from the cloud, which no longer grows
 * while it saves: from [from] — the phone's eye — the view steps away from the surfaces nearest
 * the phone, rises straight up out of the room while it pitches down, then backs away over the
 * walls onto a slow dollhouse orbit round the whole cloud.
 *
 * Its eye never comes nearer than [GUARD_M] to a point of the cloud once it has stepped away from
 * the phone's own spot (nor nearer than the phone itself stood before that), and never crosses a
 * wall: it leaves the room through its open top, at [GUARD_M] above the highest point. The ease it
 * replaces mixed the orbit's numbers instead, and its eye backed out through the wall behind the
 * phone, flashing that wall's points 30 cm from the lens as large white squares on a Pixel 4a.
 *
 * The heading holds while the view rises and turns only once above the walls, so the view never
 * swings round on itself: it looks the way the phone looked, from higher and further back.
 *
 * @param from        where the flight sets out, the eye of the phone ([ScanFollow.eyePose])
 * @param points      the scanned cloud, xyz flat
 * @param centre      where the orbit looks, the cloud's mean ([ScanFollow.cloudCentre])
 * @param radius      how far the cloud reaches on the floor from [centre] ([ScanFollow.cloudRadius])
 */
class ScanSavingPath(
    val from: OrbitPose,
    points: FloatArray,
    private val centre: Position,
    radius: Float,
    verticalFovDegrees: Double,
) {
    private val start = CameraRig.eye(from)

    /** Where the rise ends: [GUARD_M] above the cloud's highest point, over the stepped-away spot. */
    val riseTop: Position

    /** How far the orbit stands from [centre]: the whole cloud in view, its eye above [riseTop]. */
    val orbitDistance: Float

    /** The pose at the top of the rise, looking down along the phone's heading. */
    private val risen: OrbitPose

    init {
        var high = Float.NEGATIVE_INFINITY
        var i = 1
        while (i < points.size) {
            high = max(high, points[i])
            i += 3
        }
        val ceiling = max(if (high.isFinite()) high + GUARD_M else start.y, start.y)
        riseTop = column(points, start, ceiling)
        val halfFov = Math.toRadians(verticalFovDegrees / 2.0)
        val pitch = sin(Math.toRadians(ELEVATION_DEGREES.toDouble())).toFloat()
        orbitDistance = maxOf(
            ScanFollow.DISTANCE_M,
            (radius / sin(halfFov) / FILL).toFloat(),
            (ceiling - centre.y) / pitch,
        )
        risen = aimed(riseTop, from.azimuthDegrees, ELEVATION_DEGREES, RISE_REACH_M)
    }

    /** The flight [seconds] after it set out: [from] at 0, then rise, back-out and orbit. */
    fun poseAt(seconds: Float): OrbitPose {
        val t = seconds.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
        if (t == 0f) return from
        if (t < RISE_SECONDS) {
            val rise = ease(t / RISE_SECONDS)
            val slide = ease(t / SLIDE_SECONDS)
            val eye = Position(
                mix(start.x, riseTop.x, slide),
                mix(start.y, riseTop.y, rise),
                mix(start.z, riseTop.z, slide),
            )
            val reach = mix(from.distance, risen.distance, rise)
            return aimed(eye, from.azimuthDegrees, mix(from.elevationDegrees, risen.elevationDegrees, rise), reach)
        }
        val heading = headingAt(t)
        val onOrbit = OrbitPose(centre, heading, ELEVATION_DEGREES, orbitDistance)
        val out = ease((t - RISE_SECONDS) / OUT_SECONDS)
        if (out >= 1f) return onOrbit
        // A straight line between two points above the cloud stays above it.
        val eye = mix(riseTop, CameraRig.eye(onOrbit), out)
        val pose = CameraRig.poseOf(eye, mix(risen.target, centre, out))
        // poseOf answers in (-180, 180]: kept on the turn's own count, so the azimuth never jumps.
        val wrap = ((heading - pose.azimuthDegrees) / FULL_TURN).roundToInt() * FULL_TURN
        return pose.copy(azimuthDegrees = pose.azimuthDegrees + wrap)
    }

    /** The orbit's heading at [t]: the phone's until the rise is over, then turning ever faster up to its pace. */
    private fun headingAt(t: Float): Float {
        val x = ((t - RISE_SECONDS) / OUT_SECONDS).coerceIn(0f, 1f)
        // The integral of the eased pace over the back-out, then the steady pace.
        val turned = OUT_SECONDS * (x * x * x - x * x * x * x / 2f) +
            (t - RISE_SECONDS - OUT_SECONDS).coerceAtLeast(0f)
        return from.azimuthDegrees + DEGREES_PER_SECOND * turned
    }

    companion object {
        /**
         * The least distance from the flight's eye to a point of the cloud, metres. A finest surfel
         * (1.3 × 2 cm, [DenseSurfels.HALF_SIDE_VOXELS]) drawn through the orbit's 28 mm lens is
         * 1.17 × 2.6 cm / d of the view's height: a tenth of it at 30 cm — the squares a Pixel 4a
         * showed — and 6 % at 50 cm, the size a wall is drawn at arm's length in the live view. The
         * coarsest surfels (10 cm) stay under [DenseSurfels.MAX_SCREEN_SIDE] there too.
         */
        const val GUARD_M = 0.5f

        /**
         * How far ahead the view looks while it rises, metres: short, because the scan's band draws
         * the camera off its orbit eye along its own up axis ([ScanFollow.LIFT], a fifth of the
         * view's height at the target's depth: 0.2 × 2 tan 23° × 1 m = 0.17 m here), which leans
         * it towards what it looks at. Once above the walls, that lean only lifts it further.
         */
        const val RISE_REACH_M = 1f

        /** How clear of the cloud the rise's column stands: [GUARD_M] and the band's lean above. */
        const val COLUMN_CLEARANCE_M = GUARD_M + 0.2f

        /** The furthest the flight steps away from the phone's spot before it rises clear, metres. */
        const val PULL_MAX_M = 0.8f

        /** The step away from the phone's spot is tried in this many even increments. */
        private const val PULL_STEPS = 8

        /** The step away, seconds: done well before the rise, so the view leaves a near wall first. */
        const val SLIDE_SECONDS = 0.9f

        /** The rise out of the room, seconds. */
        const val RISE_SECONDS = 2.2f

        /** The back-out from above the room onto the orbit, seconds. */
        const val OUT_SECONDS = 2.6f

        /** The orbit's pitch: a dollhouse view, the floor seen over the walls. */
        const val ELEVATION_DEGREES = 55f

        /** The orbit's pace once on it: a turn in 40 s, a showcase, not a spinner. */
        const val DEGREES_PER_SECOND = 9f

        /** How much of the view's height the cloud's horizontal reach fills on the orbit. */
        const val FILL = 0.85f

        private const val FULL_TURN = 360f

        /** Points within this horizontal reach of the phone push the flight's column away from them. */
        private const val PUSH_REACH_M = GUARD_M + PULL_MAX_M

        /**
         * Where the flight rises: the phone's spot, or the nearest spot up to [PULL_MAX_M] away from
         * the surfaces around it whose column stands [COLUMN_CLEARANCE_M] clear of every point the rise could
         * pass — those from [GUARD_M] under the eye up. Without one, the clearest of them.
         */
        fun column(points: FloatArray, eye: Position, ceiling: Float): Position {
            val low = eye.y - GUARD_M
            var pushX = 0f
            var pushZ = 0f
            var i = 0
            while (i + 2 < points.size) {
                if (points[i + 1] >= low) {
                    val dx = eye.x - points[i]
                    val dz = eye.z - points[i + 2]
                    val d2 = dx * dx + dz * dz
                    if (d2 > MIN_D2 && d2 < PUSH_REACH_M * PUSH_REACH_M) {
                        pushX += dx / d2
                        pushZ += dz / d2
                    }
                }
                i += 3
            }
            val push = sqrt(pushX * pushX + pushZ * pushZ)
            if (!push.isFinite() || push < MIN_PUSH) return Position(eye.x, ceiling, eye.z)
            val ux = pushX / push
            val uz = pushZ / push
            var best = Position(eye.x, ceiling, eye.z)
            var bestClear = -1f
            for (k in 0..PULL_STEPS) {
                val step = PULL_MAX_M * k / PULL_STEPS
                val x = eye.x + ux * step
                val z = eye.z + uz * step
                val clear = horizontalClearance(points, x, z, low)
                if (clear >= COLUMN_CLEARANCE_M) return Position(x, ceiling, z)
                if (clear > bestClear) {
                    bestClear = clear
                    best = Position(x, ceiling, z)
                }
            }
            return best
        }

        /** The horizontal distance from the vertical line through ([x], [z]) to the points above [low]. */
        private fun horizontalClearance(points: FloatArray, x: Float, z: Float, low: Float): Float {
            var least = Float.MAX_VALUE
            var i = 0
            while (i + 2 < points.size) {
                if (points[i + 1] >= low) {
                    val dx = x - points[i]
                    val dz = z - points[i + 2]
                    least = minOf(least, dx * dx + dz * dz)
                }
                i += 3
            }
            return sqrt(least)
        }

        private const val MIN_D2 = 1e-8f
        private const val MIN_PUSH = 1e-3f

        /** The pose with its eye at [eye], heading [azimuth], pitched [elevation], [reach] to its target. */
        private fun aimed(eye: Position, azimuth: Float, elevation: Float, reach: Float): OrbitPose {
            val probe = CameraRig.eye(OrbitPose(Position(0f, 0f, 0f), azimuth, elevation, reach))
            return OrbitPose(
                target = Position(eye.x - probe.x, eye.y - probe.y, eye.z - probe.z),
                azimuthDegrees = azimuth,
                elevationDegrees = elevation,
                distance = reach,
            )
        }

        private fun ease(t: Float): Float {
            val x = t.coerceIn(0f, 1f)
            return x * x * (3f - 2f * x)
        }

        private fun mix(a: Float, b: Float, t: Float) = a + (b - a) * t

        private fun mix(a: Position, b: Position, t: Float) =
            Position(mix(a.x, b.x, t), mix(a.y, b.y, t), mix(a.z, b.z, t))
    }
}
