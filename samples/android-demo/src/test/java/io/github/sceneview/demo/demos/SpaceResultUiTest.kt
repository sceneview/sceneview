package io.github.sceneview.demo.demos

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.internal.ScanScope
import io.github.sceneview.demo.demos.internal.ScanScopeCopy
import io.github.sceneview.demo.demos.internal.SpaceBox
import io.github.sceneview.demo.demos.internal.SpaceFace
import io.github.sceneview.demo.demos.internal.SpaceFit
import io.github.sceneview.demo.theme.LocalStageChrome
import io.github.sceneview.demo.theme.SceneViewDemoTheme
import io.github.sceneview.demo.theme.SceneViewTokens.Space
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
 * A space's two screens: its card in the choice of what to scan, and the card of its result.
 * Each run leaves them as drawn, light and dark, in `build/scan-space-renders/` (not committed).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SpaceResultUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var dark by mutableStateOf(true)
    private var measure by mutableStateOf<SpaceMeasure>(SpaceMeasure.Found(CUPBOARD))
    private var choosing by mutableStateOf(false)

    private fun show() {
        composeRule.setContent {
            SceneViewDemoTheme(darkTheme = dark) {
                if (choosing) {
                    Box(Modifier.testTag(STAGE_TAG)) {
                        ScanScopePage(selected = ScanScope.Space, onSelect = {}, onStart = {})
                    }
                } else {
                    // The result's own stage: dark in both themes, its card the media one.
                    CompositionLocalProvider(LocalStageChrome provides StageChrome.Media) {
                        Box(Modifier.width(CARD_WIDTH).background(StageChrome.Media.ground).padding(vertical = Space.md).testTag(STAGE_TAG)) {
                            SpaceMeasureCard(measure)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `the result says the inside, the box that fits and what is in the way`() {
        show()
        composeRule.onNodeWithText("Inside 60 × 50 × 56 cm").assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.scan_space_order)).assertIsDisplayed()
        composeRule.onNodeWithText("A box of 58 × 28 × 55 cm fits").assertIsDisplayed()
        composeRule.onNodeWithText("1 thing in the way").assertIsDisplayed()
        save("result-card")
    }

    @Test
    fun `a scan that shows no space says what to film again, and one being read says nothing`() {
        measure = SpaceMeasure.Measuring
        show()
        composeRule.onNodeWithTag(SPACE_CARD_TAG).assertDoesNotExist()
        measure = SpaceMeasure.None
        composeRule.onNodeWithText(text(R.string.scan_space_none)).assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.scan_space_none_hint)).assertIsDisplayed()
        save("result-card-none")
    }

    @Test
    fun `a space with nothing in it, too tight for a box, says so`() {
        measure = SpaceMeasure.Found(SpaceFit(0.3f, 0.04f, 0.2f, setOf(SpaceFace.Front), emptyList(), null, 0f, 0f, 0f, 0f))
        show()
        composeRule.onNodeWithText("Inside 30 × 20 × 4 cm").assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.scan_space_fits_none)).assertIsDisplayed()
    }

    @Test
    fun `the choice offers a space, with what it is for`() {
        choosing = true
        show()
        composeRule.onNodeWithText(ScanScopeCopy.title(ScanScope.Space)).assertIsDisplayed()
        composeRule.onNodeWithText(ScanScopeCopy.detail(ScanScope.Space)).assertIsDisplayed()
        composeRule.onNodeWithTag("$SCAN_SCOPE_TAG-${ScanScope.Space.key}").assertIsSelected()
        composeRule.onNodeWithText(ScanScopeCopy.start(ScanScope.Space)).assertIsDisplayed()
        save("choice-dark")
        dark = false
        composeRule.waitForIdle()
        save("choice-light")
    }

    @Test
    fun `centimetres are whole, and a millimetre short of one still counts as it`() {
        assertEquals(60, cm(0.5996f))
        assertEquals(59, cm(0.598f))
        assertEquals(4, cm(0.04f))
    }

    private fun text(id: Int) = ApplicationProvider.getApplicationContext<Context>().getString(id)

    private fun save(name: String) {
        composeRule.waitForIdle()
        val image = composeRule.onNodeWithTag(STAGE_TAG).captureToImage().asAndroidBitmap()
        val out = File("build/scan-space-renders").apply { mkdirs() }.resolve("$name.png")
        out.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, FULL_QUALITY, it) }
    }

    private companion object {
        const val STAGE_TAG = "space-stage"
        val CARD_WIDTH = 411.dp
        const val FULL_QUALITY = 100

        /** A cupboard under a sink as measured: 60 × 56 × 50 cm, its drain pipe, and what fits before it. */
        val CUPBOARD = SpaceFit(
            width = 0.6f,
            height = 0.56f,
            depth = 0.5f,
            open = setOf(SpaceFace.Front),
            obstacles = listOf(SpaceBox(0.26f, 0.24f, 0.1f, 0.08f, 0.32f, 0.08f)),
            fits = SpaceBox(0.01f, 0f, 0.22f, 0.58f, 0.55f, 0.28f),
            originX = 0f,
            originY = 0f,
            originZ = 0f,
            yawDegrees = 0f,
        )
    }
}
