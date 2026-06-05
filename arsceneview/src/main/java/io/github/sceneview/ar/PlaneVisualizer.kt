package io.github.sceneview.ar

import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.IndexBuffer
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.Scene
import com.google.android.filament.VertexBuffer
import com.google.ar.core.Plane
import com.google.ar.core.TrackingState
import io.github.sceneview.collision.Matrix
import io.github.sceneview.collision.TransformProvider
import io.github.sceneview.collision.Vector3
import io.github.sceneview.safeDestroyIndexBuffer
import io.github.sceneview.safeDestroyVertexBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Renders a single ARCore Plane using native Filament geometry — V1 implementation.
 *
 * V1 is the **default** plane visualizer again as of v4.16.1 (see
 * [#2203](https://github.com/sceneview/sceneview/issues/2203)). v4.16.0 briefly shipped V2
 * ([PlaneVisualizerV2]) as the default but its visual output did not match the design
 * intent under on-device QA, so the default was reverted while V2 is polished.
 */
class PlaneVisualizer(
    private val engine: Engine,
    private val scene: Scene,
    private val plane: Plane
) : TransformProvider {

    companion object {
        private const val VERTS_PER_BOUNDARY_VERT = 2
        private const val FEATHER_LENGTH = 0.2f
        private const val FEATHER_SCALE = 0.2f

        // Pre-allocated maximums: ARCore docs cap plane polygon at 128 boundary verts
        private const val MAX_BOUNDARY_VERTS = 128
        private const val MAX_VERTS = MAX_BOUNDARY_VERTS * VERTS_PER_BOUNDARY_VERT
        // Each boundary vertex contributes 6 boundary-strip indices + up to 3 fan indices
        private const val MAX_INDICES = MAX_BOUNDARY_VERTS * 6 + (MAX_BOUNDARY_VERTS - 2) * 3

        private const val FLOAT_BYTES = 4
        private const val INT_BYTES = 4
        // POSITION: 3 floats per vertex
        private const val POSITION_STRIDE = 3 * FLOAT_BYTES
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
     * thunk once instead of on every [updateRenderable] tick. Mirrors the lazy-once caching
     * #2280 applied to `Node.transformInstance` (#2269, #2287). A `0` result is never frozen.
     */
    private var transformInstance: Int = 0

    private val vertexBuffer: VertexBuffer = VertexBuffer.Builder()
        .vertexCount(MAX_VERTS)
        .bufferCount(1)
        .attribute(
            VertexBuffer.VertexAttribute.POSITION,
            0,
            VertexBuffer.AttributeType.FLOAT3,
            0,
            POSITION_STRIDE
        )
        .build(engine)

    private val indexBuffer: IndexBuffer = IndexBuffer.Builder()
        .indexCount(MAX_INDICES)
        .bufferType(IndexBuffer.Builder.IndexType.UINT)
        .build(engine)

    // Reusable direct buffers
    private val vertexData: ByteBuffer =
        ByteBuffer.allocateDirect(MAX_VERTS * POSITION_STRIDE).order(ByteOrder.nativeOrder())
    private val indexData: ByteBuffer =
        ByteBuffer.allocateDirect(MAX_INDICES * INT_BYTES).order(ByteOrder.nativeOrder())

    // Track the last primitive counts so we only rebuild the renderable when they change
    private var builtPrimitiveCount = 0

    /**
     * Reusable 2-element backing list for [updateRenderable]'s primitive selection (#2328 / #2402).
     *
     * [updateRenderable] runs every frame the plane updates; building the primitive list with a
     * fresh `buildList { }` each call churned one short-lived list per plane per frame (300+/s with
     * 10 planes at 30 Hz). The selection is recomputed into this single reused list every call —
     * cleared first — so there is no cached state that could go stale; only the per-call allocation
     * is removed. Single-threaded (main/render thread), so the shared field is race-free.
     */
    private val primitivesScratch = ArrayList<MaterialInstance>(2)

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

        if (!updateGeometry()) {
            removePlaneFromScene()
            return
        }

        updateRenderable()
        addPlaneToScene()
    }

    private fun updateGeometry(): Boolean {
        val boundary = plane.polygon ?: return false
        boundary.rewind()
        val boundaryVertexCount = boundary.limit() / 2
        if (boundaryVertexCount == 0) return false

        val numVerts = boundaryVertexCount * VERTS_PER_BOUNDARY_VERT
        val numIndices = (boundaryVertexCount * 6) + ((boundaryVertexCount - 2) * 3)
        if (numVerts > MAX_VERTS || numIndices > MAX_INDICES) return false

        vertexData.clear()
        val floats = vertexData.asFloatBuffer()

        // Outer boundary vertices at y=0
        boundary.rewind()
        while (boundary.hasRemaining()) {
            val x = boundary.get()
            val z = boundary.get()
            floats.put(x); floats.put(0f); floats.put(z)
        }

        // Inner feathered vertices at y=1
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
            floats.put(x * scale); floats.put(1f); floats.put(z * scale)
        }

        vertexData.rewind()
        vertexBuffer.setBufferAt(engine, 0, vertexData, 0, numVerts * POSITION_STRIDE)

        indexData.clear()
        val ints = indexData.asIntBuffer()

        val firstInner = boundaryVertexCount
        // Interior fan
        for (i in 0 until boundaryVertexCount - 2) {
            ints.put(firstInner); ints.put(firstInner + i + 1); ints.put(firstInner + i + 2)
        }
        // Boundary strip quads
        for (i in 0 until boundaryVertexCount) {
            val o1 = i
            val o2 = (i + 1) % boundaryVertexCount
            val n1 = firstInner + i
            val n2 = firstInner + (i + 1) % boundaryVertexCount
            ints.put(o1); ints.put(o2); ints.put(n1)
            ints.put(n1); ints.put(o2); ints.put(n2)
        }

        indexData.rewind()
        indexBuffer.setBuffer(engine, indexData, 0, numIndices * INT_BYTES)

        return true
    }

    private fun updateRenderable() {
        val primitives = selectPlanePrimitives(
            isVisible = isVisible,
            planeMaterial = planeSubmeshMaterial,
            isShadowReceiver = isShadowReceiver,
            shadowMaterial = shadowSubmeshMaterial,
            out = primitivesScratch
        )

        if (primitives.isEmpty()) {
            removePlaneFromScene()
            return
        }

        val rm = engine.renderableManager

        if (builtPrimitiveCount != primitives.size) {
            // Primitive count changed — rebuild the renderable from scratch
            if (builtPrimitiveCount > 0) rm.destroy(entity)
            RenderableManager.Builder(primitives.size)
                .castShadows(false)
                .receiveShadows(true)
                .culling(false)
                .boundingBox(com.google.android.filament.Box(0f, 0f, 0f, 10f, 0.01f, 10f))
                .apply {
                    primitives.forEachIndexed { idx, mat ->
                        geometry(
                            idx,
                            RenderableManager.PrimitiveType.TRIANGLES,
                            vertexBuffer,
                            indexBuffer
                        )
                        material(idx, mat)
                        blendOrder(idx, idx)
                    }
                }
                .build(engine, entity)
            builtPrimitiveCount = primitives.size
        } else {
            // Same primitive count — just update materials
            val inst = rm.getInstance(entity)
            primitives.forEachIndexed { idx, mat ->
                rm.setMaterialInstanceAt(inst, idx, mat)
            }
        }

        // Update transform — the instance handle is stable for the entity's lifetime, so it's
        // looked up once and cached (#2287). A 0 result (no transform component yet) is retried.
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
 * Fills [out] with a plane's renderable primitive materials, in draw order (plane then shadow),
 * reusing the caller's list instead of allocating a fresh one per frame (#2328 / #2402). Returns
 * [out] for call-site convenience.
 *
 * Shared by both [PlaneVisualizer] (V1) and [PlaneVisualizerV2]. [out] is cleared first, so the
 * result is always rebuilt from the live arguments — there is no cached state that could go stale
 * (the safest form of "reuse": no invalidation path to miss). Pure (no Filament), so the
 * per-frame allocation removal and the visibility / shadow-receiver gating are pinned by a JVM unit
 * test.
 *
 * @param isVisible        Whether the plane's textured submesh should be drawn.
 * @param planeMaterial    Plane submesh material, or `null` if not set yet.
 * @param isShadowReceiver Whether the plane's shadow-catcher submesh should be drawn.
 * @param shadowMaterial   Shadow submesh material, or `null` if not set yet.
 * @param out              Reused destination list (cleared before filling).
 */
internal fun <T : Any> selectPlanePrimitives(
    isVisible: Boolean,
    planeMaterial: T?,
    isShadowReceiver: Boolean,
    shadowMaterial: T?,
    out: MutableList<T>,
): MutableList<T> {
    out.clear()
    if (isVisible && planeMaterial != null) out.add(planeMaterial)
    if (isShadowReceiver && shadowMaterial != null) out.add(shadowMaterial)
    return out
}
