package io.github.sceneview.ar.arcore

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.view.Display
import android.view.WindowManager
import com.google.ar.core.AugmentedImageDatabase
import com.google.ar.core.CameraConfig
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.MissingGlContextException
import com.google.ar.core.exceptions.SessionPausedException
import io.github.sceneview.ar.node.ARCameraNode

class ARSession(
    context: Context,
    features: Set<Feature> = setOf(),
    val onResumed: (session: Session) -> Unit,
    val onPaused: (session: Session) -> Unit,
    val onConfigChanged: (session: Session, config: Config) -> Unit
) : Session(context, features) {

    // We use device display sizes by default cause the onSurfaceChanged may be called after the
    // onResume and ARCore doesn't appreciate that we do things on session before calling
    // setDisplayGeometry()
    // = flickering screen if the phone is locked when starting the app
    var displayRotation: Int
    var displayWidth: Int
    var displayHeight: Int

    init {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds: Rect = windowManager.currentWindowMetrics.bounds
            displayRotation = context.display?.rotation ?: 0
            displayWidth = bounds.width()
            displayHeight = bounds.height()
        } else {
            @Suppress("DEPRECATION")
            val display: Display = windowManager.defaultDisplay
            displayRotation = display.rotation
            @Suppress("DEPRECATION")
            displayWidth = display.width
            @Suppress("DEPRECATION")
            displayHeight = display.height
        }
    }

    var isResumed = false

    var hasAugmentedImageDatabase = false

    /**
     * The most recent ARCore Frame if it is available
     *
     * The frame is updated at the beginning of each drawing frame.
     * Callers of this method should not retain a reference to the return value, since it will be
     * invalid to use the ARCore frame starting with the next frame.
     */
    var frame: Frame? = null
        private set

    override fun configure(config: Config) {
        super.configure(config)

        if (config.depthMode != Config.DepthMode.DISABLED &&
            !isDepthModeSupported(config.depthMode)
        ) {
            config.depthMode = Config.DepthMode.DISABLED
        }

        // Flash mode is only available on a subset of devices (and only with a BACK camera config
        // — front-camera sessions never expose a torch). ARCore throws if a session is configured
        // with an unsupported FlashMode, so we silently fall back to OFF here, matching the
        // depthMode auto-fallback above (#1732).
        //
        // ARCore doesn't ship an `isFlashModeSupported(FlashMode)` getter (cf. depthMode etc.),
        // so we test the whole config via `Session.isSupported(config)`. Logic is centralised in
        // [resolveFlashMode] so it can be exercised without a live Session.
        config.flashMode = resolveFlashMode(config.flashMode) { mode ->
            // Temporarily mutate the config to probe support, then restore — `isSupported` is a
            // read-only check against the native session, so probing is side-effect free.
            val previous = config.flashMode
            config.flashMode = mode
            val supported = isSupported(config)
            config.flashMode = previous
            supported
        }

        // Light estimation is not usable with front camera
        if (cameraConfig.facingDirection == CameraConfig.FacingDirection.FRONT
            && config.lightEstimationMode != Config.LightEstimationMode.DISABLED
        ) {
            config.lightEstimationMode = Config.LightEstimationMode.DISABLED
        }

        // Scene Semantics requires both the ML model present on-device AND a back-camera
        // session — the outdoor 12-class semantics model has no front-camera training data.
        // ARCore throws `UnsupportedConfigurationException` when a session is configured with
        // `SemanticMode.ENABLED` on a device that lacks the model, so we silently fall back
        // to DISABLED here, matching the depthMode / flashMode auto-fallbacks above (#1730).
        config.semanticMode = resolveSemanticMode(config.semanticMode) { mode ->
            isSemanticModeSupported(mode)
        }

        hasAugmentedImageDatabase = (config.augmentedImageDatabase?.numImages ?: 0) > 0

        onConfigChanged(this, config)
    }

    override fun resume() {
        isResumed = true
        super.resume()

        // Don't remove this code-block. It is important to correctly set the DisplayGeometry for
        // the ArCore-Session if for example the permission Dialog is shown on the screen.
        // If we remove this part, the camera is flickering if returned from the permission Dialog.
        setDisplayGeometry(displayRotation, displayWidth, displayHeight)

        onResumed(this)
    }

    override fun pause() = super.pause().also {
        isResumed = false

        onPaused(this)
    }

    /**
     * Updates the state of the ARCore system. This includes: receiving a new camera frame, updating
     * the location of the device, updating the location of tracking anchors, updating detected
     * planes, etc.
     *
     * This call may update the pose of all created anchors and detected planes. The set of updated
     * objects is accessible through [Frame.getUpdatedTrackables].
     *
     * @return The most recent Frame received
     * @throws CameraNotAvailableException if the camera could not be opened.
     * @throws SessionPausedException if the session is currently paused.
     * @throws MissingGlContextException if there is no OpenGL context available.
     */
    fun updateOrNull() = if(isResumed) {
        super.update().takeIf {
            // Check if no frame or same timestamp, no drawing.
            it.timestamp != 0L //&& it.timestamp != frame?.timestamp
        }.also {
            frame = it
        }
    } else {
        null
    }

    override fun setDisplayGeometry(rotation: Int, widthPx: Int, heightPx: Int) {
        displayRotation = rotation
        displayWidth = widthPx
        displayHeight = heightPx
        if (isResumed) {
            super.setDisplayGeometry(displayRotation, widthPx, heightPx)
        }
    }
}

