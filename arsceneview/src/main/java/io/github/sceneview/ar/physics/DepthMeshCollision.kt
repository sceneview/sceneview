package io.github.sceneview.ar.physics

import io.github.sceneview.math.Transform
import kotlin.math.max
import kotlin.math.min

/**
 * Pure math helpers backing [DepthCollider] (#1713).
 *
 * Everything in this file is `internal` (no ARCore / Filament types) so it can be unit-tested on
 * the JVM without an instrumented device — sign errors in the transform application or the
 * sphere-vs-triangle collision would otherwise only surface on depth-capable hardware.
 */

/**
 * Transforms a flat-packed array of camera-space vertex positions into world space.
 *
 * The depth mesh's vertices are produced in ARCore camera space by [io.github.sceneview.ar.arcore.buildDepthMeshGeometry];
 * the [com.google.android.filament.RenderableManager] hands them to Filament as-is and the node's
 * `worldTransform` puts them on screen. For physics we want them resolved into world space **once**
 * per rebuild so per-frame queries are cheap point/triangle math.
 *
 * @param positionsCameraSpace  Flat-packed `[x0,y0,z0, x1,y1,z1, ...]` from a [io.github.sceneview.ar.node.DepthMeshSnapshot].
 * @param worldTransform        The camera pose at rebuild time, as a 4×4 column-major matrix.
 * @return                      A new flat-packed array of the same length, in world space.
 */
internal fun transformPositionsToWorld(
    positionsCameraSpace: FloatArray,
    worldTransform: Transform,
): FloatArray {
    val out = FloatArray(positionsCameraSpace.size)
    val vertexCount = positionsCameraSpace.size / 3
    // Inline the 4×4 × (x,y,z,1) matrix multiply directly into the output FloatArray (#1810).
    // The previous `worldTransform * Position(x, y, z)` shape allocated 2 small objects per vertex
    // — a `Float4` for the homogeneous extension and a `Float3` for the post-divide result. At
    // ~920 vertices per rebuild × 5 Hz this added up to ~9k transient allocs/sec on the render
    // thread. Reading the `Float4x4` columns once + a straight per-vertex matrix-vector multiply
    // produces the same numbers with zero allocation.
    //
    // `Float4x4` from `dev.romainguy.kotlin.math` is column-major:
    //   column 0 = (x.x, x.y, x.z, x.w)  // first basis vector
    //   column 1 = (y.x, y.y, y.z, y.w)
    //   column 2 = (z.x, z.y, z.z, z.w)
    //   column 3 = (translation.x, translation.y, translation.z, translation.w)
    val m00 = worldTransform.x.x; val m10 = worldTransform.x.y; val m20 = worldTransform.x.z
    val m01 = worldTransform.y.x; val m11 = worldTransform.y.y; val m21 = worldTransform.y.z
    val m02 = worldTransform.z.x; val m12 = worldTransform.z.y; val m22 = worldTransform.z.z
    val m03 = worldTransform.w.x; val m13 = worldTransform.w.y; val m23 = worldTransform.w.z
    var i = 0
    while (i < vertexCount) {
        val base = i * 3
        val x = positionsCameraSpace[base]
        val y = positionsCameraSpace[base + 1]
        val z = positionsCameraSpace[base + 2]
        // (x,y,z,1) is the homogeneous extension. The bottom row of a TRS-transform is (0,0,0,1)
        // so the perspective divide is unconditionally 1 — we drop it for the inner loop.
        out[base] = m00 * x + m01 * y + m02 * z + m03
        out[base + 1] = m10 * x + m11 * y + m12 * z + m13
        out[base + 2] = m20 * x + m21 * y + m22 * z + m23
        i++
    }
    return out
}

/**
 * Finds the highest triangle apex directly under a sphere centred at ([x], [y], [z]) with radius
 * [radius]. Returns `null` when no triangle is found within `[x±radius, z±radius]` whose vertical
 * projection contains [x], [z].
 *
 * "Highest apex" semantics: among all candidate triangles whose XZ projection contains [x], [z],
 * the one with the largest interpolated Y at that XZ is returned. This gives the body something
 * to bounce off **including overhangs** — a ball thrown at a real desk lands on the desk top, not
 * the floor under the desk.
 *
 * Triangles whose XZ-projection is degenerate (all three points collinear in XZ) are skipped —
 * they have zero physical surface from above and treating them as a contact would divide-by-zero
 * the barycentric inversion.
 *
 * Pure function — no ARCore, no Filament, JUnit-testable on the JVM.
 *
 * @param positionsWorld   Flat-packed vertex positions in world space.
 * @param indices          Triangle indices, 3 per triangle.
 * @param x                Sphere centre X in world space.
 * @param y                Sphere centre Y in world space (unused for the lookup but kept for API
 *                         symmetry — a future "closest triangle in 3D" variant will need it).
 * @param z                Sphere centre Z in world space.
 * @param radius           Search radius around ([x], [z]) — triangles whose AABB doesn't overlap
 *                         this disc are culled before the inside-triangle test.
 * @return                 The interpolated Y of the highest matching triangle at ([x], [z]), or
 *                         `null` when no triangle covers that XZ.
 */
