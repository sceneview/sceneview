package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the Geospatial screens say when the AR session itself fails. The mapper matches ARCore's
 * exceptions by simple name, so the stand-ins below carry the real names.
 */
class ArSessionErrorTest {

    private class FatalException(message: String? = null) : RuntimeException(message)
    private class FineLocationPermissionNotGrantedException : SecurityException("fine location")
    private class UnsupportedConfigurationException : RuntimeException()
    private class UnavailableArcoreNotInstalledException : RuntimeException()
    private class CameraNotAvailableException : RuntimeException()
    private class MissingGlContextException : RuntimeException("No GL context is current.")

    /** A session failure is never a Cloud problem: those come back as an Earth state (#3262). */
    private fun assertNoCloudBlame(sentence: String) {
        listOf("cloud", "key", "visual positioning", "VPS").forEach { word ->
            assertFalse("\"$sentence\" blames \"$word\"", sentence.contains(word, ignoreCase = true))
        }
    }

    @Test
    fun `an internal ARCore failure does not send the user to Google Cloud`() {
        val sentence = friendlyArSessionError(FatalException())
        assertNoCloudBlame(sentence)
        assertFalse(sentence.contains("FatalException"))
        // The same class with a message is still ARCore's internals, not copy for a user.
        assertEquals(sentence, friendlyArSessionError(FatalException("native status -1")))
    }

    @Test
    fun `a missing location permission is named, not reported as a camera permission`() {
        val sentence = friendlyArSessionError(FineLocationPermissionNotGrantedException())
        assertTrue(sentence, sentence.contains("location", ignoreCase = true))
        assertFalse(sentence, sentence.contains("camera", ignoreCase = true))
    }

    @Test
    fun `a device that cannot run the feature is told so, without a Cloud detour`() {
        listOf(UnsupportedConfigurationException(), UnavailableArcoreNotInstalledException())
            .map(::friendlyArSessionError)
            .forEach { sentence ->
                assertTrue(sentence, sentence.contains("isn't available on this phone"))
                assertNoCloudBlame(sentence)
            }
    }

    @Test
    fun `a busy camera asks to close the other camera apps`() {
        val sentence = friendlyArSessionError(CameraNotAvailableException())
        assertTrue(sentence, sentence.contains("close other camera apps"))
    }

    @Test
    fun `a plain SecurityException is the camera permission`() {
        val sentence = friendlyArSessionError(SecurityException("camera"))
        assertTrue(sentence, sentence.contains("camera permission"))
    }

    @Test
    fun `an unknown failure with a message keeps ARCore's own sentence`() {
        assertEquals(
            "No GL context is current.",
            friendlyArSessionError(MissingGlContextException()),
        )
    }

    @Test
    fun `no failure at all still yields a sentence, never a class name`() {
        val sentence = friendlyArSessionError(null)
        assertTrue(sentence.isNotBlank())
        assertNoCloudBlame(sentence)
    }
}
