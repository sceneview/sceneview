package io.github.sceneview.core.stl

import io.github.sceneview.core.threemf.ThreeMfUnit

/**
 * An unreadable STL: malformed text, truncated binary data, non-finite positions, or an exceeded
 * byte/triangle limit. Catch this around `StlLoader.toGlb(bytes)` to report a failed import.
 * No geometry is allocated before the input byte limit and binary triangle count are checked.
 */
class StlParseException(message: String) : RuntimeException(message)

/**
 * A welded STL mesh returned by `StlLoader.parse(bytes)`, in the caller's [unit] (millimetres by
 * default) and original axes. STL declares neither units nor an up axis.
 *
 * Coincident positions are welded exactly, including signed zero; no tolerance merges nearby
 * vertices. Normals are per **corner**, so valid facet normals retain hard edges while corners
 * with missing, zero or non-finite normals receive area-weighted smooth normals from adjoining
 * faces. Zero-area faces contribute nothing; a cancelled/empty sum falls back to +Y.
 *
 * @property positions Three floats (x, y, z) per welded vertex, in [unit].
 * @property indices Three position indices per triangle, in the file's winding order.
 * @property normals Three normal components per **index** (nine floats per triangle), normalized.
 * @property unit Caller-supplied length unit; reused from 3MF without changing that public enum.
 */
class StlModel internal constructor(
    val positions: FloatArray,
    val indices: IntArray,
    val normals: FloatArray,
    val unit: ThreeMfUnit
) {
    /** Number of unique positions: `StlLoader.parse(bytes).vertexCount`. */
    val vertexCount: Int get() = positions.size / 3

    /** Number of facets: `StlLoader.parse(bytes).triangleCount`. */
    val triangleCount: Int get() = indices.size / 3
}

internal fun stlError(message: String): Nothing = throw StlParseException(message)
