package io.github.sceneview.demo.telemetry

import android.content.Context
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARSessionFailure

/** `sample_id` of the AR session the AR View tab hosts outside any sample. */
const val AR_VIEW_SAMPLE_ID = "ar_view"

/**
 * Version name of the installed "Google Play Services for AR", or [ArFunnelLog.UNKNOWN_VERSION].
 * The ARCore SDK's manifest already declares the package query this needs.
 */
fun arCoreVersionName(context: Context): String =
    runCatching { context.packageManager.getPackageInfo("com.google.ar.core", 0).versionName }
        .getOrNull() ?: ArFunnelLog.UNKNOWN_VERSION

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
 * - `ar_session_failed` logs once per failure reason, [motionSensorSilent] included. ARCore
 *   retries a session it could not create on every resume (`create()` at ON_CREATE, then
 *   `resume()` finds no session and creates again), so one visit to an AR screen on a device
 *   that cannot run AR reports the same failure twice, then once more per return from the
 *   background.
 *
 * Three things the funnel cannot say are filed with the crash reports instead, as problems the
 * app survived (#4510) — no new analytics event, the taxonomy stays the one shared with iOS:
 * - a session failure with no code of its own (`unknown`, `fatal`), with the exception that
 *   caused it, once;
 * - a session that delivers camera frames for [TRACKING_STALL_MS] and never tracks — the screen
 *   that stays on "Getting ready" over a live camera image — with the camera's tracking state
 *   and ARCore's own reason, once ([ArTrackingStalled]);
 * - a resumed session that delivers no frame at all for [FRAME_STALL_MS] — a frame loop that
 *   keeps throwing leaves the app nothing else to see — once ([ArFramesStalled]).
 *
 * The first is the exception as it was thrown; the two stalls carry the sample id, a duration and
 * the ARCore version. None of the three carries an image, a pose, or anything about the place or
 * the person, and none changes anything on screen.
 *
 * @param monotonicMillis the clock the stalls are measured on; injected by the tests.
 * @param arCoreVersion the installed "Google Play Services for AR" version name, read only when
 *   a stall is reported.
 */
class ArFunnelLog(
    private val analytics: () -> DemoAnalytics = { Telemetry.analytics },
    private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val arCoreVersion: () -> String = { UNKNOWN_VERSION },
) {
    var sampleId: String = AR_VIEW_SAMPLE_ID

    private var tracked = false
    private var placedOnce = false
    private val failuresLogged = mutableSetOf<String>()

    private var lastUntrackedFrameAt = NO_FRAME
    private var untrackedForMillis = 0L
    private var stallReported = false
    private var lastFailureReason: TrackingFailureReason? = null

    private var awaitingFrameSince = NO_FRAME
    private var framesStallReported = false

    fun sessionCreated() = analytics().log(AnalyticsEvent.ArSessionCreated(sampleId))

    /** The session runs from here on: a frame is due. */
    fun sessionResumed() {
        awaitingFrameSince = monotonicMillis()
    }

    /** A paused session owes no frame. */
    fun sessionPaused() {
        awaitingFrameSince = NO_FRAME
    }

    /**
     * Called every [FRAME_WATCH_MS] by the screen that hosts the session, frames or not: reports,
     * once, a resumed session whose last frame (or whose resume) is [FRAME_STALL_MS] old.
     */
    fun framesExpected() {
        if (framesStallReported || awaitingFrameSince == NO_FRAME) return
        val waited = monotonicMillis() - awaitingFrameSince
        if (waited < FRAME_STALL_MS) return
        framesStallReported = true
        analytics().recordNonFatal(ArFramesStalled(sampleId, waited / 1_000, arCoreVersion()))
    }

    /** Called on every frame (60 Hz): a clock read, then a boolean check once tracking was reached. */
    fun frameTracked(state: TrackingState) {
        if (awaitingFrameSince != NO_FRAME) awaitingFrameSince = monotonicMillis()
        if (tracked) return
        if (state != TrackingState.TRACKING) {
            if (!stallReported) countUntrackedFrame(state)
            return
        }
        tracked = true
        analytics().log(AnalyticsEvent.ArTrackingReady(sampleId))
    }

    /**
     * Adds this frame to the time spent receiving frames without ever tracking. Only the time
     * between two frames close together counts, so a session left in the background for a
     * minute is not a stalled one.
     */
    private fun countUntrackedFrame(state: TrackingState) {
        val now = monotonicMillis()
        val sinceLast = now - lastUntrackedFrameAt
        if (lastUntrackedFrameAt != NO_FRAME && sinceLast in 0..MAX_FRAME_GAP_MS) {
            untrackedForMillis += sinceLast
        }
        lastUntrackedFrameAt = now
        if (untrackedForMillis < TRACKING_STALL_MS) return
        stallReported = true
        analytics().recordNonFatal(
            ArTrackingStalled(
                sampleId = sampleId,
                trackingState = state.name.lowercase(),
                reason = lastFailureReason?.name?.lowercase() ?: "none",
                seconds = untrackedForMillis / 1_000,
                arCore = arCoreVersion(),
            ),
        )
    }

    fun trackingFailureChanged(reason: TrackingFailureReason?) {
        lastFailureReason = reason
        reason ?: return
        analytics().log(AnalyticsEvent.ArTrackingLost(sampleId, reason.name.lowercase()))
    }

    fun placed() {
        if (placedOnce) return
        placedOnce = true
        analytics().log(AnalyticsEvent.ArFirstPlacement(sampleId))
        // Never shown here: the interstitial waits until the AR session is over.
        io.github.sceneview.demo.support.SupportPrompt.onArPlacement()
    }

    fun sessionFailed(failure: ARSessionFailure) {
        val reason = failureReason(failure)
        if (!failedOnce(reason)) return
        // `unknown` and `fatal` name no cause: the exception itself is the only lead.
        if (failure is ARSessionFailure.Other || failure is ARSessionFailure.Fatal) {
            analytics().recordNonFatal(failure.cause)
        }
    }

    /**
     * The session never tracked and the phone's gyroscope was measured silent (#4522): ARCore
     * raises no failure for it, so the screen that measured it reports it here. One
     * `ar_session_failed` with [MOTION_SENSOR_SILENT_REASON], once like every other reason.
     */
    fun motionSensorSilent() {
        failedOnce(MOTION_SENSOR_SILENT_REASON)
    }

    /** Logs `ar_session_failed` for [reason] unless this funnel already did; `true` when it logged. */
    private fun failedOnce(reason: String): Boolean {
        if (!failuresLogged.add(reason)) return false
        analytics().log(AnalyticsEvent.ArSessionFailed(sampleId, reason))
        return true
    }

    /**
     * An AR session that received camera frames for [seconds] and never reached tracking.
     * [trackingState] is the camera's `TrackingState` on the reporting frame and [reason] ARCore's
     * `TrackingFailureReason`, both in lower case; [reason] is `none` when ARCore gave none —
     * with `paused`, the silent stall no coaching card explains.
     */
    class ArTrackingStalled(
        val sampleId: String,
        val trackingState: String,
        val reason: String,
        val seconds: Long,
        val arCore: String,
    ) : RuntimeException(
        "AR never tracked in $sampleId after $seconds s of frames, " +
            "state=$trackingState reason=$reason arcore=$arCore",
    )

    /** A resumed AR session that delivered no frame for [seconds]. */
    class ArFramesStalled(val sampleId: String, val seconds: Long, val arCore: String) :
        RuntimeException("AR delivered no frame in $sampleId for $seconds s, arcore=$arCore")

    companion object {
        /** Frames without tracking for this long, and the session is reported as stalled. */
        const val TRACKING_STALL_MS = 10_000L

        /** Two frames further apart than this are not the same stretch of frames. */
        const val MAX_FRAME_GAP_MS = 1_000L

        /** A resumed session with no frame for this long is reported as delivering none. */
        const val FRAME_STALL_MS = 10_000L

        /** How often the hosting screen calls [framesExpected]. */
        const val FRAME_WATCH_MS = 2_000L

        const val UNKNOWN_VERSION = "unknown"

        /**
         * The `ar_session_failed` reason of a session that cannot start because the phone's
         * gyroscope delivers nothing — the one reason ARCore has no exception for.
         */
        const val MOTION_SENSOR_SILENT_REASON = "motion_sensor_silent"

        private const val NO_FRAME = -1L

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
