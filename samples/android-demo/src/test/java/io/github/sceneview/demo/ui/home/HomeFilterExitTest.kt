package io.github.sceneview.demo.ui.home

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.R
import io.github.sceneview.demo.theme.SceneViewDemoTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A filter on the Home must never read as "this is all the app has" (#4304): the
 * "What's new" row leaves the catalogue whole, and a chip filter can be left three ways —
 * its own chip, Back, and the "Show all N samples" button that ends the filtered list.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class HomeFilterExitTest {

    @get:Rule
    val rule = createComposeRule()

    private var category by mutableStateOf<String?>(null)
    private val changes = mutableListOf<String?>()
    private lateinit var back: OnBackPressedDispatcher

    private fun setUpHome(initial: String? = null) {
        category = initial
        rule.setContent {
            back = checkNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            CompositionLocalProvider(LocalInspectionMode provides true) {
                SceneViewDemoTheme(darkTheme = false) {
                    Surface {
                        HomeScreen(
                            demos = DEMOS,
                            selectedCategory = category,
                            onCategoryChange = {
                                changes += it
                                category = it
                            },
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

    private fun chip(label: String) = rule.onNode(
        hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab),
    )

    private fun scrollTo(tag: String) {
        rule.onNodeWithTag(HomeTestTags.GRID).performScrollToNode(hasTestTag(tag))
    }

    @Test
    fun `the What's new row does not filter the catalogue`() {
        setUpHome()
        scrollTo(HomeTestTags.WHATS_NEW_ROW)

        rule.onNodeWithTag(HomeTestTags.WHATS_NEW_ROW).performClick()

        rule.runOnIdle {
            assertEquals(emptyList<String?>(), changes)
            assertNull(category)
        }
    }

    @Test
    fun `the selected chip is its own off switch`() {
        setUpHome()

        chip("Create").performClick()
        rule.runOnIdle { assertEquals(DemoCategory.CREATE, category) }

        chip("Create").performClick()
        rule.runOnIdle { assertNull(category) }
    }

    @Test
    fun `a filtered list ends on the way back to the whole catalogue`() {
        setUpHome(initial = WHATS_NEW_FILTER)
        scrollTo(HomeTestTags.SHOW_ALL)

        rule.onNodeWithText("Show all ${DEMOS.size} samples").assertIsDisplayed().performClick()

        rule.runOnIdle { assertNull(category) }
        rule.onNodeWithTag(HomeTestTags.SHOW_ALL).assertDoesNotExist()
    }

    @Test
    fun `back leaves the filter before it leaves the Home`() {
        setUpHome(initial = WHATS_NEW_FILTER)

        rule.runOnIdle { back.onBackPressed() }

        rule.runOnIdle { assertEquals(listOf<String?>(null), changes) }
    }

    private companion object {
        /** The build the fixture's versions are read against: only the two 4.50 demos are fresh. */
        const val BUILD_VERSION = "4.50.0"

        /**
         * A fixed catalogue, so a demo added, moved or badged in the registry cannot change
         * what these tests see: the hero's page, two fresh demos and two that are not, over
         * three sections. The labels are any two strings of the app — no test reads them.
         */
        val DEMOS: List<DemoEntry> = listOf(
            demo(HERO_DEMO_ID, DemoCategory.VIEW_3D, order = 1),
            demo("fixture-updated", DemoCategory.CREATE, order = 2, updatedIn = "4.50.0"),
            demo("fixture-added", DemoCategory.CREATE, order = 3, addedIn = "4.50.0"),
            demo("fixture-settled", DemoCategory.CREATE, order = 4),
            demo("fixture-tool", DemoCategory.DEV_TOOLS, order = 5),
        )

        fun demo(
            id: String,
            category: String,
            order: Int,
            addedIn: String = "4.0.0",
            updatedIn: String? = null,
        ) = DemoEntry(
            id = id,
            titleRes = R.string.home_section_featured,
            subtitleRes = R.string.home_browse_subtitle,
            category = category,
            icon = Icons.Filled.Palette,
            order = order,
            tags = emptySet(),
            addedIn = addedIn,
            updatedIn = updatedIn,
        )
    }
}
