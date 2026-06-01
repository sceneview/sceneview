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
import io.github.sceneview.math.Transform
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

    /**
     * Cached AR projection [Transform] plus the `(near, far)` it was computed with, so a fresh
     * one is only built when an input actually changes.
     *
     * ARCore's `Camera.getProjectionMatrix(near, far)` is invariant across frames unless the
     * near/far clip planes change **or** the display geometry changes (rotation / resize, applied
     * via `Session.setDisplayGeometry`). Recomputing it every tracked frame allocated a
     * `FloatArray(16)` + a [Transform] (see [getProjectionTransform]) and fired two redundant
     * JNI calls (`getProjectionMatrix` + the Filament `projectionTransform` setter) for an
     * identical result — a per-frame waste flagged by the hot-path audit (AR5 / umbrella #2263).
     *
     * The display-geometry half of the dirty signal is read straight from the frame
     * ([Frame.hasDisplayGeometryChanged]) — the same authoritative flag [ARCameraStream] uses to
     * rebuild its UV coordinates — so the cache can never freeze a stale projection after a device
     * rotation. Render-thread only (mutated under [update]).
     */
    private var cachedProjectionTransform: Transform? = null
    private var cachedProjectionNear: Float = Float.NaN
    private var cachedProjectionFar: Float = Float.NaN

    /**
     * Whether the AR display geometry (rotation / viewport) changed on the frame currently being
     * processed by [update] — set from [Frame.hasDisplayGeometryChanged] before [onCameraUpdated]
     * runs and consumed there to invalidate [cachedProjectionTransform]. Defaults to `true` so a
     * direct [onCameraUpdated] call (outside the [update] path) always recomputes — never reuses a
     * potentially stale cache. Render-thread only.
     */
    private var displayGeometryChanged: Boolean = true

    open fun update(session: Session, frame: Frame) {
        this.session = session
        this.frame = frame
        displayGeometryChanged = frame.hasDisplayGeometryChanged()
        onCameraUpdated(frame.camera)
    }

    /**
     * Updates the current projection and pose of the camera in world space.
     *
     * The Camera projection and pose is updated during calls to session.update() as ARCore refines
     * its estimate of the world.
     *
     * The projection [Transform] is recomputed only when [near] / [far] change or the AR display
     * geometry changed on this frame ([Frame.hasDisplayGeometryChanged]); otherwise the cached
     * projection is reused, avoiding a per-frame `FloatArray(16)` + [Transform] allocation and two
     * redundant JNI calls for an identical result (AR5 / umbrella #2263).
     */
    open fun onCameraUpdated(camera: Camera) {
        trackingState = camera.trackingState

        // Update the node's transformation properties to match the tracked pose
        pose = camera.displayOrientedPose

        // Update the projection matrix — but only when an input actually changed. The cache is
        // invalidated on a near/far change or a display-geometry change so it can never freeze a
        // stale matrix after a rotation/resize.
        val near = near
        val far = far
        if (shouldRecomputeProjection(
                cached = cachedProjectionTransform != null,
                displayGeometryChanged = displayGeometryChanged,
                near = near,
                far = far,
                cachedNear = cachedProjectionNear,
                cachedFar = cachedProjectionFar,
            )
        ) {
            val transform = camera.getProjectionTransform(near, far)
            cachedProjectionTransform = transform
            cachedProjectionNear = near
            cachedProjectionFar = far
            projectionTransform = transform
        }

        // Consume the one-frame display-geometry signal: reset to `true` so any direct
        // onCameraUpdated() call outside the update() path conservatively recomputes the
        // projection rather than trusting a stale flag from an earlier frame.
        displayGeometryChanged = true
    }

    open fun onTrackingStateChanged(trackingState: TrackingState) {
    }
}

/**
 * Pure decision for whether [ARCameraNode]'s cached AR projection [Transform] must be recomputed.
 *
 * Recompute iff there is no cached value yet, the display geometry changed this frame, or the
 * near/far clip planes differ from the values the cache was built with. Extracted as an `internal`
 * top-level function — like [io.github.sceneview.ar.arcore.buildPointCloudPositions] — so the AR5
 * (#2263) caching/invalidation logic can be pinned by a JVM unit test without instantiating the
 * ARCore `Camera` / Filament JNI surface.
 *
 * @param cached               Whether a projection is already cached.
 * @param displayGeometryChanged Whether `Frame.hasDisplayGeometryChanged()` reported a change.
 * @param near                 Current near clip plane.
 * @param far                  Current far clip plane.
 * @param cachedNear           Near clip plane the cache was built with.
 * @param cachedFar            Far clip plane the cache was built with.
 * @return `true` when the projection must be rebuilt; `false` to reuse the cached one.
 */
internal fun shouldRecomputeProjection(
    cached: Boolean,
    displayGeometryChanged: Boolean,
    near: Float,
    far: Float,
    cachedNear: Float,
    cachedFar: Float,
): Boolean = !cached ||
    displayGeometryChanged ||
    near != cachedNear ||
    far != cachedFar