/**
 * Pure-Kotlin support-gate for [Config.FlashMode] used by [ARSession.configure] (#1732).
 *
 * Returns the requested mode if the session supports it; otherwise [Config.FlashMode.OFF].
 *
 * Extracted so the gate can be unit-tested without an ARCore [Session] instance — the JNI-bound
 * `Session.isFlashModeSupported()` is impossible to mock under pure-JVM tests.
 *
 * @param requested The mode the caller asked for (typically from `Config.flashMode`).
 * @param isSupported Adapter returning `true` if the session supports `requested` (typically
 *   `session::isFlashModeSupported`).
 */
internal fun resolveFlashMode(
    requested: Config.FlashMode,
    isSupported: (Config.FlashMode) -> Boolean
): Config.FlashMode =
    if (requested == Config.FlashMode.OFF || isSupported(requested)) requested
    else Config.FlashMode.OFF

/**
 * Pure-Kotlin support-gate for [Config.SemanticMode] used by [ARSession.configure] (#1730).
 *
 * Returns the requested mode if the session supports it; otherwise [Config.SemanticMode.DISABLED].
 *
 * Scene Semantics requires the on-device ML model (downloaded by Google Play Services for AR on
 * devices that ship it) and a back-camera session. ARCore's `Session.isSemanticModeSupported` is
 * the canonical capability probe; we wrap it here so the gate logic stays unit-testable without
 * a live JNI-bound [Session].
 *
 * @param requested The mode the caller asked for (typically from `Config.semanticMode`).
 * @param isSupported Adapter returning `true` if the session supports `requested` (typically
 *   `session::isSemanticModeSupported`).
 */
internal fun resolveSemanticMode(
    requested: Config.SemanticMode,
    isSupported: (Config.SemanticMode) -> Boolean
): Config.SemanticMode =
    if (requested == Config.SemanticMode.DISABLED || isSupported(requested)) requested
    else Config.SemanticMode.DISABLED

/**
 * Define the session config used by ARCore
 *
 * Prefer calling this method before the global (Activity or Fragment) onResume() cause the session
 * base configuration in made there.
 * Any later calls (after onSessionResumed()) to this function are not completely sure be taken in
 * account by ARCore (even if most of them will work)
 *
 * Please check that all your Session Config parameters are taken in account by ARCore at
 * runtime.
 *
 * @param config the apply block for the new config
 */
fun Session.configure(config: (Config) -> Unit) = configure(this.config.apply(config))

fun Session.canHostCloudAnchor(cameraNode: ARCameraNode) =
    estimateFeatureMapQualityForHosting(cameraNode.pose) != Session.FeatureMapQuality.INSUFFICIENT

/**
 * Adds a single named image with known physical size to the augmented image database from an
 * Android bitmap, with a specified physical width in meters.
 *
 * Returns the zero-based positional index of the image within the database.
 *
 * For images added via this method, ARCore can estimate the pose of the physical image at
 * runtime as soon as ARCore detects the physical image, without requiring the user to move the
 * device to view the physical image from different viewpoints. Note that ARCore will refine the
 * estimated size and pose of the physical image as it is viewed from different viewpoints.
 *
 * This method takes time to perform non-trivial image processing (20ms - 30ms), and should be
 * run on a background thread to avoid blocking the UI thread.
 *
 * @param name Name metadata for this image, does not have to be unique.
 * @param bitmap Bitmap containing the image in ARGB_8888 format. The alpha channel is ignored in
 *     this bitmap, as only non-transparent images are currently supported.
 * @param widthInMeters Width in meters for this image, must be strictly greater than zero.
 * `null` indicates the physical size of the image is not known, at the expense of an increased
 * image detection time.
 * @throws com.google.ar.core.exceptions.ImageInsufficientQualityException if the image quality is
 *     image is insufficient, e.g. if the image has not enough features.
 * @throws java.lang.IllegalArgumentException if the bitmap is not in ARGB_888 format or the width
 *     in meters is less than or equal to zero.
 */
fun Config.addAugmentedImage(
    session: Session,
    name: String,
    bitmap: Bitmap,
    widthInMeters: Float? = null
) {
    val augmentedImageDatabase = augmentedImageDatabase.takeIf {
        // Using the default augmentedImageDatabase even if not null is not
        // working so we check if it's our AugmentedImageDatabase (if we already
        // added images)
        it.numImages > 0
    } ?: AugmentedImageDatabase(session)

    if (widthInMeters != null) {
        augmentedImageDatabase.addImage(name, bitmap, widthInMeters)
    } else {
        augmentedImageDatabase.addImage(name, bitmap)
    }
    this.augmentedImageDatabase = augmentedImageDatabase
}