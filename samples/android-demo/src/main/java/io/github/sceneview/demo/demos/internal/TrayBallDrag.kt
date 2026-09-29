package io.github.sceneview.demo.demos.internal

import android.view.MotionEvent
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Float4
import dev.romainguy.kotlin.math.rotation as rotationMatrix
import dev.romainguy.kotlin.math.transpose
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.toQuaternion
import kotlin.math.sqrt

/**
 * The finger-drag of the `rolling-balls` tray, as pure functions so it can be unit-tested without a
 * Filament engine (#4180).
 *
 * The tray hangs off a pivot at the origin rotated by `Rotation(pitch, 0, roll)`, and the simulation
 * runs in that pivot's flat frame. A touch is a world-space ray from the camera; everything here
 * first brings that ray into the tray's frame, where the felt is the plane `y = floor` and every
 * ball is a sphere at its node position:
 *
 * - [pickBall] — which ball the finger landed on (the nearest one the ray passes within reach of);
 * - [projectOnPlane] — where the finger is on the horizontal plane the held ball slides along;
 * - [ThrowVelocityTracker] — how fast the finger was moving when it let go, so a flick throws;
 * - [pointerStep] — which finger the grab belongs to, when several touch the screen.
 */
object TrayBallDrag {

    /**
     * Extra reach around a ball's radius, in metres, for a finger to grab it. The smallest ball is
     * 6 cm across and a fingertip covers more than that at the default framing, so an exact
     * ray-sphere test would miss a touch that visibly lands on the ball's edge.
     */
    const val PICK_SLOP: Float = 0.035f

    /**
     * How far above the felt a held ball is carried, in metres. Just enough for its shadow to
     * separate from it — the cue that it is in the hand, not rolling on its own.
     */
    const val HOLD_LIFT: Float = 0.02f

    /** Fastest throw, in m/s: a hard flick still lands on the tray instead of tunnelling a rail. */
    const val MAX_THROW_SPEED: Float = 4.5f

    /** Span of finger history the release velocity is measured over, in milliseconds. */
    const val THROW_WINDOW_MS: Long = 80L

    /** A world-space [direction] (or a point about the pivot) expressed in the tray's own frame. */
    fun toTrayFrame(pitchDegrees: Float, rollDegrees: Float, v: Float3): Float3 {
        if (pitchDegrees == 0f && rollDegrees == 0f) return v
        val trayRotation = rotationMatrix(Rotation(pitchDegrees, 0f, rollDegrees).toQuaternion())
        val local = transpose(trayRotation) * Float4(v.x, v.y, v.z, 0f)
        return Float3(local.x, local.y, local.z)
    }

    /** A candidate ball for [pickBall]: its [id], its centre in the tray's frame and its radius. */
    data class Candidate(val id: Int, val center: Float3, val radius: Float)

    /**
     * The id of the ball the ray from [origin] along [direction] (both in the tray's frame) reaches
     * first within [PICK_SLOP] of its surface, or `null` when it passes clear of all of them.
     */
    fun pickBall(origin: Float3, direction: Float3, candidates: Iterable<Candidate>): Int? {
        val d = normalizeOrNull(direction) ?: return null
        var bestId: Int? = null
        var bestT = Float.MAX_VALUE
        for (c in candidates) {
            val reach = c.radius + PICK_SLOP
            val t = raySphere(origin, d, c.center, reach) ?: continue
            if (t < bestT) {
                bestT = t
                bestId = c.id
            }
        }
        return bestId
    }

    /**
     * Where the ray from [origin] along [direction] (tray frame) crosses the horizontal plane
     * `y = [planeY]`, or `null` when it runs parallel to or away from it.
     */
    fun projectOnPlane(origin: Float3, direction: Float3, planeY: Float): Float3? {
        if (direction.y > -1e-4f && direction.y < 1e-4f) return null
        val t = (planeY - origin.y) / direction.y
        if (t <= 0f) return null
        return Float3(origin.x + direction.x * t, planeY, origin.z + direction.z * t)
    }

    /** What a touch event means for the finger that owns the grab (a held ball or a tilt). */
    enum class PointerStep {
        /** The first finger went down: it may pick a ball or start a tilt. */
        Begin,

        /** The owning finger moved: follow it. */
        Follow,

        /** The owning finger lifted: let go, with its velocity. */
        End,

        /** The system took the gesture away: let go without a throw. */
        Cancel,

        /** Not the owning finger, or nothing is owned: leave the grab as it is. */
        Ignore,
    }

