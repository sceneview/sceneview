package io.github.sceneview.demo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the scaffold's snackbar goes relative to the settings sheet (#4019).
 *
 * "Demo reset" is raised from a row of that sheet. Placed above the dock whatever the sheet
 * did, it sat under the glass and read through it as a ghost across the controls.
 */
class DemoSnackbarPlacementTest {

    private fun top(sheetTop: Float) = demoSnackbarTop(
        windowHeight = WINDOW,
        snackbarHeight = SNACKBAR,
        dockBandTop = DOCK_BAND_TOP,
        sheetTop = sheetTop,
        identityRowBottom = IDENTITY_ROW_BOTTOM,
        gap = GAP,
    )

    @Test
    fun sheetClosed_orNotLaidOut_restsAGapAboveTheDockBand() {
        val resting = DOCK_BAND_TOP - GAP - SNACKBAR
        assertEquals(resting, top(sheetTop = Float.NaN))
        assertEquals(resting, top(sheetTop = WINDOW.toFloat()))
    }

    @Test
    fun sheetAtItsPeek_floatsAGapAboveTheSheet() {
        val sheetTop = WINDOW * 0.64f
        val y = top(sheetTop)
        assertEquals(sheetTop.toInt() - GAP - SNACKBAR, y)
        assertTrue("the snackbar ends at ${y + SNACKBAR}, under the sheet at $sheetTop", y + SNACKBAR < sheetTop)
    }

    @Test
    fun sheetLowerThanTheDock_onItsWayOut_neverPullsTheSnackbarDown() {
        // The last frames of a closing sheet: its edge is under the dock band.
        assertEquals(DOCK_BAND_TOP - GAP - SNACKBAR, top(sheetTop = WINDOW - 20f))
    }

    @Test
    fun sheetDraggedToTheTop_stopsUnderTheIdentityRow() {
        assertEquals(IDENTITY_ROW_BOTTOM, top(sheetTop = IDENTITY_ROW_BOTTOM - 40f))
    }

    private companion object {
        const val WINDOW = 2400
        const val SNACKBAR = 126
        const val DOCK_BAND_TOP = 2130
        const val IDENTITY_ROW_BOTTOM = 330
        const val GAP = 42
    }
}
