package io.github.sceneview.web

import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Float4
import dev.romainguy.kotlin.math.inverse
import io.github.sceneview.collision.Ray
import io.github.sceneview.collision.Vector3
import io.github.sceneview.math.Transform

/**
 * Unprojects a screen point into a world-space picking [Ray] (#2024 P5c).
 *
 * Pure math — kept free of any engine access so `jsTest` can prove the
 * unprojection without the Filament WASM module. The two matrices come from
 * the engine at the [SceneView.hitTest] call site.
 *
 * @param x Horizontal screen coordinate in canvas pixels (0 = left edge).
 * @param y Vertical screen coordinate in canvas pixels (0 = top edge — DOM
 *   convention; the NDC flip happens here).
 * @param viewportWidth Canvas width in the same pixel unit as [x].
 * @param viewportHeight Canvas height in the same pixel unit as [y].
 * @param projection The camera's projection matrix (column-major).
 * @param cameraModel The camera's model (camera-to-world) matrix — the
 *   inverse view matrix.
 * @return A [Ray] from the near plane through the screen point, in world
 *   space, with a normalized direction.
 */
internal fun screenPointToRay(
    x: Float,
    y: Float,
    viewportWidth: Float,
    viewportHeight: Float,
    projection: Transform,
    cameraModel: Transform,
): Ray {
    val ndcX = 2f * x / viewportWidth - 1f
    val ndcY = 1f - 2f * y / viewportHeight
    val invProjection = inverse(projection)
    val near = unprojectNdc(invProjection, cameraModel, ndcX, ndcY, ndcZ = -1f)
    // The second point sits MID-volume (NDC z = 0), not on the far plane:
    // Filament renders with an infinite-far projection, where NDC z = +1 maps
    // to w = 0 — a point at infinity whose perspective divide is NaN. Any
    // second finite depth on the same pixel yields the same ray direction.
    val mid = unprojectNdc(invProjection, cameraModel, ndcX, ndcY, ndcZ = 0f)
    // Ray's constructor normalizes the direction.
    return Ray(
        Vector3(near.x, near.y, near.z),
        Vector3(mid.x - near.x, mid.y - near.y, mid.z - near.z),
    )
}

/** NDC → view space (inverse projection + perspective divide) → world space. */
private fun unprojectNdc(
    invProjection: Transform,
    cameraModel: Transform,
    ndcX: Float,
    ndcY: Float,
    ndcZ: Float,
): Float3 {
    val view = invProjection * Float4(ndcX, ndcY, ndcZ, 1f)
    val world = cameraModel * Float4(view.x / view.w, view.y / view.w, view.z / view.w, 1f)
    return Float3(world.x, world.y, world.z)
}
