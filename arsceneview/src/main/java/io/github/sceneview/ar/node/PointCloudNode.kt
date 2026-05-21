package io.github.sceneview.ar.node

import androidx.annotation.MainThread
import com.google.android.filament.Box
import com.google.android.filament.Engine
import com.google.android.filament.IndexBuffer
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.RenderableManager.PrimitiveType
import com.google.android.filament.VertexBuffer
import com.google.android.filament.VertexBuffer.AttributeType
import com.google.android.filament.VertexBuffer.VertexAttribute
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.arcore.buildPointCloudPositions
import io.github.sceneview.node.MeshNode
import io.github.sceneview.safeDestroyIndexBuffer
import io.github.sceneview.safeDestroyVertexBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A renderable, in-scene point cloud built from ARCore's live tracking feature points.
 *
 * `PointCloudNode` is the SceneView equivalent of AR Foundation's `ARPointCloudManager`: it
 * consumes [Frame.acquirePointCloud] every frame and renders the surviving feature points as a
 * Filament [PrimitiveType.POINTS] primitive. The rendering logic was previously available only as
 * debug telemetry through `RerunBridge.logPointCloud` — this node makes it a first-class scene
 * object usable for debug overlays, "scanning" visual feedback, procedural-art demos, and
 * surfacing tracking quality to the user.
 *
 * The points returned by ARCore are already in **world space**, so the node keeps the identity
 * transform — it does not follow the camera. Each point also carries a `[0, 1]` confidence value;
 * points below [confidenceThreshold] are filtered out before upload.
 *
 * Threading: [update] pulls the point cloud and uploads the Filament buffers on the AR frame /
 * render thread. Do not poke the node from a background coroutine — Filament JNI calls require the
 * main / render thread.
 *
 * ### Point size
 *
 * Filament renders [PrimitiveType.POINTS] at a fixed 1-pixel size with the default unlit
 * material. Variable per-point size needs a custom material that writes `gl_PointSize`; it is a
 * deliberate non-goal here to avoid shipping an extra `.filamat` blob. Color is fully
 * configurable through [materialInstance] (a `MaterialLoader.createUnlitColorInstance(...)` is the
 * typical choice).
 *
 * @param engine               The Filament [Engine].
 * @param confidenceThreshold  Minimum ARCore confidence in `[0, 1]` for a feature point to be
 *                             rendered. `0` keeps every point; raise it to drop noisy,
 *                             low-confidence points. Default [DEFAULT_CONFIDENCE_THRESHOLD].
 * @param materialInstance     Optional [MaterialInstance] applied to the cloud. When `null`,
 *                             Filament's basic default material is used. An unlit colored
 *                             material is the usual choice for a flat, lighting-independent dot.
 * @param builder              Hook into the underlying [RenderableManager.Builder] for layer
 *                             mask, priority, etc.
 * @param onPointCloudUpdated  Invoked, on the AR frame thread, every time the cloud is rebuilt.
 *                             Receives the number of points that cleared [confidenceThreshold] —
 *                             use it to show a "tracking quality" indicator.
 */
