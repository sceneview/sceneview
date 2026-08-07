package io.github.sceneview.components

import com.google.android.filament.Camera
import com.google.android.filament.Camera.Projection
import com.google.android.filament.LightManager
import dev.romainguy.kotlin.math.Float2
import dev.romainguy.kotlin.math.Float4
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Position
import io.github.sceneview.math.Transform
import io.github.sceneview.utils.cullingProjectionTransform
import io.github.sceneview.utils.forwardDirection
import io.github.sceneview.utils.leftDirection
import io.github.sceneview.utils.lookAt
import io.github.sceneview.utils.modelTransform
import io.github.sceneview.utils.projectionTransform
import io.github.sceneview.utils.scaling
import io.github.sceneview.utils.setCustomProjection
import io.github.sceneview.utils.setScaling
import io.github.sceneview.utils.upDirection
import io.github.sceneview.utils.viewToRay
import io.github.sceneview.utils.viewToWorld
import io.github.sceneview.utils.viewTransform
import io.github.sceneview.utils.worldToView

typealias FilamentCamera = Camera

interface CameraComponent : Component {

    val camera: FilamentCamera
        get() = checkNotNull(engine.getCameraComponent(entity)) {
            "Entity $entity does not have a Camera component. " +
                    "Only use CameraComponent on entities created with Engine.createCamera()."
        }

    /**
     * Sets the projection matrix from a frustum defined by six planes.
     *
     * @param projection type of projection to use
     * @param left distance in world units from the camera to the left plane,
     * at the near plane. Precondition: `left` != `right`
     * @param right distance in world units from the camera to the right plane,
     * at the near plane. Precondition: `left` != `right`
     * @param bottom distance in world units from the camera to the bottom plane,
     * at the near plane. Precondition: `bottom` != `top`
     * @param top distance in world units from the camera to the top plane,
     * at the near plane. Precondition: `bottom` != `top`
     * @param near distance in world units from the camera to the near plane.
     * The near plane's position in view space is z = -`near`.
     * Precondition:
     * `near` > 0 for [Projection.PERSPECTIVE] or
     * `near` != `far` for [Projection.ORTHO].
     * @param far distance in world units from the camera to the far plane.
     * The far plane's position in view space is z = -`far`.
     * Precondition:
     * `far` > `near`
     * for [Projection.PERSPECTIVE] or
     * `far` != `near`
     * for [Projection.ORTHO].
     *
     * These parameters are silently modified to meet the preconditions above.
     *
     * @see Projection
     */
    fun setProjection(
        projection: Projection,
        left: Double,
        right: Double,
        bottom: Double,
        top: Double,
        near: Double,
        far: Double
    ) = camera.setProjection(projection, left, right, bottom, top, near, far)

    /**
     * Sets the projection matrix from the field-of-view.
     *
     * @param fovInDegrees full field-of-view in degrees.
     * 0 < `fovInDegrees` < 180
     * @param aspect aspect ratio width/height. `aspect` > 0
     * @param near distance in world units from the camera to the near plane.
     * The near plane's position in view space is z = -`near`.
     * Precondition:
     * `near` > 0 for [Projection.PERSPECTIVE] or
     * `near` != `far` for [Projection.ORTHO].
     * @param far distance in world units from the camera to the far plane.
     * The far plane's position in view space is z = -`far`.
     * Precondition:
     * `far` > `near`
     * for [Projection.PERSPECTIVE] or
     * `far` != `near`
     * for [Projection.ORTHO].
     * @param direction direction of the field-of-view parameter.
     *
     * These parameters are silently modified to meet the preconditions above.
     *
     * @see Camera.Fov
     */
    fun setProjection(
        fovInDegrees: Double,
        aspect: Double,
        near: Double,
        far: Double,
        direction: Camera.Fov
    ) = camera.setProjection(fovInDegrees, aspect, near, far, direction)

    /**
     * Sets the projection matrix from the focal length.
     *
     * @param focalLength lens's focal length in millimeters. `focalLength` > 0
     * @param aspect aspect ratio width/height. `aspect` > 0
     * @param near distance in world units from the camera to the near plane.
     * The near plane's position in view space is z = -`near`.
     * Precondition:
     * `near` > 0 for [Projection.PERSPECTIVE] or
     * `near` != `far` for [Projection.ORTHO].
     * @param far distance in world units from the camera to the far plane.
     * The far plane's position in view space is z = -`far`.
     * Precondition:
     * `far` > `near`
     * for [Projection.PERSPECTIVE] or
     * `far` != `near`
     * for [Projection.ORTHO].
     */
    fun setLensProjection(focalLength: Double, aspect: Double, near: Double, far: Double) =
        camera.setLensProjection(focalLength, aspect, near, far)

