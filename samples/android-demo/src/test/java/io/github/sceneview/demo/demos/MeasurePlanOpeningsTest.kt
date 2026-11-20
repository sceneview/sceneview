package io.github.sceneview.demo.demos

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import io.github.sceneview.demo.demos.internal.MeasureFigureNumbers
import io.github.sceneview.demo.demos.internal.MeasureMode
import io.github.sceneview.demo.demos.internal.MeasureOpening
import io.github.sceneview.demo.demos.internal.MeasurePlanOpening
import io.github.sceneview.demo.demos.internal.MeasureUnitSystem
import io.github.sceneview.demo.demos.internal.PlanOpenings
import io.github.sceneview.demo.demos.internal.measurePlanOpeningsOnPlan
import io.github.sceneview.demo.demos.internal.measureRoomSurvey
import io.github.sceneview.demo.theme.SceneViewDemoTheme
import io.github.sceneview.math.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Locale

/**
 * Measure's plan page with a door and a window marked (#4783): both are listed under their wall
 * with the net wall area after the walls, and the PDF draws and lists them. Each run leaves its
 * renders in `build/plan-renders/` (not committed): the page in light and in dark, at the top and
 * scrolled to its walls, and the PDF's first page as its writer paints it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS-w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MeasurePlanOpeningsTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /**
     * A 4 × 3 m room, 2.50 m high: a 1.20 × 1.10 m window 1.00 m along wall A, its sill at 0.90 m,
     * and a 0.90 × 2.10 m door 0.50 m along wall B. 35 m² of wall, 31.79 m² once both are off.
     */
    private val page: MeasurePlanPage = run {
        val outline = listOf(0f to 0f, 4f to 0f, 4f to 3f, 0f to 3f).map { (x, z) -> Position(x, -1.2f, z) }
        MeasurePlanPage(
            survey = measureRoomSurvey(outline, height = 2.5f)!!,
            openings = listOf(
                MeasurePlanOpening("A", MeasureOpening(from = 1f, to = 2.2f, bottom = 0.9f, top = 2f, wall = 0)),
                MeasurePlanOpening("B", MeasureOpening(from = 0.5f, to = 1.4f, bottom = 0f, top = 2.1f, wall = 1)),
            ),
            numbers = MeasureFigureNumbers(
                mode = MeasureMode.Room,
                segments = listOf(4f, 3f, 4f, 3f),
                length = 14f,
                closed = true,
                outlineArea = 12f,
                height = 2.5f,
                wallArea = 35f,
                volume = 30f,
                openingsArea = 3.21f,
                netWallArea = 31.79f,
            ),
        )
    }

    private val openings: PlanOpenings get() = measurePlanOpeningsOnPlan(page.openings, page.numbers.netWallArea)

    private fun save(image: Bitmap, name: String) {
        val out = File("build/plan-renders").apply { mkdirs() }.resolve("$name.png")
        out.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Opens the page, saves it at its top and scrolled to its walls as `measure-openings-<theme>`, and returns its texts. */
    private fun open(dark: Boolean): List<String> {
        composeRule.setContent {
            SceneViewDemoTheme(darkTheme = dark) {
                MeasurePlanOverlay(
                    page = page,
                    title = "Measured room",
                    date = "Nov 20, 2026",
                    locale = Locale.US,
                    units = MeasureUnitSystem.Metric,
                    onClose = {},
                )
            }
        }
        composeRule.waitForIdle()
        val theme = if (dark) "dark" else "light"
        save(composeRule.onRoot().captureToImage().asAndroidBitmap(), "measure-openings-$theme")
        // Down to the last share: the walls list, its net area row included, clear of the card.
        composeRule.onNodeWithTag(RERUN_PLAN_SHARE_TEXT_TAG).performScrollTo()
        composeRule.waitForIdle()
        save(composeRule.onRoot().captureToImage().asAndroidBitmap(), "measure-openings-$theme-walls")
        return composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .flatMap { node -> node.config[SemanticsProperties.Text].map { it.text } }
    }

    private fun assertListsTheOpenings(texts: List<String>) {
        val at = { text: String -> texts.indexOf(text).also { assertTrue("$text in $texts", it >= 0) } }
        // Each under its own wall, in the walls' order, and the net area after the last wall.
        assertTrue(at("Wall A") < at("Window"))
        assertTrue(at("Window") < at("1.20 m × 1.10 m"))
        assertTrue(at("1.20 m × 1.10 m") < at("Wall B"))
        assertTrue(at("Wall B") < at("Door"))
        assertTrue(at("Door") < at("0.90 m × 2.10 m"))
        assertTrue(at("0.90 m × 2.10 m") < at("Wall C"))
        // The card under the plan says it too: once there, once in the walls list, after wall D.
        assertEquals(texts.toString(), 2, texts.count { it == "Net wall area" })
        assertEquals(texts.toString(), 2, texts.count { it == "31.8 m²" })
        assertTrue(texts.indices.any { texts[it] == "Net wall area" && it > at("Wall D") })
    }

    @Test
    fun `in light the walls list names the door and the window under their walls, then the net wall area`() {
        assertListsTheOpenings(open(dark = false))
    }

    @Test
    fun `in dark the walls list names the door and the window under their walls, then the net wall area`() {
        assertListsTheOpenings(open(dark = true))
    }

    /** A canvas over [bitmap] that keeps every text drawn on it and counts the lines. */
    private class Reader(bitmap: Bitmap) : Canvas(bitmap) {
        val texts = ArrayList<String>()
        var paths = 0

        override fun drawText(text: String, x: Float, y: Float, paint: Paint) {
            texts += text
            super.drawText(text, x, y, paint)
        }

        override fun drawPath(path: Path, paint: Paint) {
            paths++
            super.drawPath(path, paint)
        }
    }

    /** The plan's words in US English and metres, as the page reads them. */
    private fun planWords(): PlanWords {
        var words: PlanWords? = null
        composeRule.setContent { words = rememberPlanWords(MeasureUnitSystem.Metric, Locale.US) }
        composeRule.waitForIdle()
        return requireNotNull(words)
    }

    /** The PDF's first page, as its writer paints it, at twice its size on white paper. */
    private fun pdfFirstPage(words: PlanWords, openings: PlanOpenings, name: String?): Reader {
        val image = Bitmap.createBitmap(RoomPlanPdf.WIDTH * 2, RoomPlanPdf.HEIGHT * 2, Bitmap.Config.ARGB_8888)
        val reader = Reader(image)
        reader.drawColor(Color.WHITE)
        reader.scale(2f, 2f)
        var pages = 0
        RoomPlanPdf.draw(page.survey, words, "Measured room", "Nov 20, 2026", openings) { number, paint ->
            pages++
            if (number == 1) paint(reader)
        }
        assertEquals(1, pages)
        name?.let { save(image, it) }
        return reader
    }

    @Test
    fun `the PDF draws the door and the window on the plan and lists them under their walls`() {
        val words = planWords()
        val withOpenings = pdfFirstPage(words, openings, "measure-openings-pdf-page1")
        val texts = withOpenings.texts
        val at = { text: String -> texts.indexOf(text).also { assertTrue("$text in $texts", it >= 0) } }
        assertTrue(at("Wall A") < at("Window") && at("Window") < at("1.20 m × 1.10 m"))
        assertTrue(at("Wall B") < at("Door") && at("Door") < at("0.90 m × 2.10 m"))
        assertTrue(at("Wall D") < at("Net wall area") && at("Net wall area") < at("31.8 m²"))
        val without = pdfFirstPage(words, PlanOpenings.None, null)
        // Jambs, frame, leaf and swing, and each broken wall in two: more lines on the plan.
        assertTrue("${withOpenings.paths} vs ${without.paths}", withOpenings.paths > without.paths)
        assertFalse(without.texts.toString(), without.texts.any { it == "Door" || it == "Window" || it == "Net wall area" })
    }
}
