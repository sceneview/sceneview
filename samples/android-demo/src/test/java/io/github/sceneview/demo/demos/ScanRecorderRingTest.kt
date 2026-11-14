package io.github.sceneview.demo.demos

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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

    private fun recorder(progress: Float?, dark: Boolean, instruction: ScanInstruction): Bitmap {
        look = Look(progress, dark, instruction)
        if (!shown) {
            shown = true
            composeRule.setContent {
                SceneViewDemoTheme(darkTheme = look.dark) {
                    Box(Modifier.background(SceneViewTokens.Stage.background).padding(Space.md).testTag(STAGE_TAG)) {
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
        val shutter = composeRule.onNodeWithTag(AR_REC_SHUTTER_TAG).fetchSemanticsNode().boundsInRoot
        val stage = composeRule.onNodeWithTag(STAGE_TAG).fetchSemanticsNode().boundsInRoot
        var count = 0
        for (y in (shutter.top - stage.top).toInt() until (shutter.bottom - stage.top).toInt()) {
            for (x in (shutter.left - stage.left).toInt() until (shutter.right - stage.left).toInt()) {
                val pixel = image.getPixel(x, y)
                val low = minOf(pixel shr RED and BYTE, pixel shr GREEN and BYTE, pixel and BYTE)
                if (low >= LIT) count++
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
        // A full turn is the ring a recorder without a gauge shows.
        val plain = lit(recorder(null, dark = true, instruction = ScanInstruction.KeepGoing))
        assertEquals(plain.toFloat(), counts.last().toFloat(), plain * TOLERANCE)
    }

    @Test
    fun `the recorder is left as drawn in the light theme too`() {
        save(recorder(STEPS[2], dark = false, instruction = ScanInstruction.KeepGoing), "light-50")
        save(recorder(1f, dark = false, instruction = ScanInstruction.Complete), "light-100")
    }

    private fun save(image: Bitmap, name: String) {
        val out = File("build/scan-recorder-renders").apply { mkdirs() }.resolve("$name.png")
        out.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, FULL_QUALITY, it) }
    }

    private data class Look(val progress: Float?, val dark: Boolean, val instruction: ScanInstruction)

    private companion object {
        const val STAGE_TAG = "scan-recorder-stage"
        val STEPS = listOf(0f, 0.25f, 0.5f, 0.75f, 1f)
        const val RED = 16
        const val GREEN = 8
        const val BYTE = 0xFF
        const val LIT = 230
        const val PERCENT = 100
        const val FULL_QUALITY = 100
        const val TOLERANCE = 0.1f
    }
}
