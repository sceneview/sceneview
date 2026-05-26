package io.github.sceneview.ar.arcore

import java.nio.ByteBuffer
import java.nio.ShortBuffer
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Default maximum displacement, in meters, between a sampled depth point and the plane in
 * plane-local space. Samples whose `|plane-local Y|` exceeds this are rejected so a cabinet
 * 80 cm above the floor never appears as part of the floor mesh.
 *
 * 30 cm is roughly the lip of a step / the height of a rolled rug / a slight ramp — enough
 * micro-relief to read visibly, small enough that a sofa or a wall does not stretch the mesh.
 */
internal const val DEFAULT_PLANE_MAX_DISPLACEMENT_METERS: Float = 0.30f

/**
 * Default minimum ARCore raw-depth confidence (0..255) for a sample to contribute a vertex.
 *
 * Matches the threshold used by Depth Lab's `RawDepth` API examples — values < 128 are
 * typically motion-blurred edges, holes, or transparent surfaces.
 */
internal const val DEFAULT_PLANE_MIN_DEPTH_CONFIDENCE: Int = 128

/**
 * Result of a single plane-clipped depth-mesh rebuild — flat-packed positions, per-vertex
 * normals, and a triangle index array, all in **plane-local coordinates** (X-Z is the plane
 * surface, +Y is the plane up direction). Ready to upload to a Filament
 * [com.google.android.filament.VertexBuffer] / [com.google.android.filament.IndexBuffer].
 *
 * @property positions  Vertex positions in plane-local space, flat-packed as
 *                      `[x0, y0, z0, x1, y1, z1, ...]`. Length is `vertexCount * 3`.
 *                      Off-grid samples — depth ≤ 0, low confidence, outside the polygon,
 *                      or beyond the displacement clamp — are stored as `(0, 0, 0)` and
 *                      never referenced from [indices].
 * @property normals    Per-vertex unit normals in the same plane-local space, flat-packed
 *                      the same way. Length matches [positions]. Off-grid samples carry
 *                      `(0, 1, 0)` (plane up) so the buffer always has well-defined math.
 * @property indices    Triangle indices into [positions] / [normals] (3 ints per triangle).
 *                      Triangles spanning an edge discontinuity, or touching an off-grid
 *                      sample, are omitted.
 * @property width      Number of vertex columns in the grid (depth-image width / stride,
 *                      rounded up).
 * @property height     Number of vertex rows in the grid.
 */
internal data class PlaneDepthMeshGeometry(
    val positions: FloatArray,
    val normals: FloatArray,
    val indices: IntArray,
    val width: Int,
    val height: Int,
) {
    val vertexCount: Int get() = positions.size / 3
    val triangleCount: Int get() = indices.size / 3

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PlaneDepthMeshGeometry) return false
        return width == other.width &&
            height == other.height &&
            positions.contentEquals(other.positions) &&
            normals.contentEquals(other.normals) &&
            indices.contentEquals(other.indices)
    }

    override fun hashCode(): Int {
        var result = positions.contentHashCode()
        result = 31 * result + normals.contentHashCode()
        result = 31 * result + indices.contentHashCode()
        result = 31 * result + width
        result = 31 * result + height
        return result
    }
}

