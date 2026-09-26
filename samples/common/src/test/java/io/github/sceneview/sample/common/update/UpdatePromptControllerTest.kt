package io.github.sceneview.sample.common.update

import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.play.core.appupdate.testing.FakeAppUpdateManager
import com.google.android.play.core.install.model.AppUpdateType
import io.github.sceneview.sample.common.update.InAppUpdateManager.UpdateState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The update prompt policy, end to end on the JVM: the real [InAppUpdateManager] against
 * Google's `FakeAppUpdateManager`, with [UpdatePromptController] deciding what is shown.
 *
 * Covers the behaviours the snackbar is built on — available → prompt, Update → waiting
 * in the same frame, downloading with its progress, downloaded → Restart, failed → Retry,
 * dismissed → quiet for 24 h — plus the edges that would make it nag or go silent for good.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class UpdatePromptControllerTest {

    private class MemoryStore(override var availableDismissedAtMillis: Long = 0L) : UpdatePromptStore

    private lateinit var activity: ComponentActivity
    private lateinit var fake: FakeAppUpdateManager
    private lateinit var manager: InAppUpdateManager
    private val store = MemoryStore()
    private var now = 1_000_000_000_000L

    @Before
    fun setUp() {
        // The consent launcher must be registered before the activity is STARTED.
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

    private fun newController() = UpdatePromptController(manager, store, clock = { now })

    private fun idle() = shadowOf(activity.mainLooper).idle()

    private fun resumeWithUpdateAvailable() {
        fake.setUpdateAvailable(42)
        manager.checkForUpdate()
        idle()
    }

    @Test
    fun `nothing to show while no update is available`() {
        val prompt = newController()
        manager.checkForUpdate()
        idle()

        assertEquals(InAppUpdateManager.UpdateState.UP_TO_DATE, manager.updateState)
        assertNull(prompt.prompt)
    }

    @Test
    fun `an available update is offered`() {
        val prompt = newController()
        resumeWithUpdateAvailable()

        assertEquals(UpdatePrompt.Available, prompt.prompt)
        // Offering is not starting: Google's modal only follows a tap on Update.
        assertFalse(fake.isConfirmationDialogVisible)
    }

    @Test
    fun `Update starts the flexible flow and answers the tap in the same frame`() {
        val prompt = newController()
        resumeWithUpdateAvailable()

        prompt.onAction(UpdatePrompt.Available)
        // No looper pump: the snackbar already says "Waiting for Google Play…".
        assertEquals(UpdatePrompt.Waiting, prompt.prompt)
        idle()

        assertTrue(fake.isConfirmationDialogVisible)
        assertEquals(AppUpdateType.FLEXIBLE, fake.typeForUpdateInProgress)
        assertEquals(UpdatePrompt.Waiting, prompt.prompt)
        // Answering is not dismissing: nothing is snoozed for the next launches.
        assertEquals(0L, store.availableDismissedAtMillis)
    }

    @Test
    fun `a finished download asks for a restart, and Restart completes the install`() {
        val prompt = newController()
        resumeWithUpdateAvailable()
        prompt.onAction(UpdatePrompt.Available)
        idle()

        fake.userAcceptsUpdate()
        idle()
        assertEquals(UpdatePrompt.Waiting, prompt.prompt)

        fake.downloadStarts()
        idle()
        assertEquals("indeterminate until Play knows the size", UpdatePrompt.Downloading(null), prompt.prompt)

        fake.setTotalBytesToDownload(1_000)
        fake.setBytesDownloaded(420)
        idle()
        val downloading = prompt.prompt as UpdatePrompt.Downloading
        assertEquals(0.42f, downloading.progress!!, 0.001f)

        fake.downloadCompletes()
        idle()
        assertEquals(UpdatePrompt.ReadyToInstall, prompt.prompt)

        prompt.onAction(UpdatePrompt.ReadyToInstall)
        idle()
        assertTrue(fake.isInstallSplashScreenVisible)
    }

    @Test
    fun `a cancelled consent modal brings the offer back`() {
        val prompt = newController()
        resumeWithUpdateAvailable()
        prompt.onAction(UpdatePrompt.Available)
        idle()

        fake.userRejectsUpdate()
        manager.onUpdateFlowResult(ActivityResult(Activity.RESULT_CANCELED, null))

        assertEquals(UpdatePrompt.Available, prompt.prompt)
        assertEquals("a cancel is not a snooze", 0L, store.availableDismissedAtMillis)
    }

    @Test
    fun `a failed download offers Retry, and Retry starts over`() {
        val prompt = newController()
        resumeWithUpdateAvailable()
        prompt.onAction(UpdatePrompt.Available)
        idle()
        fake.userAcceptsUpdate()
        fake.downloadStarts()
        fake.downloadFails()
        idle()
        assertEquals(UpdatePrompt.Failed, prompt.prompt)

        prompt.onAction(UpdatePrompt.Failed)
        assertEquals(UpdatePrompt.Waiting, prompt.prompt)
        idle()
        assertTrue(fake.isConfirmationDialogVisible)
    }

    @Test
    fun `closing the failure clears it without snoozing the offer`() {
        val prompt = newController()
        resumeWithUpdateAvailable()
        prompt.onAction(UpdatePrompt.Available)
        idle()
        fake.userAcceptsUpdate()
        fake.downloadStarts()
        fake.downloadFails()
        idle()

        prompt.onDismissed(UpdatePrompt.Failed)

        assertNull(prompt.prompt)
        assertEquals(InAppUpdateManager.UpdateState.IDLE, manager.updateState)
        assertEquals(0L, store.availableDismissedAtMillis)
    }

    @Test
    fun `manager state maps to the prompt`() {
        assertEquals(UpdatePrompt.Available, promptFor(UpdateState.AVAILABLE, null, availableSuppressed = false))
        assertNull(promptFor(UpdateState.AVAILABLE, null, availableSuppressed = true))
        assertEquals(UpdatePrompt.Waiting, promptFor(UpdateState.PENDING, null, availableSuppressed = true))
        assertEquals(UpdatePrompt.Downloading(null), promptFor(UpdateState.DOWNLOADING, null, availableSuppressed = true))
        assertEquals(UpdatePrompt.Downloading(0.5f), promptFor(UpdateState.DOWNLOADING, 0.5f, availableSuppressed = false))
        assertEquals(UpdatePrompt.ReadyToInstall, promptFor(UpdateState.READY_TO_INSTALL, 1f, availableSuppressed = true))
        assertEquals(UpdatePrompt.Failed, promptFor(UpdateState.FAILED, null, availableSuppressed = true))
        assertNull(promptFor(UpdateState.IDLE, null, availableSuppressed = false))
        assertNull(promptFor(UpdateState.CHECKING, null, availableSuppressed = false))
        assertNull(promptFor(UpdateState.UP_TO_DATE, null, availableSuppressed = false))
    }

    @Test
    fun `a dismissed offer stays away for 24 hours, then comes back on the next launch`() {
        val first = newController()
        resumeWithUpdateAvailable()
        assertEquals(UpdatePrompt.Available, first.prompt)

        first.onDismissed(UpdatePrompt.Available)
        assertNull(first.prompt)
        assertEquals(now, store.availableDismissedAtMillis)

        // Every later resume or launch inside the window: still quiet.
        now += UpdatePromptController.DEFAULT_SNOOZE_MILLIS - 1
        assertNull(newController().prompt)

        // First launch after the window: offered again.
        now += 1
        assertEquals(UpdatePrompt.Available, newController().prompt)
    }

    @Test
    fun `a dismissal does not hide the restart the user asked for`() {
        store.availableDismissedAtMillis = now
        val prompt = newController()
        resumeWithUpdateAvailable()
        assertNull(prompt.prompt)

        // A download started before the dismissal, e.g. in an earlier session.
        manager.startUpdate()
        idle()
        fake.userAcceptsUpdate()
        fake.downloadStarts()
        fake.downloadCompletes()
        idle()

        assertEquals(UpdatePrompt.ReadyToInstall, prompt.prompt)
    }

    @Test
    fun `dismissing the restart snackbar is not recorded as a snooze`() {
        newController().onDismissed(UpdatePrompt.ReadyToInstall)
        assertEquals(0L, store.availableDismissedAtMillis)
    }

    @Test
    fun `a clock that went backwards does not snooze forever`() {
        store.availableDismissedAtMillis = now + UpdatePromptController.DEFAULT_SNOOZE_MILLIS
        val prompt = newController()
        resumeWithUpdateAvailable()

        assertEquals(UpdatePrompt.Available, prompt.prompt)
    }

    @Test
    fun `the SharedPreferences store survives a new instance`() {
        val context = RuntimeEnvironment.getApplication()
        SharedPreferencesUpdatePromptStore(context).availableDismissedAtMillis = 1234L

        assertEquals(1234L, SharedPreferencesUpdatePromptStore(context).availableDismissedAtMillis)
    }
}
