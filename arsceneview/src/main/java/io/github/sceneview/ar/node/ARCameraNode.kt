package io.github.sceneview.ar.node

import com.google.android.filament.Engine
import com.google.ar.core.Camera
import com.google.ar.core.Frame
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.arcore.getProjectionTransform
import io.github.sceneview.ar.arcore.position
import io.github.sceneview.ar.arcore.quaternion
import io.github.sceneview.node.CameraNode

/**
 * Represents a virtual camera, which determines the perspective through which the scene is viewed.
 *
 * If the camera is part of an AR scene, then the camera automatically tracks the
 * camera pose from ARCore.
 *
 * The following methods will throw [ ] when called:
 * - [parent] - CameraNode's parent cannot be changed, it is always the scene.
 * - [position] - CameraNode's position cannot be changed, it is controlled by the ARCore camera
 * pose.
 * - [rotation] - CameraNode's rotation cannot be changed, it is controlled by the ARCore camera
 * pose.
 *
 * All other functionality in Node is supported. You can access the position and rotation of the
 * camera, assign a collision shape to the camera, or add children to the camera. Disabling the
 * camera turns off rendering.
 */
open class ARCameraNode(engine: Engine) : CameraNode(engine) {

    /**
     * The virtual camera pose in world space for rendering AR content onto the latest frame.
     *
     * This is an OpenGL camera pose with +X pointing right, +Y pointing up, and -Z pointing in the
     * direction the camera is looking, with "right" and "up" being relative to current logical
     * display orientation.
     *
     * Note: This pose is only useful when [trackingState] returns [TrackingState.TRACKING] and
     * otherwise should not be used.
     */
    open var pose: Pose? = null
        protected set(value) {
            if (field != value) {
                field = value
                value?.let {
                    // Write the world translation + rotation directly from the ARCore Pose
                    // components instead of `worldTransform = it.transform`, which allocated a
                    // FloatArray(16) + Transform every frame only to decompose it straight back
                    // into TRS. The camera pose refreshes on every tracked frame (#2266 /
                    // umbrella #2263).
                    worldPosition = it.position
                    worldQuaternion = it.quaternion
                }
            }
        }

    /**
     * The TrackingState of this Node.
     *
     * Updated on each frame
     */
    open var trackingState = TrackingState.STOPPED
        protected set(value) {
            if (field != value) {
                field = value
                onTrackingStateChanged(value)
            }
        }

    var session: Session? = null
    var frame: Frame? = null

    open fun update(session: Session, frame: Frame) {
        this.session = session
        this.frame = frame
        onCameraUpdated(frame.camera)
    }

    /**
     * Updates the current projection and pose of the camera in world space.
     *
     * The Camera projection and pose is updated during calls to session.update() as ARCore refines
     * its estimate of the world.
     */
    open fun onCameraUpdated(camera: Camera) {
        trackingState = camera.trackingState

        // Update the node's transformation properties to match the tracked pose
        pose = camera.displayOrientedPose
        // Update the projection matrix.
        projectionTransform = camera.getProjectionTransform(near, far)
    }

    open fun onTrackingStateChanged(trackingState: TrackingState) {
    }
}