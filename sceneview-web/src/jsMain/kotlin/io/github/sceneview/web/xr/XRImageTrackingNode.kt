@file:Suppress("INTERFACE_WITH_SUPERCLASS")

package io.github.sceneview.web.xr

/**
 * External declaration for a per-frame **image-tracking result**, as exposed by
 * [`XRFrame.getImageTrackingResults()`](https://github.com/immersive-web/marker-tracking).
 *
 * Each result corresponds to one image registered at session-creation time via
 * the `XRSessionInit.trackedImages[]` dict.
 *
 * Spec preview: <https://immersive-web.github.io/marker-tracking/>.
 *
 * Mirrors Android `AugmentedImage` from ARCore.
 */
external interface XRImageTrackingResult {
    /** The image space — pass to [XRFrame.getPose] to obtain the marker pose. */
    val imageSpace: XRSpace

    /**
     * The zero-based index into the original `trackedImages` array passed at
     * session creation. Lets the consumer route results back to known markers.
     */
    val index: Int

    /**
     * The tracking state. One of [XRImageTrackingState] constants.
     * `"untracked"` is also legal (some runtimes never emit a result rather
     * than emit `untracked`).
     */
    val trackingState: String

    /**
     * The runtime-measured marker width in meters, if the runtime measures it.
     * Otherwise the value provided at session creation is echoed back.
     */
    val measuredWidthInMeters: Double
}

/**
 * WebXR image-tracking state constants.
 */
object XRImageTrackingState {
    /** The runtime tracks the image with full 6-DoF pose. */
    const val TRACKED = "tracked"

    /**
     * The runtime knows the image exists but cannot resolve its pose this frame
     * (out of view, partial occlusion). Last-known pose may still be returned.
     */
    const val EMULATED = "emulated"

    /** The image is not currently tracked (not in spec, used defensively). */
    const val UNTRACKED = "untracked"
}

/**
 * Per-image tracking listener — mirrors Android `arsceneview` `AugmentedImageNode`.
 *
 * Usage:
 *
 * ```kotlin
 * // 1. Register the image at session creation time:
 * val image = ImageBitmap.fromUrl("/markers/poster.png")
 * val sessionOptions = js("{}")
 * sessionOptions.requiredFeatures = arrayOf(XRFeature.IMAGE_TRACKING)
 * sessionOptions.trackedImages = arrayOf(js("{image: image, widthInMeters: 0.2}"))
 *
 * // 2. In the render loop:
 * val poster = XRImageTrackingNode(index = 0) { pose, state ->
 *     // Render content anchored to the marker
 * }
 * webXrSession.onFrame = { frame, _ ->
 *     poster.update(frame, webXrSession.referenceSpace)
 * }
 * ```
 *
 * @param index The zero-based index in the `trackedImages` array at session creation.
 * @param onTracked Per-frame callback invoked when the marker is tracked (or
 *   emulated). Receives the resolved [XRPose] and the [XRImageTrackingResult]
 *   so the consumer can read `measuredWidthInMeters` to size content.
 */
class XRImageTrackingNode(
    val index: Int,
    val onTracked: (pose: XRPose, result: XRImageTrackingResult) -> Unit = { _, _ -> },
) {
    /** Called the frame the marker transitions from "no result" to a tracked result. */
    var onFound: ((XRImageTrackingResult) -> Unit)? = null

    /** Called the frame the marker transitions from tracked to "no result". */
    var onLost: (() -> Unit)? = null

    private var previouslyVisible: Boolean = false

    /**
     * Drive the node per-frame. Walks [XRFrame.getImageTrackingResults] to find
     * the result for [index] and invokes the registered callbacks.
     */
    fun update(frame: XRFrame, referenceSpace: XRReferenceSpace) {
        val results = frame.getImageTrackingResults()
        val match = results.firstOrNull { it.index == index }
        if (match == null || match.trackingState == XRImageTrackingState.UNTRACKED) {
            if (previouslyVisible) {
                previouslyVisible = false
                onLost?.invoke()
            }
            return
        }
        if (!previouslyVisible) {
            previouslyVisible = true
            onFound?.invoke(match)
        }
        val pose = frame.getPose(match.imageSpace, referenceSpace) ?: return
        onTracked(pose, match)
    }
}
