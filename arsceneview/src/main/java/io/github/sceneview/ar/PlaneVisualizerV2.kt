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
import com.google.ar.core.TrackingState
import dev.romainguy.kotlin.math.Float3
import io.github.sceneview.ar.scene.PlaneRendererV2
import io.github.sceneview.ar.scene.planeMaterialPresetFor
import io.github.sceneview.collision.Matrix
import io.github.sceneview.collision.TransformProvider
import io.github.sceneview.math.normalToTangent
import io.github.sceneview.safeDestroyIndexBuffer
import io.github.sceneview.safeDestroyVertexBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Renders a single ARCore [Plane] as a field of soft, world-anchored dots — V2 implementation
 * ([#3507](https://github.com/sceneview/sceneview/issues/3507), rebuild of
 * [#2203](https://github.com/sceneview/sceneview/issues/2203)).
 *
 * **V2 is an opt-in.** The default is
 * [io.github.sceneview.ar.scene.PlaneRendererBase.Version.V1], drawn by [PlaneVisualizer]. Opt
 * into V2 with `ARSceneView(planeRendererVersion = PlaneRendererBase.Version.V2)`.
 *
 * What the user sees, all of it drawn by `plane_renderer_v2.mat` over a flat feathered mesh:
 *
 * 1. **Dots, not a slab.** Small round dots lying on the surface, anchored to the world so they
 *    stay put while ARCore re-centres and grows the plane. Floors and ceilings get a square
 *    lattice, walls a smaller staggered one.
 * 2. **Soft edges.** The dots fade out over the last 20 cm of the polygon (same feathered mesh
 *    as V1: the inner ring carries `y = 1`, the outer ring `y = 0`), with distance from the
 *    camera, and into an even tone where they would shimmer.
 * 3. **Reveal as the plane grows.** A bright front sweeps out from the plane's centre the
 *    first time it appears, and again over the new area each time ARCore extends it.
 * 4. **Focus.** The plane under the centre of the screen is brighter and carries a pool of
 *    light, a thin 12 cm ring and slow ripples where the camera points.
 * 5. **Tidy exit.** Hiding or disabling a plane fades it out instead of cutting it.
 *
 * The mesh is rebuilt on every [updatePlane] (at `PlaneRendererV2.maxHitTestPerSecond`) into
 * preallocated buffers; the animations advance in [tick], called every frame, which only
 * writes the uniforms that changed. Neither path allocates.
 *
 * Threading: every Filament JNI call must run on the render thread, same as V1.
 * [PlaneRendererV2.update] is called on that thread — do not poke this class from a background
 * coroutine.
 */
class PlaneVisualizerV2(
    private val engine: Engine,
    private val scene: Scene,
    private val plane: Plane
) : TransformProvider {

    companion object {
        /**
         * No longer used: V2 draws a flat feathered mesh and does not read ARCore depth any
         * more (#3507). Kept for binary compatibility.
         */
        const val DEPTH_REBUILD_INTERVAL_MS: Long = 200L

        /**
         * Time, in milliseconds, the reveal front takes to sweep a new plane from its centre to
         * its edge. Each later extension is swept at the same speed, so it takes less (the
         * front never moves slower than 1 m/s).
         */
        const val SCAN_IN_DURATION_MS: Long = 800L

        /**
         * No longer used: the V2 surface is unlit and has no reflection to fade in (#3507).
         * Kept for binary compatibility.
         */
        const val REFLECTION_FADE_IN_MS: Long = 1000L

        // V1's feathered fan: an outer ring on the polygon and an inner ring pulled 20 cm in.
        private const val MAX_BOUNDARY_VERTS = 128
        private const val MAX_VERTS = MAX_BOUNDARY_VERTS * 2
        private const val MAX_INDICES = MAX_BOUNDARY_VERTS * 6 + (MAX_BOUNDARY_VERTS - 2) * 3
        private const val FEATHER_LENGTH = 0.2f
        private const val FEATHER_SCALE = 0.2f

        private const val FLOAT_BYTES = 4
        private const val INT_BYTES = 4
        private const val POSITION_STRIDE = 3 * FLOAT_BYTES
        private const val TANGENT_STRIDE = 4 * FLOAT_BYTES
        private const val BUFFER_INDEX_POSITION = 0
        private const val BUFFER_INDEX_TANGENT = 1
        private const val BUFFER_COUNT = 2

        private val PLANE_LOCAL_UP = Float3(0f, 1f, 0f)
    }

    private val planeMatrix = Matrix()

    private var isPlaneAddedToScene = false
    private var isEnabled = true
    private var isShadowReceiver = false
    private var isVisible = false
    private var isTracking = false

    private var planeSubmeshMaterial: MaterialInstance? = null
    private var shadowSubmeshMaterial: MaterialInstance? = null

    private val entity = EntityManager.get().create()

    // Looked up once: the entity and its transform component live as long as the visualizer.
    private var transformInstance: Int = 0

    private val vertexBuffer: VertexBuffer = VertexBuffer.Builder()
        .vertexCount(MAX_VERTS)
        .bufferCount(BUFFER_COUNT)
        .attribute(
            VertexBuffer.VertexAttribute.POSITION,
            BUFFER_INDEX_POSITION,
            VertexBuffer.AttributeType.FLOAT3,
            0,
            POSITION_STRIDE,
        )
        // plane_renderer_v2 only needs POSITION, but the shadow catcher is a shadowMultiplier
        // material and Filament makes those require TANGENTS (PlaneVisualizerAttributeContractTest).
        .attribute(
            VertexBuffer.VertexAttribute.TANGENTS,
            BUFFER_INDEX_TANGENT,
            VertexBuffer.AttributeType.FLOAT4,
            0,
            TANGENT_STRIDE,
        )
        .build(engine)

    private val indexBuffer: IndexBuffer = IndexBuffer.Builder()
        .indexCount(MAX_INDICES)
        .bufferType(IndexBuffer.Builder.IndexType.UINT)
        .build(engine)

    // Reused direct buffers and their typed views, created once (V1's upload pattern). The
    // mesh is rewritten at most `maxHitTestPerSecond` times a second, long after Filament
    // consumed the previous upload.
    private val positionData: ByteBuffer =
        ByteBuffer.allocateDirect(MAX_VERTS * POSITION_STRIDE).order(ByteOrder.nativeOrder())
    private val positionFloats: FloatBuffer = positionData.asFloatBuffer()
    private val indexData: ByteBuffer =
        ByteBuffer.allocateDirect(MAX_INDICES * INT_BYTES).order(ByteOrder.nativeOrder())
    private val indexInts = indexData.asIntBuffer()

    private var currentIndexCount = 0
    private var builtPrimitiveCount = 0
    private val primitivesScratch = ArrayList<MaterialInstance>(2)

    private var lastAppliedPlaneType: Plane.Type? = null

    // ── Animation state, advanced by tick() ───────────────────────────────────────────────
    private val animation = PlaneRevealAnimation()
    private var focusTarget = 0f
    private var lastTickNanos = 0L

    // Last values written to the material, so an idle plane costs no JNI call per frame.
    private var pushedOpacity = Float.NaN
    private var pushedFocus = Float.NaN
    private var pushedProgress = Float.NaN
    private var pushedRadius = Float.NaN

    init {
        // One constant tangent frame (plane-local up) for every vertex slot: the mesh is flat in
        // the plane's own frame and the pose rides on the entity transform. Uploaded once.
        val tangent = normalToTangent(PLANE_LOCAL_UP)
        val tangentData = ByteBuffer
            .allocateDirect(MAX_VERTS * TANGENT_STRIDE)
            .order(ByteOrder.nativeOrder())
        val floats = tangentData.asFloatBuffer()
        repeat(MAX_VERTS) {
            floats.put(tangent.x)
            floats.put(tangent.y)
            floats.put(tangent.z)
            floats.put(tangent.w)
        }
        tangentData.rewind()
        vertexBuffer.setBufferAt(
            engine,
            BUFFER_INDEX_TANGENT,
            tangentData,
            0,
            MAX_VERTS * TANGENT_STRIDE
        )
    }

    fun setEnabled(enabled: Boolean) {
        if (isEnabled != enabled) {
            isEnabled = enabled
            refreshRenderable()
        }
    }

    fun setShadowReceiver(shadowReceiver: Boolean) {
        if (isShadowReceiver != shadowReceiver) {
            isShadowReceiver = shadowReceiver
            refreshRenderable()
        }
    }

    fun setVisible(visible: Boolean) {
        if (isVisible != visible) {
            isVisible = visible
            refreshRenderable()
        }
    }

    /**
     * How much this plane is the one the user is aiming at, `0..1` — the renderer passes `1`
     * for the plane under the centre of the screen. Eased by [tick].
     */
    internal fun setFocus(focus: Float) {
        focusTarget = focus.coerceIn(0f, 1f)
    }

    fun setPlaneMaterial(materialInstance: MaterialInstance) {
        planeSubmeshMaterial = materialInstance
        lastAppliedPlaneType = applyTypePresetIfChanged(
            instance = materialInstance,
            type = plane.type,
            lastAppliedType = null,
        )
        pushedOpacity = Float.NaN
        pushedFocus = Float.NaN
        pushedProgress = Float.NaN
        pushedRadius = Float.NaN
        pushUniforms()
        if (builtPrimitiveCount > 0) updateRenderable()
    }

    fun setShadowMaterial(materialInstance: MaterialInstance) {
        shadowSubmeshMaterial = materialInstance
        if (builtPrimitiveCount > 0) updateRenderable()
    }

    /**
     * No longer used: V2 does not read the camera frame since it stopped building a depth mesh
     * (#3507). Kept for binary compatibility.
     */
    @Suppress("UNUSED_PARAMETER", "UnusedParameter")
    fun setFrame(frame: Frame?, camera: Camera?) = Unit

    override fun getTransformationMatrix(): Matrix = planeMatrix

    /**
     * Re-reads the plane's pose, polygon and type from ARCore and re-uploads the mesh.
     */
    fun updatePlane() {
        isTracking = plane.trackingState == TrackingState.TRACKING
        if (!isTracking) {
            refreshRenderable()
            return
        }
        plane.centerPose.toMatrix(planeMatrix.data, 0)
        if (!rebuildMesh()) {
            currentIndexCount = 0
            removePlaneFromScene()
            return
        }
        planeSubmeshMaterial?.let { instance ->
            lastAppliedPlaneType = applyTypePresetIfChanged(
                instance = instance,
                type = plane.type,
                lastAppliedType = lastAppliedPlaneType,
            )
        }
        animation.targetRadius = computeScanRadius(plane.polygon) + REVEAL_MARGIN_M
        refreshRenderable()
    }

    /**
     * Advances the fade, focus and reveal animations to [nowNanos] and writes the uniforms
     * that changed. Called every frame by [PlaneRendererV2.update]; allocation-free.
     */
    internal fun tick(nowNanos: Long) {
        val dtSeconds = if (lastTickNanos == 0L) 0f else {
            ((nowNanos - lastTickNanos) / 1e9f).coerceIn(0f, MAX_TICK_SECONDS)
        }
        lastTickNanos = nowNanos
        val wasShowing = animation.opacity > 0f
        animation.advance(
            dtSeconds = dtSeconds,
            show = isShowingPlane(),
            focusTarget = focusTarget,
        )
        pushUniforms()
        // The plane submesh leaves the renderable once it has faded all the way out.
        if (wasShowing != animation.opacity > 0f) refreshRenderable()
    }

    private fun isShowingPlane() = isEnabled && isVisible && isTracking

    private fun pushUniforms() {
        val instance = planeSubmeshMaterial ?: return
        if (animation.opacity != pushedOpacity) {
            instance.setParameter(MATERIAL_OPACITY, animation.opacity)
            pushedOpacity = animation.opacity
        }
        if (animation.focus != pushedFocus) {
            instance.setParameter(MATERIAL_FOCUS, animation.focus)
            pushedFocus = animation.focus
        }
        val progress = animation.scanProgress
        if (progress != pushedProgress) {
            instance.setParameter(PlaneRendererV2.MATERIAL_SCAN_PROGRESS, progress)
            pushedProgress = progress
        }
        if (animation.revealRadius != pushedRadius) {
            instance.setParameter(PlaneRendererV2.MATERIAL_SCAN_PLANE_RADIUS, animation.revealRadius)
            pushedRadius = animation.revealRadius
        }
    }

    /**
     * V1's feathered fan, written straight into the reused upload buffers: the polygon as an
     * outer ring at `y = 0`, then the same ring pulled [FEATHER_LENGTH] inwards at `y = 1`.
     * The shader reads that `y` as the edge ramp and flattens it.
     */
    private fun rebuildMesh(): Boolean {
        val boundary = plane.polygon
        boundary.rewind()
        val boundaryVertexCount = boundary.limit() / 2
        if (boundaryVertexCount < 3 || boundaryVertexCount > MAX_BOUNDARY_VERTS) return false

        positionFloats.clear()
        for (i in 0 until boundaryVertexCount) {
            positionFloats.put(boundary.get(i * 2)).put(0f).put(boundary.get(i * 2 + 1))
        }
        for (i in 0 until boundaryVertexCount) {
            val x = boundary.get(i * 2)
            val z = boundary.get(i * 2 + 1)
            val magnitude = sqrt(x * x + z * z)
            val scale = if (magnitude != 0f) {
                1f - min(FEATHER_LENGTH / magnitude, FEATHER_SCALE)
            } else {
                1f - FEATHER_SCALE
            }
            positionFloats.put(x * scale).put(1f).put(z * scale)
        }
        val vertexCount = boundaryVertexCount * 2
        positionData.rewind()
        vertexBuffer.setBufferAt(
            engine, BUFFER_INDEX_POSITION, positionData, 0, vertexCount * POSITION_STRIDE
        )

        indexInts.clear()
        val firstInner = boundaryVertexCount
        for (i in 0 until boundaryVertexCount - 2) {
            indexInts.put(firstInner).put(firstInner + i + 1).put(firstInner + i + 2)
        }
        for (i in 0 until boundaryVertexCount) {
            val next = (i + 1) % boundaryVertexCount
            indexInts.put(i).put(next).put(firstInner + i)
            indexInts.put(firstInner + i).put(next).put(firstInner + next)
        }
        currentIndexCount = indexInts.position()
        indexData.rewind()
        indexBuffer.setBuffer(engine, indexData, 0, currentIndexCount * INT_BYTES)
        return true
    }

    private fun refreshRenderable() {
        if (currentIndexCount == 0) {
            removePlaneFromScene()
            return
        }
        updateRenderable()
    }

    private fun updateRenderable() {
        val primitives = selectPlanePrimitives(
            // Keep drawing while fading out; drop the submesh once invisible.
            isVisible = isShowingPlane() || animation.opacity > 0f,
            planeMaterial = planeSubmeshMaterial,
            isShadowReceiver = isEnabled && isTracking && isShadowReceiver,
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
            val builder = RenderableManager.Builder(primitives.size)
                .castShadows(false)
                .receiveShadows(true)
                .culling(false)
                .boundingBox(com.google.android.filament.Box(0f, 0f, 0f, 10f, 0.5f, 10f))
            for (idx in primitives.indices) {
                builder.geometry(
                    idx,
                    RenderableManager.PrimitiveType.TRIANGLES,
                    vertexBuffer,
                    indexBuffer,
                    0,
                    currentIndexCount,
                )
                builder.material(idx, primitives[idx])
                builder.blendOrder(idx, idx)
            }
            builder.build(engine, entity)
            builtPrimitiveCount = primitives.size
        } else {
            val inst = rm.getInstance(entity)
            for (idx in primitives.indices) {
                rm.setGeometryAt(
                    inst,
                    idx,
                    RenderableManager.PrimitiveType.TRIANGLES,
                    vertexBuffer,
                    indexBuffer,
                    0,
                    currentIndexCount,
                )
                rm.setMaterialInstanceAt(inst, idx, primitives[idx])
            }
        }

        val transformManager = engine.transformManager
        if (transformInstance == 0) {
            transformInstance = transformManager.getInstance(entity)
        }
        transformManager.setTransform(transformInstance, planeMatrix.data)
        addPlaneToScene()
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

// Material parameters added by #3507, set per plane by the visualizer only.
private const val MATERIAL_OPACITY = "opacity"
private const val MATERIAL_FOCUS = "focus"

/** Past the furthest polygon vertex, so the front's soft tail clears the edge too. */
private const val REVEAL_MARGIN_M = 0.35f

/** A frame gap longer than this (app paused, first frame) does not jump the animations. */
private const val MAX_TICK_SECONDS = 0.1f

internal const val FADE_IN_SECONDS = 0.30f
internal const val FADE_OUT_SECONDS = 0.40f
internal const val FOCUS_SECONDS = 0.25f
internal const val FRONT_GLOW_SECONDS = 0.30f
internal const val MIN_REVEAL_SPEED_M_PER_S = 1.0f

/** Gap, in metres, below which the reveal front counts as caught up with the plane edge. */
internal const val REVEAL_CAUGHT_UP_M = 0.02f

/**
 * The per-plane animation state of [PlaneVisualizerV2]: whole-plane fade, focus, and the reveal
 * front chasing the plane's edge as ARCore grows it. Pure — no Filament, no ARCore — so it is
 * unit-tested frame by frame.
 */
internal class PlaneRevealAnimation {
    /** Whole-plane opacity, `0..1`. */
    var opacity = 0f
        private set

    /** Focus emphasis, `0..1`. */
    var focus = 0f
        private set

    /** Plane-local radius, in metres, the reveal front has reached. */
    var revealRadius = 0f
        private set

    /** Brightness of the reveal front, `0..1`; `0` once the front has caught up. */
    var frontGlow = 0f
        private set

    /** Plane-local radius the front has to reach: furthest polygon vertex plus a margin. */
    var targetRadius = 0f

    /**
     * The `scanProgress` uniform: `1` = nothing to reveal (the shader skips the front
     * entirely), below `1` = the front is on screen, `1 - frontGlow` bright.
     */
    val scanProgress: Float
        get() = if (isRevealDone) 1f else min(1f - frontGlow, MAX_ACTIVE_PROGRESS)

    private val isRevealDone: Boolean
        get() = frontGlow == 0f && targetRadius - revealRadius <= REVEAL_CAUGHT_UP_M

    fun advance(dtSeconds: Float, show: Boolean, focusTarget: Float) {
        opacity = approach(
            opacity,
            if (show) 1f else 0f,
            dtSeconds / if (show) FADE_IN_SECONDS else FADE_OUT_SECONDS,
        )
        focus = approach(focus, focusTarget, dtSeconds / FOCUS_SECONDS)

        // A plane that shrank (ARCore merged or refined it) is simply clipped to its new size.
        if (targetRadius < revealRadius) revealRadius = targetRadius
        // Speed scales with the plane, so the front crosses a whole new plane from its centre in
        // SCAN_IN_DURATION_MS whatever its size; it never crawls below MIN_REVEAL_SPEED_M_PER_S.
        val speed = max(
            MIN_REVEAL_SPEED_M_PER_S,
            targetRadius * MILLIS_PER_SECOND / PlaneVisualizerV2.SCAN_IN_DURATION_MS
        )
        revealRadius = approach(revealRadius, targetRadius, speed * dtSeconds)
        frontGlow = approach(
            frontGlow,
            if (targetRadius - revealRadius > REVEAL_CAUGHT_UP_M) 1f else 0f,
            dtSeconds / FRONT_GLOW_SECONDS,
        )
    }

    private companion object {
        // Keeps the shader on its reveal branch while the front is still moving.
        const val MAX_ACTIVE_PROGRESS = 0.999f
        const val MILLIS_PER_SECOND = 1000f
    }
}

/** Moves [current] towards [target] by at most [maxDelta], landing exactly on it. */
internal fun approach(current: Float, target: Float, maxDelta: Float): Float {
    val delta = target - current
    return if (abs(delta) <= maxDelta) target else current + if (delta > 0f) maxDelta else -maxDelta
}

/**
 * Returns the max plane-local distance from origin to any vertex of [polygon]. ARCore
 * delivers the polygon as interleaved `[x0, z0, x1, z1, ...]` floats in plane-local
 * coordinates (Y is the plane normal). The reveal front sweeps from the plane origin out to
 * this radius.
 *
 * Empty buffer → 0. The buffer's position is restored before return.
 */
internal fun computeScanRadius(polygon: FloatBuffer): Float {
    val savedPos = polygon.position()
    polygon.rewind()
    val vertexCount = polygon.remaining() / 2
    var maxSq = 0f
    for (i in 0 until vertexCount) {
        val x = polygon.get(i * 2)
        val z = polygon.get(i * 2 + 1)
        val dSq = x * x + z * z
        if (dSq > maxSq) maxSq = dSq
    }
    polygon.position(savedPos)
    return sqrt(maxSq)
}

/**
 * Pushes the [planeMaterialPresetFor] dot colour and strength onto [instance] when [type]
 * differs from [lastAppliedType]. Returns the updated cached type — pass it back into the
 * `lastAppliedType` argument on the next call. A null [lastAppliedType] forces the apply.
 */
internal fun applyTypePresetIfChanged(
    instance: MaterialInstance,
    type: Plane.Type,
    lastAppliedType: Plane.Type?,
): Plane.Type {
    if (lastAppliedType == type) return type
    val preset = planeMaterialPresetFor(type)
    instance.setParameter(
        PlaneRendererV2.MATERIAL_GRID_TINT,
        preset.gridR,
        preset.gridG,
        preset.gridB,
    )
    instance.setParameter(PlaneRendererV2.MATERIAL_GRID_ALPHA, preset.dotAlpha)
    return type
}
