package io.github.sceneview.demo

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import io.github.sceneview.demo.common.DemoStatusCard
import io.github.sceneview.demo.common.DemoStatusTone
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
import kotlin.math.roundToInt

/** #3733: scaffold chrome must not darken text composed in the scene slot. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DemoScaffoldSceneContrastTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun whiteCamera_lightTheme() = assertSceneContrast(Color.White, dark = false)

    @Test
    fun blackCamera_lightTheme() = assertSceneContrast(Color.Black, dark = false)

    @Test
    fun whiteCamera_darkTheme() = assertSceneContrast(Color.White, dark = true)

    @Test
    fun blackCamera_darkTheme() = assertSceneContrast(Color.Black, dark = true)

    private fun assertSceneContrast(ground: Color, dark: Boolean) {
        composeRule.setContent {
            SceneViewDemoTheme(darkTheme = dark) {
                DemoScaffold(title = "Contrast", onBack = {}, controls = { Text("Setting") }) {
                    Box(Modifier.fillMaxSize().background(ground)) {
                        Box(Modifier.align(Alignment.TopCenter).padding(top = 80.dp)) {
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
        composeRule.waitForIdle()
        val image = composeRule.onRoot().captureToImage().asAndroidBitmap()
        val camera = if (ground == Color.White) "white" else "black"
        val theme = if (dark) "dark" else "light"
        val name = "$camera-$theme"
        val output = File("build/scaffold-renders/$name.png")
        output.parentFile.mkdirs()
        output.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }

        // Read actual glyph pixels through every line, from the top of the pill to its
        // bottom. Sample the solid stems, avoiding antialiased caps and descenders; the
        // darkest pixel on each row is the pill's fill between the glyphs.
        val texts = listOf(BOTTOM_TEXT to 3, TOP_TEXT to 3, "Settings" to 1, "Contrast" to 1)
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

        // No full-width tint may cover the camera at either edge. This column is outside
        // the identity row and dock, so it must stay unchanged along the entire scene.
        for (y in 0 until image.height) {
            assertEquals("$name: camera tinted at row $y", ground.toArgb(), image.getPixel(1, y))
        }
    }

    private companion object {
        const val TOP_TEXT = "Find a surface\nMove slowly\nKeep looking"
        const val BOTTOM_TEXT = "Place the model\nMove slowly\nTap a surface"
    }
}
