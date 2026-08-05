package io.github.erkko68.filament

import io.github.erkko68.filament.ffm.FilamentC
import java.lang.foreign.MemorySegment

// PrimitiveType is non-sequential in Filament (LINE_LOOP=2 is unused), so map explicitly
// (matches nativeMain / the Android API). GeometryType uses the generated C constants.
private fun RenderableManager.PrimitiveType.toNative(): Int = when (this) {
    RenderableManager.PrimitiveType.POINTS -> 0
    RenderableManager.PrimitiveType.LINES -> 1
    RenderableManager.PrimitiveType.LINE_STRIP -> 3
    RenderableManager.PrimitiveType.TRIANGLES -> 4
    RenderableManager.PrimitiveType.TRIANGLE_STRIP -> 5
}

private fun RenderableManager.GeometryType.toNative(): Int = when (this) {
    RenderableManager.GeometryType.DYNAMIC -> FilamentC.FILA_RENDERABLE_MANAGER_GEOMETRY_TYPE_DYNAMIC()
    RenderableManager.GeometryType.STATIC_BOUNDS -> FilamentC.FILA_RENDERABLE_MANAGER_GEOMETRY_TYPE_STATIC_BOUNDS()
    RenderableManager.GeometryType.STATIC -> FilamentC.FILA_RENDERABLE_MANAGER_GEOMETRY_TYPE_STATIC()
}

