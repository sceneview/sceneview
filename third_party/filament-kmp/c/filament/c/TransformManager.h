#ifndef FILAMENT_C_TRANSFORM_MANAGER_H
#define FILAMENT_C_TRANSFORM_MANAGER_H

#include "FilaTypes.h"

#ifdef __cplusplus
extern "C" {
#endif

// Instance
typedef uint32_t FilaTransformManagerInstance;

// TransformManager
bool FilaTransformManager_hasComponent(const FilaTransformManager* tm, FilaEntity entity);
FilaTransformManagerInstance FilaTransformManager_getInstance(const FilaTransformManager* tm, FilaEntity entity);

FilaTransformManagerInstance FilaTransformManager_create(FilaTransformManager* tm, FilaEntity entity);
FilaTransformManagerInstance FilaTransformManager_createWithParent(FilaTransformManager* tm, FilaEntity entity, FilaTransformManagerInstance parent, const float* localTransform);
FilaTransformManagerInstance FilaTransformManager_createWithParentFp64(FilaTransformManager* tm, FilaEntity entity, FilaTransformManagerInstance parent, const double* localTransform);

void FilaTransformManager_destroy(FilaTransformManager* tm, FilaEntity entity);

void FilaTransformManager_setParent(FilaTransformManager* tm, FilaTransformManagerInstance instance, FilaTransformManagerInstance newParent);
FilaEntity FilaTransformManager_getParent(const FilaTransformManager* tm, FilaTransformManagerInstance instance);

size_t FilaTransformManager_getChildCount(const FilaTransformManager* tm, FilaTransformManagerInstance instance);
void FilaTransformManager_getChildren(const FilaTransformManager* tm, FilaTransformManagerInstance instance, FilaEntity* outEntities, size_t count);

void FilaTransformManager_setTransform(FilaTransformManager* tm, FilaTransformManagerInstance instance, const float matrix[16]);
void FilaTransformManager_setTransformFp64(FilaTransformManager* tm, FilaTransformManagerInstance instance, const double matrix[16]);

void FilaTransformManager_getTransform(const FilaTransformManager* tm, FilaTransformManagerInstance instance, float out[16]);
void FilaTransformManager_getTransformFp64(const FilaTransformManager* tm, FilaTransformManagerInstance instance, double out[16]);

void FilaTransformManager_getWorldTransform(const FilaTransformManager* tm, FilaTransformManagerInstance instance, float out[16]);
void FilaTransformManager_getWorldTransformFp64(const FilaTransformManager* tm, FilaTransformManagerInstance instance, double out[16]);

void FilaTransformManager_openLocalTransformTransaction(FilaTransformManager* tm);
void FilaTransformManager_commitLocalTransformTransaction(FilaTransformManager* tm);

void FilaTransformManager_setAccurateTranslationsEnabled(FilaTransformManager* tm, bool enable);
bool FilaTransformManager_isAccurateTranslationsEnabled(const FilaTransformManager* tm);

#ifdef __cplusplus
}
#endif

#endif // FILAMENT_C_TRANSFORM_MANAGER_H
