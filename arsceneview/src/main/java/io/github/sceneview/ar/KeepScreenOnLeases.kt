package io.github.sceneview.ar

import android.view.View
import java.util.WeakHashMap

/**
 * Counts the AR scenes that hold one host view's display awake.
 *
 * Two scenes share the host view while one animates out and the next animates in. Each restoring
 * the flag it found would let the display sleep under the scene that stays, then leave it on for
 * good. The flag is handed back once, by the last scene to leave, as the first one found it
 * (#4392). Main thread only, like every caller.
 */
internal object KeepScreenOnLeases {
    private class Lease(val before: Boolean, var holders: Int = 0)

    private val leases = WeakHashMap<View, Lease>()

    fun acquire(view: View) {
        val lease = leases.getOrPut(view) { Lease(before = view.keepScreenOn) }
        lease.holders++
        view.keepScreenOn = true
    }

    fun release(view: View) {
        val lease = leases[view] ?: return
        lease.holders--
        if (lease.holders > 0) return
        leases.remove(view)
        view.keepScreenOn = lease.before
    }
}
