package io.github.sceneview.sample.common.update

import com.google.android.gms.tasks.Task
import com.google.android.play.core.appupdate.AppUpdateManager

/**
 * A pass-through [AppUpdateManager] that forwards every call to [delegate]
 * (a `FakeAppUpdateManager`) via Kotlin interface delegation, while counting
 * how many times [completeUpdate] was invoked.
 *
 * Used by the test that proves [InAppUpdateManager.completeUpdate] genuinely
 * does NOT reach the SDK while the state is still `DOWNLOADING` — the
 * `FakeAppUpdateManager` exposes no call counter of its own.
 */
class RecordingAppUpdateManager(
    private val delegate: AppUpdateManager,
) : AppUpdateManager by delegate {

    var completeUpdateCalls = 0
        private set

    override fun completeUpdate(): Task<Void> {
        completeUpdateCalls++
        return delegate.completeUpdate()
    }
}
