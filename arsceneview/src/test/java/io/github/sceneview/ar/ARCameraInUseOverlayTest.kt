package io.github.sceneview.ar

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Exercises the real arbiter and card together, without requiring Filament or an ARCore camera. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-notnight-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ARCameraInUseOverlayTest {

    @get:Rule(order = 0)
    val registerActivity = object : ExternalResource() {
        override fun before() {
            val context = RuntimeEnvironment.getApplication()
            shadowOf(context.packageManager).addActivityIfNotPresent(
                ComponentName(context.packageName, ComponentActivity::class.java.name),
            )
        }
    }

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

    @Test
    fun lightTheme_theFirstViewExplainsItsWaitAndClearsWhenTheSecondCloses() = assertCameraHandover()

    @Test
    @Config(qualifiers = "w411dp-h891dp-night-xhdpi")
    fun darkTheme_theFirstViewExplainsItsWaitAndClearsWhenTheSecondCloses() = assertCameraHandover()

    private fun assertCameraHandover() {
        val first = FakeSession()
        val second = FakeSession()
        val arbiter = CameraArbiter { it() }
        arbiter.resume(first)
        composeRule.setContent {
            Column {
                Box(Modifier.size(360.dp, 240.dp)) { ARCameraInUseOverlay(first.isWaitingForCamera) }
                Box(Modifier.size(360.dp, 240.dp)) { ARCameraInUseOverlay(second.isWaitingForCamera) }
            }
        }
        composeRule.onNodeWithTag("sceneview-camera-in-use").assertDoesNotExist()

        composeRule.runOnIdle { arbiter.resume(second) }
        val context = RuntimeEnvironment.getApplication()
        composeRule.onNodeWithText(context.getString(R.string.sceneview_camera_in_use_title)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.sceneview_camera_in_use_body)).assertIsDisplayed()

        // Keep the first view's explanation until the second has actually released the camera.
        var releaseCamera: (() -> Unit)? = null
        composeRule.runOnIdle {
            arbiter.destroy(second) { onClosed ->
                second.stop()
                releaseCamera = onClosed
            }
        }
        composeRule.onNodeWithTag("sceneview-camera-in-use").assertIsDisplayed()
        composeRule.runOnIdle { releaseCamera!!.invoke() }
        composeRule.onNodeWithTag("sceneview-camera-in-use").assertDoesNotExist()
        assertTrue(first.isRunning)
    }

    private class FakeSession : CameraClient {
        override var isRunning = false
        override val canRun = true
        override var isWaitingForCamera by mutableStateOf(false)

        override fun start(): Exception? {
            isRunning = true
            return null
        }

        override fun stop() { isRunning = false }
        override fun onStartFailed(exception: Exception) { throw exception }
        override fun onException(exception: Exception) { throw exception }
    }
}