actual class RenderableManager internal constructor(internal val nativeHandle: MemorySegment) {
    actual enum class PrimitiveType { POINTS, LINES, LINE_STRIP, TRIANGLES, TRIANGLE_STRIP }
    actual enum class GeometryType { DYNAMIC, STATIC_BOUNDS, STATIC }

    actual class Builder actual constructor(count: Int) {
        private val nativeBuilder = FilamentC.FilaRenderableManagerBuilder_create(count.toLong())

        actual fun geometry(index: Int, type: PrimitiveType, vb: VertexBuffer, ib: IndexBuffer): Builder = apply {
            FilamentC.FilaRenderableManagerBuilder_geometry(nativeBuilder, index.toLong(), type.toNative(), vb.nativeHandle, ib.nativeHandle)
        }
        actual fun geometry(index: Int, type: PrimitiveType, vb: VertexBuffer, ib: IndexBuffer, offset: Int, count: Int): Builder = apply {
            FilamentC.FilaRenderableManagerBuilder_geometryAt(nativeBuilder, index.toLong(), type.toNative(), vb.nativeHandle, ib.nativeHandle, offset.toLong(), count.toLong())
        }
        actual fun geometry(index: Int, type: PrimitiveType, vb: VertexBuffer, ib: IndexBuffer, offset: Int, minIndex: Int, maxIndex: Int, count: Int): Builder = apply {
            FilamentC.FilaRenderableManagerBuilder_geometryWithIndices(nativeBuilder, index.toLong(), type.toNative(), vb.nativeHandle, ib.nativeHandle, offset.toLong(), minIndex.toLong(), maxIndex.toLong(), count.toLong())
        }
        // Filament 1.71.5: non-indexed geometry overloads (no index buffer).
        @PlatformGap(platforms = [FilamentPlatform.WEB], behavior = "throws UnsupportedOperationException — filament.js only binds the indexed geometry overloads.")
        actual fun geometry(index: Int, type: PrimitiveType, vb: VertexBuffer, offset: Int, count: Int): Builder = apply {
            FilamentC.FilaRenderableManagerBuilder_geometryNonIndexed(nativeBuilder, index.toLong(), type.toNative(), vb.nativeHandle, offset.toLong(), count.toLong())
        }
        @PlatformGap(platforms = [FilamentPlatform.WEB], behavior = "throws UnsupportedOperationException — filament.js only binds the indexed geometry overloads.")
        actual fun geometry(index: Int, type: PrimitiveType, vb: VertexBuffer): Builder = apply {
            FilamentC.FilaRenderableManagerBuilder_geometryNonIndexedNone(nativeBuilder, index.toLong(), type.toNative(), vb.nativeHandle)
        }

        @PlatformGap(platforms = [FilamentPlatform.WEB], behavior = "throws an embind \"unbound types\" Error — filament.js does not register Builder.geometryType.")
        actual fun geometryType(type: GeometryType): Builder = apply {
            FilamentC.FilaRenderableManagerBuilder_geometryType(nativeBuilder, type.toNative())
        }
        actual fun material(index: Int, materialInstance: MaterialInstance): Builder = apply {
            FilamentC.FilaRenderableManagerBuilder_material(nativeBuilder, index.toLong(), materialInstance.nativeHandle)
        }
        actual fun blendOrder(index: Int, blendOrder: Int): Builder = apply {
            FilamentC.FilaRenderableManagerBuilder_blendOrder(nativeBuilder, index.toLong(), blendOrder.toShort())
        }
        actual fun globalBlendOrderEnabled(index: Int, enabled: Boolean): Builder = apply {
            FilamentC.FilaRenderableManagerBuilder_globalBlendOrderEnabled(nativeBuilder, index.toLong(), enabled)
        }
        actual fun boundingBox(box: Box): Builder = apply {
            FilamentC.FilaRenderableManagerBuilder_boundingBox(nativeBuilder,
                box.center[0], box.center[1], box.center[2],
                box.halfExtent[0], box.halfExtent[1], box.halfExtent[2])
        }
        actual fun layerMask(select: Int, value: Int): Builder = apply { FilamentC.FilaRenderableManagerBuilder_layerMask(nativeBuilder, select.toByte(), value.toByte()) }
        actual fun priority(priority: Int): Builder = apply { FilamentC.FilaRenderableManagerBuilder_priority(nativeBuilder, priority.toByte()) }
        actual fun channel(channel: Int): Builder = apply { FilamentC.FilaRenderableManagerBuilder_channel(nativeBuilder, channel.toByte()) }
        actual fun culling(enabled: Boolean): Builder = apply { FilamentC.FilaRenderableManagerBuilder_culling(nativeBuilder, enabled) }
        actual fun castShadows(enabled: Boolean): Builder = apply { FilamentC.FilaRenderableManagerBuilder_castShadows(nativeBuilder, enabled) }
        actual fun receiveShadows(enabled: Boolean): Builder = apply { FilamentC.FilaRenderableManagerBuilder_receiveShadows(nativeBuilder, enabled) }
        actual fun screenSpaceContactShadows(enabled: Boolean): Builder = apply { FilamentC.FilaRenderableManagerBuilder_screenSpaceContactShadows(nativeBuilder, enabled) }
        actual fun skinning(boneCount: Int): Builder = apply { FilamentC.FilaRenderableManagerBuilder_skinning(nativeBuilder, boneCount) }
        @PlatformGap(platforms = [FilamentPlatform.WEB], behavior = "silent no-op — filament.js only binds the bone-count skinning overload; glTF skinning works through gltfio.")
        actual fun skinning(boneCount: Int, bones: FloatArray): Builder = apply {
            confined { arena -> FilamentC.FilaRenderableManagerBuilder_skinningBones(nativeBuilder, boneCount, arena.floats(bones)) }
        }
        @PlatformGap(platforms = [FilamentPlatform.WEB], behavior = "silent no-op — SkinningBuffer itself is unbound in filament.js; glTF skinning works through gltfio.")
        actual fun skinning(skinningBuffer: SkinningBuffer, boneCount: Int, offset: Int): Builder = apply {
            FilamentC.FilaRenderableManagerBuilder_skinningBuffer(nativeBuilder, skinningBuffer.nativeHandle, boneCount, offset)
        }
        @PlatformGap(platforms = [FilamentPlatform.WEB], behavior = "silent no-op — SkinningBuffer itself is unbound in filament.js.")
        actual fun enableSkinningBuffers(enabled: Boolean): Builder = apply {
            FilamentC.FilaRenderableManagerBuilder_enableSkinningBuffers(nativeBuilder, enabled)
        }
        @PlatformGap(platforms = [FilamentPlatform.WEB], behavior = "degraded — filament.js only binds a boolean enable, so the target count is reduced to targetCount > 0.")
        actual fun morphing(targetCount: Int): Builder = apply { FilamentC.FilaRenderableManagerBuilder_morphing(nativeBuilder, targetCount) }
        @PlatformGap(platforms = [FilamentPlatform.WEB], behavior = "silent no-op — MorphTargetBuffer itself is unbound in filament.js; glTF morphing works through gltfio.")
        actual fun morphing(morphTargetBuffer: MorphTargetBuffer): Builder = apply {
            FilamentC.FilaRenderableManagerBuilder_morphTargetBuffer(nativeBuilder, morphTargetBuffer.nativeHandle)
        }
        actual fun fog(enabled: Boolean): Builder = apply { FilamentC.FilaRenderableManagerBuilder_fog(nativeBuilder, enabled) }
        actual fun lightChannel(channel: Int, enable: Boolean): Builder = apply { FilamentC.FilaRenderableManagerBuilder_lightChannel(nativeBuilder, channel, enable) }
        actual fun instances(instanceCount: Int): Builder = apply { FilamentC.FilaRenderableManagerBuilder_instances(nativeBuilder, instanceCount.toLong()) }
        actual fun build(engine: Engine, entity: Entity) {
            FilamentC.FilaRenderableManagerBuilder_build(nativeBuilder, engine.nativeHandle, entity)
            FilamentC.FilaRenderableManagerBuilder_destroy(nativeBuilder)
        }
    }

    actual fun hasComponent(entity: Entity): Boolean = FilamentC.FilaRenderableManager_hasComponent(nativeHandle, entity)
    actual fun getInstance(entity: Entity): EntityInstance = FilamentC.FilaRenderableManager_getInstance(nativeHandle, entity)
    actual fun destroy(entity: Entity) = FilamentC.FilaRenderableManager_destroy(nativeHandle, entity)

    actual fun setAxisAlignedBoundingBox(instance: EntityInstance, box: Box) {
        FilamentC.FilaRenderableManager_setAxisAlignedBoundingBox(nativeHandle, instance,
            box.center[0], box.center[1], box.center[2],
            box.halfExtent[0], box.halfExtent[1], box.halfExtent[2])
    }
    actual fun getAxisAlignedBoundingBox(instance: EntityInstance, outBox: Box?): Box {
        return confined { arena ->
            val center = arena.floatArr(3)
            val halfExtent = arena.floatArr(3)
            FilamentC.FilaRenderableManager_getAxisAlignedBoundingBox(nativeHandle, instance, center, halfExtent)
            val result = outBox ?: Box()
            val c = center.toFloats(); val h = halfExtent.toFloats()
            result.center[0] = c[0]; result.center[1] = c[1]; result.center[2] = c[2]
            result.halfExtent[0] = h[0]; result.halfExtent[1] = h[1]; result.halfExtent[2] = h[2]
            result
        }
    }

    actual fun setLayerMask(instance: EntityInstance, select: Int, value: Int) = FilamentC.FilaRenderableManager_setLayerMask(nativeHandle, instance, select.toByte(), value.toByte())
    actual fun setPriority(instance: EntityInstance, priority: Int) = FilamentC.FilaRenderableManager_setPriority(nativeHandle, instance, priority.toByte())
    actual fun getPriority(instance: EntityInstance): Int = FilamentC.FilaRenderableManager_getPriority(nativeHandle, instance).toInt()
    actual fun setChannel(instance: EntityInstance, channel: Int) = FilamentC.FilaRenderableManager_setChannel(nativeHandle, instance, channel.toByte())
    actual fun getChannel(instance: EntityInstance): Int = FilamentC.FilaRenderableManager_getChannel(nativeHandle, instance).toInt()
    actual fun setCulling(instance: EntityInstance, enabled: Boolean) = FilamentC.FilaRenderableManager_setCulling(nativeHandle, instance, enabled)
    actual fun isCullingEnabled(instance: EntityInstance): Boolean = FilamentC.FilaRenderableManager_isCullingEnabled(nativeHandle, instance)
    actual fun setFogEnabled(instance: EntityInstance, enabled: Boolean) = FilamentC.FilaRenderableManager_setFogEnabled(nativeHandle, instance, enabled)
    actual fun getFogEnabled(instance: EntityInstance): Boolean = FilamentC.FilaRenderableManager_getFogEnabled(nativeHandle, instance)
    actual fun setCastShadows(instance: EntityInstance, enabled: Boolean) = FilamentC.FilaRenderableManager_setCastShadows(nativeHandle, instance, enabled)
    actual fun setReceiveShadows(instance: EntityInstance, enabled: Boolean) = FilamentC.FilaRenderableManager_setReceiveShadows(nativeHandle, instance, enabled)
    actual fun setScreenSpaceContactShadows(instance: EntityInstance, enabled: Boolean) = FilamentC.FilaRenderableManager_setScreenSpaceContactShadows(nativeHandle, instance, enabled)
    actual fun isShadowCaster(instance: EntityInstance): Boolean = FilamentC.FilaRenderableManager_isShadowCaster(nativeHandle, instance)
    actual fun isShadowReceiver(instance: EntityInstance): Boolean = FilamentC.FilaRenderableManager_isShadowReceiver(nativeHandle, instance)
    actual fun isScreenSpaceContactShadowsEnabled(instance: EntityInstance): Boolean = FilamentC.FilaRenderableManager_isScreenSpaceContactShadowsEnabled(nativeHandle, instance)

    actual fun getPrimitiveCount(instance: EntityInstance): Int = FilamentC.FilaRenderableManager_getPrimitiveCount(nativeHandle, instance)
    actual fun getInstanceCount(instance: EntityInstance): Int = FilamentC.FilaRenderableManager_getInstanceCount(nativeHandle, instance)

    actual fun setMaterialInstanceAt(instance: EntityInstance, primitiveIndex: Int, materialInstance: MaterialInstance) {
        FilamentC.FilaRenderableManager_setMaterialInstanceAt(nativeHandle, instance, primitiveIndex.toLong(), materialInstance.nativeHandle)
    }

    actual fun getMaterialInstanceAt(instance: EntityInstance, primitiveIndex: Int): MaterialInstance? {
        val handle = FilamentC.FilaRenderableManager_getMaterialInstanceAt(nativeHandle, instance, primitiveIndex.toLong())
        return if (!handle.isNullPtr()) MaterialInstance(handle) else null
    }

    actual fun getEnabledAttributesAt(instance: EntityInstance, primitiveIndex: Int): Set<VertexBuffer.VertexAttribute> =
        attributeBitsetToSet(FilamentC.FilaRenderableManager_getEnabledAttributesAt(nativeHandle, instance, primitiveIndex.toLong()))

    actual fun setGeometryAt(instance: EntityInstance, primitiveIndex: Int, type: PrimitiveType, vb: VertexBuffer, ib: IndexBuffer, offset: Int, count: Int) =
        FilamentC.FilaRenderableManager_setGeometryAt(nativeHandle, instance, primitiveIndex.toLong(), type.toNative(), vb.nativeHandle, ib.nativeHandle, offset.toLong(), count.toLong())

    // Filament 1.71.5: non-indexed setGeometryAt (no index buffer).
    @PlatformGap(platforms = [FilamentPlatform.WEB], behavior = "throws UnsupportedOperationException — filament.js only binds the indexed setGeometryAt overload.")
    actual fun setGeometryAt(instance: EntityInstance, primitiveIndex: Int, type: PrimitiveType, vb: VertexBuffer, offset: Int, count: Int) =
        FilamentC.FilaRenderableManager_setGeometryAtNonIndexed(nativeHandle, instance, primitiveIndex.toLong(), type.toNative(), vb.nativeHandle, offset.toLong(), count.toLong())

    actual fun setBlendOrderAt(instance: EntityInstance, primitiveIndex: Int, blendOrder: Int) =
        FilamentC.FilaRenderableManager_setBlendOrderAt(nativeHandle, instance, primitiveIndex.toLong(), blendOrder.toShort())
    actual fun getBlendOrderAt(instance: EntityInstance, primitiveIndex: Int): Int = FilamentC.FilaRenderableManager_getBlendOrderAt(nativeHandle, instance, primitiveIndex.toLong()).toInt()
    actual fun setGlobalBlendOrderEnabledAt(instance: EntityInstance, primitiveIndex: Int, enabled: Boolean) =
        FilamentC.FilaRenderableManager_setGlobalBlendOrderEnabledAt(nativeHandle, instance, primitiveIndex.toLong(), enabled)
    actual fun isGlobalBlendOrderEnabledAt(instance: EntityInstance, primitiveIndex: Int): Boolean =
        FilamentC.FilaRenderableManager_isGlobalBlendOrderEnabledAt(nativeHandle, instance, primitiveIndex.toLong())

    actual fun setLightChannel(instance: EntityInstance, channel: Int, enable: Boolean) = FilamentC.FilaRenderableManager_setLightChannel(nativeHandle, instance, channel, enable)
    actual fun getLightChannel(instance: EntityInstance, channel: Int): Boolean = FilamentC.FilaRenderableManager_getLightChannel(nativeHandle, instance, channel)

    actual fun getMorphTargetCount(instance: EntityInstance): Int = FilamentC.FilaRenderableManager_getMorphTargetCount(nativeHandle, instance)

    actual fun setSkinningBuffer(instance: EntityInstance, skinningBuffer: SkinningBuffer, count: Int, offset: Int) {
        FilamentC.FilaRenderableManager_setSkinningBuffer(nativeHandle, instance, skinningBuffer.nativeHandle, count, offset)
    }

    actual fun setMorphWeights(instance: EntityInstance, weights: FloatArray, offset: Int) {
        confined { arena ->
            val seg = arena.floats(weights)
            FilamentC.FilaRenderableManager_setMorphWeights(nativeHandle, instance, seg.asSlice(offset.toLong() * 4L), weights.size - offset, offset)
        }
    }

    actual fun setMorphTargetBufferOffsetAt(instance: EntityInstance, level: Int, primitiveIndex: Int, offset: Int) {
        FilamentC.FilaRenderableManager_setMorphTargetBufferOffsetAt(nativeHandle, instance, level.toByte(), primitiveIndex.toLong(), offset.toLong())
    }

    actual fun setBonesAsMatrices(instance: EntityInstance, matrices: FloatArray, boneCount: Int, offset: Int) {
        confined { arena ->
            FilamentC.FilaRenderableManager_setBonesAsMatrices(nativeHandle, instance, arena.floats(matrices), boneCount, offset)
        }
    }

    actual fun setBonesAsQuaternions(instance: EntityInstance, quaternions: FloatArray, boneCount: Int, offset: Int) {
        confined { arena ->
            FilamentC.FilaRenderableManager_setBonesAsQuaternions(nativeHandle, instance, arena.floats(quaternions), boneCount, offset)
        }
    }

    actual fun clearMaterialInstanceAt(instance: EntityInstance, primitiveIndex: Int) {
        FilamentC.FilaRenderableManager_clearMaterialInstanceAt(nativeHandle, instance, primitiveIndex.toLong())
    }
}
