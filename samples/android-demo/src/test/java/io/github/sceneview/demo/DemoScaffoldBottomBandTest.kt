package io.github.sceneview.demo

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import io.github.sceneview.ar.ARCoachingOverlay
import io.github.sceneview.ar.ARCoreAvailability
import io.github.sceneview.ar.ARCoreAvailabilityOverlay
import io.github.sceneview.ar.ARCoreAvailabilityState
import io.github.sceneview.ar.ARCameraPermissionOverlay
import io.github.sceneview.ar.ARCameraPermissionState
import io.github.sceneview.ar.ArGuidanceCue
import io.github.sceneview.demo.common.placement.PlacementPhase
import io.github.sceneview.demo.common.placement.PlacementTestTags
import io.github.sceneview.demo.common.placement.TapToPlaceState
import io.github.sceneview.demo.common.placement.TapToPlaceStatusOverlays
import io.github.sceneview.demo.theme.SceneViewDemoTheme
import io.github.sceneview.demo.theme.SceneViewTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The `bottomOverlay` slot must sit **above the dock** at every font scale —
 * measured, not eyeballed.
 *
 * ## The regression this pins (#3229, carried over to the dock)
 *
 * [SETTINGS_FAB_RESERVED_SPACE] used to be a constant derived from a peek chip
 * measured at font scale 1.0 — and at 1.3 the chip outgrew it, putting two
 * demos' bottom overlays under the FAB. The chip is gone and the dock is a
 * bottom-centre floating toolbar, but the shape of the defect is the same: a
 * clearance that is predicted rather than measured fails only in the
 * configurations nobody screenshots. So the scaffold measures the dock band and
 * stacks the overlay above it, and these tests assert the *invariant* ("the
 * overlay's bottom edge never reaches the dock's top edge") on real bounds, not
 * the constant — a future dock redesign should keep them green.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DemoScaffoldBottomBandTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun bottomOverlay_clearsTheDock_atDefaultFontScale() {
        assertOverlayClearsDock(fontScale = 1.0f)
    }

    @Test
    fun bottomOverlay_clearsTheDock_atLargeFontScale() {
        // 1.3 is the scale device-QA found the historical overlap at. 1.0 passing
        // is not evidence for this one: the whole defect is that they disagree.
        assertOverlayClearsDock(fontScale = 1.3f)
    }

    @Test
    fun bottomOverlay_clearsTheDock_atMaximumFontScale() {
        // Android's accessibility slider goes to 2.0.
        assertOverlayClearsDock(fontScale = 2.0f)
    }

    @Test
    fun statusPill_stacksAboveTheDock_andKeepsUsableWidth() {
        // `peekHeader` is now a glass status pill at the top of the bottom band.
        // It must stack with the overlay above the dock, and a wordy header must
        // not starve the overlay beside it of width — the overlay still spans the
        // screen, because nothing sits in a bottom corner any more.
        val overlay = measureOverlay(
            fontScale = 1.3f,
            // `ar-measure`'s real first-launch header, verbatim — 38 characters.
            peekHeader = "Tap a surface to drop the first point",
        )
        val pill = composeRule.onNodeWithTag(DemoScaffoldTestTags.STATUS_PILL)
            .getUnclippedBoundsInRoot()
        val dock = composeRule.onNodeWithTag(DemoScaffoldTestTags.DOCK)
            .getUnclippedBoundsInRoot()

        assertTrue(
            "the status pill ends at ${pill.bottom} but the overlay probe starts at " +
                "${overlay.top} — the band is a Column, its children must stack.",
            pill.bottom <= overlay.top,
        )
        assertTrue(
            "the overlay probe ends at ${overlay.bottom} but the dock starts at ${dock.top}.",
            overlay.bottom <= dock.top,
        )
        val usable = overlay.right - overlay.left
        assertTrue(
            "the bottom overlay is only $usable wide on a $SCREEN_WIDTH screen; with the " +
                "dock centred below it, the overlay should span the full width.",
            usable > SCREEN_WIDTH / 2,
        )
    }

    @Test
    fun bareDemo_stillGetsTheDock_andTheOverlayClearsIt() {
        // Before #3328 a demo with no `controls`, no `dock` and no accent composed
        // no dock at all, and the overlay ran to the bottom of the scene. The
        // settings sheet is now the single settings surface — Reset, Send feedback
        // and QA mode live in it — so the dock always carries the Controls item
        // that opens it, on every demo. The invariant that replaces "no dock" is
        // therefore: the bare demo still has a dock, and the overlay still clears
        // it rather than running underneath.
        composeRule.setContent {
            ScaledDensity(1.0f) {
                SceneViewDemoTheme(darkTheme = false) {
                    DemoScaffold(
                        title = "Band",
                        onBack = {},
                        bottomOverlay = {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag(OVERLAY_PROBE),
                            )
                        },
                    ) {
                        Box(Modifier.fillMaxSize().testTag(SCENE_PROBE))
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(DemoScaffoldTestTags.SETTINGS_FAB)
            .assertIsDisplayed()
            .assertContentDescriptionEquals("Demo settings")
        val dock = composeRule.onNodeWithTag(DemoScaffoldTestTags.DOCK)
            .getUnclippedBoundsInRoot()
        val overlay = composeRule.onNodeWithTag(OVERLAY_PROBE).getUnclippedBoundsInRoot()
        val scene = composeRule.onNodeWithTag(SCENE_PROBE).getUnclippedBoundsInRoot()
        assertTrue(
            "a demo with no controls of its own still gets the dock, but its overlay ends " +
                "at ${overlay.bottom} while the dock starts at ${dock.top}.",
            overlay.bottom <= dock.top,
        )
        assertTrue(
            "the overlay ends at ${overlay.bottom}, ${dock.top - overlay.bottom} above the " +
                "dock at ${dock.top} on a scene ending at ${scene.bottom} — the reserve " +
                "should be the dock band and a gutter, not a second empty band.",
            dock.top - overlay.bottom < 48.dp,
        )
    }

    @Test
    fun dock_carriesTheControlsItem_withTheHistoricalTagAndLabel() {
        // `DemoInteractionTest` (androidTest) opens the controls sheet through
        // `By.res("demo-settings-fab")` or `By.desc("Demo settings")`. Both must
        // survive the move from a FAB to a dock item.
        measureOverlay(fontScale = 1.0f)
        composeRule.onNodeWithTag(DemoScaffoldTestTags.SETTINGS_FAB)
            .assertIsDisplayed()
            .assertContentDescriptionEquals("Demo settings")
    }

    @Test
    fun dock_labels_fitInsideTheGrowingBar_atMaximumFontScale() {
        composeRule.setContent {
            ScaledDensity(2.0f) {
                SceneViewDemoTheme(darkTheme = false) {
                    DemoScaffold(
                        title = "Viewer",
                        onBack = {},
                        dock = listOf(DockItem(Icons.Outlined.WbSunny, "Lighting", {})),
                    ) {}
                }
            }
        }
        composeRule.waitForIdle()

        val dock = composeRule.onNodeWithTag(DemoScaffoldTestTags.DOCK)
            .getUnclippedBoundsInRoot()
        listOf("Lighting", "Settings").forEach { label ->
            val text = composeRule.onNodeWithText(label, useUnmergedTree = true)
                .assertIsDisplayed()
                .getUnclippedBoundsInRoot()
            assertTrue(
                "$label is clipped at fontScale 2.0: its bounds $text must fit in dock $dock",
                text.top >= dock.top && text.bottom <= dock.bottom,
            )
        }
        assertTrue(
            "the 64 dp dock token is a minimum at large font scale, but measured $dock",
            dock.bottom - dock.top > 64.dp,
        )
    }

    // --- the snackbar, wired to the same band (#4483) ---

    @Test
    fun snackbar_overADemoWithNoBottomStack_restsOneGutterAboveTheDockBand() {
        // The placement the snackbar had before it knew about stacks (#3325): one gutter above
        // the dock band, whose top edge is the dock's.
        val snackbar = raiseSnackbarWithTheSheetClosed(bottomOverlay = null)
        val dock = composeRule.onNodeWithTag(DemoScaffoldTestTags.DOCK).getUnclippedBoundsInRoot()
        assertEquals(
            "the snackbar ends at ${snackbar.bottom} over a dock starting at ${dock.top}",
            SceneViewTokens.Space.md.value,
            (dock.top - snackbar.bottom).value,
            HALF_A_DP,
        )
    }

    @Test
    fun snackbar_overABottomOverlayThatDrawsNothing_isWhereItIsWithNoStack() {
        // Composed, empty: the band still measures its 104 dp dock reserve, 24 dp taller than
        // the dock band. That reserve is not a stack — for the scene's inset nor for this.
        val snackbar = raiseSnackbarWithTheSheetClosed(bottomOverlay = {})
        val dock = composeRule.onNodeWithTag(DemoScaffoldTestTags.DOCK).getUnclippedBoundsInRoot()
        assertEquals(
            "the snackbar ends at ${snackbar.bottom} over a dock starting at ${dock.top}",
            SceneViewTokens.Space.md.value,
            (dock.top - snackbar.bottom).value,
            HALF_A_DP,
        )
    }

    @Test
    fun snackbar_overABottomStack_restsOneGutterAboveIt() {
        val snackbar = raiseSnackbarWithTheSheetClosed(
            bottomOverlay = {
                Box(Modifier.fillMaxWidth().height(48.dp).testTag(OVERLAY_PROBE))
            },
        )
        val stack = composeRule.onNodeWithTag(OVERLAY_PROBE).getUnclippedBoundsInRoot()
        assertEquals(
            "the snackbar ends at ${snackbar.bottom} over a stack starting at ${stack.top}",
            SceneViewTokens.Space.md.value,
            (stack.top - snackbar.bottom).value,
            HALF_A_DP,
        )
    }

    @Test
    fun snackbar_overTheDemosOwnSceneCard_restsOneGutterAboveIt() {
        // What `TapToPlaceArSession` does with its decision card: anchored one gutter above
        // the chrome, in the scene slot, where the scaffold cannot measure it unless told.
        val snackbar = raiseSnackbarWithTheSheetClosed(
            bottomOverlay = {
                Box(Modifier.fillMaxWidth().height(48.dp).testTag(OVERLAY_PROBE))
            },
            scene = {
                Box(Modifier.fillMaxSize()) {
                    Box(
                        Modifier
                            .align(androidx.compose.ui.Alignment.BottomCenter)
                            .padding(bottom = LocalDemoChromeBottomInset.current + SceneViewTokens.Space.md)
                            .fillMaxWidth()
                            .height(120.dp)
                            .reportsDemoSceneBottomCard()
                            .testTag(SCENE_PROBE),
                    )
                }
            },
        )
        val card = composeRule.onNodeWithTag(SCENE_PROBE).getUnclippedBoundsInRoot()
        val stack = composeRule.onNodeWithTag(OVERLAY_PROBE).getUnclippedBoundsInRoot()
        assertTrue("the card at $card is not above the stack at $stack", card.bottom <= stack.top)
        assertEquals(
            "the snackbar ends at ${snackbar.bottom} over a card starting at ${card.top}",
            SceneViewTokens.Space.md.value,
            (card.top - snackbar.bottom).value,
            HALF_A_DP,
        )
    }

    @Test
    fun snackbar_waitsForTheSdkCoachingCard() {
        assertSnackbarWaitsForSdkOverlay { ARCoachingOverlay(cue = ArGuidanceCue.SCAN) }
    }

    @Test
    @Config(qualifiers = "w891dp-h411dp-xhdpi")
    fun snackbar_waitsForTheSdkAvailabilityCard_inLandscapeAtLargeFontScale() {
        assertSnackbarWaitsForSdkOverlay {
            ARCoreAvailabilityOverlay(ARCoreAvailabilityState(ARCoreAvailability.SessionFailed, {}))
        }
    }

    @Test
    fun snackbar_waitsForTheSdkPermissionCard() {
        assertSnackbarWaitsForSdkOverlay {
            ARCameraPermissionOverlay(ARCameraPermissionState(true, {}, {}))
        }
    }

    private fun assertSnackbarWaitsForSdkOverlay(overlay: @Composable BoxScope.() -> Unit) {
        val shown = mutableStateOf(true)
        showResetSnackbar(
            bottomOverlay = { Box(Modifier.fillMaxWidth().height(48.dp)) },
            scene = { Box(Modifier.fillMaxSize()) { if (shown.value) overlay() } },
            fontScale = 1.3f,
        )
        composeRule.onNodeWithTag(DemoScaffoldTestTags.SNACKBAR).assertDoesNotExist()
        // A queued message must survive longer than Material's normal short timeout.
        composeRule.mainClock.advanceTimeBy(5_000)
        composeRule.runOnIdle { shown.value = false }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Demo reset — ready to try again").assertIsDisplayed()
    }

    @Test
    fun snackbar_waitsUntilBothSdkCardsHaveLeft() {
        val permission = mutableStateOf(true)
        val availability = mutableStateOf(true)
        showResetSnackbar(bottomOverlay = null, scene = {
            Box(Modifier.fillMaxSize()) {
                if (permission.value) ARCameraPermissionOverlay(ARCameraPermissionState(true, {}, {}))
                if (availability.value) {
                    ARCoreAvailabilityOverlay(ARCoreAvailabilityState(ARCoreAvailability.SessionFailed, {}))
                }
            }
        })
        composeRule.onNodeWithTag(DemoScaffoldTestTags.SNACKBAR).assertDoesNotExist()
        composeRule.runOnIdle { permission.value = false }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(DemoScaffoldTestTags.SNACKBAR).assertDoesNotExist()
        composeRule.runOnIdle { availability.value = false }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Demo reset — ready to try again").assertIsDisplayed()
    }

    @Test
    fun snackbar_waitsThroughTheCoachingCardsExitFade_butNotForTheFoundPill() {
        val cue = mutableStateOf(ArGuidanceCue.SCAN)
        showResetSnackbar(bottomOverlay = null, scene = {
            Box(Modifier.fillMaxSize()) { ARCoachingOverlay(cue = cue.value) }
        })
        composeRule.mainClock.autoAdvance = false
        composeRule.runOnIdle { cue.value = ArGuidanceCue.SURFACE_FOUND }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onNodeWithTag(DemoScaffoldTestTags.SNACKBAR).assertDoesNotExist()
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.onNodeWithText("Demo reset — ready to try again").assertIsDisplayed()
    }

    @Test
    fun snackbar_aHandDraggedSheet_restoresTheStackRestingLineImmediately() {
        showResetSnackbar(
            bottomOverlay = { Box(Modifier.fillMaxWidth().height(360.dp).testTag(OVERLAY_PROBE)) },
            closeSheet = false,
        )
        val stack = composeRule.onNodeWithTag(OVERLAY_PROBE).getUnclippedBoundsInRoot()
        val sheet = composeRule.onNodeWithTag(DemoScaffoldTestTags.SETTINGS_SHEET)
        val openSheet = sheet.getUnclippedBoundsInRoot()
        val openSnackbar = composeRule.onNodeWithTag(DemoScaffoldTestTags.SNACKBAR).getUnclippedBoundsInRoot()
        assertTrue("the open sheet should be below the tall stack", openSheet.top > stack.top)
        assertTrue("settled open must release the stack resting line", openSnackbar.bottom > stack.top)
        composeRule.mainClock.autoAdvance = false
        sheet.performTouchInput { down(Offset(center.x, 8f)) }
        repeat(4) { step ->
            sheet.performTouchInput { moveBy(Offset(0f, 50f), delayMillis = 32) }
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.mainClock.advanceTimeByFrame()
            val draggedSheet = sheet.getUnclippedBoundsInRoot()
            val snackbar = composeRule.onNodeWithTag(DemoScaffoldTestTags.SNACKBAR).getUnclippedBoundsInRoot()
            assertTrue("step $step did not drag the sheet", draggedSheet.top > openSheet.top)
            assertTrue("step $step: snackbar $snackbar crosses returning stack $stack", snackbar.bottom <= stack.top)
        }
        sheet.performTouchInput { up() }
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        // This short drag returns to the same open detent. Its target need never change:
        // the offset alone must restore the stack clearance until the sheet settles again.
        val settledSheet = sheet.getUnclippedBoundsInRoot()
        val snackbar = composeRule.onNodeWithTag(DemoScaffoldTestTags.SNACKBAR).getUnclippedBoundsInRoot()
        assertEquals(openSheet.top.value, settledSheet.top.value, HALF_A_DP)
        assertEquals(SceneViewTokens.Space.md.value, (settledSheet.top - snackbar.bottom).value, HALF_A_DP)
    }

    @Test
    fun sceneCards_removingOneKeepsTheOtherRegistered() {
        val lowerShown = mutableStateOf(true)
        val upperShown = mutableStateOf(true)
        showResetSnackbar(bottomOverlay = null, scene = {
            Box(Modifier.fillMaxSize()) {
                key("upper") {
                    if (upperShown.value) {
                        Box(
                            Modifier.padding(top = 300.dp).height(100.dp)
                                .fillMaxWidth().reportsDemoSceneBottomCard().testTag(SCENE_PROBE),
                        )
                    }
                }
                key("lower") {
                    if (lowerShown.value) {
                        Box(
                            Modifier.padding(top = 500.dp).height(100.dp)
                                .fillMaxWidth().reportsDemoSceneBottomCard().testTag("lower-card"),
                        )
                    }
                }
            }
        })
        assertSnackbarAboveSceneProbe()
        composeRule.runOnIdle { lowerShown.value = false }
        composeRule.waitForIdle()
        assertSnackbarAboveSceneProbe()
        composeRule.runOnIdle {
            lowerShown.value = true
            upperShown.value = false
        }
        composeRule.waitForIdle()
        assertSnackbarAboveSceneProbe("lower-card")
    }

    private fun assertSnackbarAboveSceneProbe(tag: String = SCENE_PROBE) {
        val card = composeRule.onNodeWithTag(tag).getUnclippedBoundsInRoot()
        val snackbar = composeRule.onNodeWithTag(DemoScaffoldTestTags.SNACKBAR).getUnclippedBoundsInRoot()
        assertEquals(SceneViewTokens.Space.md.value, (card.top - snackbar.bottom).value, HALF_A_DP)
    }

    @Test
    @Config(qualifiers = "w891dp-h411dp-xhdpi")
    fun snackbar_besideTheRealLandscapeCornerCard_keepsItsStackRestingLine() {
        val state = TapToPlaceState().apply {
            cameraReady = true
            isTracking = true
            phase = PlacementPhase.NO_SURFACE
        }
        val snackbar = raiseSnackbarWithTheSheetClosed(
            bottomOverlay = null,
            modeSwitch = DemoModeSwitch(
                listOf(DemoMode("place", R.string.demo_mode_place), DemoMode("free-pose", R.string.demo_mode_free_pose)),
                selected = 0,
                onSelect = {},
            ),
            scene = {
                Box(Modifier.fillMaxSize()) {
                    TapToPlaceStatusOverlays(state, onViewIn3D = {}, onRestartSession = {})
                }
            },
        )
        val card = composeRule.onNodeWithTag(PlacementTestTags.PLACEMENT_CARD).getUnclippedBoundsInRoot()
        val dock = composeRule.onNodeWithTag(DemoScaffoldTestTags.DOCK).getUnclippedBoundsInRoot()
        val pill = composeRule.onNodeWithTag(DEMO_MODE_PILL_TAG).getUnclippedBoundsInRoot()
        assertTrue("expected corner card $card beside pill $pill and dock $dock", card.left >= pill.right)
        assertEquals(SceneViewTokens.Space.md.value, (pill.top - snackbar.bottom).value, HALF_A_DP)
    }

    /**
     * Raises "Demo reset" the only way the app can — from its row in the settings sheet —
     * closes the sheet, and returns where the snackbar's layout box came to rest. Material
     * draws the container 12 dp inside that box, over a stack as over the dock.
     */
    private fun raiseSnackbarWithTheSheetClosed(
        bottomOverlay: (@Composable DemoBottomOverlayScope.() -> Unit)?,
        scene: @Composable () -> Unit = {},
        modeSwitch: DemoModeSwitch? = null,
    ): DpRect {
        showResetSnackbar(bottomOverlay, scene, modeSwitch = modeSwitch)
        composeRule.onNodeWithText("Demo reset — ready to try again").assertIsDisplayed()
        return composeRule.onNodeWithTag(DemoScaffoldTestTags.SNACKBAR).getUnclippedBoundsInRoot()
    }

    private fun showResetSnackbar(
        bottomOverlay: (@Composable DemoBottomOverlayScope.() -> Unit)?,
        scene: @Composable () -> Unit = {},
        fontScale: Float = 1f,
        closeSheet: Boolean = true,
        modeSwitch: DemoModeSwitch? = null,
    ) {
        composeRule.setContent {
            ScaledDensity(fontScale) {
                SceneViewDemoTheme(darkTheme = false) {
                    DemoScaffold(
                        title = "Band",
                        onBack = {},
                        onReset = {},
                        bottomOverlay = bottomOverlay,
                        modeSwitch = modeSwitch,
                    ) { scene() }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(DemoScaffoldTestTags.SETTINGS_FAB).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(DemoScaffoldTestTags.RESET_ACTION).performClick()
        if (closeSheet) composeRule.onNodeWithTag(DemoScaffoldTestTags.SETTINGS_CLOSE).performClick()
        composeRule.waitForIdle()
    }

    private fun assertOverlayClearsDock(fontScale: Float) {
        val overlay = measureOverlay(fontScale)
        val dock = composeRule
            .onNodeWithTag(DemoScaffoldTestTags.DOCK)
            .getUnclippedBoundsInRoot()

        assertTrue(
            "at fontScale $fontScale the bottom overlay ends at ${overlay.bottom} but the " +
                "dock starts at ${dock.top} — they overlap by ${overlay.bottom - dock.top}. " +
                "The reserve is measured from the real dock band; if this fails, something " +
                "stopped feeding that measurement into the bottomOverlay slot.",
            overlay.bottom <= dock.top,
        )
    }

    private fun measureOverlay(fontScale: Float, peekHeader: String? = null): DpRect {
        composeRule.setContent {
            ScaledDensity(fontScale) {
                SceneViewDemoTheme(darkTheme = false) {
                    DemoScaffold(
                        title = "Band",
                        onBack = {},
                        peekHeader = peekHeader,
                        // Non-null `controls` is what makes the dock — and
                        // therefore the reserve — exist at all.
                        controls = { Text("a setting") },
                        bottomOverlay = {
                            // The documented full-width idiom, verbatim. The
                            // end inset is now a no-op; the clearance is vertical.
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(end = settingsFabReservedSpace)
                                    .height(48.dp)
                                    .testTag(OVERLAY_PROBE),
                            )
                        },
                    ) {}
                }
            }
        }
        composeRule.waitForIdle()
        return composeRule.onNodeWithTag(OVERLAY_PROBE).getUnclippedBoundsInRoot()
    }

    @Composable
    private fun ScaledDensity(fontScale: Float, content: @Composable () -> Unit) {
        val density = LocalDensity.current
        CompositionLocalProvider(
            LocalDensity provides Density(density.density, fontScale),
            content = content,
        )
    }

    private companion object {
        const val OVERLAY_PROBE = "bottom-overlay-probe"
        const val SCENE_PROBE = "scene-probe"

        /** Rounding of a dp measured through pixels at this density. */
        const val HALF_A_DP = 0.5f

        /** Matches the `w411dp` Robolectric qualifier on this class. */
        val SCREEN_WIDTH = 411.dp
    }
}
