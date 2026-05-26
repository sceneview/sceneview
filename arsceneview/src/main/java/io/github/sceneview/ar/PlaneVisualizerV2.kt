package io.github.sceneview.ar

import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.IndexBuffer
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.Scene
import com.google.android.filament.VertexBuffer
import com.google.ar.core.Camera
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.arcore.buildPlaneDepthMeshGeometry
import io.github.sceneview.ar.arcore.depthImage
import io.github.sceneview.ar.arcore.rawDepthConfidenceImage
import io.github.sceneview.collision.Matrix
import io.github.sceneview.collision.TransformProvider
import io.github.sceneview.safeDestroyIndexBuffer
import io.github.sceneview.safeDestroyVertexBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Renders a single ARCore Plane using native Filament geometry — V2 implementation.
 *
 * **PR #2 status** ([#2203](https://github.com/sceneview/sceneview/issues/2203)): the
 * plane is no longer a flat polygon. When ARCore depth is available, the visible portion of
 * [plane]'s polygon is tessellated by sampling the depth image; each kept vertex is displaced
 * along the plane normal by the difference between the plane's expected depth and the
 * measured depth at that pixel. A floor visibly conforms to a rug, the lip of a step, a
 * slight slope.
 *
 * The depth path rebuilds at most every [DEPTH_REBUILD_INTERVAL_MS] ms (5 Hz). When depth
 * is unavailable — device unsupported, first frames after resume, raw frame not yet ready
 * — the visualizer falls back transparently to the V1 flat-polygon fan + boundary-feather
 * mesh. Never crashes, never blanks: a flat plane is always preferable to no plane.
 *
 * Both paths upload only the POSITION attribute. The shader recovers per-fragment normals
 * via Filament's `getWorldGeometricNormal()` (cross product of triangle edges in world
 * space), so a flat fallback plane reads as `(0, 1, 0)` naturally — no per-vertex normal
 * upload is needed for slope-aware shading at PR #2 fidelity. PRs #3/#4 may switch to
 * uploaded normals if smooth shading on slopes becomes desirable.
 *
 * Threading: every Filament JNI call must run on the render thread, same as V1.
 * [PlaneRendererV2.update] is called on that thread, so [setFrame] and [updatePlane]
 * inherit the right context — do not poke this class from a background coroutine.
 */