    /**
     * Routes one touch event of [actionMasked] against the grab owned by [ownerPointerId] (`null`
     * when nothing is owned). [actionPointerId] is the id of the pointer the action is about — for
     * `ACTION_POINTER_UP`, the finger that lifted.
     *
     * The grab belongs to the finger that started it: a second finger never takes the ball over
     * (reading index 0 would snap it to whichever finger is listed first), and the owning finger
     * lifting while another stays down releases it, instead of leaving it held with no finger on it.
     */
    fun pointerStep(actionMasked: Int, actionPointerId: Int, ownerPointerId: Int?): PointerStep =
        when (actionMasked) {
            MotionEvent.ACTION_DOWN -> PointerStep.Begin
            MotionEvent.ACTION_MOVE -> if (ownerPointerId != null) PointerStep.Follow else PointerStep.Ignore
            MotionEvent.ACTION_POINTER_UP ->
                if (ownerPointerId != null && actionPointerId == ownerPointerId) PointerStep.End else PointerStep.Ignore
            // The last finger up: if a grab is still owned, it is this finger's.
            MotionEvent.ACTION_UP -> if (ownerPointerId != null) PointerStep.End else PointerStep.Ignore
            MotionEvent.ACTION_CANCEL -> if (ownerPointerId != null) PointerStep.Cancel else PointerStep.Ignore
            else -> PointerStep.Ignore
        }

    /** Nearest non-negative distance along unit [d] at which the ray enters the sphere, if any. */
    private fun raySphere(origin: Float3, d: Float3, center: Float3, radius: Float): Float? {
        val ox = origin.x - center.x
        val oy = origin.y - center.y
        val oz = origin.z - center.z
        val b = ox * d.x + oy * d.y + oz * d.z
        val c = ox * ox + oy * oy + oz * oz - radius * radius
        val discriminant = b * b - c
        if (discriminant < 0f) return null
        val root = sqrt(discriminant)
        val near = -b - root
        val far = -b + root
        return when {
            near >= 0f -> near
            far >= 0f -> 0f // The eye is inside the reach: the ball is right there.
            else -> null
        }
    }

    private fun normalizeOrNull(v: Float3): Float3? {
        val length = sqrt(v.x * v.x + v.y * v.y + v.z * v.z)
        if (length < 1e-6f) return null
        return Float3(v.x / length, v.y / length, v.z / length)
    }

    /**
     * The finger's horizontal velocity on the tray, from the samples of the last
     * [THROW_WINDOW_MS]: the slope between the oldest and the newest sample inside that window.
     * A finger that stopped before lifting has no samples left in the window but the last one, so
     * it lets the ball go gently instead of replaying a flick that ended long ago.
     */
    class ThrowVelocityTracker {
        private val times = LongArray(CAPACITY)
        private val xs = FloatArray(CAPACITY)
        private val zs = FloatArray(CAPACITY)
        private var count = 0
        private var head = 0

        fun clear() {
            count = 0
            head = 0
        }

        fun add(timeMs: Long, x: Float, z: Float) {
            times[head] = timeMs
            xs[head] = x
            zs[head] = z
            head = (head + 1) % CAPACITY
            if (count < CAPACITY) count++
        }

        /** Velocity `(vx, vz)` in m/s at [nowMs], clamped to [MAX_THROW_SPEED]. */
        fun velocity(nowMs: Long): Pair<Float, Float> {
            if (count < 2) return 0f to 0f
            val newest = (head - 1 + CAPACITY) % CAPACITY
            if (nowMs - times[newest] > THROW_WINDOW_MS) return 0f to 0f
            var oldest = newest
            for (k in 1 until count) {
                val i = (newest - k + CAPACITY) % CAPACITY
                if (times[newest] - times[i] > THROW_WINDOW_MS) break
                oldest = i
            }
            val dtMs = times[newest] - times[oldest]
            if (dtMs <= 0L) return 0f to 0f
            val seconds = dtMs / 1000f
            var vx = (xs[newest] - xs[oldest]) / seconds
            var vz = (zs[newest] - zs[oldest]) / seconds
            val speed = sqrt(vx * vx + vz * vz)
            if (speed > MAX_THROW_SPEED) {
                vx *= MAX_THROW_SPEED / speed
                vz *= MAX_THROW_SPEED / speed
            }
            return vx to vz
        }

        private companion object {
            const val CAPACITY = 32
        }
    }
}
