package io.github.sceneview.demo.demos

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.core.view.WindowCompat
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.common.LocalStatusBarIcons
import io.github.sceneview.demo.common.ProvideStatusBarIcons
import io.github.sceneview.demo.common.StatusBarIconsState
import io.github.sceneview.demo.demos.internal.MeasureFigureNumbers
import io.github.sceneview.demo.demos.internal.MeasureMode
import io.github.sceneview.demo.demos.internal.MeasureUnitSystem
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
import java.util.Locale

/**
 * The status bar over Measure's plan. The camera screen asks for light icons over its dark scrim;
 * the plan's page, on the themed stage's light ground, keeps the theme's dark ones. The camera
 * screen runs on under the page, so its request must not outlive the opening of the page — the
 * icons were white on a light ground — and must come back when the page closes. TalkBack, likewise,
 * reads the page alone while it is open, and the camera screen again once it closes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MeasurePlanStatusBarTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /** A 4 × 3 m room, 2.50 m high, with no door nor window. */
    private val page: MeasurePlanPage = run {
        val outline = listOf(0f to 0f, 4f to 0f, 4f to 3f, 0f to 3f).map { (x, z) -> Position(x, -1.2f, z) }
        MeasurePlanPage(
            survey = measureRoomSurvey(outline, height = 2.5f)!!,
            openings = emptyList(),
            numbers = MeasureFigureNumbers(
                mode = MeasureMode.Room,
                segments = listOf(4f, 3f, 4f, 3f),
                length = 14f,
                closed = true,
                outlineArea = 12f,
                height = 2.5f,
                wallArea = 35f,
                volume = 30f,
            ),
        )
    }

    private val planOpen = mutableStateOf(false)
    private var icons: StatusBarIconsState? = null

    /** Measure as the app lays it out: the camera screen, and its plan over it while open. */
    private fun setMeasure() {
        composeRule.setContent {
            SceneViewDemoTheme(darkTheme = false) {
                ProvideStatusBarIcons {
                    icons = LocalStatusBarIcons.current
                    Box(Modifier.fillMaxSize()) {
                        MeasureCameraLayer(covered = planOpen.value) {
                            DemoScaffold(title = "Measure", onBack = {}) { Box(Modifier.fillMaxSize()) }
                        }
                        if (planOpen.value) {
                            MeasurePlanOverlay(
                                page = page,
                                title = "Measured room",
                                date = "21 Oct 2026",
                                locale = Locale.UK,
                                units = MeasureUnitSystem.Metric,
                                onClose = { planOpen.value = false },
                            )
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    /** How many nodes TalkBack can reach that read the camera screen's title. */
    private val cameraTitles: Int
        get() = composeRule.onAllNodesWithText("Measure").fetchSemanticsNodes().size

    /** `true` when the window draws dark status-bar icons, for a light bar. */
    private val darkIcons: Boolean
        get() = composeRule.activity.window.let { WindowCompat.getInsetsController(it, it.decorView).isAppearanceLightStatusBars }

    @Test
    fun `in light theme with nothing asking for light icons the window keeps the theme's dark ones`() {
        composeRule.setContent {
            SceneViewDemoTheme(darkTheme = false) {
                ProvideStatusBarIcons { Box(Modifier.fillMaxSize()) }
            }
        }
        composeRule.waitForIdle()
        assertTrue(darkIcons)
    }

    @Test
    fun `the plan's page takes dark icons in light theme, and closing it gives the camera its light ones back`() {
        setMeasure()
        assertEquals(1, icons!!.lightIconRequests)
        assertFalse("light icons over the camera's scrim", darkIcons)

        planOpen.value = true
        composeRule.waitForIdle()
        assertEquals(0, icons!!.lightIconRequests)
        assertTrue("dark icons over the plan's light ground", darkIcons)

        planOpen.value = false
        composeRule.waitForIdle()
        assertEquals(1, icons!!.lightIconRequests)
        assertFalse("light icons again over the camera's scrim", darkIcons)
    }

    @Test
    fun `TalkBack reads the plan's page alone, and the camera screen again once the page closes`() {
        setMeasure()
        assertTrue("the camera screen's title is read", cameraTitles > 0)

        planOpen.value = true
        composeRule.waitForIdle()
        assertEquals("the camera screen's title under the open plan", 0, cameraTitles)
        assertTrue(
            "the plan's own title is read",
            composeRule.onAllNodesWithText("Plan").fetchSemanticsNodes().isNotEmpty(),
        )

        planOpen.value = false
        composeRule.waitForIdle()
        assertTrue("the camera screen's title is read again", cameraTitles > 0)
    }
}
