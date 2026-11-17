package io.github.sceneview.demo.demos.internal

import kotlin.math.hypot

/**
 * Whether the phone has left the one object it films ([ScanScope.Object]). An object is walked
 * round, the phone looking at it all the way: what it aims at stays put while the phone moves. A
 * room walked under "An object" aims all over the place, and its result was a few scraps of floor
 * with no word of why (2026-11-15, a bedroom walk replayed as an object).
 *
 * Measured on kept scans (2026-11-14): round an object, the point [AIM_M] ahead of the phone never
 * left its first place by more than 0.42 m across the ground, while the phone itself moved 1.24 m;
 * in three rooms it was over [LEAVE_M] away for [HOLD_MS] on end by second 15, 26 and 21.
 *
 * Where the phone first aimed, over [HOME_MS], is where the object stands. Aiming further than
 * [LEAVE_M] from there for [HOLD_MS] on end, it has left it, until it aims within [BACK_M] again.
 * A glance aside, shorter than that, leaves nothing.
 */
class ObjectStay {
    private var startedAt: Long? = null
    private var homeX = 0f
    private var homeZ = 0f
    private var homeCount = 0
    private var overSince: Long? = null
    private var away = false

    /** Whether the phone at [camera] has left its object at [nowMs]; as it last was while it is not known. */
    fun strayed(camera: DebugPose?, nowMs: Long): Boolean {
        val at = camera ?: return away
        // A camera looks down its own -Z.
        val ahead = at.rotate(0f, 0f, -1f)
        val x = at.x + ahead.x * AIM_M
        val z = at.z + ahead.z * AIM_M
        val started = startedAt ?: nowMs.also { startedAt = it }
        if (nowMs - started <= HOME_MS) {
            homeCount++
            homeX += (x - homeX) / homeCount
            homeZ += (z - homeZ) / homeCount
            return false
        }
        val off = hypot(x - homeX, z - homeZ)
        when {
            away -> if (off <= BACK_M) {
                away = false
                overSince = null
            }
            off > LEAVE_M -> {
                val since = overSince ?: nowMs.also { overSince = it }
                if (nowMs - since >= HOLD_MS) away = true
            }
            else -> overSince = null
        }
        return away
    }

    companion object {
        /** How far ahead of the phone the point it aims at is taken: an object is filmed from about there. */
        const val AIM_M = 0.8f

        /** The first aim is read over this long: a hand settling on the object, not one frame of it. */
        const val HOME_MS = 1_500L

        /** A sofa's length from where it was first aimed at: past this, the phone films something else. */
        const val LEAVE_M = 1.5f

        /** Aiming this close again, the phone is back on its object. */
        const val BACK_M = 1.1f

        /** How long the aim stays away before it counts: a look at the floor or the room is not a walk. */
        const val HOLD_MS = 1_500L
    }
}
