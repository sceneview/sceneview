package io.github.sceneview.demo.demos

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import io.github.sceneview.demo.demos.internal.ScanInstruction
import io.github.sceneview.demo.theme.SceneViewDemoTheme
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.theme.SceneViewTokens.Space
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The red button's ring as it is drawn: the part of the turn made is lit, the rest is a dim track,
 * and a full turn lights all of it. Each run leaves the recorder at five steps of the turn, light
 * and dark, in `build/scan-recorder-renders/` (not committed).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScanRecorderRingTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** What the recorder shows now: one content, set once, redrawn as this changes. */
    private var look by mutableStateOf(Look(progress = 0f, dark = true, instruction = ScanInstruction.KeepGoing))
    private var shown = false

    private fun recorder(
        progress: Float?,
        dark: Boolean,
        instruction: ScanInstruction,
        ground: Color? = null,
    ): Bitmap {
        look = Look(progress, dark, instruction, ground)
        if (!shown) {
            shown = true
            composeRule.setContent {
                SceneViewDemoTheme(darkTheme = look.dark) {
                    // The stage, or what the camera may show behind the recorder.
                    Box(Modifier.background(look.ground ?: SceneViewTokens.Stage.background).padding(Space.md).testTag(STAGE_TAG)) {
                        ScanControls(
                            instruction = look.instruction, doneEnabled = true, onFinish = {}, onTogglePause = {},
                            progress = look.progress,
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
        return composeRule.onNodeWithTag(STAGE_TAG).captureToImage().asAndroidBitmap()
    }

    /** How many of the shutter's pixels are lit as the ring's part done is: near white. */
    private fun lit(image: Bitmap): Int {
        val (shutter, stage) = bounds()
        var count = 0
        for (y in (shutter.top - stage.top).toInt() until (shutter.bottom - stage.top).toInt()) {
            for (x in (shutter.left - stage.left).toInt() until (shutter.right - stage.left).toInt()) {
                if (isLit(image.getPixel(x, y))) count++
            }
        }
        return count
    }

    @Test
    fun `the ring lights with the turn, from a dim track to the whole of it`() {
        val counts = STEPS.map { step ->
            val image = recorder(step, dark = true, instruction = ScanInstruction.KeepGoing)
            save(image, "dark-${(step * PERCENT).toInt()}")
            lit(image)
        }
        // Nothing lit before the phone turns, then more at every step.
        assertEquals("lit at nothing turned", 0, counts.first())
        for (i in 1 until counts.size) assertTrue("lit $counts", counts[i] > counts[i - 1])
        // The gauge is twice the plain ring a recorder without one shows: it is what guides.
        val plain = lit(recorder(null, dark = true, instruction = ScanInstruction.KeepGoing))
        assertTrue("full gauge ${counts.last()} px, plain ring $plain px", counts.last() > plain * THICKER)
    }

    @Test
    fun `the gauge stands on a shade, so it reads over a white wall`() {
        val image = recorder(0.5f, dark = true, instruction = ScanInstruction.KeepGoing, ground = Color.White)
        save(image, "white-wall-50")
        // The half done is lit, the half left is not: on white, an unshaded track would be.
        val done = column(image, side = RIGHT)
        val left = column(image, side = LEFT)
        assertTrue("done $done px lit", done >= GAUGE_PX - 2)
        assertEquals("left to do, lit", 0, left)
    }

    @Test
    fun `a step further swells the ring for a beat`() {
        recorder(0.5f, dark = true, instruction = ScanInstruction.KeepGoing)
        composeRule.mainClock.autoAdvance = false
        look = look.copy(progress = 0.75f)
        // The widest the ring gets over the beat's first frames.
        val beating = (1..BEAT_FRAMES).maxOf {
            composeRule.mainClock.advanceTimeByFrame()
            top(composeRule.onNodeWithTag(STAGE_TAG).captureToImage().asAndroidBitmap())
        }
        composeRule.mainClock.advanceTimeBy(SETTLE_MS)
        val settled = top(composeRule.onNodeWithTag(STAGE_TAG).captureToImage().asAndroidBitmap())
        assertTrue("beating $beating px, settled $settled px", beating > settled)
        assertEquals("settled", GAUGE_PX.toFloat(), settled.toFloat(), 2f)
    }

    /** The ring's lit thickness at its top, where the part done starts, in pixels. */
    private fun top(image: Bitmap): Int {
        val (shutter, stage) = bounds()
        val x = ((shutter.left + shutter.right) / 2f - stage.left).toInt()
        val from = (shutter.top - stage.top).toInt()
        val to = ((shutter.top + shutter.bottom) / 2f - stage.top).toInt()
        return (from until to).count { y -> isLit(image.getPixel(x, y)) }
    }

    /** The ring's lit thickness on its [side], at mid height, in pixels. */
    private fun column(image: Bitmap, side: Int): Int {
        val (shutter, stage) = bounds()
        val y = ((shutter.top + shutter.bottom) / 2f - stage.top).toInt()
        val middle = ((shutter.left + shutter.right) / 2f - stage.left).toInt()
        val xs = if (side == LEFT) (shutter.left - stage.left).toInt() until middle - RING_REACH_PX
        else middle + RING_REACH_PX until (shutter.right - stage.left).toInt()
        return xs.count { x -> isLit(image.getPixel(x, y)) }
    }

    private fun bounds() = composeRule.onNodeWithTag(AR_REC_SHUTTER_TAG).fetchSemanticsNode().boundsInRoot to
        composeRule.onNodeWithTag(STAGE_TAG).fetchSemanticsNode().boundsInRoot

    private fun isLit(pixel: Int) = minOf(pixel shr RED and BYTE, pixel shr GREEN and BYTE, pixel and BYTE) >= LIT

    @Test
    fun `the recorder is left as drawn in the light theme too`() {
        save(recorder(STEPS[2], dark = false, instruction = ScanInstruction.KeepGoing), "light-50")
        save(recorder(1f, dark = false, instruction = ScanInstruction.Complete), "light-100")
    }

    private fun save(image: Bitmap, name: String) {
        val out = File("build/scan-recorder-renders").apply { mkdirs() }.resolve("$name.png")
        out.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, FULL_QUALITY, it) }
    }

    private data class Look(val progress: Float?, val dark: Boolean, val instruction: ScanInstruction, val ground: Color? = null)

    private companion object {
        const val STAGE_TAG = "scan-recorder-stage"
        val STEPS = listOf(0f, 0.25f, 0.5f, 0.75f, 1f)
        const val RED = 16
        const val GREEN = 8
        const val BYTE = 0xFF
        const val LIT = 230
        const val PERCENT = 100
        const val FULL_QUALITY = 100
        const val THICKER = 1.5f

        /** The gauge's 6 dp at this test's 2 px per dp. */
        const val GAUGE_PX = 12

        /** Half the button less its ring, in pixels: the columns the red square never reaches. */
        const val RING_REACH_PX = 40
        const val LEFT = -1
        const val RIGHT = 1
        const val SETTLE_MS = 2_000L
        const val BEAT_FRAMES = 8
    }
}
