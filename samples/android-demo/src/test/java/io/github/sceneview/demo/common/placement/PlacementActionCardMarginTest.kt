package io.github.sceneview.demo.common.placement

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.sceneview.ar.PlacementSurface
import io.github.sceneview.demo.AssetSourceState
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.DockItem
import io.github.sceneview.demo.theme.SceneViewDemoTheme
import io.github.sceneview.demo.theme.SceneViewTokens
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/**
 * #4531: in Wall mode (and the room dollhouse, the pool, the Cloud Anchors and comparison
 * screens) the "No surface found" card is a child of the scaffold's `bottomOverlay` band,
 * which spans the whole scene — and it went edge to edge there, while the same card kept a
 * `space-md` margin on the Place screen, whose coach stack pads it. The card now keeps that
 * margin itself, so every host shows it at one distance from the screen edge.
 *
 * Composes the real [DemoScaffold] with the card in its bottom band, as `WallPlacementDemo`
 * does. Pure JVM — no ARCore session.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlacementActionCardMarginTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun noSurfaceCard_inTheScaffoldBand_keepsTheOverlayMarginOnBothSides() {
        showInBand(surface = PlacementSurface.WALL)
        assertSideMargins(SceneViewTokens.Space.md)
    }

    @Test
    fun noSurfaceCard_inTheScaffoldBand_dark_keepsTheSameMargin() {
        showInBand(surface = PlacementSurface.SURFACE, darkTheme = true)
        assertSideMargins(SceneViewTokens.Space.md)
    }

    @Test
    fun aHostThatAlreadyPadsTheCard_canTakeTheMarginOff() {
        // The Place coach stack pads its children by `space-md` and passes `0.dp`: the margin
        // is then the stack's alone, never twice.
        showInBand(surface = PlacementSurface.SURFACE, edgeMargin = 0.dp)
        assertSideMargins(0.dp)
    }

    private fun showInBand(
        surface: PlacementSurface,
        darkTheme: Boolean = false,
        edgeMargin: Dp = SceneViewTokens.Space.md,
    ) {
        composeRule.setContent {
            SceneViewDemoTheme(darkTheme = darkTheme) {
                DemoScaffold(
                    title = "Wall",
                    onBack = {},
                    assetSource = AssetSourceState.Bundled,
                    dock = listOf(DockItem(Icons.Outlined.WbSunny, "Reset", onClick = {})),
                    bottomOverlay = {
                        PlacementActionCard(
                            card = PlacementCard.NO_SURFACE,
                            onViewIn3D = {},
                            onKeepScanning = {},
                            onScanAgain = {},
                            onRestartSession = {},
                            surface = surface,
                            edgeMargin = edgeMargin,
                        )
                    },
                ) {
                    Box(Modifier.fillMaxSize().background(CAMERA_STAND_IN))
                }
            }
        }
        composeRule.waitForIdle()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
        composeRule.mainClock.advanceTimeBy(SETTLE_MILLIS)
        composeRule.waitForIdle()
    }

    private fun assertSideMargins(expected: Dp) {
        val root = composeRule.onRoot().getUnclippedBoundsInRoot()
        val card = composeRule.onNodeWithTag(PlacementTestTags.PLACEMENT_CARD).getUnclippedBoundsInRoot()
        assertEquals("left margin, card $card", expected.value, card.left.value, TOLERANCE_DP)
        assertEquals("right margin, card $card", expected.value, (root.right - card.right).value, TOLERANCE_DP)
    }

    private companion object {
        val CAMERA_STAND_IN = Color(0xFF6E6A63)
        const val SETTLE_MILLIS = 2_000L
        const val TOLERANCE_DP = 0.5f
    }
}
