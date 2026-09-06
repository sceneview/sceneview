package io.github.sceneview.core.ply

import io.github.sceneview.core.threemf.ThreeMfUnit

/** Invalid or unsupported PLY data. */
class PlyParseException(message: String) : RuntimeException(message)

/**
 * A triangulated PLY mesh in its source axes and [unit].
 *
 * [positions] and optional [colors] are per vertex. [normals] are per index so supplied vertex
 * normals and generated smooth normals can both be passed directly to the shared GLB writer.
 */
class PlyModel internal constructor(
    val positions: FloatArray,
    val indices: IntArray,
    val normals: FloatArray,
    val colors: FloatArray?,
    val unit: ThreeMfUnit
) {
    val vertexCount: Int get() = positions.size / 3
    val triangleCount: Int get() = indices.size / 3
}

internal fun plyError(message: String): Nothing = throw PlyParseException(message)
