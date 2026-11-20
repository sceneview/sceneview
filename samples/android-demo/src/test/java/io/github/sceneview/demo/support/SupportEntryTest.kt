package io.github.sceneview.demo.support

import android.graphics.Bitmap
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.R
import io.github.sceneview.demo.theme.SceneViewDemoTheme
import io.github.sceneview.demo.ui.home.HomeScreen
import io.github.sceneview.demo.ui.home.HomeTestTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Home's two support entries stay readable and independently tappable in each theme. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-notnight-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SupportEntryTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun light() = checkEntries(dark = false)

    @Test
    @Config(qualifiers = "w360dp-h800dp-night-xhdpi")
    fun dark() = checkEntries(dark = true)

    @Test
    fun lightWithLargeText() = checkEntries(dark = false, fontScale = 1.3f)

    @Test
    @Config(qualifiers = "w360dp-h800dp-night-xhdpi")
    fun darkWithLargeText() = checkEntries(dark = true, fontScale = 1.3f)

    private fun checkEntries(dark: Boolean, fontScale: Float = 1f) {
        var homeOpens = 0
        var menuOpens = 0
        rule.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(
                LocalDensity provides Density(density, fontScale),
                LocalInspectionMode provides true,
            ) {
                SceneViewDemoTheme(darkTheme = dark) {
                    Surface {
                        HomeScreen(
                            demos = listOf(
                                DemoEntry(
                                    id = "model-viewer",
                                    sourcePath = "src/main/java/io/github/sceneview/demo/demos/ModelViewerDemo.kt",
                                    titleRes = R.string.demo_model_viewer_screen_title,
                                    subtitleRes = R.string.home_browse_subtitle,
                                    category = DemoCategory.VIEW_3D,
                                    icon = Icons.Filled.Palette,
                                    order = 1,
                                    tags = emptySet(),
                                    addedIn = "4.0.0",
                                ),
                            ),
                            query = "",
                            onQueryChange = {},
                            onDemoClick = {},
                            onBrowseOnlineClick = {},
                            onSupportClick = { homeOpens++ },
                            onMenuSupportClick = { menuOpens++ },
                            hasUnseenWhatsNew = true,
                            buildVersion = "4.50.0",
                        )
                    }
                }
            }
        }
        rule.onNodeWithTag(HomeTestTags.SUPPORT_ENTRY).assertIsDisplayed()
        listOf("Support SceneView", "Help maintain the SDK, docs and samples.").forEach { text ->
            val layouts = mutableListOf<TextLayoutResult>()
            rule.onNodeWithText(text, useUnmergedTree = true)
                .assertIsDisplayed()
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue("$text has a text layout", layouts.isNotEmpty())
            assertFalse("$text is clipped at $fontScale", layouts.single().hasVisualOverflow)
        }
        val image = rule.onRoot().captureToImage().asAndroidBitmap()
        val file = File("build/support-entry-renders/home-${if (dark) "dark" else "light"}-$fontScale.png")
        requireNotNull(file.parentFile).mkdirs()
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        rule.onNodeWithTag(HomeTestTags.SUPPORT_ENTRY).performClick()
        assertEquals(1, homeOpens)
        assertEquals(0, menuOpens)
        rule.onNodeWithTag(HomeTestTags.MENU).assertIsDisplayed().performClick()
        rule.onNodeWithTag(HomeTestTags.MENU_SUPPORT).assertIsDisplayed().performClick()
        rule.onNodeWithTag(HomeTestTags.MENU_SUPPORT).assertDoesNotExist()
        assertEquals(1, homeOpens)
        assertEquals(1, menuOpens)
    }
}
