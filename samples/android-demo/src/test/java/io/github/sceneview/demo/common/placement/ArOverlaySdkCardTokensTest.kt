package io.github.sceneview.demo.common.placement

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import io.github.sceneview.ar.ARCoreAvailability
import io.github.sceneview.ar.ARCoreAvailabilityOverlay
import io.github.sceneview.ar.ARCoreAvailabilityOverlayTags
import io.github.sceneview.ar.ARCoreAvailabilityState
import io.github.sceneview.demo.theme.SceneViewTokens
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * `SceneViewTokens.ArOverlay.sdkCardMargin` and `sdkCardActionInsets` are not spacings this
 * app chooses: they say how the **SDK's** AR card is built, because
 * [PlacementAvailabilityOverlay] needs those two numbers to place that card beside the chrome.
 * The SDK keeps them `internal`, so the app cannot read them — it measures them instead
 * (#4533). This is the one place the two are compared: change the card in `arsceneview` and
 * this fails until the tokens, and `DESIGN.md`, follow.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ArOverlaySdkCardTokensTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun theTokens_areWhatTheSdkCardMeasures() {
        composeRule.setContent {
            // Narrower than the card's widest, so its ground runs margin to margin.
            Box(Modifier.size(width = 300.dp, height = 600.dp).testTag(BOX)) {
                ARCoreAvailabilityOverlay(
                    ARCoreAvailabilityState(ARCoreAvailability.SessionFailed, retry = {}),
                )
            }
        }
        composeRule.waitForIdle()

        val box = bounds(BOX)
        val card = bounds(ARCoreAvailabilityOverlayTags.CARD)
        val action = bounds(ARCoreAvailabilityOverlayTags.ACTION)
        // The body wraps, so it is as wide as the card lets its text be.
        val body = composeRule
            .onNode(hasText(sdkString("sceneview_arcore_session_failed_body")))
            .getUnclippedBoundsInRoot()
        val label = composeRule
            .onNode(hasText(sdkString("sceneview_arcore_retry")), useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        val where = "box $box, card $card, body $body, action $action, label $label"

        val margin = card.left - box.left
        val cardPadding = body.left - card.left
        val actionPadding = label.left - action.left
        assertEquals(
            "sdkCardMargin — $where",
            SceneViewTokens.ArOverlay.sdkCardMargin.value,
            margin.value,
            TOLERANCE,
        )
        assertEquals(
            "the card's margin is not the same above it — $where",
            SceneViewTokens.ArOverlay.sdkCardMargin.value,
            (box.right - card.right).value,
            TOLERANCE,
        )
        assertEquals(
            "sdkCardActionInsets — margin $margin, card padding $cardPadding, " +
                "action padding $actionPadding; $where",
            SceneViewTokens.ArOverlay.sdkCardActionInsets.value,
            ((margin + cardPadding + actionPadding) * 2).value,
            TOLERANCE * 2,
        )
    }

    /** The SDK's strings are merged into this app's resources; its `R` is not on this classpath. */
    private fun sdkString(name: String): String {
        val context = RuntimeEnvironment.getApplication()
        val id = context.resources.getIdentifier(name, "string", context.packageName)
        check(id != 0) { "the SDK no longer has a string named $name" }
        return context.getString(id)
    }

    private fun bounds(tag: String): DpRect = composeRule.onNodeWithTag(tag).getUnclippedBoundsInRoot()

    private companion object {
        const val BOX = "box"
        const val TOLERANCE = 0.75f
    }
}
