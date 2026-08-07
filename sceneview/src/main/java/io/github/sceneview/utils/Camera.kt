package io.github.sceneview.utils

import com.google.android.filament.Camera
import com.google.android.filament.Camera.Projection
import com.google.android.filament.utils.pow
import dev.romainguy.kotlin.math.Float2
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Float4
import dev.romainguy.kotlin.math.Ray
import dev.romainguy.kotlin.math.inverse
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Position
import io.github.sceneview.math.Transform
import io.github.sceneview.math.almostEquals
import io.github.sceneview.math.copyColumnsInto
import io.github.sceneview.math.toColumnsDoubleArray
import io.github.sceneview.math.toDirection
import io.github.sceneview.math.toFloat4
import io.github.sceneview.math.toTransform
import io.github.sceneview.math.worldToView as coreWorldToView
import kotlin.math.log2

/**
 * Main-thread scratch buffer reused by the [Camera.modelTransform] setter to
 * avoid allocating a `FloatArray(16)` on every camera-pose write (per frame).
 *
 * Safe because Filament JNI calls run exclusively on the main thread and
 * `Camera.setModelMatrix(FloatArray)` copies the array into native memory
 * synchronously before returning.
 */
private val modelMatrixScratch = FloatArray(16)

/**
 * Computes the camera's EV100 from exposure settings.
 *
 * Exposure value (EV) is a number that represents a combination of a camera's shutter speed and
 * f-number, such that all combinations that yield the same exposure have the same EV  (for any
 * fixed scene luminance)
 *
 * Reference: https://en.wikipedia.org/wiki/Exposure_value
 */
val Camera.ev100: Float
    get() = log2((aperture * aperture) / shutterSpeed * 100.0f / sensitivity)

/**
 * Computes the exposure normalization factor from the camera's EV100.
 *
 * Calculate a unit-less intensity scale from the actual Filament camera settings.
 *
 * This method is useful when trying to match the lighting of other engines or tools.
 * Many engines/tools use unit-less light intensities, which can be matched by setting the exposure
 * manually.
 *
 * Filament uses a physically based camera model, with aperture, shutter speed, and sensitivity
 * (ISO).
 * This plays nicely with physical light units (for instance if you measure the sun outside on a
 * sunny day in California you'll get ~110,000 lux and if you set your camera settings to
 * ƒ/16 1/125s ISO 100, exposure will be correct). However many engines use "relative exposure"
 * instead, where exposure is just a float and set to 1.0 by default.
 *
 * ARCore's light estimation was designed to work with those engines.
 * So we scale light intensities estimations by multiplying the values provided by ARCore
 * by 1.0 / log2((aperture * aperture) / shutterSpeed * 100.0f / sensitivity).
 * This should raise ARCore's values to something that's physically plausible for the chosen
 * exposure.
 *
 * **Sources:**
 *  - [https://github.com/ThomasGorisse/SceneformMaintained/pull/156#issuecomment-911873565]
 *  - [https://github.com/google/filament/blob/main/filament/src/Exposure.cpp#L46]
 */
val Camera.exposureFactor get() = 1.0f / ev100

val Camera.illuminance get() = illuminance(ev100)
fun Camera.illuminance(ev100: Float) = 2.5f * pow(2.0f, ev100)

val Camera.luminance get() = luminance(ev100)
fun Camera.luminance(ev100: Float) = pow(2.0f, ev100 - 3.0f)

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
fun Camera.setCustomProjection(
    inProjection: Transform,
    near: Double = getNear().toDouble(),
    far: Double = cullingFar.toDouble(),
    inProjectionForCulling: Transform = inProjection
) = if (inProjection != inProjectionForCulling) {
    setCustomProjection(
        inProjection.toColumnsDoubleArray(),
        inProjectionForCulling.toColumnsDoubleArray(),
        near,
        far
    )
} else {
    setCustomProjection(inProjection.toColumnsDoubleArray(), near, far)
}

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
fun Camera.setScaling(scaling: Float2) = setScaling(scaling.x.toDouble(), scaling.y.toDouble())

/**
 * Sets the camera's model matrix.
 *
 * @param eye position of the camera in world space
 * @param center position of the point in world space the camera is looking at
 * @param up unit vector denoting the camera's "up" direction
 */
fun Camera.lookAt(eye: Position, center: Position, up: Direction) = lookAt(
    eye.x.toDouble(),
    eye.y.toDouble(),
    eye.z.toDouble(),
    center.x.toDouble(),
    center.y.toDouble(),
    center.z.toDouble(),
    up.x.toDouble(),
    up.y.toDouble(),
    up.z.toDouble()
)

