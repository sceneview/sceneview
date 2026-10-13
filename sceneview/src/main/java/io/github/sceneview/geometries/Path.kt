package io.github.sceneview.geometries

import com.google.android.filament.Box
import com.google.android.filament.Engine
import com.google.android.filament.IndexBuffer
import com.google.android.filament.RenderableManager.PrimitiveType
import com.google.android.filament.VertexBuffer
import io.github.sceneview.math.Position

/**
 * A polyline geometry through a list of 3D points.
 *
 * Uses Filament's [PrimitiveType.LINES] primitive type with explicit pairs of indices so that
 * each consecutive pair of vertices forms one line segment.  When [closed] is `true`, an extra
 * segment connects the last point back to the first.
 */
class Path private constructor(
    primitiveType: PrimitiveType,
    vertices: List<Vertex>,
    vertexBuffer: VertexBuffer,
    indices: List<List<Int>>,
    indexBuffer: IndexBuffer,
    primitivesOffsets: List<IntRange>,
    boundingBox: Box,
    points: List<Position>,
    closed: Boolean
) : Geometry(
    primitiveType,
    vertices,
    vertexBuffer,
    indices,
    indexBuffer,
    primitivesOffsets,
    boundingBox
) {
    class Builder : Geometry.Builder(PrimitiveType.LINES) {
        var points: List<Position> = DEFAULT_POINTS
            private set
        var closed: Boolean = false
            private set

        fun points(points: List<Position>) = apply { this.points = points }
        fun closed(closed: Boolean) = apply { this.closed = closed }

        override fun build(engine: Engine): Path {
            vertices(getVertices(points))
            primitivesIndices(getIndices(points.size, closed))
            return build(engine) { vertexBuffer, indexBuffer, offsets, boundingBox ->
                Path(
                    primitiveType, vertices, vertexBuffer, indices, indexBuffer, offsets,
                    boundingBox, points, closed
                )
            }
        }
    }

    var points: List<Position> = points
        private set
    var closed: Boolean = closed
        private set

    /**
     * @throws IllegalArgumentException if [points] holds fewer than 2 points: a path that short
     * has no segment, and Filament aborts on the empty buffers it would need.
     */
    fun update(
        engine: Engine,
        points: List<Position> = this.points,
        closed: Boolean = this.closed
    ) = apply {
        update(
            engine = engine,
            vertices = getVertices(points),
            primitivesIndices = getIndices(points.size, closed)
        )
        this.points = points
        this.closed = closed
    }

    companion object {
        val DEFAULT_POINTS = listOf(Position(0f, 0f, 0f), Position(1f, 0f, 0f))

        /**
         * One vertex per point.
         *
         * Both [Builder.build] and [update] come through here before touching Filament, so this
         * is where a degenerate path is refused: with one point there is no segment and an empty
         * index buffer, with none an empty vertex buffer — Filament aborts natively on either.
         *
         * @throws IllegalArgumentException if [points] holds fewer than 2 points.
         */
        fun getVertices(points: List<Position>): List<Vertex> {
            require(points.size >= 2) { "Path requires at least 2 points" }
            return points.map { Vertex(position = it) }
        }

        /**
         * Build one flat list of index pairs — two indices per segment:
         *   (0,1), (1,2), ..., (n-2, n-1) and optionally (n-1, 0) when closed.
         */
        fun getIndices(count: Int, closed: Boolean): List<List<Int>> {
            val pairs = mutableListOf<Int>()
            for (i in 0 until count - 1) {
                pairs.add(i)
                pairs.add(i + 1)
            }
            if (closed && count >= 2) {
                pairs.add(count - 1)
                pairs.add(0)
            }
            return listOf(pairs)
        }
    }
}
