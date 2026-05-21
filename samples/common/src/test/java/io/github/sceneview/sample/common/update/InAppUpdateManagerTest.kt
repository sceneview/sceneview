package io.github.sceneview.sample.common.update

import android.app.Activity
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
 * stays untouched.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class InAppUpdateManagerTest {

    private lateinit var activity: Activity
    private lateinit var fake: FakeAppUpdateManager
    private lateinit var manager: InAppUpdateManager

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        fake = FakeAppUpdateManager(RuntimeEnvironment.getApplication())
        manager = InAppUpdateManager(activity, fake)
    }

    @After
    fun tearDown() {
        manager.destroy()
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

        // completeUpdate() while DOWNLOADING must NOT complete the install.
        manager.completeUpdate()
        fake.installCompletes()
        shadowOf(activity.mainLooper).idle()

        // The install never completed, so state did not fall to IDLE.
        assertEquals(InAppUpdateManager.UpdateState.DOWNLOADING, manager.updateState)
    }

    @Test
    fun `checkForStalledUpdate picks up an already-DOWNLOADED install on a fresh manager`() {
        // Simulate: a prior session downloaded the update; the new
        // InAppUpdateManager comes online in onResume and must surface the
        // DOWNLOADED state without restarting the flow.
        fake.setUpdateAvailable(42)
        val priming = InAppUpdateManager(activity, fake)
        priming.checkForUpdate()
        shadowOf(activity.mainLooper).idle()
        priming.startUpdate()
        shadowOf(activity.mainLooper).idle()
        fake.userAcceptsUpdate()
        fake.downloadStarts()
        fake.downloadCompletes()
        shadowOf(activity.mainLooper).idle()
        priming.destroy()

        val fresh = InAppUpdateManager(activity, fake)
        fresh.checkForStalledUpdate()
        shadowOf(activity.mainLooper).idle()

        assertEquals(InAppUpdateManager.UpdateState.READY_TO_INSTALL, fresh.updateState)
        fresh.destroy()
    }

    @Test
    fun `destroy is safe before any check and idempotent`() {
        val pristine = InAppUpdateManager(activity, fake)
        pristine.destroy()
        pristine.destroy() // second call must not throw
        assertEquals(InAppUpdateManager.UpdateState.IDLE, pristine.updateState)
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
