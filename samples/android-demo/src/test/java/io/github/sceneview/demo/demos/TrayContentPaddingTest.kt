package io.github.sceneview.demo.demos

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.sceneview.demo.demoContentPadding
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What Rolling Balls hands to `SceneView(contentPadding)` (#4310): the bottom of the chrome as it
 * is, and a top that gives way when keeping it would push the board under the settings sheet.
 *
 * The rule is the demos' shared [demoContentPadding] since #4326; these are the cases the board was
 * framed by before it was shared, called the way Rolling Balls calls it, with the same numbers.
 */
class TrayContentPaddingTest {

    // Measured on the Pixel 7a AVD at its default display size.
    private val scene = 952.dp
    private val statusBar = 48.dp
    private val chromeTop = 136.dp

    private fun padding(bottom: Float, compactHeight: Boolean = false) = demoContentPadding(
        cover = PaddingValues(top = chromeTop, bottom = bottom.dp),
        sceneHeight = scene,
        statusBar = statusBar,
        compactHeight = compactHeight,
    )

    @Test
    fun `with room to spare the chrome is handed over as it is`() {
        val padding = padding(bottom = 332f)
        assertEquals(chromeTop, padding.calculateTopPadding())
        assertEquals(332.dp, padding.calculateBottomPadding())
    }

    @Test
    fun `under a sheet dragged all the way up the top yields and the bottom does not`() {
        // 952 - 754 leaves 198 dp above the sheet; the title row would keep 62 dp of it, less
        // than the tenth of the view (95.2 dp) the camera needs.
        val padding = padding(bottom = 754f)
        assertEquals(754.dp, padding.calculateBottomPadding())
        assertEquals(952f - 754f - 95.2f, padding.calculateTopPadding().value, 0.01f)
    }

    @Test
    fun `the top never goes negative when the sheet alone passes the floor`() {
        val padding = padding(bottom = 900f)
        assertEquals(0.dp, padding.calculateTopPadding())
        assertEquals(900.dp, padding.calculateBottomPadding())
    }

    @Test
    fun `a phone held sideways keeps only the status bar clear at the top`() {
        val padding = padding(bottom = 120f, compactHeight = true)
        assertEquals(statusBar, padding.calculateTopPadding())
        assertEquals(120.dp, padding.calculateBottomPadding())
    }

    @Test
    fun `sideways the status bar is kept for as long as the sheet leaves a tenth of the view under it`() {
        // A 427 dp window: the SDK keeps 42.7 dp visible. Up to a 336.3 dp sheet the status bar
        // stays clear, as it always did; past it the board used to be pushed under the sheet by the
        // SDK's own floor, and the top now yields instead.
        fun top(bottom: Float) = demoContentPadding(
            cover = PaddingValues(top = chromeTop, bottom = bottom.dp),
            sceneHeight = 427.dp,
            statusBar = statusBar,
            compactHeight = true,
        ).calculateTopPadding()
        assertEquals(statusBar, top(bottom = 120f))
        assertEquals(statusBar, top(bottom = 336f))
        assertEquals(427f - 360f - 42.7f, top(bottom = 360f).value, 0.01f)
    }

    @Test
    fun `the window's side insets stay on their side whatever the layout direction`() {
        // A display cutout on the left of a phone held sideways: the controls are centred in what
        // it leaves, and so is the board.
        val padding = demoContentPadding(
            cover = PaddingValues(top = chromeTop, bottom = 120.dp),
            sceneHeight = 427.dp,
            left = 52.dp,
            right = 0.dp,
            statusBar = statusBar,
            compactHeight = true,
        )
        for (direction in LayoutDirection.entries) {
            assertEquals(52.dp, padding.calculateLeftPadding(direction))
            assertEquals(0.dp, padding.calculateRightPadding(direction))
        }
    }
}
