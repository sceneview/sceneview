package io.github.sceneview.ar.node

import com.google.android.filament.Box
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.cross
import dev.romainguy.kotlin.math.dot
import dev.romainguy.kotlin.math.normalize
import io.github.sceneview.math.normalToTangent
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer

/**
 * Per-vertex attribute derivation for [StreetscapeGeometryNode] (#3215).
 *
 * ARCore's Streetscape Geometry mesh ships positions and indices only. The lit materials the
 * node is normally given (`opaque_colored` / `transparent_colored`, the output of
 * `MaterialLoader.createColorInstance`) require POSITION|TANGENTS|UV0, and Filament does not
 * fail a mismatch — it logs `missing required attributes (0xb), declared=0x1` and shades the
 * building overlay against a constant fallback normal instead of its own surface.
 *
 * These helpers are pure JVM (no Filament, no ARCore) so they are unit-testable and so the
 * work can be measured: it runs once per geometry, at node construction.
 */

/** Bytes per vertex of the TANGENTS attribute — an xyzw quaternion, FLOAT4. */
internal const val STREETSCAPE_TANGENT_STRIDE = 4 * 4

/** Bytes per vertex of the UV0 attribute — FLOAT2. */
internal const val STREETSCAPE_UV_STRIDE = 2 * 4

/**
 * Normal assigned to a vertex no non-degenerate triangle references — +Y, matching the
 * plane visualizers' flat fallback. Never decodes to a NaN frame.
 */
private val FALLBACK_NORMAL = Float3(0.0f, 1.0f, 0.0f)

/**
 * Builds the TANGENTS buffer for a triangle mesh from its positions and indices.
 *
 * Normals are **smooth, area-weighted**: every triangle's unnormalised face normal
 * (`cross(b - a, c - a)`, whose length is twice the triangle area) is accumulated onto its three
 * vertices, then each sum is normalised. Large faces therefore dominate a shared vertex, which
 * is what a building façade meeting a terrain patch wants — there is no per-face split in the
 * ARCore mesh, so a hard-edge reconstruction is not possible without duplicating vertices.
 * Winding follows Filament's default (counter-clockwise front face).
 *
 * Each normal is then encoded as a tangent-frame quaternion via [normalToTangent], which is
 * the only form Filament's vertex shader accepts (there is no NORMAL slot in
 * `VertexBuffer.VertexAttribute`).
 *
 * @param positions `vertexCount * 3` floats, `xyz` per vertex. Read from position 0 regardless
 *   of the buffer's current position; the buffer's position is left unchanged.
 * @param indices `3 * triangleCount` vertex indices. Out-of-range or negative indices are
 *   skipped rather than thrown — a malformed ARCore mesh must not crash the session.
 * @return a direct, native-order buffer of `vertexCount * 16` bytes, position 0.
 */
internal fun computeStreetscapeTangents(
    positions: FloatBuffer,
    indices: IntBuffer,
    vertexCount: Int,
): ByteBuffer {
    val pos = positions.duplicate().apply { rewind() }
    val idx = indices.duplicate().apply { rewind() }

    // Accumulate unnormalised face normals per vertex.
    val nx = FloatArray(vertexCount)
    val ny = FloatArray(vertexCount)
    val nz = FloatArray(vertexCount)

    val triangleCount = idx.limit() / 3
    for (t in 0 until triangleCount) {
        val ia = idx.get(t * 3)
        val ib = idx.get(t * 3 + 1)
        val ic = idx.get(t * 3 + 2)
        if (ia !in 0 until vertexCount || ib !in 0 until vertexCount || ic !in 0 until vertexCount) {
            continue
        }
        val a = vertexAt(pos, ia)
        val b = vertexAt(pos, ib)
        val c = vertexAt(pos, ic)
        val n = cross(b - a, c - a)
        nx[ia] += n.x; ny[ia] += n.y; nz[ia] += n.z
        nx[ib] += n.x; ny[ib] += n.y; nz[ib] += n.z
        nx[ic] += n.x; ny[ic] += n.y; nz[ic] += n.z
    }

    val out = ByteBuffer
        .allocateDirect(vertexCount * STREETSCAPE_TANGENT_STRIDE)
        .order(ByteOrder.nativeOrder())
    val floats = out.asFloatBuffer()
    for (v in 0 until vertexCount) {
        val sum = Float3(nx[v], ny[v], nz[v])
        val normal = if (dot(sum, sum) > 0.0f) normalize(sum) else FALLBACK_NORMAL
        val q = normalToTangent(normal)
        floats.put(q.x)
        floats.put(q.y)
        floats.put(q.z)
        floats.put(q.w)
    }
    out.rewind()
    return out
}

