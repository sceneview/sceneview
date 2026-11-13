package io.github.sceneview.demo.ui.home

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.sceneview.demo.ALL_DEMOS
import io.github.sceneview.demo.theme.SceneViewDemoTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class HomeNavigationTest {
    @get:Rule val rule = createComposeRule()

    @Test fun `AR is visible initially and its selection survives restoration`() {
        val restoration = StateRestorationTester(rule)
        var opened: String? = null
        restoration.setContent {
            var section by rememberSaveable { mutableStateOf(HomeSection.Models) }
            CompositionLocalProvider(LocalInspectionMode provides true) {
                SceneViewDemoTheme {
                    HomeScreen(ALL_DEMOS, "", {}, { opened = it }, section, { section = it }, {}, false, {})
                }
            }
        }
        rule.onNodeWithTag(HomeTestTags.section(HomeSection.AR)).assertIsDisplayed().performClick()
        rule.onNodeWithTag(HomeTestTags.row("ar-placement")).assertIsDisplayed()
        rule.onNodeWithTag(HomeTestTags.row("model-viewer")).assertDoesNotExist()
        restoration.emulateSavedInstanceStateRestore()
        rule.onNodeWithTag(HomeTestTags.section(HomeSection.AR)).assertIsSelected()
        rule.onNodeWithTag(HomeTestTags.row("ar-placement")).performClick()
        assertEquals("ar-placement", opened)
    }

    @Test fun `the uncaptioned hero keeps its model viewer shortcut`() {
        var opened: String? = null
        rule.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                SceneViewDemoTheme {
                    HomeScreen(ALL_DEMOS, "", {}, { opened = it }, HomeSection.Models, {}, {}, false, {})
                }
            }
        }
        rule.onNodeWithTag(HomeTestTags.HERO).performClick()
        assertEquals("model-viewer", opened)
    }

    @Test fun `a repeated AR deep link closes even an empty search`() {
        val destination = mutableStateOf<io.github.sceneview.demo.DeepLinkRouter.HomeDestination?>(
            io.github.sceneview.demo.DeepLinkRouter.HomeDestination.AR,
        )
        rule.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                SceneViewDemoTheme {
                    io.github.sceneview.demo.ui.RootScreen(
                        onDemoClick = {},
                        destination = destination.value,
                        onDestinationConsumed = { destination.value = null },
                    )
                }
            }
        }
        rule.onNodeWithTag(HomeTestTags.section(HomeSection.AR)).assertIsSelected()
        rule.onNodeWithContentDescription("Search demos").performClick()
        rule.onNodeWithTag(HomeTestTags.SEARCH_FIELD).assertIsDisplayed()
        rule.runOnIdle { destination.value = io.github.sceneview.demo.DeepLinkRouter.HomeDestination.AR }
        rule.onNodeWithTag(HomeTestTags.SEARCH_FIELD).assertDoesNotExist()
        rule.onNodeWithTag(HomeTestTags.section(HomeSection.AR)).assertIsSelected()
        rule.onNodeWithTag(HomeTestTags.row("ar-placement")).assertIsDisplayed()
    }

    @Test fun `search reaches another section and closing it restores selected section`() {
        rule.setContent {
            var query by rememberSaveable { mutableStateOf("") }
            var searchOpen by rememberSaveable { mutableStateOf(false) }
            CompositionLocalProvider(LocalInspectionMode provides true) {
                SceneViewDemoTheme {
                    HomeScreen(ALL_DEMOS, query, { query = it }, {}, HomeSection.Models, {}, {}, searchOpen, { searchOpen = it })
                }
            }
        }
        rule.onNodeWithContentDescription("Search demos").performClick()
        rule.onNodeWithTag(HomeTestTags.SEARCH_FIELD).performTextInput("Cloud Anchors")
        rule.onNodeWithTag(HomeTestTags.row("ar-cloud-anchor")).assertIsDisplayed()
        rule.onNodeWithTag(HomeTestTags.SEARCH_CLOSE).performClick()
        rule.onNodeWithTag(HomeTestTags.section(HomeSection.Models)).assertIsSelected()
        rule.onNodeWithTag(HomeTestTags.row("model-viewer")).assertIsDisplayed()
    }
}
