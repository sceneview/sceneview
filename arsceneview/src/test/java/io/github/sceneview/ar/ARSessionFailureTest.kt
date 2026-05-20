package io.github.sceneview.ar

import com.google.ar.core.exceptions.AnchorNotSupportedForHostingException
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.CloudAnchorsNotConfiguredException
import com.google.ar.core.exceptions.DataInvalidFormatException
import com.google.ar.core.exceptions.DataUnsupportedVersionException
import com.google.ar.core.exceptions.DeadlineExceededException
import com.google.ar.core.exceptions.FatalException
import com.google.ar.core.exceptions.FineLocationPermissionNotGrantedException
import com.google.ar.core.exceptions.GooglePlayServicesLocationLibraryNotLinkedException
import com.google.ar.core.exceptions.ImageInsufficientQualityException
import com.google.ar.core.exceptions.MetadataNotFoundException
import com.google.ar.core.exceptions.MissingGlContextException
import com.google.ar.core.exceptions.NotTrackingException
import com.google.ar.core.exceptions.NotYetAvailableException
import com.google.ar.core.exceptions.PlaybackFailedException
import com.google.ar.core.exceptions.RecordingFailedException
import com.google.ar.core.exceptions.ResourceExhaustedException
import com.google.ar.core.exceptions.SessionNotPausedException
import com.google.ar.core.exceptions.SessionPausedException
import com.google.ar.core.exceptions.SessionUnsupportedException
import com.google.ar.core.exceptions.TextureNotSetException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import com.google.ar.core.exceptions.UnsupportedConfigurationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the [ARSessionFailure] taxonomy (#1759).
 *
 * Covers:
 *  - every ARCore exception subclass maps to its dedicated [ARSessionFailure] subtype;
 *  - the unmapped catch-all path lands in [ARSessionFailure.Other];
 *  - the original [Exception] is preserved on [ARSessionFailure.cause] for every subtype.
 *
 * Pure JVM — `ARSessionFailure.from` is a `when` over `is` checks, no JNI, no ARCore session.
 */
class ARSessionFailureTest {

    @Test
    fun `from() maps every ARCore exception subclass to its typed ARSessionFailure subtype`() {
        val cases: List<Pair<Exception, Class<out ARSessionFailure>>> = listOf(
            UnavailableArcoreNotInstalledException("not installed")
                to ARSessionFailure.ArCoreNotInstalled::class.java,
            UnavailableUserDeclinedInstallationException("declined")
                to ARSessionFailure.UserDeclinedInstall::class.java,
            UnavailableApkTooOldException("apk too old")
                to ARSessionFailure.ApkTooOld::class.java,
            UnavailableSdkTooOldException("sdk too old")
                to ARSessionFailure.SdkTooOld::class.java,
            UnavailableDeviceNotCompatibleException("not compatible")
                to ARSessionFailure.DeviceNotCompatible::class.java,
            FineLocationPermissionNotGrantedException("no fine location")
                to ARSessionFailure.FineLocationMissing::class.java,
            GooglePlayServicesLocationLibraryNotLinkedException("no gms loc")
                to ARSessionFailure.GooglePlayServicesLocationLibraryNotLinked::class.java,
            CameraNotAvailableException("camera busy")
                to ARSessionFailure.CameraNotAvailable::class.java,
            TextureNotSetException("no texture")
                to ARSessionFailure.TextureNotSet::class.java,
            MissingGlContextException("no gl")
                to ARSessionFailure.MissingGlContext::class.java,
            ResourceExhaustedException("quota out")
                to ARSessionFailure.ResourceExhausted::class.java,
            DeadlineExceededException("timeout")
                to ARSessionFailure.DeadlineExceeded::class.java,
            FatalException("fatal")
                to ARSessionFailure.Fatal::class.java,
            CloudAnchorsNotConfiguredException("no key")
                to ARSessionFailure.CloudAnchorsNotConfigured::class.java,
            AnchorNotSupportedForHostingException("bad anchor")
                to ARSessionFailure.AnchorNotSupportedForHosting::class.java,
            ImageInsufficientQualityException("blurry")
                to ARSessionFailure.ImageInsufficientQuality::class.java,
            RecordingFailedException("disk full")
                to ARSessionFailure.RecordingFailed::class.java,
            PlaybackFailedException("bad mp4")
                to ARSessionFailure.PlaybackFailed::class.java,
            DataInvalidFormatException("bad framing")
                to ARSessionFailure.DataInvalidFormat::class.java,
            DataUnsupportedVersionException("future version")
                to ARSessionFailure.DataUnsupportedVersion::class.java,
            MetadataNotFoundException("no key")
                to ARSessionFailure.MetadataNotFound::class.java,
            SessionUnsupportedException("config rejected")
                to ARSessionFailure.SessionUnsupported::class.java,
            SessionPausedException("paused")
                to ARSessionFailure.SessionPaused::class.java,
            SessionNotPausedException("not paused")
                to ARSessionFailure.SessionNotPaused::class.java,
            NotTrackingException("not tracking")
                to ARSessionFailure.NotTracking::class.java,
            NotYetAvailableException("not yet")
                to ARSessionFailure.NotYetAvailable::class.java,
            UnsupportedConfigurationException("bad config")
                to ARSessionFailure.UnsupportedConfiguration::class.java,
        )

        cases.forEach { (exception, expected) ->
            val failure = ARSessionFailure.from(exception)
            assertTrue(
                "Expected ${exception::class.java.simpleName} → ${expected.simpleName} " +
                    "but got ${failure::class.java.simpleName}",
                expected.isInstance(failure),
            )
            assertSame(
                "The mapped failure must preserve the original exception on `cause` " +
                    "(${exception::class.java.simpleName}).",
                exception,
                failure.cause,
            )
        }
    }

    @Test
    fun `from() routes unknown exceptions to Other`() {
        val ex = IllegalStateException("non-arcore failure")
        val failure = ARSessionFailure.from(ex)
        assertEquals(ARSessionFailure.Other::class.java, failure::class.java)
        assertSame(ex, failure.cause)
    }
}
