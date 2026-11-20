package io.github.sceneview.demo.demos

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import io.github.sceneview.demo.demos.internal.MeasureFigureNumbers
import io.github.sceneview.demo.demos.internal.MeasureMode
import io.github.sceneview.demo.demos.internal.MeasureOpening
import io.github.sceneview.demo.demos.internal.MeasurePlanOpening
import io.github.sceneview.demo.demos.internal.MeasureUnitSystem
import io.github.sceneview.demo.demos.internal.PlanOpening
import io.github.sceneview.demo.demos.internal.PlanOpenings
import io.github.sceneview.demo.demos.internal.RoomSurvey
import io.github.sceneview.demo.demos.internal.measurePlanOpeningsOnPlan
import io.github.sceneview.demo.demos.internal.measureRoomSurvey
import io.github.sceneview.demo.demos.internal.planSurvey
import io.github.sceneview.demo.theme.SceneViewDemoTheme
import io.github.sceneview.math.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.lang.reflect.Modifier
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Measure's plan page with a door and a window marked (#4783): both drawn on the plan on screen,
 * listed under their wall with the net wall area after the walls, and drawn and listed by the PDF
 * and the DXF. Each run leaves its renders in `build/plan-renders/` (not committed): the page in
 * light and in dark, at the top and scrolled to its walls, and the PDF's first page as its writer
 * paints it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS-w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MeasurePlanOpeningsTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    /** FileProvider caches its roots per process; each test has a cache folder of its own (see RerunPlanShareTest). */
    @Before
    fun forgetFileProviderRoots() {
        val cache = FileProvider::class.java.declaredFields.singleOrNull {
            it.name == "sCache" && Modifier.isStatic(it.modifiers) && Map::class.java.isAssignableFrom(it.type)
        }
        assertNotNull("FileProvider has no static sCache map any more: find where it keeps its roots", cache)
        cache!!.isAccessible = true
        (cache.get(null) as MutableMap<*, *>).clear()
    }

    /**
     * A 4 × 3 m room, 2.50 m high: a 1.20 × 1.10 m window 1.00 m along wall A, its sill at 0.90 m,
     * and a 0.93 × 2.04 m door 0.50 m along wall B. 35 m² of wall, 31.78 m² once both are off.
     */
    private val page: MeasurePlanPage = run {
        val outline = listOf(0f to 0f, 4f to 0f, 4f to 3f, 0f to 3f).map { (x, z) -> Position(x, -1.2f, z) }
        MeasurePlanPage(
            survey = measureRoomSurvey(outline, height = 2.5f)!!,
            openings = listOf(
                MeasurePlanOpening("A", MeasureOpening(from = 1f, to = 2.2f, bottom = 0.9f, top = 2f, wall = 0)),
                MeasurePlanOpening("B", MeasureOpening(from = 0.5f, to = 1.43f, bottom = 0f, top = 2.04f, wall = 1)),
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
                openingsArea = 3.22f,
                netWallArea = 31.78f,
            ),
        )
    }

    private val openings: PlanOpenings get() = measurePlanOpeningsOnPlan(page.openings, page.numbers.netWallArea)

    private fun save(image: Bitmap, name: String) {
        val out = File("build/plan-renders").apply { mkdirs() }.resolve("$name.png")
        out.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun showPage(dark: Boolean = false) {
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
    }

    /** Opens the page, saves it at its top and scrolled to its walls as `measure-openings-<theme>`, and returns its texts. */
    private fun open(dark: Boolean): List<String> {
        showPage(dark)
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
        assertTrue(at("Door") < at("0.93 m × 2.04 m"))
        assertTrue(at("0.93 m × 2.04 m") < at("Wall C"))
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

    @Test
    fun `the plan on screen draws the openings it is given`() {
        var marked by mutableStateOf(true)
        composeRule.setContent {
            SceneViewDemoTheme(darkTheme = false) {
                val words = rememberPlanWords(MeasureUnitSystem.Metric, Locale.US)
                PlanCard(page.survey, words, openings = if (marked) openings else PlanOpenings.None)
            }
        }
        composeRule.waitForIdle()
        val drawing = composeRule.onNodeWithTag(RERUN_PLAN_DRAWING_TAG)
        val with = drawing.captureToImage().asAndroidBitmap()
        marked = false
        composeRule.waitForIdle()
        val without = drawing.captureToImage().asAndroidBitmap()
        assertEquals(with.width, without.width)
        val differ = (0 until with.width * with.height).count { with.getPixel(it % with.width, it / with.width) != without.getPixel(it % with.width, it / with.width) }
        // A window's gap and frame, a door's gap, leaf and swing: hundreds of pixels, not a few.
        assertTrue("$differ pixels differ", differ > 200)
    }

    /** A canvas over [bitmap] that keeps every text drawn on it, where, and the lines painted as an opening's marks. */
    private class Reader(bitmap: Bitmap) : Canvas(bitmap) {
        class Placed(val text: String, val left: Float, val right: Float, val y: Float)

        val texts = ArrayList<Placed>()

        /** Each line stroked as an opening's jamb, frame or leaf (1 pt on paper), its bounds. */
        val openingMarks = ArrayList<RectF>()

        override fun drawText(text: String, x: Float, y: Float, paint: Paint) {
            val width = paint.measureText(text)
            val left = when (paint.textAlign) {
                Paint.Align.RIGHT -> x - width
                Paint.Align.CENTER -> x - width / 2
                else -> x
            }
            texts += Placed(text, left, left + width, y)
            super.drawText(text, x, y, paint)
        }

        override fun drawPath(path: Path, paint: Paint) {
            if (paint.style == Paint.Style.STROKE && paint.strokeWidth == OPENING_STROKE_PT) {
                openingMarks += RectF().also { path.computeBounds(it, true) }
            }
            super.drawPath(path, paint)
        }
    }

    /** The plan's words in US English, in [units], as the page reads them. */
    private fun planWords(units: MeasureUnitSystem = MeasureUnitSystem.Metric): PlanWords {
        var words: PlanWords? = null
        composeRule.setContent { words = rememberPlanWords(units, Locale.US) }
        composeRule.waitForIdle()
        return requireNotNull(words)
    }

    /** Every page of the PDF of [survey], as its writer paints them, at twice their size on white paper; the first saved as [name]. */
    private fun pdfPages(
        words: PlanWords,
        openings: PlanOpenings,
        name: String? = null,
        survey: RoomSurvey = page.survey,
    ): List<Reader> {
        val pages = ArrayList<Reader>()
        RoomPlanPdf.draw(survey, words, "Measured room", "Nov 20, 2026", openings) { number, paint ->
            val image = Bitmap.createBitmap(RoomPlanPdf.WIDTH * 2, RoomPlanPdf.HEIGHT * 2, Bitmap.Config.ARGB_8888)
            val reader = Reader(image)
            reader.drawColor(Color.WHITE)
            reader.scale(2f, 2f)
            paint(reader)
            if (number == 1) name?.let { save(image, it) }
            pages += reader
        }
        return pages
    }

    private fun near(a: Float, b: Float, within: Float = 0.5f) = abs(a - b) < within

    @Test
    fun `the PDF draws the door and the window on the plan and lists them under their walls`() {
        val words = planWords()
        val first = pdfPages(words, openings, "measure-openings-pdf-page1").single()
        val texts = first.texts.map { it.text }
        val at = { text: String -> texts.indexOf(text).also { assertTrue("$text in $texts", it >= 0) } }
        assertTrue(at("Wall A") < at("Window") && at("Window") < at("1.20 m × 1.10 m"))
        assertTrue(at("Wall B") < at("Door") && at("Door") < at("0.93 m × 2.04 m"))
        assertTrue(at("Wall D") < at("Net wall area") && at("Net wall area") < at("31.8 m²"))
        // On the plan, at 1:50 (56.69 pt to the metre): the window's two jambs and two frame lines,
        // the door's two jambs and its leaf, open as wide as the door.
        assertTrue(texts.toString(), texts.any { it.startsWith("Scale 1:50") })
        val perMetre = 2834.65f / 50
        val marks = first.openingMarks.map { hypot(it.width(), it.height()) }.sorted()
        assertEquals(marks.toString(), 7, marks.size)
        assertEquals(marks.toString(), 4, marks.count { near(it, 7f) })
        assertEquals(marks.toString(), 1, marks.count { near(it, 0.93f * perMetre) })
        assertEquals(marks.toString(), 2, marks.count { near(it, 1.2f * perMetre) })
        val without = pdfPages(words, PlanOpenings.None).single()
        assertTrue(without.openingMarks.toString(), without.openingMarks.isEmpty())
        assertFalse(without.texts.toString(), without.texts.any { it.text == "Door" || it.text == "Window" || it.text == "Net wall area" })
    }

    @Test
    fun `in feet, a long size stays in its column and an opening at a column's top names its wall`() {
        val words = planWords(MeasureUnitSystem.Imperial)
        val room = planSurvey(0f, 0f, 4f, 0f, 4f, 3f, 0f, 3f)
        // 32 doors of 3.60 × 3.33 m on wall A, "11 ft 10 in × 10 ft 11 in" each: they run over to page 2,
        // whose first column starts with one of them, set apart from wall A's row on page 1.
        val many = PlanOpenings(
            openings = List(32) { PlanOpening(wall = 0, from = 0.2f, to = 3.8f, bottom = 0f, top = 3.33f) },
            netWallArea = 4f,
        )
        val pages = pdfPages(words, many, survey = room)
        assertTrue("${pages.size} pages", pages.size >= 2)
        assertTrue(pages.flatMap { it.texts }.any { it.text.startsWith("11 ft 10 in × 10 ft 11 in") })
        for ((number, page) in pages.withIndex()) {
            // The walls' table: page 1 from its head down, each following page whole.
            val head = page.texts.firstOrNull { it.text == words.survey.walls || it.text == words.pdfWallsContinued }
            assertNotNull("page ${number + 1} has a walls' table", head)
            val table = page.texts.filter { it.y > head!!.y && it.left >= head.left - 0.5f && it.y < 790f }
            // No text runs into the next one on its line.
            for (line in table.groupBy { it.y }.values) {
                val sorted = line.sortedBy { it.left }
                sorted.zipWithNext { a, b -> assertTrue("'${a.text}' runs into '${b.text}' on page ${number + 1}", a.right <= b.left + 0.01f) }
            }
            // A bare "Door" always sits under its wall's row or another opening's, never at a column's top.
            for (door in table.filter { it.text == words.survey.door }) {
                assertTrue(
                    "a bare door at the top of a column on page ${number + 1}",
                    table.any { near(it.left, door.left) && near(it.y, door.y - 16f) },
                )
            }
        }
        assertTrue("an opening at a column's top names its wall", pages.drop(1).flatMap { it.texts }.any { it.text == "A · Door" })
    }

    @Test
    fun `the shared DXF names each opening to the centimetre, as the page does`() {
        showPage()
        val started = shadowOf(app)
        while (started.peekNextStartedActivity() != null) started.nextStartedActivity
        composeRule.onNodeWithTag(RERUN_PLAN_SHARE_DXF_TAG).performScrollTo().performClick()
        composeRule.waitUntil(SHARE_TIMEOUT_MS) { started.peekNextStartedActivity() != null }
        assertEquals(Intent.ACTION_CHOOSER, started.nextStartedActivity.action)
        val dxf = rerunShareDirectory(app, RERUN_SHARE_PLAN).walk().single { it.isFile && it.name.endsWith(".dxf") }
        // The file is ASCII: what is not, a CAD tool reads from \U+XXXX.
        val text = Regex("""\\U\+([0-9A-F]{4})""").replace(dxf.readText(Charsets.US_ASCII)) { it.groupValues[1].toInt(16).toChar().toString() }
        assertTrue(text, "\nDoor 0.93 × 2.04\n" in text)
        assertTrue(text, "\nWindow 1.20 × 1.10, sill 0.90\n" in text)
    }

    private companion object {
        /** The width a PDF strokes an opening's jamb, frame and leaf with, in points. */
        const val OPENING_STROKE_PT = 1f
        const val SHARE_TIMEOUT_MS = 20_000L
    }
}
