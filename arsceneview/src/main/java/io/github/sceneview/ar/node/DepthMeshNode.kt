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
    onMeshRebuilt: ((DepthMeshSnapshot) -> Unit)? = null,
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
     *
     * Threading: read and write on the render / AR-frame thread only (#1811). Reading from a
     * background coroutine is **unsupported** — the field may be torn or stale.
     */
    @get:MainThread
    var latestSnapshot: DepthMeshSnapshot? = null
        @MainThread protected set

    /**
     * Tracks the wall-clock time of the last successful rebuild so [refreshIntervalMs] can gate
     * the next one. Render-thread only (#1811).
     */
    private var lastRebuildTimestampMs: Long = Long.MIN_VALUE

    /** Capacity (in vertices) of the currently-allocated [vertexBuffer]. Render-thread only. */
    private var allocatedVertexCount: Int = INITIAL_VERTEX_CAPACITY

    /** Capacity (in indices) of the currently-allocated [indexBuffer]. Render-thread only. */
    private var allocatedIndexCount: Int = INITIAL_INDEX_CAPACITY

    /**
     * Track owned buffers so [destroy] can free them. (The base [MeshNode] only owns the
     * renderable; reallocations done in [rebuildBuffersIfNeeded] would otherwise leak.)
     */
    private var ownedVertexBuffer: VertexBuffer = vertexBuffer
    private var ownedIndexBuffer: IndexBuffer = indexBuffer

    /**
     * Cached direct [ByteBuffer]s for vertex / index upload (#1810). The previous
     * [uploadGeometry] allocated a fresh `ByteBuffer.allocateDirect(positions × 4)` + a fresh
     * `ByteBuffer.allocateDirect(indices × 4)` on **every rebuild** — ~11 KB + ~9 KB per rebuild
     * at the default stride-4 settings × 5 Hz = ~100 KB/s direct-buffer churn on the render
     * thread, climbing to ~600 KB/s at 30 Hz. Caching them here, with power-of-two growth
     * alongside the existing [allocatedVertexCount] / [allocatedIndexCount] capacity logic,
     * makes the steady-state upload **zero-allocation** — same buffer, position rewound + bytes
     * overwritten per upload.
     *
     * Render-thread only. Initialized lazily on first use so a node that never uploads (test
     * fixture, edge case) doesn't pay for the allocation.
     */
    private var vertexUploadBuffer: ByteBuffer? = null

    /** Companion of [vertexUploadBuffer] for the index stream. */
    private var indexUploadBuffer: ByteBuffer? = null

    /**
     * Returns the current vertex buffer (live, may change across rebuilds when capacity grows).
     *
     * Exposed for downstream consumers (the #1713 physics collider) that need a stable handle to
     * the latest geometry. Read-only — never call [com.google.android.filament.Engine.destroyVertexBuffer]
     * on this; [destroy] handles the lifecycle.
     *
     * Threading: render-thread only (#1811). Filament buffer handles are scene-graph state and
     * must not be inspected from a background coroutine.
     */
    @get:MainThread
    val currentVertexBuffer: VertexBuffer get() = ownedVertexBuffer

    /**
     * Returns the current index buffer (live, may change across rebuilds when capacity grows).
     *
     * Threading: render-thread only (#1811).
     */
    @get:MainThread
    val currentIndexBuffer: IndexBuffer get() = ownedIndexBuffer

    /**
     * Per-rebuild callback. Setter is render-thread only (#1811) — swapping the callback from a
     * background coroutine could race with the next invoke under the [update] call site.
     */
    @set:MainThread
    var onMeshRebuilt: ((DepthMeshSnapshot) -> Unit)? = onMeshRebuilt

    /**
     * Pulls the latest depth image off [frame], rebuilds the mesh if [refreshIntervalMs] has
     * elapsed, and uploads the new geometry to Filament.
     *
     * Called by the AR scene each frame for every [DepthMeshNode] child — same protocol as
     * [PoseNode.update].
     *
     * No-ops when the camera is not tracking, when depth is not supported / not yet computed, or
     * when the refresh interval has not yet elapsed.
     *
     * Threading: must be called on the render / AR-frame thread (#1811). Calling it from a
     * background coroutine is **unsupported** — Filament JNI calls require the main / render
     * thread, and the depth-image acquire path is not re-entrant.
     */
    @MainThread
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
     *
     * Reallocation order is **build new → rebind via [RenderableManager.setGeometryAt] → destroy
     * old** (see [rebuildBuffersIfNeeded] return value). Destroying first would leave the
     * renderable referencing freed Filament native handles for a window between
     * [com.google.android.filament.Engine.destroyVertexBuffer]/`destroyIndexBuffer` and
     * `setGeometryAt` — a use-after-free if the render thread picks up the renderable mid-update
     * (#1805).
     */
    private fun uploadGeometry(geometry: DepthMeshGeometry) {
        val staleBuffers = rebuildBuffersIfNeeded(geometry.vertexCount, geometry.indices.size)

        // VertexBuffer expects a direct ByteBuffer. Per-rebuild fresh-allocate path was ~11 KB
        // per upload at 5 Hz = ~100 KB/s direct-buffer churn (#1810). Cached buffers below are
        // grown in powers of two and reused across rebuilds — steady-state is zero-alloc.
        val positions = geometry.positions
        val vertexBytesNeeded = positions.size * Float.SIZE_BYTES
        val vertexBytes = acquireDirectBuffer(vertexUploadBuffer, vertexBytesNeeded).also {
            vertexUploadBuffer = it
        }
        vertexBytes.clear()
        vertexBytes.asFloatBuffer().put(positions)
        // ByteBuffer position stays at 0 (we mutated the FloatBuffer view only); setBufferAt
        // reads from the underlying ByteBuffer's position.
        ownedVertexBuffer.setBufferAt(engine, 0, vertexBytes, 0, vertexBytesNeeded)

        val indices = geometry.indices
        val indexBytesNeeded = indices.size * Int.SIZE_BYTES
        val indexBytes = acquireDirectBuffer(indexUploadBuffer, indexBytesNeeded).also {
            indexUploadBuffer = it
        }
        indexBytes.clear()
        indexBytes.asIntBuffer().put(indices)
        ownedIndexBuffer.setBuffer(engine, indexBytes, 0, indexBytesNeeded)

        // Tell Filament how many indices to actually render. We don't expose the offset/count
        // overload publicly, but we can replace the renderable's geometry in-place.
        // Rebinding the renderable to the NEW buffers BEFORE freeing the old ones closes the
        // use-after-free window (#1805): for one frame between safeDestroy and setGeometryAt the
        // renderable would otherwise reference freed Filament native handles.
        renderableManager.setGeometryAt(
            renderableInstance,
            0,
            PrimitiveType.TRIANGLES,
            ownedVertexBuffer,
            ownedIndexBuffer,
            0,
            indices.size,
        )

        // Now that the renderable points at the new buffers, it is safe to release the old ones.
        staleBuffers.staleVertexBuffer?.let { engine.safeDestroyVertexBuffer(it) }
        staleBuffers.staleIndexBuffer?.let { engine.safeDestroyIndexBuffer(it) }

        // Recompute and apply the AABB so frustum culling works once the mesh has real content.
        renderableManager.setAxisAlignedBoundingBox(renderableInstance, computeAabb(positions))
    }

    /**
     * Reallocate [ownedVertexBuffer] / [ownedIndexBuffer] when the requested capacity exceeds the
     * current one. Grows in powers of two to amortise allocation churn across rebuilds — the
     * depth image dimensions are constant, so this typically reallocates **once**.
     *
     * Returns the **old** [VertexBuffer] / [IndexBuffer] handles that the caller must destroy
     * **after** rebinding the renderable to the new buffers. Destroying in-place here would
     * leave the renderable pointing at freed Filament native handles between this call and the
     * subsequent [RenderableManager.setGeometryAt] — a use-after-free (#1805).
     */
    private fun rebuildBuffersIfNeeded(
        requestedVertexCount: Int,
        requestedIndexCount: Int,
    ): StaleBuffers {
        val plan = planBufferRebuild(
            allocatedVertexCount = allocatedVertexCount,
            allocatedIndexCount = allocatedIndexCount,
            requestedVertexCount = requestedVertexCount,
            requestedIndexCount = requestedIndexCount,
        )
        var staleVertexBuffer: VertexBuffer? = null
        var staleIndexBuffer: IndexBuffer? = null
        if (plan.newVertexCount != null) {
            // Capture the OLD buffer first; build the new buffer; swap the field. The old buffer
            // is returned so the caller can destroy it AFTER rebinding the renderable.
            staleVertexBuffer = ownedVertexBuffer
            ownedVertexBuffer = VertexBuffer.Builder()
                .bufferCount(1)
                .vertexCount(plan.newVertexCount)
                .attribute(VertexAttribute.POSITION, 0, AttributeType.FLOAT3)
                .build(engine)
            allocatedVertexCount = plan.newVertexCount
        }
        if (plan.newIndexCount != null) {
            staleIndexBuffer = ownedIndexBuffer
            ownedIndexBuffer = IndexBuffer.Builder()
                .bufferType(IndexBuffer.Builder.IndexType.UINT)
                .indexCount(plan.newIndexCount)
                .build(engine)
            allocatedIndexCount = plan.newIndexCount
        }
        return StaleBuffers(staleVertexBuffer, staleIndexBuffer)
    }

    /**
     * Buffers superseded by [rebuildBuffersIfNeeded] — destroyed by [uploadGeometry] **after** the
     * renderable has been rebound to the new buffers (#1805 ordering invariant).
     */
    private data class StaleBuffers(
        val staleVertexBuffer: VertexBuffer?,
        val staleIndexBuffer: IndexBuffer?,
    )

    override fun destroy() {
        // Destroy the renderable BEFORE the buffers — otherwise Filament's bookkeeping yelps
        // about a renderable still referencing a freed VertexBuffer (#1123 same pattern).
        super.destroy()
        engine.safeDestroyVertexBuffer(ownedVertexBuffer)
        engine.safeDestroyIndexBuffer(ownedIndexBuffer)
    }

    /** Hook for tests — JVM monotonic time. */
    protected open fun nowMs(): Long = System.currentTimeMillis()

    /**
     * Returns a direct, native-order [ByteBuffer] of at least [bytesNeeded] bytes — reusing
     * [existing] when it already has the capacity, otherwise allocating a fresh one rounded up
     * to the next power of two. The caller is responsible for `clear()` + position-resetting
     * before each write (the existing one will have stale state from a previous upload).
     *
     * Render-thread only (matches the rest of the upload path). Power-of-two growth amortises
     * the rare reallocations across many rebuilds — once the depth image's pixel count + edge
     * culling settle, this returns the same buffer on every call (#1810).
     */
    private fun acquireDirectBuffer(existing: ByteBuffer?, bytesNeeded: Int): ByteBuffer {
        if (existing != null && existing.capacity() >= bytesNeeded) return existing
        val capacity = nextPowerOfTwo(max(bytesNeeded, MIN_UPLOAD_BUFFER_BYTES))
        return ByteBuffer.allocateDirect(capacity).order(ByteOrder.nativeOrder())
    }

    companion object {
        /** Default rebuild rate, in milliseconds — 5 Hz, well below ARCore's ~30 Hz delivery. */
        const val DEFAULT_REFRESH_INTERVAL_MS: Long = 200L

        /** Initial capacity of the vertex buffer — chosen to fit a stride-4 mesh from a 160×90 depth image. */
        internal const val INITIAL_VERTEX_CAPACITY: Int = 1024

        /** Initial capacity of the index buffer — 6 indices per quad for the same stride-4 grid. */
        internal const val INITIAL_INDEX_CAPACITY: Int = 6 * 1024

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

        /**
         * Floor capacity (bytes) for the cached upload [ByteBuffer]s (#1810). Sized to cover the
         * default stride-4 mesh from a 160×90 ARCore depth image: ~1024 vertices × 12 bytes
         * ≈ 12 KB. The next-power-of-two growth in [acquireDirectBuffer] still kicks in for
         * larger captures.
         */
        internal const val MIN_UPLOAD_BUFFER_BYTES: Int = 16 * 1024

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

/** Computes an axis-aligned bounding box from a flat-packed `xyz` array. */
private fun computeAabb(positions: FloatArray): Box {
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
    val halfX = (maxX - minX) * 0.5f
    val halfY = (maxY - minY) * 0.5f
    val halfZ = (maxZ - minZ) * 0.5f
    return Box(centerX, centerY, centerZ, halfX, halfY, halfZ)
}

/**
 * Pure plan describing whether [DepthMeshNode.rebuildBuffersIfNeeded] needs to allocate fresh
 * buffers, and at what capacity. Extracted as a top-level `internal` so JVM unit tests can pin
 * the rebuild decision logic + the **build-new → rebind → destroy-old** ordering invariant
 * (#1805) without touching the Filament JNI surface.
 *
 * - `newVertexCount == null` means the existing vertex buffer is still large enough — no rebuild.
 * - `newIndexCount  == null` means the existing index buffer is still large enough — no rebuild.
 * - When non-null, both counts are guaranteed `≥ INITIAL_*_CAPACITY` and rounded up to the next
 *   power of two for allocation-churn amortisation.
 */
internal data class BufferRebuildPlan(
    val newVertexCount: Int?,
    val newIndexCount: Int?,
)

/** See [BufferRebuildPlan] for the contract. */
internal fun planBufferRebuild(
    allocatedVertexCount: Int,
    allocatedIndexCount: Int,
    requestedVertexCount: Int,
    requestedIndexCount: Int,
    initialVertexCapacity: Int = DepthMeshNode.INITIAL_VERTEX_CAPACITY,
    initialIndexCapacity: Int = DepthMeshNode.INITIAL_INDEX_CAPACITY,
): BufferRebuildPlan {
    val newVertexCount = if (requestedVertexCount > allocatedVertexCount) {
        nextPowerOfTwo(max(requestedVertexCount, initialVertexCapacity))
    } else null
    val newIndexCount = if (requestedIndexCount > allocatedIndexCount) {
        nextPowerOfTwo(max(requestedIndexCount, initialIndexCapacity))
    } else null
    return BufferRebuildPlan(newVertexCount, newIndexCount)
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
