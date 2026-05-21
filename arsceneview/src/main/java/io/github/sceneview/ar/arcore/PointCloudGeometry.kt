package io.github.sceneview.ar.arcore

import java.nio.FloatBuffer

/**
 * Pure, ARCore-free transformation of an ARCore point cloud's packed `[x, y, z, confidence]`
 * buffer into the flat `[x0,y0,z0, x1,y1,z1, ...]` position array consumed by a Filament
 * `POINTS` [com.google.android.filament.VertexBuffer].
 *
 * `com.google.ar.core.PointCloud.getPoints()` returns a [FloatBuffer] of 4 floats per detected
 * feature point — three for the **world-space** position and one for a `[0, 1]` confidence value.
 * This helper:
 *
 *  - skips every point whose confidence is below [confidenceThreshold];
 *  - copies the surviving `xyz` triples into a freshly-allocated [FloatArray].
 *
 * It is extracted as an `internal` top-level function (no [com.google.ar.core.Frame] dependency)
 * so the confidence filter + packing arithmetic can be pinned by a JVM unit test — a stride or
 * offset error would otherwise only surface on a tracking-capable device.
 *
 * @param packed             The raw point buffer, 4 floats per point (`x, y, z, confidence`).
 *                           Read via [FloatBuffer.get] from a read-only view, so the caller's
 *                           buffer position is never mutated.
 * @param confidenceThreshold Minimum confidence in `[0, 1]`. Points at or above this value are
 *                            kept; points below it are discarded. A value of `0` keeps every
 *                            point.
 * @return Flat-packed `xyz` positions of the surviving points. Empty when no point clears the
 *         threshold.
 */
internal fun buildPointCloudPositions(
    packed: FloatBuffer,
    confidenceThreshold: Float,
): FloatArray {
    val view = packed.asReadOnlyBuffer()
    val pointCount = view.remaining() / FLOATS_PER_POINT
    if (pointCount == 0) return FloatArray(0)

    // Worst case every point survives — size for that, then trim to the kept count.
    val positions = FloatArray(pointCount * 3)
    var kept = 0
    var base = view.position()
    for (i in 0 until pointCount) {
        val x = view.get(base)
        val y = view.get(base + 1)
        val z = view.get(base + 2)
        val confidence = view.get(base + 3)
        if (confidence >= confidenceThreshold) {
            positions[kept * 3] = x
            positions[kept * 3 + 1] = y
            positions[kept * 3 + 2] = z
            kept++
        }
        base += FLOATS_PER_POINT
    }
    return if (kept * 3 == positions.size) positions else positions.copyOf(kept * 3)
}

/** Floats per ARCore feature point: `x, y, z, confidence`. */
private const val FLOATS_PER_POINT = 4
