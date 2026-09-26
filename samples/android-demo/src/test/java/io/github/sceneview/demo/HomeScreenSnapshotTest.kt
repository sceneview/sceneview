package io.github.sceneview.demo

import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import com.dropbox.differ.SimpleImageComparator
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.sceneview.demo.theme.SceneViewDemoTheme
import io.github.sceneview.demo.ui.home.HomeScreen
import org.junit.Assert.assertTrue
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
 * That last clause was an intention, not a property, until #3666: the freshness
 * chips ("New" / "Updated") and the "What's new in 4.x" featured page are
 * measured against the *build's* `VERSION_NAME`, so the version bump in every
 * release commit repainted the grid and failed all four goldens on the release
 * PR. [PINNED_BUILD_VERSION] closes that: the tests render at a version derived
 * from the demo declarations instead, so `gradle.properties` no longer reaches
 * these images at all.
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

    @Test
    fun the_pinned_version_still_badges_something_and_not_the_whole_grid() {
        // These four images are the only place the freshness chips and the
        // "What's new in 4.x" featured page are pinned at all. A
        // [PINNED_BUILD_VERSION] that resolves to a version badging *nothing*
        // leaves the goldens passing while covering none of it — the one failure
        // a snapshot suite cannot report on itself, since an image with no chips
        // is a perfectly valid image. Badging *everything* is the same blind spot
        // from the other side.
        val marked = freshDemos(ALL_DEMOS, PINNED_BUILD_VERSION)
        assertTrue(
            "no demo is fresh at $PINNED_BUILD_VERSION — the home goldens no longer " +
                "cover the freshness chips or the \"What's new\" featured page",
            marked.isNotEmpty(),
        )
        assertTrue(
            "${marked.size} of ${ALL_DEMOS.size} demos are fresh at $PINNED_BUILD_VERSION — " +
                "the goldens pin a grid that no release ever looks like",
            marked.size * 3 <= ALL_DEMOS.size,
        )
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
                    buildVersion = PINNED_BUILD_VERSION,
                )
            }
        }
    }

    private companion object {
        /**
         * The version these goldens are rendered at: the newest version any demo
         * declares, never `BuildConfig.VERSION_NAME` (#3666).
         *
         * **Why not a literal.** A hard-coded `"4.37.0"` would fix the release
         * break and then rot in the other direction: `isRecentVersion` treats a
         * version declared *ahead* of the build as the freshest case, so every
         * demo added after the literal would badge itself "New" for good, and the
         * goldens would drift into a wall of chips that no user ever sees.
         *
         * **Why this.** Rendering at the newest declared version pins the grid
         * exactly as it looks in the release that last touched a demo — badges on
         * that release's demos and on the one before it, nothing else. It is a
         * pure function of the `sinceVersion` / `updatedIn` fields, so the only
         * PR that can move these goldens is a PR that edits a demo fragment,
         * which is a PR whose author is already looking at the Showcase. A
         * release commit moves `VERSION_NAME` and nothing here.
         *
         * `isRecentVersion(a, b, window = 0)` is "a is at least b" at the
         * major.minor granularity the badges use, which is the only comparison
         * needed here and saves the test its own semver parser.
         */
        val PINNED_BUILD_VERSION: String =
            ALL_DEMOS.flatMap { listOfNotNull(it.sinceVersion, it.updatedIn) }
                .reduceOrNull { newest, candidate ->
                    if (isRecentVersion(candidate, newest, window = 0)) candidate else newest
                }
                // No demo declares a version: every card is `None` whatever we
                // pass, so this only has to be a parseable constant — and a
                // constant is the point, `VERSION_NAME` would put the coupling
                // straight back.
                ?: "0.0.0"

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
