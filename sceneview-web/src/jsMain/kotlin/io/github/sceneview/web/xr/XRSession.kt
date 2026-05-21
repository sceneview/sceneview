@file:Suppress("INTERFACE_WITH_SUPERCLASS")

package io.github.sceneview.web.xr

import org.w3c.dom.events.EventTarget

/**
 * External declaration for the WebXR [XRSession](https://developer.mozilla.org/en-US/docs/Web/API/XRSession) interface.
 *
 * Represents an ongoing XR experience. Created via [XRSystem.requestSession].
 * The session manages the render loop, input sources, reference spaces, and hit testing.
 *
 * @see <a href="https://developer.mozilla.org/en-US/docs/Web/API/XRSession">MDN XRSession</a>
 */
external interface XRSession : EventTarget {
    /** The current render state (base layer, depth info). */
    val renderState: XRRenderState

    /** Array of active input sources (controllers, hands, screen taps). */
    val inputSources: dynamic /* XRInputSourceArray */

    /** The session mode: "inline", "immersive-vr", or "immersive-ar". */
    val mode: String?

    /** Session visibility state: "visible", "visible-blurred", "hidden". */
    val visibilityState: String?

    /** The framerate of the XR session, if available. */
    val frameRate: Double?

    /**
     * Request a reference space for tracking.
     *
     * @param type One of [XRReferenceSpaceType] constants
     * @return Promise resolving to an [XRReferenceSpace]
     */
    fun requestReferenceSpace(type: String): dynamic /* Promise<XRReferenceSpace> */

    /**
     * Request a callback for the next XR animation frame.
     *
     * @param callback Receives (timestamp, [XRFrame])
     * @return Handle that can be passed to [cancelAnimationFrame]
     */
    fun requestAnimationFrame(callback: (Double, XRFrame) -> Unit): Int

    /** Cancel a previously requested animation frame. */
    fun cancelAnimationFrame(handle: Int)

    /** End the XR session. Returns a Promise. */
    fun end(): dynamic /* Promise<void> */

    /** Update the render state (e.g., set the base layer). */
    fun updateRenderState(state: dynamic)

    // Event handlers
    var onend: ((dynamic) -> Unit)?
    var oninputsourceschange: ((dynamic) -> Unit)?
    var onselect: ((dynamic) -> Unit)?
    var onselectstart: ((dynamic) -> Unit)?
    var onselectend: ((dynamic) -> Unit)?
    var onsqueeze: ((dynamic) -> Unit)?
    var onsqueezestart: ((dynamic) -> Unit)?
    var onsqueezeend: ((dynamic) -> Unit)?
    var onvisibilitychange: ((dynamic) -> Unit)?
}

/**
 * External declaration for [XRRenderState](https://developer.mozilla.org/en-US/docs/Web/API/XRRenderState).
 */
external interface XRRenderState {
    val baseLayer: dynamic /* XRWebGLLayer? */
    val depthFar: Double
    val depthNear: Double
    val inlineVerticalFieldOfView: Double?
}

/**
 * WebXR session feature strings for use in requestSession options.
 */
object XRFeature {
    const val HIT_TEST = "hit-test"
    const val DOM_OVERLAY = "dom-overlay"
    const val LIGHT_ESTIMATION = "light-estimation"
    const val ANCHORS = "anchors"
    const val PLANE_DETECTION = "plane-detection"
    const val DEPTH_SENSING = "depth-sensing"
    const val HAND_TRACKING = "hand-tracking"
    const val LAYERS = "layers"
    const val MESH_DETECTION = "mesh-detection"

    /**
     * Image marker tracking — track the 6-DoF pose of a 2D image in the real world.
     * See [Marker Tracking explainer](https://github.com/immersive-web/marker-tracking) and
     * [WebXR `image-tracking` feature](https://immersive-web.github.io/marker-tracking/).
     * Supported on recent Android Chrome (origin trial / flag).
     */
    const val IMAGE_TRACKING = "image-tracking"
}
