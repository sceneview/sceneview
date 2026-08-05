#include <filament/RenderableManager.h>
#include <filament/VertexBuffer.h>
#include <filament/IndexBuffer.h>
#include <filament/MaterialInstance.h>
#include <filament/SkinningBuffer.h>
#include <filament/MorphTargetBuffer.h>
#include <filament/Engine.h>

#include <utils/Entity.h>
#include <math/vec3.h>
#include <math/mat4.h>

#include "FilaCommon.h"
#include "../c/RenderableManager.h"

using namespace filament;
using namespace utils;

extern "C" {

FilaRenderableManagerBuilder* FilaRenderableManagerBuilder_create(size_t count) {
    return reinterpret_cast<FilaRenderableManagerBuilder*>(new RenderableManager::Builder(count));
}

void FilaRenderableManagerBuilder_destroy(FilaRenderableManagerBuilder* builder) {
    delete reinterpret_cast<RenderableManager::Builder*>(builder);
}

bool FilaRenderableManagerBuilder_build(FilaRenderableManagerBuilder* builder, FilaEngine* engine, FilaEntity entity) {
    return FILA_CAST(RenderableManager::Builder, builder)->build(*FILA_CAST(Engine, engine), Entity::import(entity)) 
           == RenderableManager::Builder::Success;
}

void FilaRenderableManagerBuilder_geometry(FilaRenderableManagerBuilder* builder, size_t index,
        FilaRenderableManagerPrimitiveType type, FilaVertexBuffer* vb, FilaIndexBuffer* ib) {
    FILA_CAST(RenderableManager::Builder, builder)->geometry(index, static_cast<RenderableManager::PrimitiveType>(type),
            FILA_CAST(VertexBuffer, vb), FILA_CAST(IndexBuffer, ib));
}

void FilaRenderableManagerBuilder_geometryAt(FilaRenderableManagerBuilder* builder, size_t index,
        FilaRenderableManagerPrimitiveType type, FilaVertexBuffer* vb, FilaIndexBuffer* ib, size_t offset, size_t count) {
    FILA_CAST(RenderableManager::Builder, builder)->geometry(index, static_cast<RenderableManager::PrimitiveType>(type),
            FILA_CAST(VertexBuffer, vb), FILA_CAST(IndexBuffer, ib), offset, count);
}

void FilaRenderableManagerBuilder_geometryWithIndices(FilaRenderableManagerBuilder* builder, size_t index,
        FilaRenderableManagerPrimitiveType type, FilaVertexBuffer* vb, FilaIndexBuffer* ib, size_t offset, size_t minIndex, size_t maxIndex, size_t count) {
    FILA_CAST(RenderableManager::Builder, builder)->geometry(index, static_cast<RenderableManager::PrimitiveType>(type),
            FILA_CAST(VertexBuffer, vb), FILA_CAST(IndexBuffer, ib), offset, minIndex, maxIndex, count);
}

void FilaRenderableManagerBuilder_geometryNonIndexed(FilaRenderableManagerBuilder* builder, size_t index,
        FilaRenderableManagerPrimitiveType type, FilaVertexBuffer* vb, size_t offset, size_t count) {
    FILA_CAST(RenderableManager::Builder, builder)->geometry(index, static_cast<RenderableManager::PrimitiveType>(type),
            FILA_CAST(VertexBuffer, vb), offset, count);
}

void FilaRenderableManagerBuilder_geometryNonIndexedNone(FilaRenderableManagerBuilder* builder, size_t index,
        FilaRenderableManagerPrimitiveType type, FilaVertexBuffer* vb) {
    FILA_CAST(RenderableManager::Builder, builder)->geometry(index, static_cast<RenderableManager::PrimitiveType>(type),
            FILA_CAST(VertexBuffer, vb));
}

void FilaRenderableManagerBuilder_geometryType(FilaRenderableManagerBuilder* builder, FilaRenderableManagerGeometryType type) {
    FILA_CAST(RenderableManager::Builder, builder)->geometryType(static_cast<RenderableManager::Builder::GeometryType>(type));
}

void FilaRenderableManagerBuilder_material(FilaRenderableManagerBuilder* builder, size_t index, const FilaMaterialInstance* materialInstance) {
    FILA_CAST(RenderableManager::Builder, builder)->material(index, FILA_CONST_CAST(MaterialInstance, materialInstance));
}

void FilaRenderableManagerBuilder_blendOrder(FilaRenderableManagerBuilder* builder, size_t index, uint16_t blendOrder) {
    FILA_CAST(RenderableManager::Builder, builder)->blendOrder(index, blendOrder);
}

void FilaRenderableManagerBuilder_globalBlendOrderEnabled(FilaRenderableManagerBuilder* builder, size_t index, bool enabled) {
    FILA_CAST(RenderableManager::Builder, builder)->globalBlendOrderEnabled(index, enabled);
}

void FilaRenderableManagerBuilder_boundingBox(FilaRenderableManagerBuilder* builder, float cx, float cy, float cz, float ex, float ey, float ez) {
    FILA_CAST(RenderableManager::Builder, builder)->boundingBox({{cx, cy, cz}, {ex, ey, ez}});
}

void FilaRenderableManagerBuilder_layerMask(FilaRenderableManagerBuilder* builder, uint8_t select, uint8_t value) {
    FILA_CAST(RenderableManager::Builder, builder)->layerMask(select, value);
}

void FilaRenderableManagerBuilder_priority(FilaRenderableManagerBuilder* builder, uint8_t priority) {
    FILA_CAST(RenderableManager::Builder, builder)->priority(priority);
}

void FilaRenderableManagerBuilder_channel(FilaRenderableManagerBuilder* builder, uint8_t channel) {
    FILA_CAST(RenderableManager::Builder, builder)->channel(channel);
}

void FilaRenderableManagerBuilder_culling(FilaRenderableManagerBuilder* builder, bool enabled) {
    FILA_CAST(RenderableManager::Builder, builder)->culling(enabled);
}

void FilaRenderableManagerBuilder_castShadows(FilaRenderableManagerBuilder* builder, bool enabled) {
    FILA_CAST(RenderableManager::Builder, builder)->castShadows(enabled);
}

void FilaRenderableManagerBuilder_receiveShadows(FilaRenderableManagerBuilder* builder, bool enabled) {
    FILA_CAST(RenderableManager::Builder, builder)->receiveShadows(enabled);
}

void FilaRenderableManagerBuilder_screenSpaceContactShadows(FilaRenderableManagerBuilder* builder, bool enabled) {
    FILA_CAST(RenderableManager::Builder, builder)->screenSpaceContactShadows(enabled);
}

void FilaRenderableManagerBuilder_skinningBuffer(FilaRenderableManagerBuilder* builder, FilaSkinningBuffer* sb, uint32_t boneCount, uint32_t offset) {
    FILA_CAST(RenderableManager::Builder, builder)->skinning(FILA_CAST(SkinningBuffer, sb), boneCount, offset);
}

void FilaRenderableManagerBuilder_skinning(FilaRenderableManagerBuilder* builder, uint32_t boneCount) {
    FILA_CAST(RenderableManager::Builder, builder)->skinning(boneCount);
}

void FilaRenderableManagerBuilder_skinningBones(FilaRenderableManagerBuilder* builder, uint32_t boneCount, const void* bones) {
    FILA_CAST(RenderableManager::Builder, builder)->skinning(boneCount, static_cast<RenderableManager::Bone const*>(bones));
}

void FilaRenderableManagerBuilder_enableSkinningBuffers(FilaRenderableManagerBuilder* builder, bool enabled) {
    FILA_CAST(RenderableManager::Builder, builder)->enableSkinningBuffers(enabled);
}

void FilaRenderableManagerBuilder_morphing(FilaRenderableManagerBuilder* builder, uint32_t targetCount) {
    FILA_CAST(RenderableManager::Builder, builder)->morphing(targetCount);
}

void FilaRenderableManagerBuilder_morphTargetBuffer(FilaRenderableManagerBuilder* builder, FilaMorphTargetBuffer* mtb) {
    FILA_CAST(RenderableManager::Builder, builder)->morphing(FILA_CAST(MorphTargetBuffer, mtb));
}

void FilaRenderableManagerBuilder_morphTargetBufferOffsetAt(FilaRenderableManagerBuilder* builder, uint32_t level, uint32_t primitiveIndex, uint32_t offset) {
    FILA_CAST(RenderableManager::Builder, builder)->morphing(level, primitiveIndex, offset);
}

void FilaRenderableManagerBuilder_fog(FilaRenderableManagerBuilder* builder, bool enabled) {
    FILA_CAST(RenderableManager::Builder, builder)->fog(enabled);
}

void FilaRenderableManagerBuilder_lightChannel(FilaRenderableManagerBuilder* builder, unsigned int channel, bool enable) {
    FILA_CAST(RenderableManager::Builder, builder)->lightChannel(channel, enable);
}

void FilaRenderableManagerBuilder_instances(FilaRenderableManagerBuilder* builder, size_t instanceCount) {
    FILA_CAST(RenderableManager::Builder, builder)->instances(instanceCount);
}

// RenderableManager
bool FilaRenderableManager_hasComponent(const FilaRenderableManager* rm, FilaEntity entity) {
    return FILA_CONST_CAST(RenderableManager, rm)->hasComponent(Entity::import(entity));
}

FilaRenderableManagerInstance FilaRenderableManager_getInstance(const FilaRenderableManager* rm, FilaEntity entity) {
    return FILA_CONST_CAST(RenderableManager, rm)->getInstance(Entity::import(entity)).asValue();
}

void FilaRenderableManager_destroy(FilaRenderableManager* rm, FilaEntity entity) {
    FILA_CAST(RenderableManager, rm)->destroy(Entity::import(entity));
}

void FilaRenderableManager_setSkinningBuffer(FilaRenderableManager* rm, FilaRenderableManagerInstance instance, FilaSkinningBuffer* sb, uint32_t count, uint32_t offset) {
    FILA_CAST(RenderableManager, rm)->setSkinningBuffer(RenderableManager::Instance(instance), FILA_CAST(SkinningBuffer, sb), count, offset);
}

void FilaRenderableManager_setBonesAsMatrices(FilaRenderableManager* rm, FilaRenderableManagerInstance instance, const float* matrices, uint32_t boneCount, uint32_t offset) {
    FILA_CAST(RenderableManager, rm)->setBones(RenderableManager::Instance(instance),
            reinterpret_cast<filament::math::mat4f const *>(matrices), (size_t)boneCount, (size_t)offset);
}

void FilaRenderableManager_setBonesAsQuaternions(FilaRenderableManager* rm, FilaRenderableManagerInstance instance, const void* bones, uint32_t boneCount, uint32_t offset) {
    FILA_CAST(RenderableManager, rm)->setBones(RenderableManager::Instance(instance),
            static_cast<RenderableManager::Bone const *>(bones), (size_t)boneCount, (size_t)offset);
}

void FilaRenderableManager_setMorphWeights(FilaRenderableManager* rm, FilaRenderableManagerInstance instance, const float* weights, uint32_t count, uint32_t offset) {
    FILA_CAST(RenderableManager, rm)->setMorphWeights(RenderableManager::Instance(instance), weights, count, offset);
}

void FilaRenderableManager_setMorphTargetBufferOffsetAt(FilaRenderableManager* rm, FilaRenderableManagerInstance instance, uint8_t level, size_t primitiveIndex, size_t offset) {
    FILA_CAST(RenderableManager, rm)->setMorphTargetBufferOffsetAt(RenderableManager::Instance(instance), level, primitiveIndex, offset);
}

uint32_t FilaRenderableManager_getMorphTargetCount(const FilaRenderableManager* rm, FilaRenderableManagerInstance instance) {
    return FILA_CONST_CAST(RenderableManager, rm)->getMorphTargetCount(RenderableManager::Instance(instance));
}

void FilaRenderableManager_setAxisAlignedBoundingBox(FilaRenderableManager* rm, FilaRenderableManagerInstance instance, float cx, float cy, float cz, float ex, float ey, float ez) {
    FILA_CAST(RenderableManager, rm)->setAxisAlignedBoundingBox(RenderableManager::Instance(instance), {{cx, cy, cz}, {ex, ey, ez}});
}

void FilaRenderableManager_getAxisAlignedBoundingBox(const FilaRenderableManager* rm, FilaRenderableManagerInstance instance, float center[3], float halfExtent[3]) {
    Box const &aabb = FILA_CONST_CAST(RenderableManager, rm)->getAxisAlignedBoundingBox(RenderableManager::Instance(instance));
    center[0] = aabb.center.x; center[1] = aabb.center.y; center[2] = aabb.center.z;
    halfExtent[0] = aabb.halfExtent.x; halfExtent[1] = aabb.halfExtent.y; halfExtent[2] = aabb.halfExtent.z;
}

void FilaRenderableManager_setLayerMask(FilaRenderableManager* rm, FilaRenderableManagerInstance instance, uint8_t select, uint8_t value) {
    FILA_CAST(RenderableManager, rm)->setLayerMask(RenderableManager::Instance(instance), select, value);
}

void FilaRenderableManager_setPriority(FilaRenderableManager* rm, FilaRenderableManagerInstance instance, uint8_t priority) {
    FILA_CAST(RenderableManager, rm)->setPriority(RenderableManager::Instance(instance), priority);
}

uint8_t FilaRenderableManager_getPriority(const FilaRenderableManager* rm, FilaRenderableManagerInstance instance) {
    return FILA_CONST_CAST(RenderableManager, rm)->getPriority(RenderableManager::Instance(instance));
}

void FilaRenderableManager_setChannel(FilaRenderableManager* rm, FilaRenderableManagerInstance instance, uint8_t channel) {
    FILA_CAST(RenderableManager, rm)->setChannel(RenderableManager::Instance(instance), channel);
}

uint8_t FilaRenderableManager_getChannel(const FilaRenderableManager* rm, FilaRenderableManagerInstance instance) {
    return FILA_CONST_CAST(RenderableManager, rm)->getChannel(RenderableManager::Instance(instance));
}

void FilaRenderableManager_setCulling(FilaRenderableManager* rm, FilaRenderableManagerInstance instance, bool enabled) {
    FILA_CAST(RenderableManager, rm)->setCulling(RenderableManager::Instance(instance), enabled);
}

bool FilaRenderableManager_isCullingEnabled(const FilaRenderableManager* rm, FilaRenderableManagerInstance instance) {
    return FILA_CONST_CAST(RenderableManager, rm)->isCullingEnabled(RenderableManager::Instance(instance));
}

void FilaRenderableManager_setFogEnabled(FilaRenderableManager* rm, FilaRenderableManagerInstance instance, bool enabled) {
    FILA_CAST(RenderableManager, rm)->setFogEnabled(RenderableManager::Instance(instance), enabled);
}

bool FilaRenderableManager_getFogEnabled(const FilaRenderableManager* rm, FilaRenderableManagerInstance instance) {
    return FILA_CONST_CAST(RenderableManager, rm)->getFogEnabled(RenderableManager::Instance(instance));
}

void FilaRenderableManager_setCastShadows(FilaRenderableManager* rm, FilaRenderableManagerInstance instance, bool enabled) {
    FILA_CAST(RenderableManager, rm)->setCastShadows(RenderableManager::Instance(instance), enabled);
}

void FilaRenderableManager_setReceiveShadows(FilaRenderableManager* rm, FilaRenderableManagerInstance instance, bool enabled) {
    FILA_CAST(RenderableManager, rm)->setReceiveShadows(RenderableManager::Instance(instance), enabled);
}

void FilaRenderableManager_setScreenSpaceContactShadows(FilaRenderableManager* rm, FilaRenderableManagerInstance instance, bool enabled) {
    FILA_CAST(RenderableManager, rm)->setScreenSpaceContactShadows(RenderableManager::Instance(instance), enabled);
}

bool FilaRenderableManager_isShadowCaster(const FilaRenderableManager* rm, FilaRenderableManagerInstance instance) {
    return FILA_CONST_CAST(RenderableManager, rm)->isShadowCaster(RenderableManager::Instance(instance));
}

bool FilaRenderableManager_isShadowReceiver(const FilaRenderableManager* rm, FilaRenderableManagerInstance instance) {
    return FILA_CONST_CAST(RenderableManager, rm)->isShadowReceiver(RenderableManager::Instance(instance));
}

bool FilaRenderableManager_isScreenSpaceContactShadowsEnabled(const FilaRenderableManager* rm, FilaRenderableManagerInstance instance) {
    return FILA_CONST_CAST(RenderableManager, rm)->isScreenSpaceContactShadowsEnabled(RenderableManager::Instance(instance));
}

uint32_t FilaRenderableManager_getPrimitiveCount(const FilaRenderableManager* rm, FilaRenderableManagerInstance instance) {
    return FILA_CONST_CAST(RenderableManager, rm)->getPrimitiveCount(RenderableManager::Instance(instance));
}

uint32_t FilaRenderableManager_getInstanceCount(const FilaRenderableManager* rm, FilaRenderableManagerInstance instance) {
    return FILA_CONST_CAST(RenderableManager, rm)->getInstanceCount(RenderableManager::Instance(instance));
}

void FilaRenderableManager_setMaterialInstanceAt(FilaRenderableManager* rm, FilaRenderableManagerInstance instance, size_t primitiveIndex, const FilaMaterialInstance* materialInstance) {
    FILA_CAST(RenderableManager, rm)->setMaterialInstanceAt(RenderableManager::Instance(instance), primitiveIndex, FILA_CONST_CAST(MaterialInstance, materialInstance));
}

void FilaRenderableManager_clearMaterialInstanceAt(FilaRenderableManager* rm, FilaRenderableManagerInstance instance, size_t primitiveIndex) {
    FILA_CAST(RenderableManager, rm)->clearMaterialInstanceAt(RenderableManager::Instance(instance), primitiveIndex);
}

FilaMaterialInstance* FilaRenderableManager_getMaterialInstanceAt(const FilaRenderableManager* rm, FilaRenderableManagerInstance instance, size_t primitiveIndex) {
    return reinterpret_cast<FilaMaterialInstance*>(
        const_cast<MaterialInstance*>(FILA_CONST_CAST(RenderableManager, rm)->getMaterialInstanceAt(RenderableManager::Instance(instance), primitiveIndex))
    );
}

void FilaRenderableManager_setGeometryAt(FilaRenderableManager* rm, FilaRenderableManagerInstance instance, size_t primitiveIndex, FilaRenderableManagerPrimitiveType type, FilaVertexBuffer* vb, FilaIndexBuffer* ib, size_t offset, size_t count) {
    FILA_CAST(RenderableManager, rm)->setGeometryAt(RenderableManager::Instance(instance), primitiveIndex, static_cast<RenderableManager::PrimitiveType>(type), FILA_CAST(VertexBuffer, vb), FILA_CAST(IndexBuffer, ib), offset, count);
}

void FilaRenderableManager_setGeometryAtNonIndexed(FilaRenderableManager* rm, FilaRenderableManagerInstance instance, size_t primitiveIndex, FilaRenderableManagerPrimitiveType type, FilaVertexBuffer* vb, size_t offset, size_t count) {
    FILA_CAST(RenderableManager, rm)->setGeometryAt(RenderableManager::Instance(instance), primitiveIndex, static_cast<RenderableManager::PrimitiveType>(type), FILA_CAST(VertexBuffer, vb), offset, count);
}

void FilaRenderableManager_setBlendOrderAt(FilaRenderableManager* rm, FilaRenderableManagerInstance instance, size_t primitiveIndex, uint16_t blendOrder) {
    FILA_CAST(RenderableManager, rm)->setBlendOrderAt(RenderableManager::Instance(instance), primitiveIndex, blendOrder);
}

uint16_t FilaRenderableManager_getBlendOrderAt(const FilaRenderableManager* rm, FilaRenderableManagerInstance instance, size_t primitiveIndex) {
    return FILA_CONST_CAST(RenderableManager, rm)->getBlendOrderAt(RenderableManager::Instance(instance), primitiveIndex);
}

void FilaRenderableManager_setGlobalBlendOrderEnabledAt(FilaRenderableManager* rm, FilaRenderableManagerInstance instance, size_t primitiveIndex, bool enabled) {
    FILA_CAST(RenderableManager, rm)->setGlobalBlendOrderEnabledAt(RenderableManager::Instance(instance), primitiveIndex, enabled);
}

bool FilaRenderableManager_isGlobalBlendOrderEnabledAt(const FilaRenderableManager* rm, FilaRenderableManagerInstance instance, size_t primitiveIndex) {
    return FILA_CONST_CAST(RenderableManager, rm)->isGlobalBlendOrderEnabledAt(RenderableManager::Instance(instance), primitiveIndex);
}

uint32_t FilaRenderableManager_getEnabledAttributesAt(const FilaRenderableManager* rm, FilaRenderableManagerInstance instance, size_t primitiveIndex) {
    return FILA_CONST_CAST(RenderableManager, rm)->getEnabledAttributesAt(RenderableManager::Instance(instance), primitiveIndex).getValue();
}

void FilaRenderableManager_setLightChannel(FilaRenderableManager* rm, FilaRenderableManagerInstance instance, unsigned int channel, bool enable) {
    FILA_CAST(RenderableManager, rm)->setLightChannel(RenderableManager::Instance(instance), channel, enable);
}

bool FilaRenderableManager_getLightChannel(const FilaRenderableManager* rm, FilaRenderableManagerInstance instance, unsigned int channel) {
    return FILA_CONST_CAST(RenderableManager, rm)->getLightChannel(RenderableManager::Instance(instance), channel);
}

} // extern "C"
