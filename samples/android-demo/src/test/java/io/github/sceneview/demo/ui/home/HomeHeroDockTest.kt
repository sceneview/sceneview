package io.github.sceneview.demo.ui.home

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.dropbox.differ.SimpleImageComparator
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.sceneview.demo.ALL_DEMOS
import io.github.sceneview.demo.theme.SceneViewDemoTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The two properties the hero exists for, asserted on the composed screen rather than on the maths.
 *
 * The bug being fixed is a *gesture* bug: a thumb that started its downward travel on the 3D card
 * had its scroll swallowed, and the page would not move. Two things now stand between that and the
 * user, and neither is visible in [HomeHeroPoseTest]:
 *
 * 1. the stage is **not in the touch path at all** — it is drawn under the catalogue, it installs
 *    no pointer input, and (`isTouchEnabled = false`) the SDK installs none for it either;
 * 2. the one thing that *is* pinned over the catalogue, the permanent 80 dp band, hands the drags
 *    it receives back to the grid instead of eating them.
 *
 * The second is what this file pins, because it is the one that can regress silently: a band that
 * stops forwarding still looks perfectly correct in a screenshot.
 *
 * The goldens are the other half. The choreography is driven by a single scroll offset, so the
 * grid's state is hoisted and set *exactly* here — a swipe would land wherever the touch slop of
 * the day put it, and "the stage at mid-course" is not a property that can be pinned from an
 * approximate scroll. LayoutLib has no Filament, so what these images pin is the geometry — the
 * clip down to the band, the copy leaving before the clip reaches it, the first card meeting the
 * clipped edge — and not the camera, which [HomeHeroPoseTest] covers on 513 samples.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-night-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeHeroDockTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var grid: LazyGridState
    private lateinit var density: Density

    /** Absolute scroll, in dp, that [scrollTo] has applied so far. */
    private var scrolledDp = 0f

    @Test
    fun `a drag that starts on the docked band still scrolls the catalogue`() {
        composeRule.setContent { Home() }

        composeRule.runOnIdle {
            assertEquals(0, grid.firstVisibleItemIndex)
            assertEquals(0, grid.firstVisibleItemScrollOffset)
        }
        composeRule.onNodeWithTag(HomeTestTags.HERO_DOCK).performTouchInput { swipeUp() }

        composeRule.runOnIdle {
            assertTrue(
                "a swipe that started on the band left the catalogue where it was — the band is " +
                    "eating the gesture instead of handing it to the grid",
                grid.firstVisibleItemIndex > 0 || grid.firstVisibleItemScrollOffset > 0,
            )
        }
    }

    @Test
    fun `the catalogue starts below the band, at every scroll offset`() {
        composeRule.setContent { Home() }

        // At rest, mid-course, docked, and well past the end of the choreography.
        for (offset in listOf(0f, 170f, 340f, 900f)) {
            scrollTo(offset)
            val top = composeRule.onNodeWithTag(HomeTestTags.GRID).getUnclippedBoundsInRoot().top
            assertEquals(
                "the catalogue's top moved at $offset dp of scroll — the band is permanent only " +
                    "for as long as this number is not a function of the scroll",
                BAND_BOTTOM_DP,
                top.value,
                0.5f,
            )
        }
    }

    @Test
    fun hero_midCourse() {
        captureAt(170f, "src/test/snapshots/home_hero_mid.png")
    }

    @Test
    fun hero_docked() {
        captureAt(340f, "src/test/snapshots/home_hero_docked.png")
    }

    private fun captureAt(offsetDp: Float, path: String) {
        composeRule.setContent { Home() }
        scrollTo(offsetDp)
        composeRule.onRoot().captureRoboImage(path, roborazziOptions = HOST_TOLERANT)
    }

    /**
     * Places the screen at an absolute scroll offset.
     *
     * `dispatchRawDelta` rather than `scrollBy`: it is synchronous and takes no scroll mutex, and
     * the point is to *place* the screen at a known `p`, not to simulate reaching it.
     */
    private fun scrollTo(offsetDp: Float) {
        composeRule.runOnIdle {
            grid.dispatchRawDelta(with(density) { (offsetDp - scrolledDp).dp.toPx() })
            scrolledDp = offsetDp
        }
        composeRule.waitForIdle()
    }

    @androidx.compose.runtime.Composable
    private fun Home() {
        grid = rememberLazyGridState()
        density = LocalDensity.current
        CompositionLocalProvider(LocalInspectionMode provides true) {
            SceneViewDemoTheme(darkTheme = true) {
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
                        gridState = grid,
                    )
                }
            }
        }
    }

    private companion object {
        /** Header (56) + gap (8) + the permanent band (80): the line no card may ever cross. */
        const val BAND_BOTTOM_DP = 144f

        /** Any parseable constant: these images pin geometry, not the freshness chips (#3666). */
        const val PINNED_BUILD_VERSION = "0.0.0"

        val HOST_TOLERANT = RoborazziOptions(
            compareOptions = RoborazziOptions.CompareOptions(
                changeThreshold = 0f,
                imageComparator = SimpleImageComparator(maxDistance = 0.02f),
            ),
        )
    }
}
