package io.github.sceneview.demo.demos.internal

import io.github.sceneview.camera.CameraKeyframe
import io.github.sceneview.camera.CameraPath
import io.github.sceneview.camera.CameraShot
import io.github.sceneview.camera.CameraEasing
import io.github.sceneview.math.Position
import kotlin.math.cos
import kotlin.math.sin

/**
 * The scan result's tour (#4719): a lap of camera moves rather than a turntable, the way a
 * Matterport or Polycam showcase walks a room. From the framing it cranes up over the scan,
 * dollies in low, flies through the middle at eye height, pulls out high, and lands on the
 * framing again a full turn round, so it loops with no seam.
 *
 * - The lap is [CameraEasing.Linear] through its keyframes: the spline keeps the speed continuous,
 *   so the camera flows through each beat without stopping.
 * - [leadIn] starts it from rest: the camera leaves the framing slowly and reaches the lap's own
 *   pace by its first beat, then the lap loops. A tour never starts with a jolt.
 *
 * The beats scale with [extent], the scan's larger horizontal side: the dolly and the fly-through
 * move the target into the scan by a share of it, so a room and a table-top object get the same
 * film. Pure, so a test holds its seam and its pace.
 */
internal class ScanTourPath private constructor(val leadIn: CameraPath, val lap: CameraPath) {
    companion object {
        /** One lap, seconds: slow enough to read a room, short enough to watch twice. */
        const val LAP_SECONDS = 24f

        /**
         * The lead-in, seconds: the crane's turn at the pace the lap leaves the crane at, so the
         * lead-in hands the lap its own speed. About 4.3 s.
         */
        val leadInSeconds: Float get() = CRANE.azimuth / (DOLLY.azimuth / DOLLY.seconds)

        /** The crane: higher, wider, a sixth of a turn round. */
        private val CRANE = Beat(azimuth = 60f, elevation = 22f, distance = 1.2f, push = 0f, seconds = 5f)

        /** The dolly: low and in, the target pushed a little into the scan. */
        private val DOLLY = Beat(azimuth = 140f, elevation = -16f, distance = 0.68f, push = 0.12f, seconds = 10f)

        /** The fly-through: at eye height, close, the target in the middle of the far half. */
        private val FLY = Beat(azimuth = 215f, elevation = -24f, distance = 0.5f, push = 0.22f, seconds = 15f)

        /** The pull-out: up and back, the whole scan again before the landing. */
        private val PULL = Beat(azimuth = 295f, elevation = 12f, distance = 1.08f, push = 0f, seconds = 20f)

        /** The lowest the camera flies, degrees over the horizon: never under the floor. */
        const val MIN_ELEVATION = 8f

        /** The highest, short of straight down: the plan's elevation stays the plan's. */
        const val MAX_ELEVATION = 68f

        fun of(home: CameraShot, extent: Float): ScanTourPath {
            val beats = listOf(CRANE, DOLLY, FLY, PULL)
            val keyframes = buildList {
                add(CameraKeyframe(home, 0f))
                beats.forEach { add(CameraKeyframe(it.shot(home, extent), it.seconds)) }
                add(CameraKeyframe(home.copy(azimuthDegrees = home.azimuthDegrees + FULL_TURN), LAP_SECONDS))
            }
            // The lap as played after the lead-in: from the crane round to the crane a turn later.
            val crane = keyframes[1]
            val rotated = keyframes.drop(1).map { CameraKeyframe(it.shot, it.seconds - crane.seconds) } +
                CameraKeyframe(
                    crane.shot.copy(azimuthDegrees = crane.shot.azimuthDegrees + FULL_TURN),
                    LAP_SECONDS,
                )
            val lap = CameraPath(rotated, easing = CameraEasing.Linear, shortestArc = false, loop = true)
            // From rest at the framing to the crane, arriving at the pace the lap leaves it at.
            val leadIn = CameraPath(
                listOf(CameraKeyframe(home, 0f), CameraKeyframe(crane.shot, leadInSeconds)),
                easing = ::easeIn,
                shortestArc = false,
            )
            return ScanTourPath(leadIn, lap)
        }

        /** Leaves at rest and arrives at full pace: `t²(2 − t)`, speed 0 at 0, 1 at 1. */
        fun easeIn(t: Float): Float = t * t * (2f - t)

        private const val FULL_TURN = 360f
    }

    private class Beat(val azimuth: Float, val elevation: Float, val distance: Float, val push: Float, val seconds: Float) {
        fun shot(home: CameraShot, extent: Float): CameraShot {
            val azimuth = home.azimuthDegrees + azimuth
            // Into the scan: the camera's own forward on the floor, away from where it stands.
            val a = Math.toRadians(azimuth.toDouble())
            val shift = push * extent
            val target = Position(
                home.target.x - (sin(a) * shift).toFloat(),
                home.target.y,
                home.target.z - (cos(a) * shift).toFloat(),
            )
            return CameraShot(
                target = target,
                azimuthDegrees = azimuth,
                elevationDegrees = (home.elevationDegrees + elevation).coerceIn(MIN_ELEVATION, MAX_ELEVATION),
                distance = home.distance * distance,
            )
        }
    }
}
