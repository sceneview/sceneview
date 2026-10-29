package io.github.sceneview.ar

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
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

/**
 * [ARCameraPermissionOverlay] is public and is the first thing an AR screen shows to someone
 * who refused the camera: its one button is the only way forward. Like
 * [ARCoreAvailabilityOverlay] (see [ARCoreAvailabilityOverlayLayoutTest]) it has to hold in a
 * parent with no height limit and in a parent shorter than the card.
 *
 * ## The regression this pins (#4533)
 *
 * The card was a plain `Column`: in a box shorter than its text — a phone held sideways, a
 * large font — the text took the height and "Grant camera access" was measured with what was
 * left, down to nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ARCameraPermissionOverlayLayoutTest {

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

    private val askAgain = ARCameraPermissionState(permanentlyDenied = false, request = {}, openSettings = {})
    private val settings = ARCameraPermissionState(permanentlyDenied = true, request = {}, openSettings = {})

    @Test
    fun inAScrollingColumn_titleBodyAndActionAreAllDrawn() {
        composeRule.setContent {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                Box(Modifier.fillMaxWidth()) { ARCameraPermissionOverlay(askAgain) }
            }
        }
        assertWholeCard(R.string.sceneview_camera_permission_body)
    }

    @Test
    fun inALazyColumnItem_titleBodyAndActionAreAllDrawn() {
        composeRule.setContent {
            LazyColumn(Modifier.fillMaxWidth()) {
                item { Box(Modifier.fillMaxWidth()) { ARCameraPermissionOverlay(settings) } }
            }
        }
        assertWholeCard(R.string.sceneview_camera_permission_body_settings)
    }

    @Test
    fun inABoxShorterThanTheCard_theTextGivesWay_andTheActionKeepsItsHeight() {
        composeRule.setContent { ShortBox(160.dp) { ARCameraPermissionOverlay(askAgain) } }
        assertActionIsWholeInAShortBox(fontScale = 1.0f)
    }

    @Test
    fun inABoxShorterThanTheCard_openSettingsKeepsItsHeight() {
        composeRule.setContent { ShortBox(160.dp) { ARCameraPermissionOverlay(settings) } }
        assertActionIsWholeInAShortBox(fontScale = 1.0f)
    }

    @Test
    @Config(qualifiers = "w800dp-h360dp-land-xhdpi")
    fun phoneHeldSideways_atMaximumFontScale_theActionKeepsItsHeight() {
        // What is left of a 360 dp window once a status bar and a title row are taken off, at
        // the largest text Android's accessibility slider gives.
        composeRule.setContent {
            ScaledDensity(2.0f) { ShortBox(240.dp) { ARCameraPermissionOverlay(askAgain) } }
        }
        assertActionIsWholeInAShortBox(fontScale = 2.0f)
    }

    @Test
    fun inABoxShorterThanItsPaddingAndAction_theCardGivesUpItsPadding_notItsAction() {
        // 48 dp of margin and 52 dp for a card whose padding and one-line action ask for 80.
        composeRule.setContent { ShortBox(100.dp) { ARCameraPermissionOverlay(askAgain) } }
        assertActionIsWholeInAShortBox(fontScale = 1.0f)
    }

    @Test
    fun theCardTag_isOnTheGround_notOnTheTextInsideIt() {
        // A test that asks "does the card overlap the chrome" has to be answered with the
        // rectangle that is drawn. In a box narrower than the card's widest, the ground is the
        // box less the card's margin on each side.
        composeRule.setContent {
            Box(Modifier.size(width = 300.dp, height = 600.dp).testTag(SHORT_BOX)) {
                ARCameraPermissionOverlay(askAgain)
            }
        }
        composeRule.waitForIdle()
        val box = bounds(SHORT_BOX)
        val card = bounds(ARCameraPermissionOverlayTags.CARD)
        assertEquals("box $box, card $card", 24f, (card.left - box.left).value, 0.75f)
        assertEquals("box $box, card $card", 24f, (box.right - card.right).value, 0.75f)
    }

    private fun assertActionIsWholeInAShortBox(fontScale: Float) {
        composeRule.waitForIdle()
        val box = bounds(SHORT_BOX)
        val card = bounds(ARCameraPermissionOverlayTags.CARD)
        val action = bounds(ARCameraPermissionOverlayTags.ACTION)
        val where = "box $box, card $card, action $action"
        assertTrue("the card leaves its box — $where", card.top >= box.top && card.bottom <= box.bottom)
        val height = action.bottom - action.top
        // 24 dp of padding and one line of a 14 sp label.
        val whole = 24.dp + 16.dp * fontScale
        assertTrue("the action is squeezed to $height, under $whole — $where", height >= whole)
        assertTrue(
            "the action is not whole inside its card — $where",
            action.top >= card.top && action.bottom <= card.bottom,
        )
    }

    private fun assertWholeCard(bodyRes: Int) {
        composeRule.waitForIdle()
        val context = RuntimeEnvironment.getApplication()
        val title = composeRule
            .onNodeWithText(context.getString(R.string.sceneview_camera_permission_title))
            .getUnclippedBoundsInRoot()
        val body = composeRule.onNodeWithText(context.getString(bodyRes)).getUnclippedBoundsInRoot()
        val action = bounds(ARCameraPermissionOverlayTags.ACTION)
        val card = bounds(ARCameraPermissionOverlayTags.CARD)
        val where = "card $card, title $title, body $body, action $action"
        assertTrue("the title has no height — $where", title.bottom - title.top > 0.dp)
        assertTrue("the body has no height — $where", body.bottom - body.top > 0.dp)
        assertTrue("the action has no height — $where", action.bottom - action.top >= 40.dp)
        assertTrue("the body is drawn under the action — $where", body.bottom <= action.top)
    }

    @Composable
    private fun ShortBox(height: Dp, content: @Composable BoxScope.() -> Unit) {
        // Shorter than the card's text alone at this width.
        Box(Modifier.size(width = 320.dp, height = height).testTag(SHORT_BOX), content = content)
    }

    @Composable
    private fun ScaledDensity(fontScale: Float, content: @Composable () -> Unit) {
        val density = LocalDensity.current
        CompositionLocalProvider(
            LocalDensity provides Density(density.density, fontScale),
            content = content,
        )
    }

    private fun bounds(tag: String): DpRect = composeRule.onNodeWithTag(tag).getUnclippedBoundsInRoot()

    private companion object {
        const val SHORT_BOX = "short-box"
    }
}
