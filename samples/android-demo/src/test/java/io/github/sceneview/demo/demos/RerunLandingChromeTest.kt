package io.github.sceneview.demo.demos

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import io.github.sceneview.demo.DemoScaffoldTestTags
import io.github.sceneview.demo.demos.internal.RerunSessionSource
import io.github.sceneview.demo.demos.internal.RerunStoredSession
import io.github.sceneview.demo.theme.SceneViewDemoTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 3D Scan's landing is a list of cards under the scaffold's chrome (#5188): its settings sheet is
 * opaque, the cards fade out before the settings button, and the last card ends above that fade.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RerunLandingChromeTest {
    @get:Rule val compose = createComposeRule()

    private val sessions = (0 until 12).map {
        LandingSession(
            RerunStoredSession(it.toString(), "Room $it", 1_000L, RerunSessionSource.Recorded, 1f, 1f, 1, 0, 0),
            null,
            null,
            "Recorded",
            "1 point",
        )
    }

    /** Opens the landing and returns the theme's `surface-container`. */
    private fun open(darkTheme: Boolean = false): Color {
        var sheetColor = Color.Unspecified
        compose.setContent {
            SceneViewDemoTheme(darkTheme = darkTheme) {
                sheetColor = MaterialTheme.colorScheme.surfaceContainer
                RerunLandingScreen(
                    {},
                    RerunLandingState(sessions, null, false),
                    RerunLandingActions({}, {}, {}, {}, {}, {}, {}),
                )
            }
        }
        compose.waitForIdle()
        return sheetColor
    }

    @Test
    fun `the fade covers the settings button and the last card ends above it`() {
        open()
        val fade = compose.onNodeWithTag(LANDING_BOTTOM_FADE_TAG).fetchSemanticsNode().boundsInRoot
        val button = compose.onNodeWithTag(DemoScaffoldTestTags.SETTINGS_FAB).fetchSemanticsNode().boundsInRoot
        assertTrue("fade top ${fade.top} is above the button top ${button.top}", fade.top <= button.top)
        assertTrue("fade reaches below the button: $fade vs $button", fade.bottom >= button.bottom)

        compose.onNodeWithTag(SESSIONS_LIST_TAG).performScrollToKey("bottom-inset")
        compose.waitForIdle()
        val last = compose.onAllNodesWithTag(SESSION_ROW_TAG).fetchSemanticsNodes()
            .maxBy { it.boundsInRoot.bottom }
        assertTrue(
            "last card bottom ${last.boundsInRoot.bottom} clears the fade top ${fade.top}",
            last.boundsInRoot.bottom <= fade.top + 1f,
        )
    }

    @Test
    fun `the settings sheet is opaque over the cards in light theme`() = assertOpaqueSheet(darkTheme = false)

    @Test
    fun `the settings sheet is opaque over the cards in dark theme`() = assertOpaqueSheet(darkTheme = true)

    private fun assertOpaqueSheet(darkTheme: Boolean) {
        val expected = open(darkTheme)
        compose.onNodeWithTag(DemoScaffoldTestTags.SETTINGS_FAB).performClick()
        compose.waitForIdle()
        val sheet = compose.onNodeWithTag(DemoScaffoldTestTags.SETTINGS_SHEET)
        val bitmap = sheet.captureToImage().asAndroidBitmap()
        // Beside the drag handle, clear of the 28 dp corners and of the header's text.
        val pixel = bitmap.getPixel((bitmap.width * 0.65f).toInt(), 4)
        assertEquals(
            "sheet ground is surface-container, not glass over the cards",
            Integer.toHexString(expected.toArgb()),
            Integer.toHexString(pixel),
        )
    }
}
