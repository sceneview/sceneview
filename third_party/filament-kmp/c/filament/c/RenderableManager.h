#ifndef FILAMENT_C_RENDERABLE_MANAGER_H
#define FILAMENT_C_RENDERABLE_MANAGER_H

#include "FilaTypes.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef enum FilaRenderableManagerPrimitiveType {
    FILA_RENDERABLE_MANAGER_PRIMITIVE_TYPE_POINTS = 0,
    FILA_RENDERABLE_MANAGER_PRIMITIVE_TYPE_LINES = 1,
    FILA_RENDERABLE_MANAGER_PRIMITIVE_TYPE_LINE_STRIP = 3,
    FILA_RENDERABLE_MANAGER_PRIMITIVE_TYPE_TRIANGLES = 4,
    FILA_RENDERABLE_MANAGER_PRIMITIVE_TYPE_TRIANGLE_STRIP = 5,
} FilaRenderableManagerPrimitiveType;

typedef enum FilaRenderableManagerGeometryType {
    FILA_RENDERABLE_MANAGER_GEOMETRY_TYPE_DYNAMIC = 0,
    FILA_RENDERABLE_MANAGER_GEOMETRY_TYPE_STATIC_BOUNDS = 1,
    FILA_RENDERABLE_MANAGER_GEOMETRY_TYPE_STATIC = 2,
} FilaRenderableManagerGeometryType;

// Instance
typedef uint32_t FilaRenderableManagerInstance;

// Builder
typedef struct FilaRenderableManagerBuilder FilaRenderableManagerBuilder;

FilaRenderableManagerBuilder* FilaRenderableManagerBuilder_create(size_t count);
void FilaRenderableManagerBuilder_destroy(FilaRenderableManagerBuilder* builder);
bool FilaRenderableManagerBuilder_build(FilaRenderableManagerBuilder* builder, FilaEngine* engine, FilaEntity entity);

void FilaRenderableManagerBuilder_geometry(FilaRenderableManagerBuilder* builder, size_t index,
        FilaRenderableManagerPrimitiveType type, FilaVertexBuffer* vb, FilaIndexBuffer* ib);
void FilaRenderableManagerBuilder_geometryAt(FilaRenderableManagerBuilder* builder, size_t index,
        FilaRenderableManagerPrimitiveType type, FilaVertexBuffer* vb, FilaIndexBuffer* ib, size_t offset, size_t count);
void FilaRenderableManagerBuilder_geometryWithIndices(FilaRenderableManagerBuilder* builder, size_t index,
        FilaRenderableManagerPrimitiveType type, FilaVertexBuffer* vb, FilaIndexBuffer* ib, size_t offset, size_t minIndex, size_t maxIndex, size_t count);
void FilaRenderableManagerBuilder_geometryNonIndexed(FilaRenderableManagerBuilder* builder, size_t index,
        FilaRenderableManagerPrimitiveType type, FilaVertexBuffer* vb, size_t offset, size_t count);
void FilaRenderableManagerBuilder_geometryNonIndexedNone(FilaRenderableManagerBuilder* builder, size_t index,
        FilaRenderableManagerPrimitiveType type, FilaVertexBuffer* vb);

