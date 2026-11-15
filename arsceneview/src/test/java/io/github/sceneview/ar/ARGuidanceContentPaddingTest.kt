package io.github.sceneview.ar

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The SDK recovery cards must fit inside the band left by the host's chrome (#4525). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w800dp-h360dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ARGuidanceContentPaddingTest {
    @get:Rule(order = 0)
    val registerActivity = object : ExternalResource() {
        override fun before() {
            val context = RuntimeEnvironment.getApplication()
            shadowOf(context.packageManager).addActivityIfNotPresent(
                ComponentName(context.packageName, ComponentActivity::class.java.name),
            )
        }
    }

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

    @Test
    fun sessionFailure_clearsHostChrome_andRetainsItsRetryAction() = assertPaddedCard(permission = false)

    @Test
    fun permissionDenial_clearsHostChrome_andRetainsItsRequestAction() = assertPaddedCard(permission = true)

    @Test
    fun sessionFailure_resolvesStartAndEndPaddingInRtl() = assertPaddedCard(permission = false, rtl = true)

    @Test
    fun permissionDenial_resolvesStartAndEndPaddingInRtl() = assertPaddedCard(permission = true, rtl = true)

    @Test
    fun sessionFailure_lendsVerticalMarginsWhenHostPaddingLeavesOnlyAnActionBand() = assertShortBand(permission = false)

    @Test
    fun permissionDenial_lendsVerticalMarginsWhenHostPaddingLeavesOnlyAnActionBand() = assertShortBand(permission = true)

    private fun assertShortBand(permission: Boolean) {
        var clicks = 0
        composeRule.setContent {
            Box(Modifier.size(320.dp, 240.dp).testTag("host")) {
                val padding = PaddingValues(top = 80.dp, bottom = 90.dp)
                if (permission) {
                    ARCameraPermissionOverlay(
                        ARCameraPermissionState(false, request = { clicks++ }, openSettings = {}),
                        contentPadding = padding,
                    )
                } else {
                    ARCoreAvailabilityOverlay(
                        ARCoreAvailabilityState(ARCoreAvailability.SessionFailed, retry = { clicks++ }),
                        contentPadding = padding,
                    )
                }
            }
        }
        val cardTag = if (permission) ARCameraPermissionOverlayTags.CARD else ARCoreAvailabilityOverlayTags.CARD
        val actionTag = if (permission) ARCameraPermissionOverlayTags.ACTION else ARCoreAvailabilityOverlayTags.ACTION
        val host = composeRule.onNodeWithTag("host").getUnclippedBoundsInRoot()
        val card = composeRule.onNodeWithTag(cardTag).getUnclippedBoundsInRoot()
        val action = composeRule.onNodeWithTag(actionTag).getUnclippedBoundsInRoot()
        assertTrue("card $card leaves the host's free band $host", card.top >= host.top + 80.dp && card.bottom <= host.bottom - 90.dp)
        assertTrue("host insets squeeze the action to $action", action.bottom - action.top >= 40.dp)
        assertTrue("action $action leaves card $card", action.top >= card.top && action.bottom <= card.bottom)
        composeRule.onNodeWithTag(actionTag).performClick()
        assertEquals(1, clicks)
    }

    private fun assertPaddedCard(permission: Boolean, rtl: Boolean = false) {
        // A side panel as well as a bottom selector: test all edges, not just a vertical shift.
        val padding = PaddingValues(start = 300.dp, top = 32.dp, end = 16.dp, bottom = 180.dp)
        var clicks = 0
        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                Box(Modifier.fillMaxSize()) {
                    if (permission) {
                        ARCameraPermissionOverlay(
                            ARCameraPermissionState(false, request = { clicks++ }, openSettings = {}),
                            contentPadding = padding,
                        )
                    } else {
                        ARCoreAvailabilityOverlay(
                            ARCoreAvailabilityState(ARCoreAvailability.SessionFailed, retry = { clicks++ }),
                            contentPadding = padding,
                        )
                    }
                }
            }
        }
        val cardTag = if (permission) ARCameraPermissionOverlayTags.CARD else ARCoreAvailabilityOverlayTags.CARD
        val actionTag = if (permission) ARCameraPermissionOverlayTags.ACTION else ARCoreAvailabilityOverlayTags.ACTION
        val card = composeRule.onNodeWithTag(cardTag).getUnclippedBoundsInRoot()
        val action = composeRule.onNodeWithTag(actionTag).getUnclippedBoundsInRoot()
        val left = if (rtl) 16.dp else 300.dp
        val right = 800.dp - if (rtl) 300.dp else 16.dp
        assertTrue("card $card overlaps the host's side panel", card.left >= left && card.right <= right)
        assertTrue("card $card overlaps the host's top or bottom chrome", card.top >= 32.dp && card.bottom <= 180.dp)
        assertTrue("action $action is clipped by card $card", action.top >= card.top && action.bottom <= card.bottom)
        assertTrue("action $action lost its touch target", action.bottom - action.top >= 40.dp)
        composeRule.onNodeWithTag(actionTag).performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun changingHostChrome_repositionsTheExistingCard() {
        var padding by mutableStateOf(PaddingValues(0.dp))
        composeRule.setContent {
            Box(Modifier.fillMaxSize()) {
                ARCoreAvailabilityOverlay(
                    ARCoreAvailabilityState(ARCoreAvailability.SessionFailed, retry = {}),
                    contentPadding = padding,
                )
            }
        }
        val before = composeRule.onNodeWithTag(ARCoreAvailabilityOverlayTags.CARD).getUnclippedBoundsInRoot()
        composeRule.runOnIdle { padding = PaddingValues(bottom = 180.dp) }
        val after = composeRule.onNodeWithTag(ARCoreAvailabilityOverlayTags.CARD).getUnclippedBoundsInRoot()
        assertTrue("the card did not move when the host's selector appeared: $before -> $after", after.top < before.top)
        assertTrue("the card still overlaps the selector: $after", after.bottom <= 180.dp)
    }
}
