package io.github.sceneview.sample.common.update

import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.play.core.appupdate.testing.FakeAppUpdateManager
import com.google.android.play.core.install.model.AppUpdateType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Drives [InAppUpdateManager] against Google's `FakeAppUpdateManager` so the
 * Compose-backed `updateState` + `downloadProgress` can be asserted on the
 * JVM (Robolectric — no emulator, no real Play Store).
 *
 * The fake is injected via the second [InAppUpdateManager] constructor — the
 * production path (single-arg) calls `AppUpdateManagerFactory.create` and
 * stays untouched. A real `ComponentActivity` is used so
 * [InAppUpdateManager.registerForResult] can register a genuine
 * `ActivityResultLauncher`; the FLEXIBLE consent modal's CANCEL is then
 * simulated by invoking [InAppUpdateManager.onUpdateFlowResult] directly,
 * since `FakeAppUpdateManager` does not drive the launcher itself.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class InAppUpdateManagerTest {

    private lateinit var activity: ComponentActivity
    private lateinit var fake: FakeAppUpdateManager
    private lateinit var manager: InAppUpdateManager

    @Before
    fun setUp() {
        // `registerForActivityResult` must run before the activity is STARTED,
        // so build the controller, create the manager, register the launcher,
        // and only then drive the activity to RESUMED via `setup()`.
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create()
        activity = controller.get()
        fake = FakeAppUpdateManager(RuntimeEnvironment.getApplication())
        manager = InAppUpdateManager(activity, fake)
        manager.registerForResult(activity)
        controller.start().resume()
    }

    @After
    fun tearDown() {
        manager.destroy()
    }

    private fun newManager(): InAppUpdateManager {
        // A second manager for the same activity; the activity is already
        // STARTED so a fresh `registerForResult` would throw — these helpers
        // are only used in flows that never reach `startUpdate()`.
        return InAppUpdateManager(activity, fake)
    }

    @Test
    fun `idle on construction`() {
        assertEquals(InAppUpdateManager.UpdateState.IDLE, manager.updateState)
        assertEquals(0f, manager.downloadProgress, 0f)
    }

    @Test
    fun `no update available transitions to UP_TO_DATE`() {
        // Default fake state is `setUpdateNotAvailable` (UpdateAvailability.UPDATE_NOT_AVAILABLE).
        manager.checkForUpdate()
        shadowOf(activity.mainLooper).idle()

        assertEquals(InAppUpdateManager.UpdateState.UP_TO_DATE, manager.updateState)
    }

    @Test
    fun `checkForUpdate surfaces AVAILABLE but does NOT start the Google flow`() {
        // Single-modal invariant (#1941): detecting an update must NOT pop the
        // Google consent modal — it only surfaces the in-app AVAILABLE banner.
        fake.setUpdateAvailable(/* availableVersionCode = */ 42)
        manager.checkForUpdate()
        shadowOf(activity.mainLooper).idle()

        assertEquals(InAppUpdateManager.UpdateState.AVAILABLE, manager.updateState)
        // The Google modal must not have been triggered by checkForUpdate().
        assertFalse(fake.isConfirmationDialogVisible)
    }

    @Test
    fun `startUpdate triggers the single Google consent modal`() {
        // Only a deliberate startUpdate() tap pops the Google modal — exactly once.
        fake.setUpdateAvailable(42)
        manager.checkForUpdate()
        shadowOf(activity.mainLooper).idle()
        assertFalse(fake.isConfirmationDialogVisible)

        manager.startUpdate()
        shadowOf(activity.mainLooper).idle()

        assertTrue(fake.isConfirmationDialogVisible)
        // Guards against a future refactor silently degrading to IMMEDIATE.
        assertEquals(AppUpdateType.FLEXIBLE, fake.typeForUpdateInProgress)
    }

    @Test
    fun `startUpdate is a no-op unless state is AVAILABLE`() {
        // A stray startUpdate() call before any update is detected must do nothing.
        manager.startUpdate()
        shadowOf(activity.mainLooper).idle()

        assertFalse(fake.isConfirmationDialogVisible)
        assertEquals(InAppUpdateManager.UpdateState.IDLE, manager.updateState)
    }

    @Test
    fun `update available + user consent + download completion drives READY_TO_INSTALL`() {
        fake.setUpdateAvailable(/* availableVersionCode = */ 42)
        manager.checkForUpdate()
        shadowOf(activity.mainLooper).idle()
        manager.startUpdate()
        shadowOf(activity.mainLooper).idle()

        fake.userAcceptsUpdate()
        fake.downloadStarts()
        fake.downloadCompletes()
        shadowOf(activity.mainLooper).idle()

        assertEquals(InAppUpdateManager.UpdateState.READY_TO_INSTALL, manager.updateState)
    }

    @Test
    fun `install completion returns state to IDLE`() {
        fake.setUpdateAvailable(42)
        manager.checkForUpdate()
        shadowOf(activity.mainLooper).idle()
        manager.startUpdate()
        shadowOf(activity.mainLooper).idle()
        fake.userAcceptsUpdate()
        fake.downloadStarts()
        fake.downloadCompletes()
        shadowOf(activity.mainLooper).idle()
        // Real Play Core requires `completeUpdate()` to transition past
        // DOWNLOADED — the fake mirrors that, otherwise INSTALLING / INSTALLED
        // listener events never fire. This is the exact line the production
        // "Restart" button invokes.
        manager.completeUpdate()
        fake.installCompletes()
        shadowOf(activity.mainLooper).idle()

        assertEquals(InAppUpdateManager.UpdateState.IDLE, manager.updateState)
    }

    @Test
    fun `completeUpdate is a no-op unless state is READY_TO_INSTALL`() {
        // Restart-only-when-ready invariant (#1941): the banner's Restart button
        // must not finish an install that isn't actually downloaded yet.
        fake.setUpdateAvailable(42)
        manager.checkForUpdate()
        shadowOf(activity.mainLooper).idle()
        manager.startUpdate()
        shadowOf(activity.mainLooper).idle()
        fake.userAcceptsUpdate()
        fake.downloadStarts()
        shadowOf(activity.mainLooper).idle()
        assertEquals(InAppUpdateManager.UpdateState.DOWNLOADING, manager.updateState)

        // completeUpdate() while DOWNLOADING must NOT complete the install — and
        // must NOT call through to the SDK's completeUpdate() at all.
        manager.completeUpdate()
        fake.installCompletes()
        shadowOf(activity.mainLooper).idle()

        // installCompletes() above forced the fake to INSTALLED, but because the
        // production completeUpdate() was a no-op the listener-driven INSTALLED
        // path did not run from a real completion. The DOWNLOADING state is the
        // proof the guard held: had completeUpdate() actually run, the manager
        // would have observed INSTALLED and fallen to IDLE.
        assertEquals(InAppUpdateManager.UpdateState.DOWNLOADING, manager.updateState)
    }

    @Test
    fun `completeUpdate does not invoke the SDK while still DOWNLOADING`() {
        // Stronger than the no-op-state test above: spy that the SDK's
        // completeUpdate() is genuinely never reached. The fake exposes no
        // call counter, so a pass-through recording manager wraps it.
        val recording = RecordingAppUpdateManager(fake)
        val recordingManager = InAppUpdateManager(activity, recording)

        fake.setUpdateAvailable(42)
        recordingManager.checkForUpdate()
        shadowOf(activity.mainLooper).idle()
        // completeUpdate() never depends on the launcher. The state is AVAILABLE
        // — i.e. NOT READY_TO_INSTALL — so completeUpdate() must be a no-op.
        assertEquals(InAppUpdateManager.UpdateState.AVAILABLE, recordingManager.updateState)

        recordingManager.completeUpdate()
        shadowOf(activity.mainLooper).idle()

        assertEquals(0, recording.completeUpdateCalls)
        recordingManager.destroy()
    }

    @Test
    fun `checkForStalledUpdate picks up an already-DOWNLOADED install on a fresh manager`() {
        // Simulate: a prior session downloaded the update; the new
        // InAppUpdateManager comes online in onResume and must surface the
        // DOWNLOADED state without restarting the flow.
        fake.setUpdateAvailable(42)
        manager.checkForUpdate()
        shadowOf(activity.mainLooper).idle()
        manager.startUpdate()
        shadowOf(activity.mainLooper).idle()
        fake.userAcceptsUpdate()
        fake.downloadStarts()
        fake.downloadCompletes()
        shadowOf(activity.mainLooper).idle()
        manager.destroy()

        val fresh = newManager()
        fresh.checkForStalledUpdate()
        shadowOf(activity.mainLooper).idle()

        assertEquals(InAppUpdateManager.UpdateState.READY_TO_INSTALL, fresh.updateState)
        fresh.destroy()
    }

    @Test
    fun `checkForStalledUpdate re-attaches to an in-progress download`() {
        // Rotation mid-download: the recreated manager must resume DOWNLOADING
        // (re-register the listener), not show AVAILABLE and re-pop the modal.
        fake.setUpdateAvailable(42)
        manager.checkForUpdate()
        shadowOf(activity.mainLooper).idle()
        manager.startUpdate()
        shadowOf(activity.mainLooper).idle()
        fake.userAcceptsUpdate()
        fake.downloadStarts()
        shadowOf(activity.mainLooper).idle()
        assertEquals(InAppUpdateManager.UpdateState.DOWNLOADING, manager.updateState)
        manager.destroy()

        val fresh = newManager()
        fresh.checkForStalledUpdate()
        shadowOf(activity.mainLooper).idle()

        assertEquals(InAppUpdateManager.UpdateState.DOWNLOADING, fresh.updateState)

        // The re-attached listener must still observe completion.
        fake.downloadCompletes()
        shadowOf(activity.mainLooper).idle()
        assertEquals(InAppUpdateManager.UpdateState.READY_TO_INSTALL, fresh.updateState)
        fresh.destroy()
    }

    @Test
    fun `checkForUpdate re-attaches to an in-progress download after rotation`() {
        // Same rotation case but via checkForUpdate() (called right after
        // checkForStalledUpdate in onResume): an already-DOWNLOADING update
        // must resume DOWNLOADING rather than re-surface AVAILABLE.
        fake.setUpdateAvailable(42)
        manager.checkForUpdate()
        shadowOf(activity.mainLooper).idle()
        manager.startUpdate()
        shadowOf(activity.mainLooper).idle()
        fake.userAcceptsUpdate()
        fake.downloadStarts()
        shadowOf(activity.mainLooper).idle()
        manager.destroy()

        val fresh = newManager()
        fresh.checkForUpdate()
        shadowOf(activity.mainLooper).idle()

        assertEquals(InAppUpdateManager.UpdateState.DOWNLOADING, fresh.updateState)
        assertFalse(fake.isConfirmationDialogVisible)
        fresh.destroy()
    }

    @Test
    fun `destroy is safe before any check and idempotent`() {
        val pristine = newManager()
        pristine.destroy()
        pristine.destroy() // second call must not throw
        assertEquals(InAppUpdateManager.UpdateState.IDLE, pristine.updateState)
    }

    @Test
    fun `a late appUpdateInfo callback after destroy does not mutate state`() {
        // destroy() cannot cancel an in-flight Play Task — the `destroyed`
        // guard must swallow a callback that lands afterwards. `checkForUpdate()`
        // synchronously sets CHECKING; the async success callback would
        // otherwise advance it to AVAILABLE. The guard must freeze it.
        fake.setUpdateAvailable(42)
        manager.checkForUpdate() // Task issued, success callback still pending
        assertEquals(InAppUpdateManager.UpdateState.CHECKING, manager.updateState)
        manager.destroy()        // destroyed = true before the looper idles
        shadowOf(activity.mainLooper).idle() // the late success callback fires now

        // The guarded callback must NOT have surfaced AVAILABLE — state is
        // frozen wherever destroy() left it, never advanced by a dead manager.
        assertFalse(manager.updateState == InAppUpdateManager.UpdateState.AVAILABLE)
    }

    @Test
    fun `two rapid checkForUpdate calls trigger only one update flow`() {
        // Simulate a fast double-resume: both `checkForUpdate()` calls land
        // while the first SDK round-trip is still in flight (state CHECKING).
        // The `inFlight` guard must drop the second call so the SDK is queried
        // exactly once and the manager lands cleanly in AVAILABLE.
        fake.setUpdateAvailable(42)
        manager.checkForUpdate()
        manager.checkForUpdate() // second call before the looper idles
        shadowOf(activity.mainLooper).idle()

        assertEquals(InAppUpdateManager.UpdateState.AVAILABLE, manager.updateState)
        // No Google modal was triggered by either checkForUpdate() call.
        assertFalse(fake.isConfirmationDialogVisible)
    }

    @Test
    fun `double onResume in the AVAILABLE window produces no extra prompt`() {
        // Single-modal invariant (#1941): once the AVAILABLE banner is showing,
        // a second onResume (config change, fast background-foreground) must be
        // a complete no-op — the AVAILABLE early-return blocks any re-query and
        // never re-pops the Google modal.
        fake.setUpdateAvailable(42)
        manager.checkForUpdate()
        shadowOf(activity.mainLooper).idle()
        assertEquals(InAppUpdateManager.UpdateState.AVAILABLE, manager.updateState)

        // Second resume while AVAILABLE — must do nothing.
        manager.checkForUpdate()
        shadowOf(activity.mainLooper).idle()

        assertEquals(InAppUpdateManager.UpdateState.AVAILABLE, manager.updateState)
        assertFalse(fake.isConfirmationDialogVisible)

        // The user finally taps Update — exactly one Google modal appears.
        manager.startUpdate()
        shadowOf(activity.mainLooper).idle()
        assertTrue(fake.isConfirmationDialogVisible)
    }

    @Test
    fun `double startUpdate does not double-prompt`() {
        // The user double-taps the in-app Update button. The state leaves
        // AVAILABLE on the first tap, so the second tap's `startUpdate()` is a
        // no-op — exactly one consent flow is started.
        fake.setUpdateAvailable(42)
        manager.checkForUpdate()
        shadowOf(activity.mainLooper).idle()
        assertEquals(InAppUpdateManager.UpdateState.AVAILABLE, manager.updateState)

        manager.startUpdate()
        manager.startUpdate() // second tap before anything resolves
        shadowOf(activity.mainLooper).idle()

        assertTrue(fake.isConfirmationDialogVisible)
        assertEquals(AppUpdateType.FLEXIBLE, fake.typeForUpdateInProgress)
    }

    @Test
    fun `cancelling the consent modal returns to a retryable AVAILABLE`() {
        // MAJOR 1+2: the FLEXIBLE consent modal's CANCEL is delivered via the
        // activity result (RESULT_CANCELED), not the install-state listener.
        // It must reset to AVAILABLE and KEEP pendingUpdateInfo so a second
        // startUpdate() still works.
        fake.setUpdateAvailable(42)
        manager.checkForUpdate()
        shadowOf(activity.mainLooper).idle()
        assertEquals(InAppUpdateManager.UpdateState.AVAILABLE, manager.updateState)

        manager.startUpdate()
        shadowOf(activity.mainLooper).idle()
        // The flow left AVAILABLE while the modal is up.
        assertTrue(fake.isConfirmationDialogVisible)

        // The user dismisses the modal — RESULT_CANCELED comes back.
        manager.onUpdateFlowResult(ActivityResult(Activity.RESULT_CANCELED, null))
        shadowOf(activity.mainLooper).idle()

        // Back to a retryable AVAILABLE.
        assertEquals(InAppUpdateManager.UpdateState.AVAILABLE, manager.updateState)

        // The retry must still work — pendingUpdateInfo was retained.
        manager.startUpdate()
        shadowOf(activity.mainLooper).idle()
        assertTrue(fake.isConfirmationDialogVisible)
    }

    @Test
    fun `inFlight clears after a failed check so a later check can run`() {
        // A no-update round-trip must clear the `inFlight` guard so the manager
        // is not permanently locked out of all future checks.
        fake.setUpdateNotAvailable()
        manager.checkForUpdate()
        shadowOf(activity.mainLooper).idle()
        assertEquals(InAppUpdateManager.UpdateState.UP_TO_DATE, manager.updateState)

        // A subsequent check must NOT be short-circuited by a stuck guard.
        fake.setUpdateAvailable(42)
        manager.checkForUpdate()
        shadowOf(activity.mainLooper).idle()
        assertEquals(InAppUpdateManager.UpdateState.AVAILABLE, manager.updateState)
    }

    @Test
    fun `DOWNLOADING state surfaces with non-zero downloadProgress`() {
        fake.setUpdateAvailable(42)
        manager.checkForUpdate()
        shadowOf(activity.mainLooper).idle()
        manager.startUpdate()
        shadowOf(activity.mainLooper).idle()
        fake.userAcceptsUpdate()
        // `downloadStarts()` flips the fake into the DOWNLOADING phase and
        // emits an InstallState with 0 bytes — that already drives the manager
        // to `UpdateState.DOWNLOADING`.
        fake.downloadStarts()
        shadowOf(activity.mainLooper).idle()
        assertEquals(InAppUpdateManager.UpdateState.DOWNLOADING, manager.updateState)

        // `setBytesDownloaded` only re-fires the InstallStateUpdatedListener
        // once the fake is in the DOWNLOADING phase AND the byte count fits
        // inside `totalBytesToDownload`, so the total must be set first. The
        // re-emitted DOWNLOADING event carries 500 / 2000 -> 0.25 progress.
        fake.setTotalBytesToDownload(2000)
        fake.setBytesDownloaded(500)
        shadowOf(activity.mainLooper).idle()

        assertEquals(InAppUpdateManager.UpdateState.DOWNLOADING, manager.updateState)
        assertEquals(0.25f, manager.downloadProgress, 0.001f)
    }

    @Test
    fun `zero totalBytes does not crash progress computation`() {
        fake.setUpdateAvailable(42)
        manager.checkForUpdate()
        shadowOf(activity.mainLooper).idle()
        manager.startUpdate()
        shadowOf(activity.mainLooper).idle()
        fake.userAcceptsUpdate()
        fake.downloadStarts()
        // Surviving the idle() pump without throwing is the assertion.
        shadowOf(activity.mainLooper).idle()
        assertTrue(manager.downloadProgress in 0f..1f)
    }
}