/**
 * Retrieves the camera's projection matrix. The projection matrix used for rendering always has
 * its far plane set to infinity. This is why it may differ from the matrix set through
 * setProjection() or setLensProjection().
 *
 * Transform containing the camera's projection as a column-major matrix.
 */
var Camera.projectionTransform: Transform
    get() = DoubleArray(16).apply { getProjectionMatrix(this) }.toTransform()
    set(value) {
        setCustomProjection(value)
    }

/**
 * Retrieves the camera's culling matrix. The culling matrix is the same as the projection
 * matrix, except the far plane is finite.
 *
 * Transform containing the camera's projection as a column-major matrix.
 */
val Camera.cullingProjectionTransform: Transform
    get() = DoubleArray(16).apply {
        getCullingProjectionMatrix(this)
    }.toTransform()

/**
 * Returns the scaling amount used to scale the projection matrix.
 *
 * The diagonal of the scaling matrix applied after the projection matrix.
 *
 * @see setScaling
 */
val Camera.scaling: Float4 get() = DoubleArray(4).apply { getScaling(this) }.toFloat4()

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
var Camera.modelTransform: Transform
    get() = FloatArray(16).apply { getModelMatrix(this) }.toTransform()
    set(value) {
        setModelMatrix(value.copyColumnsInto(modelMatrixScratch))
    }

/**
 * Retrieves the camera's view matrix. The view matrix is the inverse of the model matrix.
 *
 * Transform containing the camera's column-major view matrix.
 */
val Camera.viewTransform: Transform
    get() = FloatArray(16).apply { getViewMatrix(this) }.toTransform()

/**
 * Retrieves the camera left unit vector in world space, that is a unit vector that points to
 * the left of the camera.
 *
 * Float3 Direction containing the camera's left vector in world units.
 */
val Camera.leftDirection: Direction
    get() = FloatArray(3).apply { getLeftVector(this) }.toDirection()

/**
 * Retrieves the camera up unit vector in world space, that is a unit vector that points up with
 * respect to the camera.
 *
 * Float3 Direction containing the camera's up vector in world units.
 */
val Camera.upDirection: Direction
    get() = FloatArray(3).apply { getUpVector(this) }.toDirection()

/**
 * Retrieves the camera forward unit vector in world space, that is a unit vector that points
 * in the direction the camera is looking at.
 *
 * Float3 Direction containing the camera's forward vector in world units.
 */
val Camera.forwardDirection: Direction
    get() = FloatArray(3).apply { getForwardVector(this) }.toDirection()

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
fun Camera.viewToWorld(viewPosition: Float2, z: Float = 1.0f): Position {
    val clipSpacePosition = Float4(
        // Normalize between -1 and 1.
        Float3(x = viewPosition.x, y = viewPosition.y, z = z) * 2.0f - 1.0f,
        w = 1.0f
    )
    val worldPosition = inverse(cullingProjectionTransform * viewTransform) * clipSpacePosition
    return when {
        worldPosition.w almostEquals 0.0f -> Position()
        else -> worldPosition.xyz / worldPosition.w
    }
}

/**
 * Get a view space position from a world position.
 *
 * The device coordinate space is unaffected by the orientation of the device.
 *
 * Returns `null` when [worldPosition] is **behind the camera** (behind the near plane). Such a
 * point has no on-screen location: its clip-space `w` is `<= 0`, and dividing by a non-positive
 * `w` yields a *finite, mirrored* coordinate on the wrong side of the view — a plausible-but-wrong
 * value that no `isFinite` check downstream would reject. Reporting `null` instead lets callers
 * (e.g. an overlay projecting the corners of a bounding box) skip the point rather than draw it at
 * a mirrored pixel. A point exactly on the camera (`w == 0`) is also reported as `null`.
 *
 * @param worldPosition The world position to convert.
 *
 * @return normalized view coordinate, or `null` if [worldPosition] is behind the near plane.
 * x = (0 = left, 0.5 = center, 1 = right)
 * y = (0 = bottom, 0.5 = center, 1 = top)
 */
fun Camera.worldToView(worldPosition: Position): Float2? =
    coreWorldToView(worldPosition, cullingProjectionTransform, viewTransform)

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
fun Camera.viewToRay(viewPosition: Float2): Ray {
    val startPosition = viewToWorld(viewPosition, z = 0.0f)
    val endPosition = viewToWorld(viewPosition, z = 1.0f)
    val direction = endPosition - startPosition
    return Ray(origin = startPosition, direction = direction)
}