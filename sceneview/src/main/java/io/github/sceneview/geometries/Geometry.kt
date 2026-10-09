package io.github.sceneview.geometries

import android.os.Handler
import android.os.Looper
import com.google.android.filament.Box
import com.google.android.filament.Engine
import com.google.android.filament.IndexBuffer
import com.google.android.filament.RenderableManager
import com.google.android.filament.RenderableManager.PrimitiveType
import com.google.android.filament.VertexBuffer
import dev.romainguy.kotlin.math.Float2
import dev.romainguy.kotlin.math.max
import dev.romainguy.kotlin.math.min
import io.github.sceneview.EngineRenderInvalidators
import io.github.sceneview.EntityInstance
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

    /**
     * Back-pressure on the in-place uploads below: one in flight, the rest collapsed into the
     * latest — see [LatestWinsUploadGate] for why a geometry cannot simply hand Filament every
     * update it is given (#4365).
     */
    private val uploads = LatestWinsUploadGate<GeometryUpload>(
        merge = { pending, next -> pending.mergedWith(next) },
        canUpload = { it.targets(vertexBuffer, indexBuffer) },
        upload = { upload, onReleased -> upload.issue(vertexBuffer, indexBuffer, onReleased) },
        // This upload left from a Filament callback, not from a node call, so no node asked for
        // a frame: without this a render-on-demand view would keep showing the superseded state.
        onDeferredUpload = { EngineRenderInvalidators.requestRender(it.engine) },
    )

    /**
     * `true` once Filament holds the vertices and indices last set on this geometry and has let
     * go of the buffers they were uploaded from.
     */
    internal val isUploadSettled: Boolean
        get() = uploads.isIdle

    /**
     * Replaces the vertices. Same count and attributes as the geometry was built with.
     *
     * [vertices] and [boundingBox] change immediately. The upload itself is handed to Filament
     * right away when the previous one has been consumed; otherwise it waits for it, and only the
     * most recent call is kept — see [update].
     */
    fun setVertices(engine: Engine, vertices: List<Vertex>) {
        applyVertices(vertices)
        uploads.submit(GeometryUpload(engine, vertices = vertices))
    }

    /** Replaces the indices. Uploaded under the same policy as [setVertices]. */
    fun setPrimitivesIndices(engine: Engine, primitivesIndices: List<List<Int>>) {
        applyPrimitivesIndices(primitivesIndices)
        uploads.submit(GeometryUpload(engine, primitivesIndices = primitivesIndices))
    }

    /**
     * Updates the vertices and / or the indices in place. Anything equal to the current value is
     * skipped.
     *
     * Safe to call at any rate, including faster than frames are presented — an animation driven
     * from Compose's frame clock while the view has no surface yet, or while the GPU is behind.
     * Filament keeps every buffer it is handed pinned until its backend thread has consumed it,
     * so a geometry holds **one** upload in flight and remembers only the latest state set while
     * it waits. That state is uploaded as soon as the previous upload is released; the ones in
     * between, which no frame could have shown, are dropped. Vertices and indices set by one call
     * are uploaded together.
     */
    fun update(
        engine: Engine,
        vertices: List<Vertex> = this.vertices,
        primitivesIndices: List<List<Int>> = this.primitivesIndices
    ) = apply {
        val verticesChanged = this.vertices != vertices
        val indicesChanged = this.primitivesIndices != primitivesIndices
        if (verticesChanged) applyVertices(vertices)
        if (indicesChanged) applyPrimitivesIndices(primitivesIndices)
        if (verticesChanged || indicesChanged) {
            uploads.submit(
                GeometryUpload(
                    engine,
                    vertices = vertices.takeIf { verticesChanged },
                    primitivesIndices = primitivesIndices.takeIf { indicesChanged },
                )
            )
        }
    }

    private fun applyVertices(vertices: List<Vertex>) {
        // Checked here, at the call site, because the upload may not happen inside this call.
        vertices.requireUniformAttributes()
        boundingBox = vertices.boundingBox()
        this.vertices = vertices
    }

    private fun applyPrimitivesIndices(primitivesIndices: List<List<Int>>) {
        this.primitivesIndices = primitivesIndices
        primitivesOffsets = primitivesIndices.getOffsets()
    }
}

