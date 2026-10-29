package io.github.sceneview.demo.demos

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import io.github.sceneview.demo.demos.internal.MeasureFigureNumbers
import io.github.sceneview.demo.demos.internal.MeasureMode
import io.github.sceneview.demo.demos.internal.MeasureOpening
import io.github.sceneview.demo.demos.internal.MeasurePlanOpening
import io.github.sceneview.demo.demos.internal.MeasureUnitSystem
import io.github.sceneview.demo.demos.internal.measureRoomSurvey
import io.github.sceneview.demo.theme.SceneViewDemoTheme
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.theme.StageChrome
import io.github.sceneview.math.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

/**
 * The openings card under Measure's plan stands on the plan's page, the themed stage, not on the
 * camera: `DESIGN.md` Themed stage's card for the current theme. In light theme a light glass card
 * with `on-surface` text on the light ground, where it was the black card drawn over the camera.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MeasurePlanCardSurfaceTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** A 4 × 3 m room, 2.50 m high, with a 0.9 × 2.1 m door on wall B: 33.11 m² of wall left. */
    private val page: MeasurePlanPage = run {
        val outline = listOf(0f to 0f, 4f to 0f, 4f to 3f, 0f to 3f).map { (x, z) -> Position(x, -1.2f, z) }
        MeasurePlanPage(
            survey = measureRoomSurvey(outline, height = 2.5f)!!,
            openings = listOf(MeasurePlanOpening("B", MeasureOpening(from = 0.5f, to = 1.4f, bottom = 0f, top = 2.1f, wall = 1))),
            numbers = MeasureFigureNumbers(
                mode = MeasureMode.Room,
                segments = listOf(4f, 3f, 4f, 3f),
                length = 14f,
                closed = true,
                outlineArea = 12f,
                height = 2.5f,
                wallArea = 35f,
                volume = 30f,
                openingsArea = 1.89f,
                netWallArea = 33.11f,
            ),
        )
    }

    private fun open(darkTheme: Boolean) {
        composeRule.setContent {
            SceneViewDemoTheme(darkTheme = darkTheme) {
                MeasurePlanOverlay(
                    page = page,
                    title = "Measured room",
                    date = "21 Oct 2026",
                    locale = Locale.UK,
                    units = MeasureUnitSystem.Metric,
                    onClose = {},
                )
            }
        }
        composeRule.waitForIdle()
    }

    /** The card's ground, read in its top padding, above its text and inside its edge. */
    private fun ground(): Color {
        val card = composeRule.onNodeWithTag(MEASURE_PLAN_CARD_TAG).captureToImage().asAndroidBitmap()
        return Color(card.getPixel(card.width / 2, TOP_PADDING_PX))
    }

    /** The colour the card writes its net wall area in. */
    private fun valueInk(): Color {
        val node = composeRule.onNode(hasText("33.1 m²") and hasAnyAncestor(hasTestTag(MEASURE_PLAN_CARD_TAG)), useUnmergedTree = true)
            .fetchSemanticsNode()
        val layouts = ArrayList<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        return layouts.first().layoutInput.style.color
    }

    @Test
    fun `in light theme the card is the themed stage's light card, written in on-surface`() {
        open(darkTheme = false)
        val ground = ground()
        assertTrue("card ground $ground", ground.luminance() > 0.5f)
        assertEquals(StageChrome.Light.onCard, valueInk())
    }

    @Test
    fun `in dark theme the card keeps the dark glass, written in white`() {
        open(darkTheme = true)
        val ground = ground()
        assertTrue("card ground $ground", ground.luminance() < 0.2f)
        assertEquals(SceneViewTokens.ArOverlay.onScrim, valueInk())
    }

    private companion object {
        /** Inside the card's `space-sm` top padding (16 px at xhdpi), below its hairline edge. */
        const val TOP_PADDING_PX = 10
    }
}
