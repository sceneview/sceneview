package io.github.sceneview.camera

import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.lookAt
import dev.romainguy.kotlin.math.mix
import io.github.sceneview.math.Position
import io.github.sceneview.math.Transform
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Where a camera stands and what it looks at, as a cinematographer would call it: a **shot**.
 *
 * A shot is an orbit around its [target] — an angle around the subject, an angle above it and a
 * distance — rather than an eye position, so two shots of the same subject interpolate along an
 * arc *around* it instead of a straight line *through* it, and a shot carried onto another
 * subject keeps its angle. [eye] is derived. The same convention as the orbit manipulators of
 * `SceneView` and `applyCinematicOrbit`: azimuth around world +Y measured from +Z towards +X,
 * elevation positive looking *down* at the target, +Y up.
 *
 * ```kotlin
 * val home = CameraShot(target = Position(0f, 0.8f, 0f), azimuthDegrees = 35f, elevationDegrees = 32f, distance = 4f)
 * val plan = home.copy(elevationDegrees = 84f)             // the same room, seen as a floor plan
 * cameraNode.transform = home.transform()                  // or drive it through CameraAnimator
 * ```
 *
 * @property target           World point the camera looks at and orbits around.
 * @property azimuthDegrees   Angle around world +Y, from +Z towards +X. Unbounded: a path that
 *                            turns twice reads 720°. [normalizeDegrees] wraps it for display.
 * @property elevationDegrees Angle above the horizon, in `(-90, 90)`. Positive looks down.
 * @property distance         Camera-to-target distance, in metres, `> 0`.
 */
data class CameraShot(
    val target: Position = Position(0f, 0f, 0f),
    val azimuthDegrees: Float = 0f,
    val elevationDegrees: Float = 0f,
    val distance: Float = 1f,
) {
    /** The camera's eye position in world space. */
    val eye: Position
        get() {
            val azimuth = Math.toRadians(azimuthDegrees.toDouble())
            val elevation = Math.toRadians(elevationDegrees.coerceIn(-MAX_ELEVATION, MAX_ELEVATION).toDouble())
            val horizontal = distance * cos(elevation)
            return Position(
                x = target.x + (horizontal * sin(azimuth)).toFloat(),
                y = target.y + (distance * sin(elevation)).toFloat(),
                z = target.z + (horizontal * cos(azimuth)).toFloat(),
            )
        }

    /** The camera's view transform for this shot: [eye] looking at [target], [up] up. */
    fun transform(up: Position = WORLD_UP): Transform = Transform(lookAt(eye = eye, target = target, up = up))

    companion object {
        /** Keeps a shot short of the poles, where the orbit basis degenerates and the view flips. */
        const val MAX_ELEVATION = 89f

        internal val WORLD_UP = Float3(0f, 1f, 0f)

        /** The shot of a camera at [eye] looking at [target]: the inverse of [CameraShot.eye]. */
        fun lookingAt(eye: Position, target: Position): CameraShot {
            val dx = eye.x - target.x
            val dy = eye.y - target.y
            val dz = eye.z - target.z
            val distance = sqrt(dx * dx + dy * dy + dz * dz)
            if (distance <= 0f || !distance.isFinite()) return CameraShot(target = target)
            val elevation = Math.toDegrees(asin((dy / distance).coerceIn(-1f, 1f).toDouble())).toFloat()
            val azimuth = Math.toDegrees(atan2(dx.toDouble(), dz.toDouble())).toFloat()
            return CameraShot(
                target = target,
                azimuthDegrees = azimuth,
                elevationDegrees = elevation.coerceIn(-MAX_ELEVATION, MAX_ELEVATION),
                distance = distance,
            )
        }

        /**
         * The shot [fraction] of the way from [from] to [to]: target, elevation and distance
         * linearly, the azimuth the short way round (`350° → 10°` passes through 0°, not 180°)
         * when [shortestArc] is set, exactly as given otherwise — a tour that turns a full 360°
         * is spelled out as `azimuthDegrees + 360f` and must not collapse to nothing.
         */
        fun lerp(from: CameraShot, to: CameraShot, fraction: Float, shortestArc: Boolean = true): CameraShot {
            val t = fraction.coerceIn(0f, 1f)
            val azimuthDelta = if (shortestArc) {
                shortestDelta(from.azimuthDegrees, to.azimuthDegrees)
            } else {
                to.azimuthDegrees - from.azimuthDegrees
            }
            return CameraShot(
                target = mix(from.target, to.target, t),
                azimuthDegrees = from.azimuthDegrees + azimuthDelta * t,
                elevationDegrees = from.elevationDegrees + (to.elevationDegrees - from.elevationDegrees) * t,
                distance = from.distance + (to.distance - from.distance) * t,
            )
        }

        /** [degrees] wrapped into `(-180, 180]`. */
        fun normalizeDegrees(degrees: Float): Float {
            if (!degrees.isFinite()) return 0f
            var wrapped = degrees % FULL_TURN
            if (wrapped > HALF_TURN) wrapped -= FULL_TURN
            if (wrapped <= -HALF_TURN) wrapped += FULL_TURN
            return wrapped
        }

        /** The signed turn from [from] to [to] the short way round, in `(-180, 180]`. */
        fun shortestDelta(from: Float, to: Float): Float = normalizeDegrees(to - from)

        private const val FULL_TURN = 360f
        private const val HALF_TURN = 180f
    }
}

/**
 * How fast a [CameraShot] is changing, per second — what a cinematic that is interrupted by a
 * touch hands the gesture, so a fling picks up at the speed the camera already had instead of
 * stopping dead under the finger.
 */
data class CameraShotVelocity(
    val azimuthDegreesPerSecond: Float = 0f,
    val elevationDegreesPerSecond: Float = 0f,
    val distancePerSecond: Float = 0f,
    val targetPerSecond: Position = Position(0f, 0f, 0f),
) {
    /** `true` when nothing moves. */
    val isZero: Boolean
        get() = azimuthDegreesPerSecond == 0f && elevationDegreesPerSecond == 0f && distancePerSecond == 0f &&
            targetPerSecond.x == 0f && targetPerSecond.y == 0f && targetPerSecond.z == 0f

    companion object {
        val Zero = CameraShotVelocity()

        /** The mean velocity between [from] and [to] over [deltaSeconds]; [Zero] for a zero step. */
        fun between(from: CameraShot, to: CameraShot, deltaSeconds: Float): CameraShotVelocity {
            if (deltaSeconds <= 0f || !deltaSeconds.isFinite()) return Zero
            return CameraShotVelocity(
                azimuthDegreesPerSecond = (to.azimuthDegrees - from.azimuthDegrees) / deltaSeconds,
                elevationDegreesPerSecond = (to.elevationDegrees - from.elevationDegrees) / deltaSeconds,
                distancePerSecond = (to.distance - from.distance) / deltaSeconds,
                targetPerSecond = (to.target - from.target) / deltaSeconds,
            )
        }
    }
}
