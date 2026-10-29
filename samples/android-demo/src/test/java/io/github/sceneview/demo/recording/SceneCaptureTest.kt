package io.github.sceneview.demo.recording

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import io.github.sceneview.capture.SceneRecorder
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.DemoScaffoldTestTags
import io.github.sceneview.demo.theme.SceneViewDemoTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The demo side of the SDK's `SceneRecorder`: the elapsed label, the watermark, the sheet rows. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SceneCaptureTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun formatElapsed_readsMinutesAndSeconds() {
        assertEquals("0:00", formatElapsed(0))
        assertEquals("0:00", formatElapsed(999))
        assertEquals("0:01", formatElapsed(1_000))
        assertEquals("0:59", formatElapsed(59_999))
        assertEquals("1:00", formatElapsed(60_000))
        assertEquals("12:05", formatElapsed(725_000))
    }

    @Test
    fun watermark_drawsInTheBottomLeftCornerOnly() {
        val bitmap = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888)
        watermark("Made with SceneView").draw(Canvas(bitmap), bitmap.width, bitmap.height)

        val (w, h) = bitmap.width to bitmap.height
        assertTrue("the label is in the bottom-left corner", bitmap.hasInk(0, h * 9 / 10, w * 2 / 3, h))
        assertFalse("nothing above the bottom band", bitmap.hasInk(0, 0, w, h * 9 / 10))
        assertFalse("nothing in the right third", bitmap.hasInk(w * 2 / 3, 0, w, h))
    }

    @Test
    fun settingsSheet_withARecorder_offersPhotoAndVideo() {
        val recorder = SceneRecorder(composeRule.activity)
        composeRule.setContent {
            SceneViewDemoTheme(darkTheme = false) {
                DemoScaffold(title = "Capture", onBack = {}, recorder = recorder) {
                    Box(Modifier.fillMaxSize().testTag("probe-scene"))
                }
            }
        }
        composeRule.onNodeWithTag(DemoScaffoldTestTags.SETTINGS_FAB).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(DemoScaffoldTestTags.PHOTO_ACTION).assertIsDisplayed()
        composeRule.onNodeWithTag(DemoScaffoldTestTags.RECORD_ACTION).assertIsDisplayed()
    }

    private fun Bitmap.hasInk(left: Int, top: Int, right: Int, bottom: Int): Boolean {
        for (y in top until bottom) {
            for (x in left until right) {
                if (Color.alpha(getPixel(x, y)) != 0) return true
            }
        }
        return false
    }
}
