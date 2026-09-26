package io.github.sceneview.demo.update

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.android.play.core.appupdate.testing.FakeAppUpdateManager
import com.google.android.play.core.install.model.AppUpdateType
import io.github.sceneview.demo.theme.SceneViewDemoTheme
import io.github.sceneview.sample.common.update.InAppUpdateManager
import io.github.sceneview.sample.common.update.UpdatePromptController
import io.github.sceneview.sample.common.update.UpdatePromptStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Play update snackbar as the user meets it: real [InAppUpdateManager], real
 * [UpdatePromptController], the real [UpdatePromptHost], Google's `FakeAppUpdateManager`
 * standing in for Play (a real update needs a Play-installed build and a newer release).
 *
 * #3947: a tap on **Update** must be answered on screen at once — "Waiting for Google Play…",
 * then "Downloading update…" with its percentage — never by an empty screen until the
 * restart prompt.
 *
 * The activity is built by hand rather than by a compose rule because the consent-modal
 * launcher must be registered before the activity is STARTED, as `MainActivity` does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdateSnackbarTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private class MemoryStore(override var availableDismissedAtMillis: Long = 0L) : UpdatePromptStore

    private lateinit var activity: ComponentActivity
    private lateinit var fake: FakeAppUpdateManager
    private lateinit var manager: InAppUpdateManager
    private val store = MemoryStore()

    @Before
    fun setUp() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create()
        activity = controller.get()
        fake = FakeAppUpdateManager(activity)
        manager = InAppUpdateManager(activity, fake)
        manager.registerForResult(activity)
        // visible() attaches the window, which is where the compose rule finds the content.
        controller.start().resume().visible()
    }

    @After
    fun tearDown() {
        manager.destroy()
    }

    private fun showRootWithUpdateAvailable(enabled: Boolean = true) {
        fake.setUpdateAvailable(42)
        manager.checkForUpdate()
        val prompt = UpdatePromptController(manager, store)
        activity.setContent {
            SceneViewDemoTheme(dynamicColor = false) {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    snackbarHost = { UpdatePromptHost(controller = prompt, enabled = enabled) },
                ) { padding -> Box(Modifier.padding(padding).fillMaxSize()) }
            }
        }
        composeRule.waitForIdle()
    }

    private fun tapUpdate() {
        composeRule.onNodeWithText("Update").performClick()
        composeRule.waitForIdle()
    }

    // The narration ellipsis breathes, so its dots are not matched.
    private fun assertNarrating(message: String) {
        composeRule.onNodeWithText(message, substring = true).assertIsDisplayed()
    }

    @Test
    fun availableUpdate_showsTheSnackbar() {
        showRootWithUpdateAvailable()

        composeRule.onNodeWithText("Update available").assertIsDisplayed()
        composeRule.onNodeWithText("Update").assertIsDisplayed()
        assertFalse("detecting an update must not pop Google's modal", fake.isConfirmationDialogVisible)
    }

    @Test
    fun update_answersTheTap_showsTheDownload_thenRestartCompletesIt() {
        showRootWithUpdateAvailable()

        tapUpdate()
        assertTrue(fake.isConfirmationDialogVisible)
        assertEquals(AppUpdateType.FLEXIBLE, fake.typeForUpdateInProgress)
        composeRule.onNodeWithText("Update available").assertDoesNotExist()
        assertNarrating("Waiting for Google Play")

        fake.userAcceptsUpdate()
        composeRule.waitForIdle()
        assertNarrating("Waiting for Google Play")

        fake.downloadStarts()
        composeRule.waitForIdle()
        assertNarrating("Downloading update")
        composeRule.onNodeWithText("0%").assertDoesNotExist()

        fake.setTotalBytesToDownload(1_000)
        fake.setBytesDownloaded(420)
        composeRule.waitForIdle()
        assertNarrating("Downloading update")
        composeRule.onNodeWithText("42%").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Dismiss").assertDoesNotExist()

        fake.downloadCompletes()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Update ready").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Dismiss").assertDoesNotExist()
        composeRule.onNodeWithText("Restart").performClick()
        composeRule.waitForIdle()
        assertTrue(fake.isInstallSplashScreenVisible)
    }

    @Test
    fun aFailedDownload_offersRetry() {
        showRootWithUpdateAvailable()
        tapUpdate()
        fake.userAcceptsUpdate()
        fake.downloadStarts()
        fake.downloadFails()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Update failed").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Dismiss").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").performClick()
        composeRule.waitForIdle()

        assertNarrating("Waiting for Google Play")
        assertTrue(fake.isConfirmationDialogVisible)
    }

    @Test
    fun dismissingTheOffer_hidesIt_andSnoozesTheNextLaunches() {
        showRootWithUpdateAvailable()

        composeRule.onNodeWithContentDescription("Dismiss").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Update available").assertDoesNotExist()
        assertTrue(store.availableDismissedAtMillis > 0L)
        assertFalse(fake.isConfirmationDialogVisible)
    }

    @Test
    fun disabledHost_showsNothing() {
        // RootScreen passes enabled = false while an AR session owns the bottom of the screen.
        showRootWithUpdateAvailable(enabled = false)

        composeRule.onNodeWithText("Update available").assertDoesNotExist()
    }
}