    /**
     * Sets a custom projection matrix.
     *
     * The projection matrices must define an NDC system that must match the OpenGL convention, that
     * is all 3 axis are mapped to [-1, 1].
     *
     * @param inProjection custom projection matrix for rendering.
     * @param inProjectionForCulling custom projection matrix for culling.
     * @param near distance in world units from the camera to the near plane.
     * The near plane's position in view space is z = -`near`.
     * Precondition:
     * `near` > 0 for [Projection.PERSPECTIVE] or
     * `near` != `far` for [Projection.ORTHO].
     * @param far distance in world units from the camera to the far plane.
     * The far plane's position in view space is z = -`far`.
     * Precondition:
     * `far` > `near`
     * for [Projection.PERSPECTIVE] or
     * `far` != `near`
     * for [Projection.ORTHO].
     */
    fun setCustomProjection(
        inProjection: Transform,
        near: Double = camera.near.toDouble(),
        far: Double = camera.cullingFar.toDouble(),
        inProjectionForCulling: Transform = inProjection
    ) = camera.setCustomProjection(inProjection, near, far, inProjectionForCulling)

    /**
     * Sets an additional matrix that scales the projection matrix.
     *
     * This is useful to adjust the aspect ratio of the camera independent from its projection.
     * First, pass an aspect of 1.0 to setProjection. Then set the scaling with the desired aspect
     * ratio:
     *
     * ```
     * double aspect = width / height;
     *
     * // with Fov.HORIZONTAL passed to setProjection:
     * camera.setScaling(1.0, aspect);
     *
     * // with Fov.VERTICAL passed to setProjection:
     * camera.setScaling(1.0 / aspect, 1.0);
     * ```
     *
     * By default, this is an identity matrix.
     *
     * @param scaling  horizontal and vertical scaling to be applied after the projection matrix.
     *
     * @see Camera.setProjection
     * @see Camera.setLensProjection
     * @see Camera.setCustomProjection
     */
    fun setScaling(scaling: Float2) = camera.setScaling(scaling)

    /**
     * Sets an additional matrix that shifts (translates) the projection matrix.
     *
     * The shift parameters are specified in NDC coordinates, that is, if the translation must be
     * specified in pixels, the xshift and yshift parameters be scaled by 1.0 / viewport.width and
     * 1.0 / viewport.height respectively.
     *
     * @param xShift horizontal shift in NDC coordinates applied after the projection
     * @param yShift vertical shift in NDC coordinates applied after the projection
     *
     * @see Camera.setProjection
     * @see Camera.setLensProjection
     * @see Camera.setCustomProjection
     */
    fun setShift(xShift: Double, yShift: Double) = camera.setShift(xShift, yShift)

    /**
     * Sets the camera's model matrix.
     *
     * @param eye position of the camera in world space
     * @param center position of the point in world space the camera is looking at
     * @param up unit vector denoting the camera's "up" direction
     */
    fun lookAt(eye: Position, center: Position, up: Direction) = camera.lookAt(eye, center, up)

    /**
     * Gets the distance to the near plane
     */
    val near: Float get() = camera.near

    val far get() = cullingFar

    /**
     * Gets the distance to the far plane
     */
    val cullingFar get() = camera.cullingFar

    /**
     * Retrieves the camera's projection matrix. The projection matrix used for rendering always has
     * its far plane set to infinity. This is why it may differ from the matrix set through
     * setProjection() or setLensProjection().
     *
     * Transform containing the camera's projection as a column-major matrix.
     */
    var projectionTransform
        get() = camera.projectionTransform
        set(value) {
            camera.projectionTransform = value
        }

    /**
     * Retrieves the camera's culling matrix. The culling matrix is the same as the projection
     * matrix, except the far plane is finite.
     *
     * Transform containing the camera's projection as a column-major matrix.
     */
    val cullingProjectionTransform get() = camera.cullingProjectionTransform

    /**
     * Returns the scaling amount used to scale the projection matrix.
     *
     * The diagonal of the scaling matrix applied after the projection matrix.
     *
     * @see setScaling
     */
    val scaling: Float4 get() = camera.scaling

    /**
     * The camera's model matrix.
     *
     * Helper method to set the camera's entity transform component.
     *
     * The model matrix encodes the camera position and orientation, or pose.
     *
     * Remember that the Camera "looks" towards its -z axis.
     *
     * This has the same effect as calling:
     * ```
     * engine.getTransformManager().setTransform(
     * engine.getTransformManager().getInstance(camera->getEntity()), viewMatrix);
     * ```
     *
     * Transform containing the camera's pose as a column-major matrix.
     * The camera position and orientation provided as a **rigid transform** matrix.
     */
    var modelTransform: Transform
        get() = camera.modelTransform
        set(value) {
            camera.modelTransform = value
        }

    /**
     * Retrieves the camera's view matrix. The view matrix is the inverse of the model matrix.
     *
     * Transform containing the camera's column-major view matrix.
     */
    val viewTransform: Transform get() = camera.viewTransform

