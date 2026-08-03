#include <filament/TransformManager.h>
#include <math/mat4.h>
#include <utils/Entity.h>
#include <algorithm>

#include "FilaCommon.h"
#include "../c/TransformManager.h"

using namespace filament;
using namespace utils;

extern "C" {

bool FilaTransformManager_hasComponent(const FilaTransformManager* tm, FilaEntity entity) {
    return FILA_CONST_CAST(TransformManager, tm)->hasComponent(Entity::import(entity));
}

FilaTransformManagerInstance FilaTransformManager_getInstance(const FilaTransformManager* tm, FilaEntity entity) {
    return FILA_CONST_CAST(TransformManager, tm)->getInstance(Entity::import(entity)).asValue();
}

FilaTransformManagerInstance FilaTransformManager_create(FilaTransformManager* tm, FilaEntity entity) {
    FILA_CAST(TransformManager, tm)->create(Entity::import(entity));
    return FILA_CAST(TransformManager, tm)->getInstance(Entity::import(entity)).asValue();
}

FilaTransformManagerInstance FilaTransformManager_createWithParent(FilaTransformManager* tm, FilaEntity entity, FilaTransformManagerInstance parent, const float* localTransform) {
    if (localTransform) {
        FILA_CAST(TransformManager, tm)->create(Entity::import(entity), TransformManager::Instance(parent),
                *reinterpret_cast<const filament::math::mat4f *>(localTransform));
    } else {
        FILA_CAST(TransformManager, tm)->create(Entity::import(entity), TransformManager::Instance(parent));
    }
    return FILA_CAST(TransformManager, tm)->getInstance(Entity::import(entity)).asValue();
}

FilaTransformManagerInstance FilaTransformManager_createWithParentFp64(FilaTransformManager* tm, FilaEntity entity, FilaTransformManagerInstance parent, const double* localTransform) {
    if (localTransform) {
        FILA_CAST(TransformManager, tm)->create(Entity::import(entity), TransformManager::Instance(parent),
                *reinterpret_cast<const filament::math::mat4 *>(localTransform));
    } else {
        FILA_CAST(TransformManager, tm)->create(Entity::import(entity), TransformManager::Instance(parent));
    }
    return FILA_CAST(TransformManager, tm)->getInstance(Entity::import(entity)).asValue();
}

void FilaTransformManager_destroy(FilaTransformManager* tm, FilaEntity entity) {
    FILA_CAST(TransformManager, tm)->destroy(Entity::import(entity));
}

void FilaTransformManager_setParent(FilaTransformManager* tm, FilaTransformManagerInstance instance, FilaTransformManagerInstance newParent) {
    FILA_CAST(TransformManager, tm)->setParent(TransformManager::Instance(instance), TransformManager::Instance(newParent));
}

FilaEntity FilaTransformManager_getParent(const FilaTransformManager* tm, FilaTransformManagerInstance instance) {
    return FILA_CONST_CAST(TransformManager, tm)->getParent(TransformManager::Instance(instance)).getId();
}

size_t FilaTransformManager_getChildCount(const FilaTransformManager* tm, FilaTransformManagerInstance instance) {
    return FILA_CONST_CAST(TransformManager, tm)->getChildCount(TransformManager::Instance(instance));
}

void FilaTransformManager_getChildren(const FilaTransformManager* tm, FilaTransformManagerInstance instance, FilaEntity* outEntities, size_t count) {
    FILA_CONST_CAST(TransformManager, tm)->getChildren(TransformManager::Instance(instance),
            reinterpret_cast<Entity *>(outEntities), count);
}

void FilaTransformManager_setTransform(FilaTransformManager* tm, FilaTransformManagerInstance instance, const float matrix[16]) {
    FILA_CAST(TransformManager, tm)->setTransform(TransformManager::Instance(instance),
            *reinterpret_cast<const filament::math::mat4f *>(matrix));
}

void FilaTransformManager_setTransformFp64(FilaTransformManager* tm, FilaTransformManagerInstance instance, const double matrix[16]) {
    FILA_CAST(TransformManager, tm)->setTransform(TransformManager::Instance(instance),
            *reinterpret_cast<const filament::math::mat4 *>(matrix));
}

void FilaTransformManager_getTransform(const FilaTransformManager* tm, FilaTransformManagerInstance instance, float out[16]) {
    *reinterpret_cast<filament::math::mat4f *>(out) = FILA_CONST_CAST(TransformManager, tm)->getTransform(TransformManager::Instance(instance));
}

void FilaTransformManager_getTransformFp64(const FilaTransformManager* tm, FilaTransformManagerInstance instance, double out[16]) {
    *reinterpret_cast<filament::math::mat4 *>(out) = FILA_CONST_CAST(TransformManager, tm)->getTransformAccurate(TransformManager::Instance(instance));
}

void FilaTransformManager_getWorldTransform(const FilaTransformManager* tm, FilaTransformManagerInstance instance, float out[16]) {
    *reinterpret_cast<filament::math::mat4f *>(out) = FILA_CONST_CAST(TransformManager, tm)->getWorldTransform(TransformManager::Instance(instance));
}

void FilaTransformManager_getWorldTransformFp64(const FilaTransformManager* tm, FilaTransformManagerInstance instance, double out[16]) {
    *reinterpret_cast<filament::math::mat4 *>(out) = FILA_CONST_CAST(TransformManager, tm)->getWorldTransformAccurate(TransformManager::Instance(instance));
}

void FilaTransformManager_openLocalTransformTransaction(FilaTransformManager* tm) {
    FILA_CAST(TransformManager, tm)->openLocalTransformTransaction();
}

void FilaTransformManager_commitLocalTransformTransaction(FilaTransformManager* tm) {
    FILA_CAST(TransformManager, tm)->commitLocalTransformTransaction();
}

void FilaTransformManager_setAccurateTranslationsEnabled(FilaTransformManager* tm, bool enable) {
    FILA_CAST(TransformManager, tm)->setAccurateTranslationsEnabled(enable);
}

bool FilaTransformManager_isAccurateTranslationsEnabled(const FilaTransformManager* tm) {
    return FILA_CONST_CAST(TransformManager, tm)->isAccurateTranslationsEnabled();
}

} // extern "C"
