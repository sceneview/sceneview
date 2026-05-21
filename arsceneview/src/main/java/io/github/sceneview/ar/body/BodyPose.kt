package io.github.sceneview.ar.body

/**
 * One tracked skeleton joint produced by [BodyPose.fromMediaPipeLandmarks].
 *
 * Coordinates are **image-space**, not world-space — see the parity caveat on [Joint]:
 *
 * @property x Horizontal position normalised to `[0, 1]` across the camera frame
 *             (`0` = left edge, `1` = right edge).
 * @property y Vertical position normalised to `[0, 1]` down the camera frame
 *             (`0` = top edge, `1` = bottom edge).
 * @property z Relative depth from the MediaPipe model — roughly metres with the origin near
 *             the hips, smaller (more negative) values being closer to the camera. **Not** a
 *             metric AR-world coordinate; treat it as an ordering hint only.
 * @property inFrameLikelihood Model confidence in `[0, 1]` that the joint is visible and
 *             correctly placed. Synthesised joints ([Joint.ROOT], [Joint.SPINE],
 *             [Joint.NECK]) carry the minimum confidence of the joints they were derived
 *             from.
 */
data class BodyLandmark(
    val joint: Joint,
    val x: Float,
    val y: Float,
    val z: Float,
    val inFrameLikelihood: Float,
)

/**
 * A single detected body pose — the 17 ARKit-parity [Joint]s projected from one MediaPipe
 * Pose Landmarker result.
 *
 * ### What this is — and is not
 *
 * On Android, SceneView body tracking runs Google's on-device MediaPipe Pose Landmarker on
 * the AR CPU camera image (ARCore has no native body-tracking API). The result is an
 * **image-space** pose: joints are normalised pixel coordinates plus a relative depth, *not*
 * a world-anchored 3D skeleton. See the parity caveat on [Joint] for the full ARKit
 * comparison. This makes [BodyPose] ideal for 2D skeleton overlays, fitness/gesture
 * detection and on-screen AR filters, and *unsuitable* as a drop-in for ARKit's
 * `BodyTrackedEntity` world-anchored rig.
 *
 * Construct one per detection with [fromMediaPipeLandmarks]; SceneView's AR Body Tracker
 * demo does exactly that for every throttled camera frame.
 *
 * @property landmarks The detected joints keyed by [Joint]. A joint is absent from the map
 *           when its source landmark is missing from the MediaPipe result.
 */
data class BodyPose(
    val landmarks: Map<Joint, BodyLandmark>,
) {
    /** `true` when at least one joint was detected. */
    val isTracked: Boolean get() = landmarks.isNotEmpty()

    /** The landmark for [joint], or `null` when that joint was not detected this frame. */
    operator fun get(joint: Joint): BodyLandmark? = landmarks[joint]

    companion object {
        /**
         * Builds a [BodyPose] from a flat MediaPipe Pose Landmarker result.
         *
         * @param landmarks The raw per-landmark `(x, y, z, visibility)` tuples, indexed
         *        `0..32` exactly as MediaPipe emits them. Lists shorter than
         *        [Joint.MEDIAPIPE_LANDMARK_COUNT] are tolerated — missing indices simply
         *        produce missing joints. A `null` or empty list yields an untracked pose.
         *
         * The 13 directly-mapped joints come straight from [Joint.fromMediaPipeLandmarkIndex];
         * the 3 synthesised joints are derived as:
         *  - [Joint.ROOT]  — midpoint of the two hips.
         *  - [Joint.NECK]  — midpoint of the two shoulders.
         *  - [Joint.SPINE] — midpoint of [Joint.ROOT] and [Joint.NECK].
         *
         * A synthesised joint is only emitted when **both** of its parents were detected.
         */
        fun fromMediaPipeLandmarks(
            landmarks: List<RawLandmark>?,
        ): BodyPose {
            if (landmarks.isNullOrEmpty()) return BodyPose(emptyMap())

            val mapped = HashMap<Joint, BodyLandmark>()
            landmarks.forEachIndexed { index, raw ->
                val joint = Joint.fromMediaPipeLandmarkIndex(index) ?: return@forEachIndexed
                mapped[joint] = BodyLandmark(joint, raw.x, raw.y, raw.z, raw.visibility)
            }

            midpoint(Joint.ROOT, mapped[Joint.LEFT_HIP], mapped[Joint.RIGHT_HIP])
                ?.let { mapped[Joint.ROOT] = it }
            midpoint(Joint.NECK, mapped[Joint.LEFT_SHOULDER], mapped[Joint.RIGHT_SHOULDER])
                ?.let { mapped[Joint.NECK] = it }
            midpoint(Joint.SPINE, mapped[Joint.ROOT], mapped[Joint.NECK])
                ?.let { mapped[Joint.SPINE] = it }

            return BodyPose(mapped)
        }

        /**
         * Midpoint of two landmarks, retagged as [joint], or `null` when either parent is
         * missing. Confidence is the lower of the two parents — a synthesised joint is only
         * as trustworthy as its weakest input.
         */
        private fun midpoint(joint: Joint, a: BodyLandmark?, b: BodyLandmark?): BodyLandmark? {
            if (a == null || b == null) return null
            return BodyLandmark(
                joint = joint,
                x = (a.x + b.x) / 2f,
                y = (a.y + b.y) / 2f,
                z = (a.z + b.z) / 2f,
                inFrameLikelihood = minOf(a.inFrameLikelihood, b.inFrameLikelihood),
            )
        }
    }

    /**
     * A renderer-agnostic copy of one MediaPipe `NormalizedLandmark` — `(x, y, z)` plus the
     * model's `visibility` score. Decoupled from the MediaPipe SDK type so [BodyPose] and
     * its tests carry **no dependency on `com.google.mediapipe`**; the AR Body Tracker demo
     * adapts the SDK landmarks into this shape.
     */
    data class RawLandmark(
        val x: Float,
        val y: Float,
        val z: Float,
        val visibility: Float,
    )
}
