package io.github.sceneview.ar

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import org.robolectric.RuntimeEnvironment
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * [ARCoreAvailabilityOverlay] is public: a host may draw it anywhere, not only in the
 * full-screen box [ARSceneView] gives it. Its layout has to hold in both kinds of parent.
 *
 * - **A parent with no height limit** — a scrolling column, a `LazyColumn` item. A text block
 *   that takes "what the button leaves" of an unlimited height is given zero: the card would
 *   come up as a button with no explanation above it.
 * - **A parent shorter than the card** — a phone held sideways, a large font. The text gives
 *   way and scrolls; the button, the only way out, keeps its height (#4509).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ARCoreAvailabilityOverlayLayoutTest {

    /** `createComposeRule()` launches a bare `ComponentActivity`; the library declares none. */
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

    private val state = ARCoreAvailabilityState(ARCoreAvailability.SessionFailed, retry = {})

    @Test
    fun inAScrollingColumn_titleBodyAndActionAreAllDrawn() {
        composeRule.setContent {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                Box(Modifier.fillMaxWidth()) { ARCoreAvailabilityOverlay(state) }
            }
        }
        assertWholeCard()
    }

    @Test
    fun inALazyColumnItem_titleBodyAndActionAreAllDrawn() {
        composeRule.setContent {
            LazyColumn(Modifier.fillMaxWidth()) {
                item { Box(Modifier.fillMaxWidth()) { ARCoreAvailabilityOverlay(state) } }
            }
        }
        assertWholeCard()
    }

    @Test
    fun inABoxShorterThanTheCard_theTextGivesWay_andTheActionKeepsItsHeight() {
        composeRule.setContent { ShortBox(160.dp) { ARCoreAvailabilityOverlay(state) } }
        assertActionIsWholeInItsShortBox()
    }

    @Test
    fun inABoxShorterThanItsPaddingAndAction_theCardGivesUpItsPadding_notItsAction() {
        // 48 dp of margin and 52 dp for a card whose padding and one-line action ask for 80.
        composeRule.setContent { ShortBox(100.dp) { ARCoreAvailabilityOverlay(state) } }
        assertActionIsWholeInItsShortBox()
    }

    private fun assertActionIsWholeInItsShortBox() {
        composeRule.waitForIdle()

        val box = bounds(SHORT_BOX)
        val card = bounds(ARCoreAvailabilityOverlayTags.CARD)
        val action = bounds(ARCoreAvailabilityOverlayTags.ACTION)
        val where = "box $box, card $card, action $action"
        assertTrue("the card leaves its box — $where", card.top >= box.top && card.bottom <= box.bottom)
        assertTrue(
            "the action is squeezed to ${action.bottom - action.top} — $where",
            action.bottom - action.top >= 40.dp,
        )
        assertTrue(
            "the action is not whole inside its card — $where",
            action.top >= card.top && action.bottom <= card.bottom,
        )
    }

    @Composable
    private fun ShortBox(
        height: Dp,
        content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit,
    ) {
        // Shorter than the card's text alone at this width.
        Box(Modifier.size(width = 320.dp, height = height).testTag(SHORT_BOX), content = content)
    }

    private fun assertWholeCard() {
        composeRule.waitForIdle()
        val context = RuntimeEnvironment.getApplication()
        val title = composeRule
            .onNodeWithText(context.getString(state.availability.titleRes()))
            .getUnclippedBoundsInRoot()
        val body = composeRule
            .onNodeWithText(context.getString(state.availability.bodyRes()))
            .getUnclippedBoundsInRoot()
        val action = bounds(ARCoreAvailabilityOverlayTags.ACTION)
        val card = bounds(ARCoreAvailabilityOverlayTags.CARD)
        val where = "card $card, title $title, body $body, action $action"
        assertTrue("the title has no height — $where", title.bottom - title.top > 0.dp)
        assertTrue("the body has no height — $where", body.bottom - body.top > 0.dp)
        assertTrue("the action has no height — $where", action.bottom - action.top >= 40.dp)
        assertTrue("the body is drawn under the action — $where", body.bottom <= action.top)
    }

    private fun bounds(tag: String): DpRect = composeRule.onNodeWithTag(tag).getUnclippedBoundsInRoot()

    private companion object {
        const val SHORT_BOX = "short-box"
    }
}
