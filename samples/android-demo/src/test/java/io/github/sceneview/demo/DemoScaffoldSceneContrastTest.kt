package io.github.sceneview.demo

import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import io.github.sceneview.demo.common.DemoStatusCard
import io.github.sceneview.demo.common.DemoStatusTone
import io.github.sceneview.demo.common.ProvideStatusBarIcons
import io.github.sceneview.demo.theme.SceneViewDemoTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** #3733: keep scene text clear and protect system icons only inside the status-bar inset. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DemoScaffoldSceneContrastTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun whiteCamera_lightTheme() = assertSceneContrast(Color.White, dark = false)

    @Test
    fun blackCamera_lightTheme() = assertSceneContrast(Color.Black, dark = false)

    @Test
    fun whiteCamera_darkTheme() = assertSceneContrast(Color.White, dark = true)

    @Test
    fun blackCamera_darkTheme() = assertSceneContrast(Color.Black, dark = true)

    @Test
    fun whiteCamera_themedLightStage() = assertSceneContrast(Color.White, dark = false, themedStage = true)

    @Test
    fun blackCamera_themedLightStage() = assertSceneContrast(Color.Black, dark = false, themedStage = true)

    @Test
    fun whiteCamera_lightTheme_hiddenChrome() = assertSceneContrast(Color.White, dark = false, hideChrome = true)

    @Test
    fun blackCamera_lightTheme_hiddenChrome() = assertSceneContrast(Color.Black, dark = false, hideChrome = true)

    private fun assertSceneContrast(
        ground: Color,
        dark: Boolean,
        themedStage: Boolean = false,
        hideChrome: Boolean = false,
    ) {
        composeRule.activityRule.scenario.onActivity { activity ->
            WindowCompat.setDecorFitsSystemWindows(activity.window, false)
            activity.window.statusBarColor = android.graphics.Color.TRANSPARENT
            activity.window.isStatusBarContrastEnforced = false
        }
        var observedStatusInsetPx = 0
        composeRule.setContent {
            SceneViewDemoTheme(darkTheme = dark) {
                ProvideStatusBarIcons {
                    observedStatusInsetPx = WindowInsets.statusBars.getTop(LocalDensity.current)
                    DemoScaffold(
                        title = "Contrast",
                        onBack = {},
                        controls = { Text("Setting") },
                        themedStage = themedStage,
                        chromeToggleOnTap = hideChrome,
                    ) {
                        Box(Modifier.fillMaxSize().background(ground)) {
                            Box(
                                Modifier.align(Alignment.TopCenter).padding(
                                    top = maxOf(
                                        LocalDemoChromeTopInset.current,
                                        WindowInsets.statusBars.asPaddingValues().calculateTopPadding(),
                                    ) + 16.dp,
                                ),
                            ) {
                                DemoStatusCard(text = TOP_TEXT, tone = DemoStatusTone.Guidance)
                            }
                            Box(
                                Modifier.align(Alignment.BottomCenter).padding(
                                    bottom = LocalDemoChromeBottomInset.current + 16.dp,
                                ),
                            ) {
                                DemoStatusCard(text = BOTTOM_TEXT, tone = DemoStatusTone.Guidance)
                            }
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
        val statusInsetPx = (STATUS_BAR_DP * composeRule.activity.resources.displayMetrics.density).roundToInt()
        composeRule.activityRule.scenario.onActivity { activity ->
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, statusInsetPx, 0, 0))
                .setVisible(WindowInsetsCompat.Type.statusBars(), true)
                .build()
            val content = activity.findViewById<ViewGroup>(android.R.id.content)
            generateSequence(content as View) { view ->
                (view as? ViewGroup)?.takeIf { it.childCount > 0 }?.getChildAt(0)
            }.forEach { ViewCompat.dispatchApplyWindowInsets(it, insets) }
        }
        composeRule.waitForIdle()
        assertEquals("the composition must receive the real 24 dp status inset", statusInsetPx, observedStatusInsetPx)
        if (hideChrome) {
            composeRule.onRoot().performTouchInput { click(center) }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag(DemoScaffoldTestTags.DOCK).assertIsNotDisplayed()
        }
        val image = composeRule.onRoot().captureToImage().asAndroidBitmap()
        val camera = if (ground == Color.White) "white" else "black"
        val theme = if (dark) "dark" else "light"
        val name = "$camera-$theme${if (themedStage) "-themed" else ""}${if (hideChrome) "-hidden" else ""}"
        val output = File("build/scaffold-renders/$name.png")
        output.parentFile.mkdirs()
        output.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }

        // Read actual glyph pixels through every line, from the top of the pill to its
        // bottom. Sample the solid stems, avoiding antialiased caps and descenders; the
        // darkest pixel on each row is the pill's fill between the glyphs.
        val texts = listOf(BOTTOM_TEXT to 3, TOP_TEXT to 3) +
            if (hideChrome) emptyList() else listOf("Settings" to 1, "Contrast" to 1)
        for ((text, lineCount) in texts) {
            val node = composeRule.onNodeWithText(text, useUnmergedTree = true)
            val bounds = node.fetchSemanticsNode().boundsInRoot
            val layouts = mutableListOf<TextLayoutResult>()
            node.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
            assertEquals(1, layouts.size)
            val layout = layouts.single()
            assertEquals(lineCount, layout.lineCount)
            val density = layout.layoutInput.density
            val em = layout.layoutInput.style.fontSize.value * density.density * density.fontScale
            val ratios = (0 until layout.lineCount).flatMap { line ->
                val baseline = bounds.top + layout.getLineBaseline(line)
                ((baseline - em * 0.65f).roundToInt()..(baseline - em * 0.15f).roundToInt()).map { y ->
                    val pixels = (bounds.left.roundToInt() until bounds.right.roundToInt()).map { x ->
                        Color(image.getPixel(x, y)).luminance()
                    }
                    (pixels.max() + 0.05f) / (pixels.min() + 0.05f)
                }
            }
            println("$name ${text.lineSequence().first()} minimum contrast: ${ratios.min()}")
            assertTrue("$name: text contrast by row $ratios", ratios.all { it >= 4.5f })
        }

        // System icons are drawn by Android, outside captureToImage. Read the real window
        // appearance and measure its nominal black/white glyph contrast against app pixels.
        val window = composeRule.activity.window
        val darkIcons = WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars
        val iconLuminance = if (darkIcons) 0f else 1f
        val statusRatios = (0 until statusInsetPx).map { y ->
            val background = Color(image.getPixel(1, y)).luminance()
            (max(iconLuminance, background) + 0.05f) / (min(iconLuminance, background) + 0.05f)
        }
        println("$name darkIcons=$darkIcons statusBarContrast=${statusRatios.min()}")
        assertTrue("$name: system icon contrast $statusRatios", statusRatios.all { it >= 4.5f })
        assertEquals("the light themed stage uses dark icons", themedStage && !dark, darkIcons)

        // Only the actual status-bar inset may have a full-width ground. Below it, the
        // camera must remain unchanged outside the identity row, cards and dock.
        for (y in statusInsetPx until image.height) {
            assertEquals("$name: camera tinted below status bar at row $y", ground.toArgb(), image.getPixel(1, y))
        }
    }

    private companion object {
        const val STATUS_BAR_DP = 24
        const val TOP_TEXT = "Find a surface\nMove slowly\nKeep looking"
        const val BOTTOM_TEXT = "Place the model\nMove slowly\nTap a surface"
    }
}
