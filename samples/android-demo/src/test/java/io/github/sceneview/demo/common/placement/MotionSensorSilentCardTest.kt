package io.github.sceneview.demo.common.placement

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import io.github.sceneview.demo.AssetSourceState
import io.github.sceneview.demo.DEMO_MODE_PILL_TAG
import io.github.sceneview.demo.DemoMode
import io.github.sceneview.demo.DemoModeSwitch
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.DemoScaffoldTestTags
import io.github.sceneview.demo.DockItem
import io.github.sceneview.demo.LocalDemoModeSwitch
import io.github.sceneview.demo.R
import io.github.sceneview.demo.common.ForcedTrackingFailure
import io.github.sceneview.demo.theme.SceneViewDemoTheme
import io.github.sceneview.demo.theme.SceneViewTokens
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/**
 * The card an AR session shows once the phone's gyroscope is known to deliver nothing (#4522),
 * on the real screen: the real [DemoScaffold] inside AR Placement's mode switch, with the real
 * [TapToPlaceStatusOverlays] in its scene slot, in the state such a session is stuck in —
 * frames arriving, never tracking, phase `INITIALIZING`.
 *
 * What is held: the card replaces "Getting ready" rather than joining it, it carries no
 * button, and it stays clear of the chrome in portrait, sideways and at the largest text.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MotionSensorSilentCardTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val state = TapToPlaceState()

    @Before
    fun setUp() {
        ForcedTrackingFailure.override = null
        state.cameraReady = true
        state.isTracking = false
        state.trackingFailureReason = null
        state.phase = PlacementPhase.INITIALIZING
    }

    @After
    fun tearDown() {
        ForcedTrackingFailure.override = null
    }

    @Test
    fun withoutTheVerdict_theScreenSaysGettingReady_andShowsNoCard() {
        // The control: this is the screen the verdict replaces, and it must stay as it is.
        state.motionSensorSilent = false
        show()
        assertEquals(1, gettingReadyNodes())
        composeRule.onNodeWithTag(PlacementTestTags.PLACEMENT_CARD).assertDoesNotExist()
    }

    @Test
    fun portrait_light() = assertRestartCard()

    @Test
    fun portrait_dark() = assertRestartCard(darkTheme = true)

    @Test
    fun portrait_dark_atMaximumFontScale() =
        assertRestartCard(fontScale = 2.0f, darkTheme = true)

    @Test
    @Config(qualifiers = "w800dp-h360dp-land-xhdpi")
    fun phoneHeldSideways_light() {
        assertRestartCard()
        assertWholeMessageAboveTheDockScrim()
    }

    @Test
    @Config(qualifiers = "w800dp-h360dp-land-xhdpi")
    fun phoneHeldSideways_dark() {
        assertRestartCard(darkTheme = true)
        assertWholeMessageAboveTheDockScrim()
    }

    @Test
    @Config(qualifiers = "w800dp-h360dp-land-xhdpi")
    fun phoneHeldSideways_light_atMaximumFontScale() {
        assertRestartCard(fontScale = 2.0f)
        assertTheCutMessageScrollsToItsEnd()
    }

    @Test
    @Config(qualifiers = "w800dp-h360dp-land-xhdpi")
    fun phoneHeldSideways_dark_atMaximumFontScale() {
        assertRestartCard(fontScale = 2.0f, darkTheme = true)
        assertTheCutMessageScrollsToItsEnd()
    }

    /**
     * Sideways at the default text size the card has the corner beside the chrome: the whole
     * message is on screen, and none of it under the scrim the scaffold draws over the dock.
     */
    private fun assertWholeMessageAboveTheDockScrim() {
        val card = bounds(PlacementTestTags.PLACEMENT_CARD)
        val dock = bounds(DemoScaffoldTestTags.DOCK)
        val detail = detailNode().getUnclippedBoundsInRoot()
        detailNode().assertIsDisplayed()
        assertTrue(
            "the detail line runs out of the card — detail $detail, card $card",
            detail.top >= card.top && detail.bottom <= card.bottom,
        )
        assertTrue(
            "the card reaches into the dock band, where the scaffold's scrim dims it — card $card, dock $dock",
            card.bottom <= dock.top,
        )
    }

    /**
     * Sideways at the largest text the band above the chrome is shorter than the message: it
     * is cut, with a fading edge, and a drag brings its last line inside the card.
     */
    private fun assertTheCutMessageScrollsToItsEnd() {
        val card = bounds(PlacementTestTags.PLACEMENT_CARD)
        val before = detailNode().getUnclippedBoundsInRoot()
        assertTrue(
            "expected a message taller than the card here — detail $before, card $card",
            before.bottom > card.bottom,
        )

        // A finger put down on the words and dragged up, further than they can travel.
        composeRule.onNodeWithTag(PlacementTestTags.PLACEMENT_CARD).performTouchInput {
            swipeUp(startY = centerY, endY = centerY - height * 2)
        }
        composeRule.mainClock.advanceTimeBy(2_000)
        composeRule.waitForIdle()

        val after = detailNode().getUnclippedBoundsInRoot()
        assertTrue(
            "scrolling does not bring the last line of the message inside the card's padding — " +
                "detail $after, card $card",
            after.bottom <= card.bottom - CARD_PADDING + TOLERANCE,
        )
    }

    private fun detailNode() = composeRule.onNodeWithText(
        composeRule.activity.resources.getString(R.string.ar_place_motion_sensor_detail),
    )

    private fun assertRestartCard(fontScale: Float = 1.0f, darkTheme: Boolean = false) {
        state.motionSensorSilent = true
        show(fontScale, darkTheme)


        val resources = composeRule.activity.resources
        composeRule.onNodeWithText(resources.getString(R.string.ar_place_motion_sensor_title))
            .assertExists()
        composeRule.onNodeWithText(resources.getString(R.string.ar_place_motion_sensor_detail))
            .assertExists()
        assertEquals("\"Getting ready\" is still on screen next to the card", 0, gettingReadyNodes())
        // Nothing to press: no action of the app's brings a gyroscope back.
        composeRule.onNodeWithTag(PlacementTestTags.PLACEMENT_CARD_PRIMARY).assertDoesNotExist()
        composeRule.onNodeWithTag(PlacementTestTags.PLACEMENT_CARD_SECONDARY).assertDoesNotExist()

        composeRule.onNodeWithTag(PlacementTestTags.PLACEMENT_CARD).assertIsDisplayed()
        val window = composeRule.onRoot().getUnclippedBoundsInRoot()
        val card = bounds(PlacementTestTags.PLACEMENT_CARD)
        val pill = bounds(DEMO_MODE_PILL_TAG)
        val dock = bounds(DemoScaffoldTestTags.DOCK)
        val identity = bounds(DemoScaffoldTestTags.IDENTITY_ROW)
        val where = "card $card, mode pill $pill, dock $dock, identity row $identity, window $window"
        assertTrue("the card shares pixels with the mode pill — $where", !card.overlaps(pill))
        assertTrue("the card shares pixels with the dock — $where", !card.overlaps(dock))
        assertTrue("the card runs under the identity row — $where", !card.overlaps(identity))
        assertTrue(
            "the card leaves the window — $where",
            card.top >= window.top && card.bottom <= window.bottom &&
                card.left >= window.left && card.right <= window.right,
        )
    }

    /** The SDK's coaching card is one node whose description starts with its headline. */
    private fun gettingReadyNodes(): Int = composeRule
        .onAllNodes(hasContentDescription("Getting ready", substring = true))
        .fetchSemanticsNodes().size

    private fun show(fontScale: Float = 1.0f, darkTheme: Boolean = false) {
        composeRule.setContent {
            ScaledDensity(fontScale) {
                SceneViewDemoTheme(darkTheme = darkTheme) {
                    CompositionLocalProvider(
                        LocalDemoModeSwitch provides DemoModeSwitch(MODES, selected = 0, onSelect = {}),
                    ) {
                        // The chrome `ARPlacementDemo` declares: two dock items and the source pill.
                        DemoScaffold(
                            title = "AR Placement",
                            onBack = {},
                            assetSource = AssetSourceState.Bundled,
                            dock = listOf(
                                DockItem(Icons.Outlined.WbSunny, "Models", onClick = {}),
                                DockItem(Icons.Outlined.WbSunny, "Reset", onClick = {}),
                            ),
                        ) {
                            // A flat stand-in for the camera image the session keeps delivering.
                            Box(Modifier.fillMaxSize().background(CAMERA_STAND_IN))
                            TapToPlaceStatusOverlays(state = state, onViewIn3D = {}, onRestartSession = {})
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
        // Past the coaching card's 500 ms wait — counted on the system clock, which the compose
        // test clock does not move — and then past every enter transition.
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
        composeRule.mainClock.advanceTimeBy(2_000)
        composeRule.waitForIdle()
    }

    private fun bounds(tag: String): DpRect = composeRule
        .onNodeWithTag(tag)
        .getUnclippedBoundsInRoot()

    private fun DpRect.overlaps(other: DpRect): Boolean =
        left < other.right - TOLERANCE && other.left < right - TOLERANCE &&
            top < other.bottom - TOLERANCE && other.top < bottom - TOLERANCE

    @Composable
    private fun ScaledDensity(fontScale: Float, content: @Composable () -> Unit) {
        val density = LocalDensity.current
        CompositionLocalProvider(
            LocalDensity provides Density(density.density, fontScale),
            content = content,
        )
    }

    private companion object {
        val TOLERANCE = 0.75.dp

        /** The card's own padding, `space-md`: the text's viewport ends this far above its edge. */
        val CARD_PADDING = SceneViewTokens.Space.md
        val CAMERA_STAND_IN = Color(0xFF7C7F86)

        val MODES = listOf(
            DemoMode("place", R.string.demo_mode_place),
            DemoMode("free-pose", R.string.demo_mode_free_pose),
            DemoMode("one-call", R.string.demo_mode_one_call),
        )
    }
}
