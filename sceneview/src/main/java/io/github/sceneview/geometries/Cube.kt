package io.github.sceneview.geometries

import com.google.android.filament.Box
import com.google.android.filament.Engine
import com.google.android.filament.IndexBuffer
import com.google.android.filament.RenderableManager.PrimitiveType
import com.google.android.filament.VertexBuffer
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Position
import io.github.sceneview.math.Size

/**
 * An axis-aligned box [Geometry] with per-face normals and UV coordinates, suitable for
 * use as a renderable mesh (e.g. attached to a `CubeNode`).
 *
 * Instances are immutable once built; create one with the [Builder] and mutate it
 * afterwards through [update]. All Filament buffer operations run synchronously and must
 * therefore be called on the main thread.
 */
class Cube private constructor(
    primitiveType: PrimitiveType,
    vertices: List<Vertex>,
    vertexBuffer: VertexBuffer,
    primitivesIndices: List<List<Int>>,
    indexBuffer: IndexBuffer,
    primitivesOffsets: List<IntRange>,
    boundingBox: Box,
    size: Size,
    center: Position
) : Geometry(
    primitiveType = primitiveType,
    vertices = vertices,
    vertexBuffer = vertexBuffer,
    primitivesIndices = primitivesIndices,
    indexBuffer,
    primitivesOffsets,
    boundingBox
) {
    /**
     * Builder for [Cube]. Configure [size] and [center], then call [build].
     */
    class Builder : Geometry.Builder(PrimitiveType.TRIANGLES) {
        /** Full extents of the box along each axis, in scene units. Defaults to [DEFAULT_SIZE]. */
        var size: Size = DEFAULT_SIZE
            private set

        /** Local-space position of the box center. Defaults to [DEFAULT_CENTER] (the origin). */
        var center: Position = DEFAULT_CENTER
            private set

        /** Sets the full extents of the box along each axis. Returns this builder for chaining. */
        fun size(size: Size) = apply { this.size = size }

        /** Sets the local-space center of the box. Returns this builder for chaining. */
        fun center(center: Position) = apply { this.center = center }

        /**
         * Builds the [Cube], allocating its Filament vertex and index buffers on [engine].
         * Must be called on the main thread.
         */
        override fun build(engine: Engine): Cube {
            vertices(getVertices(size, center))
            primitivesIndices(INDICES)
            return build(engine) { vertexBuffer, indexBuffer, offsets, boundingBox ->
                Cube(
                    primitiveType, vertices, vertexBuffer, indices, indexBuffer, offsets,
                    boundingBox, size, center
                )
            }
        }
    }

    /** Current local-space center of the box. */
    var center: Position = center
        private set

    /** Current full extents of the box along each axis, in scene units. */
    var size: Size = size
        private set

    /**
     * Regenerates the box vertices in place for the given [center] and [size] and re-uploads
     * them to the existing Filament vertex buffer. Index buffer is unchanged. Must be called
     * on the main thread. Returns this geometry for chaining.
     */
    fun update(
        engine: Engine,
        center: Position = this.center,
        size: Size = this.size
    ) = apply {
        // getVertices takes (size, center) — NOT (center, size). Both are `Float3` typealiases,
        // so a transposition compiles cleanly and silently builds the wrong box: this call passed
        // them the wrong way round, so `updateGeometry(size = Size(3f))` on a centred cube built
        // a zero-sized box centred at (3, 3, 3) instead of a 3-unit cube at the origin. The
        // builder above (`vertices(getVertices(size, center))`) always had it right, so only
        // cubes resized *after* construction were affected (#3194).
        update(engine = engine, vertices = getVertices(size, center))

        this.center = center
        this.size = size
    }

    companion object {
        val DEFAULT_SIZE = Size(1.0f)
        val DEFAULT_CENTER = Position(0.0f)

        val INDICES = buildList {
            val sideCount = 6
            val verticesPerSide = 4
            for (i in 0 until sideCount) {
                add(
                    listOf(
                        // First triangle for this side.
                        3 + verticesPerSide * i,
                        1 + verticesPerSide * i,
                        0 + verticesPerSide * i,
                        // Second triangle for this side.
                        3 + verticesPerSide * i,
                        2 + verticesPerSide * i,
                        1 + verticesPerSide * i
                    )
                )
            }
        }

        fun getVertices(size: Size, center: Position) = buildList {
            val extents = size * 0.5f
            val p0 = center + Size(-extents.x, -extents.y, extents.z)
            val p1 = center + Size(extents.x, -extents.y, extents.z)
            val p2 = center + Size(extents.x, -extents.y, -extents.z)
            val p3 = center + Size(-extents.x, -extents.y, -extents.z)
            val p4 = center + Size(-extents.x, extents.y, extents.z)
            val p5 = center + Size(extents.x, extents.y, extents.z)
            val p6 = center + Size(extents.x, extents.y, -extents.z)
            val p7 = center + Size(-extents.x, extents.y, -extents.z)
            val up = Direction(y = 1.0f)
            val down = Direction(y = -1.0f)
            val front = Direction(z = -1.0f)
            val back = Direction(z = 1.0f)
            val left = Direction(x = -1.0f)
            val right = Direction(x = 1.0f)
            val uv00 = UvCoordinate(x = 0.0f, y = 0.0f)
            val uv10 = UvCoordinate(x = 1.0f, y = 0.0f)
            val uv01 = UvCoordinate(x = 0.0f, y = 1.0f)
            val uv11 = UvCoordinate(x = 1.0f, y = 1.0f)
            addAll(
                listOf(
                    // Bottom
                    Vertex(position = p0, normal = down, uvCoordinate = uv01),
                    Vertex(position = p1, normal = down, uvCoordinate = uv11),
                    Vertex(position = p2, normal = down, uvCoordinate = uv10),
                    Vertex(position = p3, normal = down, uvCoordinate = uv00),
                    // Left
                    Vertex(position = p7, normal = left, uvCoordinate = uv01),
                    Vertex(position = p4, normal = left, uvCoordinate = uv11),
                    Vertex(position = p0, normal = left, uvCoordinate = uv10),
                    Vertex(position = p3, normal = left, uvCoordinate = uv00),
                    // Back
                    Vertex(position = p4, normal = back, uvCoordinate = uv01),
                    Vertex(position = p5, normal = back, uvCoordinate = uv11),
                    Vertex(position = p1, normal = back, uvCoordinate = uv10),
                    Vertex(position = p0, normal = back, uvCoordinate = uv00),
                    // Front
                    Vertex(position = p6, normal = front, uvCoordinate = uv01),
                    Vertex(position = p7, normal = front, uvCoordinate = uv11),
                    Vertex(position = p3, normal = front, uvCoordinate = uv10),
                    Vertex(position = p2, normal = front, uvCoordinate = uv00),
                    // Right
                    Vertex(position = p5, normal = right, uvCoordinate = uv01),
                    Vertex(position = p6, normal = right, uvCoordinate = uv11),
                    Vertex(position = p2, normal = right, uvCoordinate = uv10),
                    Vertex(position = p1, normal = right, uvCoordinate = uv00),
                    // Top
                    Vertex(position = p7, normal = up, uvCoordinate = uv01),
                    Vertex(position = p6, normal = up, uvCoordinate = uv11),
                    Vertex(position = p5, normal = up, uvCoordinate = uv10),
                    Vertex(position = p4, normal = up, uvCoordinate = uv00)
                )
            )
        }
    }
}