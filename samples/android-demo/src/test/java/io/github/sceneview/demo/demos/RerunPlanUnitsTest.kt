package io.github.sceneview.demo.demos

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import io.github.sceneview.demo.demos.internal.PlanMetrics
import io.github.sceneview.demo.demos.internal.PlanStroke
import io.github.sceneview.demo.demos.internal.PlanTextRole
import io.github.sceneview.demo.demos.internal.RoomPlanLayout
import io.github.sceneview.demo.demos.internal.SurveyReport
import io.github.sceneview.demo.demos.internal.planSurvey
import io.github.sceneview.demo.theme.LocalStageChrome
import io.github.sceneview.demo.theme.StageChrome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The plan's units follow the phone's language: feet and inches in US English, metres everywhere
 * else — on the screen, on the PDF and in the shared text alike. Its figures take the phone's
 * decimal separator, as Measure's plan does: `4,00 m` in French and German, `4.00 m` in English.
 * Each screen run leaves its render in `build/plan-renders/` (not committed).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RerunPlanUnitsTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** 4 × 3 m with its top-right corner cut at 45°, 2.50 m high: 11.5 m², walls of 3, 1.41, 2, 4 and 3 m. */
    private val traced = planSurvey(0f, 0f, 3f, 0f, 4f, 1f, 4f, 3f, 0f, 3f)

    /** 7.29 × 6.05 m, scanned with a 25 cm scatter: about the widest figures a room's survey writes. */
    private val wide = planSurvey(0f, 0f, 7.29f, 0f, 7.29f, 6.05f, 0f, 6.05f, spread = 0.25f)

    /** A figure written in metres: `4.00 m`, `11.5 m²`, `28.8 m³`. */
    private val metric = Regex("""\d m\b|m²|m³""")

    /** The words the screen, the PDF and the shared text are written with, under the test's locale. */
    private fun words(): PlanWords {
        var words: PlanWords? = null
        composeRule.setContent { words = rememberPlanWords() }
        composeRule.waitForIdle()
        return words!!
    }

    /**
     * Shows the traced room's plan under [chrome], saves it as [name] and returns the texts its
     * semantics tree holds: the figures beside the plan. The plan's own labels are drawn on its
     * canvas, out of that tree; they are read through [SurveyReport.planTexts] instead.
     */
    private fun render(chrome: StageChrome, name: String): List<String> {
        composeRule.setContent {
            CompositionLocalProvider(LocalStageChrome provides chrome) {
                RerunPlanView(survey = traced, title = "Traced room", date = null)
            }
        }
        composeRule.waitForIdle()
        val image = composeRule.onNodeWithTag(RERUN_PLAN_TAG).captureToImage().asAndroidBitmap()
        val out = File("build/plan-renders").apply { mkdirs() }.resolve("$name.png")
        out.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .flatMap { node -> node.config[SemanticsProperties.Text].map { it.text } }
    }

    /** The plan as the PDF lays it out: its scale bar's label and the bar's length, in points. */
    private fun pdfScaleBar(words: PlanWords): Pair<String, Float> {
        val metrics = PlanMetrics(1f) { text, size -> text.length * size * GLYPH }
        val drawing = RoomPlanLayout.layout(
            traced, SurveyReport.planTexts(traced, words.survey), PDF_WIDTH, PDF_PLAN_HEIGHT, metrics,
            scales = PDF_SCALES.map { POINTS_PER_METRE / it },
        )
        val label = drawing.texts.single { it.role == PlanTextRole.Scale }.text
        val bar = drawing.lines.filter { it.stroke == PlanStroke.Scale && it.y0 == it.y1 }.maxBy { it.x1 - it.x0 }
        return label to (bar.x1 - bar.x0) / drawing.pxPerMetre
    }

    private fun assertImperialScreen(texts: List<String>) {
        assertTrue(texts.toString(), "124 ft²" in texts)
        assertTrue(texts.toString(), "13 ft 1 in × 9 ft 10 in" in texts)
        assertTrue(texts.toString(), "8 ft 2 in" in texts)
        assertTrue(texts.toString(), texts.none { metric.containsMatchIn(it) })
    }

    @Test
    @Config(qualifiers = "en-rUS-w411dp-h891dp-xhdpi")
    fun `in US English, in light, the plan screen gives feet and inches`() {
        assertImperialScreen(render(StageChrome.Light, "units-us-light"))
    }

    @Test
    @Config(qualifiers = "en-rUS-w411dp-h891dp-xhdpi")
    fun `in US English, in dark, the plan screen gives feet and inches`() {
        assertImperialScreen(render(StageChrome.Media, "units-us-dark"))
    }

    @Test
    @Config(qualifiers = "en-rUS")
    fun `in US English the PDF and the shared text give feet and inches`() {
        val words = words()
        val rows = SurveyReport.rows(traced, words.survey).map { it.value }
        assertEquals(listOf("124 ft²", "13 ft 1 in × 9 ft 10 in", "44 ft", "8 ft 2 in", "1015 ft³"), rows)
        assertEquals(
            listOf("9 ft 10 in", "4 ft 8 in", "6 ft 7 in", "13 ft 1 in", "9 ft 10 in"),
            SurveyReport.wallRows(traced, words.survey).map { it.value },
        )
        val plan = SurveyReport.planTexts(traced, words.survey)
        assertEquals(listOf("9' 10\"", "4' 8\"", "6' 7\"", "13' 1\"", "9' 10\""), plan.walls)
        assertEquals("124 ft²", plan.area)
        assertTrue(words.pdfScale, words.pdfScale.endsWith("dimensions in feet and inches"))
        val text = SurveyReport.text(traced, words.survey, "Traced room", null)
        assertTrue(text, "124 ft²" in text && !metric.containsMatchIn(text))
    }

    @Test
    @Config(qualifiers = "en-rUS")
    fun `in US English the PDF's widest figure ends clear of the walls table beside it`() {
        val rows = SurveyReport.rows(wide, words().survey).map { it.value }
        assertEquals("23 ft 11 in × 19 ft 10 in ± 10 in", rows[1])
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = pdf("TEXT_SIZE")
            typeface = Typeface.DEFAULT
        }
        // The same paper the hints under the figures keep before the walls table.
        val limit = pdf("WALLS_X") - pdf("LEGEND_SPACE")
        for (value in rows) {
            val end = pdf("MARGIN") + pdf("VALUE_X") + paint.measureText(value)
            assertTrue("\"$value\" ends at $end pt, past $limit pt", end <= limit)
        }
    }

    @Test
    @Config(qualifiers = "en-rUS")
    fun `in US English the PDF's scale bar is a round number of feet`() {
        val (label, metres) = pdfScaleBar(words())
        val feet = Regex("""(\d+) ft""").matchEntire(label)?.groupValues?.get(1)?.toInt()
        assertNotNull(label, feet)
        assertTrue(label, feet!! in listOf(1, 2, 5, 10, 20, 50))
        assertEquals(feet * METRES_PER_FOOT, metres, 0.001f)
    }

    @Test
    @Config(qualifiers = "en-rGB-w411dp-h891dp-xhdpi")
    fun `in British English the plan screen stays in metres`() {
        val texts = render(StageChrome.Light, "units-gb-light")
        assertTrue(texts.toString(), "11.5 m²" in texts)
        assertTrue(texts.toString(), texts.none { "ft" in it })
    }

    @Test
    @Config(qualifiers = "en-rGB")
    fun `in British English the PDF and the shared text write the decimal point`() {
        assertMetricReport(words(), ".")
    }

    @Test
    @Config(qualifiers = "fr-rFR-w411dp-h891dp-xhdpi")
    fun `in French the plan screen writes metres with the decimal comma`() {
        val texts = render(StageChrome.Light, "units-fr-light")
        assertTrue(texts.toString(), "11,5 m²" in texts)
        assertTrue(texts.toString(), texts.none { "ft" in it || "11.5" in it })
    }

    @Test
    @Config(qualifiers = "fr-rFR")
    fun `in French the PDF and the shared text write the decimal comma`() {
        assertMetricReport(words(), ",")
    }

    @Test
    @Config(qualifiers = "de-rDE")
    fun `in German the PDF and the shared text write the decimal comma`() {
        assertMetricReport(words(), ",")
    }

    /**
     * The traced room's figures in metres with [mark] as the decimal separator: the PDF's rows, the
     * plan's wall labels, its scale bar and the shared text.
     */
    private fun assertMetricReport(words: PlanWords, mark: String) {
        fun decimal(figure: String) = figure.replace(".", mark)
        assertEquals(decimal("4.00 × 3.00 m"), SurveyReport.rows(traced, words.survey)[1].value)
        assertEquals(
            listOf("3.00", "1.40", "2.00", "4.00", "3.00").map(::decimal),
            SurveyReport.planTexts(traced, words.survey).walls,
        )
        assertTrue(words.pdfScale, words.pdfScale.endsWith("dimensions in metres"))
        val (label, metres) = pdfScaleBar(words)
        assertEquals(label, label.removeSuffix(" m").replace(mark, ".").toFloat(), metres, 0.001f)
        val text = SurveyReport.text(traced, words.survey, "Traced room", null)
        assertTrue(text, decimal("11.5 m²") in text && decimal("4.00 × 3.00 m") in text)
    }

    /** One of the PDF page's own measures, in points. */
    private fun pdf(name: String): Float =
        RoomPlanPdf::class.java.getDeclaredField(name).apply { isAccessible = true }.getFloat(null)

    private companion object {
        const val GLYPH = 0.55f
        const val PDF_WIDTH = 515f
        const val PDF_PLAN_HEIGHT = 388f
        const val POINTS_PER_METRE = 2834.65f
        const val METRES_PER_FOOT = 0.3048f
        val PDF_SCALES = listOf(20, 25, 50, 75, 100, 150, 200, 250, 500)
    }
}
