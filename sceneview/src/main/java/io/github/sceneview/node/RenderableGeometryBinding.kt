package io.github.sceneview.node

import com.google.android.filament.Engine
import com.google.android.filament.RenderableManager
import io.github.sceneview.Entity
import io.github.sceneview.bumpRenderableGeneration
import io.github.sceneview.components.RenderableComponent
import io.github.sceneview.geometries.Geometry
import io.github.sceneview.geometries.geometry
import io.github.sceneview.geometries.mergedForPrimitiveCount
import io.github.sceneview.geometries.setGeometry
import io.github.sceneview.model.Model
import io.github.sceneview.renderableGeneration
import io.github.sceneview.safeDestroyGeometry
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** A geometry consumer follows the Filament component, which can outlive its node wrapper. */
internal class RenderableGeometryBinding(
    override val engine: Engine,
    override val entity: Entity,
) : RenderableComponent, Geometry.Consumer {
    private var boundGeometry: Geometry? = null
    private var mergeGeometryPrimitives = false
    var replayGeometryBuilder: RenderableManager.Builder.() -> Unit = {}
    private var layerMaskSelect = 0
    private var layerMaskValue = 0
    // Never retain the wrapper (and its enclosing ModelNode/asset) through the asset registry.
    private val nodes = mutableListOf<WeakReference<RenderableNode>>()
    private var cachedInstance = 0
    private var cachedGeneration = -1

    // Geometry updates can run every frame. Share RenderableNode's compaction-safe cache rule.
    override val renderableInstance: Int
        get() {
            val generation = engine.renderableGeneration()
            if (cachedInstance == 0 || cachedGeneration != generation) {
                cachedInstance = renderableManager.getInstance(entity)
                cachedGeneration = generation
            }
            return cachedInstance
        }

    fun attachNode(node: RenderableNode) {
        detachNode(node)
        nodes += WeakReference(node)
    }

    fun detachNode(node: RenderableNode) {
        nodes.removeAll { it.get() == null || it.get() === node }
    }

    override fun setLayerMask(select: Int, value: Int) {
        // Preserve only explicitly selected bits when replaying a builder after a topology change.
        layerMaskSelect = layerMaskSelect or select
        layerMaskValue = (layerMaskValue and select.inv()) or (value and select)
    }

    override fun validate(primitiveCount: Int) {
        require(primitiveCount > 0) { "A rendered geometry requires at least one primitive" }
        if (!mergeGeometryPrimitives && primitiveCount != this.primitiveCount) {
            require(morphTargetCount == 0) {
                "Changing primitive count on a morphed renderable is unsupported"
            }
        }
    }

    override fun rebind() = applyBoundGeometry()

    fun setGeometry(
        geometry: Geometry,
        offsets: List<IntRange>,
        merged: Boolean = false
    ) {
        // From the offsets, not from `primitivesIndices`: while a rebuild waits for the previous
        // upload the indices are already the new ones, and the buffers still the old ones.
        require((merged && offsets == geometry.primitivesOffsets.mergedForPrimitiveCount(1)) ||
            (!merged && offsets == geometry.primitivesOffsets)) {
            "Dynamic geometry requires natural primitive offsets or one range covering all indices"
        }
        val previous = boundGeometry
        val previousMerge = mergeGeometryPrimitives
        if (previous !== geometry) geometry.attach(this)
        boundGeometry = geometry
        mergeGeometryPrimitives = merged
        try {
            applyBoundGeometry()
        } catch (failure: Throwable) {
            boundGeometry = previous
            mergeGeometryPrimitives = previousMerge
            if (previous !== geometry) geometry.detach(this)
            throw failure
        }
        if (previous !== geometry) {
            previous?.detach(this)
            previous?.let { engine.safeDestroyGeometry(it) }
        }
    }

    private fun applyBoundGeometry() {
        val geometry = boundGeometry ?: return
        val offsets = if (mergeGeometryPrimitives) {
            geometry.primitivesOffsets.mergedForPrimitiveCount(1)
        } else geometry.primitivesOffsets
        validate(geometry.primitivesOffsets.size)
        if (primitiveCount != offsets.size) rebuildGeometryRenderable(geometry, offsets)
        renderableManager.setGeometry(renderableInstance, geometry, offsets)
        nodes.removeAll { it.get() == null }
        nodes.forEach { reference ->
            reference.get()?.let { node ->
                node.onGeometryApplied()
                node.onComponentChanged()
                if (!node.hasCustomCollisionShape) node.updateCollisionShape()
            }
        }
    }

    // Filament cannot resize a renderable's primitive array with setGeometryAt. Rebuild only
    // when that array changes, keeping the entity, live materials and accessible render flags.
    // New submeshes inherit the last material; removed submeshes cease drawing.
    //
    // What survives is what Filament lets us read back (priority, channel, culling, fog, shadow
    // flags, light channels, instance count, materials, blend orders), the layer mask tracked by
    // setLayerMask, and whatever GeometryNode's builderApply sets. A skinning buffer, bone
    // matrices, an InstanceBuffer or morph targets configured any other way have no getter and
    // are NOT carried over: set them in builderApply, or re-apply them after the update.
    private fun rebuildGeometryRenderable(geometry: Geometry, offsets: List<IntRange>) {
        val manager = renderableManager
        val instance = renderableInstance
        val materials = List(primitiveCount) { getMaterialInstanceAt(it) }
        val blendOrders = List(primitiveCount) { manager.getBlendOrderAt(instance, it) }
        val globalBlend = List(primitiveCount) { manager.isGlobalBlendOrderEnabledAt(instance, it) }
        val priority = manager.getPriority(instance)
        val channel = manager.getChannel(instance)
        val culling = manager.isCullingEnabled(instance)
        val fog = manager.getFogEnabled(instance)
        val cast = manager.isShadowCaster(instance)
        val receive = manager.isShadowReceiver(instance)
        val contact = manager.isScreenSpaceContactShadowsEnabled(instance)
        val lightChannels = List(8) { manager.getLightChannel(instance, it) }
        val instanceCount = manager.getInstanceCount(instance)
        val builder = RenderableManager.Builder(offsets.size)
            .apply(replayGeometryBuilder)
            .geometry(geometry, offsets)
            .priority(priority).channel(channel).culling(culling).fog(fog)
            .castShadows(cast).receiveShadows(receive).screenSpaceContactShadows(contact)
            .instances(instanceCount)
        lightChannels.forEachIndexed { index, enabled -> builder.lightChannel(index, enabled) }
        if (layerMaskSelect != 0) builder.layerMask(layerMaskSelect, layerMaskValue)
        offsets.indices.forEach { index ->
            val source = index.coerceAtMost(materials.lastIndex)
            materials[source]?.let { builder.material(index, it) }
            builder.blendOrder(index, blendOrders[source])
            builder.globalBlendOrderEnabled(index, globalBlend[source])
        }
        try {
            builder.build(engine, entity)
        } finally {
            // Replacing a component can compact the manager, invalidating other nodes' handles.
            engine.bumpRenderableGeneration()
        }
    }

    fun release() {
        boundGeometry?.let {
            it.detach(this)
            engine.safeDestroyGeometry(it)
        }
        boundGeometry = null
        nodes.clear()
    }
}

/** One binding per model entity, shared across Reset and overlapping Compose wrappers. */
internal object ModelGeometryBindings {
    private val models = WeakHashMap<Model, MutableMap<Entity, RenderableGeometryBinding>>()

    fun get(model: Model, entity: Entity): RenderableGeometryBinding? = models[model]?.get(entity)

    fun getOrCreate(model: Model, entity: Entity): RenderableGeometryBinding =
        models.getOrPut(model) { mutableMapOf() }.getOrPut(entity) {
            RenderableGeometryBinding(model.engine, entity)
        }

    // Called only after gltfio has removed the components, or after their engine is gone.
    fun release(model: Model) {
        models.remove(model)?.values?.forEach { it.release() }
    }
}
