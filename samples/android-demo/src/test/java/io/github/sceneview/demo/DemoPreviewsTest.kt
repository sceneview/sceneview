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
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
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

/** A cold preview cache must not hold up the first frame of Back from an AR demo (#4461). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DemoPreviewsTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val decodeThreads = CopyOnWriteArrayList<Thread>()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val baseResources = context.resources

    @Suppress("DEPRECATION")
    private val resources = object : Resources(
        baseResources.assets, baseResources.displayMetrics, baseResources.configuration,
    ) {
        override fun getDrawable(id: Int, theme: Theme?): Drawable {
            decodeThreads += Thread.currentThread()
            return super.getDrawable(id, theme)
        }
    }

    @Before
    fun clearCache() = DemoPreviews.trimMemory()

    @After
    fun releaseCache() = DemoPreviews.trimMemory()

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
    fun `a cold preview first yields the icon fallback then a painter`() {
        val observed = observePreview()

        assertNull("the first composition must not wait for a decode", observed.first())
        composeRule.waitUntil { observed.lastOrNull() != null }
        assertFalse(decodeThreads.isEmpty())
        assertTrue(decodeThreads.all { it != Looper.getMainLooper().thread })
    }

    @Test
    fun `a warm preview is available in the first composition`() {
        runBlocking { DemoPreviews.bitmap(resources, R.drawable.preview_ar_placement_light) }
        val decodes = decodeThreads.size

        val observed = observePreview()

        assertNotNull(observed.first())
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
        composeRule.waitUntil { observed.any { !it.first && it.second != null } }

        composeRule.runOnIdle { dark = true }
        composeRule.waitForIdle()
        composeRule.waitUntil { observed.any { it.first && it.second != null } }

        assertNull(observed.first { it.first }.second)
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
}
