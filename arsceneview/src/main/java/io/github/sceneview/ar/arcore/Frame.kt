package io.github.sceneview.ar.arcore

import android.media.Image
import com.google.ar.core.AugmentedFace
import com.google.ar.core.AugmentedImage
import com.google.ar.core.Frame
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.StreetscapeGeometry
import com.google.ar.core.Trackable
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.NotYetAvailableException
import dev.romainguy.kotlin.math.Ray
import io.github.sceneview.utils.fps
import io.github.sceneview.utils.intervalSeconds

/**
 * Similar to [Frame.hitTest], but takes an arbitrary ray in world space coordinates instead of a
 * screen-space point.
 *
 * Note: When using [Session.Feature.FRONT_CAMERA], the returned hit result list will always be
 * empty, as the camera is not [TrackingState.TRACKING]. Hit testing against tracked faces is not
 * currently supported.
 *
 * @param ray a ray containing an origin and direction in world space coordinates. Does not have to
 * be normalized.
 *
 * @return an ordered list of intersections with scene geometry, nearest hit first
 */
fun Frame.hitTest(ray: Ray): List<HitResult> {
    val origin = ray.origin.toFloatArray()
    val direction = ray.direction.toFloatArray()
    return hitTest(origin, 0, direction, 0)
}

/**
 * Returns the collection of updated trackables of the same type as [trackable] for this frame.
 */
inline fun <reified T : Trackable> Frame.hasUpdatedTrackable(trackable: T) =
    getUpdatedTrackables(T::class.java)

/**
 * Returns all updated [Trackable]s of any type for this frame.
 */
fun Frame.getUpdatedTrackables() = getUpdatedTrackables(Trackable::class.java)

/**
 * Retrieve the frame tracked Planes.
 */
fun Frame.getUpdatedPlanes() = getUpdatedTrackables(Plane::class.java)

/**
 * Retrieve if the frame is currently tracking a plane.
 *
 * @return true if the frame is tracking at least one plane.
 */
fun Frame.isTrackingPlane() = getUpdatedPlanes().any {
    it.trackingState == TrackingState.TRACKING
}

/**
 * Retrieve the frame tracked Augmented Images.
 */
fun Frame.getUpdatedAugmentedImages() = getUpdatedTrackables(AugmentedImage::class.java)

/**
 * Retrieve the frame tracked Augmented Faces.
 */
fun Frame.getUpdatedAugmentedFaces() = getUpdatedTrackables(AugmentedFace::class.java)

/**
 * Retrieve the frame tracked Streetscape Geometries.
 */
fun Frame.getUpdatedStreetscapeGeometries() = getUpdatedTrackables(StreetscapeGeometry::class.java)

/**
 * Returns the time interval in seconds between this frame and [other].
 * If [other] is `null`, returns the interval from timestamp 0.
 */
fun Frame.intervalSeconds(other: Frame?): Double = timestamp.intervalSeconds(other?.timestamp)

/**
 * Returns the estimated frames per second between this frame and [other].
 * If [other] is `null`, returns the FPS from timestamp 0.
 */
fun Frame.fps(other: Frame?): Double = timestamp.fps(other?.timestamp)

/**
 * Acquires the **CPU camera image** for this frame in YUV_420_888 layout (#1733).
 *
 * Wraps [Frame.acquireCameraImage] — the standard ARCore entry-point for any custom computer
 * vision pipeline: ML Kit barcode/face/text detection, OpenCV processing, custom temporal
 * filters, screenshot helpers, etc. The resolution matches the active
 * [com.google.ar.core.CameraConfig], so apps that need 1080p / 60 FPS / depth-sensor-paired
 * configs should pair this accessor with the `cameraConfigFilter { … }` DSL on `ARSceneView`
 * (or pass a custom `sessionCameraConfig`).
 *
 * Returns `null` if the image is not yet available — typically only for the first frame after
 * session resume, or while the session is paused (`NotYetAvailableException` from ARCore).
 *
 * **The returned [Image] is caller-owned** — close it via `use { }` or an explicit
 * `image.close()` in a `finally` block. Failing to close leaks a native handle and ARCore will
 * eventually throw `ResourceExhaustedException` after a couple of frames (the CPU image pool
 * is typically only 2–3 slots deep).
 *
 * ```kotlin
 * onSessionUpdated = { _, frame ->
 *     frame.cameraImage()?.use { image ->
 *         // YUV_420_888 — image.planes[0] is the Y plane, [1] is U, [2] is V.
 *         myCvPipeline.process(image)
 *     }
 * }
 * ```
 *
 * @see Frame.acquireCameraImage
 */
fun Frame.cameraImage(): Image? = try {
    acquireCameraImage()
} catch (_: NotYetAvailableException) {
    null
}

/**
 * Acquires the smoothed full-resolution **depth** image (16-bit per pixel, ARGB_8888-packed
 * `DEPTH16` layout per ARCore docs) for this frame.
 *
 * Wraps [Frame.acquireDepthImage16Bits] — exposed publicly (#1771) so apps can drive
 * custom occlusion, physics raycasts, AR overlays gated on depth confidence, etc.
 *
 * Returns `null` if depth is not yet available (`NotYetAvailableException` from ARCore).
 * Requires [com.google.ar.core.Config.DepthMode.AUTOMATIC] enabled on the session.
 *
 * **The returned [Image] is caller-owned** — close it via `use { }` or an explicit
 * `image.close()` in a `finally` block. Failing to close will leak the native handle and
 * eventually throw `ResourceExhaustedException` when ARCore runs out of image slots.
 *
 * @see Frame.acquireDepthImage16Bits
 */
fun Frame.depthImage(): Image? = try {
    acquireDepthImage16Bits()
} catch (_: NotYetAvailableException) {
    null
}

/**
 * Acquires the **raw, unsmoothed** depth image (16-bit per pixel) for this frame.
 *
 * Wraps [Frame.acquireRawDepthImage16Bits]. Higher temporal noise than [depthImage], but
 * preserves sharp edges and is the right input for confidence-filtered overlays or
 * custom temporal filtering.
 *
 * Returns `null` if depth is not yet available. **Caller-owned** — close in `use { }`.
 *
 * @see Frame.acquireRawDepthImage16Bits
 */
fun Frame.rawDepthImage(): Image? = try {
    acquireRawDepthImage16Bits()
} catch (_: NotYetAvailableException) {
    null
}

/**
 * Acquires the **per-pixel confidence** image (`Y8` format, 0..255) matching
 * [rawDepthImage] — higher values mean more confident depth estimate.
 *
 * Wraps [Frame.acquireRawDepthConfidenceImage]. Lets apps filter unreliable raw depth
 * samples (typical practice: discard pixels with confidence < 128).
 *
 * Returns `null` if depth is not yet available. **Caller-owned** — close in `use { }`.
 *
 * @see Frame.acquireRawDepthConfidenceImage
 */
fun Frame.rawDepthConfidenceImage(): Image? = try {
    acquireRawDepthConfidenceImage()
} catch (_: NotYetAvailableException) {
    null
}