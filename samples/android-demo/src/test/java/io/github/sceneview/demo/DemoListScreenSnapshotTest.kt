package io.github.sceneview.demo

import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import com.dropbox.differ.SimpleImageComparator
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.sceneview.demo.theme.SceneViewDemoTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Roborazzi snapshot tests for [DemoListScreen] — the Samples tab grid.
 *
 * These pin the four `demo_list_*.png` goldens, which sat in
 * `src/test/snapshots/` from commit `425618a48` (2026-05-06) until this test
 * was written: no test ever referenced them, so `verifyRoborazziDebug`
 * compared 11 of the 15 committed goldens and silently ignored these four
 * ([#3031](https://github.com/sceneview/sceneview/issues/3031)). They read as
 * dark-mode / large-font / tablet coverage of the demo list that did not
 * exist.
 *
 * Each test runs with [LocalInspectionMode] forced on, which makes
 * [io.github.sceneview.demo.ui.ParticleBackground] short-circuit to a static
 * backdrop. Two reasons, both load-bearing:
 *  - the live backdrop calls `rememberEngine()`, which throws
 *    `UnsatisfiedLinkError: no filament-jni` on the JVM;
 *  - its particle field is seeded from an *unseeded* `Random`, so a
 *    pixel-exact golden could never match it twice.
 *
 * That makes these goldens a check on the grid chrome — card layout, category
 * headers, status chips, typography, the M3 colour scheme in both themes, and
 * reflow at `fontScale = 1.5` and on a tablet — not on the 3D backdrop.
 *
 * Re-record after a deliberate UI change:
 *   `./gradlew :samples:android-demo:recordRoborazziDebug --tests '*DemoListScreenSnapshotTest*'`
 *
 * Verify against goldens (every CI run):
 *   `./gradlew :samples:android-demo:verifyRoborazziDebug`
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DemoListScreenSnapshotTest {

    @Test
    fun demoList_lightMode() {
        captureRoboImage("src/test/snapshots/demo_list_light.png", roborazziOptions = HOST_TOLERANT) {
            SceneViewDemoTheme(darkTheme = false) {
                DemoList()
            }
        }
    }

    @Test
    // `night` precedes the density in the Android qualifier order; Robolectric
    // rejects the string outright if they are swapped.
    @Config(qualifiers = "w411dp-h891dp-night-xhdpi")
    fun demoList_darkMode() {
        // Pins the dark palette specifically: the cards' `outlineVariant`
        // hairline exists because `surfaceContainer` sits only a few luminance
        // steps above the backdrop in dark mode (#1443). Dropping the border
        // would show up here and nowhere else.
        //
        // The `-night` qualifier is load-bearing, not decoration.
        // `DemoListScreen` and `ParticleBackground` both branch on
        // `isSystemInDarkTheme()`, which reads the device configuration and
        // ignores the `darkTheme` argument passed to [SceneViewDemoTheme].
        // Without it this golden recorded dark cards on a *white* backdrop
        // with the light-mode category accents — a combination the app never
        // renders, pinned as if it were dark mode.
        captureRoboImage("src/test/snapshots/demo_list_dark.png", roborazziOptions = HOST_TOLERANT) {
            SceneViewDemoTheme(darkTheme = true) {
                DemoList()
            }
        }
    }

    @Test
    @Config(fontScale = 1.5f)
    fun demoList_largeFont() {
        // Accessibility reflow: the card is a fixed 168dp tall with a 1-line
        // title and 2-line subtitle, so an oversized font scale is exactly
        // where text starts getting clipped.
        captureRoboImage("src/test/snapshots/demo_list_large_font.png", roborazziOptions = HOST_TOLERANT) {
            SceneViewDemoTheme(darkTheme = false) {
                DemoList()
            }
        }
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun demoList_tablet() {
        // The grid is `GridCells.Fixed(2)`, so on a 1280dp-wide screen the
        // cards stretch rather than reflowing into more columns. Pinned so
        // that stays a deliberate choice instead of an accident.
        captureRoboImage("src/test/snapshots/demo_list_tablet.png", roborazziOptions = HOST_TOLERANT) {
            SceneViewDemoTheme(darkTheme = false) {
                DemoList()
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun DemoList() {
        CompositionLocalProvider(LocalInspectionMode provides true) {
            Surface { DemoListScreen(onDemoClick = {}, onAboutClick = {}) }
        }
    }

    private companion object {
        /**
         * Byte-exact comparison fails these four goldens across host OSes, and
         * the other 11 goldens in this module do not. The difference is
         * measurable and tiny: goldens recorded on macOS, verified on the Linux
         * CI runner, changed 0.06–0.67 % of pixels by **at most 2 of 255** per
         * channel (median 1, nothing above 32). It is confined to the cards'
         * `Brush.linearGradient` icon tiles — gradient rasterisation rounds
         * differently per host. The other goldens are flat `surfaceContainer`
         * control panels with no gradient, which is why they survive byte-exact
         * comparison and these cannot.
         *
         * The tolerance is therefore per-PIXEL, not a share-of-pixels
         * threshold: `changeThreshold = 0` still fails the test if *any single
         * pixel* moves further than [MAX_DISTANCE]. A share-of-pixels threshold
         * would have been the wrong lever — it would silently absorb a real,
         * small, localised regression (a clipped label, a dropped status chip)
         * for as long as it stayed under the percentage.
         *
         * [MAX_DISTANCE] is measured, not guessed. Running the real
         * `SimpleImageComparator` over the CI-rendered images against these
         * goldens, the cross-host drift disappears between 0.012 and 0.014 on
         * all four (0.010 still leaves 25–70 differing pixels). 0.02 keeps ~40 %
         * headroom over that cliff while staying ~29× below a real change: a
         * one-pixel text shift moves edge pixels by ~0.4 in this unit, dark
         * glyph on a light card. An 8000-pixel mutation of each golden still
         * reports 8000 differing pixels here — the tolerance absorbs host
         * rounding and nothing else.
         *
         * The goldens are deliberately kept as recorded on a **developer**
         * machine, matching the `recordRoborazziDebug` workflow in the KDoc
         * above. That way the Linux CI run exercises this tolerance on every
         * build: if the drift ever grows past [MAX_DISTANCE], CI says so.
         * Committing CI-recorded goldens instead would make CI byte-exact and
         * blind to exactly that.
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
