package io.github.sceneview.demo

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.theme.md_theme_dark_surfaceContainer
import io.github.sceneview.demo.theme.md_theme_light_surfaceContainer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** A cold preview cache must not hold up the first frame of Back from an AR demo (#4461). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DemoPreviewsTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val decodeThreads = CopyOnWriteArrayList<Thread>()
    private var decodeGate: CountDownLatch? = null
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val baseResources = context.resources

    @Suppress("DEPRECATION")
    private val resources = object : Resources(
        baseResources.assets, baseResources.displayMetrics, baseResources.configuration,
    ) {
        override fun getDrawable(id: Int, theme: Theme?): Drawable {
            decodeThreads += Thread.currentThread()
            check(decodeGate?.await(TIMEOUT_MS, TimeUnit.MILLISECONDS) != false) { "decode gate timed out" }
            return super.getDrawable(id, theme)
        }
    }

    @Before
    fun clearCache() = DemoPreviews.trimMemory()

    @After
    fun releaseCache() {
        decodeGate?.countDown()
        DemoPreviews.trimMemory()
    }

    @Test
    fun `a cache miss decodes off the main thread`() = runBlocking {
        val bitmap = DemoPreviews.bitmap(resources, R.drawable.preview_ar_placement_light)

        assertTrue(bitmap.width > 0)
        assertFalse("the probe must observe the real drawable decode", decodeThreads.isEmpty())
        assertTrue("decoding must leave the UI thread", decodeThreads.all { it != Looper.getMainLooper().thread })
    }

    @Test
    fun `a cached bitmap is reused until memory is trimmed`() = runBlocking {
        val res = R.drawable.preview_ar_placement_light
        val first = DemoPreviews.bitmap(resources, res)
        val decodes = decodeThreads.size

        assertSame(first, DemoPreviews.bitmap(resources, res))
        assertEquals("a cache hit must not decode again", decodes, decodeThreads.size)

        DemoPreviews.trimMemory()
        DemoPreviews.bitmap(resources, res)
        assertTrue("trimmed previews must be decoded again", decodeThreads.size > decodes)
    }

    @Test
    fun `a cold preview fades from a neutral placeholder to the picture`() {
        composeRule.mainClock.autoAdvance = false
        decodeGate = CountDownLatch(1)
        val observed = observePreview()

        val placeholder = List(64) { md_theme_light_surfaceContainer }
        assertEquals(
            "the first frame must be neutral without waiting for a decode",
            placeholder,
            pixels(observed.first()!!),
        )
        decodeGate!!.countDown()
        composeRule.waitUntil(timeoutMillis = TIMEOUT_MS) {
            DemoPreviews.cachedBitmap(R.drawable.preview_ar_placement_light) != null
        }
        composeRule.waitUntil(timeoutMillis = TIMEOUT_MS) {
            composeRule.mainClock.advanceTimeByFrame()
            observed.size > 1
        }
        val expected = pixels(
            BitmapPainter(DemoPreviews.cachedBitmap(R.drawable.preview_ar_placement_light)!!),
            background = md_theme_light_surfaceContainer,
        )
        assertEquals("a newly decoded picture must start at zero opacity", placeholder, pixels(observed.last()!!))
        composeRule.mainClock.advanceTimeBy(SceneViewTokens.Motion.fadeMillis / 2L)
        val halfway = pixels(observed.last()!!)
        assertFalse("the picture must become visible during the fade", halfway == placeholder)
        assertFalse("the picture must not jump to full opacity", halfway == expected)
        composeRule.mainClock.advanceTimeBy(SceneViewTokens.Motion.fadeMillis.toLong())
        assertEquals(expected, pixels(observed.last()!!))
        assertFalse(decodeThreads.isEmpty())
        assertTrue(decodeThreads.all { it != Looper.getMainLooper().thread })
    }

    @Test
    fun `a warm preview is available in the first composition`() {
        runBlocking { DemoPreviews.bitmap(resources, R.drawable.preview_ar_placement_light) }
        val decodes = decodeThreads.size

        val observed = observePreview()

        assertNotNull(observed.first())
        assertEquals(
            "a cache hit must be fully visible without a fade",
            pixels(
                BitmapPainter(DemoPreviews.cachedBitmap(R.drawable.preview_ar_placement_light)!!),
                md_theme_light_surfaceContainer,
            ),
            pixels(observed.first()!!),
        )
        assertEquals("the first frame reuses the cache", decodes, decodeThreads.size)
    }

    @Test
    fun `a new theme never displays the previous themes painter while loading`() {
        var dark by mutableStateOf(false)
        val observed = CopyOnWriteArrayList<Pair<Boolean, Painter?>>()
        val demo = ALL_DEMOS.first { it.id == "ar-placement" }
        composeRule.setContent {
            CompositionLocalProvider(LocalContext provides previewContext) {
                val painter = demo.previewPainter(dark = dark)
                SideEffect { observed += dark to painter }
            }
        }
        composeRule.waitUntil(timeoutMillis = TIMEOUT_MS) {
            DemoPreviews.cachedBitmap(R.drawable.preview_ar_placement_light) != null
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle { dark = true }
        composeRule.waitForIdle()
        composeRule.waitUntil(timeoutMillis = TIMEOUT_MS) {
            DemoPreviews.cachedBitmap(R.drawable.preview_ar_placement_dark) != null
        }

        assertEquals(List(64) { md_theme_dark_surfaceContainer }, pixels(observed.first { it.first }.second!!))
    }

    @Test
    fun `a demo without a capture keeps the icon fallback`() {
        val demo = ALL_DEMOS.first().copy(id = "missing-preview")
        val observed = CopyOnWriteArrayList<Painter?>()
        composeRule.setContent {
            val painter = demo.previewPainter(dark = false)
            SideEffect { observed += painter }
        }

        assertFalse(observed.isEmpty())
        observed.forEach { assertNull(it) }
    }

    private val previewContext: Context
        get() = object : ContextWrapper(context) {
            override fun getResources(): Resources = this@DemoPreviewsTest.resources
        }

    private fun observePreview(): List<Painter?> {
        val observed = CopyOnWriteArrayList<Painter?>()
        val demo = ALL_DEMOS.first { it.id == "ar-placement" }
        composeRule.setContent {
            CompositionLocalProvider(LocalContext provides previewContext) {
                val painter = demo.previewPainter(dark = false)
                SideEffect { observed += painter }
            }
        }
        return observed
    }

    /** Draw the actual painter, including animation opacity, without a device screenshot. */
    private fun pixels(painter: Painter, background: Color = Color.Transparent): List<Color> {
        val image = ImageBitmap(8, 8)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(image), Size(8f, 8f)) {
            drawRect(background)
            with(painter) { draw(size) }
        }
        val pixels = image.toPixelMap()
        return List(64) { pixels[it % 8, it / 8] }
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
