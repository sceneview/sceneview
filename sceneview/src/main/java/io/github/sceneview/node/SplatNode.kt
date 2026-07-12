package io.github.sceneview.node

import androidx.annotation.MainThread
import com.google.android.filament.Box
import com.google.android.filament.EntityManager
import com.google.android.filament.IndexBuffer
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.Texture
import com.google.android.filament.VertexBuffer
import dev.romainguy.kotlin.math.Float4
import dev.romainguy.kotlin.math.inverse
import io.github.sceneview.EngineDestroyQueue
import io.github.sceneview.Entity
import io.github.sceneview.core.splat.SplatCloud
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.math.Position
import io.github.sceneview.safeDestroyEntity
import io.github.sceneview.safeDestroyIndexBuffer
import io.github.sceneview.safeDestroyRenderable
import io.github.sceneview.safeDestroyVertexBuffer
import io.github.sceneview.splat.SplatBuffers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.max

/**
 * A node that renders a 3D Gaussian Splatting ([SplatCloud]) scene — the radiance-field capture
 * format produced by Scaniverse, Polycam, Luma, and every INRIA-style trainer (#2646).
 *
 * ### How it renders (P1 scope)
 *
 * Each gaussian is drawn as a **hardware-instanced camera-facing quad** using the dedicated
 * `splat.filamat` material: the vertex shader fetches the per-splat centre / half-extent /
 * colour / opacity from two square `RGBA16F` data textures indexed by the instance id, and the
 * fragment applies an isotropic gaussian falloff with premultiplied-alpha blending
 * (see [SplatBuffers] for the exact texel layout contract). Splat centres are therefore
 * stored as **half-floats**: positional precision degrades with distance from the model
 * origin (~1 cm at 16 m) — fine for object/room-scale captures; very large outdoor scans
 * may show sub-splat wobble (a 32-bit position texture is a P2/#2646 option if P1c device
 * validation shows it matters). Filament 1.71.5 has no compute
 * shaders, so correct alpha compositing relies on a **CPU painter's sort**:
 *
 * - **Isotropic billboards** — each splat renders as the circumscribed disc of its gaussian
 *   (radius = 3 sigma of the largest scale axis). Anisotropic screen-space ellipses
 *   (2D-covariance projection of `SplatCloud.rotations` + per-axis scales) are the planned P2
 *   quality step of #2646; [SplatCloud.rotations] is carried but not yet consumed.
 * - **View-dependent sort** — when a [cameraPositionProvider] is set, the node re-sorts
 *   back-to-front on a background thread whenever the camera has moved beyond a small
 *   threshold (relative to the cloud radius), then re-uploads the data textures on the main
 *   thread. Between re-sorts the order is stale by at most the camera delta, so slow orbits
 *   stay visually correct; without a provider the order stays as loaded (view-independent —
 *   expect popping when orbiting to the far side).
 * - **Draw batching** — Filament caps hardware instancing at 65535 instances per renderable
 *   ([SplatBuffers.MAX_INSTANCES_PER_BATCH]). Larger clouds are split into multiple renderable
 *   batches under this single node, all sharing the same two data textures via each batch's
 *   `instanceOffset` material parameter. With multiple batches, per-batch `blendOrder` keys
 *   (global blend order) keep the batches compositing in texel order — which the sort keeps
 *   globally back-to-front.
 *
 * ### Threading
 *
 * Construction performs Filament JNI calls (texture/buffer/renderable builders) and **must run
 * on the main thread** — in Compose use the `SceneScope.SplatNode` composable /
 * `rememberSplatCloud`. The depth sort + texel re-pack run on a background dispatcher; only the
 * resulting `Texture.setImage` upload hops back to the main thread (mirroring
 * `ModelLoader.loadModelAsync`'s contract).
 *
 * ```kotlin
 * SceneView(
 *     onFrame = { cameraPosition = cameraNode.worldPosition }
 * ) {
 *     SplatNode(
 *         splatCloud = cloud,
 *         cameraPositionProvider = { cameraPosition }
 *     )
 * }
 * ```
 *
 * @param materialLoader Used to create the splat material instances (and to resolve their
 *                       lifecycle on [destroy]).
 * @param splatCloud     The gaussians to render (must be non-empty). The arrays are read
 *                       (never written) by this node — also from the background sort thread,
 *                       so callers must not mutate them while the node is alive.
 * @param cameraPositionProvider Invoked on the frame loop to obtain the current camera
 *                       **world** position for the painter's sort (the node maps it into its
 *                       own model space, so transformed/parented nodes sort correctly).
 *                       `null` disables view-dependent sorting.
 *
 * @see SplatBuffers
 * @see io.github.sceneview.loaders.MaterialLoader.createSplatInstance
 */