open class PointCloudNode(
    engine: Engine,
    var confidenceThreshold: Float = DEFAULT_CONFIDENCE_THRESHOLD,
    materialInstance: MaterialInstance? = null,
    builder: RenderableManager.Builder.() -> Unit = {},
    onPointCloudUpdated: ((pointCount: Int) -> Unit)? = null,
) : MeshNode(
    engine = engine,
    primitiveType = PrimitiveType.POINTS,
    vertexBuffer = createInitialVertexBuffer(engine),
    indexBuffer = createInitialIndexBuffer(engine),
    // Degenerate but non-empty AABB so Filament's "AABB can't be empty" precondition passes
    // before the first valid point cloud populates the real bounds — see DepthMeshNode #1783.
    boundingBox = Box(
        0f, 0f, 0f,
        DEGENERATE_AABB_HALF_EXTENT_M, DEGENERATE_AABB_HALF_EXTENT_M, DEGENERATE_AABB_HALF_EXTENT_M,
    ),
    materialInstance = materialInstance,
    builder = builder,
) {
    /**
     * Number of points rendered after the most recent successful [update]. `0` until the first
     * point cloud is acquired. Render-thread only.
     */
    @get:MainThread
    var pointCount: Int = 0
        @MainThread protected set

    /**
     * Per-update callback. Setter is render-thread only — swapping the callback from a background
     * coroutine could race with the next invoke under the [update] call site.
     */
    @set:MainThread
    var onPointCloudUpdated: ((pointCount: Int) -> Unit)? = onPointCloudUpdated

    /** Capacity (in vertices) of the currently-allocated buffers. Render-thread only. */
    private var allocatedPointCount: Int = INITIAL_POINT_CAPACITY

    /** Owned buffers so [destroy] can free them. The base [MeshNode] only owns the renderable. */
    private var ownedVertexBuffer: VertexBuffer = vertexBuffer
    private var ownedIndexBuffer: IndexBuffer = indexBuffer

    /**
     * Pulls the latest feature point cloud off [frame], filters it by [confidenceThreshold], and
     * uploads the surviving points to Filament.
     *
     * Called by the AR scene each frame for every [PointCloudNode] child — same protocol as
     * [DepthMeshNode.update]. No-ops when the camera is not tracking or when the point cloud
     * cannot be acquired (depth API not ready, etc.).
     *
     * Threading: must be called on the render / AR-frame thread. Filament JNI calls require the
     * main / render thread.
     */
    @MainThread
    open fun update(session: Session, frame: Frame) {
        if (frame.camera.trackingState != TrackingState.TRACKING) return

        val positions = runCatching {
            frame.acquirePointCloud().use { cloud ->
                buildPointCloudPositions(cloud.points, confidenceThreshold)
            }
        }.getOrNull() ?: return

        uploadPoints(positions)
        pointCount = positions.size / 3
        onPointCloudUpdated?.invoke(pointCount)
    }

    /**
     * Uploads [positions] (flat `xyz`) to the Filament buffers, growing the underlying buffers
     * when the new cloud exceeds the current capacity.
     *
     * Reallocation order is **build new → rebind via [RenderableManager.setGeometryAt] → destroy
     * old** so the renderable never references a freed native handle (the #1805 use-after-free
     * invariant proven by [DepthMeshNode]).
     */
    private fun uploadPoints(positions: FloatArray) {
        val requestedCount = positions.size / 3

        // Allocate replacement buffers WITHOUT swapping the owned* fields yet: if the upload
        // below throws we must still be able to free the new buffers AND keep the old ones for
        // [destroy]. Mirrors DepthMeshNode #1840.
        val grow = requestedCount > allocatedPointCount
        val newCapacity = if (grow) {
            pointCloudNextPowerOfTwo(maxOf(requestedCount, INITIAL_POINT_CAPACITY))
        } else {
            allocatedPointCount
        }
        val newVertexBuffer = if (grow) createVertexBuffer(engine, newCapacity) else null
        val newIndexBuffer = if (grow) createIndexBuffer(engine, newCapacity) else null
        val targetVertexBuffer = newVertexBuffer ?: ownedVertexBuffer
        val targetIndexBuffer = newIndexBuffer ?: ownedIndexBuffer

        try {
            // Fresh direct buffers per upload — Filament copies asynchronously on the render
            // command stream, so reusing a cached buffer risks a torn upload (DepthMeshNode #1841).
            val vertexBytesNeeded = positions.size * Float.SIZE_BYTES
            val vertexBytes = ByteBuffer.allocateDirect(vertexBytesNeeded).order(ByteOrder.nativeOrder())
            vertexBytes.asFloatBuffer().put(positions)
            targetVertexBuffer.setBufferAt(engine, 0, vertexBytes, 0, vertexBytesNeeded)

            // POINTS still need an index buffer — a trivial 0,1,2,...,n-1 sequence.
            val indexBytesNeeded = requestedCount * Int.SIZE_BYTES
            val indexBytes = ByteBuffer.allocateDirect(maxOf(indexBytesNeeded, Int.SIZE_BYTES))
                .order(ByteOrder.nativeOrder())
            val intView = indexBytes.asIntBuffer()
            for (i in 0 until requestedCount) intView.put(i)
            targetIndexBuffer.setBuffer(engine, indexBytes, 0, indexBytesNeeded)

            // Rebind to the (possibly new) buffers and tell Filament how many points to draw.
            // Done BEFORE freeing the old buffers to close the #1805 use-after-free window.
            renderableManager.setGeometryAt(
                renderableInstance,
                0,
                PrimitiveType.POINTS,
                targetVertexBuffer,
                targetIndexBuffer,
                0,
                requestedCount,
            )
            renderableManager.setAxisAlignedBoundingBox(renderableInstance, computePointCloudAabb(positions))
        } catch (t: Throwable) {
            // Upload threw — keep the old buffers reachable, free only the new ones (#1840).
            newVertexBuffer?.let { engine.safeDestroyVertexBuffer(it) }
            newIndexBuffer?.let { engine.safeDestroyIndexBuffer(it) }
            throw t
        }

        // Commit the swap and free the now-stale old buffers.
        if (newVertexBuffer != null) {
            val staleVertex = ownedVertexBuffer
            val staleIndex = ownedIndexBuffer
            ownedVertexBuffer = newVertexBuffer
            ownedIndexBuffer = newIndexBuffer!!
            allocatedPointCount = newCapacity
            engine.safeDestroyVertexBuffer(staleVertex)
            engine.safeDestroyIndexBuffer(staleIndex)
        }
    }

    override fun destroy() {
        // Destroy the renderable BEFORE the buffers so Filament's bookkeeping does not yelp about
        // a renderable still referencing a freed VertexBuffer (DepthMeshNode #1123 pattern).
        super.destroy()
        engine.safeDestroyVertexBuffer(ownedVertexBuffer)
        engine.safeDestroyIndexBuffer(ownedIndexBuffer)
    }

    companion object {
        /**
         * Default confidence cut-off. `0.2` drops the noisiest motion-stereo points on non-LiDAR
         * Pixels while keeping enough of the cloud to be visually useful.
         */
        const val DEFAULT_CONFIDENCE_THRESHOLD: Float = 0.2f

        /** Initial buffer capacity (in points). ARCore typically tracks a few hundred points. */
        internal const val INITIAL_POINT_CAPACITY: Int = 256

        /**
         * Half-extent (metres) for the degenerate AABB used before the first real point cloud.
         * Filament SIGABRTs on an empty AABB — see [DepthMeshNode.DEGENERATE_AABB_HALF_EXTENT_M].
         */
        internal const val DEGENERATE_AABB_HALF_EXTENT_M: Float = 1e-3f

        private fun createVertexBuffer(engine: Engine, count: Int): VertexBuffer =
            VertexBuffer.Builder()
                .bufferCount(1)
                .vertexCount(count)
                .attribute(VertexAttribute.POSITION, 0, AttributeType.FLOAT3)
                .build(engine)

        private fun createIndexBuffer(engine: Engine, count: Int): IndexBuffer =
            IndexBuffer.Builder()
                .bufferType(IndexBuffer.Builder.IndexType.UINT)
                .indexCount(count)
                .build(engine)

        private fun createInitialVertexBuffer(engine: Engine): VertexBuffer =
            createVertexBuffer(engine, INITIAL_POINT_CAPACITY)

        private fun createInitialIndexBuffer(engine: Engine): IndexBuffer =
            createIndexBuffer(engine, INITIAL_POINT_CAPACITY)
    }
}