class PlaneVisualizerV2(
    private val engine: Engine,
    private val scene: Scene,
    private val plane: Plane
) : TransformProvider {

    companion object {
        /**
         * Minimum interval, in milliseconds, between two depth-driven mesh rebuilds.
         * 200 ms = 5 Hz — same rate as [io.github.sceneview.ar.node.DepthMeshNode], well
         * below ARCore's ~30 Hz frame delivery so the rebuild does not soak the budget.
         */
        const val DEPTH_REBUILD_INTERVAL_MS: Long = 200L

        // V2 buffer sizing — much larger than V1's polygon-only path because a depth grid
        // can carry ~880 verts for a 160×90 depth image at stride 4. Headroom for stride 2
        // (~3500 verts) is intentionally NOT covered: stride 4 is the established default,
        // and exceeding the cap drops the rebuild silently rather than crashing.
        private const val MAX_VERTS = 2048
        private const val MAX_INDICES = MAX_VERTS * 6

        private const val FLOAT_BYTES = 4
        private const val INT_BYTES = 4

        // POSITION only. Per-fragment normals come from `getWorldGeometricNormal()` in the
        // shader (a cross product of the triangle's world-space edges), so the vertex
        // buffer stays single-attribute and small.
        private const val POSITION_STRIDE = 3 * FLOAT_BYTES

        // ── V1-flat fallback constants (kept intact) ────────────────────────────────────
        private const val VERTS_PER_BOUNDARY_VERT = 2
        private const val FEATHER_LENGTH = 0.2f
        private const val FEATHER_SCALE = 0.2f
        private const val MAX_BOUNDARY_VERTS = 128
    }

    private val planeMatrix = Matrix()

    private var isPlaneAddedToScene = false
    private var isEnabled = true
    private var isShadowReceiver = false
    private var isVisible = false

    private var planeSubmeshMaterial: MaterialInstance? = null
    private var shadowSubmeshMaterial: MaterialInstance? = null

    private val entity = EntityManager.get().create()

    private val vertexBuffer: VertexBuffer = VertexBuffer.Builder()
        .vertexCount(MAX_VERTS)
        .bufferCount(1)
        .attribute(
            VertexBuffer.VertexAttribute.POSITION,
            0,
            VertexBuffer.AttributeType.FLOAT3,
            0,
            POSITION_STRIDE,
        )
        .build(engine)

    private val indexBuffer: IndexBuffer = IndexBuffer.Builder()
        .indexCount(MAX_INDICES)
        .bufferType(IndexBuffer.Builder.IndexType.UINT)
        .build(engine)

    // Reusable direct buffers. Allocating a fresh ByteBuffer per frame is the canonical
    // pattern for Filament uploads (see DepthMeshNode #1841 — Filament's setBufferAt copies
    // asynchronously so the buffer cannot be reused). We cache one of each size to amortise
    // the allocation across frames *within the same rebuild path*; the contents are copied
    // by Filament before the next rebuild can clobber them because the rebuild itself runs
    // on the render thread and is gated by the 200 ms interval.
    private val vertexData: ByteBuffer =
        ByteBuffer.allocateDirect(MAX_VERTS * POSITION_STRIDE).order(ByteOrder.nativeOrder())
    private val indexData: ByteBuffer =
        ByteBuffer.allocateDirect(MAX_INDICES * INT_BYTES).order(ByteOrder.nativeOrder())

    private var builtPrimitiveCount = 0
    private var currentVertexCount = 0
    private var currentIndexCount = 0
    private var lastDepthRebuildMs: Long = Long.MIN_VALUE

    // Frame + camera handed in by PlaneRendererV2.update before each updatePlane call.
    private var currentFrame: Frame? = null
    private var currentCamera: Camera? = null

    // Cached scratch matrices — building these per frame would add GC pressure.
    private val cameraToPlaneLocal = FloatArray(16)
    private val cameraPoseMatrix = FloatArray(16)
    private val planeInvMatrix = FloatArray(16)

    fun setEnabled(enabled: Boolean) {
        if (isEnabled != enabled) {
            isEnabled = enabled
            updatePlane()
        }
    }

    fun setShadowReceiver(shadowReceiver: Boolean) {
        if (isShadowReceiver != shadowReceiver) {
            isShadowReceiver = shadowReceiver
            updatePlane()
        }
    }

    fun setVisible(visible: Boolean) {
        if (isVisible != visible) {
            isVisible = visible
            updatePlane()
        }
    }

    fun setPlaneMaterial(materialInstance: MaterialInstance) {
        planeSubmeshMaterial = materialInstance
        if (builtPrimitiveCount > 0) updateRenderable()
    }

    fun setShadowMaterial(materialInstance: MaterialInstance) {
        shadowSubmeshMaterial = materialInstance
        if (builtPrimitiveCount > 0) updateRenderable()
    }

    /**
     * Threads the latest [Frame] + [Camera] through to the depth-driven rebuild path.
     * Called by [io.github.sceneview.ar.scene.PlaneRendererV2.update] before each
     * [updatePlane]. Either argument may be null — the visualizer then falls back to the
     * V1 flat-polygon mesh transparently.
     */
    fun setFrame(frame: Frame?, camera: Camera?) {
        currentFrame = frame
        currentCamera = camera
    }

    override fun getTransformationMatrix(): Matrix = planeMatrix

    fun updatePlane() {
        if (!isEnabled || (!isVisible && !isShadowReceiver)) {
            removePlaneFromScene()
            return
        }
        if (plane.trackingState != TrackingState.TRACKING) {
            removePlaneFromScene()
            return
        }

        plane.centerPose.toMatrix(planeMatrix.data, 0)

        val now = System.currentTimeMillis()
        val depthMeshRebuilt = tryRebuildDepthMesh(now)
        if (depthMeshRebuilt) lastDepthRebuildMs = now

        if (!depthMeshRebuilt && currentVertexCount == 0) {
            // No fresh depth mesh AND nothing already on screen — try the flat fallback.
            if (!rebuildFlatMesh()) {
                removePlaneFromScene()
                return
            }
        }

        updateRenderable()
        addPlaneToScene()
    }

    /**
     * Eligibility + rate-limit + try the depth path. Returns true when the depth-driven
     * mesh was actually rebuilt and uploaded this call, false in every other case
     * (tracking lost, depth unavailable, interval not yet elapsed, polygon empty, etc).
     * The caller falls back to [rebuildFlatMesh] when this returns false AND the buffer
     * is empty.
     */
    private fun tryRebuildDepthMesh(now: Long): Boolean {
        val frame = currentFrame ?: return false
        val camera = currentCamera ?: return false
        if (camera.trackingState != TrackingState.TRACKING) return false
        if (now - lastDepthRebuildMs < DEPTH_REBUILD_INTERVAL_MS) return false
        return rebuildDepthMesh(frame, camera)
    }

    /**
     * Builds a depth-driven plane-clipped mesh for this frame and uploads it to Filament.
     * Returns true on success, false when depth is unavailable / unusable so the caller
     * can fall back to [rebuildFlatMesh]. Catches Throwable defensively: a depth-API
     * failure must never cascade into a render-thread crash — PR #2's hard rule is that
     * V2 always degrades to V1's flat polygon, never to a stack trace.
     */
    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    private fun rebuildDepthMesh(frame: Frame, camera: Camera): Boolean {
        val depthImage = frame.depthImage() ?: return false
        try {
            val intrinsics = computeScaledIntrinsics(camera, depthImage.width, depthImage.height)
                ?: return false
            val polygon = copyPolygonForClipping() ?: return false
            computeCameraToPlaneLocal(camera.pose)
            return buildAndUploadDepthMesh(frame, depthImage, intrinsics, polygon)
        } catch (e: Throwable) {
            android.util.Log.d("SceneView", "PlaneVisualizerV2 depth rebuild failed; falling back to flat", e)
            return false
        } finally {
            depthImage.close()
        }
    }

    private fun buildAndUploadDepthMesh(
        frame: Frame,
        depthImage: android.media.Image,
        intrinsics: ScaledIntrinsics,
        polygon: FloatArray,
    ): Boolean {
        val depthPlane = depthImage.planes[0]
        val rowStrideShorts = depthPlane.rowStride / 2
        val depthBuffer = depthPlane.buffer.order(ByteOrder.nativeOrder()).asShortBuffer()
        val confidenceImage = frame.rawDepthConfidenceImage()
        try {
            val geometry = buildPlaneDepthMeshGeometry(
                depthBuffer = depthBuffer,
                depthWidth = depthImage.width,
                depthHeight = depthImage.height,
                rowStrideShorts = rowStrideShorts,
                fx = intrinsics.fx, fy = intrinsics.fy,
                cx = intrinsics.cx, cy = intrinsics.cy,
                cameraToPlaneLocal = cameraToPlaneLocal,
                polygon = polygon,
                confidenceBuffer = confidenceImage?.planes?.get(0)
                    ?.buffer?.order(ByteOrder.nativeOrder()),
                confidenceRowStrideBytes = confidenceImage?.planes?.get(0)?.rowStride ?: 0,
                confidenceWidth = confidenceImage?.width ?: 0,
                confidenceHeight = confidenceImage?.height ?: 0,
            )
            if (geometry.vertexCount > MAX_VERTS ||
                geometry.indices.size > MAX_INDICES ||
                geometry.triangleCount == 0
            ) {
                // Over-cap blobs would corrupt Filament buffers; an empty mesh would render
                // nothing. Either way fall back rather than upload broken data.
                return false
            }
            uploadGeometry(
                positions = geometry.positions,
                normals = geometry.normals,
                indices = geometry.indices,
                vertexCount = geometry.vertexCount,
            )
            return true
        } finally {
            confidenceImage?.close()
        }
    }

    /**
     * Scales ARCore camera intrinsics (reported for the full-resolution CPU image) into
     * the depth image's coordinate frame. Returns null when ARCore reports degenerate
     * intrinsics (zero focal length or zero image dimensions, #1812) — propagating null
     * triggers the flat-fallback rather than poisoning every depth-driven vertex with
     * `Inf` coordinates.
     */
    private fun computeScaledIntrinsics(
        camera: Camera,
        depthWidth: Int,
        depthHeight: Int,
    ): ScaledIntrinsics? {
        val intrinsics = camera.imageIntrinsics
        val intrinsicWidth = intrinsics.imageDimensions[0]
        val intrinsicHeight = intrinsics.imageDimensions[1]
        val rawFx = intrinsics.focalLength[0]
        val rawFy = intrinsics.focalLength[1]
        if (intrinsicWidth <= 0 || intrinsicHeight <= 0) return null
        if (rawFx == 0f || rawFy == 0f) return null
        val scaleX = depthWidth / intrinsicWidth.toFloat()
        val scaleY = depthHeight / intrinsicHeight.toFloat()
        return ScaledIntrinsics(
            fx = rawFx * scaleX,
            fy = rawFy * scaleY,
            cx = intrinsics.principalPoint[0] * scaleX,
            cy = intrinsics.principalPoint[1] * scaleY,
        )
    }

    /**
     * Snapshots the plane polygon into a freshly-allocated `[x0, z0, x1, z1, ...]`
     * FloatArray we own — ARCore's FloatBuffer is recycled across frames so the geometry
     * helper cannot retain it. Returns null when the polygon is still empty (the plane
     * has not yet grown its boundary).
     */
    private fun copyPolygonForClipping(): FloatArray? {
        val polygonBuffer = plane.polygon
        polygonBuffer.rewind()
        if (polygonBuffer.remaining() < 6) return null // <3 points → can't clip
        val polygon = FloatArray(polygonBuffer.remaining())
        polygonBuffer.get(polygon)
        return polygon
    }

    /**
     * V1's fan + boundary-strip geometry — unchanged math, plus a `(0, 1, 0)` normal for
     * every vertex so the V2 shader's slope shading still produces a flat brightness.
     *
     * Kept as the fallback because: ARCore depth is opt-in, not every device supports it,
     * and the first few frames after [Frame.acquireDepthImage16Bits] is invoked typically
     * throw `NotYetAvailableException`. A flat plane on the floor still beats no plane at
     * all.
     */
    private fun rebuildFlatMesh(): Boolean {
        val boundary = plane.polygon
        boundary.rewind()
        val boundaryVertexCount = boundary.limit() / 2
        if (boundaryVertexCount == 0 || boundaryVertexCount > MAX_BOUNDARY_VERTS) return false

        val numVerts = boundaryVertexCount * VERTS_PER_BOUNDARY_VERT
        val numIndices = (boundaryVertexCount * 6) + ((boundaryVertexCount - 2) * 3)
        if (numVerts > MAX_VERTS || numIndices > MAX_INDICES) return false

        val positions = FloatArray(numVerts * 3)
        val normals = FloatArray(numVerts * 3)
        val indices = IntArray(numIndices)

        // Pre-fill every normal with plane-local up so the shader's slope read is sane.
        var ni = 1
        while (ni < normals.size) {
            normals[ni] = 1f
            ni += 3
        }

        writeFlatVertices(boundary, positions)
        writeFlatIndices(boundaryVertexCount, indices)

        uploadGeometry(positions = positions, normals = normals, indices = indices, vertexCount = numVerts)
        return true
    }

    /**
     * V1-style vertex emission: an outer boundary ring (the raw plane polygon at y=0)
     * followed by a same-count inner ring scaled inward by [FEATHER_SCALE]. Both rings
     * sit at y=0 in V2 (V1 used y=1 on the inner ring to carry the per-vertex feather
     * via `texCoordsAlpha.z` — V2's shader cannot abuse Y the same way without breaking
     * the depth-path math, so the feathered Y signal is dropped). [positions] must be
     * sized for `boundaryVertexCount * VERTS_PER_BOUNDARY_VERT` vertices.
     */
    private fun writeFlatVertices(
        boundary: java.nio.FloatBuffer,
        positions: FloatArray,
    ) {
        boundary.rewind()
        var write = 0
        while (boundary.hasRemaining()) {
            val x = boundary.get()
            val z = boundary.get()
            positions[write * 3] = x
            positions[write * 3 + 1] = 0f
            positions[write * 3 + 2] = z
            write++
        }
        boundary.rewind()
        while (boundary.hasRemaining()) {
            val x = boundary.get()
            val z = boundary.get()
            val magnitude = Math.hypot(x.toDouble(), z.toDouble()).toFloat()
            val scale = if (magnitude != 0f) {
                1f - minOf(FEATHER_LENGTH / magnitude, FEATHER_SCALE)
            } else {
                1f - FEATHER_SCALE
            }
            positions[write * 3] = x * scale
            positions[write * 3 + 1] = 0f
            positions[write * 3 + 2] = z * scale
            write++
        }
    }

    /**
     * V1-style index emission: an interior fan over the inner ring + a boundary strip
     * stitching outer ring to inner ring. [indices] must be sized
     * `boundaryVertexCount * 6 + (boundaryVertexCount - 2) * 3`.
     */
    private fun writeFlatIndices(boundaryVertexCount: Int, indices: IntArray) {
        val firstInner = boundaryVertexCount
        var idx = 0
        for (i in 0 until boundaryVertexCount - 2) {
            indices[idx++] = firstInner
            indices[idx++] = firstInner + i + 1
            indices[idx++] = firstInner + i + 2
        }
        for (i in 0 until boundaryVertexCount) {
            val o1 = i
            val o2 = (i + 1) % boundaryVertexCount
            val n1 = firstInner + i
            val n2 = firstInner + (i + 1) % boundaryVertexCount
            indices[idx++] = o1
            indices[idx++] = o2
            indices[idx++] = n1
            indices[idx++] = n1
            indices[idx++] = o2
            indices[idx++] = n2
        }
    }

    private fun uploadGeometry(
        positions: FloatArray,
        @Suppress("UNUSED_PARAMETER") normals: FloatArray,
        indices: IntArray,
        vertexCount: Int,
    ) {
        // POSITION-only upload. `normals` is accepted for API symmetry with the geometry
        // helper and to keep the door open for PRs #3/#4 (uploaded per-vertex normals for
        // smooth slope shading on glossy floors). At PR #2 fidelity the shader's
        // `getWorldGeometricNormal()` is enough — see class KDoc.
        vertexData.clear()
        val floats = vertexData.asFloatBuffer()
        floats.put(positions, 0, vertexCount * 3)
        vertexData.rewind()
        vertexBuffer.setBufferAt(engine, 0, vertexData, 0, vertexCount * POSITION_STRIDE)

        indexData.clear()
        val ints = indexData.asIntBuffer()
        ints.put(indices)
        indexData.rewind()
        indexBuffer.setBuffer(engine, indexData, 0, indices.size * INT_BYTES)

        currentVertexCount = vertexCount
        currentIndexCount = indices.size
    }

    /**
     * Computes the column-major 4x4 transforming camera-space points into plane-local space:
     *
     *     cameraToPlaneLocal = plane.centerPose.inverse() ⊗ camera.pose
     *
     * ARCore [Pose.toMatrix] writes a 4x4 column-major into a float[16], which is the
     * convention every consumer in this file uses.
     */
    private fun computeCameraToPlaneLocal(cameraPose: Pose) {
        cameraPose.toMatrix(cameraPoseMatrix, 0)
        plane.centerPose.inverse().toMatrix(planeInvMatrix, 0)
        multiplyMatrix4x4(planeInvMatrix, cameraPoseMatrix, cameraToPlaneLocal)
    }

    private fun updateRenderable() {
        val primitives = buildList {
            if (isVisible && planeSubmeshMaterial != null) add(planeSubmeshMaterial!!)
            if (isShadowReceiver && shadowSubmeshMaterial != null) add(shadowSubmeshMaterial!!)
        }

        if (primitives.isEmpty() || currentIndexCount == 0) {
            removePlaneFromScene()
            return
        }

        val rm = engine.renderableManager

        if (builtPrimitiveCount != primitives.size) {
            if (builtPrimitiveCount > 0) rm.destroy(entity)
            RenderableManager.Builder(primitives.size)
                .castShadows(false)
                .receiveShadows(true)
                .culling(false)
                .boundingBox(com.google.android.filament.Box(0f, 0f, 0f, 10f, 0.5f, 10f))
                .apply {
                    primitives.forEachIndexed { idx, mat ->
                        geometry(
                            idx,
                            RenderableManager.PrimitiveType.TRIANGLES,
                            vertexBuffer,
                            indexBuffer,
                            0,
                            currentIndexCount,
                        )
                        material(idx, mat)
                        blendOrder(idx, idx)
                    }
                }
                .build(engine, entity)
            builtPrimitiveCount = primitives.size
        } else {
            val inst = rm.getInstance(entity)
            primitives.forEachIndexed { idx, mat ->
                rm.setGeometryAt(
                    inst,
                    idx,
                    RenderableManager.PrimitiveType.TRIANGLES,
                    vertexBuffer,
                    indexBuffer,
                    0,
                    currentIndexCount,
                )
                rm.setMaterialInstanceAt(inst, idx, mat)
            }
        }

        val transformManager = engine.transformManager
        val transformInst = transformManager.getInstance(entity)
        transformManager.setTransform(transformInst, planeMatrix.data)
    }

    fun destroy() {
        removePlaneFromScene()
        if (builtPrimitiveCount > 0) engine.renderableManager.destroy(entity)
        engine.safeDestroyVertexBuffer(vertexBuffer)
        engine.safeDestroyIndexBuffer(indexBuffer)
        EntityManager.get().destroy(entity)
    }

    private fun addPlaneToScene() {
        if (!isPlaneAddedToScene) {
            scene.addEntity(entity)
            isPlaneAddedToScene = true
        }
    }

    private fun removePlaneFromScene() {
        if (isPlaneAddedToScene) {
            scene.removeEntity(entity)
            isPlaneAddedToScene = false
        }
    }
}

