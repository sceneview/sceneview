package io.github.sceneview.demo.demos

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.sceneview.demo.demos.internal.MeasureFigureNumbers
import io.github.sceneview.demo.demos.internal.MeasureMode
import io.github.sceneview.demo.demos.internal.MeasureOpening
import io.github.sceneview.demo.demos.internal.MeasurePlanOpening
import io.github.sceneview.demo.demos.internal.MeasureUnitSystem
import io.github.sceneview.demo.demos.internal.SurveyReport
import io.github.sceneview.demo.demos.internal.measureRoomSurvey
import io.github.sceneview.demo.theme.SceneViewDemoTheme
import io.github.sceneview.math.Position
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

/**
 * Measure's plan page writes its plan, its card, its PDF and its text in the Measure screen's
 * units: one page, one unit system, even when the settings picked the other one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MeasurePlanOverlayUnitsTest {

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

    /** A figure written in metres: `90 cm`, `4.00 m`, `12.0 m²`, `30.0 m³`. */
    private val metric = Regex("""\d c?m\b|m²|m³""")

    /** A figure written in feet and inches: `2 ft 11 in`, `11 in`, `129 ft²`, `1059 ft³`. */
    private val imperial = Regex("""\d (ft|in)\b|ft²|ft³""")

    /** Opens the page under [locale] with the Measure screen in [units]; returns the texts it shows. */
    private fun open(locale: Locale, units: MeasureUnitSystem): List<String> {
        composeRule.setContent {
            SceneViewDemoTheme(darkTheme = false) {
                MeasurePlanOverlay(page = page, title = "Measured room", date = "21 Oct 2026", locale = locale, units = units, onClose = {})
            }
        }
        composeRule.waitForIdle()
        return composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .flatMap { node -> node.config[SemanticsProperties.Text].map { it.text } }
    }

    @Test
    @Config(qualifiers = "fr-rFR-w411dp-h891dp-xhdpi")
    fun `French with imperial picked reads the plan and its card in feet and inches`() {
        val texts = open(Locale.FRANCE, MeasureUnitSystem.Imperial)
        assertTrue(texts.toString(), "356 ft²" in texts)
        assertTrue(texts.toString(), "129 ft²" in texts)
        assertTrue(texts.toString(), texts.none { metric.containsMatchIn(it) })
    }

    @Test
    @Config(qualifiers = "en-rUS-w411dp-h891dp-xhdpi")
    fun `US English with metric picked reads the plan and its card in metres`() {
        val texts = open(Locale.US, MeasureUnitSystem.Metric)
        assertTrue(texts.toString(), "33.1 m²" in texts)
        assertTrue(texts.toString(), "12.0 m²" in texts)
        assertTrue(texts.toString(), texts.none { imperial.containsMatchIn(it) })
    }

    @Test
    @Config(qualifiers = "fr-rFR")
    fun `French with imperial picked shares the PDF and the text in feet and inches`() {
        var words: PlanWords? = null
        composeRule.setContent { words = rememberPlanWords(MeasureUnitSystem.Imperial) }
        composeRule.waitForIdle()
        val text = SurveyReport.text(page.survey, words!!.survey, "Measured room", null)
        assertTrue(text, "129 ft²" in text && !metric.containsMatchIn(text))
        assertTrue(words!!.pdfScale, words!!.pdfScale.endsWith("dimensions in feet and inches"))
    }
}
