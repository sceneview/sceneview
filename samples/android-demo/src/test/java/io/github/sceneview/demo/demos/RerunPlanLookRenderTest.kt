package io.github.sceneview.demo.demos

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.internal.MeasureUnitSystem
import io.github.sceneview.demo.demos.internal.PlanMetrics
import io.github.sceneview.demo.demos.internal.RescanKind
import io.github.sceneview.demo.demos.internal.RescanZone
import io.github.sceneview.demo.demos.internal.ResultFloor
import io.github.sceneview.demo.demos.internal.RoomPlanLayout
import io.github.sceneview.demo.demos.internal.SurveyReport
import io.github.sceneview.demo.demos.internal.structuredSurvey
import io.github.sceneview.demo.theme.LocalStageChrome
import io.github.sceneview.demo.theme.StageChrome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The plan of a scanned room, in light and in dark (#4735): its floor squared by the grid every
 * result view stands on, a line every quarter metre, and the part still to scan laid on it in the
 * warning's amber, named in the legend. Each run leaves its render in `build/plan-renders/` (not
 * committed).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RerunPlanLookRenderTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** 4 × 3 m, read off a scan: on the page, its frame is the scan's. */
    private val survey = structuredSurvey(0f, 0f, 4f, 0f, 4f, 3f, 0f, 3f)

    /** A hole in the floor near the top-left corner, still to scan. */
    private val hole = RescanZone(RescanKind.FloorHole, floatArrayOf(0.25f, 0f, 0.25f, 1.25f, 0.05f, 1f), 0.75f, "a hole")

    private class Render(val image: Bitmap, val origin: FloatArray, val pxPerMetre: Float, val notScanned: String)

    /** Shows the room's plan under [chrome] with [zones], saves its drawing as [name]. */
    private fun render(chrome: StageChrome, name: String, zones: List<RescanZone>): Render {
        var words: PlanWords? = null
        var unit = 0f
        var notScanned = ""
        composeRule.setContent {
            words = rememberPlanWords(MeasureUnitSystem.Metric, Locale.UK)
            val density = LocalDensity.current
            unit = density.density * density.fontScale.coerceAtMost(1.3f)
            notScanned = stringResource(R.string.scan_result_not_scanned)
            CompositionLocalProvider(LocalStageChrome provides chrome) {
                RerunPlanView(
                    survey = survey, title = "Scanned room", date = null,
                    units = MeasureUnitSystem.Metric, locale = Locale.UK, zones = zones,
                )
            }
        }
        composeRule.waitForIdle()
        val image = composeRule.onNodeWithTag(RERUN_PLAN_DRAWING_TAG).captureToImage().asAndroidBitmap()
        File("build/plan-renders").apply { mkdirs() }.resolve("$name.png").outputStream().use {
            image.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        // The layout the card drew, laid out again on the same size.
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val metrics = PlanMetrics(unit) { text, size ->
            paint.textSize = size
            paint.measureText(text)
        }
        val texts = SurveyReport.planTexts(survey, words!!.survey)
        val drawing = RoomPlanLayout.layout(survey, texts, image.width.toFloat(), image.height.toFloat(), metrics)
        val origin = ResultFloor.planOrigin(drawing, survey)!!
        return Render(image, origin, drawing.pxPerMetre, notScanned)
    }

    private fun Render.x(u: Float) = (origin[0] + u * pxPerMetre).roundToInt()
    private fun Render.y(v: Float) = (origin[1] + v * pxPerMetre).roundToInt()

    private fun luminance(argb: Int) = 0.299f * Color.red(argb) + 0.587f * Color.green(argb) + 0.114f * Color.blue(argb)

    /** How many of the room's inner quarter-metre lines cross the floor a little above its bottom wall. */
    private fun Render.gridLinesAcross(): Int {
        val row = y(2.375f)
        val from = x(0.1f)
        val to = x(3.9f)
        val floor = (from..to).map { luminance(image.getPixel(it, row)) }.sorted().let { it[it.size / 2] }
        return (1..15).count { k ->
            val at = x(k * 0.25f)
            (at - 2..at + 2).any { abs(luminance(image.getPixel(it, row)) - floor) > GRID_CONTRAST }
        }
    }

    /** The mean of red minus blue over the page rectangle [u0, v0]–[u1, v1]. */
    private fun Render.warmth(u0: Float, v0: Float, u1: Float, v1: Float): Float {
        var sum = 0f
        var n = 0
        for (py in y(v0)..y(v1)) for (px in x(u0)..x(u1)) {
            val c = image.getPixel(px, py)
            sum += Color.red(c) - Color.blue(c)
            n++
        }
        return sum / n
    }

    private fun assertLook(chrome: StageChrome, name: String) {
        val render = render(chrome, name, listOf(hole))
        val lines = render.gridLinesAcross()
        assertTrue("$lines of the 15 inner grid lines cross the floor", lines >= 14)
        val zone = render.warmth(0.4f, 0.4f, 1.1f, 0.85f)
        val beside = render.warmth(2.6f, 0.4f, 3.4f, 0.85f)
        assertTrue("the part still to scan is not amber: $zone against $beside beside it", zone - beside > AMBER_LEAD)
        assertEquals(1, composeRule.onAllNodesWithText(render.notScanned).fetchSemanticsNodes().size)
    }

    @Test
    fun `in light the plan stands on its grid, the part still to scan in amber`() =
        assertLook(StageChrome.Light, "look-light")

    @Test
    fun `in dark the plan stands on its grid, the part still to scan in amber`() =
        assertLook(StageChrome.Media, "look-dark")

    @Test
    fun `with nothing left to scan the grid stays and the legend names no zone`() {
        val render = render(StageChrome.Light, "look-whole", emptyList())
        assertTrue(render.gridLinesAcross() >= 14)
        val zone = render.warmth(0.4f, 0.4f, 1.1f, 0.85f)
        val beside = render.warmth(2.6f, 0.4f, 3.4f, 0.85f)
        assertTrue(abs(zone - beside) < AMBER_LEAD / 2)
        assertEquals(0, composeRule.onAllNodesWithText(render.notScanned).fetchSemanticsNodes().size)
    }

    private companion object {
        /** A grid line's least step in luminance from the floor it crosses. */
        const val GRID_CONTRAST = 8f

        /** How much warmer (red over blue) a zone still to scan reads than the floor beside it. */
        const val AMBER_LEAD = 25f
    }
}
