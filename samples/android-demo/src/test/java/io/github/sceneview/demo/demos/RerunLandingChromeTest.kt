package io.github.sceneview.demo.demos

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
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
import kotlin.math.abs

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

    private var opened: LandingSession? = null

    /** Opens the landing and returns the theme's `surface-container`. */
    private fun open(darkTheme: Boolean = false): Color {
        var sheetColor = Color.Unspecified
        compose.setContent {
            SceneViewDemoTheme(darkTheme = darkTheme) {
                sheetColor = MaterialTheme.colorScheme.surfaceContainer
                RerunLandingScreen(
                    {},
                    RerunLandingState(sessions, null, false),
                    RerunLandingActions({}, {}, {}, { opened = it }, {}, {}, {}),
                )
            }
        }
        compose.waitForIdle()
        return sheetColor
    }

    @Test
    fun `the solid band covers the settings button and the last card ends above the fade`() {
        open()
        val fade = compose.onNodeWithTag(LANDING_BOTTOM_FADE_TAG).fetchSemanticsNode().boundsInRoot
        val solid = compose.onNodeWithTag(LANDING_BOTTOM_SOLID_TAG).fetchSemanticsNode().boundsInRoot
        val button = compose.onNodeWithTag(DemoScaffoldTestTags.SETTINGS_FAB).fetchSemanticsNode().boundsInRoot
        assertTrue("solid top ${solid.top} is above the button top ${button.top}", solid.top <= button.top)
        assertTrue("solid band reaches below the button: $solid vs $button", solid.bottom >= button.bottom)

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
    fun `the solid band takes taps and hands drags to the list`() {
        open()
        val solid = compose.onNodeWithTag(LANDING_BOTTOM_SOLID_TAG)
        val bounds = solid.fetchSemanticsNode().boundsInRoot
        // Beside the settings button, over the card the band hides.
        val beside = Offset(bounds.width * 0.15f, bounds.height * 0.3f)
        solid.performTouchInput { click(beside) }
        compose.waitForIdle()
        assertEquals("a tap on the band opens no hidden card", null, opened)

        val before = compose.onAllNodesWithTag(SESSION_ROW_TAG).fetchSemanticsNodes().minOf { it.boundsInRoot.top }
        solid.performTouchInput {
            swipe(beside, Offset(beside.x, beside.y - bounds.height * 0.8f), 300)
        }
        compose.waitForIdle()
        val after = compose.onAllNodesWithTag(SESSION_ROW_TAG).fetchSemanticsNodes().minOf { it.boundsInRoot.top }
        assertTrue("an upward drag on the band scrolls the list forward: $before -> $after", after < before)
    }

    @Test
    fun `full screen drops the band and the list reaches the bottom gutter`() {
        open()
        compose.onNodeWithTag(DemoScaffoldTestTags.FULL_SCREEN).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(LANDING_BOTTOM_FADE_TAG).assertDoesNotExist()
        compose.onNodeWithTag(DemoScaffoldTestTags.SETTINGS_FAB).assertDoesNotExist()
        compose.onNodeWithTag(SESSIONS_LIST_TAG).performScrollToKey("bottom-inset")
        compose.waitForIdle()
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val last = compose.onAllNodesWithTag(SESSION_ROW_TAG).fetchSemanticsNodes()
            .maxBy { it.boundsInRoot.bottom }
        // Without the chrome's 104 dp reserve the last card ends near the window's bottom edge.
        val gap = root.bottom - last.boundsInRoot.bottom
        val density = compose.density.density
        assertTrue("last card ends ${gap / density} dp above the bottom", gap / density < 80f)
    }

    @Test
    fun `the settings sheet is opaque over the cards in light theme`() = assertOpaqueSheet(darkTheme = false)

    @Test
    fun `the settings sheet is opaque over the cards in dark theme`() = assertOpaqueSheet(darkTheme = true)

    /**
     * Every pixel of the sheet's drag-handle band, across its width (the cards' thumbnails and
     * titles lie under it), is the theme's `surface-container`: the glass sheet let them through.
     */
    private fun assertOpaqueSheet(darkTheme: Boolean) {
        val expected = open(darkTheme).toArgb()
        compose.onNodeWithTag(DemoScaffoldTestTags.SETTINGS_FAB).performClick()
        compose.waitForIdle()
        val bitmap = compose.onNodeWithTag(DemoScaffoldTestTags.SETTINGS_SHEET).captureToImage().asAndroidBitmap()
        val density = compose.density.density
        // Clear of the 28 dp corners and of the 32 dp handle in the middle, inside its 48 dp row.
        val corner = (28 * density).toInt()
        val handle = (24 * density).toInt()
        val mid = bitmap.width / 2
        val rows = (4 until (16 * density).toInt() step 2)
        val off = rows.flatMap { y ->
            (corner until bitmap.width - corner)
                .filter { abs(it - mid) > handle }
                .mapNotNull { x -> bitmap.offBy(x, y, expected) }
        }
        assertTrue(
            "sheet ground is surface-container ${Integer.toHexString(expected)}; " +
                "${off.size} pixels differ, e.g. ${off.take(3)}",
            off.isEmpty(),
        )
    }

    private fun Bitmap.offBy(x: Int, y: Int, expected: Int): String? {
        val pixel = getPixel(x, y)
        val delta = listOf(16, 8, 0).maxOf { shift -> abs((pixel shr shift and 0xFF) - (expected shr shift and 0xFF)) }
        return if (delta > 1) "($x,$y)=${Integer.toHexString(pixel)}" else null
    }
}
