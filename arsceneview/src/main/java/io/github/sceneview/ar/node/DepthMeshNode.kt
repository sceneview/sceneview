package io.github.sceneview.ar.node

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
import io.github.sceneview.ar.arcore.DEFAULT_DEPTH_EDGE_THRESHOLD_METERS
import io.github.sceneview.ar.arcore.DEFAULT_DEPTH_MESH_STRIDE
import io.github.sceneview.ar.arcore.DepthMeshGeometry
import io.github.sceneview.ar.arcore.buildDepthMeshGeometry
import io.github.sceneview.ar.arcore.transform
import io.github.sceneview.math.Transform
import io.github.sceneview.node.MeshNode
import io.github.sceneview.safeDestroyIndexBuffer
import io.github.sceneview.safeDestroyVertexBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

/**
 * A renderable Filament mesh built from the live ARCore environment depth image, with
 * edge-discontinuity culling so triangles never stretch across depth jumps.
 *
 * `DepthMeshNode` is the SceneView equivalent of Google's
 * [arcore-depth-lab](https://github.com/googlesamples/arcore-depth-lab) `ScreenSpaceDepthMesh`. It
 * reifies the depth image as actual geometry inside the Filament scene so virtual objects can
 * **cast shadows onto the real world**, the mesh can carry a scan / pulse / x-ray material,
 * and the same vertex/index buffers can be re-used as a static collider for a depth-driven
 * physics rig (see [#1713](https://github.com/sceneview/sceneview/issues/1713)).
 *
 * The mesh is anchored in ARCore **camera space**: its world transform is updated each frame to
 * follow the camera pose, so the (camera-space) vertices appear at the right place in the world.
 *
 * Requires the [Session] to be configured with a depth mode — either [Config.DepthMode.AUTOMATIC]
 * or [Config.DepthMode.RAW_DEPTH_ONLY]. The mesh stays empty until both depth is available *and*
 * the camera is in [TrackingState.TRACKING].
 *
 * Threading: depth sampling + Filament buffer uploads run on the AR frame thread (the rendering
 * thread). Do not poke the node from a background coroutine.
 *
 * ### Cost
 *
 * Rebuilding the mesh involves a full pass over the depth image, an unprojection per sample, and
 * a full upload to Filament. This is **expensive**: by default the rebuild rate is rate-limited
 * to one rebuild every [refreshIntervalMs] (200 ms = 5 Hz). Tune up or down depending on whether
 * you want a "live" depth surface or a coarser, cheaper one.
 *
 * ### Mesh density
 *
 * Each [stride] depth samples become one vertex of a triangulated grid. Higher [stride] = fewer
 * vertices = faster rebuild = lower visual fidelity. The default ([DEFAULT_DEPTH_MESH_STRIDE]) is
 * tuned for the 160×90 ARCore depth image.
 *
 * @param engine                The Filament [Engine].
 * @param refreshIntervalMs     Minimum interval, in milliseconds, between two mesh rebuilds. The
 *                              actual rebuild is gated by ARCore frame delivery, so the effective
 *                              rate cannot exceed ARCore's frame rate (~30 Hz).
 * @param stride                Pixel stride between samples used as vertices. Default
 *                              [DEFAULT_DEPTH_MESH_STRIDE]. Use 1 for full-resolution depth (slow);
 *                              use a larger value to coarsen the mesh.
 * @param edgeThresholdMeters   Maximum Z-distance between two neighbouring depth samples before the
 *                              spanning triangle is culled. Default
 *                              [DEFAULT_DEPTH_EDGE_THRESHOLD_METERS] = 10 cm.
 * @param materialInstance      Optional [MaterialInstance] applied to the mesh. When `null`,
 *                              Filament's basic default material is used. A typical use is a
 *                              transparent shadow-receiver material (so the mesh is invisible but
 *                              still casts shadows from virtual objects).
 * @param builder               Hook into the underlying [RenderableManager.Builder] for cast/receive
 *                              shadow flags, layer mask, etc.
 * @param onMeshRebuilt         Invoked, on the AR frame thread, every time the mesh is rebuilt.
 *                              Receives the freshly-computed [DepthMeshSnapshot] — vertex positions
 *                              and triangle indices in **camera space**. Use this to feed a physics
 *                              collider (#1713), a debug overlay, or any other downstream consumer.
 */
