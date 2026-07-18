package io.github.sceneview.web.nodes

import io.github.sceneview.core.splat.SplatCloud
import io.github.sceneview.math.Position
import io.github.sceneview.math.computeWorldToLocal
import io.github.sceneview.math.worldToLocalPosition
import io.github.sceneview.web.bindings.Engine
import io.github.sceneview.web.bindings.Entity
import io.github.sceneview.web.bindings.IndexBuffer
import io.github.sceneview.web.bindings.Material
import io.github.sceneview.web.bindings.MaterialInstance
import io.github.sceneview.web.bindings.VertexBuffer
import io.github.sceneview.web.splat.SplatWebBuffers
import io.github.sceneview.web.splat.createSplatMaterial
import io.github.sceneview.web.splat.filamentBox
import io.github.sceneview.web.splat.indexBufferBuilder
import io.github.sceneview.web.splat.indexTypeUshort
import io.github.sceneview.web.splat.nearestClampSampler
import io.github.sceneview.web.splat.newSplatEntity
import io.github.sceneview.web.splat.pixelBufferFloat
import io.github.sceneview.web.splat.primitiveTypeTriangles
import io.github.sceneview.web.splat.renderableManagerBuilder
import io.github.sceneview.web.splat.sampler2d
import io.github.sceneview.web.splat.formatRgba16f
import io.github.sceneview.web.splat.textureBuilder
import io.github.sceneview.web.splat.vertexAttributePosition
import io.github.sceneview.web.splat.attributeTypeFloat3
import io.github.sceneview.web.splat.vertexBufferBuilder
import org.khronos.webgl.Float32Array
import org.khronos.webgl.Uint16Array
import kotlin.math.max

/**
 * A node that renders a 3D Gaussian Splatting ([SplatCloud]) scene on the web — the
 * Kotlin/JS + Filament.js port of Android's `io.github.sceneview.node.SplatNode`
 * (#2646 P2). Same Filament engine (WASM/WebGL2 backend), same rendering design:
 *
 * - Each gaussian is a **hardware-instanced camera-facing quad** drawn by the dedicated
 *   `splat_web.filamat` material (embedded in the bundle): the vertex shader fetches the
 *   per-splat centre / half-extent / colour / opacity from two square `RGBA16F` data
 *   textures indexed by the instance id; the fragment applies an isotropic gaussian
 *   falloff with premultiplied-alpha blending. See [SplatWebBuffers] for the texel
 *   layout contract.
 * - **View-dependent painter's sort** — when a [cameraPositionProvider] is set, the node
 *   re-sorts back-to-front whenever the camera has moved beyond ~1% of the cloud radius,
 *   then re-packs + re-uploads the data textures. JS is single-threaded, so the sort runs
 *   synchronously on the frame tick — ~1 ms at the 8k demo scale (the Android node uses a
 *   background dispatcher instead; same order semantics either way).
 * - **Draw batching** — instancing caps at [SplatWebBuffers.MAX_INSTANCES_PER_BATCH]
 *   (65535) per renderable. Larger clouds split into multiple renderable batches under
 *   this single node, sharing the two data textures via each batch's `instanceOffset`
 *   material parameter, composited in texel order via per-batch global `blendOrder` keys.
 *
 * Construction performs Filament WASM calls and must run after `Filament.init()` —
 * always create through [io.github.sceneview.web.addSplatNode], which also registers the
 * batch entities with the scene.
 *
 * The parsers are the shared KMP `sceneview-core` ones — `SplatParser.parse(bytes)`
 * accepts INRIA `.ply` and Niantic `.spz` byte-identically to Android.
 */
