package io.github.sceneview.demo

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the demos with a scene under the glass hand to `contentPadding`, and what they fit their
 * camera for (#4326). The shots themselves are tested where they are composed:
 * `DoublePendulumHomeShotTest`, `ContactShadowHomeShotTest`, `ViewerHomeFramingTest`; the
 * composable that reads the window is in `DemoSceneFrameTest`.
 */
class DemoSubjectFramingTest {

    // A Pixel 7a held sideways, at its default display size.
    private val width = 914.dp
    private val height = 411.dp
    private val chromeTop = 80.dp

    private fun padding(bottom: Float) = demoContentPadding(
        cover = PaddingValues(top = chromeTop, bottom = bottom.dp),
        sceneHeight = height,
    )

    @Test
    fun `with room to spare the cover is handed over as it is`() {
        val padding = padding(bottom = 190f)
        assertEquals(chromeTop, padding.calculateTopPadding())
        assertEquals(190.dp, padding.calculateBottomPadding())
    }

    @Test
    fun `under a sheet dragged all the way up the top yields and the bottom does not`() {
        // 411 - 320 leaves 91 dp above the sheet; the title row would keep 11 dp of it, less
        // than the tenth of the view (41.1 dp) the SDK keeps visible.
        val padding = padding(bottom = 320f)
        assertEquals(320.dp, padding.calculateBottomPadding())
        assertEquals(411f - 320f - 41.1f, padding.calculateTopPadding().value, 0.01f)
    }

    @Test
    fun `the top never goes negative when the sheet alone passes the floor`() {
        val padding = padding(bottom = 400f)
        assertEquals(0.dp, padding.calculateTopPadding())
        assertEquals(400.dp, padding.calculateBottomPadding())
    }

    @Test
    fun `a phone held sideways keeps only the status bar clear at the top`() {
        // The title is a chip in a corner; its row is not a band over a centred subject.
        val padding = demoContentPadding(
            cover = PaddingValues(top = chromeTop, bottom = 190.dp),
            sceneHeight = height,
            statusBar = 24.dp,
            compactHeight = true,
        )
        assertEquals(24.dp, padding.calculateTopPadding())
        assertEquals(190.dp, padding.calculateBottomPadding())
    }

    @Test
    fun `a slot the scaffold already inset gets no top, sideways or not`() {
        // Its cover has no top: the status bar is above the slot, not over it.
        val padding = demoContentPadding(
            cover = PaddingValues(bottom = 120.dp),
            sceneHeight = 300.dp,
            statusBar = 24.dp,
            compactHeight = true,
        )
        assertEquals(0.dp, padding.calculateTopPadding())
    }

    @Test
    fun `sideways too the top yields before the band above the sheet does`() {
        val padding = demoContentPadding(
            cover = PaddingValues(top = chromeTop, bottom = 360.dp),
            sceneHeight = height,
            statusBar = 24.dp,
            compactHeight = true,
        )
        assertEquals(411f - 360f - 41.1f, padding.calculateTopPadding().value, 0.01f)
    }

    @Test
    fun `the sides are the window's own, whatever the layout direction`() {
        // The cutout is on the left of the glass, in RTL too.
        val padding = demoContentPadding(
            cover = PaddingValues(top = chromeTop, bottom = 190.dp),
            sceneHeight = height,
            left = 48.dp,
            right = 0.dp,
        )
        LayoutDirection.entries.forEach { direction ->
            assertEquals(48.dp, padding.calculateLeftPadding(direction))
            assertEquals(0.dp, padding.calculateRightPadding(direction))
        }
    }

    @Test
    fun `the free aspect is the area between the bands and the side insets`() {
        val padding = demoContentPadding(
            cover = PaddingValues(top = chromeTop, bottom = 190.dp),
            sceneHeight = height,
            left = 48.dp,
            right = 24.dp,
        )
        assertEquals((914f - 72f) / (411f - 270f), demoFreeAspect(width, height, padding), 0.0001f)
    }

    @Test
    fun `a scene already inset by its bands is free edge to edge`() {
        // Upright, the scaffold insets the slot itself and reports nothing over it.
        val padding = demoContentPadding(PaddingValues(0.dp), sceneHeight = 538.dp)
        assertEquals(411f / 538f, demoFreeAspect(411.dp, 538.dp, padding), 0.0001f)
    }

    @Test
    fun `the free aspect keeps the SDK's floor on each axis`() {
        val padding = PaddingValues(horizontal = 500.dp, vertical = 300.dp)
        assertEquals(91.4f / 41.1f, demoFreeAspect(width, height, padding), 0.0001f)
    }

    @Test
    fun `a scene that has not been measured has a usable aspect`() {
        assertEquals(1f, demoFreeAspect(0.dp, 0.dp, PaddingValues(0.dp)), 0f)
    }

    // One arm of 0.5 m carrying one of 0.3 m, the tip bob the heavier one — as in the demo.
    private fun ceiling(angle1: Double, angle2: Double) = pendulumCeiling(
        length1 = 0.5f, length2 = 0.3f, mass1 = 1f, mass2 = 1.6f,
        releaseAngle1 = angle1.toFloat(), releaseAngle2 = angle2.toFloat(),
        jointBobRadius = 0.06f, tipBobRadius = 0.08f,
    )

    @Test
    fun `a pendulum let go hanging straight down has nowhere to climb`() {
        // Both bobs stay where they hang, but for the few centimetres granted to the integrator:
        // the top of what is drawn is the joint bob, an arm's length under the hinge.
        val ceiling = ceiling(0.0, 0.0)
        assertTrue(ceiling < -0.5f + 0.06f + 0.05f)
        assertTrue(ceiling >= -0.5f + 0.06f)
    }

    @Test
    fun `a pendulum let go straight up can reach the whole disc and no further`() {
        assertEquals(0.5f + 0.3f + 0.08f, ceiling(Math.PI, Math.PI), 0.0001f)
    }

    @Test
    fun `the higher the release, the higher the ceiling`() {
        val ceilings = listOf(0.2, 0.5, 0.8, 1.0).map { ceiling(Math.PI * it, Math.PI * it) }
        ceilings.zipWithNext().forEach { (lower, higher) -> assertTrue(lower < higher) }
    }

    @Test
    fun `let go level, neither bob is framed far above the hinge`() {
        // Both arms horizontal: no energy to spare. The tip can only pass the hinge by what the
        // joint gives up below it, and the weighted sum of the two heights stays at zero.
        val ceiling = ceiling(Math.PI / 2, Math.PI / 2)
        // Joint with the tip hanging under it: (0 + 1.6 * 0.3) / 2.6 = 0.185 above the hinge.
        // Tip with the joint under it: (0 + 1 * 0.3) / 2.6 = 0.115. Plus allowance and radius.
        assertEquals(0.185f + 0.03f + 0.06f, ceiling, 0.001f)
    }

    @Test
    fun `upright the pendulum is fitted around its hinge, whatever its ceiling`() {
        val upright = pendulumSubject(pivotHeight = 1.4f, swingExtent = 1.9f, ceiling = 0.6f, strip = false)
        assertEquals(1.9f, upright.extentY, 0f)
        assertEquals(1.4f, upright.centerY, 0f)
    }

    @Test
    fun `in a strip the pendulum is fitted from the floor to its ceiling`() {
        val strip = pendulumSubject(pivotHeight = 1.4f, swingExtent = 1.9f, ceiling = 0.6f, strip = true)
        assertEquals(2f, strip.extentY, 0.000001f)
        assertEquals(1f, strip.centerY, 0.000001f)
    }

    @Test
    fun `in a strip the fit never passes the disc the arms can reach, nor stops below the hinge`() {
        val past = pendulumSubject(pivotHeight = 1.4f, swingExtent = 1.9f, ceiling = 3f, strip = true)
        assertEquals(1.4f + 0.95f, past.extentY, 0.000001f)
        val below = pendulumSubject(pivotHeight = 1.4f, swingExtent = 1.9f, ceiling = -0.4f, strip = true)
        assertEquals(1.4f, below.extentY, 0.000001f)
    }

    @Test
    fun `in a strip arms that hang below the floor are fitted down to their lowest point`() {
        // A hinge lower than the arms are long: the foot of the fit is the bottom of the swing.
        val strip = pendulumSubject(pivotHeight = 0.5f, swingExtent = 1.9f, ceiling = 0.2f, strip = true)
        assertEquals(0.2f + 0.95f, strip.extentY, 0.000001f)
        assertEquals(0.5f + (0.2f - 0.95f) / 2f, strip.centerY, 0.000001f)
    }

    @Test
    fun `a sheet taller than half the window covers half of it until it is dragged up`() {
        // The Lighting sheet on a phone held sideways: it measures the whole window.
        assertEquals(205.5.dp, settledSheetCover(measured = 411.dp, windowHeight = 411.dp, expanded = false))
        assertEquals(411.dp, settledSheetCover(measured = 411.dp, windowHeight = 411.dp, expanded = true))
        // Upright it is shorter than half the window and sits fully on screen either way.
        assertEquals(330.dp, settledSheetCover(measured = 330.dp, windowHeight = 914.dp, expanded = false))
        assertEquals(330.dp, settledSheetCover(measured = 330.dp, windowHeight = 914.dp, expanded = true))
        // A window not measured yet takes nothing away.
        assertEquals(330.dp, settledSheetCover(measured = 330.dp, windowHeight = 0.dp, expanded = false))
    }
}