open class DepthMeshNode(
    engine: Engine,
    var refreshIntervalMs: Long = DEFAULT_REFRESH_INTERVAL_MS,
    var stride: Int = DEFAULT_DEPTH_MESH_STRIDE,
    var edgeThresholdMeters: Float = DEFAULT_DEPTH_EDGE_THRESHOLD_METERS,
    materialInstance: MaterialInstance? = null,
    builder: RenderableManager.Builder.() -> Unit = {},
    var onMeshRebuilt: ((DepthMeshSnapshot) -> Unit)? = null,
) : MeshNode(
    engine = engine,
    primitiveType = PrimitiveType.TRIANGLES,
    vertexBuffer = createInitialVertexBuffer(engine),
    indexBuffer = createInitialIndexBuffer(engine),
    // Degenerate but non-empty AABB so Filament's precondition (#1783) passes before the first
    // valid depth frame populates the real bounds. A zero-sized box at the origin trips the
    // "AABB can't be empty, unless culling is disabled and the object is not a shadow caster/
    // receiver" check and SIGABRTs the process. A 1 mm half-extent is small enough to be a no-op
    // visually + for culling, large enough to satisfy Filament's `halfExtent > 0` precondition.
    // See [computeAabb] which clamps the initial zero-positions case to the same value.
    boundingBox = Box(0f, 0f, 0f, DEGENERATE_AABB_HALF_EXTENT_M, DEGENERATE_AABB_HALF_EXTENT_M, DEGENERATE_AABB_HALF_EXTENT_M),
    materialInstance = materialInstance,
    builder = builder,
) {
    /**
     * Most recently built depth mesh, or `null` until the first successful rebuild.
     *
     * Vertices are in **ARCore camera space** at the time of the rebuild. Combine with the node's
     * current [worldTransform] to get world-space positions — or read [DepthMeshSnapshot.worldTransform]
     * which captures the camera pose at rebuild time.
     */
    var latestSnapshot: DepthMeshSnapshot? = null
        protected set

    /**
     * Tracks the wall-clock time of the last successful rebuild so [refreshIntervalMs] can gate
     * the next one.
     */
    private var lastRebuildTimestampMs: Long = Long.MIN_VALUE

    /** Capacity (in vertices) of the currently-allocated [vertexBuffer]. */
    private var allocatedVertexCount: Int = INITIAL_VERTEX_CAPACITY

    /** Capacity (in indices) of the currently-allocated [indexBuffer]. */
    private var allocatedIndexCount: Int = INITIAL_INDEX_CAPACITY

    /**
     * Track owned buffers so [destroy] can free them. (The base [MeshNode] only owns the
     * renderable; reallocations done in [rebuildBuffersIfNeeded] would otherwise leak.)
     */
    private var ownedVertexBuffer: VertexBuffer = vertexBuffer
    private var ownedIndexBuffer: IndexBuffer = indexBuffer

    /**
     * Returns the current vertex buffer (live, may change across rebuilds when capacity grows).
     *
     * Exposed for downstream consumers (the #1713 physics collider) that need a stable handle to
     * the latest geometry. Read-only — never call [com.google.android.filament.Engine.destroyVertexBuffer]
     * on this; [destroy] handles the lifecycle.
     */
    val currentVertexBuffer: VertexBuffer get() = ownedVertexBuffer

    /** Returns the current index buffer (live, may change across rebuilds when capacity grows). */
    val currentIndexBuffer: IndexBuffer get() = ownedIndexBuffer

    /**
     * Pulls the latest depth image off [frame], rebuilds the mesh if [refreshIntervalMs] has
     * elapsed, and uploads the new geometry to Filament.
     *
     * Called by the AR scene each frame for every [DepthMeshNode] child — same protocol as
     * [PoseNode.update].
     *
     * No-ops when the camera is not tracking, when depth is not supported / not yet computed, or
     * when the refresh interval has not yet elapsed.
     */
    open fun update(session: Session, frame: Frame) {
        val camera = frame.camera
        if (camera.trackingState != TrackingState.TRACKING) return

        val now = nowMs()
        if (now - lastRebuildTimestampMs < refreshIntervalMs) return

        val depthImage = runCatching { frame.acquireDepthImage16Bits() }.getOrNull()
            ?: runCatching { frame.acquireRawDepthImage16Bits() }.getOrNull()
            ?: return

        try {
            val depthWidth = depthImage.width
            val depthHeight = depthImage.height
            val plane = depthImage.planes[0]
            val rowStrideShorts = plane.rowStride / 2
            // ARCore packs DEPTH16 little-endian on every supported device — matches nativeOrder.
            val buffer = plane.buffer.order(ByteOrder.nativeOrder()).asShortBuffer()

            // ARCore reports intrinsics for the full-resolution CPU image; scale them to the
            // (smaller) depth image resolution.
            val intrinsics = camera.imageIntrinsics
            val scaleX = depthWidth / intrinsics.imageDimensions[0].toFloat()
            val scaleY = depthHeight / intrinsics.imageDimensions[1].toFloat()
            val fx = intrinsics.focalLength[0] * scaleX
            val fy = intrinsics.focalLength[1] * scaleY
            val cx = intrinsics.principalPoint[0] * scaleX
            val cy = intrinsics.principalPoint[1] * scaleY

            val geometry = buildDepthMeshGeometry(
                depthBuffer = buffer,
                depthWidth = depthWidth,
                depthHeight = depthHeight,
                rowStrideShorts = rowStrideShorts,
                fx = fx,
                fy = fy,
                cx = cx,
                cy = cy,
                stride = stride.coerceAtLeast(1),
                edgeThresholdMeters = edgeThresholdMeters,
            )

            uploadGeometry(geometry)
            // Track the camera pose at rebuild time so the node visually follows the camera.
            worldTransform = camera.pose.transform
            lastRebuildTimestampMs = now

            val snapshot = DepthMeshSnapshot(
                positions = geometry.positions,
                indices = geometry.indices,
                width = geometry.width,
                height = geometry.height,
                worldTransform = worldTransform,
                timestampMs = now,
            )
            latestSnapshot = snapshot
            onMeshRebuilt?.invoke(snapshot)
        } finally {
            depthImage.close()
        }
    }

    /**
     * Uploads [geometry] to the Filament [vertexBuffer] / [indexBuffer]. Reallocates the
     * underlying buffers when the new geometry exceeds the current capacity — Filament buffers
     * have a fixed `vertexCount` / `indexCount` set at build time.
     */
    private fun uploadGeometry(geometry: DepthMeshGeometry) {
        rebuildBuffersIfNeeded(geometry.vertexCount, geometry.indices.size)

        // VertexBuffer expects a direct ByteBuffer; allocate one ByteBuffer of the exact size.
        val positions = geometry.positions
        val vertexBytes = ByteBuffer.allocateDirect(positions.size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
        vertexBytes.asFloatBuffer().apply {
            put(positions)
            // floatBuffer's position advanced, but VertexBuffer.setBufferAt(...) reads from the
            // *ByteBuffer*'s position, which we left at 0 — no rewind needed.
        }
        ownedVertexBuffer.setBufferAt(engine, 0, vertexBytes, 0, positions.size * Float.SIZE_BYTES)

        val indices = geometry.indices
        val indexBytes = ByteBuffer.allocateDirect(indices.size * Int.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
        indexBytes.asIntBuffer().apply { put(indices) }
        ownedIndexBuffer.setBuffer(engine, indexBytes, 0, indices.size * Int.SIZE_BYTES)

        // Tell Filament how many indices to actually render. We don't expose the offset/count
        // overload publicly, but we can replace the renderable's geometry in-place.
        renderableManager.setGeometryAt(
            renderableInstance,
            0,
            PrimitiveType.TRIANGLES,
            ownedVertexBuffer,
            ownedIndexBuffer,
            0,
            indices.size,
        )

        // Recompute and apply the AABB so frustum culling works once the mesh has real content.
        renderableManager.setAxisAlignedBoundingBox(renderableInstance, computeAabb(positions))
    }

    /**
     * Reallocate [ownedVertexBuffer] / [ownedIndexBuffer] when the requested capacity exceeds the
     * current one. Grows in powers of two to amortise allocation churn across rebuilds — the
     * depth image dimensions are constant, so this typically reallocates **once**.
     */
    private fun rebuildBuffersIfNeeded(requestedVertexCount: Int, requestedIndexCount: Int) {
        if (requestedVertexCount > allocatedVertexCount) {
            val newCount = nextPowerOfTwo(max(requestedVertexCount, INITIAL_VERTEX_CAPACITY))
            engine.safeDestroyVertexBuffer(ownedVertexBuffer)
            ownedVertexBuffer = VertexBuffer.Builder()
                .bufferCount(1)
                .vertexCount(newCount)
                .attribute(VertexAttribute.POSITION, 0, AttributeType.FLOAT3)
                .build(engine)
            allocatedVertexCount = newCount
        }
        if (requestedIndexCount > allocatedIndexCount) {
            val newCount = nextPowerOfTwo(max(requestedIndexCount, INITIAL_INDEX_CAPACITY))
            engine.safeDestroyIndexBuffer(ownedIndexBuffer)
            ownedIndexBuffer = IndexBuffer.Builder()
                .bufferType(IndexBuffer.Builder.IndexType.UINT)
                .indexCount(newCount)
                .build(engine)
            allocatedIndexCount = newCount
        }
    }

    override fun destroy() {
        // Destroy the renderable BEFORE the buffers — otherwise Filament's bookkeeping yelps
        // about a renderable still referencing a freed VertexBuffer (#1123 same pattern).
        super.destroy()
        engine.safeDestroyVertexBuffer(ownedVertexBuffer)
        engine.safeDestroyIndexBuffer(ownedIndexBuffer)
    }

    /** Hook for tests — JVM monotonic time. */
    protected open fun nowMs(): Long = System.currentTimeMillis()

    companion object {
        /** Default rebuild rate, in milliseconds — 5 Hz, well below ARCore's ~30 Hz delivery. */
        const val DEFAULT_REFRESH_INTERVAL_MS: Long = 200L

        /** Initial capacity of the vertex buffer — chosen to fit a stride-4 mesh from a 160×90 depth image. */
        private const val INITIAL_VERTEX_CAPACITY: Int = 1024

        /** Initial capacity of the index buffer — 6 indices per quad for the same stride-4 grid. */
        private const val INITIAL_INDEX_CAPACITY: Int = 6 * 1024

        /**
         * Half-extent (metres) for the degenerate AABB used before the first real depth frame.
         *
         * Filament SIGABRTs with `Precondition: AABB can't be empty, unless culling is disabled
         * and the object is not a shadow caster/receiver` when the renderable's bounding box has
         * any zero-half-extent and the renderable has culling enabled (the default) or casts /
         * receives shadows. We pre-populate a 1 mm cube at the origin so the precondition passes
         * even before [update] has run with valid camera tracking + depth data — see #1783.
         */
        internal const val DEGENERATE_AABB_HALF_EXTENT_M: Float = 1e-3f

        private fun createInitialVertexBuffer(engine: Engine): VertexBuffer =
            VertexBuffer.Builder()
                .bufferCount(1)
                .vertexCount(INITIAL_VERTEX_CAPACITY)
                .attribute(VertexAttribute.POSITION, 0, AttributeType.FLOAT3)
                .build(engine)

        private fun createInitialIndexBuffer(engine: Engine): IndexBuffer =
            IndexBuffer.Builder()
                .bufferType(IndexBuffer.Builder.IndexType.UINT)
                .indexCount(INITIAL_INDEX_CAPACITY)
                .build(engine)
    }
}

/**
 * Immutable per-rebuild snapshot of a [DepthMeshNode]'s geometry — exposed via
 * [DepthMeshNode.onMeshRebuilt] and [DepthMeshNode.latestSnapshot] so downstream consumers
 * (e.g. the #1713 depth-driven physics collider, debug overlays, point-cloud exporters) can read
 * the mesh data without poking the Filament internals.
 *
 * @property positions       Flat-packed vertex positions `[x0,y0,z0, x1,y1,z1, ...]` in **ARCore
 *                           camera space** at rebuild time. Multiply by [worldTransform] to get
 *                           world-space coordinates.
 * @property indices         Triangle indices (3 ints per triangle).
 * @property width           Grid width in vertices.
 * @property height          Grid height in vertices.
 * @property worldTransform  Camera pose at rebuild time, as a 4×4 column-major transform. Apply
 *                           to a camera-space [positions] vertex to get the world-space position.
 * @property timestampMs     Wall-clock timestamp (`System.currentTimeMillis`) at rebuild time.
 */
data class DepthMeshSnapshot(
    val positions: FloatArray,
    val indices: IntArray,
    val width: Int,
    val height: Int,
    val worldTransform: Transform,
    val timestampMs: Long,
) {
    val vertexCount: Int get() = positions.size / 3
    val triangleCount: Int get() = indices.size / 3

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DepthMeshSnapshot) return false
        return width == other.width &&
            height == other.height &&
            timestampMs == other.timestampMs &&
            worldTransform == other.worldTransform &&
            positions.contentEquals(other.positions) &&
            indices.contentEquals(other.indices)
    }

    override fun hashCode(): Int {
        var result = positions.contentHashCode()
        result = 31 * result + indices.contentHashCode()
        result = 31 * result + width
        result = 31 * result + height
        result = 31 * result + worldTransform.hashCode()
        result = 31 * result + timestampMs.hashCode()
        return result
    }
}

