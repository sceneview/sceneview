package io.github.sceneview.ar.arcore

import java.nio.ShortBuffer
import kotlin.math.abs
import kotlin.math.max

/**
 * Default downsampling stride between depth samples used as mesh vertices.
 *
 * The ARCore environment depth image is roughly 160x90 on most devices, so an unsampled mesh would
 * be ~28 000 quads × 2 triangles. A stride of 4 cuts that to ~900 quads — visually dense enough
 * for shadows / scan effects while keeping the per-frame rebuild and Filament upload under the
 * 16 ms budget.
 */
internal const val DEFAULT_DEPTH_MESH_STRIDE = 4

/**
 * Default edge-discontinuity threshold in meters.
 *
 * Two neighbouring depth samples whose Z differs by more than this are assumed to lie on different
 * real-world surfaces (object edge, hole, occluding boundary). The triangle that would span them
 * is culled so the mesh does not stretch a "curtain" across the depth jump — Depth Lab's
 * `ScreenSpaceDepthMesh` uses the same heuristic.
 */
internal const val DEFAULT_DEPTH_EDGE_THRESHOLD_METERS = 0.10f

/**
 * Result of a single depth-mesh rebuild — a flat-packed vertex array plus a triangle index array,
 * both ready to upload to a Filament [com.google.android.filament.VertexBuffer] /
 * [com.google.android.filament.IndexBuffer].
 *
 * @property positions  Vertex positions in **ARCore camera space** (right-handed, +X right, +Y up,
 *                      -Z forward), flat-packed as `[x0, y0, z0, x1, y1, z1, ...]`. Length is
 *                      `vertexCount * 3`. Off-grid samples — depth ≤ 0, infinity, or NaN — are
 *                      stored as `(0, 0, 0)` and never referenced from [indices].
 * @property indices    Triangle indices into [positions] (3 ints per triangle). Triangles whose
 *                      vertices span an edge discontinuity are omitted, so [indices].size is
 *                      bounded above by `gridQuadCount * 6` but is typically much less.
 * @property width      Number of vertex columns in the grid (depth-image width / stride, rounded
 *                      up). Useful for the #1713 collider when it wants to walk the grid.
 * @property height     Number of vertex rows in the grid.
 */
internal data class DepthMeshGeometry(
    val positions: FloatArray,
    val indices: IntArray,
    val width: Int,
    val height: Int,
) {
    val vertexCount: Int get() = positions.size / 3
    val triangleCount: Int get() = indices.size / 3

    // data class equals/hashCode would compare the array identities; spell them out so two builds
    // producing the same numeric content are treated as equal (useful in tests).
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DepthMeshGeometry) return false
        return width == other.width &&
            height == other.height &&
            positions.contentEquals(other.positions) &&
            indices.contentEquals(other.indices)
    }

    override fun hashCode(): Int {
        var result = positions.contentHashCode()
        result = 31 * result + indices.contentHashCode()
        result = 31 * result + width
        result = 31 * result + height
        return result
    }
}

/**
 * Builds a triangle mesh from a single ARCore depth image, with edge-discontinuity culling.
 *
 * The depth image is a `DEPTH16` luminance image — one unsigned 16-bit sample per pixel, in
 * **millimeters**. This function unprojects each (sub-sampled) pixel into ARCore camera space
 * using the supplied pinhole intrinsics, stitches the resulting vertex grid into two triangles
 * per quad, and drops any triangle that crosses a depth jump larger than [edgeThresholdMeters].
 *
 * The math mirrors [unprojectDepthPixel] — kept in lock-step so a depth-driven raycast and a
 * depth-driven mesh always agree on world coordinates.
 *
 * `internal` so the projection + culling math can be unit-tested without an ARCore [com.google.ar.core.Frame].
 *
 * @param depthBuffer       The depth plane's [ShortBuffer], obtained from
 *                          `depthImage.planes[0].buffer.order(nativeOrder).asShortBuffer()`.
 * @param depthWidth        Depth-image width in pixels.
 * @param depthHeight       Depth-image height in pixels.
 * @param rowStrideShorts   Row stride in **shorts** (= `plane.rowStride / 2`).
 * @param fx                Focal length X, scaled to the depth-image resolution.
 * @param fy                Focal length Y, scaled to the depth-image resolution.
 * @param cx                Principal point X, scaled to the depth-image resolution.
 * @param cy                Principal point Y, scaled to the depth-image resolution.
 * @param stride            Vertex stride in pixels. Higher = coarser mesh, fewer triangles.
 * @param edgeThresholdMeters  Maximum Z-distance between two neighbours before the spanning
 *                          triangle is culled.
 */