/**
 * Builds a plane-clipped triangle mesh from a single ARCore depth image — the workhorse
 * of [io.github.sceneview.ar.PlaneVisualizerV2].
 *
 * For every sub-sampled depth pixel:
 *  1. Unproject into ARCore camera space (same math as [unprojectDepthPixel] /
 *     [buildDepthMeshGeometry]).
 *  2. Transform into plane-local space via [cameraToPlaneLocal] — a column-major 4x4 that
 *     callers compute as `plane.centerPose.inverse() ⊗ camera.pose`.
 *  3. Reject the vertex if any of these are true:
 *     - depth ≤ 0 (no measurement),
 *     - `|plane-local Y|` exceeds [maxDisplacementMeters],
 *     - the confidence buffer (when supplied) reports `< minConfidence`,
 *     - the (plane-local X, plane-local Z) projection sits outside [polygon].
 *  4. Then triangulate the kept vertices in a grid + cull triangles whose plane-local Y
 *     range exceeds [edgeThresholdMeters] (the in-plane analogue of camera-space depth
 *     jumps in [buildDepthMeshGeometry]).
 *
 * Per-vertex normals are recovered by central differences of the grid neighbours and
 * normalised. Boundary vertices use forward / backward differences. Missing neighbours fall
 * back to `(0, 1, 0)` so a flat plane sees normals all pointing along plane-local +Y, and
 * the math never NaNs.
 *
 * The CCW winding convention matches Filament's default front-face: triangles are wound CCW
 * when viewed from +Y (i.e. from above the floor looking down).
 *
 * `internal` so the unprojection + clipping + normal math can be unit-tested without an
 * ARCore [com.google.ar.core.Frame].
 *
 * @param depthBuffer            The depth plane's [ShortBuffer]
 *                               (`depthImage.planes[0].buffer.order(nativeOrder).asShortBuffer()`).
 * @param depthWidth             Depth-image width in pixels.
 * @param depthHeight            Depth-image height in pixels.
 * @param rowStrideShorts        Row stride in **shorts** (= `plane.rowStride / 2`).
 * @param fx                     Focal length X, scaled to the depth-image resolution.
 * @param fy                     Focal length Y, scaled to the depth-image resolution.
 * @param cx                     Principal point X, scaled to the depth-image resolution.
 * @param cy                     Principal point Y, scaled to the depth-image resolution.
 * @param cameraToPlaneLocal     A column-major 4x4 transforming camera-space points into
 *                               plane-local space. Length must be 16.
 * @param polygon                Plane polygon as `[x0, z0, x1, z1, ...]` 2D plane-local
 *                               vertices (from [com.google.ar.core.Plane.getPolygon]). Vertices
 *                               whose (x, z) falls outside this polygon are rejected.
 * @param maxDisplacementMeters  Clamp on `|plane-local Y|`. Default
 *                               [DEFAULT_PLANE_MAX_DISPLACEMENT_METERS] (30 cm).
 * @param stride                 Vertex stride in pixels. Higher = coarser mesh.
 * @param edgeThresholdMeters    Maximum plane-local-Y range across a triangle before it is
 *                               culled. Defaults to [DEFAULT_DEPTH_EDGE_THRESHOLD_METERS]
 *                               (10 cm) — same heuristic as [buildDepthMeshGeometry].
 * @param confidenceBuffer       Optional matching `Y8` confidence plane
 *                               ([com.google.ar.core.Frame.acquireRawDepthConfidenceImage]).
 *                               When supplied, samples with `confidence < minConfidence` are
 *                               rejected.
 * @param confidenceRowStrideBytes  Row stride in **bytes** for [confidenceBuffer]. Ignored
 *                               when [confidenceBuffer] is null.
 * @param confidenceWidth        Width of the confidence image. Often equal to [depthWidth],
 *                               but ARCore is not contractually required to match — pass the
 *                               value reported by the confidence image itself.
 * @param confidenceHeight       Height of the confidence image.
 * @param minConfidence          0..255 confidence threshold. Defaults to
 *                               [DEFAULT_PLANE_MIN_DEPTH_CONFIDENCE] (128).
 */
