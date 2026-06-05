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
import dev.romainguy.kotlin.math.Float3
import io.github.sceneview.ar.arcore.buildPlaneDepthMeshGeometry
import io.github.sceneview.ar.arcore.depthImage
import io.github.sceneview.ar.arcore.rawDepthConfidenceImage
import io.github.sceneview.ar.scene.PlaneRendererV2
import io.github.sceneview.ar.scene.planeMaterialPresetFor
import io.github.sceneview.collision.Matrix
import io.github.sceneview.collision.TransformProvider
import io.github.sceneview.material.setParameter
import io.github.sceneview.math.normalToTangent
import io.github.sceneview.safeDestroyIndexBuffer
import io.github.sceneview.safeDestroyVertexBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Renders a single ARCore Plane using native Filament geometry — V2 implementation.
 *
 * **V2 is the default plane renderer as of this release.** See
 * [#2203](https://github.com/sceneview/sceneview/issues/2203) for the umbrella that delivered
 * it. The legacy V1 [io.github.sceneview.ar.PlaneVisualizer] remains available behind
 * `ARSceneView(planeRendererVersion = PlaneRendererBase.Version.V1)` for one release cycle
 * and is now `@Deprecated`.
 *
 * **PR #3 status** ([#2203](https://github.com/sceneview/sceneview/issues/2203)): the V2
 * plane is now a real PBR surface lit by the real room. Four user-visible effects:
 *
 * 1. **PBR lit shading** — the V2 material switched from `shadingModel: unlit` (PR #2) to
 *    `shadingModel: lit`. The fragment stage assigns `metallic` / `roughness` /
 *    `reflectance` so Filament's PBR pipeline lights the plane the same way it lights any
 *    other lit `MaterialInstance`.
 * 2. **HDR cubemap reflection** — `scene.indirectLight` is fed each frame by
 *    [io.github.sceneview.ar.light.LightEstimator] from ARCore's
 *    `acquireEnvironmentalHdrCubeMap()` + `environmentalHdrAmbientSphericalHarmonics`. The
 *    lit plane samples that IBL automatically — a glossy floor visibly reflects the
 *    ceiling lights and window glow.
 * 3. **Scan-in ring** — when a plane is first detected, a thin bright ring expands
 *    outward from the polygon centroid over 800 ms. First-contact magic. Driven by the
 *    `scanProgress` + `scanPlaneRadius` material uniforms updated each frame.
 * 4. **Reflection fade-in** — `reflectionFadeIn` ramps from 0 to 1 over the first second
 *    of the plane's life. The shader modulates `metallic` + `reflectance` by it so the
 *    PBR contribution brightens up smoothly rather than snapping in when ARCore's HDR
 *    cubemap estimate first stabilises.
 *
 * The depth path now uploads **two** vertex attributes — `POSITION` (PR #2) and the new
 * `TANGENTS` quaternion frame derived from the smooth per-vertex normals already
 * computed by [io.github.sceneview.ar.arcore.PlaneDepthMeshGeometry]. PBR specular
 * highlights wrap correctly across bumps and slopes instead of looking faceted. The flat
 * fallback fills the tangent buffer with the quaternion for plane-local `(0, 1, 0)` so a
 * flat plane lights like a level surface.
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

        /**
         * Duration, in milliseconds, of the scan-in ring expansion played the first time
         * a plane appears (PR #3). 800 ms matches the value validated with the user
         * 2026-05-25 — long enough to read as a deliberate animation, short enough that
         * it does not delay the moment the plane becomes useful for interaction.
         */
        const val SCAN_IN_DURATION_MS: Long = 800L

        /**
         * Duration, in milliseconds, of the IBL reflection fade-in (PR #3). ~1 s gives
         * ARCore's HDR cubemap estimate time to stabilise so the PBR pipeline ramps from
         * diffuse-only to fully lit smoothly — the surface never "snaps" the reflection
         * on once the cubemap first arrives.
         */
        const val REFLECTION_FADE_IN_MS: Long = 1000L

        // V2 buffer sizing — much larger than V1's polygon-only path because a depth grid
        // can carry ~880 verts for a 160×90 depth image at stride 4. Headroom for stride 2
        // (~3500 verts) is intentionally NOT covered: stride 4 is the established default,
        // and exceeding the cap drops the rebuild silently rather than crashing.
        private const val MAX_VERTS = 2048
        private const val MAX_INDICES = MAX_VERTS * 6

        private const val FLOAT_BYTES = 4
        private const val INT_BYTES = 4

        // PR #3 ships per-vertex POSITION + TANGENTS. Filament's PBR pipeline uses
        // TANGENTS (a quaternion encoding tangent/bitangent/normal) as the per-vertex
        // normal input — there is no separate NORMAL attribute in the enum. We compute
        // the quaternion from each vertex's smooth normal via `normalToTangent(...)`
        // (shared math from sceneview-core) and upload it as 4 floats per vertex.
        private const val POSITION_STRIDE = 3 * FLOAT_BYTES
        private const val TANGENT_STRIDE = 4 * FLOAT_BYTES

        // PR #3 separates POSITION and TANGENTS into two backing buffers (bufferCount=2)
        // — same pattern as `sceneview/.../geometries/Geometry.kt`. Keeps each attribute
        // upload self-contained and matches the canonical sceneview vertex layout, so a
        // future maintainer extending the V2 buffer with UV0/COLOR can follow the same
        // structure without re-interleaving.
        private const val BUFFER_INDEX_POSITION = 0
        private const val BUFFER_INDEX_TANGENT = 1

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

    /**
     * Cached `TransformManager` instance handle for [entity].
     *
     * `0` means "not yet looked up". The plane entity and its transform component are created
     * once and live for the visualizer's lifetime (rebuilding the renderable in place does not
     * touch the transform component), so the handle is stable — we pay the `getInstance` JNI
     * thunk once instead of on every update tick. Mirrors the lazy-once caching #2280 applied
     * to `Node.transformInstance` (#2269, #2287). A `0` result is never frozen.
     */
    private var transformInstance: Int = 0

    private val vertexBuffer: VertexBuffer = VertexBuffer.Builder()
        .vertexCount(MAX_VERTS)
        .bufferCount(2)
        .attribute(
            VertexBuffer.VertexAttribute.POSITION,
            BUFFER_INDEX_POSITION,
            VertexBuffer.AttributeType.FLOAT3,
            0,
            POSITION_STRIDE,
        )
        .attribute(
            VertexBuffer.VertexAttribute.TANGENTS,
            BUFFER_INDEX_TANGENT,
            VertexBuffer.AttributeType.FLOAT4,
            0,
            TANGENT_STRIDE,
        )
        // Quaternion components live in [-1, 1] but Filament's lit pipeline does NOT
        // expect TANGENTS to be `.normalized(...)`-flagged when uploaded as FLOAT4
        // (the flag is for integer formats). Matches `Geometry.kt`'s float4 branch.
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
    private val positionData: ByteBuffer =
        ByteBuffer.allocateDirect(MAX_VERTS * POSITION_STRIDE).order(ByteOrder.nativeOrder())
    private val tangentData: ByteBuffer =
        ByteBuffer.allocateDirect(MAX_VERTS * TANGENT_STRIDE).order(ByteOrder.nativeOrder())
    private val indexData: ByteBuffer =
        ByteBuffer.allocateDirect(MAX_INDICES * INT_BYTES).order(ByteOrder.nativeOrder())

    // Reusable scratch FloatArray for the per-vertex tangent quaternion encoding. Sized
    // for MAX_VERTS so a worst-case depth rebuild never re-allocates. Filled in
    // `uploadGeometry` from the per-vertex normals via `normalToTangent(...)`.
    private val tangentScratch = FloatArray(MAX_VERTS * 4)

    private var builtPrimitiveCount = 0

    /**
     * Reusable 2-element backing list for [updateRenderable]'s primitive selection (#2328 / #2402).
     *
     * V2 is the default renderer, so this per-frame path runs often. Building the primitive list
     * with a fresh `buildList { }` each call churned one short-lived list per plane per frame; the
     * selection is recomputed into this single reused list every call (cleared first via
     * [selectPlanePrimitives]), so no cached state can go stale — only the allocation is removed.
     * Single-threaded (main/render thread), so the shared field is race-free.
     */
    private val primitivesScratch = ArrayList<MaterialInstance>(2)
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

    // ── PR #3 scan-in animation state ───────────────────────────────────────────────
    // First wall-clock timestamp (System.nanoTime) at which this plane was observed
    // TRACKING. The scan-in + reflection fade-in are driven by elapsed wall-clock since
    // this moment — NOT by ARCore frame timestamps, because frame timestamps pause when
    // the surface is occluded and would freeze the animation mid-way. `null` until the
    // first tracking frame; once set it never resets — a subsumed plane is destroyed and
    // a brand-new visualizer instance picks up its own `firstDetectedTimeNanos`.
    private var firstDetectedTimeNanos: Long? = null

    // Max plane-local distance from origin to any polygon vertex, refreshed when the
    // polygon hash changes (cheap O(boundaryVerts)). Sets `scanPlaneRadius` so the ring
    // sweeps from the centroid out to the furthest edge of the actual detected polygon.
    private var scanPlaneRadius: Float = 0f

    // Hash of the last polygon used for the scanPlaneRadius computation — lets us skip
    // the O(n) recompute when the polygon hasn't moved. ARCore reuses the FloatBuffer so
    // we hash the raw float bytes via vertex count + a couple of corner samples; a full
    // hash per frame would defeat the purpose.
    private var lastPolygonHash: Int = 0

    // Latched `true` once both animation phases have reached their final value, so we
    // can skip the per-frame `setParameter` calls (avoid JNI churn for every frame after
    // the animation completes — typically the entire steady-state lifetime of the plane).
    private var animationIdle: Boolean = false

    // ── PR #4 type-aware shading state ──────────────────────────────────────────────
    // Last `plane.type` value the per-instance material was configured for. `null` means
    // the preset has not been applied yet (first call to `applyPlaneTypePreset()` will
    // push it). On every `updatePlane()` we compare against `plane.type` — if ARCore
    // re-classified the plane (rare, but legal: a horizontal plane re-merged with a
    // vertical neighbour can flip), we re-push the 3 setParameter calls. The cost is
    // 3 JNI calls only on change — steady-state lifetime is free.
    private var lastAppliedPlaneType: Plane.Type? = null

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
        // PR #4 — the type-aware preset lives on this MaterialInstance, so a fresh
        // instance starts at the shared `planeMaterial` defaults. Apply once here so
        // the first render carries the right values even if `updatePlane()` hasn't
        // fired yet (e.g. `setShadowReceiver(true)` before the first frame). Pass
        // `null` as the cached type to force the apply on the new instance.
        lastAppliedPlaneType = applyTypePresetIfChanged(
            instance = materialInstance,
            type = plane.type,
            lastAppliedType = null,
        )
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

        // PR #3: latch the first-tracking wall-clock time and recompute the polygon
        // radius if it shifted (ARCore grows planes as more geometry is observed). Both
        // are cheap — fall through to the mesh rebuild as before.
        if (firstDetectedTimeNanos == null) {
            firstDetectedTimeNanos = System.nanoTime()
        }
        refreshScanRadius()

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

        // PR #4: re-apply the per-`plane.type` preset whenever ARCore re-classifies the
        // plane (rare but legal — e.g. a horizontal plane re-merged with a vertical
        // neighbour). `applyTypePresetIfChanged` is a no-op when the type hasn't
        // changed, so the steady-state cost is one Boolean compare per frame.
        planeSubmeshMaterial?.let { instance ->
            lastAppliedPlaneType = applyTypePresetIfChanged(
                instance = instance,
                type = plane.type,
                lastAppliedType = lastAppliedPlaneType,
            )
        }

        // PR #3: push the scan-in + fade-in uniforms BEFORE updateRenderable so the
        // first frame the plane is added to the scene already carries the right values
        // (scanProgress ≈ 0, reflectionFadeIn ≈ 0). Without this the ring would
        // not appear on the very first frame of the plane's life.
        pushAnimationUniforms()
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
     * Recomputes [scanPlaneRadius] from the current plane polygon — the max plane-local
     * distance from origin to any polygon vertex. The scan-in ring sweeps from the
     * centroid out to this radius. Skips when the polygon hash has not changed since
     * the last call (cheap optimisation — ARCore grows planes monotonically, so the
     * radius typically converges within the first second).
     *
     * Public surface stays unchanged: only [scanPlaneRadius] is updated.
     */
    private fun refreshScanRadius() {
        val polygonBuffer = plane.polygon
        polygonBuffer.rewind()
        val vertexCount = polygonBuffer.remaining() / 2
        if (vertexCount == 0) {
            scanPlaneRadius = 0f
            lastPolygonHash = 0
            return
        }
        // Cheap polygon hash: count + first + last corners. ARCore reuses the underlying
        // FloatBuffer instance, so reading two corners + the count gives us a stable
        // "did this polygon shift?" signal without scanning every vertex on every frame.
        val firstX = polygonBuffer.get(0)
        val firstZ = polygonBuffer.get(1)
        val lastX = polygonBuffer.get(vertexCount * 2 - 2)
        val lastZ = polygonBuffer.get(vertexCount * 2 - 1)
        val hash = vertexCount
            .let { 31 * it + firstX.toRawBits() }
            .let { 31 * it + firstZ.toRawBits() }
            .let { 31 * it + lastX.toRawBits() }
            .let { 31 * it + lastZ.toRawBits() }
        if (hash == lastPolygonHash && scanPlaneRadius > 0f) return
        lastPolygonHash = hash
        scanPlaneRadius = computeScanRadius(polygonBuffer)
    }

    /**
     * Pushes [scanProgress] + [reflectionFadeIn] + [scanPlaneRadius] uniforms onto the
     * per-instance plane material. Bails after both animations have reached their
     * terminal value to avoid hammering JNI for every frame for the entire steady-state
     * lifetime of the plane.
     */
    private fun pushAnimationUniforms() {
        if (animationIdle) return
        val instance = planeSubmeshMaterial ?: return
        val startNanos = firstDetectedTimeNanos ?: return
        val elapsedNanos = System.nanoTime() - startNanos
        val scanProgress = computeScanProgress(elapsedNanos)
        val reflectionFadeIn = computeReflectionFadeIn(elapsedNanos)

        instance.setParameter("scanProgress", scanProgress)
        instance.setParameter("reflectionFadeIn", reflectionFadeIn)
        instance.setParameter("scanPlaneRadius", scanPlaneRadius)

        if (scanProgress >= 1f && reflectionFadeIn >= 1f) {
            // Latch one final time to make absolutely sure the steady-state values are
            // on the GPU, then stop pushing.
            animationIdle = true
        }
    }

    /**
     * V1's fan + boundary-strip geometry — unchanged math, plus a `(0, 1, 0)` normal for
     * every vertex so the V2 lit shader sees a level surface.
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

        // Pre-fill every normal with plane-local up so the lit shader reads as flat.
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
        normals: FloatArray,
        indices: IntArray,
        vertexCount: Int,
    ) {
        // PR #3 ships per-vertex POSITION and TANGENTS. Filament's PBR pipeline reads
        // TANGENTS (an xyzw quaternion encoding the tangent/bitangent/normal frame) as
        // the per-vertex normal input — there is no separate NORMAL slot in the
        // VertexAttribute enum (see #2203 PR #3 brief). Encoding the normals via
        // `normalToTangent(...)` matches the canonical sceneview vertex layout in
        // `sceneview/.../geometries/Geometry.kt:227`.
        positionData.clear()
        val positionFloats = positionData.asFloatBuffer()
        positionFloats.put(positions, 0, vertexCount * 3)
        positionData.rewind()
        vertexBuffer.setBufferAt(
            engine, BUFFER_INDEX_POSITION, positionData, 0, vertexCount * POSITION_STRIDE
        )

        // Encode each smooth normal as a quaternion tangent frame into `tangentScratch`,
        // then upload as FLOAT4. The encoded frame survives Filament's interpolation
        // gracefully because quaternions are renormalised in the lit pipeline.
        encodeTangents(normals, vertexCount, tangentScratch)
        tangentData.clear()
        val tangentFloats = tangentData.asFloatBuffer()
        tangentFloats.put(tangentScratch, 0, vertexCount * 4)
        tangentData.rewind()
        vertexBuffer.setBufferAt(
            engine, BUFFER_INDEX_TANGENT, tangentData, 0, vertexCount * TANGENT_STRIDE
        )

        indexData.clear()
        val ints = indexData.asIntBuffer()
        ints.put(indices)
        indexData.rewind()
        indexBuffer.setBuffer(engine, indexData, 0, indices.size * INT_BYTES)

        currentVertexCount = vertexCount
        currentIndexCount = indices.size
    }

    /**
     * Encodes [vertexCount] plane-local unit normals from [normals] into [out] as
     * xyzw quaternion tangent frames (4 floats per vertex) using the same
     * `normalToTangent` math the rest of the codebase uses for static geometry.
     *
     * `out` must be at least `vertexCount * 4` floats. The first 3 floats of each
     * triplet in [normals] are interpreted as the (x, y, z) unit normal; any extras
     * are ignored. A zero-length normal (degenerate input) falls back to plane-local
     * `(0, 1, 0)` so the lit shader never sees NaN — matching the
     * `buildPlaneDepthMeshGeometry` documented fallback.
     */
    private fun encodeTangents(
        normals: FloatArray,
        vertexCount: Int,
        out: FloatArray,
    ) {
        var src = 0
        var dst = 0
        repeat(vertexCount) {
            val nx = normals[src]
            val ny = normals[src + 1]
            val nz = normals[src + 2]
            val lenSq = nx * nx + ny * ny + nz * nz
            val normal = if (lenSq > 1e-8f) Float3(nx, ny, nz) else Float3(0f, 1f, 0f)
            val q = normalToTangent(normal)
            out[dst] = q.x
            out[dst + 1] = q.y
            out[dst + 2] = q.z
            out[dst + 3] = q.w
            src += 3
            dst += 4
        }
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
        val primitives = selectPlanePrimitives(
            isVisible = isVisible,
            planeMaterial = planeSubmeshMaterial,
            isShadowReceiver = isShadowReceiver,
            shadowMaterial = shadowSubmeshMaterial,
            out = primitivesScratch
        )

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

        // The instance handle is stable for the entity's lifetime, so it's looked up once and
        // cached (#2287). A 0 result (no transform component yet) is retried on the next tick.
        val transformManager = engine.transformManager
        if (transformInstance == 0) {
            transformInstance = transformManager.getInstance(entity)
        }
        transformManager.setTransform(transformInstance, planeMatrix.data)
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
 * Returns the max plane-local distance from origin to any vertex of [polygon]. ARCore
 * delivers the polygon as interleaved `[x0, z0, x1, z1, ...]` floats in plane-local
 * coordinates (Y is the plane normal). The visualizer's scan-in ring sweeps from the
 * polygon centroid (= plane origin) out to this radius, so the ring reaches the actual
 * boundary of the detected surface — not an arbitrary constant.
 *
 * Empty buffer → 0 (no polygon to scan). Single vertex → its distance. The buffer's
 * position is restored before return.
 *
 * Pure top-level helper, `internal` so [PlaneVisualizerV2Test] can call it without
 * spinning up an Engine or a Plane.
 */
internal fun computeScanRadius(polygon: FloatBuffer): Float {
    val savedPos = polygon.position()
    polygon.rewind()
    val vertexCount = polygon.remaining() / 2
    if (vertexCount == 0) {
        polygon.position(savedPos)
        return 0f
    }
    var maxSq = 0f
    for (i in 0 until vertexCount) {
        val x = polygon.get(i * 2)
        val z = polygon.get(i * 2 + 1)
        val dSq = x * x + z * z
        if (dSq > maxSq) maxSq = dSq
    }
    polygon.position(savedPos)
    return kotlin.math.sqrt(maxSq)
}

/**
 * Returns the scan-in animation progress in `[0, 1]` for the given
 * [elapsedNanos] since [PlaneVisualizerV2]'s first tracking frame.
 *
 * `0` at the moment of first detection, `1.0` at or past
 * [PlaneVisualizerV2.SCAN_IN_DURATION_MS] (800 ms). Clamped at both ends so the
 * shader never sees NaN or a negative ring radius. Pure top-level helper, `internal`
 * so the math is unit-testable without an Engine.
 */
internal fun computeScanProgress(elapsedNanos: Long): Float {
    if (elapsedNanos <= 0L) return 0f
    val elapsedMs = elapsedNanos / 1_000_000f
    val progress = elapsedMs / PlaneVisualizerV2.SCAN_IN_DURATION_MS
    return progress.coerceIn(0f, 1f)
}

/**
 * Returns the reflection fade-in progress in `[0, 1]` for the given
 * [elapsedNanos] since [PlaneVisualizerV2]'s first tracking frame.
 *
 * `0` at the moment of first detection, `1.0` at or past
 * [PlaneVisualizerV2.REFLECTION_FADE_IN_MS] (~1 s). The shader scales `metallic` +
 * `reflectance` by this value so the PBR contribution ramps in smoothly while ARCore's
 * HDR cubemap estimate is still stabilising.
 *
 * Pure top-level helper, `internal` so the math is unit-testable without an Engine.
 */
internal fun computeReflectionFadeIn(elapsedNanos: Long): Float {
    if (elapsedNanos <= 0L) return 0f
    val elapsedMs = elapsedNanos / 1_000_000f
    val progress = elapsedMs / PlaneVisualizerV2.REFLECTION_FADE_IN_MS
    return progress.coerceIn(0f, 1f)
}

/**
 * Pushes the [planeMaterialPresetFor] preset onto [instance] when [type] differs
 * from [lastAppliedType]. Returns the updated cached type — pass it back into the
 * `lastAppliedType` argument on the next call.
 *
 * Three `setParameter` JNI calls — only runs on change. Steady-state cost is one
 * Boolean compare per frame. A null [lastAppliedType] forces the apply (used the
 * first time a `MaterialInstance` is bound to a visualizer in
 * [PlaneVisualizerV2.setPlaneMaterial]).
 *
 * Top-level + `internal` so the routing is unit-testable without an Engine + a real
 * ARCore Plane, and so the visualizer class stays under detekt's 25-function cap.
 */
internal fun applyTypePresetIfChanged(
    instance: MaterialInstance,
    type: Plane.Type,
    lastAppliedType: Plane.Type?,
): Plane.Type {
    if (lastAppliedType == type) return type
    val preset = planeMaterialPresetFor(type)
    instance.setParameter(PlaneRendererV2.MATERIAL_METALLIC, preset.metallic)
    instance.setParameter(PlaneRendererV2.MATERIAL_ROUGHNESS, preset.roughness)
    instance.setParameter(
        PlaneRendererV2.MATERIAL_GRID_TINT,
        preset.gridR,
        preset.gridG,
        preset.gridB,
    )
    return type
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