internal fun buildDepthMeshGeometry(
    depthBuffer: ShortBuffer,
    depthWidth: Int,
    depthHeight: Int,
    rowStrideShorts: Int,
    fx: Float,
    fy: Float,
    cx: Float,
    cy: Float,
    stride: Int = DEFAULT_DEPTH_MESH_STRIDE,
    edgeThresholdMeters: Float = DEFAULT_DEPTH_EDGE_THRESHOLD_METERS,
): DepthMeshGeometry {
    require(stride >= 1) { "stride must be ≥ 1, was $stride" }
    require(edgeThresholdMeters > 0f) {
        "edgeThresholdMeters must be > 0, was $edgeThresholdMeters"
    }

    // Number of grid columns / rows. The last column/row of the depth image may not align with the
    // stride — we still want to render up to (but not past) the image boundary, so we ceil-divide.
    val gridWidth = if (depthWidth <= 0) 0 else (depthWidth + stride - 1) / stride
    val gridHeight = if (depthHeight <= 0) 0 else (depthHeight + stride - 1) / stride

    if (gridWidth < 2 || gridHeight < 2) {
        // Not enough samples to form a single quad — return an empty mesh rather than crashing
        // on a malformed depth image.
        return DepthMeshGeometry(FloatArray(0), IntArray(0), gridWidth, gridHeight)
    }

    val vertexCount = gridWidth * gridHeight
    val positions = FloatArray(vertexCount * 3)
    val depthsMeters = FloatArray(vertexCount)
    val valid = BooleanArray(vertexCount)

    // Pass 1 — unproject every grid sample. Off-grid (depth ≤ 0 or out of bounds) stays at
    // (0,0,0) and `valid[i] = false`.
    var i = 0
    for (gy in 0 until gridHeight) {
        val py = (gy * stride).coerceAtMost(depthHeight - 1)
        for (gx in 0 until gridWidth) {
            val px = (gx * stride).coerceAtMost(depthWidth - 1)
            val depth = depthMillimetersAt(depthBuffer, px, py, rowStrideShorts, depthWidth, depthHeight)
            if (depth > 0) {
                val depthM = depth / 1000f
                // Mirror of `unprojectDepthPixel`: ARCore camera space is +X right, +Y up, -Z fwd.
                val baseIndex = i * 3
                positions[baseIndex] = (px - cx) * depthM / fx
                positions[baseIndex + 1] = -(py - cy) * depthM / fy
                positions[baseIndex + 2] = -depthM
                depthsMeters[i] = depthM
                valid[i] = true
            }
            i++
        }
    }

    // Pass 2 — emit two triangles per grid quad, culling any whose vertices span an edge
    // discontinuity (or include an invalid sample).
    //
    // Quad layout for cell (gx, gy):
    //   tl --- tr
    //   |   /  |
    //   bl --- br
    //
    // Triangles: (tl, bl, br) + (tl, br, tr). Wound CCW when viewed from -Z (i.e. from the camera
    // looking forward), which matches Filament's default front-face convention.
    val indices = ArrayList<Int>(gridWidth * gridHeight * 6)
    for (gy in 0 until gridHeight - 1) {
        val rowTop = gy * gridWidth
        val rowBottom = (gy + 1) * gridWidth
        for (gx in 0 until gridWidth - 1) {
            val tl = rowTop + gx
            val tr = tl + 1
            val bl = rowBottom + gx
            val br = bl + 1
            if (!valid[tl] || !valid[tr] || !valid[bl] || !valid[br]) continue

            val zTl = depthsMeters[tl]
            val zTr = depthsMeters[tr]
            val zBl = depthsMeters[bl]
            val zBr = depthsMeters[br]

            // Cull the first triangle if its depth spread exceeds the threshold.
            if (!isEdgeDiscontinuity(zTl, zBl, zBr, edgeThresholdMeters)) {
                indices.add(tl); indices.add(bl); indices.add(br)
            }
            if (!isEdgeDiscontinuity(zTl, zBr, zTr, edgeThresholdMeters)) {
                indices.add(tl); indices.add(br); indices.add(tr)
            }
        }
    }

    return DepthMeshGeometry(
        positions = positions,
        indices = indices.toIntArray(),
        width = gridWidth,
        height = gridHeight,
    )
}

/**
 * Reads the depth sample at depth-image pixel ([x], [y]) in **millimeters**, or `0` when the pixel
 * is out of bounds or has no depth.
 *
 * Mirror of the private helper used by [Frame.hitTestDepth] — kept in this file so the mesh-builder
 * has no dependency on the hit-test code path and can be unit-tested in isolation.
 */
internal fun depthMillimetersAt(
    buffer: ShortBuffer,
    x: Int,
    y: Int,
    rowStrideShorts: Int,
    width: Int,
    height: Int,
): Int {
    if (x !in 0 until width || y !in 0 until height) return 0
    return buffer.get(y * rowStrideShorts + x).toInt() and 0xFFFF
}

/**
 * True when the three depths span a real-world edge discontinuity larger than [thresholdMeters].
 *
 * Uses the **range** of the three depths (max - min) rather than pairwise diffs so a single
 * outlier in the middle of a smooth surface is still caught with a single comparison.
 *
 * `internal` so the culling rule can be unit-tested without an ARCore [com.google.ar.core.Frame].
 */
internal fun isEdgeDiscontinuity(
    z0: Float,
    z1: Float,
    z2: Float,
    thresholdMeters: Float,
): Boolean {
    val minZ = minOf(z0, z1, z2)
    val maxZ = max(max(z0, z1), z2)
    return abs(maxZ - minZ) > thresholdMeters
}
