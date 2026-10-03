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
import io.github.sceneview.safeDestroyIndexBuffer
import io.github.sceneview.safeDestroyVertexBuffer
import io.github.sceneview.math.Box
import io.github.sceneview.math.Color
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Position
import io.github.sceneview.math.normalToTangent
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
    vertexBuffer: VertexBuffer,
    primitivesIndices: List<List<Int>>,
    indexBuffer: IndexBuffer,
    var primitivesOffsets: List<IntRange>,
    var boundingBox: Box
) {
    var vertexBuffer: VertexBuffer = vertexBuffer
        private set
    var indexBuffer: IndexBuffer = indexBuffer
        private set

    internal interface Consumer {
        fun validate(primitiveCount: Int)
        fun rebind()
    }

    private val lifetime = GeometryBufferLifetime<Pair<VertexBuffer, IndexBuffer>>()

    internal fun attach(consumer: Consumer) = lifetime.attach(consumer)

    internal fun detach(consumer: Consumer) = lifetime.detach(consumer)

    /**
     * Releases the buffers, immediately, unless a node is still bound to this geometry — in which
     * case this is a no-op and the last node to go releases them. Main-thread only.
     *
     * Nodes are the only references counted. A raw renderable that was lent [vertexBuffer] and
     * [indexBuffer] (a `MeshNode`) is not: destroy it before, or in the same pass as, this call.
     */
    internal fun destroy(engine: Engine) {
        lifetime.destroy(vertexBuffer to indexBuffer) { engine.release(it) }
    }

    private fun rebindConsumers(engine: Engine) = lifetime.rebind { engine.release(it) }

    // Immediate, not frame-deferred: by the time a pair gets here every bound renderable has been
    // destroyed or re-pointed at other buffers, and Filament runs driver commands in order.
    private fun Engine.release(buffers: Pair<VertexBuffer, IndexBuffer>) {
        safeDestroyVertexBuffer(buffers.first)
        safeDestroyIndexBuffer(buffers.second)
    }

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
            // Filament aborts on a zero-sized VertexBuffer; fail before allocating anything.
            require(vertices.isNotEmpty()) { "Geometry requires at least one vertex" }
            val vertexBuffer = vertexBuilder.build(engine)
            var indexBuffer: IndexBuffer? = null
            try {
                val boundingBox = vertexBuffer.setVertices(engine, vertices)
                val builtIndices = indexBuilder.build(engine)
                indexBuffer = builtIndices
                builtIndices.setIndices(engine, indices.flatten())
                return constructor(vertexBuffer, builtIndices, indices.getOffsets(), boundingBox)
            } catch (failure: Throwable) {
                // These buffers have not been handed to any renderable yet.
                engine.safeDestroyVertexBuffer(vertexBuffer)
                indexBuffer?.let { engine.safeDestroyIndexBuffer(it) }
                throw failure
            }
        }

        open fun build(engine: Engine) =
            build(engine) { vertexBuffer, indexBuffer, offsets, boundingBox ->
                Geometry(
                    primitiveType, vertices, vertexBuffer, indices, indexBuffer,
                    offsets, boundingBox
                )
            }
    }

    var vertices: List<Vertex> = vertices.toList()
        private set

    var primitivesIndices: List<List<Int>> = primitivesIndices.map { it.toList() }
        private set

    val indices: List<Int>
        get() = primitivesIndices.flatten()

    fun setVertices(engine: Engine, vertices: List<Vertex>) {
        update(engine, vertices = vertices)
    }

    fun setPrimitivesIndices(engine: Engine, primitivesIndices: List<List<Int>>) {
        update(engine, primitivesIndices = primitivesIndices)
    }

    /**
     * Updates this geometry on the main thread. Equal vertex counts, per-primitive index counts
     * and attribute layouts reuse the buffers; any change to these rebuilds both buffers.
     * All nodes bound through GeometryNode or RenderableNode.setGeometry are synchronously
     * rebound (including bounds and primitive ranges), and the replaced buffers are destroyed
     * right after — no node draws from them any more. A shared geometry stays alive until its
     * last node dies.
     *
     * [vertexBuffer] and [indexBuffer] may be lent to a raw renderable (a `MeshNode`) as long as
     * every update keeps the counts and the attribute layout: the buffers are then never
     * replaced. A raw renderable is not rebound by a rebuild and would be left drawing from
     * destroyed buffers — bind a `GeometryNode` instead when the topology can change.
     *
     * @throws IllegalArgumentException if [vertices] is empty.
     * @throws IllegalStateException if this geometry has been destroyed.
     */
    fun update(
        engine: Engine,
        vertices: List<Vertex> = this.vertices,
        primitivesIndices: List<List<Int>> = this.primitivesIndices
    ) = apply {
        check(!lifetime.isDestroyed) { "Geometry has been destroyed" }
        // Filament aborts on a zero-sized VertexBuffer; fail before allocating anything.
        require(vertices.isNotEmpty()) { "Geometry requires at least one vertex" }
        // Identity first: the default arguments are this geometry's own lists.
        val verticesChanged = this.vertices !== vertices && this.vertices != vertices
        val indicesChanged = this.primitivesIndices !== primitivesIndices &&
            this.primitivesIndices != primitivesIndices
        if (!verticesChanged && !indicesChanged) {
            // A consumer that failed its last rebind still holds the retired buffers: retry.
            if (lifetime.retiredCount > 0) rebindConsumers(engine)
            return@apply
        }
        lifetime.validate(primitivesIndices.size)
        if (requiresBufferRebuild(this.vertices, this.primitivesIndices, vertices, primitivesIndices)) {
            val replacement = Builder(primitiveType).vertices(vertices)
                .primitivesIndices(primitivesIndices).build(engine)
            // Retired, not destroyed: consumers reference them until rebindConsumers succeeds.
            lifetime.retire(vertexBuffer to indexBuffer)
            vertexBuffer = replacement.vertexBuffer
            indexBuffer = replacement.indexBuffer
            boundingBox = replacement.boundingBox
            primitivesOffsets = primitivesIndices.getOffsets()
        } else {
            // Same counts, same layout: rewrite in place. The offsets cannot have moved.
            if (verticesChanged) boundingBox = vertexBuffer.setVertices(engine, vertices)
            if (indicesChanged) indexBuffer.setIndices(engine, primitivesIndices.flatten())
        }
        // Copy only what changed, so a list the caller mutates later still compares as different.
        if (verticesChanged) this.vertices = vertices.toList()
        if (indicesChanged) this.primitivesIndices = primitivesIndices.map { it.toList() }
        rebindConsumers(engine)
    }
}

