package io.github.sceneview.demo.demos

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.core.content.IntentCompat
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.internal.MeasureFigureNumbers
import io.github.sceneview.demo.demos.internal.MeasureMode
import io.github.sceneview.demo.demos.internal.MeasureOpening
import io.github.sceneview.demo.demos.internal.MeasurePlanOpening
import io.github.sceneview.demo.demos.internal.MeasureUnitSystem
import io.github.sceneview.demo.demos.internal.measureRoomSurvey
import io.github.sceneview.demo.theme.SceneViewDemoTheme
import io.github.sceneview.math.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

/**
 * Measure's plan page in French, from its share button to the text the share sheet receives:
 * the text the page hands over, not one rebuilt beside it, writes every figure with the decimal
 * comma and sets its walls apart with a semicolon. The PDF is drawn from the same words, but onto a canvas no test reads back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MeasurePlanShareTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

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

    /** A decimal point between two digits, `4.00`. */
    private val point = Regex("""\d\.\d""")

    /** Opens the page in French and metres, taps its text share, and returns what the share sheet was handed. */
    private fun sharedText(): String {
        composeRule.setContent {
            SceneViewDemoTheme(darkTheme = false) {
                MeasurePlanOverlay(
                    page = page,
                    title = "Measured room",
                    date = "21 Oct 2026",
                    locale = Locale.FRANCE,
                    units = MeasureUnitSystem.Metric,
                    onClose = {},
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(RERUN_PLAN_SHARE_TEXT_TAG).performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()
        val chooser = generateSequence { shadowOf(composeRule.activity).nextStartedActivity }
            .firstOrNull { it.action == Intent.ACTION_CHOOSER }
        assertNotNull("the text share opens the share sheet", chooser)
        val send = IntentCompat.getParcelableExtra(chooser!!, Intent.EXTRA_INTENT, Intent::class.java)
        assertNotNull("the share sheet is handed a text to send", send)
        assertEquals("text/plain", send!!.type)
        return requireNotNull(send.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test
    fun `French plan shares its text with a decimal comma`() {
        val text = sharedText()
        assertTrue(text, text.startsWith("Measured room"))
        assertTrue(text, "4,00 × 3,00 m" in text)
        assertTrue(text, "14,00 m" in text)
        assertTrue(text, "12,0 m²" in text)
        assertTrue(text, "30,0 m³" in text)
        assertTrue(text, !point.containsMatchIn(text))
    }

    @Test
    fun `French plan's shared text sets its walls apart with a semicolon, clear of the decimal comma`() {
        val text = sharedText()
        val walls = composeRule.activity.getString(R.string.demo_ar_rerun_plan_walls)
        val line = text.lines().single { it.startsWith("$walls: ") }
        // `A 4,00 m, B 3,00 m` reads as one more decimal: a semicolon ends each wall.
        val parted = Regex("^" + Regex.escape(walls) + ": A [^;]+; B [^;]+; C [^;]+; D [^;]+$")
        assertTrue(line, parted.matches(line))
        assertFalse(line, ", " in line)
    }
}
