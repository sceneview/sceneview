package io.github.sceneview

import io.github.sceneview.math.Position

/**
 * Chooses which points of a bounding box [fitCameraToBounds] fits to the viewport.
 * The default is [Corners]; callers must explicitly opt in to [Silhouette].
 * No model type is inferred and no animation poses are sampled.
 */
enum class FitMode {
    /** All eight box corners: the unchanged fit, suitable for box-shaped products. */
    Corners,

    /**
     * Fits the horizontal mid-lines of the side faces and the centres of the top and bottom
     * faces: six probes in total, since adjacent side mid-lines share their endpoints.
     *
     * Suits organic subjects, such as a character or creature seen at three quarters from above,
     * where the near bottom box corner sits far below the subject's feet. This is a heuristic,
     * not a mesh silhouette: it can crop box-shaped products with geometry in the corners.
     * Only the probes at the requested viewing direction are guaranteed to fit.
     */
    Silhouette
}

internal fun Aabb.framingProbes(mode: FitMode): List<Position> {
    if (isEmpty) return emptyList()
    val lo = min
    val hi = max
    return when (mode) {
        // Preserve the historical corner order and Float coordinates exactly.
        FitMode.Corners -> List(8) { i ->
            Position(
                if (i and 1 == 0) lo.x else hi.x,
                if (i and 2 == 0) lo.y else hi.y,
                if (i and 4 == 0) lo.z else hi.z
            )
        }
        FitMode.Silhouette -> listOf(
            Position(lo.x, center.y, lo.z), Position(hi.x, center.y, lo.z),
            Position(lo.x, center.y, hi.z), Position(hi.x, center.y, hi.z),
            Position(center.x, lo.y, center.z), Position(center.x, hi.y, center.z)
        )
    }
}