/**
 * Computes an axis-aligned bounding box from a flat-packed `xyz` array, clamping every half-extent
 * to at least [PointCloudNode.DEGENERATE_AABB_HALF_EXTENT_M] so Filament never sees an empty box
 * (it SIGABRTs on one — see [computeAabb] in `DepthMeshNode.kt`, the #1783 / #1806 invariant).
 *
 * Extracted as an `internal` top-level function so the bounds math can be pinned by a JVM unit
 * test without touching the Filament JNI surface.
 */
internal fun computePointCloudAabb(positions: FloatArray): Box {
    if (positions.isEmpty()) return Box(
        0f, 0f, 0f,
        PointCloudNode.DEGENERATE_AABB_HALF_EXTENT_M,
        PointCloudNode.DEGENERATE_AABB_HALF_EXTENT_M,
        PointCloudNode.DEGENERATE_AABB_HALF_EXTENT_M,
    )
    var minX = Float.POSITIVE_INFINITY
    var minY = Float.POSITIVE_INFINITY
    var minZ = Float.POSITIVE_INFINITY
    var maxX = Float.NEGATIVE_INFINITY
    var maxY = Float.NEGATIVE_INFINITY
    var maxZ = Float.NEGATIVE_INFINITY
    var i = 0
    while (i < positions.size) {
        val x = positions[i]
        val y = positions[i + 1]
        val z = positions[i + 2]
        if (x < minX) minX = x
        if (y < minY) minY = y
        if (z < minZ) minZ = z
        if (x > maxX) maxX = x
        if (y > maxY) maxY = y
        if (z > maxZ) maxZ = z
        i += 3
    }
    val minHalf = PointCloudNode.DEGENERATE_AABB_HALF_EXTENT_M
    return Box(
        (minX + maxX) * 0.5f,
        (minY + maxY) * 0.5f,
        (minZ + maxZ) * 0.5f,
        ((maxX - minX) * 0.5f).coerceAtLeast(minHalf),
        ((maxY - minY) * 0.5f).coerceAtLeast(minHalf),
        ((maxZ - minZ) * 0.5f).coerceAtLeast(minHalf),
    )
}

/** Rounds [value] up to the next power of two — amortises buffer-allocation churn. */
internal fun pointCloudNextPowerOfTwo(value: Int): Int {
    if (value <= 1) return 1
    var v = value - 1
    v = v or (v ushr 1)
    v = v or (v ushr 2)
    v = v or (v ushr 4)
    v = v or (v ushr 8)
    v = v or (v ushr 16)
    return v + 1
}
