package io.github.sceneview.demo.common

import android.Manifest
import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import io.github.sceneview.demo.R
import io.github.sceneview.demo.theme.SceneViewDemoTheme
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
 * settings page. Robolectric answers neither; those are in the PR's emulator captures.
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
