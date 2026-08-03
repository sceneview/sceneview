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
import io.github.sceneview.utils.setCustomProjection

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
 * The **projection** is owned by ARCore too: it is re-derived from the physical camera's intrinsics
 * on every tracked frame, so [focalLength] has no effect here and [updateProjection] restores
 * ARCore's matrix rather than building one from a lens (#2950). Set [near] / [far] to move the clip
 * planes — those *are* honoured, and ARCore rebuilds its projection around them.
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
     * rotation. That flag only covers the inputs ARCore owns, though, so a third dirty signal
     * ([projectionInvalidated]) covers the projection being rewritten from outside the ARCore path
     * — the case a rotation-only guard structurally cannot see (#2950). Render-thread only
     * (mutated under [update]).
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

    /**
     * Whether something outside the ARCore path rewrote the Filament camera's projection since
     * [cachedProjectionTransform] was applied — in which case the cache describes a matrix the
     * camera no longer holds, and reusing it would leave the wrong projection in place forever.
     *
     * Set by the [updateProjection] fallback, the one remaining path that rebuilds the projection
     * from a generic focal length; cleared as soon as ARCore's projection is applied again.
     * Render-thread only. (#2950)
     */
    private var projectionInvalidated: Boolean = false

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
                projectionInvalidated = projectionInvalidated,
                near = near,
                far = far,
                cachedNear = cachedProjectionNear,
                cachedFar = cachedProjectionFar,
            )
        ) {
            applyARProjection(camera, near, far)
        }

        // Consume the one-frame display-geometry signal: reset to `true` so any direct
        // onCameraUpdated() call outside the update() path conservatively recomputes the
        // projection rather than trusting a stale flag from an earlier frame.
        displayGeometryChanged = true
    }

    /**
     * Rebuilds the projection from the camera's focal length — **except** on an [ARCameraNode],
     * whose projection is owned by ARCore.
     *
     * [CameraNode.updateProjection] derives a projection from a generic lens (defaulting to the
     * 28 mm `_focalLength`, i.e. a 46.4° vertical field of view on Filament's 35 mm frame). For a
     * plain 3D camera that is the whole point; for an AR camera it discards the projection ARCore
     * derived from the physical camera's real intrinsics, so virtual content is drawn at the wrong
     * scale and — far worse — stops being registered to the real world, sliding across the room as
     * the device rotates. `ARSceneView` calls it from its surface-resize callback, which is enough
     * to lose ARCore's projection for the rest of the session (#2950).
     *
     * A focal length also cannot express ARCore's off-centre principal point, so re-deriving the
     * matrix from ARCore rather than from a lens is the only way to reproduce it term for term.
     *
     * The generic behaviour is kept as a fallback for the window before the first AR frame, when
     * there is no ARCore projection to restore yet; the cache is marked invalid there so the next
     * tracked frame re-applies ARCore's projection over it.
     */
    override fun updateProjection(
        focalLength: Double,
        near: Float,
        far: Float,
        aspect: Double
    ) {
        val arCamera = frame?.camera
        if (arCamera != null) {
            applyARProjection(arCamera, near, far)
        } else {
            super.updateProjection(focalLength, near, far, aspect)
            projectionInvalidated = true
        }
    }

    /**
     * Applies ARCore's projection for `(near, far)` to the Filament camera and records it as the
     * cached one, so [shouldRecomputeProjection] keeps describing what the camera actually holds.
     *
     * Writes the clip planes explicitly rather than through `projectionTransform = transform`:
     * that setter forwards to `Camera.setCustomProjection(value)`, whose `near` / `far` **default
     * to the values Filament already holds** (`getNear()` / `cullingFar`), so it would apply a
     * matrix built for one set of clip planes while leaving Filament reporting another. That
     * matters because [near] / [far] are not backing fields — they read straight back off the
     * Filament camera, and [onCameraUpdated] feeds those getters into [shouldRecomputeProjection]
     * against [cachedProjectionNear] / [cachedProjectionFar]. Not persisting them would make a
     * caller's `near = …` survive exactly one frame before the next tracked frame read the
     * unchanged getter, disagreed with the cache and recomputed it away.
     */
    private fun applyARProjection(arCamera: Camera, near: Float, far: Float) {
        val transform = arCamera.getProjectionTransform(near, far)
        cachedProjectionTransform = transform
        cachedProjectionNear = near
        cachedProjectionFar = far
        projectionInvalidated = false
        camera.setCustomProjection(transform, near.toDouble(), far.toDouble())
    }

    open fun onTrackingStateChanged(trackingState: TrackingState) {
    }
}

/**
 * Pure decision for whether [ARCameraNode]'s cached AR projection [Transform] must be recomputed.
 *
 * Recompute iff there is no cached value yet, the projection was rewritten from outside the ARCore
 * path, the display geometry changed this frame, or the near/far clip planes differ from the values
 * the cache was built with. Extracted as an `internal` top-level function — like
 * [io.github.sceneview.ar.arcore.buildPointCloudPositions] — so the AR5 (#2263)
 * caching/invalidation logic can be pinned by a JVM unit test without instantiating the ARCore
 * `Camera` / Filament JNI surface.
 *
 * The [projectionInvalidated] term exists because the other four describe only the *inputs* ARCore
 * owns. They are all false while another component overwrites the Filament camera's projection
 * underneath the cache, so without it the cache stays "correct about what it last computed and
 * wrong about what the camera currently holds" — and nothing else ever re-derives the projection,
 * making the wrong value stable for the life of the session (#2950).
 *
 * @param cached               Whether a projection is already cached.
 * @param displayGeometryChanged Whether `Frame.hasDisplayGeometryChanged()` reported a change.
 * @param projectionInvalidated Whether the Filament camera's projection was rebuilt outside the
 * ARCore path since the cached one was applied.
 * @param near                 Current near clip plane.
 * @param far                  Current far clip plane.
 * @param cachedNear           Near clip plane the cache was built with.
 * @param cachedFar            Far clip plane the cache was built with.
 * @return `true` when the projection must be rebuilt; `false` to reuse the cached one.
 */
internal fun shouldRecomputeProjection(
    cached: Boolean,
    displayGeometryChanged: Boolean,
    projectionInvalidated: Boolean,
    near: Float,
    far: Float,
    cachedNear: Float,
    cachedFar: Float,
): Boolean = !cached ||
    projectionInvalidated ||
    displayGeometryChanged ||
    near != cachedNear ||
    far != cachedFar