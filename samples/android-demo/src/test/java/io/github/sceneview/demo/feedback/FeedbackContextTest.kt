package io.github.sceneview.demo.feedback

import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Covers [captureFeedbackContext] — the device / app / route snapshot attached
 * to every feedback submission (#1934).
 */
@RunWith(RobolectricTestRunner::class)
class FeedbackContextTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @After
    fun tearDown() {
        // Shared process-wide state — reset so tests stay independent.
        FeedbackRecorder.currentDemoId = null
    }

    @Test
    fun `always captures the core device and app fields`() {
        val ctx = captureFeedbackContext(context, demoId = null)

        assertTrue(ctx.containsKey("appVersion"))
        assertTrue(ctx.containsKey("appVersionCode"))
        assertTrue(ctx.containsKey("androidVersion"))
        assertTrue(ctx.containsKey("sdkInt"))
        assertTrue(ctx.containsKey("device"))
        assertTrue(ctx.containsKey("locale"))
    }

    @Test
    fun `on a tab screen the route is list and no demo id is set`() {
        val ctx = captureFeedbackContext(context, demoId = null)

        assertEquals("list", ctx["route"])
        assertFalse(ctx.containsKey("demo"))
    }

    @Test
    fun `inside a demo the demo id and the demo route are both captured`() {
        val ctx = captureFeedbackContext(context, demoId = "model-viewer")

        assertEquals("model-viewer", ctx["demo"])
        assertEquals("demo/model-viewer", ctx["route"])
    }

    @Test
    fun `a blank demo id is treated as a tab screen`() {
        val ctx = captureFeedbackContext(context, demoId = "  ")

        assertEquals("list", ctx["route"])
        assertFalse(ctx.containsKey("demo"))
    }

    @Test
    fun `the demo id defaults to the recorder's tracked route`() {
        FeedbackRecorder.currentDemoId = "lighting"

        val ctx = captureFeedbackContext(context)

        assertEquals("lighting", ctx["demo"])
        assertEquals("demo/lighting", ctx["route"])
    }
}
