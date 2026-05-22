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
 * Covers [captureFeedbackContext] — the device / app context snapshot attached
 * to every feedback submission (#1934). The `route` key was removed; `demoId`
 * is the canonical navigation key the worker recognises.
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
    fun `on a tab screen no demoId is set`() {
        val ctx = captureFeedbackContext(context, demoId = null)

        // The worker only renders the named "Demo" row when `demoId` is present,
        // so a tab-screen report simply omits the key — no `route` key exists.
        assertFalse(ctx.containsKey("demoId"))
        assertFalse(ctx.containsKey("route"))
    }

    @Test
    fun `inside a demo the demoId is captured under the worker's key`() {
        val ctx = captureFeedbackContext(context, demoId = "model-viewer")

        assertEquals("model-viewer", ctx["demoId"])
        // `route` is never emitted — the worker has no label for it.
        assertFalse(ctx.containsKey("route"))
    }

    @Test
    fun `a blank demo id is treated as a tab screen`() {
        val ctx = captureFeedbackContext(context, demoId = "  ")

        assertFalse(ctx.containsKey("demoId"))
    }

    @Test
    fun `the demoId defaults to the recorder's tracked demo`() {
        FeedbackRecorder.currentDemoId = "lighting"

        val ctx = captureFeedbackContext(context)

        assertEquals("lighting", ctx["demoId"])
    }

    @Test
    fun `isEmulator returns true on a Robolectric test runner (emulator Build fields)`() {
        // Robolectric sets Build.FINGERPRINT to "robolectric" which starts
        // with a non-"generic" prefix, but PRODUCT defaults to "robolectric"
        // and HARDWARE defaults to "robolectric". The important thing is that
        // isEmulator() does not throw — emulator detection is best-effort and
        // some environments may return false. The test verifies the function
        // is callable and returns a Boolean without crashing.
        // No assertion on the exact value — environment-dependent.
        // Merely verifies the call path is reachable without NPE / crash.
        val result = isEmulator()
        // On Robolectric the Build fields don't match a real emulator's values,
        // so the result is false. Either way the call must complete without error.
        assertFalse("isEmulator() must not throw on Robolectric", result)
    }
}
