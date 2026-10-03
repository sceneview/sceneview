package io.github.sceneview.demo.demos

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the Geometry scene hands to `SceneView(contentPadding = …)`: the chrome as the scaffold
 * reports it, the window's safe insets on the sides, and a top that gives way when keeping it
 * would push the shapes under a settings sheet dragged all the way up — and the bottom the block
 * is framed for, which is the chrome's with that sheet closed.
 */
class GeometryContentPaddingTest {

    // Measured on the Pixel 7a AVD at its default display size, portrait.
    private val scene = 952.dp
    private val chromeTop = 136.dp

    private fun padding(bottom: Float) = geometryContentPadding(
        chrome = PaddingValues(top = chromeTop, bottom = bottom.dp),
        sceneHeight = scene,
    )

    @Test
    fun `with room to spare the chrome is handed over as it is`() {
        val padding = padding(bottom = 280f)
        assertEquals(chromeTop, padding.calculateTopPadding())
        assertEquals(280.dp, padding.calculateBottomPadding())
    }

    @Test
    fun `under a sheet dragged all the way up the top yields and the bottom does not`() {
        // 952 - 754 leaves 198 dp above the sheet; the title row would keep 62 dp of it, less
        // than the tenth of the view (95.2 dp) the SDK keeps visible.
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
    fun `the sides are the window's own, whatever the layout direction`() {
        // A phone held sideways: the cutout is on the left of the glass, in RTL too.
        val padding = geometryContentPadding(
            chrome = PaddingValues(top = 80.dp, bottom = 180.dp),
            sceneHeight = 426.dp,
            left = 48.dp,
            right = 0.dp,
        )
        LayoutDirection.entries.forEach { direction ->
            assertEquals(48.dp, padding.calculateLeftPadding(direction))
            assertEquals(0.dp, padding.calculateRightPadding(direction))
        }
    }

    // ── The band at rest ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `at rest the bottom is whatever the scaffold reports, and follows it`() {
        val rest = GeometryRestBottom()
        // First frame: the chips are not measured yet. Then they are.
        assertEquals(128.dp, rest.observe(live = 128.dp, sheetClosed = true))
        assertEquals(252.dp, rest.observe(live = 252.dp, sheetClosed = true))
    }

    @Test
    fun `an open sheet does not move the bottom at rest`() {
        val rest = GeometryRestBottom()
        rest.observe(live = 252.dp, sheetClosed = true)
        // Opening, settled at its detent, dragged all the way up.
        listOf(260f, 317f, 754f).forEach { live ->
            assertEquals(252.dp, rest.observe(live = live.dp, sheetClosed = false))
        }
    }

    @Test
    fun `a sheet on its way down is not the rest`() {
        val rest = GeometryRestBottom()
        rest.observe(live = 252.dp, sheetClosed = true)
        rest.observe(live = 317.dp, sheetClosed = false)
        // Let go: the scaffold calls it closed while it still covers more than the chrome.
        listOf(317f, 290f, 261f, 252f).forEach { live ->
            assertEquals(252.dp, rest.observe(live = live.dp, sheetClosed = true))
        }
        // Down. The chrome is followed again — here the chips wrapped onto one more row.
        assertEquals(300.dp, rest.observe(live = 300.dp, sheetClosed = true))
    }

    @Test
    fun `with Settings open from the start the live band stands in until it closes`() {
        // The phone was turned with the sheet open: this window was never seen at rest.
        val rest = GeometryRestBottom()
        assertEquals(240.dp, rest.observe(live = 240.dp, sheetClosed = false))
        assertEquals(240.dp, rest.observe(live = 240.dp, sheetClosed = true))
        assertEquals(180.dp, rest.observe(live = 180.dp, sheetClosed = true))
        assertEquals(180.dp, rest.observe(live = 200.dp, sheetClosed = false))
    }
}