/**
 * Computes an axis-aligned bounding box from a flat-packed `xyz` array.
 *
 * Filament rejects empty AABBs (any half-extent ≤ 0) with a `Precondition: AABB can't be empty`
 * SIGABRT when culling is enabled or the renderable casts/receives shadows. Two degenerate paths
 * trigger this:
 *  1. Empty `positions` (no depth samples this frame) — guarded since #1783.
 *  2. All samples share the same coordinate on at least one axis (e.g. a depth image where every
 *     pixel reports the same range, or the first frame after a scene reset where only one
 *     off-grid (0,0,0) vertex made it through). The min/max collapse and at least one half-extent
 *     comes out as 0 — same SIGABRT (#1806).
 *
 * The clamp below substitutes [DepthMeshNode.DEGENERATE_AABB_HALF_EXTENT_M] for any half-extent
 * below that threshold so the returned [Box] always satisfies Filament's `halfExtent > 0`
 * precondition. The centre is preserved so frustum culling still localises the renderable.
 */
internal fun computeAabb(positions: FloatArray): Box {
    // Same precondition guard as the initial AABB — Filament rejects empty boxes (#1783).
    if (positions.isEmpty()) return Box(
        0f, 0f, 0f,
        DepthMeshNode.DEGENERATE_AABB_HALF_EXTENT_M,
        DepthMeshNode.DEGENERATE_AABB_HALF_EXTENT_M,
        DepthMeshNode.DEGENERATE_AABB_HALF_EXTENT_M,
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
    val centerX = (minX + maxX) * 0.5f
    val centerY = (minY + maxY) * 0.5f
    val centerZ = (minZ + maxZ) * 0.5f
    // Clamp any half-extent below the degenerate threshold to the minimum half-extent so Filament
    // never sees a zero-extent box (#1806). This catches the all-same-coordinate case that the
    // empty-positions guard from #1783 misses.
    val minHalf = DepthMeshNode.DEGENERATE_AABB_HALF_EXTENT_M
    val halfX = ((maxX - minX) * 0.5f).coerceAtLeast(minHalf)
    val halfY = ((maxY - minY) * 0.5f).coerceAtLeast(minHalf)
    val halfZ = ((maxZ - minZ) * 0.5f).coerceAtLeast(minHalf)
    return Box(centerX, centerY, centerZ, halfX, halfY, halfZ)
}

private fun nextPowerOfTwo(value: Int): Int {
    if (value <= 1) return 1
    var v = value - 1
    v = v or (v ushr 1)
    v = v or (v ushr 2)
    v = v or (v ushr 4)
    v = v or (v ushr 8)
    v = v or (v ushr 16)
    return v + 1
}
