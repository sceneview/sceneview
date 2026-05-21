package io.github.sceneview.ar.body

/**
 * Skeleton joint, named to match the upper tier of ARKit's
 * [`ARSkeleton.JointName`](https://developer.apple.com/documentation/arkit/arskeleton/jointname)
 * 17-joint hierarchy so app code that targets a joint reads the same on Android (this enum)
 * and on Apple (`SceneViewSwift`).
 *
 * ### Parity caveat — read before relying on this
 *
 * ARCore has **no native body-tracking API**. Unlike ARKit's
 * `ARBodyTrackingConfiguration` — which delivers a world-anchored, 6-DoF rigged skeleton via
 * `ARBodyAnchor` / `BodyTrackedEntity` — the Android body-tracking path feeds the CPU camera
 * image to Google's on-device [MediaPipe Pose Landmarker](https://ai.google.dev/edge/mediapipe/solutions/vision/pose_landmarker)
 * and returns **image-space landmarks**:
 *
 *  - `x` / `y` are normalised to `[0, 1]` within the camera frame.
 *  - `z` is a *relative* depth (roughly metres, origin at the hips) — it is **not** a
 *    metric world coordinate and has no absolute scale or AR-world anchoring.
 *
 * In practice this means a SceneView [BodyPose] on Android is good for **2D skeleton
 * overlays, gesture/fitness detection and AR filters that track the person on screen**, but
 * it is *not* a substitute for ARKit's world-anchored 3D skeleton. Attaching a 3D model so
 * it stays welded to a limb in world space the way `BodyTrackedEntity` does is not possible
 * from MediaPipe landmarks alone.
 *
 * MediaPipe reports 33 raw landmarks; SceneView projects them onto these 17 ARKit-parity
 * joints via [fromMediaPipeLandmarkIndex] so the public surface stays small and
 * cross-platform. Joints with no direct MediaPipe equivalent (e.g. [SPINE], [ROOT]) are
 * synthesised by [BodyPose] as the midpoint of their anatomical neighbours.
 */
enum class Joint {
    /** Pelvis / hip centre — the synthesised root of the skeleton. */
    ROOT,

    /** Lower-spine midpoint between [ROOT] and [NECK] — synthesised. */
    SPINE,

    /** Base of the neck, midpoint of the two shoulders — synthesised. */
    NECK,

    /** Centre of the head (nose landmark). */
    HEAD,

    /** Left shoulder. */
    LEFT_SHOULDER,

    /** Left elbow. */
    LEFT_ELBOW,

    /** Left wrist / hand. */
    LEFT_HAND,

    /** Right shoulder. */
    RIGHT_SHOULDER,

    /** Right elbow. */
    RIGHT_ELBOW,

    /** Right wrist / hand. */
    RIGHT_HAND,

    /** Left hip. */
    LEFT_HIP,

    /** Left knee. */
    LEFT_KNEE,

    /** Left ankle / foot. */
    LEFT_FOOT,

    /** Right hip. */
    RIGHT_HIP,

    /** Right knee. */
    RIGHT_KNEE,

    /** Right ankle / foot. */
    RIGHT_FOOT;

    companion object {
        /**
         * Maps a [MediaPipe Pose Landmarker landmark index](https://ai.google.dev/edge/mediapipe/solutions/vision/pose_landmarker#pose_landmarker_model)
         * (`0..32`) to the SceneView [Joint] it feeds.
         *
         * Returns `null` for landmarks SceneView does not surface as a joint — the dense
         * facial landmarks (`1..10`: eyes, ears, mouth) and the extra hand/foot detail
         * (`17..22`, `29..32`: pinky, index, thumb, heel) — because they have no ARKit
         * 17-joint equivalent. The synthesised joints [ROOT], [SPINE] and [NECK] also
         * return `null` here: they are computed by [BodyPose], not read straight from a
         * landmark.
         */
        fun fromMediaPipeLandmarkIndex(index: Int): Joint? = when (index) {
            MP_NOSE -> HEAD
            MP_LEFT_SHOULDER -> LEFT_SHOULDER
            MP_RIGHT_SHOULDER -> RIGHT_SHOULDER
            MP_LEFT_ELBOW -> LEFT_ELBOW
            MP_RIGHT_ELBOW -> RIGHT_ELBOW
            MP_LEFT_WRIST -> LEFT_HAND
            MP_RIGHT_WRIST -> RIGHT_HAND
            MP_LEFT_HIP -> LEFT_HIP
            MP_RIGHT_HIP -> RIGHT_HIP
            MP_LEFT_KNEE -> LEFT_KNEE
            MP_RIGHT_KNEE -> RIGHT_KNEE
            MP_LEFT_ANKLE -> LEFT_FOOT
            MP_RIGHT_ANKLE -> RIGHT_FOOT
            else -> null
        }

        // MediaPipe Pose Landmarker landmark indices used by the mapping above. Stable
        // model contract — see the model card linked in `fromMediaPipeLandmarkIndex`.
        internal const val MP_NOSE = 0
        internal const val MP_LEFT_SHOULDER = 11
        internal const val MP_RIGHT_SHOULDER = 12
        internal const val MP_LEFT_ELBOW = 13
        internal const val MP_RIGHT_ELBOW = 14
        internal const val MP_LEFT_WRIST = 15
        internal const val MP_RIGHT_WRIST = 16
        internal const val MP_LEFT_HIP = 23
        internal const val MP_RIGHT_HIP = 24
        internal const val MP_LEFT_KNEE = 25
        internal const val MP_RIGHT_KNEE = 26
        internal const val MP_LEFT_ANKLE = 27
        internal const val MP_RIGHT_ANKLE = 28

        /** Number of raw landmarks the MediaPipe Pose Landmarker model emits. */
        const val MEDIAPIPE_LANDMARK_COUNT = 33
    }
}

/**
 * The bone connectivity of the [Joint] skeleton — each pair is two joints that are
 * anatomically linked and should be drawn as a line segment by a skeleton overlay.
 *
 * Used by the AR Body Tracker demo to render the live skeleton; exposed publicly so apps
 * building their own overlay do not have to re-derive the topology.
 */
val SKELETON_BONES: List<Pair<Joint, Joint>> = listOf(
    Joint.ROOT to Joint.SPINE,
    Joint.SPINE to Joint.NECK,
    Joint.NECK to Joint.HEAD,
    Joint.NECK to Joint.LEFT_SHOULDER,
    Joint.LEFT_SHOULDER to Joint.LEFT_ELBOW,
    Joint.LEFT_ELBOW to Joint.LEFT_HAND,
    Joint.NECK to Joint.RIGHT_SHOULDER,
    Joint.RIGHT_SHOULDER to Joint.RIGHT_ELBOW,
    Joint.RIGHT_ELBOW to Joint.RIGHT_HAND,
    Joint.ROOT to Joint.LEFT_HIP,
    Joint.LEFT_HIP to Joint.LEFT_KNEE,
    Joint.LEFT_KNEE to Joint.LEFT_FOOT,
    Joint.ROOT to Joint.RIGHT_HIP,
    Joint.RIGHT_HIP to Joint.RIGHT_KNEE,
    Joint.RIGHT_KNEE to Joint.RIGHT_FOOT,
)
