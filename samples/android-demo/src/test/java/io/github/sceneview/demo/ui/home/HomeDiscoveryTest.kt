package io.github.sceneview.demo.ui.home

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import io.github.sceneview.demo.ALL_DEMOS
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.listedDemos
import io.github.sceneview.demo.theme.SceneViewDemoTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Layout and navigation checks without loading the live renderer. Native graphics: the legacy
 * shadow text layout does not measure real glyphs, so it cannot answer "does this label fit".
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xhdpi")
class HomeDiscoveryTest {
    @get:Rule
    val rule = createComposeRule()

    private val opened = mutableListOf<String>()

    private fun showHome(darkTheme: Boolean) {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalInspectionMode provides true,
                LocalDensity provides Density(density.density, fontScale = 1.3f),
            ) {
                SceneViewDemoTheme(darkTheme = darkTheme) {
                    Surface(Modifier.fillMaxSize()) {
                        HomeScreen(
                            demos = listedDemos(ALL_DEMOS, xrDevice = false),
                            query = "",
                            onQueryChange = {},
                            onDemoClick = { opened += it },
                            onBrowseOnlineClick = {},
                            buildVersion = "4.67.0",
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun `light theme shows all three complete outcomes at 360dp and 130 percent text`() {
        showHome(darkTheme = false)
        assertOutcomes()
    }

    @Test
    fun `dark theme shows all three complete outcomes at 360dp and 130 percent text`() {
        showHome(darkTheme = true)
        assertOutcomes()
    }

    private fun assertOutcomes() {
        val hero = rule.onNodeWithTag(HomeTestTags.HERO).assertHeightIsEqualTo(192.dp)
        val firstChoice = rule.onNodeWithTag(HomeTestTags.outcome("model-viewer"))
        assertTrue(
            "The first choice must sit below the live scene",
            firstChoice.getUnclippedBoundsInRoot().top > hero.getUnclippedBoundsInRoot().bottom,
        )
        val expected = listOf(
            "model-viewer" to "View a model",
            "ar-placement" to "Place in your room",
            "ar-rerun" to "Explore a scan",
        )
        expected.forEach { (id, label) ->
            rule.onNodeWithTag(HomeTestTags.GRID).performScrollToNode(hasTestTag(HomeTestTags.outcome(id)))
            val row = rule.onNodeWithTag(HomeTestTags.outcome(id)).assertIsDisplayed()
            val outcome = HOME_OUTCOMES.single { it.demoId == id }
            val supportingText = androidx.test.core.app.ApplicationProvider
                .getApplicationContext<android.content.Context>().getString(outcome.supportingRes)
            row.assertContentDescriptionEquals("$label. $supportingText")
            assertTrue(row.getUnclippedBoundsInRoot().height >= 48.dp)
            val layouts = mutableListOf<TextLayoutResult>()
            rule.onNodeWithText(label, useUnmergedTree = true).assertIsDisplayed()
                .fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
            val layout = layouts.single()
            assertFalse(
                "$label must fit without clipping: size=${layout.size}, paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height}, lines=${layout.lineCount}, widthOverflow=${layout.didOverflowWidth}, heightOverflow=${layout.didOverflowHeight}",
                layout.hasVisualOverflow,
            )
            assertTrue((0 until layout.lineCount).none { layout.isLineEllipsized(it) })
            row.performClick()
        }
        assertEquals(expected.map { it.first }, opened)
        listOf("materials", "cosmos").forEach { id ->
            rule.onNodeWithTag(HomeTestTags.GRID).performScrollToNode(hasTestTag(HomeTestTags.featured(id)))
            rule.onNodeWithTag(HomeTestTags.featured(id)).assertIsDisplayed()
        }
        rule.onNodeWithTag(HomeTestTags.WHATS_NEW_ROW).assertDoesNotExist()
        rule.onNodeWithTag(HomeTestTags.GRID).performScrollToNode(hasTestTag(HomeTestTags.row("model-viewer")))
        val catalogueRow = rule.onNodeWithTag(HomeTestTags.row("model-viewer")).assertIsDisplayed()
        assertTrue(
            "A catalogue picture must not inflate its row beyond the live hero height",
            catalogueRow.getUnclippedBoundsInRoot().height < 192.dp,
        )
    }

    @Test
    fun `Materials shortcut keeps its row when jumping to Materials and scenes`() {
        showHome(darkTheme = false)
        rule.onNodeWithTag(HomeTestTags.GRID).performScrollToNode(hasTestTag(HomeTestTags.featured("materials")))
        rule.onNodeWithTag(HomeTestTags.featured("materials")).assertIsDisplayed()
        val chip = HomeTestTags.sectionChip(DemoCategory.CREATE)
        rule.onNodeWithTag(HomeTestTags.GRID).performScrollToNode(hasTestTag(HomeTestTags.CATEGORY_CHIPS))
        rule.onNodeWithTag(HomeTestTags.CATEGORY_CHIPS).performScrollToNode(hasTestTag(chip))
        rule.onNodeWithTag(chip).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(HomeTestTags.sectionHeader(DemoCategory.CREATE)).assertIsDisplayed()
        rule.onNodeWithTag(HomeTestTags.row("materials")).assertIsDisplayed()
    }
}
