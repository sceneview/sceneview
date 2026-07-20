package io.github.sceneview.web.xr

import io.github.sceneview.math.toTransform
import io.github.sceneview.web.nodes.Node

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
 * Create one from a hit-test result, and [drive] a scene-graph [Node] so
 * AR-placed content is real graph content (children compose beneath it):
 *
 * ```kotlin
 * webXrSession.onHitTest = { pose -> /* show reticle */ }
 * webXrSession.onInputSelect = { _, _ ->
 *     val hit = lastHitResult ?: return@onInputSelect
 *     hit.createAnchor().then { anchor: XRAnchor ->
 *         val node = XRAnchorNode(anchor)
 *         // Anchor the model: its worldTransform now follows the anchor's
 *         // per-frame pose, and children parented under it compose for free.
 *         node.drive(sceneView.addModelNode("chair.glb"))
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
     * The scene-graph [Node] whose `worldTransform` this anchor drives each
     * [update], or `null` when the anchor only reports poses through
     * [onUpdate]. Set via [drive], cleared via [stopDriving] (and
     * automatically when the node is destroyed).
     */
    var drivenNode: Node? = null
        private set

    /** One-shot guard so a node re-parented after [drive] warns once, not per frame. */
    private var warnedNonRoot = false

    /**
     * Binds [node] so its `worldTransform` follows this anchor's per-frame
     * pose — AR-placed content becomes a real scene-graph node, with children
     * composing beneath it (#2024 P5a).
     *
     * The node must be a **root** (no parent): anchor poses are world-space,
     * so writing them into a parented node's world transform would compose
     * them twice through the parent chain. If the node gains a parent after
     * this call, per-frame writes are skipped (with a one-time console
     * warning) until it is a root again.
     *
     * The write path is the node's plain `worldTransform` setter — the same
     * `TransformManager.setTransform` route proven in-browser by the
     * `kotlin-bundle.spec.ts` #2024-P1 probe; no new embind binding.
     *
     * Replaces any previously driven node. Call [stopDriving] to release
     * the node while keeping the anchor alive.
     */
    fun drive(node: Node) {
        require(!node.isDestroyed) { "Cannot drive a destroyed Node" }
        require(node.parent == null) {
            "XRAnchorNode can only drive a root node (anchor poses are " +
                "world-space); detach '${node.name ?: node}' from its parent first"
        }
        drivenNode = node
        warnedNonRoot = false
    }

    /**
     * Releases the node bound by [drive] without touching the anchor. The
     * node keeps its last driven transform. Safe to call when nothing is
     * driven.
     */
    fun stopDriving() {
        drivenNode = null
    }

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
        // Drive the bound node BEFORE the callbacks, so onAttached/onUpdate
        // observe an up-to-date graph.
        drivenNode?.let { driveNode(it, pose) }
        if (!attached) {
            attached = true
            onAttached?.invoke(pose)
        }
        onUpdate(pose)
        return true
    }

    private fun driveNode(node: Node, pose: XRPose) {
        if (node.isDestroyed) {
            // Auto-release: a late transform write on a destroyed node would be
            // dropped by the node anyway; stop tracking it entirely.
            drivenNode = null
            return
        }
        if (node.parent != null) {
            if (!warnedNonRoot) {
                warnedNonRoot = true
                console.warn(
                    "XRAnchorNode: driven node '${node.name ?: node}' gained a " +
                        "parent — skipping pose writes (anchor poses are " +
                        "world-space and would double-compose). Detach it or " +
                        "call stopDriving().",
                )
            }
            return
        }
        warnedNonRoot = false
        applyXRPoseMatrix(node, pose.transform.matrix)
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

/** Element count of a 4x4 pose matrix. */
private const val POSE_MATRIX_SIZE = 16

/**
 * Writes a WebXR pose matrix — a column-major 16-element `Float32Array`
 * ([XRRigidTransform.matrix]) — into [node]'s `worldTransform`.
 *
 * WebXR and Filament share the same right-handed, +y-up, -z-forward,
 * column-major convention, so the matrix maps 1:1 onto the core
 * `FloatArray.toTransform()` column layout — no axis or handedness fix-up.
 * Kept as a top-level internal (not a method) so the synthetic-pose `jsTest`
 * can prove the conversion without a live WebXR session.
 */
internal fun applyXRPoseMatrix(node: Node, matrix: dynamic) {
    val columns = FloatArray(POSE_MATRIX_SIZE) { i ->
        matrix[i].unsafeCast<Double>().toFloat()
    }
    node.worldTransform = columns.toTransform()
}