@Suppress("UNUSED_PARAMETER") // y reserved for a future 3D variant — keep stable API
internal fun nearestSurfaceYBelow(
    positionsWorld: FloatArray,
    indices: IntArray,
    x: Float,
    y: Float,
    z: Float,
    radius: Float,
): Float? {
    if (indices.isEmpty()) return null

    var bestY: Float? = null
    val rSquared = radius * radius
    val triangleCount = indices.size / 3
    val posSize = positionsWorld.size
    var t = 0
    while (t < triangleCount) {
        val i0 = indices[t * 3] * 3
        val i1 = indices[t * 3 + 1] * 3
        val i2 = indices[t * 3 + 2] * 3

        // Defensive bounds check (#1812): a future drift between [positionsWorld] and [indices]
        // (e.g. caller passed mismatched arrays, or a partial copy after a region cull bug) would
        // otherwise AIOOBE on the render thread mid-frame. Skip the malformed triangle instead.
        if (i0 < 0 || i1 < 0 || i2 < 0 ||
            i0 + 2 >= posSize || i1 + 2 >= posSize || i2 + 2 >= posSize
        ) {
            t++
            continue
        }

        val ax = positionsWorld[i0]
        val ay = positionsWorld[i0 + 1]
        val az = positionsWorld[i0 + 2]
        val bx = positionsWorld[i1]
        val by = positionsWorld[i1 + 1]
        val bz = positionsWorld[i1 + 2]
        val cx = positionsWorld[i2]
        val cy = positionsWorld[i2 + 1]
        val cz = positionsWorld[i2 + 2]

        // Broad-phase: skip triangles whose XZ AABB doesn't overlap the search disc.
        val minX = min(ax, min(bx, cx))
        val maxX = max(ax, max(bx, cx))
        val minZ = min(az, min(bz, cz))
        val maxZ = max(az, max(bz, cz))
        if (x + radius < minX || x - radius > maxX || z + radius < minZ || z - radius > maxZ) {
            t++
            continue
        }

        // Barycentric coords in XZ — point ([x], [z]) is inside iff all three are in [0,1].
        // Skip degenerate (collinear-in-XZ) triangles where denom ≈ 0.
        val denom = (bz - cz) * (ax - cx) + (cx - bx) * (az - cz)
        if (kotlin.math.abs(denom) < 1e-7f) {
            t++
            continue
        }
        val invDenom = 1f / denom
        val u = ((bz - cz) * (x - cx) + (cx - bx) * (z - cz)) * invDenom
        val v = ((cz - az) * (x - cx) + (ax - cx) * (z - cz)) * invDenom
        val w = 1f - u - v
        if (u < 0f || v < 0f || w < 0f) {
            // The XZ projection of ([x],[z]) is outside the triangle, but the triangle's AABB
            // overlapped the search disc — see if a triangle vertex itself is within `radius`
            // of ([x],[z]). That keeps a body resting on a near-vertical wall responsive even
            // when its XZ centre projects just past the wall's footprint.
            val d0 = (ax - x) * (ax - x) + (az - z) * (az - z)
            val d1 = (bx - x) * (bx - x) + (bz - z) * (bz - z)
            val d2 = (cx - x) * (cx - x) + (cz - z) * (cz - z)
            if (d0 > rSquared && d1 > rSquared && d2 > rSquared) {
                t++
                continue
            }
            // The XZ centre is outside the triangle but a vertex is in range. Take the
            // highest-Y vertex as the contact candidate — conservative & cheap.
            val candidate = max(ay, max(by, cy))
            bestY = if (bestY == null) candidate else max(bestY, candidate)
            t++
            continue
        }

        // Interpolate Y at ([x], [z]) using the barycentric coords.
        val interpY = u * ay + v * by + w * cy
        bestY = if (bestY == null) interpY else max(bestY, interpY)
        t++
    }
    return bestY
}

/**
 * AABB in world space — used by [DepthCollider] for region-near-bodies culling so a single ball
 * doesn't pay for the cost of testing every triangle of the depth mesh.
 *
 * @property minX  inclusive lower X bound
 * @property minY  inclusive lower Y bound
 * @property minZ  inclusive lower Z bound
 * @property maxX  inclusive upper X bound
 * @property maxY  inclusive upper Y bound
 * @property maxZ  inclusive upper Z bound
 */
