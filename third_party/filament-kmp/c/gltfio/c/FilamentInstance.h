#ifndef GLTFIO_C_FILAMENT_INSTANCE_H
#define GLTFIO_C_FILAMENT_INSTANCE_H

#include "GltfioTypes.h"

#ifdef __cplusplus
extern "C" {
#endif

FilaFilamentAsset* FilaFilamentInstance_getAsset(FilaFilamentInstance* instance);

size_t FilaFilamentInstance_getEntityCount(FilaFilamentInstance* instance);
void FilaFilamentInstance_getEntities(FilaFilamentInstance* instance, FilaEntity* entities);

FilaEntity FilaFilamentInstance_getRoot(FilaFilamentInstance* instance);
FilaAnimator* FilaFilamentInstance_getAnimator(FilaFilamentInstance* instance);
FilaBox FilaFilamentInstance_getBoundingBox(FilaFilamentInstance* instance);

const char* FilaFilamentInstance_getName(FilaFilamentInstance* instance, FilaEntity entity);

size_t FilaFilamentInstance_getSkinCount(FilaFilamentInstance* instance);
void FilaFilamentInstance_getSkinNames(FilaFilamentInstance* instance, const char** names);
void FilaFilamentInstance_attachSkin(FilaFilamentInstance* instance, size_t skinIndex, FilaEntity entity);
void FilaFilamentInstance_detachSkin(FilaFilamentInstance* instance, size_t skinIndex, FilaEntity entity);
size_t FilaFilamentInstance_getJointCountAt(FilaFilamentInstance* instance, size_t skinIndex);
void FilaFilamentInstance_getJointsAt(FilaFilamentInstance* instance, size_t skinIndex, FilaEntity* joints);

void FilaFilamentInstance_applyMaterialVariant(FilaFilamentInstance* instance, size_t variantIndex);
size_t FilaFilamentInstance_getMaterialInstanceCount(FilaFilamentInstance* instance);
void FilaFilamentInstance_getMaterialInstances(FilaFilamentInstance* instance, FilaMaterialInstance** instances);
size_t FilaFilamentInstance_getMaterialVariantCount(FilaFilamentInstance* instance);
void FilaFilamentInstance_getMaterialVariantNames(FilaFilamentInstance* instance, const char** names);

#ifdef __cplusplus
}
#endif

#endif // GLTFIO_C_FILAMENT_INSTANCE_H
