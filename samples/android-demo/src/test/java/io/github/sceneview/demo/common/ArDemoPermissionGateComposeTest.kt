package io.github.sceneview.demo.common

import android.Manifest
import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import com.google.ar.core.ArCoreApk
import io.github.sceneview.ar.ARCameraPermissionRecovery
import io.github.sceneview.demo.R
import io.github.sceneview.demo.theme.SceneViewDemoTheme
import io.github.sceneview.demo.ui.ArLauncherScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The gate as composed (#4139): what it holds back is not in the composition at all — an
 * `ARScene` mounted behind a card would still start its session and its own permission
 * request — and a grant read on resume is enough to mount it, with no tap.
 *
 * What it is **not**: proof of the system dialog or of the round trip through the real
 * settings page. Those are covered by the owner's Pixel 4a checks.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ArDemoPermissionGateComposeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `content is not composed while the camera is refused`() {
        shadowOf(app).denyPermissions(Manifest.permission.CAMERA)
        setGate()

        composeRule.onNodeWithTag(CONTENT_TAG).assertDoesNotExist()
        composeRule.onNodeWithText(app.getString(R.string.ar_permission_required_title)).assertIsDisplayed()
        // Asking is still possible, so the way out is a button that asks — not settings.
        composeRule.onNodeWithText(app.getString(R.string.ar_permission_allow)).assertIsDisplayed()
    }

    @Test
    fun `a grant read on resume mounts the content with no tap`() {
        shadowOf(app).denyPermissions(Manifest.permission.CAMERA)
        setGate()
        composeRule.onNodeWithTag(CONTENT_TAG).assertDoesNotExist()

        // What the user does in system settings, then comes back.
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)

        composeRule.onNodeWithTag(CONTENT_TAG).assertIsDisplayed()
        composeRule.onNodeWithText(app.getString(R.string.ar_permission_required_title)).assertDoesNotExist()
    }

    @Test
    fun `a granted camera composes the content at once`() {
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        setGate()

        composeRule.onNodeWithTag(CONTENT_TAG).assertIsDisplayed()
    }

    /** A debug QA state stages a camera screen without a camera, so without the gate. */
    @Test
    fun `a disabled gate lets the content through`() {
        shadowOf(app).denyPermissions(Manifest.permission.CAMERA)
        setGate(enabled = false)

        composeRule.onNodeWithTag(CONTENT_TAG).assertIsDisplayed()
    }

    @Test
    fun `an ambiguous answer keeps both recovery actions and neutral copy`() {
        var requests = 0
        var settingsOpened = 0
        composeRule.setContent {
            SceneViewDemoTheme {
                ArCameraPermissionGate(
                    permission = ArCameraPermission(
                        granted = false,
                        shouldShowRationale = false,
                        recovery = ARCameraPermissionRecovery.RequestWithSettings,
                        request = { requests++ },
                        openSettings = { settingsOpened++ },
                    ),
                    blocked = { detail, action, onAction, onRequestAgain ->
                        ArPermissionCard("AR", detail, action, onAction, onRequestAgain)
                    },
                    content = { Box(Modifier.testTag(CONTENT_TAG)) },
                )
            }
        }
        composeRule.onNodeWithTag(CONTENT_TAG).assertDoesNotExist()
        composeRule.onNodeWithText(app.getString(R.string.ar_permission_fallback_subtitle)).assertIsDisplayed()
        composeRule.onNodeWithText(app.getString(R.string.ar_permission_blocked_subtitle)).assertDoesNotExist()
        val settingsButton = composeRule.onNodeWithText(app.getString(R.string.ar_permission_open_settings))
        val requestButton = composeRule.onNodeWithText(app.getString(R.string.ar_permission_allow))
        assertTrue(settingsButton.getUnclippedBoundsInRoot().bottom <= requestButton.getUnclippedBoundsInRoot().top)
        assertEquals("ambiguous recovery must not prompt automatically", 0, requests)
        settingsButton.performClick()
        assertEquals(1, settingsOpened)
        assertEquals(0, requests)
        requestButton.performClick()
        assertEquals(1, requests)
    }

    @Test
    fun `launcher leads with settings and keeps a secondary request after an ambiguous answer`() {
        var requests = 0
        var settingsOpened = 0
        var sessionsStarted = 0
        composeRule.setContent {
            SceneViewDemoTheme {
                ArLauncherScreen(
                    availability = ArCoreApk.Availability.SUPPORTED_INSTALLED,
                    cameraGranted = false,
                    cameraRecovery = ARCameraPermissionRecovery.RequestWithSettings,
                    onRequestCamera = { requests++ },
                    onOpenSettings = { settingsOpened++ },
                    onStartArSession = { sessionsStarted++ },
                    onArDemoClick = {},
                )
            }
        }
        val settingsButton = composeRule.onNodeWithText(app.getString(R.string.ar_permission_open_settings))
        val requestButton = composeRule.onNodeWithText(app.getString(R.string.ar_permission_allow))
        assertTrue(settingsButton.getUnclippedBoundsInRoot().bottom <= requestButton.getUnclippedBoundsInRoot().top)
        settingsButton.performClick()
        assertEquals(1, settingsOpened)
        assertEquals(0, requests)
        requestButton.performClick()
        assertEquals(1, requests)
        assertEquals(0, sessionsStarted)
    }

    private fun setGate(enabled: Boolean = true) {
        composeRule.setContent {
            SceneViewDemoTheme {
                ArDemoPermissionGate(title = "AR", onBack = {}, enabled = enabled) {
                    Box(Modifier.fillMaxSize().testTag(CONTENT_TAG))
                }
            }
        }
        composeRule.waitForIdle()
    }

    private companion object {
        const val CONTENT_TAG = "gated-content"
    }
}