void FilaRenderableManagerBuilder_geometryType(FilaRenderableManagerBuilder* builder, FilaRenderableManagerGeometryType type);
void FilaRenderableManagerBuilder_material(FilaRenderableManagerBuilder* builder, size_t index, const FilaMaterialInstance* materialInstance);
void FilaRenderableManagerBuilder_blendOrder(FilaRenderableManagerBuilder* builder, size_t index, uint16_t blendOrder);
void FilaRenderableManagerBuilder_globalBlendOrderEnabled(FilaRenderableManagerBuilder* builder, size_t index, bool enabled);
void FilaRenderableManagerBuilder_boundingBox(FilaRenderableManagerBuilder* builder, float cx, float cy, float cz, float hx, float hy, float hz);
void FilaRenderableManagerBuilder_layerMask(FilaRenderableManagerBuilder* builder, uint8_t select, uint8_t value);
void FilaRenderableManagerBuilder_priority(FilaRenderableManagerBuilder* builder, uint8_t priority);
void FilaRenderableManagerBuilder_channel(FilaRenderableManagerBuilder* builder, uint8_t channel);
void FilaRenderableManagerBuilder_culling(FilaRenderableManagerBuilder* builder, bool enabled);
void FilaRenderableManagerBuilder_castShadows(FilaRenderableManagerBuilder* builder, bool enabled);
void FilaRenderableManagerBuilder_receiveShadows(FilaRenderableManagerBuilder* builder, bool enabled);
void FilaRenderableManagerBuilder_screenSpaceContactShadows(FilaRenderableManagerBuilder* builder, bool enabled);
void FilaRenderableManagerBuilder_skinningBuffer(FilaRenderableManagerBuilder* builder, FilaSkinningBuffer* sb, uint32_t boneCount, uint32_t offset);
void FilaRenderableManagerBuilder_skinning(FilaRenderableManagerBuilder* builder, uint32_t boneCount);
void FilaRenderableManagerBuilder_skinningBones(FilaRenderableManagerBuilder* builder, uint32_t boneCount, const void* bones);
void FilaRenderableManagerBuilder_enableSkinningBuffers(FilaRenderableManagerBuilder* builder, bool enabled);
void FilaRenderableManagerBuilder_morphing(FilaRenderableManagerBuilder* builder, uint32_t targetCount);
void FilaRenderableManagerBuilder_morphTargetBuffer(FilaRenderableManagerBuilder* builder, FilaMorphTargetBuffer* mtb);
void FilaRenderableManagerBuilder_morphTargetBufferOffsetAt(FilaRenderableManagerBuilder* builder, uint32_t level, uint32_t primitiveIndex, uint32_t offset);
void FilaRenderableManagerBuilder_fog(FilaRenderableManagerBuilder* builder, bool enabled);
void FilaRenderableManagerBuilder_lightChannel(FilaRenderableManagerBuilder* builder, unsigned int channel, bool enable);
void FilaRenderableManagerBuilder_instances(FilaRenderableManagerBuilder* builder, size_t instanceCount);

// RenderableManager
bool FilaRenderableManager_hasComponent(const FilaRenderableManager* rm, FilaEntity entity);
FilaRenderableManagerInstance FilaRenderableManager_getInstance(const FilaRenderableManager* rm, FilaEntity entity);
void FilaRenderableManager_destroy(FilaRenderableManager* rm, FilaEntity entity);

void FilaRenderableManager_setSkinningBuffer(FilaRenderableManager* rm, FilaRenderableManagerInstance instance, FilaSkinningBuffer* sb, uint32_t count, uint32_t offset);
void FilaRenderableManager_setBonesAsMatrices(FilaRenderableManager* rm, FilaRenderableManagerInstance instance, const float* matrices, uint32_t boneCount, uint32_t offset);
void FilaRenderableManager_setBonesAsQuaternions(FilaRenderableManager* rm, FilaRenderableManagerInstance instance, const void* bones, uint32_t boneCount, uint32_t offset);
void FilaRenderableManager_setMorphWeights(FilaRenderableManager* rm, FilaRenderableManagerInstance instance, const float* weights, uint32_t count, uint32_t offset);
void FilaRenderableManager_setMorphTargetBufferOffsetAt(FilaRenderableManager* rm, FilaRenderableManagerInstance instance, uint8_t level, size_t primitiveIndex, size_t offset);
uint32_t FilaRenderableManager_getMorphTargetCount(const FilaRenderableManager* rm, FilaRenderableManagerInstance instance);

void FilaRenderableManager_setAxisAlignedBoundingBox(FilaRenderableManager* rm, FilaRenderableManagerInstance instance, float cx, float cy, float cz, float hx, float hy, float hz);
void FilaRenderableManager_getAxisAlignedBoundingBox(const FilaRenderableManager* rm, FilaRenderableManagerInstance instance, float center[3], float halfExtent[3]);

