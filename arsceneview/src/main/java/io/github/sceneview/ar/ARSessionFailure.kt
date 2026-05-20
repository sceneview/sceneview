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

/**
 * Typed taxonomy of ARCore session failures — covers all 25 ARCore exception subclasses plus an
 * [Other] escape hatch for anything ARCore adds in the future (#1759).
 *
 * Today every ARCore exception is lumped into a single `Exception` handed to
 * [ARSceneView][io.github.sceneview.ar.ARSceneView]'s `onSessionFailed` callback. Apps that want
 * to do retry vs fallback vs "install ARCore" vs "open Settings" routing have to string-match
 * on the exception class name — fragile and impossible to exhaustively check at compile time.
 *
 * `ARSessionFailure` is a sealed hierarchy: a `when` expression on it is **exhaustive**, so the
 * compiler catches the day a new failure mode is added and the app forgot to handle it. Wrap
 * any ARCore exception via [ARSessionFailure.from] (typically inside an
 * `onSessionFailed = { ex -> handle(ARSessionFailure.from(ex)) }` lambda).
 *
 * The original [cause] is preserved on every subtype so apps that need the raw stack trace can
 * still get at it.
 *
 * ### Categories
 *
 * | Group | Subtypes |
 * |---|---|
 * | Install / availability | [ArCoreNotInstalled], [UserDeclinedInstall], [ApkTooOld], [SdkTooOld], [DeviceNotCompatible] |
 * | Permissions            | [FineLocationMissing], [GooglePlayServicesLocationLibraryNotLinked] |
 * | Camera                 | [CameraNotAvailable], [TextureNotSet], [MissingGlContext] |
 * | Quota / runtime        | [ResourceExhausted], [DeadlineExceeded], [Fatal] |
 * | Cloud Anchor           | [CloudAnchorsNotConfigured], [AnchorNotSupportedForHosting] |
 * | Augmented image        | [ImageInsufficientQuality] |
 * | Recording / playback   | [RecordingFailed], [PlaybackFailed], [DataInvalidFormat], [DataUnsupportedVersion], [MetadataNotFound] |
 * | Session / config       | [SessionUnsupported], [SessionPaused], [SessionNotPaused], [NotTracking], [NotYetAvailable], [UnsupportedConfiguration] |
 * | Catch-all              | [Other] |
 *
 * ### Usage
 *
 * ```kotlin
 * ARSceneView(
 *     onSessionFailure = { failure ->
 *         when (failure) {
 *             is ARSessionFailure.ArCoreNotInstalled -> showInstallArCoreCta()
 *             is ARSessionFailure.UserDeclinedInstall -> showRetryCta()
 *             is ARSessionFailure.FineLocationMissing -> requestLocationPermission()
 *             is ARSessionFailure.CloudAnchorsNotConfigured -> showCloudKeySetupHelp()
 *             is ARSessionFailure.CameraNotAvailable -> openCameraSettings()
 *             is ARSessionFailure.ResourceExhausted -> showCloudQuotaErrorCta()
 *             is ARSessionFailure.Other -> reportToCrashlytics(failure.cause)
 *             else -> showGenericRetryCta()
 *         }
 *     }
 * ) { /* … */ }
 * ```
 */
public sealed class ARSessionFailure(public open val cause: Exception) {

    // ── Install / availability ───────────────────────────────────────────────

    /** ARCore APK is not installed on the device. Prompt the user to install Play Services for AR. */
    public data class ArCoreNotInstalled(override val cause: UnavailableArcoreNotInstalledException) :
        ARSessionFailure(cause)

    /** The user dismissed the ARCore install prompt. Offer a retry button. */
    public data class UserDeclinedInstall(override val cause: UnavailableUserDeclinedInstallationException) :
        ARSessionFailure(cause)

    /** The installed ARCore APK is too old. Trigger Play Services for AR update. */
    public data class ApkTooOld(override val cause: UnavailableApkTooOldException) :
        ARSessionFailure(cause)

    /** The app was built against a newer ARCore SDK than the device APK supports. */
    public data class SdkTooOld(override val cause: UnavailableSdkTooOldException) :
        ARSessionFailure(cause)

    /** Device hardware does not support ARCore. Disable AR entry points permanently. */
    public data class DeviceNotCompatible(override val cause: UnavailableDeviceNotCompatibleException) :
        ARSessionFailure(cause)

    // ── Permissions ──────────────────────────────────────────────────────────

    /**
     * `ACCESS_FINE_LOCATION` was not granted but `Config.GeospatialMode = ENABLED` was requested.
     * Re-run the runtime-permission flow or disable the Geospatial features.
     */
    public data class FineLocationMissing(override val cause: FineLocationPermissionNotGrantedException) :
        ARSessionFailure(cause)

    /**
     * Geospatial APIs need the Google Play Services Location library on the classpath. Add
     * `com.google.android.gms:play-services-location` to the app `build.gradle`.
     */
    public data class GooglePlayServicesLocationLibraryNotLinked(
        override val cause: GooglePlayServicesLocationLibraryNotLinkedException
    ) : ARSessionFailure(cause)

    // ── Camera ───────────────────────────────────────────────────────────────

    /**
     * Camera is held by another app or the session lost it on resume. Direct the user to close
     * other camera apps; retry the session.
     */
    public data class CameraNotAvailable(override val cause: CameraNotAvailableException) :
        ARSessionFailure(cause)

    /** The camera texture name was not set before the first frame. Internal — should not surface. */
    public data class TextureNotSet(override val cause: TextureNotSetException) :
        ARSessionFailure(cause)

    /** No OpenGL context was current on the calling thread. Internal — should not surface. */
    public data class MissingGlContext(override val cause: MissingGlContextException) :
        ARSessionFailure(cause)

    // ── Quota / runtime ──────────────────────────────────────────────────────

    /**
     * The Cloud Anchor / Geospatial API quota was exhausted (per-project / per-hour). Back off
     * and retry, or offer a paid-quota upgrade path.
     */
    public data class ResourceExhausted(override val cause: ResourceExhaustedException) :
        ARSessionFailure(cause)

    /** An ARCore network operation timed out. Suggest checking connectivity and retrying. */
    public data class DeadlineExceeded(override val cause: DeadlineExceededException) :
        ARSessionFailure(cause)

    /** Unrecoverable native ARCore error. Recreate the session. */
    public data class Fatal(override val cause: FatalException) :
        ARSessionFailure(cause)

    // ── Cloud Anchor ─────────────────────────────────────────────────────────

    /**
     * `Config.CloudAnchorMode = ENABLED` but the ARCore Cloud API key is missing from
     * `AndroidManifest.xml`. See `samples/android-demo/STREETSCAPE_SETUP.md`.
     */
    public data class CloudAnchorsNotConfigured(override val cause: CloudAnchorsNotConfiguredException) :
        ARSessionFailure(cause)

    /** The local anchor pose cannot be hosted as a Cloud Anchor (insufficient feature map quality). */
    public data class AnchorNotSupportedForHosting(override val cause: AnchorNotSupportedForHostingException) :
        ARSessionFailure(cause)

    // ── Augmented image ──────────────────────────────────────────────────────

    /**
     * The bitmap passed to [com.google.ar.core.AugmentedImageDatabase.addImage] does not have
     * enough features for tracking. Use a higher-contrast / more-textured reference image.
     */
    public data class ImageInsufficientQuality(override val cause: ImageInsufficientQualityException) :
        ARSessionFailure(cause)

    // ── Recording / playback ─────────────────────────────────────────────────

    /**
     * [com.google.ar.core.Session.startRecording] / [com.google.ar.core.Session.stopRecording]
     * failed. See [io.github.sceneview.ar.recording.ARRecorder.errorMessage] for the cause.
     */
    public data class RecordingFailed(override val cause: RecordingFailedException) :
        ARSessionFailure(cause)

    /** Playback dataset binding failed — bad path / unreadable MP4 / unsupported codec. */
    public data class PlaybackFailed(override val cause: PlaybackFailedException) :
        ARSessionFailure(cause)

    /** Recording MP4 is malformed at the framing level. */
    public data class DataInvalidFormat(override val cause: DataInvalidFormatException) :
        ARSessionFailure(cause)

    /** Recording MP4 was produced by a newer ARCore version than the device supports. */
    public data class DataUnsupportedVersion(override val cause: DataUnsupportedVersionException) :
        ARSessionFailure(cause)

    /** Track metadata key not found in the dataset. */
    public data class MetadataNotFound(override val cause: MetadataNotFoundException) :
        ARSessionFailure(cause)

    // ── Session / config ─────────────────────────────────────────────────────

    /** The session configuration cannot be created on the current device + camera config. */
    public data class SessionUnsupported(override val cause: SessionUnsupportedException) :
        ARSessionFailure(cause)

    /** Called an active-session-only API while paused. */
    public data class SessionPaused(override val cause: SessionPausedException) :
        ARSessionFailure(cause)

    /** Called a paused-session-only API while active. */
    public data class SessionNotPaused(override val cause: SessionNotPausedException) :
        ARSessionFailure(cause)

    /** Camera is not currently tracking — the operation needs a `TrackingState.TRACKING` frame. */
    public data class NotTracking(override val cause: NotTrackingException) :
        ARSessionFailure(cause)

    /** Requested data is not yet available (e.g. depth before the first depth frame). */
    public data class NotYetAvailable(override val cause: NotYetAvailableException) :
        ARSessionFailure(cause)

    /** The requested `Config` combination is not supported on this device. */
    public data class UnsupportedConfiguration(override val cause: UnsupportedConfigurationException) :
        ARSessionFailure(cause)

    // ── Catch-all ────────────────────────────────────────────────────────────

    /**
     * Anything not covered by the typed subclasses above — typically a future ARCore exception
     * SceneView hasn't been updated for yet, or a generic `Exception` thrown by SceneView's own
     * session pipeline. Always check [cause] for context.
     */
    public data class Other(override val cause: Exception) : ARSessionFailure(cause)

    public companion object {

        /**
         * Map an ARCore [Exception] to the matching [ARSessionFailure] subtype.
         *
         * Uses [is] dispatch (NOT [Class.equals]) so subclasses of ARCore exceptions still
         * map to the right bucket — e.g. the JVM auto-generated subclasses Robolectric
         * creates for testing.
         */
        @JvmStatic
        public fun from(exception: Exception): ARSessionFailure = when (exception) {
            // Install / availability
            is UnavailableArcoreNotInstalledException -> ArCoreNotInstalled(exception)
            is UnavailableUserDeclinedInstallationException -> UserDeclinedInstall(exception)
            is UnavailableApkTooOldException -> ApkTooOld(exception)
            is UnavailableSdkTooOldException -> SdkTooOld(exception)
            is UnavailableDeviceNotCompatibleException -> DeviceNotCompatible(exception)

            // Permissions
            is FineLocationPermissionNotGrantedException -> FineLocationMissing(exception)
            is GooglePlayServicesLocationLibraryNotLinkedException ->
                GooglePlayServicesLocationLibraryNotLinked(exception)

            // Camera
            is CameraNotAvailableException -> CameraNotAvailable(exception)
            is TextureNotSetException -> TextureNotSet(exception)
            is MissingGlContextException -> MissingGlContext(exception)

            // Quota / runtime
            is ResourceExhaustedException -> ResourceExhausted(exception)
            is DeadlineExceededException -> DeadlineExceeded(exception)
            is FatalException -> Fatal(exception)

            // Cloud Anchor
            is CloudAnchorsNotConfiguredException -> CloudAnchorsNotConfigured(exception)
            is AnchorNotSupportedForHostingException -> AnchorNotSupportedForHosting(exception)

            // Augmented image
            is ImageInsufficientQualityException -> ImageInsufficientQuality(exception)

            // Recording / playback
            is RecordingFailedException -> RecordingFailed(exception)
            is PlaybackFailedException -> PlaybackFailed(exception)
            is DataInvalidFormatException -> DataInvalidFormat(exception)
            is DataUnsupportedVersionException -> DataUnsupportedVersion(exception)
            is MetadataNotFoundException -> MetadataNotFound(exception)

            // Session / config
            is SessionUnsupportedException -> SessionUnsupported(exception)
            is SessionPausedException -> SessionPaused(exception)
            is SessionNotPausedException -> SessionNotPaused(exception)
            is NotTrackingException -> NotTracking(exception)
            is NotYetAvailableException -> NotYetAvailable(exception)
            is UnsupportedConfigurationException -> UnsupportedConfiguration(exception)

            else -> Other(exception)
        }
    }
}
