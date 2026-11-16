package io.github.sceneview.demo.ui.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import io.github.sceneview.demo.DemoPreviews
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
import kotlin.math.abs
import kotlin.math.roundToInt

/** Real Geometry pixels must survive the row's layout, including a width-capped picture (#4351). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-notnight-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WhatsNewRowTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun phoneLight() = assertWholeGeometry("home_light", dark = false)

    @Test
    @Config(qualifiers = "w411dp-h891dp-night-xhdpi")
    fun phoneDark() = assertWholeGeometry("home_dark", dark = true)

    @Test
    @Config(qualifiers = "w1280dp-h800dp-notnight-xhdpi")
    fun tabletLight() = assertWholeGeometry("home_tablet_light", dark = false)

    @Test
    @Config(qualifiers = "w1280dp-h800dp-night-xhdpi")
    fun tabletDark() = assertWholeGeometry("home_tablet_dark", dark = true)

    @Test
    @Config(qualifiers = "w320dp-h800dp-notnight-xhdpi")
    fun narrowWindowWithLargeTextLight() = assertWholeGeometry("home_large_text_light", dark = false, fontScale = 2f)

    @Test
    @Config(qualifiers = "w320dp-h800dp-night-xhdpi")
    fun narrowWindowWithLargeTextDark() = assertWholeGeometry("home_large_text_dark", dark = true, fontScale = 2f)

    private fun assertWholeGeometry(name: String, dark: Boolean, fontScale: Float = 1f) {
        rule.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                SceneViewDemoTheme(darkTheme = dark) {
                    Box(Modifier.padding(16.dp)) {
                        WhatsNewRow(
                            subtitle = "See what's new in SceneView",
                            onClick = {},
                            leadDemoId = "geometry",
                            modifier = Modifier.testTag(ROW_TAG),
                        )
                    }
                }
            }
        }
        val row = rule.onNodeWithTag(ROW_TAG).captureToImage().asAndroidBitmap()
        val output = File("build/whats-new-row-renders/$name.png")
        requireNotNull(output.parentFile).mkdirs()
        output.outputStream().use { row.compress(Bitmap.CompressFormat.PNG, 100, it) }

        val resources = ApplicationProvider.getApplicationContext<Context>().resources
        val source = BitmapFactory.decodeResource(resources, requireNotNull(DemoPreviews.resourceFor("geometry", dark)))
        assertEquals(800, source.width)
        assertEquals(640, source.height)
        // Fit inside the row's height and at most half its width. A Crop here loses
        // the cylinder and torus when large text makes the row taller than that box.
        val scale = minOf(row.height.toFloat() / source.height, (row.width * 0.5f).roundToInt().toFloat() / source.width)
        val top = (row.height - source.height * scale) / 2f
        for ((shape, point) in LANDMARKS) {
            val (x, y) = point
            val expected = source.getPixel(x, y)
            val actual = row.getPixel((x * scale).roundToInt(), (top + y * scale).roundToInt())
            for (channel in listOf<(Int) -> Int>(Color::red, Color::green, Color::blue)) {
                assertTrue("$name: $shape is cropped or faded", abs(channel(expected) - channel(actual)) <= 25)
            }
        }
        source.recycle()

        // The dark stage may be shared by both themes; the row still melts into
        // the scheme's own tint instead of stretching that stage under the caption.
        val ground = row.getPixel(row.width * 9 / 10, row.height - 6)
        assertTrue("$name: row tint follows the theme", if (dark) Color.red(ground) < 100 else Color.red(ground) > 200)
    }

    private companion object {
        const val ROW_TAG = "whats-new-row"
        // Interior landmarks, including the lower tips cut in the original phone
        // goldens, and both sides lost by Crop when the picture's width is capped.
        val LANDMARKS = mapOf(
            "cube" to (290 to 180),
            "sphere" to (500 to 180),
            "cylinder" to (180 to 370),
            "cone" to (400 to 395),
            "torus" to (630 to 360),
            "capsule bottom" to (290 to 570),
            "plane bottom" to (495 to 575),
        )
    }
}
