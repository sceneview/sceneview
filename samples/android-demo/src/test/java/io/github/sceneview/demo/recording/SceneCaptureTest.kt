package io.github.sceneview.demo.recording

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.core.content.IntentCompat
import io.github.sceneview.capture.SceneRecorder
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.DemoScaffoldTestTags
import io.github.sceneview.demo.theme.SceneViewDemoTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

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
    fun watermarkPill_padsTheLabelAndStaysInTheFrame() {
        val pill = watermarkPill(textWidth = 400f, textSize = 40f, margin = 40f, height = 1920f, out = RectF())

        assertTrue("it starts left of the text, inside the frame", pill.left >= 0f && pill.left < 40f)
        assertTrue("it ends right of the text", pill.right > 440f)
        assertTrue("it rises above the cap height", pill.top < 1880f - 30f)
        assertTrue("it covers the descenders, inside the frame", pill.bottom > 1880f && pill.bottom < 1920f)
    }

    @Test
    fun watermark_readsOnAWhiteFrame() {
        // The light theme's grid is near-white: white text alone disappears there (QA 2026-11-01).
        val bitmap = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        watermark("Made with SceneView").draw(Canvas(bitmap), bitmap.width, bitmap.height)

        val textSize = 1080 * 0.04f
        val pill = watermarkPill(0f, textSize, textSize, 1920f, RectF())
        // In the pill's left padding, at its middle: the ground the white label reads on.
        val ground = bitmap.getPixel((pill.left + textSize * 0.25f).toInt(), pill.centerY().toInt())
        val grey = Color.red(ground)
        assertTrue("the label's ground is dark enough for white text (was $grey)", grey <= 110)
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

    @Test
    fun sharePreview_readsTheUriFromTheClip() {
        // Without the URI in the clip, the sheet's thumbnail read threw a SecurityException
        // (Pixel 4a QA 2026-11-02): the grant on EXTRA_STREAM alone never reaches the sheet.
        val context = composeRule.activity
        val file = File(context.filesDir, "captures/probe.jpg").apply {
            parentFile!!.mkdirs()
            writeBytes(byteArrayOf(1))
        }
        val started = shadowOf(context.application)
        while (started.peekNextStartedActivity() != null) started.nextStartedActivity

        assertTrue(shareCapture(context, file, "image/jpeg"))

        val sheet = started.nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, sheet.action)
        val send = IntentCompat.getParcelableExtra(sheet, Intent.EXTRA_INTENT, Intent::class.java)!!
        val stream = IntentCompat.getParcelableExtra(send, Intent.EXTRA_STREAM, Uri::class.java)
        assertNotNull("the share carries no file", stream)
        val clip = send.clipData
        assertNotNull("the share sheet gets no grant for its preview", clip)
        assertEquals(stream, clip!!.getItemAt(0).uri)
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
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
