package io.github.sceneview.math

import dev.romainguy.kotlin.math.Float2
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Float4
import dev.romainguy.kotlin.math.Mat4
import dev.romainguy.kotlin.math.Ray
import dev.romainguy.kotlin.math.inverse

/**
 * Pure camera projection utilities — no platform dependencies.
 *
 * These functions perform view↔world coordinate transformations using
 * projection and view matrices directly, making them portable across
 * Android (Filament), iOS (RealityKit/Metal), and any other renderer.
 */

/**
 * Convert a view-space position to a world-space position.
 *
 * @param viewPosition Normalized view coordinate:
 *   x = (0 = left, 0.5 = center, 1 = right)
 *   y = (0 = bottom, 0.5 = center, 1 = top)
 * @param z Depth between 1 (near) and 0 (infinity/far).
 * @param projectionMatrix The camera's culling projection matrix (finite far plane).
 * @param viewMatrix The camera's view matrix (inverse of model matrix).
 * @return The world-space position.
 */
fun viewToWorld(
    viewPosition: Float2,
    z: Float = 1.0f,
    projectionMatrix: Mat4,
    viewMatrix: Mat4
): Position {
    val clipSpacePosition = Float4(
        // Normalize between -1 and 1
        Float3(x = viewPosition.x, y = viewPosition.y, z = z) * 2.0f - 1.0f,
        w = 1.0f
    )
    val worldPosition = inverse(projectionMatrix * viewMatrix) * clipSpacePosition
    return when {
        worldPosition.w almostEquals 0.0f -> Position()
        else -> worldPosition.xyz / worldPosition.w
    }
}

/**
 * Convert a world-space position to a normalized view-space position.
 *
 * Returns `null` when [worldPosition] is **behind the camera** (at or behind the camera's eye plane). Such a
 * point has no on-screen location: its clip-space `w` is `<= 0`, and dividing by a non-positive
 * `w` yields a *finite, mirrored* coordinate on the wrong side of the view — a plausible-but-wrong
 * value no `isFinite` check would reject. Callers that project several world points (e.g. the 8
 * corners of a bounding box) must skip the `null` ones rather than draw them at a mirrored pixel.
 * A point exactly on the camera (`w == 0`) is likewise reported as `null`.
 *
 * @param worldPosition The world-space position to convert.
 * @param projectionMatrix The camera's culling projection matrix.
 * @param viewMatrix The camera's view matrix.
 * @return Normalized view coordinate, or `null` if [worldPosition] is at or behind the camera's eye plane:
 *   x = (0 = left, 0.5 = center, 1 = right)
 *   y = (0 = bottom, 0.5 = center, 1 = top)
 */
fun worldToView(
    worldPosition: Position,
    projectionMatrix: Mat4,
    viewMatrix: Mat4
): Float2? {
    val viewProjectionTransform = projectionMatrix * viewMatrix
    val viewPosition = viewProjectionTransform * Float4(worldPosition, w = 1.0f)
    // A point at or behind the camera's eye plane has w <= 0; dividing by it produces a mirrored, finite
    // (and therefore silently wrong) coordinate. Report "no view position" instead.
    if (viewPosition.w <= 0.0f) return null
    // Divide by w component to convert to clip space
    return (viewPosition / viewPosition.w).xy / 2.0f + 0.5f
}

/**
 * Calculate a ray in world space going from the near-plane of the camera
 * through a point in view space.
 *
 * @param viewPosition Normalized view coordinate:
 *   x = (0 = left, 0.5 = center, 1 = right)
 *   y = (0 = bottom, 0.5 = center, 1 = top)
 * @param projectionMatrix The camera's culling projection matrix.
 * @param viewMatrix The camera's view matrix.
 * @return A Ray from the camera near plane towards far/infinity.
 */
fun viewToRay(
    viewPosition: Float2,
    projectionMatrix: Mat4,
    viewMatrix: Mat4
): Ray {
    val startPosition = viewToWorld(viewPosition, z = 0.0f, projectionMatrix, viewMatrix)
    val endPosition = viewToWorld(viewPosition, z = 1.0f, projectionMatrix, viewMatrix)
    val direction = endPosition - startPosition
    return Ray(origin = startPosition, direction = direction)
}

/**
 * Compute the exposure value (EV100) from camera exposure settings.
 *
 * EV is a number that represents a combination of shutter speed and f-number,
 * such that all combinations yielding the same exposure have the same EV.
 *
 * @param aperture The lens aperture (f-number).
 * @param shutterSpeed The shutter speed in seconds.
 * @param sensitivity The sensor sensitivity (ISO).
 * @return The EV100 value.
 *
 * @see <a href="https://en.wikipedia.org/wiki/Exposure_value">Wikipedia: Exposure value</a>
 */
fun exposureEV100(aperture: Float, shutterSpeed: Float, sensitivity: Float): Float {
    return kotlin.math.log2((aperture * aperture) / shutterSpeed * 100.0f / sensitivity)
}

/**
 * Compute a unit-less exposure normalization factor from EV100.
 *
 * Useful for matching lighting between physically-based renderers (which use
 * real camera settings) and engines that use relative/unit-less light intensities.
 *
 * @param ev100 The exposure value at ISO 100.
 * @return The exposure normalization factor (1 / ev100).
 */
fun exposureFactor(ev100: Float): Float = 1.0f / ev100
