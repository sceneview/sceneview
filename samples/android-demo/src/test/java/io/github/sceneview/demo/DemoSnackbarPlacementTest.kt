package io.github.sceneview.demo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the scaffold's snackbar goes relative to the settings sheet (#4019) and to what a demo
 * stacks above the dock (#4483).
 *
 * "Demo reset" is raised from a row of that sheet. Placed above the dock whatever the sheet
 * did, it sat under the glass and read through it as a ghost across the controls. Placed above
 * the dock whatever the demo stacked there, it lay across the demo's chips once the sheet closed.
 */
class DemoSnackbarPlacementTest {

    private fun top(
        sheetTop: Float = Float.NaN,
        bottomStackTop: Int = WINDOW,
        bottomStackVisibility: Float = 1f,
        sceneCardTop: Int = WINDOW,
    ) = demoSnackbarTop(
        windowHeight = WINDOW,
        snackbarHeight = SNACKBAR,
        dockBandTop = DOCK_BAND_TOP,
        bottomStackTop = bottomStackTop,
        bottomStackVisibility = bottomStackVisibility,
        sceneCardTop = sceneCardTop,
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

    // --- a demo's bottom stack (#4483) ---

    @Test
    fun bottomStack_sheetClosed_restsAGapAboveTheStack() {
        val y = top(bottomStackTop = STACK_TOP)
        assertEquals(STACK_TOP - GAP - SNACKBAR, y)
        assertTrue("the snackbar ends at ${y + SNACKBAR}, across the stack at $STACK_TOP", y + SNACKBAR < STACK_TOP)
    }

    @Test
    fun noBottomStack_isThePlacementItAlwaysWas_whateverTheStackFadeSays() {
        val resting = DOCK_BAND_TOP - GAP - SNACKBAR
        for (visibility in listOf(0f, 0.5f, 1f)) {
            assertEquals(resting, top(bottomStackTop = WINDOW, bottomStackVisibility = visibility))
        }
    }

    @Test
    fun bottomStack_endingUnderTheDockBand_liftsNothing() {
        assertEquals(DOCK_BAND_TOP - GAP - SNACKBAR, top(bottomStackTop = DOCK_BAND_TOP + 30))
    }

    @Test
    fun bottomStack_fadedOutUnderAnOpenSheet_isWhatTheSheetAloneGave() {
        val sheetTop = WINDOW * 0.64f
        assertEquals(
            top(sheetTop = sheetTop),
            top(sheetTop = sheetTop, bottomStackTop = STACK_TOP, bottomStackVisibility = 0f),
        )
        // A sheet whose peek stays lower than the stack: the snackbar sits on the sheet, over
        // the place the faded stack has left.
        val lowSheet = STACK_TOP + 200f
        assertEquals(
            lowSheet.toInt() - GAP - SNACKBAR,
            top(sheetTop = lowSheet, bottomStackTop = STACK_TOP, bottomStackVisibility = 0f),
        )
    }

    /**
     * Where the host's stack fade is heading for a sheet in this state: the value it snaps to
     * on the way up, and eases to on the way down ([demoBottomStackGone]).
     */
    private fun stackVisibility(
        sheetRequested: Boolean,
        sheetShown: Boolean,
        sheetSettled: Boolean,
        hidden: Boolean = false,
    ) = if (demoBottomStackGone(hidden, sheetRequested, sheetShown, sheetSettled)) 0f else 1f

    @Test
    fun sheetClosingUnderTheSnackbar_isOneTravelDown_endingAboveTheStack() {
        // The host puts the resting line back above the stack as soon as the sheet is on its
        // way out: the snackbar rides the sheet down and stops there. Closed from its row or
        // its close button, the request is withdrawn first; dragged down, the request stands
        // and the sheet is merely no longer settled.
        val open = WINDOW * 0.5f
        for (dragged in listOf(false, true)) {
            assertOneTravelDown(open) { last ->
                stackVisibility(
                    sheetRequested = dragged,
                    // `currentValue` flips to hidden when the sheet lands.
                    sheetShown = !last,
                    sheetSettled = last,
                )
            }
        }
    }

    private fun assertOneTravelDown(open: Float, visibility: (last: Boolean) -> Float) {
        // Settled open over a faded stack, a gap above the sheet.
        var previous = top(
            sheetTop = open,
            bottomStackTop = STACK_TOP,
            bottomStackVisibility = stackVisibility(sheetRequested = true, sheetShown = true, sheetSettled = true),
        )
        assertEquals(open.toInt() - GAP - SNACKBAR, previous)
        for (step in 1..20) {
            val y = top(
                sheetTop = open + (WINDOW - open) * step / 20f,
                bottomStackTop = STACK_TOP,
                bottomStackVisibility = visibility(step == 20),
            )
            assertTrue("step $step: $y is above the previous $previous", y >= previous)
            assertTrue("step $step: $y is across the stack", y + SNACKBAR < STACK_TOP)
            previous = y
        }
        assertEquals(STACK_TOP - GAP - SNACKBAR, previous)
    }

    @Test
    fun sheetOpeningUnderTheSnackbar_isOneTravelUp() {
        // The resting line stays above the stack until the sheet has settled: a sheet on its
        // way up only ever pushes the snackbar.
        val open = WINDOW * 0.5f
        var previous = top(
            sheetTop = WINDOW.toFloat(),
            bottomStackTop = STACK_TOP,
            bottomStackVisibility = stackVisibility(sheetRequested = false, sheetShown = false, sheetSettled = true),
        )
        assertEquals(STACK_TOP - GAP - SNACKBAR, previous)
        for (step in 1..20) {
            val y = top(
                sheetTop = WINDOW - (WINDOW - open) * step / 20f,
                bottomStackTop = STACK_TOP,
                // Requested, travelling: `currentValue` is still hidden, the target is not.
                bottomStackVisibility = stackVisibility(
                    sheetRequested = true,
                    sheetShown = false,
                    sheetSettled = false,
                ),
            )
            assertTrue("step $step: $y is under the previous $previous", y <= previous)
            previous = y
        }
        assertEquals(open.toInt() - GAP - SNACKBAR, previous)
        // Landed: the stack is gone, and the snackbar does not move for it.
        val settled = top(
            sheetTop = open,
            bottomStackTop = STACK_TOP,
            bottomStackVisibility = stackVisibility(sheetRequested = true, sheetShown = true, sheetSettled = true),
        )
        assertEquals(previous, settled)
    }

    // --- when the stack is out of the way ---

    @Test
    fun stackIsGone_onlyUnderASheetThatHasSettledOpen_orWhenTheCallerHidesIt() {
        assertEquals(1f, stackVisibility(sheetRequested = false, sheetShown = false, sheetSettled = true))
        // On its way up, by the Settings button.
        assertEquals(1f, stackVisibility(sheetRequested = true, sheetShown = false, sheetSettled = false))
        // Between two detents.
        assertEquals(1f, stackVisibility(sheetRequested = true, sheetShown = true, sheetSettled = false))
        assertEquals(0f, stackVisibility(sheetRequested = true, sheetShown = true, sheetSettled = true))
        // Closed from its own button: the request is withdrawn before the sheet has moved.
        assertEquals(1f, stackVisibility(sheetRequested = false, sheetShown = true, sheetSettled = true))
        // Requested, but nothing to show yet: the sheet rests hidden.
        assertEquals(1f, stackVisibility(sheetRequested = true, sheetShown = false, sheetSettled = true))
        // A demo's own sheet hides the dock and the stack with it (`dockHidden`).
        assertEquals(
            0f,
            stackVisibility(sheetRequested = false, sheetShown = false, sheetSettled = true, hidden = true),
        )
    }

    // --- the demo's own card over the scene (#4483, the cards of #4509) ---

    @Test
    fun sceneCard_aboveTheStack_getsTheSnackbarAGapAboveIt() {
        val y = top(bottomStackTop = STACK_TOP, sceneCardTop = CARD_TOP)
        assertEquals(CARD_TOP - GAP - SNACKBAR, y)
        assertTrue("the snackbar ends at ${y + SNACKBAR}, across the card at $CARD_TOP", y + SNACKBAR < CARD_TOP)
    }

    @Test
    fun sceneCard_overADockWithNoStack_getsTheSnackbarAGapAboveIt() {
        val cardTop = DOCK_BAND_TOP - GAP - 300
        assertEquals(cardTop - GAP - SNACKBAR, top(sceneCardTop = cardTop))
    }

    @Test
    fun sceneCard_staysClear_whenTheStackFadesOrASheetSettlesLowerThanIt() {
        // The card is the scene's, not the chrome's: it does not fade under the sheet.
        val lowSheet = CARD_TOP + 300f
        for (visibility in listOf(0f, 0.5f, 1f)) {
            assertEquals(
                CARD_TOP - GAP - SNACKBAR,
                top(
                    sheetTop = lowSheet,
                    bottomStackTop = STACK_TOP,
                    bottomStackVisibility = visibility,
                    sceneCardTop = CARD_TOP,
                ),
            )
        }
    }

    @Test
    fun sceneCard_underASheetThatReachesHigher_leavesTheSnackbarOnTheSheet() {
        val sheetTop = CARD_TOP - 300f
        assertEquals(
            sheetTop.toInt() - GAP - SNACKBAR,
            top(sheetTop = sheetTop, bottomStackTop = STACK_TOP, sceneCardTop = CARD_TOP),
        )
    }

    @Test
    fun sceneCard_tooTallForTheWindow_stopsUnderTheIdentityRow() {
        assertEquals(
            IDENTITY_ROW_BOTTOM,
            top(bottomStackTop = STACK_TOP, sceneCardTop = IDENTITY_ROW_BOTTOM + 60),
        )
    }

    @Test
    fun noSceneCard_isThePlacementTheStackAloneGave() {
        assertEquals(
            STACK_TOP - GAP - SNACKBAR,
            top(bottomStackTop = STACK_TOP, sceneCardTop = WINDOW),
        )
    }

    @Test
    fun sheetSettledLowerThanTheStack_letsTheSnackbarDownOntoIt_inOneTravel() {
        val lowSheet = STACK_TOP + 200f
        var previous = top(sheetTop = lowSheet, bottomStackTop = STACK_TOP)
        assertEquals(STACK_TOP - GAP - SNACKBAR, previous)
        for (step in 1..10) {
            val y = top(sheetTop = lowSheet, bottomStackTop = STACK_TOP, bottomStackVisibility = 1f - step / 10f)
            assertTrue("step $step: $y is above the previous $previous", y >= previous)
            previous = y
        }
        assertEquals(lowSheet.toInt() - GAP - SNACKBAR, previous)
    }

    @Test
    fun sheetHigherThanTheStack_stillWins() {
        val sheetTop = STACK_TOP - 400f
        assertEquals(
            sheetTop.toInt() - GAP - SNACKBAR,
            top(sheetTop = sheetTop, bottomStackTop = STACK_TOP),
        )
    }

    @Test
    fun bottomStack_tooTallForTheWindow_stopsUnderTheIdentityRow() {
        // No room between the identity row and the stack: the back button wins, the snackbar
        // lies across the top of the stack and stays fully on screen.
        val y = top(bottomStackTop = IDENTITY_ROW_BOTTOM + 60)
        assertEquals(IDENTITY_ROW_BOTTOM, y)
        assertTrue(y + SNACKBAR < WINDOW)
    }

    // --- what counts as a stack ---

    @Test
    fun stackTop_isTheBandsTopEdge() {
        assertEquals(WINDOW - 700, demoBottomStackTop(windowHeight = WINDOW, band = 700))
    }

    @Test
    fun stackTop_withNoBand_isNoStack() {
        // The scaffold hands a zero for a band that is not up or that draws nothing — the test
        // `demoChromeBottomInset` makes for the scene (`DemoScaffoldBottomBandTest`).
        assertEquals(WINDOW, demoBottomStackTop(windowHeight = WINDOW, band = 0))
    }

    private companion object {
        const val WINDOW = 2400
        const val SNACKBAR = 126
        const val DOCK_BAND_TOP = 2130
        const val IDENTITY_ROW_BOTTOM = 330
        const val GAP = 42
        const val STACK_TOP = 1700
        const val CARD_TOP = 1200
    }
}
