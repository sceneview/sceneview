package io.github.sceneview.geometries

import com.google.android.filament.Box
import com.google.android.filament.Engine
import com.google.android.filament.IndexBuffer
import com.google.android.filament.RenderableManager
import com.google.android.filament.RenderableManager.PrimitiveType
import com.google.android.filament.VertexBuffer
import dev.romainguy.kotlin.math.Float2
import dev.romainguy.kotlin.math.max
import dev.romainguy.kotlin.math.min
import io.github.sceneview.EntityInstance
import io.github.sceneview.math.Box
import io.github.sceneview.math.Color
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Position
import io.github.sceneview.math.normalToTangent
import java.nio.FloatBuffer
import java.nio.IntBuffer

typealias UvCoordinate = Float2
typealias UvScale = Float2

private const val kPositionSize = 3 // x, y, z
private const val kTangentSize = 4 // Quaternion: x, y, z, w
private const val kUVSize = 2 // x, y
private const val kColorSize = 4 // r, g, b, a

/**
 * Geometry parameters for building and updating a Renderable
 *
 * A renderable is made of several primitives.
 * You can ever declare only 1 if you want each parts of your Geometry to have the same material
 * or one for each triangle indices with a different material.
 * We could declare n primitives (n per face) and give each of them a different material
 * instance, setup with different parameters
 *
 * @see Cube
 * @see Cylinder
 * @see Plane
 * @see Sphere
 */
