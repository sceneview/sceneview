package io.github.sceneview.ar.arcore

import com.google.ar.core.exceptions.DeadlineExceededException
import com.google.ar.core.exceptions.FatalException
import com.google.ar.core.exceptions.NotYetAvailableException
import com.google.ar.core.exceptions.ResourceExhaustedException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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
 * lives in [acquireCpuImageOrNull] and is exercised here directly; a source check pins that
 * `cameraImage()` goes through it.
 */
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
    fun `full image pool maps to null`() {
        assertNull(acquireCpuImageOrNull<String> { throw ResourceExhaustedException("pool") })
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

    @Test
    fun `cameraImage goes through the null mapping`() {
        val source = File("src/main/java/io/github/sceneview/ar/arcore/Frame.kt").readText()
        assertTrue(
            "`Frame.cameraImage()` must wrap acquireCameraImage() in acquireCpuImageOrNull",
            Regex(
                """fun\s+Frame\.cameraImage\(\)\s*:\s*Image\?\s*=\s*""" +
                    """acquireCpuImageOrNull\s*\{\s*acquireCameraImage\(\)\s*}"""
            ).containsMatchIn(source)
        )
    }

    @Test
    fun `KDoc samples never acquire from a stored frame off-thread`() {
        // The old samples showed `withContext(Dispatchers.Default) { frame.captureCameraBitmap() }`
        // from a button coroutine: the exact shape that crashed the demo.
        val antiPattern = Regex("""withContext\([^)]*\)\s*\{\s*frame\.captureCameraBitmap\(\)""")
        listOf("CameraImageBitmap.kt", "RuntimeAugmentedImageDatabase.kt").forEach { name ->
            val source = File("src/main/java/io/github/sceneview/ar/arcore/$name").readText()
            assertFalse("$name KDoc still shows the stale-frame capture", antiPattern.containsMatchIn(source))
        }
    }
}