val List<Geometry.Vertex>.hasNormals get() = any { it.normal != null }
val List<Geometry.Vertex>.hasUvCoordinates get() = any { it.uvCoordinate != null }
val List<Geometry.Vertex>.hasColors get() = any { it.color != null }

/** Pure allocation decision; index counts are compared per primitive, not just in total. */
internal fun requiresBufferRebuild(
    oldVertices: List<Geometry.Vertex>,
    oldIndices: List<List<Int>>,
    newVertices: List<Geometry.Vertex>,
    newIndices: List<List<Int>>
): Boolean = oldVertices.size != newVertices.size ||
    oldIndices.map { it.size } != newIndices.map { it.size } ||
    oldVertices.hasNormals != newVertices.hasNormals ||
    oldVertices.hasUvCoordinates != newVertices.hasUvCoordinates ||
    oldVertices.hasColors != newVertices.hasColors

/**
 * A fresh **direct** float buffer of [count] floats, filled by [fill] and left ready to read.
 *
 * Filament's `setBufferAt` keeps a JNI global reference to the [FloatBuffer] it is handed until
 * the driver's async copy completes on the render thread. For a heap-backed buffer (plain
 * `FloatBuffer.allocate`) that reference comes paired with a *second* one to the buffer's backing
 * `float[]`, because the JNI implementation has to pin the array to read it — twice the live
 * global refs per upload. A direct buffer is backed by native memory instead of a Java array, so
 * only the buffer itself needs pinning.
 *
 * On geometry that re-uploads every frame — an animated [Tube] such as the marching dashes and
 * moving trail in the Lines & Paths demo — that difference is the one between a few hundred live
 * global refs and ART's 51 200-entry cap, which is what
 * [#3715](https://github.com/sceneview/sceneview/issues/3715) hit on a render thread lagging
 * behind a loaded GPU.
 *
 * Always allocate fresh rather than reusing one buffer across uploads: the copy is asynchronous,
 * so a reused buffer could be overwritten by the next frame's `put` while Filament is still
 * reading the previous one — its own class of corruption, already ruled out for `DepthMeshNode`
 * in #1841 (`arsceneview`'s equivalent per-frame upload path). A short-lived direct allocation
 * trades a bit of native-heap churn for correctness; GC reclaims it once Filament's copy is done
 * (or sooner, once the JNI global ref is released).
 */
