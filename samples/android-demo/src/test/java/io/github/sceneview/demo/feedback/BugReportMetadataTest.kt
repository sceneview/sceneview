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

    @Test
    fun `always captures the core device and app fields`() {
        val metadata = captureBugReportMetadata(context, demoId = null)
        assertEquals(
            "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            metadata["App version"],
        )
        assertTrue(metadata.containsKey("Device"))
        assertTrue(metadata.containsKey("Android"))
        assertTrue(metadata.containsKey("ABI"))
        assertTrue(metadata.containsKey("Screen"))
        assertTrue(metadata.containsKey("Locale"))
    }

    @Test
    fun `names the current demo when one is open and omits the row otherwise`() {
        assertEquals(
            "model-viewer",
            captureBugReportMetadata(context, demoId = "model-viewer")["Demo"],
        )
        assertFalse(captureBugReportMetadata(context, demoId = null).containsKey("Demo"))
        assertFalse(captureBugReportMetadata(context, demoId = "  ").containsKey("Demo"))
    }

    @Test
    fun `logcat collection never throws even when the binary is unavailable`() {
        // Under Robolectric `logcat` may not exist / return nothing — the
        // report must degrade to metadata + note, not crash.
        collectAppLogcat()
    }
}
