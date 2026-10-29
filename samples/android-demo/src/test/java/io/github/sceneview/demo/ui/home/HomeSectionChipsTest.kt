package io.github.sceneview.demo.ui.home

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.R
import io.github.sceneview.demo.theme.SceneViewDemoTheme
import io.github.sceneview.demo.theme.SceneViewTokens
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Home's chips are a table of contents, not a filter: a chip scrolls the catalogue to
 * its section, under the pinned header, and every sample stays listed (#4304 — a filter
 * read as "this is all the app has").
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class HomeSectionChipsTest {

    @get:Rule
    val rule = createComposeRule()

    private fun setUpHome() {
        rule.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                SceneViewDemoTheme(darkTheme = false) {
                    Surface {
                        HomeScreen(
                            demos = DEMOS,
                            query = "",
                            onQueryChange = {},
                            onDemoClick = {},
                            onBrowseOnlineClick = {},
                            buildVersion = BUILD_VERSION,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `a chip scrolls its section header just under the pinned header`() {
        setUpHome()
        val placeAr = HomeTestTags.sectionHeader(DemoCategory.PLACE_AR)
        rule.onNodeWithTag(placeAr).assertDoesNotExist()

        rule.onNodeWithTag(HomeTestTags.GRID)
            .performScrollToNode(hasTestTag(HomeTestTags.sectionChip(DemoCategory.PLACE_AR)))
        rule.onNodeWithTag(HomeTestTags.sectionChip(DemoCategory.PLACE_AR)).performClick()
        rule.waitForIdle()

        val top = rule.onNodeWithTag(placeAr).assertIsDisplayed().getUnclippedBoundsInRoot().top
        val header = SceneViewTokens.Home.headerHeight
        assertTrue("section header at $top, pinned header is $header", top >= header - 1.dp)
        assertTrue("section header at $top, expected right under the pinned header", top < header + 48.dp)
    }

    @Test
    fun `a chip leaves every sample listed`() {
        setUpHome()
        rule.onNodeWithTag(HomeTestTags.GRID)
            .performScrollToNode(hasTestTag(HomeTestTags.sectionChip(DemoCategory.DEV_TOOLS)))
        rule.onNodeWithTag(HomeTestTags.sectionChip(DemoCategory.DEV_TOOLS)).performClick()
        rule.waitForIdle()

        // The section before the one jumped to is still in the list, above it.
        rule.onNodeWithTag(HomeTestTags.GRID)
            .performScrollToNode(hasTestTag(HomeTestTags.sectionHeader(DemoCategory.CREATE)))
        rule.onNodeWithTag(HomeTestTags.sectionHeader(DemoCategory.CREATE)).assertIsDisplayed()
    }

    private companion object {
        const val BUILD_VERSION = "4.50.0"

        /**
         * A fixed catalogue, so a demo added or moved in the registry cannot change what
         * these tests see: the hero's page, then a Create section long enough to push the
         * next sections off the first screen, and a Place in AR section long enough to
         * be scrolled to the top — the last section of a list cannot be, the list ends
         * first. The labels are any two strings of the app — no test reads them.
         */
        val DEMOS: List<DemoEntry> = listOf(demo(HERO_DEMO_ID, DemoCategory.VIEW_3D, order = 1)) +
            (2..13).map { demo("fixture-create-$it", DemoCategory.CREATE, order = it) } +
            (14..25).map { demo("fixture-place-$it", DemoCategory.PLACE_AR, order = it) } +
            demo("fixture-tool", DemoCategory.DEV_TOOLS, order = 26)

        fun demo(id: String, category: String, order: Int) = DemoEntry(
            id = id,
            titleRes = R.string.home_section_featured,
            subtitleRes = R.string.home_browse_subtitle,
            category = category,
            icon = Icons.Filled.Palette,
            order = order,
            tags = emptySet(),
            addedIn = "4.0.0",
        )
    }
}
