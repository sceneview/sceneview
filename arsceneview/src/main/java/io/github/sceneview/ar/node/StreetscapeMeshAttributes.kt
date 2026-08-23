package io.github.sceneview.ar.node

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
