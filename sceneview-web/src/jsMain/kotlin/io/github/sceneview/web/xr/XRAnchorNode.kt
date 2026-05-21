package io.github.sceneview.web.xr

/**
 * High-level wrapper around an [XRAnchor] — mirrors Android `arsceneview`
 * `AnchorNode`.
 *
 * Resolves the per-frame [XRPose] of the anchor and dispatches it to the
 * `onUpdate` callback. Tracks add / lost transitions so consumers can drop
 * any Filament resources tied to a dropped anchor.
 *
 * Requires the [XRFeature.ANCHORS] session feature.
 *
 * Create one from a hit-test result:
 *
 * ```kotlin
 * webXrSession.onHitTest = { pose -> /* show reticle */ }
 * webXrSession.onInputSelect = { _, _ ->
 *     val hit = lastHitResult ?: return@onInputSelect
 *     hit.createAnchor().then { anchor: XRAnchor ->
 *         val node = XRAnchorNode(anchor) { pose ->
 *             // Update Filament entity transform from pose.transform.matrix
 *         }
 *         anchors += node
 *     }
 * }
 *
 * // Per-frame:
 * webXrSession.onFrame = { frame, _ ->
 *     anchors.removeAll { !it.update(frame, webXrSession.referenceSpace) }
 * }
 * ```
 *
 * Or directly from [XRFrame.createAnchor]:
 *
 * ```kotlin
 * frame.createAnchor(transform, referenceSpace).then { a: XRAnchor ->
 *     XRAnchorNode(a) { pose -> ... }
 * }
 * ```
 *
 * @param anchor The underlying [XRAnchor]. Owned by this node — call [detach]
 *   to release it.
 * @param onUpdate Per-frame callback invoked with the resolved anchor pose.
 */
class XRAnchorNode(
    val anchor: XRAnchor,
    val onUpdate: (pose: XRPose) -> Unit = {},
) {
    /** Called the first frame the anchor is resolved. */
    var onAttached: ((XRPose) -> Unit)? = null

    /**
     * Called when the anchor goes missing from `frame.trackedAnchors` — typically
     * because the runtime dropped it (poor tracking, out of session bounds).
     */
    var onLost: (() -> Unit)? = null

    private var attached: Boolean = false
    private var detached: Boolean = false

    /** True once [detach] has been called. The node is then inert. */
    val isDetached: Boolean get() = detached

    /**
     * Drive the node per-frame.
     *
     * @return `true` if the anchor is still tracked / present. `false` once it
     *   has been lost or detached; the caller should drop the node.
     */
    fun update(frame: XRFrame, referenceSpace: XRReferenceSpace): Boolean {
        if (detached) return false
        // Cheap presence check using trackedAnchors when available
        val tracked = frame.asDynamic().trackedAnchors
        if (tracked != null) {
            val isPresent = jsTypeOf(tracked.has) == "function" && (tracked.has(anchor) as? Boolean ?: false)
            if (!isPresent) {
                if (attached) {
                    attached = false
                    onLost?.invoke()
                }
                return false
            }
        }
        val pose = frame.getPose(anchor.anchorSpace, referenceSpace) ?: return true
        if (!attached) {
            attached = true
            onAttached?.invoke(pose)
        }
        onUpdate(pose)
        return true
    }

    /**
     * Release the underlying [XRAnchor]. The node is inert afterwards.
     * Safe to call multiple times.
     */
    fun detach() {
        if (detached) return
        detached = true
        try {
            anchor.delete()
        } catch (_: dynamic) {
            // delete() may throw if the anchor was already released by the runtime
        }
    }
}