// internal (rather than private) so GeometryDirectBufferUploadTest can pin this contract directly
// — the Filament Engine can't run on the JVM, but this helper has no Filament dependency at all.
internal fun directFloatBuffer(count: Int, fill: FloatBuffer.() -> Unit): FloatBuffer =
    ByteBuffer.allocateDirect(count * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply(fill)

/** [directFloatBuffer]'s counterpart for index buffers — see there for why direct matters. */
internal fun directIntBuffer(count: Int, fill: IntBuffer.() -> Unit): IntBuffer =
    ByteBuffer.allocateDirect(count * Int.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
        .asIntBuffer()
        .apply(fill)

fun VertexBuffer.setVertices(engine: Engine, vertices: List<Geometry.Vertex>): Box {
    var bufferIndex = 0

    // Create position Buffer
    setBufferAt(
        engine, bufferIndex,
        directFloatBuffer(vertices.size * kPositionSize) {
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
            directFloatBuffer(vertices.size * kTangentSize) {
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
            directFloatBuffer(vertices.size * kUVSize) {
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
            directFloatBuffer(vertices.size * kColorSize) {
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
    // Fill the index buffer with the data. Direct for the same reason as directFloatBuffer above
    // — see there.
    setBuffer(
        engine,
        directIntBuffer(indices.size) {
            indices.forEach { put(it) }
            flip()
        }
    )
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
 * The Filament primitive → source-index-range mapping a geometry resize should apply, given the
 * number of Filament primitives the renderable was actually built with ([builtPrimitiveCount]).
 *
 * [io.github.sceneview.components.RenderableComponent.setGeometry]'s single-[Geometry] overload
 * defaults to `this` (the new geometry's own raw, un-merged [Geometry.primitivesOffsets])
 * unconditionally — correct for a renderable built 1:1 with them, but wrong for one built by
 * *merging* every raw primitive into fewer Filament primitive slots, which is what every
 * procedural shape node's `materialInstance: MaterialInstance?` constructor does (`CubeNode`,
 * `SphereNode`, `CylinderNode`, `ConeNode`, `TorusNode`, `CapsuleNode`, `PlaneNode`) so a single
 * [com.google.android.filament.MaterialInstance] covers the whole shape. Falling back to the raw
 * offsets there walks past the single slot Filament actually has: `setGeometryAt` only ever
 * reaches primitive `0`, so only the first — and usually smallest — raw primitive lands and the
 * rest draw nothing (#3855, e.g. a resized `CylinderNode(materialInstance = …)` rendering as a
 * single triangle instead of the whole cylinder).
 */
internal fun List<IntRange>.mergedForPrimitiveCount(builtPrimitiveCount: Int): List<IntRange> =
    if (builtPrimitiveCount == 1 && size > 1) {
        listOf(first().first..last().last)
    } else {
        this
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
