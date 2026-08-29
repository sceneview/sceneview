package io.github.sceneview.demo.feedback

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.sceneview.demo.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Covers [captureBugReportMetadata] — the device / app context snapshot a
 * maintainer sees at the top of every shared bug report.
 */
@RunWith(RobolectricTestRunner::class)
class BugReportMetadataTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** The demo route as declared by the `NavHost`, query argument included. */
    private val demoRoute = "demo/{id}?model={model}"

    @Test
    fun `always captures the core device and app fields`() {
        val metadata = captureBugReportMetadata(context, ReportScreen())
        assertEquals(
            "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            metadata["App version"],
        )
        assertTrue(metadata.containsKey("Device"))
        assertTrue(metadata.containsKey("Android"))
        assertTrue(metadata.containsKey("ABI"))
        assertTrue(metadata.containsKey("Display"))
        assertTrue(metadata.containsKey("Locale"))
    }

    @Test
    fun `always names a screen, even when nothing identifies it`() {
        // The row a maintainer reads first: it must never be missing (#3390).
        assertEquals("unknown", screenRow(ReportScreen()))
        assertEquals("Showcase tab", screenRow(ReportScreen(rootScreen = "Showcase tab")))
        // A demo on top wins over both the root screen and the raw route.
        assertEquals(
            "Demo · model-viewer",
            screenRow(
                ReportScreen(
                    demoId = "model-viewer",
                    rootScreen = "Showcase tab",
                    route = demoRoute,
                ),
            ),
        )
        // Last resort: the raw route, minus its query arguments.
        assertEquals("demo/{id}", screenRow(ReportScreen(route = demoRoute)))
    }

    private fun screenRow(screen: ReportScreen): String? =
        captureBugReportMetadata(context, screen)["Screen"]

    @Test
    fun `names the current demo when one is open and omits the row otherwise`() {
        assertEquals(
            "model-viewer",
            captureBugReportMetadata(context, ReportScreen(demoId = "model-viewer"))["Demo"],
        )
        assertFalse(captureBugReportMetadata(context, ReportScreen()).containsKey("Demo"))
        assertFalse(
            captureBugReportMetadata(context, ReportScreen(demoId = "  ")).containsKey("Demo"),
        )
    }

    @Test
    fun `logcat collection never throws even when the binary is unavailable`() {
        // Under Robolectric `logcat` may not exist / return nothing — the
        // report must degrade to metadata + note, not crash.
        collectAppLogcat()
    }
}