/**
 * One in-place upload of a [Geometry]: the streams that changed, `null` for the ones that did not.
 */
private class GeometryUpload(
    val engine: Engine,
    val vertices: List<Geometry.Vertex>? = null,
    val primitivesIndices: List<List<Int>>? = null,
) {
    /** The newest of each stream: a vertex or index list fully replaces the one before it. */
    fun mergedWith(next: GeometryUpload) = GeometryUpload(
        engine = next.engine,
        vertices = next.vertices ?: vertices,
        primitivesIndices = next.primitivesIndices ?: primitivesIndices,
    )

    /**
     * Whether the buffers are still there to upload into. Asked from a Filament callback, some
     * time after the update was requested: the engine or the geometry may be gone by then.
     */
    fun targets(vertexBuffer: VertexBuffer, indexBuffer: IndexBuffer): Boolean = runCatching {
        engine.isValid &&
            (vertices == null || engine.isValidVertexBuffer(vertexBuffer)) &&
            (primitivesIndices == null || engine.isValidIndexBuffer(indexBuffer))
    }.getOrDefault(false)

    /**
     * Hands every changed stream to Filament and calls [onReleased] once Filament has released
     * all of them.
     */
    fun issue(vertexBuffer: VertexBuffer, indexBuffer: IndexBuffer, onReleased: () -> Unit) {
        val streams =
            (vertices?.attributeStreamCount ?: 0) + (if (primitivesIndices != null) 1 else 0)
        val countdown = ReleaseCountdown(streams, onReleased)
        val released = Runnable { countdown.release() }
        val handler = uploadThreadHandler()
        vertices?.let { vertexBuffer.uploadVertices(engine, it, handler, released) }
        primitivesIndices?.let { primitivesIndices ->
            indexBuffer.uploadIndices(
                engine, primitivesIndices.flatMap { it.indices }, handler, released
            )
        }
    }
}

private val mainThreadHandler by lazy { Handler(Looper.getMainLooper()) }

/**
 * Where Filament reports a released buffer: the thread the upload was issued from, which is the
 * thread that owns the engine and the only one allowed to issue the next upload. Falls back to
 * the main thread for a caller without a [Looper].
 */
private fun uploadThreadHandler(): Handler {
    val looper = Looper.myLooper() ?: return mainThreadHandler
    return if (looper === Looper.getMainLooper()) mainThreadHandler else Handler(looper)
}

val List<Geometry.Vertex>.hasNormals get() = any { it.normal != null }
val List<Geometry.Vertex>.hasUvCoordinates get() = any { it.uvCoordinate != null }
val List<Geometry.Vertex>.hasColors get() = any { it.color != null }

/** How many `setBufferAt` calls these vertices take: position, plus each optional stream. */
private val List<Geometry.Vertex>.attributeStreamCount: Int
    get() = 1 +
        (if (hasNormals) 1 else 0) +
        (if (hasUvCoordinates) 1 else 0) +
        (if (hasColors) 1 else 0)

private fun missingAttribute(name: String): Nothing = error(
    "Geometry attribute '$name' missing on a vertex while other vertices declare one — every " +
        "vertex must declare a $name or none should (partial vertex declarations are not " +
        "supported)."
)

/** Throws what [uploadVertices] would, without building a buffer. */
private fun List<Geometry.Vertex>.requireUniformAttributes() {
    if (hasNormals && any { it.normal == null }) missingAttribute("normal")
    if (hasUvCoordinates && any { it.uvCoordinate == null }) missingAttribute("uvCoordinate")
    if (hasColors && any { it.color == null }) missingAttribute("color")
}