/**
 * `a` and `b` are column-major 4x4 matrices (length-16 FloatArrays). Writes `out = a · b`.
 * Used by [PlaneVisualizerV2] to compose plane-local-from-world with world-from-camera into
 * a single matrix the geometry helper applies to every camera-space sample.
 *
 * Pure top-level helper — easier to test than a private method on the visualizer.
 */
internal fun multiplyMatrix4x4(a: FloatArray, b: FloatArray, out: FloatArray) {
    require(a.size == 16 && b.size == 16 && out.size == 16) {
        "All three matrices must be 4x4 (size 16)"
    }
    for (col in 0..3) {
        for (row in 0..3) {
            var sum = 0f
            for (k in 0..3) {
                sum += a[k * 4 + row] * b[col * 4 + k]
            }
            out[col * 4 + row] = sum
        }
    }
}

/**
 * Resolution-scaled ARCore camera intrinsics — the same `(fx, fy, cx, cy)` quartet
 * [io.github.sceneview.ar.arcore.unprojectDepthPixel] takes, with the scaling from
 * full-resolution image to depth-image already applied. Kept top-level + `internal` so
 * the scaling math is unit-testable; the visualizer fills it once per frame.
 */
internal data class ScaledIntrinsics(
    val fx: Float,
    val fy: Float,
    val cx: Float,
    val cy: Float,
)
