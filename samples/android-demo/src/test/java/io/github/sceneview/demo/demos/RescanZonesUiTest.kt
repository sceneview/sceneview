package io.github.sceneview.demo.demos

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.sceneview.demo.demos.internal.RescanKind
import io.github.sceneview.demo.demos.internal.RescanZone
import io.github.sceneview.demo.theme.LocalStageChrome
import io.github.sceneview.demo.theme.SceneViewDemoTheme
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.theme.StageChrome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The parts to scan again on the result (#4736): the pill beside the tour's, and the list a tap
 * opens, each part with its kind, why, and a "Re-scan this part" that starts its re-scan, or is
 * shown but not usable when the scan cannot be lined up on. Each render run leaves the pill and
 * the list, light and dark, in `build/rescan-renders/` (not committed).
 */
@RunWith(RobolectricTestRunner::class)
// A phone's width and height, so the list shows whole as on one.
@Config(sdk = [34], qualifiers = "w393dp-h851dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RescanZonesUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** The three parts the synthetic room with a wall panel, a piece of floor and a corner taken out gives. */
    private val zones = listOf(
        RescanZone(
            RescanKind.MissingWall, floatArrayOf(-0.34f, 0.01f, 1.66f, 0.96f, 2.6f, 1.96f), 1f,
            "1.0 m of this wall was never seen.",
        ),
        RescanZone(
            RescanKind.FloorHole, floatArrayOf(-0.6f, 0.01f, -0.8f, 0.1f, 0.06f, -0.1f), 0.49f,
            "About 0.5 m² of floor was never seen, and nothing stands on it.",
        ),
        RescanZone(
            RescanKind.LowDensity, floatArrayOf(1f, 0.01f, -1.75f, 2f, 0.06f, -0.75f), 1f,
            "Only sparsely seen: about 15 % of the room's usual density over 1.0 m².",
        ),
    )

    /** The pill on the stage's own ground, under the app's [dark] or light theme; a tap opens the list, [onRescan] its buttons. */
    private fun show(dark: Boolean, onRescan: ((RescanZone) -> Unit)? = null) {
        composeRule.setContent {
            SceneViewDemoTheme(darkTheme = dark) {
                val chrome = if (dark) StageChrome.Media else StageChrome.Light
                CompositionLocalProvider(LocalStageChrome provides chrome) {
                    var open by remember { mutableStateOf(false) }
                    Box(Modifier.background(chrome.ground).padding(SceneViewTokens.Space.md).testTag(STAGE_TAG)) {
                        RescanZonesPill(zones.size, onClick = { open = true })
                    }
                    if (open) RescanZonesDialog(zones, onDismiss = { open = false }, onRescan = onRescan)
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun save(image: Bitmap, name: String) {
        val out = File("build/rescan-renders").apply { mkdirs() }.resolve("$name.png")
        out.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Whether [image] holds the amber the 3D view frames the parts with. */
    private fun holdsAmber(image: Bitmap): Boolean {
        val amber = SceneViewTokens.ArOverlay.accentGuidance.toArgb()
        val pixels = IntArray(image.width * image.height)
        image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
        return pixels.any { close(it, amber) }
    }

    private fun close(a: Int, b: Int) = (0 until 3).all { shift ->
        val bits = shift * BITS_PER_CHANNEL
        kotlin.math.abs((a shr bits and CHANNEL) - (b shr bits and CHANNEL)) <= AMBER_SLACK
    }

    /** The pill and the list under one theme, saved, and the amber swatch found on both. */
    private fun render(dark: Boolean) {
        val name = if (dark) "dark" else "light"
        show(dark, onRescan = {})
        val pill = composeRule.onNodeWithTag(STAGE_TAG).captureToImage().asAndroidBitmap()
        save(pill, "rescan-pill-$name")
        assertTrue("no amber swatch on the $name pill", holdsAmber(pill))
        composeRule.onNodeWithTag(RERUN_MODEL_RESCAN_TAG).performClick()
        composeRule.waitForIdle()
        val list = composeRule.onNode(isDialog()).captureToImage().asAndroidBitmap()
        save(list, "rescan-list-$name")
        assertTrue("no amber swatch on the $name list", holdsAmber(list))
    }

    @Test
    fun `without a scan to line up on, each part's re-scan is shown but not usable`() {
        show(dark = false)
        composeRule.onNodeWithTag(RERUN_MODEL_RESCAN_TAG)
            .assertContentDescriptionEquals("3 parts to scan again")
            .performClick()
        composeRule.onNodeWithText("Missing wall").assertExists()
        composeRule.onNodeWithText("Hole in the floor").assertExists()
        composeRule.onNodeWithText("Sparse area").assertExists()
        zones.forEach { composeRule.onNodeWithText(it.reason).assertExists() }
        val actions = composeRule.onAllNodesWithTag(RERUN_MODEL_RESCAN_ACTION_TAG)
        actions.assertCountEquals(zones.size)
        repeat(zones.size) { actions[it].assertIsNotEnabled() }
        composeRule.onNodeWithText("Scanning one part on its own is not available yet", substring = true).assertExists()

        composeRule.onNodeWithText("Close").performClick()
        composeRule.onAllNodesWithTag(RERUN_MODEL_RESCAN_ACTION_TAG).assertCountEquals(0)
    }

    @Test
    fun `each part's re-scan starts that part's`() {
        val picked = mutableListOf<RescanZone>()
        show(dark = false, onRescan = { picked += it })
        composeRule.onNodeWithTag(RERUN_MODEL_RESCAN_TAG).performClick()
        val actions = composeRule.onAllNodesWithTag(RERUN_MODEL_RESCAN_ACTION_TAG)
        actions.assertCountEquals(zones.size)
        repeat(zones.size) { actions[it].assertIsEnabled() }
        composeRule.onNodeWithText("Scan one again and it is added to a copy of this scan", substring = true).assertExists()
        composeRule.onNodeWithText("not available yet", substring = true).assertDoesNotExist()

        actions[1].performClick()
        assertEquals(1, picked.size)
        assertSame(zones[1], picked.single())
    }

    @Test
    fun `one part is counted in the singular`() {
        composeRule.setContent {
            SceneViewDemoTheme(darkTheme = false) { RescanZonesPill(1, onClick = {}) }
        }
        composeRule.onNodeWithTag(RERUN_MODEL_RESCAN_TAG).assertContentDescriptionEquals("1 part to scan again")
    }

    @Test
    fun `the pill and the list in the light theme`() = render(dark = false)

    @Test
    fun `the pill and the list in the dark theme`() = render(dark = true)

    private companion object {
        const val STAGE_TAG = "rescan_stage"
        const val BITS_PER_CHANNEL = 8
        const val CHANNEL = 0xFF

        /** Antialiasing at the swatch's edges: its core still lands within a few levels of the token. */
        const val AMBER_SLACK = 6
    }
}
