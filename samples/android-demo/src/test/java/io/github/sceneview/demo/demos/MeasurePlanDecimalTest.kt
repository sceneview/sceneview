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
 * Measure's plan page writes its figures with the phone's decimal mark, one rule for the plan,
 * its table, the card under it, the PDF and the shared text: `4,00` in French and German, `4.00`
 * in British and US English. The Measure screen already wrote `2,10 m` beside a plan of `4.20`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MeasurePlanDecimalTest {

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

    /** A decimal point between two digits, `4.00`, or a decimal comma, `4,00`. */
    private val point = Regex("""\d\.\d""")
    private val comma = Regex("""\d,\d""")

    /**
     * Opens the page under [locale], in metres, and checks every figure it writes — on screen, in
     * the PDF's plan and in the shared text — takes [mark] and never the other one.
     */
    private fun assertDecimalMark(locale: Locale, mark: Char) {
        var words: PlanWords? = null
        composeRule.setContent {
            SceneViewDemoTheme(darkTheme = false) {
                // The words the page's PDF and text shares read, under the same locale.
                words = rememberPlanWords(MeasureUnitSystem.Metric, locale)
                MeasurePlanOverlay(
                    page = page,
                    title = "Measured room",
                    date = "21 Oct 2026",
                    locale = locale,
                    units = MeasureUnitSystem.Metric,
                    onClose = {},
                )
            }
        }
        composeRule.waitForIdle()
        val texts = composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .flatMap { node -> node.config[SemanticsProperties.Text].map { it.text } }
        val other = if (mark == ',') point else comma
        val dimensions = "4${mark}00 × 3${mark}00 m"

        // The screen: the plan's table and the card under it.
        assertTrue(texts.toString(), dimensions in texts)
        assertTrue(texts.toString(), "12${mark}0 m²" in texts)
        assertTrue(texts.toString(), "33${mark}1 m²" in texts)
        assertTrue(texts.toString(), texts.none { other.containsMatchIn(it) })

        // The shared text and the PDF read the same words.
        val text = SurveyReport.text(page.survey, words!!.survey, "Measured room", null)
        assertTrue(text, dimensions in text && "14${mark}00 m" in text && "30${mark}0 m³" in text)
        assertTrue(text, !other.containsMatchIn(text))
        val plan = SurveyReport.planTexts(page.survey, words!!.survey)
        assertTrue(plan.walls.toString(), "4${mark}00" in plan.walls && plan.walls.none { other.containsMatchIn(it) })
        assertEquals("12${mark}0 m²", plan.area)
        assertEquals("0${mark}5 m", plan.scale(0.5f))
    }

    @Test
    @Config(qualifiers = "fr-rFR-w411dp-h891dp-xhdpi")
    fun `French writes the plan, its card and its shares with a decimal comma`() =
        assertDecimalMark(Locale.FRANCE, ',')

    @Test
    @Config(qualifiers = "de-rDE-w411dp-h891dp-xhdpi")
    fun `German writes the plan, its card and its shares with a decimal comma`() =
        assertDecimalMark(Locale.GERMANY, ',')

    @Test
    @Config(qualifiers = "en-rGB-w411dp-h891dp-xhdpi")
    fun `British English writes the plan, its card and its shares with a decimal point`() =
        assertDecimalMark(Locale.UK, '.')

    @Test
    @Config(qualifiers = "en-rUS-w411dp-h891dp-xhdpi")
    fun `US English with metres picked writes them with a decimal point`() =
        assertDecimalMark(Locale.US, '.')
}
