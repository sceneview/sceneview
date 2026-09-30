package io.github.sceneview.demo.demos.internal

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The gesture → tilt → physics chain of the `rolling-balls` board, as plain maths the JVM tests
 * can pin down.
 *
 * Three things used to make the board feel rough, and each has its piece here:
 * - **Lost drag deltas.** Every move event used to launch a coroutine that snapped an animation
 *   to "current + delta", reading "current" before the previous launch had landed: two events in
 *   one frame kept only the second delta. The drag now accumulates into a plain [Tilt] target
 *   synchronously, event by event ([dragTarget]).
 * - **A board that jumped with the touch sampling rate.** The rendered tilt now follows the target
 *   through [follow], an exponential smoothing evaluated once per rendered frame, and the gravity
 *   the simulation steps with is derived from that same rendered tilt in the same frame — the
 *   board and the balls on it never disagree.
 * - **Syrupy balls.** Rolling used to be a per-step velocity multiplier, which is a strong linear
 *   drag: it capped a ball's speed on any slope and held it still on a gentle one. [roll] is a
 *   sphere that actually rolls: 5/7 of the slope's pull (the rest spins the ball up), minus a
 *   small constant rolling resistance and a light drag.
 */
object TrayMotion {

    /** Steepest the board tips, either way, on either axis. */
    const val MAX_TILT_DEGREES: Float = 35f

    /** Degrees of tilt per pixel of drag: a half-screen swipe (≈500 px) sweeps about 35°. */
    const val DEGREES_PER_PIXEL: Float = 0.07f

    /** Time constant of the rendered tilt following the finger: responsive, never a step. */
    const val FOLLOW_SECONDS: Float = 0.05f

    /** Time constant of Level: the board settles back rather than snapping. */
    const val LEVEL_SECONDS: Float = 0.12f

    /** Share of the slope's pull that accelerates a rolling solid sphere: 1 / (1 + 2/5). */
    const val ROLLING_SHARE: Float = 5f / 7f

    /** Speed-proportional drag while rolling, per second: felt, air and a little slip. */
    const val ROLLING_DRAG_PER_SECOND: Float = 0.3f

    /** How far above the floor a ball may be and still count as rolling on it. */
    const val CONTACT_SLOP: Float = 0.002f

    /** Vertical speed under which a ball touching the floor is rolling, not bouncing. */
    const val CONTACT_VERTICAL_SPEED: Float = 0.3f

    /** A board attitude, in degrees: `pitch` about X (positive lowers the near edge), `roll` about Z. */
    data class Tilt(val pitch: Float, val roll: Float) {
        val isLevel: Boolean get() = pitch == 0f && roll == 0f

        companion object {
            val LEVEL = Tilt(0f, 0f)
        }
    }

    /** [t] with both axes held to ±[MAX_TILT_DEGREES]. */
    fun clamp(t: Tilt): Tilt = Tilt(
        t.pitch.coerceIn(-MAX_TILT_DEGREES, MAX_TILT_DEGREES),
        t.roll.coerceIn(-MAX_TILT_DEGREES, MAX_TILT_DEGREES),
    )

    /**
     * The target after a drag of ([dxPx], [dyPx]) screen pixels, seen by a camera whose azimuth
     * around the board is [cameraYawRadians] (0 = looking down −Z, the opening shot).
     *
     * The board tips the way the finger goes, on screen: drag right and the side of the board that
     * is on the right *of the screen* goes down, whichever side of the table the camera has been
     * orbited to. The drag is turned from screen axes into the board's X/Z by the camera's yaw,
     * then pitch lowers +Z and a negative roll lowers +X.
     */
    fun dragTarget(target: Tilt, dxPx: Float, dyPx: Float, cameraYawRadians: Float): Tilt {
        val c = cos(cameraYawRadians)
        val s = sin(cameraYawRadians)
        // Screen right is the camera's +X on the ground; screen down is towards the viewer.
        val worldX = dxPx * c + dyPx * s
        val worldZ = -dxPx * s + dyPx * c
        return clamp(
            Tilt(
                pitch = target.pitch + worldZ * DEGREES_PER_PIXEL,
                roll = target.roll - worldX * DEGREES_PER_PIXEL,
            ),
        )
    }

    /**
     * The camera's azimuth around the board from its eye and target positions: 0 when the eye is
     * on +Z looking towards −Z, as in the opening shot.
     */
    fun cameraYaw(eyeX: Float, eyeZ: Float, targetX: Float, targetZ: Float): Float =
        atan2(eyeX - targetX, eyeZ - targetZ)

    /**
     * One frame of the rendered tilt following [target]: an exponential approach with time
     * constant [timeConstant], frame-rate independent. Snaps once it is within a hundredth of a
     * degree, so a still board stops asking for frames.
     */
    fun follow(current: Tilt, target: Tilt, dtSeconds: Float, timeConstant: Float): Tilt {
        if (current == target) return current
        val k = 1f - exp(-dtSeconds.coerceAtLeast(0f) / timeConstant)
        val pitch = current.pitch + (target.pitch - current.pitch) * k
        val roll = current.roll + (target.roll - current.roll) * k
        return if (abs(target.pitch - pitch) < SNAP_DEGREES && abs(target.roll - roll) < SNAP_DEGREES) {
            target
        } else {
            Tilt(pitch, roll)
        }
    }

    /** Whether a ball at height [y] with vertical speed [vy] is rolling on the floor at [floorY]. */
    fun isRolling(y: Float, vy: Float, radius: Float, floorY: Float): Boolean =
        y <= floorY + radius + CONTACT_SLOP && abs(vy) < CONTACT_VERTICAL_SPEED

    /**
     * One step of a ball rolling on the board, applied *after* the step integrated the full
     * gravity ([gx], [gy], [gz], in the board's frame) into its velocity: takes back the 2/7 of the
     * slope's pull that goes into spinning the ball, then brakes it by rolling resistance
     * [resistance] (a coefficient, times the normal force) and by [ROLLING_DRAG_PER_SECOND].
     *
     * Returns the new horizontal velocity. A ball on a slope shallower than its resistance never
     * starts; a ball on the flat comes to a stop instead of creeping forever.
     */
    fun roll(
        vx: Float,
        vz: Float,
        gx: Float,
        gy: Float,
        gz: Float,
        resistance: Float,
        dtSeconds: Float,
    ): Pair<Float, Float> {
        val spin = 1f - ROLLING_SHARE
        val rx = vx - spin * gx * dtSeconds
        val rz = vz - spin * gz * dtSeconds
        val speed = sqrt(rx * rx + rz * rz)
        val brake = (resistance * abs(gy) + ROLLING_DRAG_PER_SECOND * speed) * dtSeconds
        if (speed <= brake) return 0f to 0f
        val scale = (speed - brake) / speed
        return rx * scale to rz * scale
    }

    private const val SNAP_DEGREES = 0.01f
}