open class Geometry internal constructor(
    val primitiveType: PrimitiveType,
    vertices: List<Vertex>,
    val vertexBuffer: VertexBuffer,
    primitivesIndices: List<List<Int>>,
    val indexBuffer: IndexBuffer,
    var primitivesOffsets: List<IntRange>,
    var boundingBox: Box
) {
    /**
     * Used for constructing renderables dynamically
     *
     * @param uvCoordinate Represents a texture Coordinate for a Vertex.
     * Values should be between 0 and 1.
     */
    data class Vertex(
        val position: Position = Position(),
        val normal: Direction? = null,
        val uvCoordinate: UvCoordinate? = null,
        val color: Color? = null
    )

//    /**
//     * Represents a Submesh for a Geometry.
//     *
//     * Each Geometry may have multiple Submeshes.
//     */
//    data class PrimitiveIndices(val indices: List<Int>) {
//        constructor(vararg indices: Int) : this(indices.toList())
//    }

    open class Builder(val primitiveType: PrimitiveType = PrimitiveType.TRIANGLES) {
        protected val vertexBuilder = VertexBuffer.Builder()
        protected val indexBuilder = IndexBuffer.Builder()

        protected var vertices: List<Vertex> = listOf()
        protected var indices: List<List<Int>> = listOf()

        fun vertices(vertices: List<Vertex>) = apply {
            vertexBuilder.bufferCount(
                1 + // Position is never null
                        (if (vertices.hasNormals) 1 else 0) +
                        (if (vertices.hasUvCoordinates) 1 else 0) +
                        (if (vertices.hasColors) 1 else 0)
            )
            vertexBuilder.vertexCount(vertices.size)

            // Position Attribute
            var bufferIndex = 0
            vertexBuilder.attribute(
                VertexBuffer.VertexAttribute.POSITION,
                bufferIndex,
                VertexBuffer.AttributeType.FLOAT3,
                0,
                kPositionSize * Float.SIZE_BYTES
            )
            // Tangents Attribute
            if (vertices.hasNormals) {
                bufferIndex++
                vertexBuilder.attribute(
                    VertexBuffer.VertexAttribute.TANGENTS,
                    bufferIndex,
                    VertexBuffer.AttributeType.FLOAT4,
                    0,
                    kTangentSize * Float.SIZE_BYTES
                )
                vertexBuilder.normalized(VertexBuffer.VertexAttribute.TANGENTS)
            }
            // Uv Attribute
            if (vertices.hasUvCoordinates) {
                bufferIndex++
                vertexBuilder.attribute(
                    VertexBuffer.VertexAttribute.UV0,
                    bufferIndex,
                    VertexBuffer.AttributeType.FLOAT2,
                    0,
                    kUVSize * Float.SIZE_BYTES
                )
            }
            // Color Attribute
            if (vertices.hasColors) {
                bufferIndex++
                vertexBuilder.attribute(
                    VertexBuffer.VertexAttribute.COLOR,
                    bufferIndex,
                    VertexBuffer.AttributeType.FLOAT4,
                    0,
                    kColorSize * Float.SIZE_BYTES
                )
                vertexBuilder.normalized(VertexBuffer.VertexAttribute.COLOR)
            }
            this.vertices = vertices
        }

        fun primitivesIndices(indices: List<List<Int>>) = apply {
            indexBuilder.indexCount(indices.sumOf { it.size })
                .bufferType(IndexBuffer.Builder.IndexType.UINT)
            this.indices = indices
        }

        fun indices(indices: List<Int>) = primitivesIndices(listOf(indices))

        fun <T : Geometry> build(
            engine: Engine,
            constructor: (
                vertexBuffer: VertexBuffer, indexBuffer: IndexBuffer,
                offsets: List<IntRange>, boundingBox: Box
            ) -> T
        ): T {
            val vertexBuffer = vertexBuilder.build(engine)
            val boundingBox = vertexBuffer.setVertices(engine, vertices)
            val indexBuffer = indexBuilder.build(engine).apply {
                setIndices(engine, indices.flatten())
            }
            return constructor(
                vertexBuffer,
                indexBuffer,
                indices.getOffsets(),
                boundingBox
            )
        }

        open fun build(engine: Engine) =
            build(engine) { vertexBuffer, indexBuffer, offsets, boundingBox ->
                Geometry(
                    primitiveType, vertices, vertexBuffer, indices, indexBuffer,
                    offsets, boundingBox
                )
            }
    }

    var vertices: List<Vertex> = vertices
        private set

    var primitivesIndices: List<List<Int>> = primitivesIndices
        private set

    val indices: List<Int>
        get() = primitivesIndices.flatten()

    fun setVertices(engine: Engine, vertices: List<Vertex>) {
        this.vertices = vertices
        boundingBox = vertexBuffer.setVertices(engine, vertices)
    }

    fun setPrimitivesIndices(engine: Engine, primitivesIndices: List<List<Int>>) {
        this.primitivesIndices = primitivesIndices
        primitivesOffsets = primitivesIndices.getOffsets()
        indexBuffer.setIndices(engine, primitivesIndices.flatMap { it.indices })
    }

    fun update(
        engine: Engine,
        vertices: List<Vertex> = this.vertices,
        primitivesIndices: List<List<Int>> = this.primitivesIndices
    ) = apply {
        if (this.vertices != vertices) {
            setVertices(engine, vertices)
        }
        if (this.primitivesIndices != primitivesIndices) {
            setPrimitivesIndices(engine, primitivesIndices)
        }
    }
}

val List<Geometry.Vertex>.hasNormals get() = any { it.normal != null }
val List<Geometry.Vertex>.hasUvCoordinates get() = any { it.uvCoordinate != null }
val List<Geometry.Vertex>.hasColors get() = any { it.color != null }

