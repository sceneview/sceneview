package io.github.sceneview.demo.common.placement

import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.sceneview.demo.demoChromeBottomInset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two decisions that keep the placement screen's cards off the bottom chrome (#4509,
 * #4503), as plain arithmetic: how tall the scaffold says its chrome is
 * ([demoChromeBottomInset]) and where the stack goes relative to it ([coachStackSlot]).
 */
class CoachStackSlotTest {

    // ── demoChromeBottomInset ─────────────────────────────────────────────────────────

    @Test
    fun `with nothing above the dock, the chrome is the dock band`() {
        assertEquals(80.dp, chromeInset(overlayBand = 0.dp))
    }

    @Test
    fun `a mode pill above the dock raises the chrome to its top edge`() {
        // The band measures its content (a 48 dp pill), the 104 dp reserve under it and the
        // system bar. What the scene must clear is all of it but the bar.
        assertEquals(152.dp, chromeInset(overlayBand = 48.dp + 104.dp + NAV_BAR))
    }

    @Test
    fun `the chrome never reads lower than the dock`() {
        // The band's measurement lands a frame after the dock's floor: never report less.
        assertEquals(80.dp, chromeInset(overlayBand = 40.dp))
    }

    @Test
    fun `a scene already inset by the band only has the dock left over it`() {
        assertEquals(
            80.dp,
            chromeInset(overlayBand = 48.dp + 104.dp + NAV_BAR, sceneReserved = true),
        )
    }

    @Test
    fun `an empty band is not chrome - its dock reserve is not reported`() {
        // A `bottomOverlay` lambda that emits nothing still composes the band, which then
        // measures its 104 dp dock reserve and the system bar. Nothing is drawn there: the
        // scene keeps the 80 dp it has on a screen with no band at all.
        assertEquals(
            80.dp,
            chromeInset(overlayBand = 104.dp + NAV_BAR, overlayContent = 0.dp),
        )
    }

    @Test
    fun `a demo overlay with no mode pill raises the chrome to its own top edge`() {
        assertEquals(
            140.dp,
            chromeInset(overlayBand = 36.dp + 104.dp + NAV_BAR, overlayContent = 36.dp),
        )
    }

    // ── coachStackSlot ────────────────────────────────────────────────────────────────

    @Test
    fun `in portrait the stack sits one gutter above the chrome`() {
        val slot = slot(compactHeight = false, cardShown = true, bandWidth = 411.dp)
        assertFalse(slot.besideChrome)
        assertEquals(152.dp + GUTTER, slot.bottom)
    }

    @Test
    fun `a card on a phone held sideways moves beside the chrome`() {
        val slot = slot(compactHeight = true, cardShown = true, bandWidth = 800.dp)
        assertTrue(slot.besideChrome)
        // (800 - 300) / 2 = 250 on each side, less a gutter either side of the stack.
        assertEquals(218.dp, slot.width)
        assertEquals(GUTTER, slot.bottom)
    }

    @Test
    fun `the corner never makes the card wider than its own maximum`() {
        val slot = slot(compactHeight = true, cardShown = true, bandWidth = 1600.dp)
        assertTrue(slot.besideChrome)
        assertEquals(MAX_WIDTH, slot.width)
    }

    @Test
    fun `a coaching line alone stays above the chrome, sideways too`() {
        val slot = slot(compactHeight = true, cardShown = false, bandWidth = 800.dp)
        assertFalse(slot.besideChrome)
        assertEquals(152.dp + GUTTER, slot.bottom)
    }

    @Test
    fun `a corner too narrow for the card leaves the stack above the chrome`() {
        // (640 - 300) / 2 - 32 = 138, under the 192 dp minimum.
        val slot = slot(compactHeight = true, cardShown = true, bandWidth = 640.dp)
        assertFalse(slot.besideChrome)
    }

    @Test
    fun `chrome of unknown width leaves the stack above it`() {
        // A demo's own bottom overlay may span the window: there is no corner to count on.
        val slot = slot(
            compactHeight = true,
            cardShown = true,
            bandWidth = 800.dp,
            chromeWidth = Dp.Unspecified,
        )
        assertFalse(slot.besideChrome)
    }

    private fun chromeInset(
        overlayBand: Dp,
        sceneReserved: Boolean = false,
        // By default the band holds something whenever it measures anything.
        overlayContent: Dp = overlayBand,
    ): Dp =
        demoChromeBottomInset(
            dockBand = 80.dp,
            overlayBand = overlayBand,
            overlayContent = overlayContent,
            navigationBarInset = NAV_BAR,
            sceneReserved = sceneReserved,
        )

    private fun slot(
        compactHeight: Boolean,
        cardShown: Boolean,
        bandWidth: Dp,
        chromeWidth: Dp = 300.dp,
    ): CoachStackSlot = coachStackSlot(
        compactHeight = compactHeight,
        cardShown = cardShown,
        bandWidth = bandWidth,
        chromeWidth = chromeWidth,
        chromeBottom = 152.dp,
        gutter = GUTTER,
        minWidth = 192.dp,
        maxWidth = MAX_WIDTH,
    )

    private companion object {
        val NAV_BAR = 24.dp
        val GUTTER = 16.dp
        val MAX_WIDTH = 480.dp
    }

    // ── coachStackAlignment ───────────────────────────────────────────────────────────

    @Test
    fun `above the chrome every card is centred on it`() {
        val above = CoachStackSlot(besideChrome = false, width = Dp.Unspecified, bottom = 168.dp)
        PlacementCard.entries.forEach { assertEquals(Alignment.BottomCenter, coachStackAlignment(above, it)) }
        assertEquals(Alignment.BottomCenter, coachStackAlignment(above, null))
    }

    @Test
    fun `beside the chrome a card with buttons sits in the bottom corner`() {
        val beside = CoachStackSlot(besideChrome = true, width = 254.dp, bottom = 16.dp)
        assertEquals(Alignment.BottomEnd, coachStackAlignment(beside, PlacementCard.NO_SURFACE))
        assertEquals(Alignment.BottomEnd, coachStackAlignment(beside, PlacementCard.CAMERA_ERROR))
        assertEquals(Alignment.BottomEnd, coachStackAlignment(beside, PlacementCard.RECOVERY_FAILED))
    }

    @Test
    fun `beside the chrome the card with nothing to press stands above the dock's scrim`() {
        val beside = CoachStackSlot(besideChrome = true, width = 254.dp, bottom = 16.dp)
        assertEquals(Alignment.TopEnd, coachStackAlignment(beside, PlacementCard.MOTION_SENSOR_SILENT))
    }
}
