package io.github.sceneview.demo.auto

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/**
 * What the driver is holding down. [steer] is `-1` (left), `0` or `1` (right).
 */
internal data class DriveInput(
    val throttle: Boolean = false,
    val brake: Boolean = false,
    val steer: Int = 0,
)

/**
 * A car on the garage floor: a kinematic bicycle model, no physics engine. Plain Kotlin and
 * plain floats, so it is unit-tested on the JVM and stepped from the frame loop.
 *
 * The floor is the `x`/`z` plane. [heading] is in degrees around `+Y`, `0` pointing along `+Z`,
 * so the car's nose is at `(sin heading, cos heading)` and the pose maps one-to-one onto a
 * node's `position` and `rotation.y`.
 *
 * The car cannot leave the floor: it is held inside a disc of [arenaRadius] and outside the
 * podium, a disc of [obstacleRadius] around the origin. Touching either slides the car along the
 * edge and takes the speed that was aimed at it.
 */
internal class DriveModel(
    private val arenaRadius: Float = GarageStage.DRIVE_RADIUS,
    private val obstacleRadius: Float = GarageStage.PODIUM_RADIUS + GarageStage.PODIUM_CLEARANCE,
) {
    var x = START_X
        private set
    var z = START_Z
        private set
    var heading = START_HEADING
        private set

    /** Metres per second along the nose; negative in reverse. */
    var speed = 0f
        private set

    /** Eased steering, `-1` (full left) to `1` (full right). */
    var steer = 0f
        private set

    /** `true` while the brake is slowing a car that still rolls forward: the brake lights. */
    var braking = false
        private set

    fun reset() {
        x = START_X
        z = START_Z
        heading = START_HEADING
        speed = 0f
        steer = 0f
        braking = false
    }

    fun step(dt: Float, input: DriveInput) {
        if (dt <= 0f) return
        steer += (input.steer.coerceIn(-1, 1) - steer) * (1f - exp(-STEER_RATE * dt))

        braking = input.brake && speed > CREEP
        speed = when {
            input.throttle && !input.brake -> min(MAX_SPEED, speed + (if (speed < 0f) BRAKE else ACCELERATION) * dt)
            // The brake pedal stops the car, then backs it up: one control, no gear selector.
            input.brake && speed > CREEP -> max(0f, speed - BRAKE * dt)
            input.brake -> max(-MAX_REVERSE, speed - REVERSE_ACCELERATION * dt)
            else -> speed * exp(-ROLLING_DRAG * dt)
        }
        if (!input.throttle && !input.brake && abs(speed) < REST_SPEED) speed = 0f

        // A fast car turns less: the steering angle narrows as the speed climbs.
        val lock = MAX_LOCK_DEGREES - (MAX_LOCK_DEGREES - MIN_LOCK_DEGREES) * min(1f, abs(speed) / MAX_SPEED)
        val wheel = Math.toRadians((steer * lock).toDouble())
        // Looking along the nose with `+Y` up, `+X` is on the left: a right turn lowers the heading.
        heading -= Math.toDegrees(speed / WHEELBASE * tan(wheel) * dt).toFloat()
        heading = ((heading % FULL_TURN) + FULL_TURN) % FULL_TURN

        val radians = Math.toRadians(heading.toDouble())
        val forwardX = sin(radians).toFloat()
        val forwardZ = cos(radians).toFloat()
        x += forwardX * speed * dt
        z += forwardZ * speed * dt
        contain(forwardX, forwardZ)
    }

    /** Puts the car back between the podium and the edge of the floor. */
    private fun contain(forwardX: Float, forwardZ: Float) {
        val distance = hypot(x, z)
        if (distance < MIN_DISTANCE) {
            // Dead centre has no "outward": anywhere on the podium's edge will do.
            x = 0f
            z = obstacleRadius
            speed = 0f
            return
        }
        val outwardX = x / distance
        val outwardZ = z / distance
        // How much of the motion is aimed at the wall: 1 head-on, 0 along it.
        val towardEdge = (forwardX * outwardX + forwardZ * outwardZ) * if (speed < 0f) -1f else 1f
        when {
            distance > arenaRadius -> {
                x = outwardX * arenaRadius
                z = outwardZ * arenaRadius
                if (towardEdge > 0f) speed *= 1f - towardEdge
            }
            distance < obstacleRadius -> {
                x = outwardX * obstacleRadius
                z = outwardZ * obstacleRadius
                if (towardEdge < 0f) speed *= 1f + towardEdge
            }
        }
    }

    companion object {
        /** Starts beside the podium, pointing along the lane that circles it. */
        const val START_X = 0f
        const val START_Z = 9f
        const val START_HEADING = 90f

        /** About 50 km/h: fast for a garage, slow enough to steer on a touch screen. */
        const val MAX_SPEED = 14f
        const val MAX_REVERSE = 5f
        private const val ACCELERATION = 7f
        private const val REVERSE_ACCELERATION = 5f
        private const val BRAKE = 18f
        /** Fraction of the speed lost per second with no pedal down. */
        private const val ROLLING_DRAG = 0.45f
        private const val REST_SPEED = 0.05f
        /** Under this speed the brake pedal is the reverse pedal. */
        private const val CREEP = 0.2f
        private const val WHEELBASE = 2.6f
        private const val MAX_LOCK_DEGREES = 30f
        private const val MIN_LOCK_DEGREES = 9f
        private const val STEER_RATE = 6f
        private const val FULL_TURN = 360f
        private const val MIN_DISTANCE = 1e-3f
    }
}
