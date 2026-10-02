package io.github.sceneview.demo.telemetry

import com.google.ar.core.TrackingFailureReason
import io.github.sceneview.ar.ARSessionFailure

/** `sample_id` of the AR session the AR View tab hosts outside any sample. */
const val AR_VIEW_SAMPLE_ID = "ar_view"

/**
 * The five AR funnel events of `docs/docs/recipes/measure-ar-funnel.md`, fed from ARSceneView's
 * public callbacks. One instance per AR session.
 *
 * - `ar_tracking_ready` logs once, on the first frame whose camera is TRACKING ([frameTracked],
 *   from `onSessionUpdated`). Not from `onTrackingFailureChanged(null)`: that callback only
 *   fires on a *change* of failure reason, so a session that tracks at once never calls it.
 * - `ar_first_placement` logs once.
 * - `ar_tracking_lost` logs every time ARCore reports a new failure reason (ARSceneView
 *   already de-duplicates).
 * - `ar_session_failed` logs once per failure reason. ARCore retries a session it could not
 *   create on every resume (`create()` at ON_CREATE, then `resume()` finds no session and
 *   creates again), so one visit to an AR screen on a device that cannot run AR reports the
 *   same failure twice, then once more per return from the background.
 */
class ArFunnelLog(private val analytics: () -> DemoAnalytics = { Telemetry.analytics }) {
    var sampleId: String = AR_VIEW_SAMPLE_ID

    private var tracked = false
    private var placedOnce = false
    private val failuresLogged = mutableSetOf<String>()

    fun sessionCreated() = analytics().log(AnalyticsEvent.ArSessionCreated(sampleId))

    /** Called on every frame (60 Hz): a single boolean check once tracking was reached. */
    fun frameTracked(isTracking: Boolean) {
        if (tracked || !isTracking) return
        tracked = true
        analytics().log(AnalyticsEvent.ArTrackingReady(sampleId))
    }

    fun trackingFailureChanged(reason: TrackingFailureReason?) {
        reason ?: return
        analytics().log(AnalyticsEvent.ArTrackingLost(sampleId, reason.name.lowercase()))
    }

    fun placed() {
        if (placedOnce) return
        placedOnce = true
        analytics().log(AnalyticsEvent.ArFirstPlacement(sampleId))
    }

    fun sessionFailed(failure: ARSessionFailure) {
        val reason = failureReason(failure)
        if (!failuresLogged.add(reason)) return
        analytics().log(AnalyticsEvent.ArSessionFailed(sampleId, reason))
    }

    companion object {
        /**
         * A stable snake_case code per failure type, the same vocabulary as iOS. Spelled out
         * rather than derived from the class name, which R8 renames in a release build; the
         * `when` is exhaustive, so a failure type added to ARSceneView fails this build until
         * it gets a code.
         */
        @Suppress("CyclomaticComplexMethod")
        fun failureReason(failure: ARSessionFailure): String = when (failure) {
            is ARSessionFailure.ArCoreNotInstalled -> "arcore_not_installed"
            is ARSessionFailure.UserDeclinedInstall -> "user_declined_install"
            is ARSessionFailure.ApkTooOld -> "apk_too_old"
            is ARSessionFailure.SdkTooOld -> "sdk_too_old"
            is ARSessionFailure.DeviceNotCompatible -> "device_not_compatible"
            is ARSessionFailure.FineLocationMissing -> "fine_location_missing"
            is ARSessionFailure.GooglePlayServicesLocationLibraryNotLinked -> "location_library_not_linked"
            is ARSessionFailure.CameraNotAvailable -> "camera_not_available"
            is ARSessionFailure.TextureNotSet -> "texture_not_set"
            is ARSessionFailure.MissingGlContext -> "missing_gl_context"
            is ARSessionFailure.ResourceExhausted -> "resource_exhausted"
            is ARSessionFailure.DeadlineExceeded -> "deadline_exceeded"
            is ARSessionFailure.Fatal -> "fatal"
            is ARSessionFailure.CloudAnchorsNotConfigured -> "cloud_anchors_not_configured"
            is ARSessionFailure.AnchorNotSupportedForHosting -> "anchor_not_supported_for_hosting"
            is ARSessionFailure.ImageInsufficientQuality -> "image_insufficient_quality"
            is ARSessionFailure.RecordingFailed -> "recording_failed"
            is ARSessionFailure.PlaybackFailed -> "playback_failed"
            is ARSessionFailure.DataInvalidFormat -> "data_invalid_format"
            is ARSessionFailure.DataUnsupportedVersion -> "data_unsupported_version"
            is ARSessionFailure.MetadataNotFound -> "metadata_not_found"
            is ARSessionFailure.SessionUnsupported -> "session_unsupported"
            is ARSessionFailure.SessionPaused -> "session_paused"
            is ARSessionFailure.SessionNotPaused -> "session_not_paused"
            is ARSessionFailure.NotTracking -> "not_tracking"
            is ARSessionFailure.NotYetAvailable -> "not_yet_available"
            is ARSessionFailure.UnsupportedConfiguration -> "unsupported_configuration"
            is ARSessionFailure.Other -> "unknown"
        }
    }
}