/**
 * A zero-filled UV0 buffer of `vertexCount` FLOAT2 entries.
 *
 * Streetscape meshes have no natural parameterisation and the colored materials never sample
 * a texture; the attribute only has to *exist* for Filament to stop reporting it missing. A
 * newly allocated direct buffer is already zeroed, so no fill pass is needed.
 */
internal fun zeroStreetscapeUvs(vertexCount: Int): ByteBuffer = ByteBuffer
    .allocateDirect(vertexCount * STREETSCAPE_UV_STRIDE)
    .order(ByteOrder.nativeOrder())

private fun vertexAt(positions: FloatBuffer, index: Int): Float3 = Float3(
    positions.get(index * 3),
    positions.get(index * 3 + 1),
    positions.get(index * 3 + 2),
)

/**
 * Smallest half-extent (metres) of a Streetscape mesh's bounding box. A flat terrain patch has a
 * zero extent on its up axis, and Filament treats a box with a zero half-extent as empty.
 */
internal const val STREETSCAPE_MIN_AABB_HALF_EXTENT_M: Float = 1e-3f

/**
 * `true` when an ARCore Streetscape mesh has something to draw: at least one vertex and one
 * whole triangle. [StreetscapeGeometryNode] builds no renderable for any other mesh.
 */
internal fun isStreetscapeMeshRenderable(vertexCount: Int, indexCount: Int): Boolean =
    vertexCount > 0 && indexCount >= 3

/**
 * Axis-aligned bounding box of the first [vertexCount] `xyz` positions of a Streetscape mesh.
 *
 * Filament's `RenderableManager.Builder.build` aborts with `AABB can't be empty, unless culling
 * is disabled and the object is not a shadow caster/receiver` when a renderable has no box, and
 * a renderable receives shadows by default. [StreetscapeGeometryNode] used to build its mesh
 * without a box, so the first geometry ARCore delivered aborted the app.
 *
 * Every half-extent is clamped to at least [STREETSCAPE_MIN_AABB_HALF_EXTENT_M] so a flat or
 * single-point mesh still yields a non-empty box. Non-finite coordinates are ignored.
 *
 * @param positions `xyz` floats, read from index 0; the buffer's position is left unchanged.
 * @param vertexCount number of vertices to read, clamped to what [positions] holds.
 * @return the box, or `null` when there is no finite vertex to bound (empty mesh).
 */
internal fun computeStreetscapeAabb(positions: FloatBuffer, vertexCount: Int): Box? {
    val count = minOf(vertexCount, positions.limit() / 3)
    var minX = Float.POSITIVE_INFINITY
    var minY = Float.POSITIVE_INFINITY
    var minZ = Float.POSITIVE_INFINITY
    var maxX = Float.NEGATIVE_INFINITY
    var maxY = Float.NEGATIVE_INFINITY
    var maxZ = Float.NEGATIVE_INFINITY
    var bounded = 0
    for (v in 0 until count) {
        val x = positions.get(v * 3)
        val y = positions.get(v * 3 + 1)
        val z = positions.get(v * 3 + 2)
        if (!x.isFinite() || !y.isFinite() || !z.isFinite()) continue
        if (x < minX) minX = x
        if (y < minY) minY = y
        if (z < minZ) minZ = z
        if (x > maxX) maxX = x
        if (y > maxY) maxY = y
        if (z > maxZ) maxZ = z
        bounded++
    }
    if (bounded == 0) return null
    return Box(
        (minX + maxX) * 0.5f,
        (minY + maxY) * 0.5f,
        (minZ + maxZ) * 0.5f,
        ((maxX - minX) * 0.5f).coerceAtLeast(STREETSCAPE_MIN_AABB_HALF_EXTENT_M),
        ((maxY - minY) * 0.5f).coerceAtLeast(STREETSCAPE_MIN_AABB_HALF_EXTENT_M),
        ((maxZ - minZ) * 0.5f).coerceAtLeast(STREETSCAPE_MIN_AABB_HALF_EXTENT_M),
    )
}
