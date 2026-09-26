package io.github.sceneview.sample.common.update

import android.os.SystemClock

/**
 * Decides whether [InAppUpdateManager.checkForUpdate] is worth a Play round-trip (#3939).
 *
 * `checkForUpdate` runs on every `onResume`, and every round-trip binds to the Play Store
 * service. Once Play has answered "nothing to do" (up to date, or the query failed), asking
 * again on the next resume a few seconds later cannot change the answer, so the check is
 * skipped until [minIntervalMillis] has passed.
 *
 * Only a quiet answer is throttled. A result that shows a flow — an update available, a
 * download running or finished — clears the throttle, so a manager recreated mid-flow (a
 * rotation during a download) still re-attaches on its first resume.
 *
 * [PROCESS] is shared by every [InAppUpdateManager] of the process, which makes the check run
 * once per process start and then at most once per interval. Not thread-safe: all calls come
 * from the main thread (see [InAppUpdateManager]'s threading notes).
 */
class UpdateCheckThrottle(
    private val minIntervalMillis: Long = DEFAULT_MIN_INTERVAL_MILLIS,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) {
    private var lastQuietResultAt: Long? = null

    /** True when no quiet answer was recorded, or the last one is older than the interval. */
    fun shouldCheck(): Boolean {
        val last = lastQuietResultAt ?: return true
        return clock() - last >= minIntervalMillis
    }

    /** Play answered with nothing to show: up to date, or the query failed. */
    fun recordQuietResult() {
        lastQuietResultAt = clock()
    }

    /** Play answered with a flow to show or resume: never skip the next check. */
    fun recordFlowResult() {
        lastQuietResultAt = null
    }

    companion object {
        /** Thirty minutes: a release published meanwhile is picked up on a later resume. */
        const val DEFAULT_MIN_INTERVAL_MILLIS: Long = 30 * 60 * 1000L

        /** The throttle every production [InAppUpdateManager] of this process shares. */
        val PROCESS: UpdateCheckThrottle = UpdateCheckThrottle()
    }
}
