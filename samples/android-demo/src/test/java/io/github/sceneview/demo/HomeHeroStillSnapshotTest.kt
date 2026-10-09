package io.github.sceneview.demo

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.dp
import com.dropbox.differ.SimpleImageComparator
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.sceneview.demo.theme.SceneViewDemoTheme
import io.github.sceneview.demo.ui.home.HomeHeroStill
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What the home hero stage shows where the flight cannot run (#4411): the bundled picture.
 * Two stages, at the heights `HomeScreen` gives them (status bar + header + band + bleed):
 * the phone's shows it whole, the tablet's wide one crops it and must keep the sun, the
 * horizon and the helmet.
 *
 * The home goldens (`HomeScreenSnapshotTest`) do not cover it: they run in inspection mode,
 * where the stage paints the sky alone. The hero is the same in light and dark (DESIGN.md,
 * "the hero stays dark"), so one palette is enough here.
 *
 * Re-record after replacing `home_hero_still.webp`:
 *   `./gradlew :samples:android-demo:recordRoborazziDebug --tests '*HomeHeroStillSnapshotTest*'`
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeHeroStillSnapshotTest {

    @Test
    fun heroStill_phone() {
        captureRoboImage("src/test/snapshots/home_hero_still_phone.png", roborazziOptions = HOST_TOLERANT) {
            Stage(heightDp = 460)
        }
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-mdpi") // the crop is what matters here, not the pixels
    fun heroStill_tablet() {
        captureRoboImage("src/test/snapshots/home_hero_still_tablet.png", roborazziOptions = HOST_TOLERANT) {
            Stage(heightDp = 536)
        }
    }

    @androidx.compose.runtime.Composable
    private fun Stage(heightDp: Int) {
        SceneViewDemoTheme(darkTheme = false) {
            Box(Modifier.fillMaxWidth().height(heightDp.dp).clipToBounds()) {
                HomeHeroStill(Modifier.fillMaxSize())
            }
        }
    }

    private companion object {
        /** Same cross-host tolerance as the home goldens: ≤ 2/255 per channel, no pixel beyond. */
        val HOST_TOLERANT = RoborazziOptions(
            compareOptions = RoborazziOptions.CompareOptions(
                changeThreshold = 0f,
                imageComparator = SimpleImageComparator(maxDistance = 0.02f),
            ),
        )
    }
}
