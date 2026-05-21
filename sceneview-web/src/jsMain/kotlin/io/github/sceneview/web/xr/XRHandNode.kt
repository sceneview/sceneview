package io.github.sceneview.web.xr

/**
 * Hand-tracking DSL — mirrors Android `arsceneview` hand-tracking idioms.
 *
 * Usage from a WebXR render loop:
 *
 * ```kotlin
 * val leftHand = XRHandNode(Handedness.LEFT)
 *     .joint(Joint.INDEX_TIP) { pose ->
 *         // pose is the per-frame XRPose for the index fingertip
 *     }
 *     .joint(Joint.WRIST) { pose -> /* ... */ }
 *
 * webXrSession.onFrame = { frame, _ ->
 *     leftHand.update(frame, webXrSession.referenceSpace)
 * }
 * ```
 *
 * Requires the [XRFeature.HAND_TRACKING] session feature.
 *
 * The 25 joint names are defined by the
 * [WebXR Hand Input spec](https://www.w3.org/TR/webxr-hand-input-1/).
 *
 * @param handedness Which hand this node tracks. Use [Handedness] constants.
 */
class XRHandNode(
    val handedness: String,
) {
    /** Callback invoked when the underlying [XRInputSource] (the hand) is no longer present. */
    var onHandLost: (() -> Unit)? = null

    /** Callback invoked the frame the hand becomes tracked. */
    var onHandFound: ((XRHand) -> Unit)? = null

    private val jointCallbacks: MutableMap<String, (XRPose) -> Unit> = mutableMapOf()
    private var previouslyTracked: Boolean = false

    /**
     * Register a callback for a specific [Joint]. Returns `this` for chaining.
     *
     * The callback fires every frame the hand is tracked and the joint's pose
     * is resolvable. Use [Joint] constants for the joint name.
     */
    fun joint(joint: String, block: (XRPose) -> Unit): XRHandNode {
        jointCallbacks[joint] = block
        return this
    }

    /** Number of joints currently registered (used by tests). */
    val jointCount: Int get() = jointCallbacks.size

    /** Whether a given joint name is being observed. */
    fun isObserving(joint: String): Boolean = jointCallbacks.containsKey(joint)

    /** Remove the observer for a joint. */
    fun removeJoint(joint: String): Boolean = jointCallbacks.remove(joint) != null

    /** Remove all observers. */
    fun clear() {
        jointCallbacks.clear()
    }

    /**
     * Per-frame update — call this from the render loop or `onFrame` callback.
     *
     * Walks the session's input sources, finds the one matching [handedness]
     * with a non-null `hand`, and invokes every registered joint callback with
     * the per-frame [XRPose] resolved via [XRFrame.getPose].
     */
    fun update(frame: XRFrame, referenceSpace: XRReferenceSpace) {
        val inputSources = frame.session.inputSources
        val length = (inputSources.length as? Int) ?: 0
        var found: XRHand? = null
        for (i in 0 until length) {
            val src = inputSources[i] as? XRInputSource ?: continue
            if (src.handedness == handedness && src.hand != null) {
                found = src.hand
                break
            }
        }
        if (found == null) {
            if (previouslyTracked) {
                previouslyTracked = false
                onHandLost?.invoke()
            }
            return
        }
        if (!previouslyTracked) {
            previouslyTracked = true
            onHandFound?.invoke(found)
        }
        for ((jointName, cb) in jointCallbacks) {
            val space = found.get(jointName) ?: continue
            val pose = frame.getPose(space, referenceSpace) ?: continue
            cb(pose)
        }
    }

    /**
     * Handedness constants — mirror [XRHandedness] but live next to [XRHandNode]
     * for DSL-style ergonomics: `XRHandNode(Handedness.LEFT)`.
     */
    object Handedness {
        const val LEFT = XRHandedness.LEFT
        const val RIGHT = XRHandedness.RIGHT
        const val NONE = XRHandedness.NONE
    }

    /**
     * Joint name constants for the 25 joints in the
     * [WebXR Hand Input spec](https://www.w3.org/TR/webxr-hand-input-1/).
     *
     * Short aliases for the most-used joints (`INDEX_TIP`, `THUMB_TIP`,
     * `WRIST`, etc.) are exposed here; the full spec-string list lives in
     * [XRHandJoint].
     */
    object Joint {
        const val WRIST = XRHandJoint.WRIST

        const val THUMB_METACARPAL = XRHandJoint.THUMB_METACARPAL
        const val THUMB_PROXIMAL = XRHandJoint.THUMB_PHALANX_PROXIMAL
        const val THUMB_DISTAL = XRHandJoint.THUMB_PHALANX_DISTAL
        const val THUMB_TIP = XRHandJoint.THUMB_TIP

        const val INDEX_METACARPAL = XRHandJoint.INDEX_FINGER_METACARPAL
        const val INDEX_PROXIMAL = XRHandJoint.INDEX_FINGER_PHALANX_PROXIMAL
        const val INDEX_INTERMEDIATE = XRHandJoint.INDEX_FINGER_PHALANX_INTERMEDIATE
        const val INDEX_DISTAL = XRHandJoint.INDEX_FINGER_PHALANX_DISTAL
        const val INDEX_TIP = XRHandJoint.INDEX_FINGER_TIP

        const val MIDDLE_METACARPAL = XRHandJoint.MIDDLE_FINGER_METACARPAL
        const val MIDDLE_PROXIMAL = XRHandJoint.MIDDLE_FINGER_PHALANX_PROXIMAL
        const val MIDDLE_INTERMEDIATE = XRHandJoint.MIDDLE_FINGER_PHALANX_INTERMEDIATE
        const val MIDDLE_DISTAL = XRHandJoint.MIDDLE_FINGER_PHALANX_DISTAL
        const val MIDDLE_TIP = XRHandJoint.MIDDLE_FINGER_TIP

        const val RING_METACARPAL = XRHandJoint.RING_FINGER_METACARPAL
        const val RING_PROXIMAL = XRHandJoint.RING_FINGER_PHALANX_PROXIMAL
        const val RING_INTERMEDIATE = XRHandJoint.RING_FINGER_PHALANX_INTERMEDIATE
        const val RING_DISTAL = XRHandJoint.RING_FINGER_PHALANX_DISTAL
        const val RING_TIP = XRHandJoint.RING_FINGER_TIP

        const val PINKY_METACARPAL = XRHandJoint.PINKY_FINGER_METACARPAL
        const val PINKY_PROXIMAL = XRHandJoint.PINKY_FINGER_PHALANX_PROXIMAL
        const val PINKY_INTERMEDIATE = XRHandJoint.PINKY_FINGER_PHALANX_INTERMEDIATE
        const val PINKY_DISTAL = XRHandJoint.PINKY_FINGER_PHALANX_DISTAL
        const val PINKY_TIP = XRHandJoint.PINKY_FINGER_TIP

        /** Convenience: all 25 joint names in the order defined by the spec. */
        val ALL: List<String> = listOf(
            WRIST,
            THUMB_METACARPAL, THUMB_PROXIMAL, THUMB_DISTAL, THUMB_TIP,
            INDEX_METACARPAL, INDEX_PROXIMAL, INDEX_INTERMEDIATE, INDEX_DISTAL, INDEX_TIP,
            MIDDLE_METACARPAL, MIDDLE_PROXIMAL, MIDDLE_INTERMEDIATE, MIDDLE_DISTAL, MIDDLE_TIP,
            RING_METACARPAL, RING_PROXIMAL, RING_INTERMEDIATE, RING_DISTAL, RING_TIP,
            PINKY_METACARPAL, PINKY_PROXIMAL, PINKY_INTERMEDIATE, PINKY_DISTAL, PINKY_TIP,
        )
    }
}
