package io.github.sceneview.ar

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The order in which the SDK's AR card gives things up as its parent gets shorter (#4533):
 * text, then the gap above the action, then its padding — the action last.
 */
class AROverlayCardFitTest {

    private fun fit(maxHeight: Int, textHeight: Int) = fitAROverlayCard(
        maxHeight = maxHeight,
        textHeight = textHeight,
        actionHeight = ACTION,
        padding = PADDING,
        gap = GAP,
    )

    @Test
    fun withRoom_theCardIsItsPaddingTextGapAndAction() {
        val fit = fit(maxHeight = Int.MAX_VALUE, textHeight = 100)
        assertEquals(AROverlayCardFit(height = 40 + 100 + 16 + 64, textTop = 20, actionTop = 136), fit)
    }

    @Test
    fun withNoTextLeft_theGapAboveTheActionGoes_andTheActionIsCentredInThePadding() {
        // Exactly the room the padding, the gap and the action ask for: the text got none.
        val fit = fit(maxHeight = 120, textHeight = 0)
        assertEquals(AROverlayCardFit(height = 104, textTop = 20, actionTop = 20), fit)
    }

    @Test
    fun shorterThanPaddingAndAction_thePaddingGivesWay_onBothSides() {
        // 640 x 360 dp at font scale 2.0 with system bars: 72 for a 64 action.
        val fit = fit(maxHeight = 72, textHeight = 0)
        assertEquals(AROverlayCardFit(height = 72, textTop = 4, actionTop = 4), fit)
    }

    @Test
    fun shorterThanTheAction_nothingIsLeftToGive() {
        val fit = fit(maxHeight = 50, textHeight = 0)
        assertEquals(AROverlayCardFit(height = 64, textTop = 0, actionTop = 0), fit)
    }

    @Test
    fun aCardWithNoAction_isItsPaddingAndText() {
        val fit = fitAROverlayCard(maxHeight = 500, textHeight = 100, actionHeight = 0, padding = 40, gap = 0)
        assertEquals(AROverlayCardFit(height = 140, textTop = 20, actionTop = 120), fit)
    }

    private companion object {
        const val ACTION = 64
        const val PADDING = 40
        const val GAP = 16
    }
}