fun VertexBuffer.setVertices(engine: Engine, vertices: List<Geometry.Vertex>): Box {
    var bufferIndex = 0

    // Create position Buffer
    setBufferAt(
        engine, bufferIndex,
        FloatBuffer.allocate(vertices.size * kPositionSize).apply {
            vertices.forEach { put(it.position.toFloatArray()) }
            // Make sure the cursor is pointing in the right place in the byte buffer
            flip()
        }, 0,
        vertices.size * kPositionSize
    )

    // Create tangents Buffer
    if (vertices.hasNormals) {
        bufferIndex++
        setBufferAt(
            engine, bufferIndex,
            FloatBuffer.allocate(vertices.size * kTangentSize).apply {
                vertices.forEach { vertex ->
                    val normal = vertex.normal ?: error(
                        "Geometry attribute 'normal' missing on a vertex while other vertices " +
                            "declare one — every vertex must declare a normal or none should " +
                            "(partial vertex declarations are not supported)."
                    )
                    put(normalToTangent(normal).toFloatArray())
                }
                flip()
            }, 0,
            vertices.size * kTangentSize
        )
    }

    // Create UV Buffer
    if (vertices.hasUvCoordinates) {
        bufferIndex++
        setBufferAt(
            engine, bufferIndex,
            FloatBuffer.allocate(vertices.size * kUVSize).apply {
                vertices.forEach { vertex ->
                    val uvCoordinate = vertex.uvCoordinate ?: error(
                        "Geometry attribute 'uvCoordinate' missing on a vertex while other " +
                            "vertices declare one — every vertex must declare a uvCoordinate or " +
                            "none should (partial vertex declarations are not supported)."
                    )
                    put(uvCoordinate.toFloatArray())
                }
                rewind()
            }, 0,
            vertices.size * kUVSize
        )
    }

    // Create color Buffer
    if (vertices.hasColors) {
        bufferIndex++
        setBufferAt(
            engine, bufferIndex,
            FloatBuffer.allocate(vertices.size * kColorSize).apply {
                vertices.forEach { vertex ->
                    val color = vertex.color ?: error(
                        "Geometry attribute 'color' missing on a vertex while other vertices " +
                            "declare one — every vertex must declare a color or none should " +
                            "(partial vertex declarations are not supported)."
                    )
                    put(color.toFloatArray())
                }
                rewind()
            }, 0,
            vertices.size * kColorSize
        )
    }

    // Calculate the Aabb in one pass through the vertices.
    var minPosition = Position(vertices.first().position)
    var maxPosition = Position(vertices.first().position)
    vertices.forEach { vertex ->
        minPosition = min(minPosition, vertex.position)
        maxPosition = max(maxPosition, vertex.position)
    }

    val halfExtent = (maxPosition - minPosition) / 2.0f
    val center = minPosition + halfExtent
    return Box(center, halfExtent)
}

fun IndexBuffer.setIndices(
    engine: Engine,
    indices: List<Int>
) {
    // Fill the index buffer with the data
    setBuffer(engine,
        IntBuffer.allocate(indices.size).apply {
            indices.forEach { put(it) }
            flip()
        })
}


fun List<List<Int>>.getOffsets(): List<IntRange> {
    var indexStart = 0
    return map { primitiveIndices ->
        (indexStart until indexStart + primitiveIndices.size).also {
            indexStart += primitiveIndices.size
        }
    }
}

/**
 * Specifies the geometry data for a primitive.
 *
 * Filament primitives must have an associated [VertexBuffer] and [IndexBuffer].
 * Typically, each primitive is specified with a pair of daisy-chained calls:
 * [geometry] and [RenderableManager.Builder.material].
 * @see Geometry
 * @see Plane
 * @see Cube
 * @see Sphere
 * @see Cylinder
 * @see RenderableManager.setGeometry
 */
fun RenderableManager.Builder.geometry(
    geometry: Geometry,
    offsets: List<IntRange> = geometry.primitivesOffsets
) = apply {
    offsets.forEachIndexed { primitiveIndex, offset ->
        geometry(
            primitiveIndex,
            geometry.primitiveType,
            geometry.vertexBuffer,
            geometry.indexBuffer,
            offset.first,
            offset.count()
        )
    }
    // Overall bounding box of the renderable
    boundingBox(geometry.boundingBox)
}

/**
 * Changes the geometry for the given renderable instance.
 *
 * @see Geometry
 * @see Plane
 * @see Cube
 * @see Sphere
 * @see Cylinder
 * @see RenderableManager.Builder.geometry
 */
fun RenderableManager.setGeometry(
    instance: EntityInstance,
    geometry: Geometry,
    offsets: List<IntRange> = geometry.primitivesOffsets
) {
    offsets.forEachIndexed { primitiveIndex, offset ->
        setGeometryAt(
            instance,
            primitiveIndex,
            geometry.primitiveType,
            geometry.vertexBuffer,
            geometry.indexBuffer,
            offset.first,
            offset.count()
        )
    }
    // Overall bounding box of the renderable
    setAxisAlignedBoundingBox(instance, geometry.boundingBox)
}