    /**
     * Retrieves the camera left unit vector in world space, that is a unit vector that points to
     * the left of the camera.
     *
     * Float3 Direction containing the camera's left vector in world units.
     */
    val leftDirection: Direction get() = camera.leftDirection

    /**
     * Retrieves the camera up unit vector in world space, that is a unit vector that points up with
     * respect to the camera.
     *
     * Float3 Direction containing the camera's up vector in world units.
     */
    val upDirection: Direction get() = camera.upDirection

    /**
     * Retrieves the camera forward unit vector in world space, that is a unit vector that points
     * in the direction the camera is looking at.
     *
     * Float3 Direction containing the camera's forward vector in world units.
     */
    val forwardDirection: Direction get() = camera.forwardDirection

    /**
     * Get a world space position from a view space position.
     *
     * @param viewPosition normalized view coordinate
     * x = (0 = left, 0.5 = center, 1 = right)
     * y = (0 = bottom, 0.5 = center, 1 = top)
     * @param z Z is used for the depth between 1 and 0
     * (1 = near, 0 = infinity).
     *
     * @return The world position of the point.
     */
    fun viewToWorld(viewPosition: Float2, z: Float = 1.0f) = camera.viewToWorld(viewPosition, z)

    /**
     * Get a view space position from a world position.
     *
     * The device coordinate space is unaffected by the orientation of the device.
     *
     * Returns `null` when [worldPosition] is **behind the camera** (behind the near plane): such a
     * point has no view-space position and was historically projected to a finite, mirrored
     * coordinate on the wrong side of the view. Handle the `null` case rather than draw at a bogus
     * point. See [io.github.sceneview.utils.worldToView].
     *
     * @param worldPosition The world position to convert.
     *
     * @return normalized view coordinate, or `null` if [worldPosition] is behind the near plane.
     * x = (0 = left, 0.5 = center, 1 = right)
     * y = (0 = bottom, 0.5 = center, 1 = top)
     */
    fun worldToView(worldPosition: Position): Float2? = camera.worldToView(worldPosition)

    /**
     * Calculates a ray in world space going from the near-plane of the camera and through a point in
     * view space.
     *
     * @param viewPosition normalized view coordinate
     * x = (0 = left, 0.5 = center, 1 = right)
     * y = (0 = bottom, 0.5 = center, 1 = top)
     *
     *  @return A Ray from the camera near to far / infinity
     */
    fun viewToRay(viewPosition: Float2) = camera.viewToRay(viewPosition)

    /**
     * Sets this camera's exposure (default is f/16, 1/125s, 100 ISO)
     *
     * The exposure ultimately controls the scene's brightness, just like with a real camera.
     * The default values provide adequate exposure for a camera placed outdoors on a sunny day
     * with the sun at the zenith.
     *
     * With the default parameters, the scene must contain at least one Light of intensity similar
     * to the sun (e.g.: a 100,000 lux directional light) and/or an indirect light of appropriate
     * intensity (30,000).
     *
     * @param aperture Aperture in f-stops, clamped between 0.5 and 64.
     * A lower aperture value increases the exposure, leading to a brighter scene.
     * Realistic values are between 0.95 and 32.
     * @param shutterSpeed Shutter speed in seconds, clamped between 1/25,000 and 60.
     * A lower shutter speed increases the exposure. Realistic values are between 1/8000 and 30.
     * @param sensitivity Sensitivity in ISO, clamped between 10 and 204,800.
     * A higher sensitivity increases the exposure. Realistic values are between 50 and 25600.
     *
     * @see LightManager
     */
    fun setExposure(aperture: Float, shutterSpeed: Float, sensitivity: Float) =
        camera.setExposure(aperture, shutterSpeed, sensitivity)

    /**
     * Sets this camera's exposure directly. Calling this method will set the aperture to 1.0, the
     * shutter speed to 1.2 and the sensitivity will be computed to match the requested exposure
     * (for a desired exposure of 1.0, the sensitivity will be set to 100 ISO).
     *
     * This method is useful when trying to match the lighting of other engines or tools.
     * Many engines/tools use unit-less light intensities, which can be matched by setting the
     * exposure manually. This can be typically achieved by setting the exposure to 1.0.
     *
     * @see LightManager
     */
    fun setExposure(exposure: Float) = camera.setExposure(exposure)

    /**
     * Gets the aperture in f-stops
     */
    val aperture: Float get() = camera.aperture

    /**
     * Gets the shutter speed in seconds
     */
    val shutterSpeed: Float get() = camera.shutterSpeed

    /**
     * Gets the focal length in meters
     */
    val focalLength get() = camera.focalLength

    /**
     * The distance from the camera to the focus plane in world units
     *
     * Distance from the camera to the focus plane in world units. Must be positive and larger than
     * the camera's near clipping plane.
     */
    var focusDistance: Float
        get() = camera.focusDistance
        set(value) {
            camera.focusDistance = value
        }

    /**
     * Gets the sensitivity in ISO
     */
    val sensitivity: Float get() = camera.sensitivity
}