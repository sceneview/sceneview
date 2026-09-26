package io.github.sceneview.demo.common.placement

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import io.github.sceneview.ar.PlacementSurface
import io.github.sceneview.demo.R
import io.github.sceneview.demo.theme.SceneViewDemoTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #3823: a plain, textureless wall gives ARCore's vertical-plane search too few feature
 * points to converge, so the "No surface found" card fires after the same 10 s timeout as
 * the floor flow — but "Try a brighter, textured area." says nothing a wall session can
 * act on. The card must show the wall-specific tip when it is raised from the wall flow,
 * and keep the original one everywhere else. Pure JVM — no ARCore session, no emulator.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlacementActionCardSurfaceTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun noSurfaceCard_onWall_showsWallSpecificDetail() {
        composeRule.setContent {
            SceneViewDemoTheme {
                PlacementActionCard(
                    card = PlacementCard.NO_SURFACE,
                    onViewIn3D = {},
                    onKeepScanning = {},
                    onScanAgain = {},
                    onRestartSession = {},
                    surface = PlacementSurface.WALL,
                )
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.ar_place_no_surface_detail_wall))
            .assertExists()
    }

    @Test
    fun noSurfaceCard_defaultSurface_showsGenericDetail() {
        composeRule.setContent {
            SceneViewDemoTheme {
                PlacementActionCard(
                    card = PlacementCard.NO_SURFACE,
                    onViewIn3D = {},
                    onKeepScanning = {},
                    onScanAgain = {},
                    onRestartSession = {},
                )
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.ar_place_no_surface_detail))
            .assertExists()
    }
}