@Suppress("LongParameterList", "CyclomaticComplexMethod", "LongMethod")
internal fun buildPlaneDepthMeshGeometry(
    depthBuffer: ShortBuffer,
    depthWidth: Int,
    depthHeight: Int,
    rowStrideShorts: Int,
    fx: Float,
    fy: Float,
    cx: Float,
    cy: Float,
    cameraToPlaneLocal: FloatArray,
    polygon: FloatArray,
    maxDisplacementMeters: Float = DEFAULT_PLANE_MAX_DISPLACEMENT_METERS,
    stride: Int = DEFAULT_DEPTH_MESH_STRIDE,
    edgeThresholdMeters: Float = DEFAULT_DEPTH_EDGE_THRESHOLD_METERS,
    confidenceBuffer: ByteBuffer? = null,
    confidenceRowStrideBytes: Int = 0,
    confidenceWidth: Int = 0,
    confidenceHeight: Int = 0,
    minConfidence: Int = DEFAULT_PLANE_MIN_DEPTH_CONFIDENCE,
): PlaneDepthMeshGeometry {
    require(stride >= 1) { "stride must be ≥ 1, was $stride" }
    require(edgeThresholdMeters > 0f) {
        "edgeThresholdMeters must be > 0, was $edgeThresholdMeters"
    }
    require(cameraToPlaneLocal.size == 16) {
        "cameraToPlaneLocal must be a 4x4 matrix (size 16), was ${cameraToPlaneLocal.size}"
    }
    require(maxDisplacementMeters > 0f) {
        "maxDisplacementMeters must be > 0, was $maxDisplacementMeters"
    }

    val gridWidth = if (depthWidth <= 0) 0 else (depthWidth + stride - 1) / stride
    val gridHeight = if (depthHeight <= 0) 0 else (depthHeight + stride - 1) / stride

    if (gridWidth < 2 || gridHeight < 2) {
        return PlaneDepthMeshGeometry(
            positions = FloatArray(0),
            normals = FloatArray(0),
            indices = IntArray(0),
            width = gridWidth,
            height = gridHeight,
        )
    }

    val vertexCount = gridWidth * gridHeight
    val positions = FloatArray(vertexCount * 3)
    val normals = FloatArray(vertexCount * 3)
    val valid = BooleanArray(vertexCount)

    // Default every normal to plane-up so the buffer never holds NaN/zero vectors for
    // off-grid samples — the shader assumes the normal is a unit vector everywhere.
    var fillIdx = 1
    while (fillIdx < normals.size) {
        normals[fillIdx] = 1f
        fillIdx += 3
    }

    // ── Pass 1 — unproject + clip into plane-local space ────────────────────────────────
    var i = 0
    for (gy in 0 until gridHeight) {
        val py = (gy * stride).coerceAtMost(depthHeight - 1)
        for (gx in 0 until gridWidth) {
            val px = (gx * stride).coerceAtMost(depthWidth - 1)
            val depthMm = depthMillimetersAt(depthBuffer, px, py, rowStrideShorts, depthWidth, depthHeight)
            if (depthMm > 0 && passesConfidence(
                    confidenceBuffer, confidenceRowStrideBytes,
                    confidenceWidth, confidenceHeight,
                    px, py, minConfidence,
                )
            ) {
                val depthM = depthMm / 1000f
                val camX = (px - cx) * depthM / fx
                val camY = -(py - cy) * depthM / fy
                val camZ = -depthM

                val planeX = transformPoint(cameraToPlaneLocal, camX, camY, camZ, row = 0)
                val planeY = transformPoint(cameraToPlaneLocal, camX, camY, camZ, row = 1)
                val planeZ = transformPoint(cameraToPlaneLocal, camX, camY, camZ, row = 2)

                if (abs(planeY) <= maxDisplacementMeters &&
                    pointInPolygon2D(planeX, planeZ, polygon)
                ) {
                    val baseIndex = i * 3
                    positions[baseIndex] = planeX
                    positions[baseIndex + 1] = planeY
                    positions[baseIndex + 2] = planeZ
                    valid[i] = true
                }
            }
            i++
        }
    }

    // ── Pass 2 — compute per-vertex normals by central / forward / backward differences ──
    computeGridNormals(positions, valid, normals, gridWidth, gridHeight)

    // ── Pass 3 — emit two triangles per grid quad, cull edge discontinuities ───────────
    val indices = ArrayList<Int>(gridWidth * gridHeight * 6)
    for (gy in 0 until gridHeight - 1) {
        val rowTop = gy * gridWidth
        val rowBottom = (gy + 1) * gridWidth
        for (gx in 0 until gridWidth - 1) {
            val tl = rowTop + gx
            val tr = tl + 1
            val bl = rowBottom + gx
            val br = bl + 1
            if (!allValid(valid, tl, tr, bl, br)) continue

            val yTl = positions[tl * 3 + 1]
            val yTr = positions[tr * 3 + 1]
            val yBl = positions[bl * 3 + 1]
            val yBr = positions[br * 3 + 1]

            // CCW from +Y (looking down at the floor). Plane-local convention: depth-image
            // rows grow downwards in pixels, which after the cameraY = -(py-cy)·depth/fy
            // negation and the cameraToPlaneLocal transform usually maps to +Z in plane
            // space — so (tl, bl, br) / (tl, br, tr) is the right winding to keep normals
            // pointing along +Y for a flat horizontal plane. The test for the flat case
            // asserts this explicitly.
            if (!isEdgeDiscontinuity(yTl, yBl, yBr, edgeThresholdMeters)) {
                indices.add(tl); indices.add(bl); indices.add(br)
            }
            if (!isEdgeDiscontinuity(yTl, yBr, yTr, edgeThresholdMeters)) {
                indices.add(tl); indices.add(br); indices.add(tr)
            }
        }
    }

    return PlaneDepthMeshGeometry(
        positions = positions,
        normals = normals,
        indices = indices.toIntArray(),
        width = gridWidth,
        height = gridHeight,
    )
}

