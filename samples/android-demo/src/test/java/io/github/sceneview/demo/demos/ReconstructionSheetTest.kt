package io.github.sceneview.demo.demos

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.internal.ReconstructionMethod
import io.github.sceneview.demo.theme.SceneViewDemoTheme
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
 * The end of a scan's choice of reconstruction (#4630): the two methods the phone runs and
 * nothing else, "As scanned" picked first, "Filled" only when the scan has its surfels and fits
 * the fill's memory — and, when it fits only on a coarser grid, saying so before it is picked.
 * Each run leaves the sheet's content, light and dark, en-US and fr-FR, and in landscape with
 * large text, in `build/reconstruction-sheet-renders/` (not committed).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReconstructionSheetTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var finished: ReconstructionMethod? = null

    private fun show(fill: FillPlan) {
        composeRule.setContent {
            SceneViewDemoTheme(darkTheme = false) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                    ReconstructionChoices(fill, ReconstructionMethod.Fused, onFinish = { finished = it })
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun text(id: Int): String = ApplicationProvider.getApplicationContext<android.content.Context>().getString(id)

    @Test
    fun `only the two methods the phone runs are offered, as scanned first`() {
        show(FillPlan.Fills(1))
        val choices = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)
        composeRule.onAllNodes(choices).assertCountEquals(ReconstructionMethod.entries.size)
        composeRule.onNodeWithTag(choiceTag(ReconstructionMethod.Fused)).assertIsSelected()
        composeRule.onNodeWithTag(choiceTag(ReconstructionMethod.Poisson)).assertIsNotSelected()
    }

    @Test
    fun `filled can be picked and the model finishes with it`() {
        show(FillPlan.Fills(1))
        composeRule.onNodeWithTag(choiceTag(ReconstructionMethod.Poisson)).performClick().assertIsSelected()
        composeRule.onNodeWithTag(choiceTag(ReconstructionMethod.Fused)).assertIsNotSelected()
        composeRule.onNodeWithTag(FINISH_TAG).performClick()
        assertEquals(ReconstructionMethod.Poisson, finished)
    }

    @Test
    fun `in full detail filled says nothing of a coarser grid`() {
        show(FillPlan.Fills(1))
        composeRule.onNodeWithText(text(R.string.scan_finish_method_filled_detail)).assertExists()
        composeRule.onNodeWithText(text(R.string.scan_finish_method_filled_half)).assertDoesNotExist()
        composeRule.onNodeWithText(text(R.string.scan_finish_method_filled_quarter)).assertDoesNotExist()
    }

    @Test
    fun `a scan that fills only on a coarser grid says so, and can still be filled`() {
        show(FillPlan.Fills(2))
        composeRule.onNodeWithText(text(R.string.scan_finish_method_filled_detail)).assertExists()
        composeRule.onNodeWithText(text(R.string.scan_finish_method_filled_half)).assertExists()
        composeRule.onNodeWithTag(choiceTag(ReconstructionMethod.Poisson)).assertIsEnabled().performClick()
        composeRule.onNodeWithTag(FINISH_TAG).performClick()
        assertEquals(ReconstructionMethod.Poisson, finished)
    }

    @Test
    fun `at a quarter of the detail filled says so`() {
        show(FillPlan.Fills(4))
        composeRule.onNodeWithText(text(R.string.scan_finish_method_filled_quarter)).assertExists()
        composeRule.onNodeWithText(text(R.string.scan_finish_method_filled_half)).assertDoesNotExist()
    }

    @Test
    fun `a scan too big to fill shows filled, says why, and cannot be picked`() {
        show(FillPlan.TooBig)
        composeRule.onNodeWithText(text(R.string.scan_finish_method_filled_too_big)).assertExists()
        composeRule.onNodeWithText(text(R.string.scan_finish_method_filled_detail)).assertDoesNotExist()
        composeRule.onNodeWithTag(choiceTag(ReconstructionMethod.Poisson)).assertIsNotEnabled().performClick()
        composeRule.onNodeWithTag(choiceTag(ReconstructionMethod.Fused)).assertIsSelected()
        composeRule.onNodeWithTag(FINISH_TAG).performClick()
        assertEquals(ReconstructionMethod.Fused, finished)
    }

    @Test
    fun `without surfels filled is shown, says why, and cannot be picked`() {
        show(FillPlan.NoSurfels)
        composeRule.onNodeWithText(text(R.string.scan_finish_method_filled_unavailable)).assertExists()
        composeRule.onNodeWithTag(choiceTag(ReconstructionMethod.Poisson)).assertIsNotEnabled().performClick()
        composeRule.onNodeWithTag(choiceTag(ReconstructionMethod.Fused)).assertIsSelected()
        composeRule.onNodeWithTag(FINISH_TAG).performClick()
        assertEquals(ReconstructionMethod.Fused, finished)
    }

    @Test
    @Config(qualifiers = "w891dp-h411dp-land-xhdpi")
    fun `in landscape with large text the sheet scrolls to its finish button`() {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = LARGE_FONT_SCALE)) {
                SceneViewDemoTheme(darkTheme = false) {
                    Surface(Modifier.fillMaxSize().testTag(SHEET_TAG), color = MaterialTheme.colorScheme.surfaceContainer) {
                        ReconstructionChoices(FillPlan.Fills(2), ReconstructionMethod.Fused, onFinish = { finished = it })
                    }
                }
            }
        }
        composeRule.onNodeWithTag(choiceTag(ReconstructionMethod.Poisson)).performClick()
        save(composeRule.onNodeWithTag(SHEET_TAG).captureToImage().asAndroidBitmap(), "landscape-large-text-top")
        // The note on the lower detail pushes the button below the fold: it must still be reached.
        composeRule.onNodeWithTag(FINISH_TAG).performScrollTo().assertIsDisplayed().performClick()
        save(composeRule.onNodeWithTag(SHEET_TAG).captureToImage().asAndroidBitmap(), "landscape-large-text-end")
        assertEquals(ReconstructionMethod.Poisson, finished)
    }

    @Test
    fun `the sheet renders in light and dark`() {
        render(LOOKS, prefix = "en-US")
    }

    @Test
    @Config(qualifiers = "fr-rFR-w411dp-h891dp-xhdpi")
    fun `the sheet renders in light and dark in French`() {
        // The demo is not translated: French falls back to the English strings, none missing.
        render(LOOKS, prefix = "fr-FR")
        assertTrue(text(R.string.scan_finish_method_title).isNotEmpty())
    }

    private fun render(looks: List<Look>, prefix: String) {
        var look by mutableStateOf(looks.first())
        composeRule.setContent {
            key(look) {
                SceneViewDemoTheme(darkTheme = look.dark) {
                    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                        ReconstructionChoices(look.fill, look.initial, onFinish = {}, Modifier.testTag(SHEET_TAG))
                    }
                }
            }
        }
        for (each in looks) {
            look = each
            composeRule.waitForIdle()
            composeRule.onAllNodesWithTag(SHEET_TAG).assertCountEquals(1)
            val image = composeRule.onNodeWithTag(SHEET_TAG).captureToImage().asAndroidBitmap()
            save(image, "$prefix-${each.name}")
            val ground = Color(image.getPixel(image.width - 1, image.height - 1)).luminance()
            assertTrue("${each.name}: ground $ground", if (each.dark) ground < DARK_GROUND else ground > LIGHT_GROUND)
        }
    }

    private fun save(image: Bitmap, name: String) {
        val out = File("build/reconstruction-sheet-renders").apply { mkdirs() }.resolve("$name.png")
        out.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private data class Look(val name: String, val dark: Boolean, val fill: FillPlan, val initial: ReconstructionMethod)

    private companion object {
        const val SHEET_TAG = "reconstruction-sheet"
        const val DARK_GROUND = 0.2f
        const val LIGHT_GROUND = 0.5f
        const val LARGE_FONT_SCALE = 2f
        val LOOKS = listOf(false, true).flatMap { dark ->
            val theme = if (dark) "dark" else "light"
            listOf(
                Look(theme, dark, FillPlan.Fills(1), ReconstructionMethod.Fused),
                Look("$theme-filled", dark, FillPlan.Fills(1), ReconstructionMethod.Poisson),
                Look("$theme-half", dark, FillPlan.Fills(2), ReconstructionMethod.Poisson),
                Look("$theme-too-big", dark, FillPlan.TooBig, ReconstructionMethod.Fused),
                Look("$theme-unavailable", dark, FillPlan.NoSurfels, ReconstructionMethod.Fused),
            )
        }
    }
}
