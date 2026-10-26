package io.github.sceneview.camera

import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Float4
import dev.romainguy.kotlin.math.Quaternion
import dev.romainguy.kotlin.math.cross
import dev.romainguy.kotlin.math.length
import dev.romainguy.kotlin.math.lookAt
import dev.romainguy.kotlin.math.normalize
import dev.romainguy.kotlin.math.rotation
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Position
import io.github.sceneview.math.Transform
import io.github.sceneview.math.quaternion
import io.github.sceneview.node.CameraNode

/**
 * Where a camera stands and which way it faces, in world space: a [position] and a unit
 * [quaternion].
 *
 * The rotation follows the Filament camera convention: an identity [quaternion] looks down `-Z`
 * with `+Y` up, so [forward] is `-Z` rotated by [quaternion].
 *
 * [CameraShot] describes a camera by the subject it orbits. A pose describes it by where it is,
 * which is what a keyframed move needs: a travelling or a dolly is a straight line through
 * space, not an arc around a subject. [CameraPosePath] interpolates poses; [CameraNode.applyPose]
 * writes one to a camera.
 *
 * @property position Camera position, in world units.
 * @property quaternion Camera orientation. Expected normalized; [CameraPosePath] normalizes every
 * keyframe rotation before it interpolates.
 */
data class CameraPose(
    val position: Position = Position(),
    val quaternion: Quaternion = Quaternion(),
) {

    /** The unit direction the camera looks along: `-Z` rotated by [quaternion]. */
    val forward: Direction get() = rotate(quaternion, Float3(0f, 0f, -1f))

    /** The unit direction of the camera's screen-right axis: `+X` rotated by [quaternion]. */
    val right: Direction get() = rotate(quaternion, Float3(1f, 0f, 0f))

    /** This pose as a node transform, with a unit scale. */
    fun transform(): Transform = Transform(position, quaternion)

    companion object {

        /**
         * The pose of a camera at [eye] that looks at [target], with [up] as the world up.
         *
         * When [eye] and [target] coincide, the camera keeps the identity rotation. When the line
         * of sight is parallel to [up] (straight down or straight up), the screen-up axis falls
         * back to world `-Z` so the result is never `NaN`.
         *
         * @param eye Camera position, in world units.
         * @param target The point the camera looks at.
         * @param up World up. Defaults to `+Y`.
         */
        fun lookingAt(
            eye: Position,
            target: Position,
            up: Direction = WORLD_UP,
        ): CameraPose = CameraPose(eye, lookRotation(eye, target, up) ?: Quaternion())

        /**
         * The rotation of a camera at [eye] looking at [target], or `null` when [eye] and
         * [target] coincide and no direction can be derived.
         */
        internal fun lookRotation(eye: Position, target: Position, up: Direction): Quaternion? {
            val toTarget = target - eye
            val distance = length(toTarget)
            if (!(distance > DEGENERATE_LENGTH)) return null
            val forward = toTarget / distance
            val safeUp = when {
                length(cross(forward, up)) > DEGENERATE_LENGTH -> up
                length(cross(forward, FALLBACK_UP)) > DEGENERATE_LENGTH -> FALLBACK_UP
                else -> Float3(1f, 0f, 0f)
            }
            return normalize(lookAt(eye = eye, target = target, up = safeUp).quaternion)
        }

        internal fun rotate(quaternion: Quaternion, vector: Float3): Float3 =
            (rotation(quaternion) * Float4(vector, 0f)).xyz

        /** World up assumed by [lookingAt] and the path presets. */
        internal val WORLD_UP = Float3(0f, 1f, 0f)

        /** Screen-up used when the line of sight is parallel to the requested up axis. */
        private val FALLBACK_UP = Float3(0f, 0f, -1f)

        /** Below this length a direction is treated as zero. */
        private const val DEGENERATE_LENGTH = 1e-6f
    }
}

/**
 * Something that gives a camera pose for any time on a fixed timeline: the common shape of
 * [CameraPosePath] and [CameraPath], and what [CameraPathPlayer] plays.
 *
 * [poseAt] must be a pure function of its argument: the same time always gives the same pose,
 * whatever was asked before. That is what lets a player scrub, a renderer step frame by frame at
 * a fixed rate, and two runs produce the same images.
 */
interface CameraTimeline {

    /** Length of the timeline, in seconds. `0` for an empty or single-pose timeline. */
    val durationSeconds: Float

    /** `true` when the timeline repeats: times past [durationSeconds] wrap around to the start. */
    val loop: Boolean

    /**
     * The camera pose at [seconds] from the start of the timeline.
     *
     * Times before `0` hold the first pose and times after [durationSeconds] hold the last one,
     * unless [loop] is set, in which case they wrap.
     */
    fun poseAt(seconds: Float): CameraPose
}

/** This shot as a [CameraPose]: its [CameraShot.eye], looking at its target with `+Y` up. */
fun CameraShot.toPose(): CameraPose = CameraPose(eye, transform().quaternion)

/**
 * Places this camera at [pose], immediately (no smoothing).
 *
 * Call it from the main thread, typically from `SceneView`'s `onFrame`. While a camera
 * manipulator is installed, the manipulator's own pose overwrites this one on the next frame, so
 * pass `cameraManipulator = null` to `SceneView` while a path owns the camera.
 *
 * The node's scale is kept.
 */
fun CameraNode.applyPose(pose: CameraPose) {
    transform(position = pose.position, quaternion = pose.quaternion, smooth = false)
}

/**
 * Places this camera at the pose [timeline] gives for [seconds], and returns that pose.
 *
 * The same main-thread and manipulator rules as [applyPose] apply.
 */
fun CameraNode.applyCameraPath(timeline: CameraTimeline, seconds: Float): CameraPose =
    timeline.poseAt(seconds).also { applyPose(it) }
