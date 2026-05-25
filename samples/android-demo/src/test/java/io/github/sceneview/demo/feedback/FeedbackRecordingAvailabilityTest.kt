package io.github.sceneview.demo.feedback

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/**
 * Regression test for #2188: when the manifest omits
 * `FOREGROUND_SERVICE_MEDIA_PROJECTION` (the #2120 catch-22), the feedback
 * recorder must report screen recording as unavailable on Android 14+ rather
 * than letting the foreground service crash the app with
 * `ForegroundServiceDidNotStartInTimeException`.
 *
 * The Android `pm` would refuse to install an AAB whose manifest declared the
 * permission without a matching Play Console FGS declaration, so the only
 * thing the app code can do is detect the missing permission and route the
 * user to a text-only feedback path.
 */
@RunWith(RobolectricTestRunner::class)
class FeedbackRecordingAvailabilityTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    @Config(sdk = [33])
    fun `recording is reported available on pre-Android-14 even without the typed FGS permission`() {
        // Pre-14: the typed FGS permission isn't required at all; the typed
        // startForeground call degrades gracefully via the catch in
        // goForeground(). Recording is still attempted.
        assertTrue(
            "Android 13 (no typed FGS) must NOT refuse recording up-front",
            FeedbackRecordingService.isRecordingAvailable(context),
        )
    }

    @Test
    @Config(sdk = [34])
    fun `recording is reported unavailable on Android 14+ when the typed FGS permission is missing`() {
        // The test manifest (samples/android-demo/src/main/AndroidManifest.xml)
        // intentionally omits FOREGROUND_SERVICE_MEDIA_PROJECTION (#2120 to
        // unblock Play Store). On Android 14+ this MUST be detected so the
        // flow can short-circuit the consent step before
        // startForegroundService crashes the app.
        assertFalse(
            "Android 14 without FOREGROUND_SERVICE_MEDIA_PROJECTION must refuse recording up-front",
            FeedbackRecordingService.isRecordingAvailable(context),
        )
    }

    @Test
    fun `the launcher publishes the recording-unavailable sentinel rather than asking for permissions`() {
        // The fix routes through FeedbackRecorder.setFailed with this exact
        // sentinel so the FeedbackFlow can recognise the case and skip the
        // consent step. If the sentinel ever changes, the flow's branch
        // matches by name and would silently regress to the generic
        // "recording failed" UI — which would in turn dead-end on a no-op
        // Retry button. Anchor the sentinel so a rename here forces an
        // explicit reconciliation with FeedbackFlow.
        org.junit.Assert.assertEquals(
            "recording-unavailable",
            FeedbackRecorder.FAILURE_RECORDING_UNAVAILABLE,
        )
    }

    /**
     * Static reachability: on a recent SDK with no override, the helper must
     * not call back into Android 14-only `PackageManager.checkPermission`
     * APIs without a guard — the helper is invoked at composition time, so a
     * NoClassDefFoundError or SecurityException here would crash every
     * composition that observes the feedback state, not just the recording
     * flow itself.
     */
    @Test
    fun `availability check never throws`() {
        // Should be a pure read of a manifest permission grant — no I/O, no
        // service call, no system-side prompt.
        FeedbackRecordingService.isRecordingAvailable(context)

        // Reset SDK to whatever the runner gave us; the check is idempotent.
        ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", 34)
        FeedbackRecordingService.isRecordingAvailable(context)
        ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", 33)
        FeedbackRecordingService.isRecordingAvailable(context)
    }
}
