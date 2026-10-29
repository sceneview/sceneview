package io.github.sceneview.demo.demos.internal

import io.github.sceneview.math.Position
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * The live view of 3D Scan follows the phone like a navigation app (#4628): heading-up, so the
 * way the phone faces is always the top of the screen, from a little behind and above it, the
 * phone itself on a fixed point in the lower part of the view. A drag or a pinch leaves it
 * ([ArDebugOrbitCamera]'s free mode) and Recenter brings it back.
 *
 * Only the view follows: this corrects nothing of ARCore's pose, drift included. It is the same
 * world, seen from where the phone is.
 */
class ScanFollow {

    /** The last heading read off the phone, kept while it looks straight up or down. */
    var headingDegrees: Float? = null
        private set

    /**
     * The follow pose for the phone at [camera] (ARCore's display-oriented pose), or `null`
     * before the first pose with a heading. Each call updates [headingDegrees] from it.
     */
    fun poseFor(camera: DebugPose?): OrbitPose? {
        camera ?: return null
        val heading = headingOf(camera, headingDegrees) ?: return null
        headingDegrees = heading
        return poseAt(camera, heading)
    }

    companion object {
        /**
         * How far the view stands from the phone, in metres. Close enough that what the phone
         * just took in fills the screen, far enough that a room's width still reads around it.
         */
        const val DISTANCE_M = 2.6f

        /** How steeply the view looks down on the phone: a map with depth, not a floor plan. */
        const val ELEVATION_DEGREES = 38f

        /**
         * Where the phone sits on screen, as [ArDebugOrbitCamera.lift]: a fifth of the height
         * below the centre, so most of the view is the area ahead, as in a navigation app.
         */
        const val LIFT = -0.2f

        /** The live view's [OrbitBand]: the whole card, the phone drawn at [LIFT]. */
        val BAND = OrbitBand.CARD.copy(lift = LIFT)

        /**
         * The follow ease, per second: a time constant of about 120 ms, which takes the tremor
         * of a hand out of the picture without lagging behind a turn.
         */
        const val APPROACH_RATE = 8f

        /**
         * The fastest the view turns with the phone: two turns a second, past any hand, so
         * heading-up keeps up with a quick turn where the room's framing swings round slowly.
         */
        const val MAX_TURN_DEGREES_PER_SECOND = 720f

        /**
         * The least horizontal share of the phone's forward axis that still gives a heading:
         * under it (pointing within ~80° of straight up or down) the last heading holds, since
         * the yaw of a phone looking at the floor is noise.
         */
        const val MIN_HORIZONTAL = 0.17f

        /**
         * The orbit of a drag across the whole width of the view: half a turn, so a third of the
         * screen looks 60° round the cloud and no flick spins it out of sight (#4628).
         */
        const val DRAG_DEGREES_PER_VIEW_WIDTH = 180f

        /**
         * The pitch a drag can reach: never level with the cloud or under it, where the floor
         * hides it, and never past straight down, where the view would flip over.
         */
        val DRAG_ELEVATION_RANGE = 10f..85f

        /**
         * The furthest a flick coasts on after the finger lifts: a quarter turn, smooth and
         * decaying like every orbit in the demo, never far enough to lose the room.
         */
        const val MAX_COAST_DEGREES = 90f

        /**
         * The heading of [camera], in the [OrbitPose.azimuthDegrees] convention: the azimuth at
         * which an [OrbitPose] looks the way the phone does. The phone looks down its local −Z;
         * an orbit camera at azimuth `a` looks along `(−sin a, ·, −cos a)`. [previous] comes
         * back when the phone points too close to vertical to have one.
         */
        fun headingOf(camera: DebugPose, previous: Float? = null): Float? {
            val (forwardX, _, forwardZ) = forwardOf(camera)
            val horizontal = sqrt(forwardX * forwardX + forwardZ * forwardZ)
            if (!horizontal.isFinite() || horizontal < MIN_HORIZONTAL) return previous
            return Math.toDegrees(atan2(-forwardX, -forwardZ).toDouble()).toFloat()
        }

        /** The follow pose for the phone at [camera], heading [headingDegrees]. */
        fun poseAt(camera: DebugPose, headingDegrees: Float): OrbitPose = OrbitPose(
            target = Position(camera.x, camera.y, camera.z),
            azimuthDegrees = headingDegrees,
            elevationDegrees = ELEVATION_DEGREES,
            distance = DISTANCE_M,
        )

        /** The phone's forward axis in the world: its local −Z, rotated by its quaternion. */
        fun forwardOf(camera: DebugPose): Triple<Float, Float, Float> {
            val x = camera.qx
            val y = camera.qy
            val z = camera.qz
            val w = camera.qw
            // Minus the rotation matrix's third column.
            return Triple(
                -2f * (x * z + w * y),
                -2f * (y * z - w * x),
                -(1f - 2f * (x * x + y * y)),
            )
        }

        /**
         * The phone's eye as an orbit pose (#4719): the camera at [cameraToWorld]'s position
         * (column-major, as `Pose.toMatrix` writes it), looking down its forward axis at a point
         * level with [centre] along it — the cloud's depth ahead of the phone, [MIN_EYE_REACH_M] at
         * least. The saving flight ([ScanSavingPath]) sets out from it, so it leaves the camera
         * image without a cut.
         */
        fun eyePose(cameraToWorld: FloatArray, centre: Position): OrbitPose {
            val ex = cameraToWorld[12]
            val ey = cameraToWorld[13]
            val ez = cameraToWorld[14]
            val fx = -cameraToWorld[8]
            val fy = -cameraToWorld[9]
            val fz = -cameraToWorld[10]
            val ahead = (centre.x - ex) * fx + (centre.y - ey) * fy + (centre.z - ez) * fz
            val reach = ahead.takeIf { it.isFinite() }?.coerceAtLeast(MIN_EYE_REACH_M) ?: MIN_EYE_REACH_M
            return CameraRig.poseOf(
                eye = Position(ex, ey, ez),
                target = Position(ex + fx * reach, ey + fy * reach, ez + fz * reach),
            )
        }

        /** The nearest the eye's orbit target stands ahead of the phone, metres. */
        const val MIN_EYE_REACH_M = 0.5f

        /**
         * How far [points] reach on the floor from [centre], metres: the 95th percentile of their
         * horizontal distance, so a few stray far points do not shrink the room on screen. Sampled
         * evenly, [CENTRE_SAMPLE] points at most; `0` without any.
         */
        fun cloudRadius(points: FloatArray, centre: Position): Float {
            val count = points.size / 3
            if (count == 0) return 0f
            val step = (count + CENTRE_SAMPLE - 1) / CENTRE_SAMPLE
            val reach = FloatArray((count + step - 1) / step)
            var n = 0
            var i = 0
            while (i < count && n < reach.size) {
                val dx = points[i * 3] - centre.x
                val dz = points[i * 3 + 2] - centre.z
                reach[n++] = sqrt(dx * dx + dz * dz)
                i += step
            }
            reach.sort(0, n)
            return reach[((n - 1) * RADIUS_PERCENTILE).toInt()]
        }

        private const val RADIUS_PERCENTILE = 0.95f

        /** At most this many points are averaged: enough for a stable centre, cheap per frame. */
        const val CENTRE_SAMPLE = 20_000

        /**
         * Where a drag orbits: the mean of the scanned points, `null` without any. Not the centre
         * of the phone's path and the planes — the phone stands at the edge of what it scans, so
         * orbiting its path half a turn left the view facing away from the cloud. Large clouds are
         * sampled evenly, [CENTRE_SAMPLE] points at most.
         */
        fun cloudCentre(points: FloatArray): Position? {
            val count = points.size / 3
            if (count == 0) return null
            val step = (count + CENTRE_SAMPLE - 1) / CENTRE_SAMPLE
            var x = 0.0
            var y = 0.0
            var z = 0.0
            var n = 0
            var i = 0
            while (i < count) {
                x += points[i * 3]
                y += points[i * 3 + 1]
                z += points[i * 3 + 2]
                n++
                i += step
            }
            return Position((x / n).toFloat(), (y / n).toFloat(), (z / n).toFloat())
        }
    }
}