internal data class RegionAabb(
    val minX: Float,
    val minY: Float,
    val minZ: Float,
    val maxX: Float,
    val maxY: Float,
    val maxZ: Float,
) {
    fun isFinite(): Boolean = minX.isFinite() && minY.isFinite() && minZ.isFinite() &&
        maxX.isFinite() && maxY.isFinite() && maxZ.isFinite()

    companion object {
        /** Region that contains nothing — used as an identity for [union]. */
        val EMPTY: RegionAabb = RegionAabb(
            minX = Float.POSITIVE_INFINITY,
            minY = Float.POSITIVE_INFINITY,
            minZ = Float.POSITIVE_INFINITY,
            maxX = Float.NEGATIVE_INFINITY,
            maxY = Float.NEGATIVE_INFINITY,
            maxZ = Float.NEGATIVE_INFINITY,
        )
    }
}

/**
 * Computes a single padded AABB enclosing every sphere in [centres] expanded by [padding] on each
 * side. Returns [RegionAabb.EMPTY] when [centres] is empty — callers should treat that as "skip
 * the whole region cull" rather than "the world is empty".
 *
 * Pure function — JUnit-testable.
 *
 * @param centres   Sphere centres in world space (flat-packed `[x0,y0,z0, x1,y1,z1, ...]`).
 * @param padding   Per-axis padding in metres. Use roughly the sphere's collision radius + a small
 *                  fudge so a fast-moving body doesn't tunnel out of the region between rebuilds.
 */
internal fun computeBodiesRegion(centres: FloatArray, padding: Float): RegionAabb {
    if (centres.isEmpty()) return RegionAabb.EMPTY
    val bodyCount = centres.size / 3
    var minX = Float.POSITIVE_INFINITY
    var minY = Float.POSITIVE_INFINITY
    var minZ = Float.POSITIVE_INFINITY
    var maxX = Float.NEGATIVE_INFINITY
    var maxY = Float.NEGATIVE_INFINITY
    var maxZ = Float.NEGATIVE_INFINITY
    var i = 0
    while (i < bodyCount) {
        val base = i * 3
        val x = centres[base]
        val y = centres[base + 1]
        val z = centres[base + 2]
        if (x < minX) minX = x
        if (y < minY) minY = y
        if (z < minZ) minZ = z
        if (x > maxX) maxX = x
        if (y > maxY) maxY = y
        if (z > maxZ) maxZ = z
        i++
    }
    return RegionAabb(
        minX = minX - padding,
        minY = minY - padding,
        minZ = minZ - padding,
        maxX = maxX + padding,
        maxY = maxY + padding,
        maxZ = maxZ + padding,
    )
}

/**
 * Returns a new triangle-index array containing only triangles whose AABB overlaps [region].
 *
 * Used by [DepthCollider] to keep the per-frame, per-body inner loop small when the depth mesh has
 * thousands of triangles but the bodies are clustered in one spot — Depth Lab's "Collider" scene
 * uses the same trick.
 *
 * Pure function — JUnit-testable.
 *
 * @param positionsWorld    Vertex positions in world space.
 * @param indices           Triangle indices, 3 per triangle.
 * @param region            World-space AABB. Triangles whose AABB doesn't overlap it are dropped.
 * @return                  A new index array of length divisible by 3.
 */
internal fun cullIndicesToRegion(
    positionsWorld: FloatArray,
    indices: IntArray,
    region: RegionAabb,
): IntArray {
    if (indices.isEmpty()) return indices
    if (!region.isFinite()) return IntArray(0)
    val out = IntArray(indices.size)
    var outSize = 0
    val triangleCount = indices.size / 3
    var t = 0
    while (t < triangleCount) {
        val i0 = indices[t * 3] * 3
        val i1 = indices[t * 3 + 1] * 3
        val i2 = indices[t * 3 + 2] * 3
        val ax = positionsWorld[i0]; val ay = positionsWorld[i0 + 1]; val az = positionsWorld[i0 + 2]
        val bx = positionsWorld[i1]; val by = positionsWorld[i1 + 1]; val bz = positionsWorld[i1 + 2]
        val cx = positionsWorld[i2]; val cy = positionsWorld[i2 + 1]; val cz = positionsWorld[i2 + 2]
        val triMinX = min(ax, min(bx, cx))
        val triMaxX = max(ax, max(bx, cx))
        val triMinY = min(ay, min(by, cy))
        val triMaxY = max(ay, max(by, cy))
        val triMinZ = min(az, min(bz, cz))
        val triMaxZ = max(az, max(bz, cz))
        if (
            triMaxX >= region.minX && triMinX <= region.maxX &&
            triMaxY >= region.minY && triMinY <= region.maxY &&
            triMaxZ >= region.minZ && triMinZ <= region.maxZ
        ) {
            out[outSize] = indices[t * 3]
            out[outSize + 1] = indices[t * 3 + 1]
            out[outSize + 2] = indices[t * 3 + 2]
            outSize += 3
        }
        t++
    }
    return if (outSize == out.size) out else out.copyOf(outSize)
}