class SplatNode internal constructor(
    private val filamentEngine: Engine,
    entity: Entity,
    val splatCloud: SplatCloud,
) : Node(filamentEngine, entity) {

    init {
        require(splatCloud.count > 0) { "splatCloud must contain at least one splat" }
    }

    /**
     * Invoked on the frame loop to obtain the current camera **world** position for the
     * painter's sort (the node maps it into its own model space, so transformed/parented
     * nodes sort correctly). `null` disables view-dependent sorting (expect popping when
     * orbiting to the far side). Wired by `addSplatNode`.
     */
    var cameraPositionProvider: (() -> Position)? = null

    /** Repaint hook (the render gate cannot infer a texture re-upload) — set by the factory. */
    internal var onInvalidate: (() -> Unit)? = null

    /** Scene-detach hook run first on [destroy] — set by the factory. */
    internal var onDetach: ((Array<Entity>) -> Unit)? = null

    /** Side of the two square RGBA16F per-splat data textures. */
    val textureSize = SplatWebBuffers.textureSize(splatCloud.count)

    /** Contiguous instance ranges, one renderable batch each (cap 65535 instances per draw). */
    val batches = SplatWebBuffers.batchRanges(splatCloud.count)

    /** Model-space culling AABB `[cx, cy, cz, hx, hy, hz]` of the whole cloud. */
    private val boundingBox: FloatArray = SplatWebBuffers.boundingBox(splatCloud)

    /** Rough cloud radius — scales the camera-motion threshold that gates a re-sort. */
    private val boundingRadius: Float = max(boundingBox[3], max(boundingBox[4], boundingBox[5]))

    private val material: Material = createSplatMaterial(filamentEngine)
    private val positionScaleTexture = buildDataTexture()
    private val colorOpacityTexture = buildDataTexture()

    /** One material instance per batch (distinct `instanceOffset`). */
    private val materialInstances: List<MaterialInstance> = batches.map { range ->
        material.createInstance().also { instance ->
            val sampler = nearestClampSampler()
            instance.setTextureParameter("splatPositionScale", positionScaleTexture, sampler)
            instance.setTextureParameter("splatColorOpacity", colorOpacityTexture, sampler)
            // float parameters carrying integral values — the web runtime's embind has no
            // setIntParameter (see splat_web.mat's header for the full rationale).
            instance.setFloatParameter("texWidth", textureSize.toDouble())
            instance.setFloatParameter("instanceOffset", range.first.toDouble())
        }
    }

    private val quadVertexBuffer: VertexBuffer
    private val quadIndexBuffer: IndexBuffer

    /** One renderable entity per batch, transform-parented under this node's entity. */
    internal val batchEntities: List<Entity>

    /** Instance count currently baked into each batch renderable (rebuilds are in place). */
    private val batchBuiltCounts: IntArray = IntArray(batches.size) { batches[it].count() }

    /** Instance count currently displayed per batch — `0` means hidden via layer mask. */
    private val batchVisibleCounts: IntArray = IntArray(batches.size) { batches[it].count() }

    private var lastSortCameraPosition: Position? = null

    init {
        // Initial as-loaded order; the first frame with a cameraPositionProvider re-sorts.
        val identityOrder = IntArray(splatCloud.count) { it }
        uploadTextures(
            SplatWebBuffers.packPositionScale(splatCloud, identityOrder, textureSize),
            SplatWebBuffers.packColorOpacity(splatCloud, identityOrder, textureSize)
        )

        // The shared unit quad ([-1, 1]^2 at z = 0) instanced once per splat.
        quadVertexBuffer = vertexBufferBuilder()
            .bufferCount(1)
            .vertexCount(4)
            .attribute(vertexAttributePosition(), 0, attributeTypeFloat3(), 0, 3 * 4)
            .build(filamentEngine)
            .also { vb ->
                val corners = Float32Array(
                    arrayOf(
                        -1f, -1f, 0f,
                        1f, -1f, 0f,
                        1f, 1f, 0f,
                        -1f, 1f, 0f
                    )
                )
                vb.setBufferAt(filamentEngine, 0, corners)
            }
        quadIndexBuffer = indexBufferBuilder()
            .indexCount(6)
            .bufferType(indexTypeUshort())
            .build(filamentEngine)
            .also { ib ->
                ib.setBuffer(filamentEngine, Uint16Array(arrayOf<Short>(0, 1, 2, 0, 2, 3)))
            }

        // One renderable entity per batch, parented under this node's transform so the
        // node's (world) transform drives every batch (the shader reads
        // getWorldFromModelMatrix()).
        batchEntities = batches.indices.map { index ->
            newSplatEntity().also { batchEntity ->
                adoptChildEntity(batchEntity)
                buildBatchRenderable(batchEntity, index, batches[index].count())
            }
        }
    }

    /**
     * Number of splats rendered, `0..splatCloud.count`. Defaults to the full cloud.
     *
     * Lowering it truncates the draw to the first N texels of the current (sorted) order —
     * a cheap LOD / reveal-animation hook, mirroring the Android node. Batches that become
     * empty are hidden via layer mask (instanced draws need >= 1 instance); partially
     * visible batches are rebuilt in place with the reduced instance count.
     */
    var splatCount: Int = splatCloud.count
        set(value) {
            val clamped = value.coerceIn(0, splatCloud.count)
            if (field == clamped || isDestroyed) return
            field = clamped
            val renderableManager = filamentEngine.getRenderableManager()
            batches.forEachIndexed { index, range ->
                val visible = SplatWebBuffers.visibleInBatch(clamped, range)
                if (visible == batchVisibleCounts[index]) return@forEachIndexed
                val batchEntity = batchEntities[index]
                if (visible == 0) {
                    renderableManager.setLayerMask(
                        renderableManager.getInstance(batchEntity), 0xff, 0x00
                    )
                } else {
                    if (visible != batchBuiltCounts[index]) {
                        // Instance count is baked at build time (no runtime setter) —
                        // rebuild the renderable in place on the same entity. The rebuild
                        // resets the layer mask, so re-assert visibility below.
                        renderableManager.destroy(batchEntity)
                        buildBatchRenderable(batchEntity, index, visible)
                        batchBuiltCounts[index] = visible
                    }
                    renderableManager.setLayerMask(
                        renderableManager.getInstance(batchEntity), 0xff, 0xff
                    )
                }
                batchVisibleCounts[index] = visible
            }
            onInvalidate?.invoke()
        }

    /**
     * (Re)builds one batch renderable on [batchEntity] with [instanceCount] instances.
     *
     * All batches share the unit quad geometry, the full-cloud bounding box (instances
     * are displaced in the vertex shader, so per-batch tight boxes would not survive
     * re-sorts), and the two data textures. With several batches, a global `blendOrder`
     * keyed on the batch index keeps Filament compositing them in texel order — the
     * painter's sort packs texels globally back-to-front, so batch 0 is always the
     * farthest slice.
     */
    private fun buildBatchRenderable(batchEntity: Entity, batchIndex: Int, instanceCount: Int) {
        val builder = renderableManagerBuilder()
            .boundingBox(filamentBox(boundingBox))
            .geometry(0, primitiveTypeTriangles(), quadVertexBuffer, quadIndexBuffer)
            .material(0, materialInstances[batchIndex])
            .instances(instanceCount)
            .culling(true)
            .castShadows(false)
            .receiveShadows(false)
        if (batches.size > 1) {
            builder.blendOrder(0, batchIndex)
            builder.globalBlendOrderEnabled(0, true)
        }
        builder.build(filamentEngine, batchEntity)
    }

    /**
     * Re-sorts back-to-front when the camera has moved more than ~1% of the cloud radius
     * since the last sorted position — the same threshold as the Android node. Runs
     * synchronously on the frame tick (single-threaded JS).
     */
    override fun onFrame(deltaTime: Float) {
        val provider = cameraPositionProvider ?: return
        if (isDestroyed) return
        // Map the camera world position into this node's model space so
        // parented/transformed nodes sort correctly (splat positions are model-space).
        val cameraLocal = worldToLocalPosition(provider(), computeWorldToLocal(worldTransform))
        val last = lastSortCameraPosition
        if (last != null) {
            val dx = cameraLocal.x - last.x
            val dy = cameraLocal.y - last.y
            val dz = cameraLocal.z - last.z
            val threshold = max(boundingRadius * 0.01f, 1e-5f)
            if (dx * dx + dy * dy + dz * dz < threshold * threshold) return
        }
        lastSortCameraPosition = cameraLocal
        val order = SplatWebBuffers.sortBackToFront(
            splatCloud.positions, splatCloud.count,
            cameraLocal.x, cameraLocal.y, cameraLocal.z
        )
        uploadTextures(
            SplatWebBuffers.packPositionScale(splatCloud, order, textureSize),
            SplatWebBuffers.packColorOpacity(splatCloud, order, textureSize)
        )
        onInvalidate?.invoke()
    }

    private fun buildDataTexture() = textureBuilder()
        .width(textureSize)
        .height(textureSize)
        .levels(1)
        .sampler(sampler2d())
        .format(formatRgba16f())
        .build(filamentEngine)

    private fun uploadTextures(positionScale: Float32Array, colorOpacity: Float32Array) {
        positionScaleTexture.setImage(filamentEngine, 0, pixelBufferFloat(positionScale))
        colorOpacityTexture.setImage(filamentEngine, 0, pixelBufferFloat(colorOpacity))
    }

    /**
     * Destroys the batch renderables + entities, this node (via `super`), the material
     * instances, the material, the shared quad buffers, and the two data textures —
     * instances strictly before material and textures (Filament asserts on a texture
     * still bound to a live MaterialInstance), the same ordering as the Android node and
     * the light teardown's component-then-entity rule (#1700).
     */
    override fun destroy() {
        if (isDestroyed) return
        // Remove the batch entities from the scene FIRST (the factory's hook) — a
        // destroyed renderable must never be left registered.
        onDetach?.invoke(batchEntities.toTypedArray())
        onDetach = null
        val renderableManager = filamentEngine.getRenderableManager()
        val transformManager = filamentEngine.getTransformManager()
        batchEntities.forEach { batchEntity ->
            if (renderableManager.hasComponent(batchEntity)) renderableManager.destroy(batchEntity)
            if (transformManager.hasComponent(batchEntity)) transformManager.destroy(batchEntity)
            filamentEngine.destroyEntity(batchEntity)
        }
        // Flips isDestroyed, destroys children, detaches, frees this node's entity.
        super.destroy()
        materialInstances.forEach { filamentEngine.destroyMaterialInstance(it) }
        filamentEngine.destroyMaterial(material)
        filamentEngine.destroyVertexBuffer(quadVertexBuffer)
        filamentEngine.destroyIndexBuffer(quadIndexBuffer)
        filamentEngine.destroyTexture(positionScaleTexture)
        filamentEngine.destroyTexture(colorOpacityTexture)
    }
}
