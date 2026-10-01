package io.github.sceneview.ar.arcore

import com.google.ar.core.exceptions.DeadlineExceededException
import com.google.ar.core.exceptions.FatalException
import com.google.ar.core.exceptions.NotYetAvailableException
import com.google.ar.core.exceptions.ResourceExhaustedException
import android.util.Log
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * `Frame.cameraImage()` must return `null`, never throw, for the three "no image for this
 * frame" conditions ARCore reports by exception.
 *
 * Play vitals, 4.47.0: the AR Image demo stored the last `Frame` and acquired its camera image
 * from a button tap. By then the frame was no longer current, `acquireCameraImage` threw
 * `DeadlineExceededException`, and `cameraImage()` only caught `NotYetAvailableException`, so
 * the app died. The ML Object Label demo re-threw the same exceptions from its render callback.
 *
 * `com.google.ar.core.Frame` is JNI-bound and cannot be instantiated on the JVM, so the mapping
 * lives in [acquireCpuImageOrNull], which `cameraImage()` delegates to, and is exercised here
 * directly. Robolectric only provides `android.util.Log`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CameraImageAcquisitionTest {

    @Test
    fun `returns the acquired value when ARCore has an image`() {
        assertEquals("image", acquireCpuImageOrNull { "image" })
    }

    @Test
    fun `stale frame maps to null`() {
        assertNull(acquireCpuImageOrNull<String> { throw DeadlineExceededException("stale") })
    }

    @Test
    fun `full image pool maps to null and warns`() {
        ShadowLog.clear()
        assertNull(acquireCpuImageOrNull<String> { throw ResourceExhaustedException("pool") })
        // A full pool usually means the caller leaks images: the null must not hide it.
        assertTrue(
            "ResourceExhausted must be logged at WARN",
            ShadowLog.getLogsForTag(CPU_IMAGE_LOG_TAG).any { it.type == Log.WARN }
        )
    }

    @Test
    fun `stale frame and warm-up stay silent`() {
        ShadowLog.clear()
        acquireCpuImageOrNull<String> { throw DeadlineExceededException("stale") }
        acquireCpuImageOrNull<String> { throw NotYetAvailableException("warm-up") }
        assertTrue(ShadowLog.getLogsForTag(CPU_IMAGE_LOG_TAG).isEmpty())
    }

    @Test
    fun `warm-up maps to null`() {
        assertNull(acquireCpuImageOrNull<String> { throw NotYetAvailableException("warm-up") })
    }

    @Test
    fun `other ARCore failures still propagate`() {
        // A dead session or a programming error must not be hidden behind a silent null.
        val thrown = runCatching {
            acquireCpuImageOrNull<String> { throw FatalException("dead") }
        }.exceptionOrNull()
        assertTrue("FatalException must propagate, got $thrown", thrown is FatalException)
    }
}