/**
 * Reads byte `(x, y)` from an `Y8` confidence plane and returns true when it clears
 * [minConfidence]. Returns true when [buffer] is null (the caller has opted out of the
 * confidence filter). Returns false for out-of-bounds pixels — safer to skip than to
 * fabricate a confident sample.
 */
internal fun passesConfidence(
    buffer: ByteBuffer?,
    rowStrideBytes: Int,
    width: Int,
    height: Int,
    x: Int,
    y: Int,
    minConfidence: Int,
): Boolean {
    if (buffer == null) return true
    if (x !in 0 until width || y !in 0 until height) return false
    val idx = y * rowStrideBytes + x
    if (idx < 0 || idx >= buffer.capacity()) return false
    val value = buffer.get(idx).toInt() and 0xFF
    return value >= minConfidence
}

/**
 * Applies the column-major 4x4 [m] to point `(x, y, z, 1)` and returns the [row]-th
 * coordinate of the result. ARCore poses (`Pose.toMatrix(...)`) and Filament use
 * column-major storage, so `m[col*4 + row]` is the (row, col) cell.
 */
internal fun transformPoint(m: FloatArray, x: Float, y: Float, z: Float, row: Int): Float =
    m[0 * 4 + row] * x +
        m[1 * 4 + row] * y +
        m[2 * 4 + row] * z +
        m[3 * 4 + row]

/**
 * Fills [normals] (flat-packed `[nx0,ny0,nz0, ...]`) by central differences of [positions]
 * grid neighbours. Boundary vertices fall back to forward / backward differences. Off-grid
 * vertices (either themselves invalid or with no usable neighbour pair on either axis) keep
 * the pre-seeded `(0, 1, 0)` fallback so the buffer always carries unit vectors.
 *
 * The normal is `normalize(cross(dPos/dGridX, dPos/dGridZ))`. The CCW winding (see
 * [buildPlaneDepthMeshGeometry]) means a flat plane produces normals all along plane-local
 * +Y.
 */
internal fun computeGridNormals(
    positions: FloatArray,
    valid: BooleanArray,
    normals: FloatArray,
    gridWidth: Int,
    gridHeight: Int,
) {
    for (gy in 0 until gridHeight) {
        for (gx in 0 until gridWidth) {
            writeNormalAt(positions, valid, normals, gridWidth, gx, gy)
        }
    }
}

/**
 * Computes and writes the per-vertex normal at grid position (gx, gy) into [normals].
 * No-ops when the vertex is invalid, when no usable tangent pair can be sampled, or
 * when the cross product collapses (parallel tangents) — the pre-seeded `(0, 1, 0)`
 * fallback then stands. Extracted from [computeGridNormals] so the inner loop body has
 * a single early-return and is easier to read.
 */
