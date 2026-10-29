package io.github.sceneview.demo.demos

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.internal.planSurvey
import io.github.sceneview.demo.theme.LocalStageChrome
import io.github.sceneview.demo.theme.StageChrome
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The plan screen of a room traced with a tape, in light and in dark: its figures carry no "±"
 * and it says nothing about a scan. Each run leaves its render in `build/plan-renders/` (not
 * committed), the plan's oblique wall dimensioned along itself.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RerunPlanRenderTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** 4 × 3 m with its top-right corner cut at 45°, 2.50 m high: no figure with a scatter. */
    private val traced = planSurvey(0f, 0f, 3f, 0f, 4f, 1f, 4f, 3f, 0f, 3f)

    /** Shows the traced room's plan under [chrome], saves it as [name] and returns it with the spread note's words. */
    private fun render(chrome: StageChrome, name: String): Pair<Bitmap, String> {
        var note = ""
        composeRule.setContent {
            note = stringResource(R.string.demo_ar_rerun_plan_spread_note)
            CompositionLocalProvider(LocalStageChrome provides chrome) {
                RerunPlanView(survey = traced, title = "Traced room", date = null)
            }
        }
        composeRule.waitForIdle()
        val image = composeRule.onNodeWithTag(RERUN_PLAN_TAG).captureToImage().asAndroidBitmap()
        val out = File("build/plan-renders").apply { mkdirs() }.resolve("$name.png")
        out.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return image to note
    }

    private fun assertSaysNothingOfAScan(note: String) {
        assertEquals(0, composeRule.onAllNodesWithText("±", substring = true).fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithText(note).fetchSemanticsNodes().size)
    }

    @Test
    fun `in light a traced room's plan gives no plus-minus and no word of a scan`() {
        val (image, note) = render(StageChrome.Light, "traced-light")
        assertEquals(StageChrome.Light.ground.toArgb(), image.getPixel(1, image.height - 2))
        assertSaysNothingOfAScan(note)
    }

    @Test
    fun `in dark a traced room's plan gives no plus-minus and no word of a scan`() {
        val (image, note) = render(StageChrome.Media, "traced-dark")
        assertEquals(StageChrome.Media.ground.toArgb(), image.getPixel(1, image.height - 2))
        assertSaysNothingOfAScan(note)
    }
}