open class SplatNode(
    val materialLoader: MaterialLoader,
    val splatCloud: SplatCloud,
    var cameraPositionProvider: (() -> Position)? = null
) : Node(materialLoader.engine) {

    init {
        require(splatCloud.count > 0) { "splatCloud must contain at least one splat" }
    }

    /** Side of the two square RGBA16F per-splat data textures. */
    val textureSize = SplatBuffers.textureSize(splatCloud.count)

    /** Contiguous instance ranges, one renderable batch each (cap 65535 instances per draw). */
    val batches = SplatBuffers.batchRanges(splatCloud.count)

    /** Per-splat `xyz = centre, w = billboard half-extent` — see [SplatBuffers]. */
    val positionScaleTexture: Texture = buildDataTexture()

    /** Per-splat `rgb = linear colour, a = straight opacity` — see [SplatBuffers]. */
    val colorOpacityTexture: Texture = buildDataTexture()

    /** One material instance per batch (distinct `instanceOffset`), created via [materialLoader]. */
    val materialInstances: List<MaterialInstance> = batches.map { range ->
        materialLoader.createSplatInstance(
            positionScaleTexture = positionScaleTexture,
            colorOpacityTexture = colorOpacityTexture,
            textureSize = textureSize,
            instanceOffset = range.first
        )
    }

    /** Model-space culling AABB of the whole cloud (splat centres grown by their half-extents). */
    private val boundingBox: Box = SplatBuffers.boundingBox(splatCloud).let {
        Box(it[0], it[1], it[2], it[3], it[4], it[5])
    }

    /** Rough cloud radius — scales the camera-motion threshold that gates a re-sort. */
    private val boundingRadius: Float =
        max(boundingBox.halfExtent[0], max(boundingBox.halfExtent[1], boundingBox.halfExtent[2]))

    private val quadVertexBuffer: VertexBuffer
    private val quadIndexBuffer: IndexBuffer
    private val batchEntities: List<Entity>

    /** Instance count currently baked into each batch renderable (rebuilds are in place). */
    private val batchBuiltCounts: IntArray = IntArray(batches.size) { batches[it].count() }

    /** Instance count currently displayed per batch — `0` means hidden via layer mask. */
    private val batchVisibleCounts: IntArray = IntArray(batches.size) { batches[it].count() }

    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var sortJob: Job? = null
    private var lastSortCameraPosition: Position? = null
    private var isDestroyed = false

    /**
     * Number of splats rendered, `0..splatCloud.count`. Defaults to the full cloud.
     *
     * Lowering it truncates the draw to the first N texels of the current (sorted) order —
     * a cheap LOD / reveal-animation hook. Batches that become empty are hidden; partially
     * visible batches are rebuilt in place with the reduced instance count (the entity —
     * and therefore the scene registration — never changes). Main thread only.
     */
    var splatCount: Int = splatCloud.count
        @MainThread set(value) {
            val clamped = value.coerceIn(0, splatCloud.count)
            if (field == clamped) return
            field = clamped
            batches.forEachIndexed { index, range ->
                val visible = SplatBuffers.visibleInBatch(clamped, range)
                if (visible == batchVisibleCounts[index]) return@forEachIndexed
                val entity = batchEntities[index]
                val renderableManager = engine.renderableManager
                if (visible == 0) {
                    // Instanced draws need >= 1 instance — hide the whole batch instead.
                    renderableManager.setLayerMask(renderableManager.getInstance(entity), 0xff, 0x00)
                } else {
                    if (visible != batchBuiltCounts[index]) {
                        // Instance count is baked at build time (no runtime setter in Filament
                        // 1.71.5) — rebuild the renderable in place on the same entity. The
                        // rebuild resets the layer mask, so re-assert visibility below.
                        buildBatchRenderable(entity, index, visible)
                        batchBuiltCounts[index] = visible
                    }
                    renderableManager.setLayerMask(renderableManager.getInstance(entity), 0xff, 0xff)
                }
                batchVisibleCounts[index] = visible
            }
        }

    init {
        // Initial as-loaded order; the first frame with a cameraPositionProvider re-sorts.
        val identityOrder = IntArray(splatCloud.count) { it }
        uploadTextures(
            SplatBuffers.packPositionScale(splatCloud, identityOrder, textureSize),
            SplatBuffers.packColorOpacity(splatCloud, identityOrder, textureSize)
        )

        // The shared unit quad ([-1, 1]^2 at z = 0) instanced once per splat.
        quadVertexBuffer = VertexBuffer.Builder()
            .bufferCount(1)
            .vertexCount(4)
            .attribute(
                VertexBuffer.VertexAttribute.POSITION, 0,
                VertexBuffer.AttributeType.FLOAT3, 0, 3 * Float.SIZE_BYTES
            )
            .build(engine)
            .apply {
                setBufferAt(
                    engine, 0,
                    floatBufferOf(
                        -1f, -1f, 0f,
                        1f, -1f, 0f,
                        1f, 1f, 0f,
                        -1f, 1f, 0f
                    )
                )
            }
        quadIndexBuffer = IndexBuffer.Builder()
            .indexCount(6)
            .bufferType(IndexBuffer.Builder.IndexType.USHORT)
            .build(engine)
            .apply {
                setBuffer(
                    engine,
                    ByteBuffer.allocateDirect(6 * Short.SIZE_BYTES)
                        .order(ByteOrder.nativeOrder())
                        .asShortBuffer()
                        .put(shortArrayOf(0, 1, 2, 0, 2, 3))
                        .apply { rewind() }
                )
            }

        // One renderable entity per batch, transform-parented to this node so the node's
        // (world) transform drives every batch (the shader reads getWorldFromModelMatrix()).
        batchEntities = batches.indices.map { index ->
            EntityManager.get().create().also { entity ->
                // null local transform = identity; parent = this node's transform component.
                transformManager.create(entity, transformInstance, null as FloatArray?)
                buildBatchRenderable(entity, index, batches[index].count())
            }
        }
    }

    /**
     * All Filament entities this node contributes to the scene: its own (transform-only)
     * entity plus one renderable entity per instance batch.
     */
    override val sceneEntities get() = listOf(entity) + batchEntities

    /**
     * (Re)builds one batch renderable in place on [entity] with [instanceCount] instances.
     *
     * All batches share the unit quad geometry, the full-cloud [boundingBox] (instances are
     * displaced in the vertex shader, so per-batch tight boxes would not survive re-sorts),
     * and the two data textures. With several batches, a global `blendOrder` keyed on the
     * batch index keeps Filament compositing them in texel order — the painter's sort packs
     * texels globally back-to-front, so batch 0 is always the farthest slice.
     */
    private fun buildBatchRenderable(entity: Entity, batchIndex: Int, instanceCount: Int) {
        RenderableManager.Builder(1)
            .boundingBox(boundingBox)
            .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, quadVertexBuffer, quadIndexBuffer)
            .material(0, materialInstances[batchIndex])
            .instances(instanceCount)
            .culling(true)
            .castShadows(false)
            .receiveShadows(false)
            .apply {
                if (batches.size > 1) {
                    blendOrder(0, batchIndex)
                    globalBlendOrderEnabled(0, true)
                }
            }
            .build(engine, entity)
    }

    override fun onFrame(frameTimeNanos: Long) {
        super.onFrame(frameTimeNanos)
        maybeResort()
    }

    /**
     * Kicks a background re-sort when the camera has moved more than ~1% of the cloud radius
     * since the last sorted position. At most one sort is in flight; a skipped frame simply
     * retries on the next one.
     */
    private fun maybeResort() {
        val provider = cameraPositionProvider ?: return
        if (isDestroyed || sortJob?.isActive == true) return
        // Map the camera world position into this node's model space so parented/transformed
        // nodes sort correctly (the splat positions are model-space).
        val cameraWorld = provider()
        val local = inverse(worldTransform) * Float4(cameraWorld, 1f)
        val cameraLocal = Position(local.x, local.y, local.z)
        val last = lastSortCameraPosition
        if (last != null) {
            val dx = cameraLocal.x - last.x
            val dy = cameraLocal.y - last.y
            val dz = cameraLocal.z - last.z
            val threshold = max(boundingRadius * 0.01f, 1e-5f)
            if (dx * dx + dy * dy + dz * dz < threshold * threshold) return
        }
        lastSortCameraPosition = cameraLocal
        sortJob = coroutineScope.launch {
            val order = SplatBuffers.sortBackToFront(
                splatCloud.positions, splatCloud.count,
                cameraLocal.x, cameraLocal.y, cameraLocal.z
            )
            // Fresh buffers per sort: Filament owns an upload buffer asynchronously until the
            // GPU copy completes, so re-using one would race. Cheap at P1 scales; a
            // double-buffered pool is a P2 optimisation (#2646).
            val positionScale = SplatBuffers.packPositionScale(splatCloud, order, textureSize)
            val colorOpacity = SplatBuffers.packColorOpacity(splatCloud, order, textureSize)
            withContext(Dispatchers.Main) {
                if (!isDestroyed) uploadTextures(positionScale, colorOpacity)
            }
        }
    }

    private fun buildDataTexture(): Texture = Texture.Builder()
        .width(textureSize)
        .height(textureSize)
        .levels(1)
        .sampler(Texture.Sampler.SAMPLER_2D)
        .format(Texture.InternalFormat.RGBA16F)
        .build(engine)

    @MainThread
    private fun uploadTextures(positionScale: FloatBuffer, colorOpacity: FloatBuffer) {
        // FLOAT32 pixel data into an RGBA16F texture: the backend converts on upload (GL ES 3
        // accepts GL_FLOAT for RGBA16F), which keeps the packing pure-JVM testable and skips a
        // manual half-float conversion.
        positionScaleTexture.setImage(
            engine, 0,
            Texture.PixelBufferDescriptor(positionScale, Texture.Format.RGBA, Texture.Type.FLOAT)
        )
        colorOpacityTexture.setImage(
            engine, 0,
            Texture.PixelBufferDescriptor(colorOpacity, Texture.Format.RGBA, Texture.Type.FLOAT)
        )
    }

    private fun floatBufferOf(vararg values: Float): FloatBuffer =
        ByteBuffer.allocateDirect(values.size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(values)
            .apply { rewind() }

    /**
     * Destroys the batch renderables + entities, this node, the material instances (via
     * [materialLoader]), the shared quad buffers, and frame-defers the data-texture destroy
     * (textures still bound to a reclaiming MaterialInstance must outlive it by a few frames —
     * the [EngineDestroyQueue] contract, see ImageNode / #874).
     */
    override fun destroy() {
        // Re-entry guard: a second destroy() would re-enqueue the data textures
        // on the EngineDestroyQueue (benign today, but future-proof it).
        if (isDestroyed) return
        isDestroyed = true
        coroutineScope.cancel()
        // Renderable components first (while entity ids are still valid), then the entities.
        batchEntities.forEach { engine.safeDestroyRenderable(it) }
        batchEntities.forEach { engine.safeDestroyEntity(it) }
        super.destroy()
        materialInstances.forEach { materialLoader.destroyMaterialInstance(it) }
        engine.safeDestroyVertexBuffer(quadVertexBuffer)
        engine.safeDestroyIndexBuffer(quadIndexBuffer)
        EngineDestroyQueue.of(engine).enqueueTexture(positionScaleTexture)
        EngineDestroyQueue.of(engine).enqueueTexture(colorOpacityTexture)
    }
}
