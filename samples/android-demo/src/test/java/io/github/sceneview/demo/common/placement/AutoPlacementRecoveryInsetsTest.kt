package io.github.sceneview.demo.common.placement

import android.view.View
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import io.github.sceneview.ar.ARCameraPermissionOverlay
import io.github.sceneview.ar.ARCameraPermissionOverlayTags
import io.github.sceneview.ar.ARCameraPermissionRecovery
import io.github.sceneview.ar.ARCameraPermissionState
import io.github.sceneview.ar.ARCoreAvailability
import io.github.sceneview.ar.ARCoreAvailabilityOverlay
import io.github.sceneview.ar.ARCoreAvailabilityOverlayTags
import io.github.sceneview.ar.ARCoreAvailabilityState
import io.github.sceneview.ar.PlacementPhase
import io.github.sceneview.demo.DEMO_MODE_PILL_TAG
import io.github.sceneview.demo.DemoModeSwitch
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.LocalDemoModeSwitch
import io.github.sceneview.demo.demos.autoPlacementGuidancePadding
import io.github.sceneview.demo.demos.autoPlacementRecoveryCardShown
import io.github.sceneview.demo.fragments.ArPlacementFragment
import io.github.sceneview.demo.theme.SceneViewDemoTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Review regressions: use the production recovery predicate, actual system insets and font scale. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w800dp-h360dp-land-notnight-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AutoPlacementRecoveryInsetsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun deniedCameraBeforeSession_usesRecoveryLayout() = verify(permission = true)

    @Test
    fun deniedCameraBeforeSession_withGestureBars_keepsAllowCameraWhole() = verify(permission = true, bottomBar = 24)

    @Test
    @Config(qualifiers = "w640dp-h360dp-land-notnight-xhdpi")
    fun narrowLandscape_keepsRetryWhole() = verify()

    @Test
    @Config(qualifiers = "w640dp-h360dp-land-notnight-xhdpi")
    fun narrowLandscape_atMaximumFont_keepsRetryWhole() = verify(fontScale = 2f)

    @Test
    @Config(qualifiers = "w640dp-h360dp-land-notnight-xhdpi")
    fun narrowLandscape_atMaximumFont_withGestureBars_keepsRetryWhole() = verify(fontScale = 2f, bottomBar = 24)

    @Test
    @Config(qualifiers = "w640dp-h360dp-land-notnight-xhdpi")
    fun narrowLandscape_atMaximumFont_withGestureBars_keepsBothPermissionActionsReachable() = verify(
        permission = true,
        fontScale = 2f,
        bottomBar = 24,
        permissionRecovery = ARCameraPermissionRecovery.RequestWithSettings,
    )

    @Test
    @Config(qualifiers = "w640dp-h360dp-land-notnight-xhdpi")
    fun narrowLandscape_withSideNavigation_keepsExplanationVisible() = verify(rightBar = 48)

    @Test
    @Config(qualifiers = "w640dp-h360dp-land-notnight-xhdpi")
    fun narrowLandscape_atLargeFont_withSideNavigation_usesSafeWidth() = verify(fontScale = 1.3f, rightBar = 48)

    @Test
    @Config(qualifiers = "w580dp-h360dp-land-notnight-xhdpi")
    fun smallLandscape_withSideNavigation_usesSafeWidth() = verify(rightBar = 48)

    private fun verify(
        permission: Boolean = false,
        fontScale: Float = 1f,
        bottomBar: Int = 0,
        rightBar: Int = 0,
        permissionRecovery: ARCameraPermissionRecovery = ARCameraPermissionRecovery.Request,
    ) {
        var host: View? = null
        var density = 1f
        var clicks = 0
        var settingsOpened = 0
        composeRule.setContent {
            host = LocalView.current
            density = LocalDensity.current.density
            SceneViewDemoTheme(darkTheme = false) {
                CompositionLocalProvider(
                    LocalDensity provides Density(density, fontScale),
                    LocalDemoModeSwitch provides DemoModeSwitch(ArPlacementFragment.modes, selected = 2, onSelect = {}),
                ) {
                    DemoScaffold(title = "One call", onBack = {}, controls = { Text("Reset placement") }) {
                        BoxWithConstraints(Modifier.fillMaxSize()) {
                            val recovery = autoPlacementRecoveryCardShown(
                                cameraDenied = permission,
                                availability = if (permission) null else ARCoreAvailability.SessionFailed,
                                phase = PlacementPhase.INITIALIZING,
                            )
                            val padding = autoPlacementGuidancePadding(maxWidth, recovery)
                            val modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing)
                            if (permission) {
                                ARCameraPermissionOverlay(
                                    ARCameraPermissionState(
                                        recovery = permissionRecovery,
                                        request = { clicks++ },
                                        openSettings = { settingsOpened++ },
                                    ),
                                    modifier = modifier,
                                    contentPadding = padding,
                                )
                            } else {
                                ARCoreAvailabilityOverlay(
                                    ARCoreAvailabilityState(ARCoreAvailability.SessionFailed, retry = { clicks++ }),
                                    modifier = modifier,
                                    contentPadding = padding,
                                )
                            }
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
        val view = checkNotNull(host)
        val physicalDensity = view.resources.displayMetrics.density
        fun px(dp: Int) = (dp * physicalDensity).toInt()
        val topBar = if (bottomBar != 0 || rightBar != 0) 24 else 0
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, px(topBar), 0, 0))
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, px(rightBar), px(bottomBar)))
            .build()
        composeRule.runOnUiThread { ViewCompat.dispatchApplyWindowInsets(view, insets) }
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.waitForIdle()

        val cardTag = if (permission) ARCameraPermissionOverlayTags.CARD else ARCoreAvailabilityOverlayTags.CARD
        val actionTag = if (permission) ARCameraPermissionOverlayTags.ACTION else ARCoreAvailabilityOverlayTags.ACTION
        val card = bounds(cardTag)
        val actionNode = composeRule.onNodeWithTag(actionTag)
        val action = actionNode.getUnclippedBoundsInRoot()
        val visibleAction = actionNode.fetchSemanticsNode().boundsInRoot
        val selector = bounds(DEMO_MODE_PILL_TAG)
        val where = "card=$card action=$action selector=$selector font=$fontScale bottom=$bottomBar right=$rightBar"
        assertTrue("button label is squeezed: $where", action.bottom - action.top >= 24.dp + 15.dp * fontScale)
        assertTrue("button is clipped: $where", visibleAction.height >= (action.bottom - action.top).value * density - 1f)
        assertTrue("button leaves its card: $where", action.top >= card.top && action.bottom <= card.bottom)
        assertTrue("card overlaps selector: $where", !card.overlaps(selector))
        assertTrue("card enters system bars: $where", card.top >= topBar.dp && card.right <= (view.width / density - rightBar).dp)
        // At 200% text the band may hold only the action. At normal/large text its title must fit too.
        if (fontScale < 2f) {
            val titleNode = composeRule.onNodeWithText(if (permission) "Camera access needed" else "Couldn't start AR")
            val title = titleNode.getUnclippedBoundsInRoot()
            val visibleTitle = titleNode.fetchSemanticsNode().boundsInRoot
            assertTrue("title is clipped: $where", visibleTitle.height >= (title.bottom - title.top).value * density - 1f)
            val bodyResource = if (permission) {
                io.github.sceneview.ar.R.string.sceneview_camera_permission_body
            } else {
                io.github.sceneview.ar.R.string.sceneview_arcore_session_failed_body
            }
            val bodyNode = composeRule.onNodeWithText(RuntimeEnvironment.getApplication().getString(bodyResource))
            bodyNode.performScrollTo()
            val visibleBody = bodyNode.fetchSemanticsNode().boundsInRoot
            assertTrue("explanation cannot be read even after scrolling: $where", visibleBody.height >= 15f * fontScale * density)
        }
        actionNode.performClick()
        if (permission && permissionRecovery == ARCameraPermissionRecovery.RequestWithSettings) {
            assertEquals("settings must remain the primary action", 1, settingsOpened)
            assertEquals(0, clicks)
            val requestNode = composeRule.onNodeWithTag(ARCameraPermissionOverlayTags.REQUEST_ACTION)
            val requestHeight = requestNode.getUnclippedBoundsInRoot().let { it.bottom - it.top }
            assertTrue("secondary label is squeezed: $where height=$requestHeight", requestHeight >= 24.dp + 15.dp * fontScale)
            // At 200% text the label can wrap: scroll its text into view even if the
            // button's padding makes the complete touch target taller than the band.
            val requestLabel = composeRule.onNodeWithText(
                RuntimeEnvironment.getApplication().getString(io.github.sceneview.ar.R.string.sceneview_camera_permission_grant),
                useUnmergedTree = true,
            )
            requestLabel.performScrollTo()
            val label = requestLabel.getUnclippedBoundsInRoot()
            val visibleLabel = requestLabel.fetchSemanticsNode().boundsInRoot
            val visibleRequest = requestNode.fetchSemanticsNode().boundsInRoot
            assertTrue("secondary label is clipped: $where label=$label", visibleLabel.height >= (label.bottom - label.top).value * density - 1f)
            assertTrue("secondary label leaves its card: $where label=$label", label.top >= card.top && label.bottom <= card.bottom)
            assertTrue("secondary touch target is too short: $where", visibleRequest.height >= 48f * density)
            requestNode.performClick()
            assertEquals("the request path must remain usable", 1, clicks)
            assertEquals(1, settingsOpened)
            // Users must also be able to return to the first action after reaching the second.
            actionNode.performScrollTo()
            assertTrue("settings action cannot be reached again", actionNode.fetchSemanticsNode().boundsInRoot.height >= visibleAction.height - 1f)
        } else {
            assertEquals("the recovery action must remain usable", 1, clicks)
        }
    }

    private fun bounds(tag: String) = composeRule.onNodeWithTag(tag).getUnclippedBoundsInRoot()

    private fun DpRect.overlaps(other: DpRect): Boolean =
        left < other.right - 0.75.dp && other.left < right - 0.75.dp &&
            top < other.bottom - 0.75.dp && other.top < bottom - 0.75.dp
}