void FilaRenderableManager_setLayerMask(FilaRenderableManager* rm, FilaRenderableManagerInstance instance, uint8_t select, uint8_t value);
void FilaRenderableManager_setPriority(FilaRenderableManager* rm, FilaRenderableManagerInstance instance, uint8_t priority);
uint8_t FilaRenderableManager_getPriority(const FilaRenderableManager* rm, FilaRenderableManagerInstance instance);
void FilaRenderableManager_setChannel(FilaRenderableManager* rm, FilaRenderableManagerInstance instance, uint8_t channel);
uint8_t FilaRenderableManager_getChannel(const FilaRenderableManager* rm, FilaRenderableManagerInstance instance);
void FilaRenderableManager_setCulling(FilaRenderableManager* rm, FilaRenderableManagerInstance instance, bool enabled);
bool FilaRenderableManager_isCullingEnabled(const FilaRenderableManager* rm, FilaRenderableManagerInstance instance);
void FilaRenderableManager_setFogEnabled(FilaRenderableManager* rm, FilaRenderableManagerInstance instance, bool enabled);
bool FilaRenderableManager_getFogEnabled(const FilaRenderableManager* rm, FilaRenderableManagerInstance instance);
void FilaRenderableManager_setCastShadows(FilaRenderableManager* rm, FilaRenderableManagerInstance instance, bool enabled);
void FilaRenderableManager_setReceiveShadows(FilaRenderableManager* rm, FilaRenderableManagerInstance instance, bool enabled);
void FilaRenderableManager_setScreenSpaceContactShadows(FilaRenderableManager* rm, FilaRenderableManagerInstance instance, bool enabled);
bool FilaRenderableManager_isShadowCaster(const FilaRenderableManager* rm, FilaRenderableManagerInstance instance);
bool FilaRenderableManager_isShadowReceiver(const FilaRenderableManager* rm, FilaRenderableManagerInstance instance);
bool FilaRenderableManager_isScreenSpaceContactShadowsEnabled(const FilaRenderableManager* rm, FilaRenderableManagerInstance instance);

uint32_t FilaRenderableManager_getPrimitiveCount(const FilaRenderableManager* rm, FilaRenderableManagerInstance instance);
uint32_t FilaRenderableManager_getInstanceCount(const FilaRenderableManager* rm, FilaRenderableManagerInstance instance);

void FilaRenderableManager_setMaterialInstanceAt(FilaRenderableManager* rm, FilaRenderableManagerInstance instance, size_t primitiveIndex, const FilaMaterialInstance* materialInstance);
void FilaRenderableManager_clearMaterialInstanceAt(FilaRenderableManager* rm, FilaRenderableManagerInstance instance, size_t primitiveIndex);
FilaMaterialInstance* FilaRenderableManager_getMaterialInstanceAt(const FilaRenderableManager* rm, FilaRenderableManagerInstance instance, size_t primitiveIndex);

void FilaRenderableManager_setGeometryAt(FilaRenderableManager* rm, FilaRenderableManagerInstance instance, size_t primitiveIndex, FilaRenderableManagerPrimitiveType type, FilaVertexBuffer* vb, FilaIndexBuffer* ib, size_t offset, size_t count);
void FilaRenderableManager_setGeometryAtNonIndexed(FilaRenderableManager* rm, FilaRenderableManagerInstance instance, size_t primitiveIndex, FilaRenderableManagerPrimitiveType type, FilaVertexBuffer* vb, size_t offset, size_t count);

void FilaRenderableManager_setBlendOrderAt(FilaRenderableManager* rm, FilaRenderableManagerInstance instance, size_t primitiveIndex, uint16_t blendOrder);
uint16_t FilaRenderableManager_getBlendOrderAt(const FilaRenderableManager* rm, FilaRenderableManagerInstance instance, size_t primitiveIndex);
void FilaRenderableManager_setGlobalBlendOrderEnabledAt(FilaRenderableManager* rm, FilaRenderableManagerInstance instance, size_t primitiveIndex, bool enabled);
bool FilaRenderableManager_isGlobalBlendOrderEnabledAt(const FilaRenderableManager* rm, FilaRenderableManagerInstance instance, size_t primitiveIndex);

uint32_t FilaRenderableManager_getEnabledAttributesAt(const FilaRenderableManager* rm, FilaRenderableManagerInstance instance, size_t primitiveIndex);

void FilaRenderableManager_setLightChannel(FilaRenderableManager* rm, FilaRenderableManagerInstance instance, unsigned int channel, bool enable);
bool FilaRenderableManager_getLightChannel(const FilaRenderableManager* rm, FilaRenderableManagerInstance instance, unsigned int channel);

#ifdef __cplusplus
}
#endif

#endif // FILAMENT_C_RENDERABLE_MANAGER_H
