package io.github.sceneview.demo.demos.internal

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #4527 — on a phone held sideways the dollhouse's room is framed in the band at the start of
 * the window, clear of the identity row above it, the dock beside it and the chip in the end
 * corner.
 */
class DollhouseSideBandTest {

    // A Pixel 7a held sideways: 914 × 411 dp, status bar 24 dp, gesture bar 24 dp.
    private fun band(
        width: Dp = 914.dp,
        dock: Dp = 176.dp,
        startIsLeft: Boolean = true,
        card: Dp = 280.dp,
    ) = dollhouseSideBand(
        width = width,
        safeLeft = 0.dp,
        safeRight = 0.dp,
        safeTop = 24.dp,
        safeBottom = 24.dp,
        identityRow = 64.dp,
        bottomChromeWidth = dock,
        cornerCard = card,
        cornerMargin = 16.dp,
        startIsLeft = startIsLeft,
    )

    @Test
    fun `the band runs from the start edge to the dock's start edge`() {
        val p = band()
        assertEquals(0.dp, p.calculateLeftPadding(LayoutDirection.Ltr))
        // The dock is centred: its start edge is 457 - 88 = 369 dp from the left.
        assertEquals(914.dp - 369.dp, p.calculateRightPadding(LayoutDirection.Ltr))
    }

    @Test
    fun `the band starts under the identity row and runs down to the navigation bar`() {
        val p = band()
        assertEquals(88.dp, p.calculateTopPadding())
        // The dock is not in the band: only the system bar is left below.
        assertEquals(24.dp, p.calculateBottomPadding())
    }

    @Test
    fun `a corner card reaching further in than the dock ends the band first`() {
        // 640 dp wide, a 60 dp dock starting at 290, a 400 dp card starting at 224.
        val wide = band(width = 640.dp, dock = 60.dp, card = 400.dp)
        assertEquals(640.dp - 224.dp, wide.calculateRightPadding(LayoutDirection.Ltr))
        // A card as wide as the window leaves only the start edge.
        val narrow = band(width = 640.dp, dock = 120.dp, card = 600.dp)
        assertEquals(640.dp - 24.dp, narrow.calculateRightPadding(LayoutDirection.Ltr))
    }

    @Test
    fun `before the dock is measured the band runs to the window's centre`() {
        assertEquals(457.dp, band(dock = Dp.Unspecified).calculateRightPadding(LayoutDirection.Ltr))
    }

    @Test
    fun `right to left the band is on the right`() {
        val p = band(startIsLeft = false)
        assertEquals(0.dp, p.calculateRightPadding(LayoutDirection.Ltr))
        assertEquals(914.dp - 369.dp, p.calculateLeftPadding(LayoutDirection.Ltr))
    }

    @Test
    fun `the band is wider than it is tall, like a dollhouse seen from above`() {
        val p = band()
        val w = 914.dp - p.calculateLeftPadding(LayoutDirection.Ltr) - p.calculateRightPadding(LayoutDirection.Ltr)
        val h = 411.dp - p.calculateTopPadding() - p.calculateBottomPadding()
        assertTrue("band $w × $h", w > h)
    }
}
