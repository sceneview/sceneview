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
import java.util.concurrent.Executor

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
        val destroyed = lifetime.destroy(vertexBuffer to indexBuffer) { engine.release(it) }
        // What was waiting described buffers that are gone, and would rebuild them.
        if (destroyed) uploads.discardPending()
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
            val (vertexBuffer, indexBuffer) = buildBuffers(engine, handler = null, released = null)
            return try {
                constructor(vertexBuffer, indexBuffer, indices.getOffsets(), vertices.boundingBox())
            } catch (failure: Throwable) {
                // These buffers have not been handed to any renderable yet.
                engine.safeDestroyVertexBuffer(vertexBuffer)
                engine.safeDestroyIndexBuffer(indexBuffer)
                throw failure
            }
        }

        /**
         * Builds both buffers and uploads the vertices and indices set on this builder into
         * them. [released] runs on [handler] once per buffer Filament has let go of — see
         * [GeometryBufferLayout.streamCount] — or never when both are `null`.
         */
        internal fun buildBuffers(
            engine: Engine,
            handler: Any?,
            released: Runnable?
        ): Pair<VertexBuffer, IndexBuffer> {
            val vertexBuffer = vertexBuilder.build(engine)
            var indexBuffer: IndexBuffer? = null
            try {
                vertexBuffer.uploadVertices(engine, vertices, handler, released)
                val builtIndices = indexBuilder.build(engine)
                indexBuffer = builtIndices
                builtIndices.uploadIndices(engine, indices.flatten(), handler, released)
                return vertexBuffer to builtIndices
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

    /** What [vertexBuffer] and [indexBuffer] were sized for; a state that differs rebuilds them. */
    private var bufferLayout = GeometryBufferLayout.of(this.vertices, this.primitivesIndices)

    /** Counts [rebuildBuffers] calls, so [update] knows one ran inside its own submission. */
    private var rebuildCount = 0

    /**
     * Back-pressure on the uploads below, in place and rebuild alike: one in flight, the rest
     * collapsed into the latest — see [LatestWinsUploadGate] for why a geometry cannot simply
     * hand Filament every update it is given (#4365).
     */
    private val uploads = LatestWinsUploadGate<GeometryUpload>(
        merge = { pending, next -> pending.mergedWith(next) },
        // `this.`: in an initializer the bare names are the constructor parameters — the buffers
        // this geometry was built with, destroyed by its first rebuild.
        canUpload = { !lifetime.isDestroyed && it.targets(this.vertexBuffer, this.indexBuffer) },
        upload = { upload, onReleased -> issueUpload(upload, onReleased) },
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
     * Replaces the vertices: `update(engine, vertices = vertices)`. Any count, any attributes —
     * see [update] for what is rebuilt, and for what changes during the call and what waits for
     * the previous upload.
     *
     * To change vertices **and** indices, call [update] with both rather than this and
     * [setPrimitivesIndices] in turn: two calls are two updates, and a frame can be drawn between
     * them with the new vertices on the old indices.
     */
    fun setVertices(engine: Engine, vertices: List<Vertex>) {
        update(engine, vertices = vertices)
    }

    /**
     * Replaces the indices: `update(engine, primitivesIndices = primitivesIndices)`. Like
     * [setVertices] it is an update of its own: use [update] to change vertices and indices in
     * the same frame.
     */
    fun setPrimitivesIndices(engine: Engine, primitivesIndices: List<List<Int>>) {
        update(engine, primitivesIndices = primitivesIndices)
    }

    /**
     * Updates the vertices and / or the indices. Anything equal to the current value is skipped.
     *
     * **Any list fits.** The same vertex count, the same index count per primitive and the same
     * attributes (normals, UVs, colours) are written into the existing buffers. Anything else —
     * a sphere given more slices, a path that gains a point, a shape that gains colours —
     * rebuilds both buffers, re-points every node bound to this geometry (`GeometryNode`, or any
     * `RenderableNode.setGeometry`) at them, primitive ranges, bounding box and collider
     * included, and destroys the replaced buffers right after: no node draws from them any more.
     *
     * **Safe to call at any rate**, including faster than frames are presented — an animation
     * driven from Compose's frame clock while the view has no surface yet, or while the GPU is
     * behind. Filament keeps every buffer it is handed pinned until its backend thread has
     * consumed it, so a geometry holds **one** upload in flight — in place or rebuild alike — and
     * remembers only the latest state set while it waits. That state is uploaded as soon as the
     * previous upload is released; the ones set in between are dropped without ever reaching
     * Filament.
     *
     * What changes when:
     * - [vertices] and [primitivesIndices] always change during the call.
     * - An update that fits the buffers also changes [boundingBox] and [primitivesOffsets]
     *   during the call, whether or not its upload has to wait.
     * - An update that needs new buffers and has to wait changes none of [vertexBuffer],
     *   [indexBuffer], [primitivesOffsets] and [boundingBox] yet: the four keep describing the
     *   buffers the nodes are drawing from, and switch together, with the nodes, when the
     *   rebuild goes out. With nothing in flight that is during the call.
     *
     * What a frame can show, as a result:
     * - Vertices and indices passed to **one** call are uploaded together, so no frame mixes one
     *   call's vertices with another's indices. That does not hold for [setVertices] followed by
     *   [setPrimitivesIndices], which are two updates.
     * - Two calls made before a frame is drawn are no longer guaranteed to land in that same
     *   frame: the first is already with Filament, so the frame may show it, and the latest state
     *   follows on the next one. The last state set is always the one that ends up on screen.
     *
     * The lists are read during the call: a `MutableList` can be refilled and passed again
     * afterwards, and is compared against the copy taken here.
     *
     * [vertexBuffer] and [indexBuffer] may be lent to a raw renderable (a `MeshNode`) as long as
     * every update keeps the counts and the attributes: the buffers are then never replaced. A
     * raw renderable is not re-pointed by a rebuild and would be left drawing from destroyed
     * buffers — bind a `GeometryNode` instead when the topology can change.
     *
     * Main-thread only.
     *
     * @throws IllegalArgumentException if [vertices] is empty.
     * @throws IllegalStateException if this geometry has been destroyed, or if some vertices
     * declare a normal, UV or colour and others do not.
     */
    fun update(
        engine: Engine,
        vertices: List<Vertex> = this.vertices,
        primitivesIndices: List<List<Int>> = this.primitivesIndices
    ) = apply {
        check(!lifetime.isDestroyed) { "Geometry has been destroyed" }
        // Filament aborts on a zero-sized VertexBuffer; fail before anything changes.
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
        // Checked here, at the call site, because the upload may not happen inside this call.
        if (verticesChanged) vertices.requireUniformAttributes()
        lifetime.validate(primitivesIndices.size)

        // Only what changed, so a list the caller refills later still compares as different. The
        // snapshot is both this geometry's state and what the upload carries.
        val streams = GeometryStreams.snapshotOf(
            vertices = vertices.takeIf { verticesChanged },
            primitivesIndices = primitivesIndices.takeIf { indicesChanged },
        )
        streams.vertices?.let { this.vertices = it }
        streams.primitivesIndices?.let { this.primitivesIndices = it }

        val fitsBuffers =
            GeometryBufferLayout.of(this.vertices, this.primitivesIndices) == bufferLayout
        // Fits: these describe the buffers already bound, so they follow now even if the upload
        // waits. Does not fit: they wait for the buffers they describe — see [rebuildBuffers].
        if (fitsBuffers) describeBuffers()
        val rebuildsBefore = rebuildCount
        uploads.submit(GeometryUpload(engine, streams))
        // A rebuild that left during the call has re-pointed the nodes itself; one that waits
        // has nothing to show them yet.
        if (fitsBuffers && rebuildCount == rebuildsBefore) rebindConsumers(engine)
    }

    /** [boundingBox] and [primitivesOffsets], from the state the bound buffers are sized for. */
    private fun describeBuffers() {
        boundingBox = vertices.boundingBox()
        primitivesOffsets = primitivesIndices.getOffsets()
    }

    /**
     * Hands the latest state to Filament — called by the gate, during [update] when nothing is in
     * flight, otherwise from the release of the upload that was.
     *
     * Decided here rather than in [update]: between the two the state may have changed size and
     * come back, and what matters is whether it fits the buffers as they are now.
     */
    private fun issueUpload(upload: GeometryUpload, onReleased: () -> Unit) {
        val layout = GeometryBufferLayout.of(vertices, primitivesIndices)
        if (layout == bufferLayout) {
            upload.issue(vertexBuffer, indexBuffer, onReleased)
        } else {
            rebuildBuffers(upload.engine, layout, onReleased)
        }
    }

    /**
     * Replaces both buffers with ones sized for the current state, and re-points every node.
     *
     * Gated like an in-place upload: a geometry that changes size on every update while Filament
     * consumes nothing would otherwise pin a new set of buffers per call (#4365).
     */
    private fun rebuildBuffers(
        engine: Engine,
        layout: GeometryBufferLayout,
        onReleased: () -> Unit
    ) {
        val countdown = ReleaseCountdown(layout.streamCount, onReleased)
        val released = Runnable { countdown.release() }
        val (newVertexBuffer, newIndexBuffer) = Builder(primitiveType)
            .vertices(vertices)
            .primitivesIndices(primitivesIndices)
            .buildBuffers(engine, releaseThreadHandler(), released)
        // Retired, not destroyed: consumers reference them until rebindConsumers succeeds.
        lifetime.retire(vertexBuffer to indexBuffer)
        vertexBuffer = newVertexBuffer
        indexBuffer = newIndexBuffer
        bufferLayout = layout
        rebuildCount++
        describeBuffers()
        rebindConsumers(engine)
    }
}

/**
 * The data of one [Geometry] update: the streams that changed, `null` for the ones that did not.
 * An update that fits the buffers uploads exactly these; one that does not rebuilds the buffers
 * from the geometry's whole state.
 *
 * Only built through [snapshotOf]: an upload can wait for the previous one to be released, long
 * after the call that asked for it returned, and must still carry what that call was given.
 *
 * Free of any Filament type so that the snapshot and the merge are tested on the JVM.
 */
internal class GeometryStreams private constructor(
    val vertices: List<Geometry.Vertex>?,
    val primitivesIndices: List<List<Int>>?,
) {
    /**
     * The index values of every primitive, end to end — what the index buffer holds. The values,
     * not `0 until n`: `flatMap { it.indices }` reads the same and uploads the latter.
     */
    val flatIndices: List<Int>? get() = primitivesIndices?.flatten()

    /** The newest of each stream: a vertex or index list fully replaces the one before it. */
    fun mergedWith(next: GeometryStreams) = GeometryStreams(
        vertices = next.vertices ?: vertices,
        primitivesIndices = next.primitivesIndices ?: primitivesIndices,
    )

    companion object {
        /**
         * Copies the lists, so that a caller refilling its own `MutableList` afterwards does not
         * rewrite an upload that has not left yet. The index lists are copied one level down for
         * the same reason. The vertices themselves are shared, not cloned: a [Geometry.Vertex] is
         * replaced, not edited, by every geometry builder.
         */
        fun snapshotOf(
            vertices: List<Geometry.Vertex>? = null,
            primitivesIndices: List<List<Int>>? = null,
        ) = GeometryStreams(
            vertices = vertices?.toList(),
            primitivesIndices = primitivesIndices?.map { it.toList() },
        )
    }
}

/** One update of a [Geometry]: its [streams], and the engine to hand them to. */
private class GeometryUpload(val engine: Engine, val streams: GeometryStreams) {
    private val vertices get() = streams.vertices
    private val primitivesIndices get() = streams.primitivesIndices

    fun mergedWith(next: GeometryUpload) =
        GeometryUpload(next.engine, streams.mergedWith(next.streams))

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
     * Writes every changed stream into the existing buffers and calls [onReleased] once Filament
     * has released all of them.
     */
    fun issue(vertexBuffer: VertexBuffer, indexBuffer: IndexBuffer, onReleased: () -> Unit) {
        val streamCount =
            (vertices?.attributeStreamCount ?: 0) + (if (primitivesIndices != null) 1 else 0)
        val countdown = ReleaseCountdown(streamCount, onReleased)
        val released = Runnable { countdown.release() }
        val handler = releaseThreadHandler()
        vertices?.let { vertexBuffer.uploadVertices(engine, it, handler, released) }
        if (primitivesIndices != null) {
            indexBuffer.uploadIndices(engine, streams.flatIndices.orEmpty(), handler, released)
        }
    }
}

private val mainThreadHandler by lazy { Handler(Looper.getMainLooper()) }

/**
 * Where Filament reports a released buffer — a [Handler] or an [Executor], it takes either: the
 * thread the upload was issued from, which is the thread that owns the engine and the only one
 * allowed to issue the next upload. Falls back to the main thread for a caller without a
 * [Looper], and for one whose looper has quit by the time the buffer is released — a release that
 * is never delivered would leave the geometry's gate closed for good.
 */
private fun releaseThreadHandler(): Any {
    val looper = Looper.myLooper() ?: return mainThreadHandler
    if (looper === Looper.getMainLooper()) return mainThreadHandler
    val handler = Handler(looper)
    return Executor { released -> if (!handler.post(released)) mainThreadHandler.post(released) }
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
 * What a [Geometry]'s Filament buffers were sized for. A state with another layout does not fit
 * them: the index counts are compared per primitive, not just in total, because the renderable's
 * primitive ranges follow them.
 *
 * Free of any Filament type so the allocation decision is tested on the JVM.
 */
internal data class GeometryBufferLayout(
    val vertexCount: Int,
    val indexCounts: List<Int>,
    val hasNormals: Boolean,
    val hasUvCoordinates: Boolean,
    val hasColors: Boolean,
) {
    /** How many buffers Filament is handed, and reports back, when this layout is built. */
    val streamCount: Int
        get() = 1 + // position
            (if (hasNormals) 1 else 0) +
            (if (hasUvCoordinates) 1 else 0) +
            (if (hasColors) 1 else 0) +
            1 // indices

    companion object {
        fun of(vertices: List<Geometry.Vertex>, primitivesIndices: List<List<Int>>) =
            GeometryBufferLayout(
                vertexCount = vertices.size,
                indexCounts = primitivesIndices.map { it.size },
                hasNormals = vertices.hasNormals,
                hasUvCoordinates = vertices.hasUvCoordinates,
                hasColors = vertices.hasColors,
            )
    }
}

/** Pure allocation decision — see [GeometryBufferLayout]. */
internal fun requiresBufferRebuild(
    oldVertices: List<Geometry.Vertex>,
    oldIndices: List<List<Int>>,
    newVertices: List<Geometry.Vertex>,
    newIndices: List<List<Int>>
): Boolean = GeometryBufferLayout.of(oldVertices, oldIndices) !=
    GeometryBufferLayout.of(newVertices, newIndices)

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