private fun writeNormalAt(
    positions: FloatArray,
    valid: BooleanArray,
    normals: FloatArray,
    gridWidth: Int,
    gx: Int,
    gy: Int,
) {
    val idx = gy * gridWidth + gx
    if (!valid[idx]) return

    val tx = sampleTangent(positions, valid, gridWidth, gx, gy, dx = 1, dy = 0) ?: return
    val tz = sampleTangent(positions, valid, gridWidth, gx, gy, dx = 0, dy = 1) ?: return

    // cross(tz, tx) — chosen so a flat horizontal plane (depth-image rows mapping
    // to +Z plane-local, columns to +X) sees normals along +Y. cross(X, Z) = -Y
    // in a right-handed coord system, so we flip the operand order to land on +Y.
    // This is the same winding as the triangle emission (tl, bl, br) seen from +Y,
    // which keeps Filament's default front-face happy.
    val nx = tz[1] * tx[2] - tz[2] * tx[1]
    val ny = tz[2] * tx[0] - tz[0] * tx[2]
    val nz = tz[0] * tx[1] - tz[1] * tx[0]
    val length = sqrt(nx * nx + ny * ny + nz * nz)
    if (length <= 0f) return
    val inv = 1f / length
    val base = idx * 3
    normals[base] = nx * inv
    normals[base + 1] = ny * inv
    normals[base + 2] = nz * inv
}

/**
 * Returns true iff every vertex index in [v] is marked valid. Extracted from the
 * triangle-emission loop so the 4-way validity test is a single named call rather than
 * a long `||` chain (detekt ComplexCondition threshold = 3).
 */
private fun allValid(valid: BooleanArray, a: Int, b: Int, c: Int, d: Int): Boolean =
    valid[a] && valid[b] && valid[c] && valid[d]

/**
 * Returns the tangent vector at (gx, gy) along axis (dx, dy) ∈ {(1,0), (0,1)} as a 3-array
 * `[x, y, z]`, or null when neither the forward nor the backward neighbour is valid.
 *
 * Prefers central differences when both neighbours are valid (smoother normals across the
 * grid interior), falls back to forward / backward differences on grid borders.
 */
private fun sampleTangent(
    positions: FloatArray,
    valid: BooleanArray,
    gridWidth: Int,
    gx: Int,
    gy: Int,
    dx: Int,
    dy: Int,
): FloatArray? {
    val gridHeight = valid.size / gridWidth
    val fwdX = gx + dx
    val fwdY = gy + dy
    val backX = gx - dx
    val backY = gy - dy
    val hasFwd = fwdX in 0 until gridWidth && fwdY in 0 until gridHeight &&
        valid[fwdY * gridWidth + fwdX]
    val hasBack = backX in 0 until gridWidth && backY in 0 until gridHeight &&
        valid[backY * gridWidth + backX]

    val baseFwd = if (hasFwd) (fwdY * gridWidth + fwdX) * 3 else -1
    val baseBack = if (hasBack) (backY * gridWidth + backX) * 3 else -1
    val baseSelf = (gy * gridWidth + gx) * 3

    return when {
        hasFwd && hasBack -> floatArrayOf(
            positions[baseFwd] - positions[baseBack],
            positions[baseFwd + 1] - positions[baseBack + 1],
            positions[baseFwd + 2] - positions[baseBack + 2],
        )
        hasFwd -> floatArrayOf(
            positions[baseFwd] - positions[baseSelf],
            positions[baseFwd + 1] - positions[baseSelf + 1],
            positions[baseFwd + 2] - positions[baseSelf + 2],
        )
        hasBack -> floatArrayOf(
            positions[baseSelf] - positions[baseBack],
            positions[baseSelf + 1] - positions[baseBack + 1],
            positions[baseSelf + 2] - positions[baseBack + 2],
        )
        else -> null
    }
}

