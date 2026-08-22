package io.github.sceneview.demo

import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import com.dropbox.differ.SimpleImageComparator
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.sceneview.demo.theme.SceneViewDemoTheme
import io.github.sceneview.demo.ui.home.HomeScreen
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Roborazzi snapshot tests for [HomeScreen] — the Showcase tab.
 *
 * Replaces `DemoListScreenSnapshotTest` and its `demo_list_*.png` goldens
 * (the category-grouped grid over a particle backdrop, both gone with the home
 * redesign). Same four variants: light, dark, large font, tablet.
 *
 * Each test runs with [LocalInspectionMode] forced on so the "What's new"
 * loader is skipped — the goldens pin the home chrome (pinned header, hero,
 * category chips, media cards in editorial order, status chips, the closing
 * "Browse online models" card, both palettes, `fontScale = 1.5` reflow and the
 * tablet `Adaptive(220.dp)` column count), not a release's version string.
 *
 * Re-record after a deliberate UI change:
 *   `./gradlew :samples:android-demo:recordRoborazziDebug --tests '*HomeScreenSnapshotTest*'`
 *
 * Verify against goldens (every CI run):
 *   `./gradlew :samples:android-demo:verifyRoborazziDebug`
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeScreenSnapshotTest {

    @Test
    fun home_lightMode() {
        captureRoboImage("src/test/snapshots/home_light.png", roborazziOptions = HOST_TOLERANT) {
            SceneViewDemoTheme(darkTheme = false) {
                Home()
            }
        }
    }

    @Test
    // `night` precedes the density in the Android qualifier order; Robolectric
    // rejects the string outright if they are swapped. The qualifier is
    // load-bearing: the chip, outline and icon-tile colours branch on
    // `isSystemInDarkTheme()`, which reads the device configuration and ignores
    // the `darkTheme` argument passed to [SceneViewDemoTheme].
    @Config(qualifiers = "w411dp-h891dp-night-xhdpi")
    fun home_darkMode() {
        captureRoboImage("src/test/snapshots/home_dark.png", roborazziOptions = HOST_TOLERANT) {
            SceneViewDemoTheme(darkTheme = true) {
                Home()
            }
        }
    }

    @Test
    @Config(fontScale = 1.5f)
    fun home_largeFont() {
        // Accessibility reflow: card title and subtitle are one line each with
        // ellipsis, the hero copy is capped at 2 lines / 260 dp — an oversized
        // font scale is exactly where clipping would show.
        captureRoboImage("src/test/snapshots/home_large_font.png", roborazziOptions = HOST_TOLERANT) {
            SceneViewDemoTheme(darkTheme = false) {
                Home()
            }
        }
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun home_tablet() {
        // `GridCells.Adaptive(220.dp)` above 600 dp: the cards reflow into more
        // columns and the hero grows to 400 dp. Pinned so that stays deliberate.
        captureRoboImage("src/test/snapshots/home_tablet.png", roborazziOptions = HOST_TOLERANT) {
            SceneViewDemoTheme(darkTheme = false) {
                Home()
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun Home() {
        CompositionLocalProvider(LocalInspectionMode provides true) {
            Surface {
                HomeScreen(
                    demos = ALL_DEMOS,
                    selectedCategory = null,
                    onCategoryChange = {},
                    query = "",
                    onQueryChange = {},
                    onDemoClick = {},
                    onBrowseOnlineClick = {},
                )
            }
        }
    }

    private companion object {
        /**
         * Per-pixel tolerance for cross-host gradient rasterisation (the hero
         * scrim and placeholder are gradients), inherited from the previous
         * `DemoListScreenSnapshotTest`: 0.02 absorbs the measured macOS → Linux
         * drift (≤ 2/255 per channel) and nothing else — `changeThreshold = 0`
         * still fails on a single pixel that moves further than this.
         */
        private const val MAX_DISTANCE = 0.02f

        val HOST_TOLERANT = RoborazziOptions(
            compareOptions = RoborazziOptions.CompareOptions(
                changeThreshold = 0f,
                imageComparator = SimpleImageComparator(maxDistance = MAX_DISTANCE),
            ),
        )
    }
}