/** The axis-aligned bounding box of the positions, in one pass. */
private fun List<Geometry.Vertex>.boundingBox(): Box {
    var minPosition = Position(first().position)
    var maxPosition = Position(first().position)
    forEach { vertex ->
        minPosition = min(minPosition, vertex.position)
        maxPosition = max(maxPosition, vertex.position)
    }

    val halfExtent = (maxPosition - minPosition) / 2.0f
    val center = minPosition + halfExtent
    return Box(center, halfExtent)
}

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

/**
 * Uploads [vertices] into this buffer — one `setBufferAt` per attribute stream — and returns their
 * bounding box.
 *
 * Every call pins its buffers until Filament's backend thread has consumed them, so calling this
 * faster than frames are presented piles pins up without bound (#4365). [Geometry.update] is the
 * rate-safe way to animate a geometry.
 */
fun VertexBuffer.setVertices(engine: Engine, vertices: List<Geometry.Vertex>): Box {
    uploadVertices(engine, vertices, handler = null, onStreamReleased = null)
    return vertices.boundingBox()
}

/**
 * The upload behind [setVertices]. [onStreamReleased] runs on [handler] — an Android [Handler] or
 * a `java.util.concurrent.Executor`, as Filament takes them — once per attribute stream, when
 * Filament has let go of that stream's buffer.
 */
private fun VertexBuffer.uploadVertices(
    engine: Engine,
    vertices: List<Geometry.Vertex>,
    handler: Any?,
    onStreamReleased: Runnable?,
) {
    var bufferIndex = 0

    // Create position Buffer
    setBufferAt(
        engine, bufferIndex,
        directFloatBuffer(vertices.size * kPositionSize) {
            vertices.forEach { put(it.position.toFloatArray()) }
            // Make sure the cursor is pointing in the right place in the byte buffer
            flip()
        }, 0,
        vertices.size * kPositionSize, handler, onStreamReleased
    )

    // Create tangents Buffer
    if (vertices.hasNormals) {
        bufferIndex++
        setBufferAt(
            engine, bufferIndex,
            directFloatBuffer(vertices.size * kTangentSize) {
                vertices.forEach { vertex ->
                    val normal = vertex.normal ?: missingAttribute("normal")
                    put(normalToTangent(normal).toFloatArray())
                }
                flip()
            }, 0,
            vertices.size * kTangentSize, handler, onStreamReleased
        )
    }

    // Create UV Buffer
    if (vertices.hasUvCoordinates) {
        bufferIndex++
        setBufferAt(
            engine, bufferIndex,
            directFloatBuffer(vertices.size * kUVSize) {
                vertices.forEach { vertex ->
                    val uvCoordinate = vertex.uvCoordinate ?: missingAttribute("uvCoordinate")
                    put(uvCoordinate.toFloatArray())
                }
                rewind()
            }, 0,
            vertices.size * kUVSize, handler, onStreamReleased
        )
    }

    // Create color Buffer
    if (vertices.hasColors) {
        bufferIndex++
        setBufferAt(
            engine, bufferIndex,
            directFloatBuffer(vertices.size * kColorSize) {
                vertices.forEach { vertex ->
                    val color = vertex.color ?: missingAttribute("color")
                    put(color.toFloatArray())
                }
                rewind()
            }, 0,
            vertices.size * kColorSize, handler, onStreamReleased
        )
    }
}

fun IndexBuffer.setIndices(
    engine: Engine,
    indices: List<Int>
) {
    uploadIndices(engine, indices, handler = null, onReleased = null)
}

/** The upload behind [setIndices]; [handler] and [onReleased] as in [uploadVertices]. */
private fun IndexBuffer.uploadIndices(
    engine: Engine,
    indices: List<Int>,
    handler: Any?,
    onReleased: Runnable?,
) {
    // Fill the index buffer with the data. Direct for the same reason as directFloatBuffer above
    // — see there.
    setBuffer(
        engine,
        directIntBuffer(indices.size) {
            indices.forEach { put(it) }
            flip()
        },
        0, indices.size, handler, onReleased
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
