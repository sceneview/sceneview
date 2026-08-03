#include <gltfio/FilamentInstance.h>
#include <gltfio/FilamentAsset.h>
#include <gltfio/Animator.h>
#include <filament/Box.h>
#include <utils/Entity.h>

#include "../c/FilamentInstance.h"

using namespace filament;
using namespace filament::gltfio;

extern "C" {

FilaFilamentAsset* FilaFilamentInstance_getAsset(FilaFilamentInstance* instance) {
    return (FilaFilamentAsset*) ((FilamentInstance*) instance)->getAsset();
}

size_t FilaFilamentInstance_getEntityCount(FilaFilamentInstance* instance) {
    return ((FilamentInstance*) instance)->getEntityCount();
}

void FilaFilamentInstance_getEntities(FilaFilamentInstance* instance, FilaEntity* entities) {
    const utils::Entity* src = ((FilamentInstance*) instance)->getEntities();
    size_t count = ((FilamentInstance*) instance)->getEntityCount();
    for (size_t i = 0; i < count; ++i) {
        entities[i] = src[i].getId();
    }
}

FilaEntity FilaFilamentInstance_getRoot(FilaFilamentInstance* instance) {
    return ((FilamentInstance*) instance)->getRoot().getId();
}

FilaAnimator* FilaFilamentInstance_getAnimator(FilaFilamentInstance* instance) {
    return (FilaAnimator*) ((FilamentInstance*) instance)->getAnimator();
}

FilaBox FilaFilamentInstance_getBoundingBox(FilaFilamentInstance* instance) {
    auto aabb = ((FilamentInstance*) instance)->getBoundingBox();
    FilaBox box;
    auto center = aabb.center();
    auto extent = aabb.extent();
    box.centerX = center.x;
    box.centerY = center.y;
    box.centerZ = center.z;
    box.halfExtentX = extent.x;
    box.halfExtentY = extent.y;
    box.halfExtentZ = extent.z;
    return box;
}

const char* FilaFilamentInstance_getName(FilaFilamentInstance* instance, FilaEntity entity) {
    return ((FilamentInstance*) instance)->getAsset()->getName(utils::Entity::import(entity));
}

size_t FilaFilamentInstance_getSkinCount(FilaFilamentInstance* instance) {
    return ((FilamentInstance*) instance)->getSkinCount();
}

void FilaFilamentInstance_getSkinNames(FilaFilamentInstance* instance, const char** names) {
    size_t count = ((FilamentInstance*) instance)->getSkinCount();
    for (size_t i = 0; i < count; ++i) {
        names[i] = ((FilamentInstance*) instance)->getSkinNameAt(i);
    }
}

void FilaFilamentInstance_attachSkin(FilaFilamentInstance* instance, size_t skinIndex, FilaEntity entity) {
    ((FilamentInstance*) instance)->attachSkin(skinIndex, utils::Entity::import(entity));
}

void FilaFilamentInstance_detachSkin(FilaFilamentInstance* instance, size_t skinIndex, FilaEntity entity) {
    ((FilamentInstance*) instance)->detachSkin(skinIndex, utils::Entity::import(entity));
}

size_t FilaFilamentInstance_getJointCountAt(FilaFilamentInstance* instance, size_t skinIndex) {
    return ((FilamentInstance*) instance)->getJointCountAt(skinIndex);
}

void FilaFilamentInstance_getJointsAt(FilaFilamentInstance* instance, size_t skinIndex, FilaEntity* joints) {
    const utils::Entity* src = ((FilamentInstance*) instance)->getJointsAt(skinIndex);
    size_t count = ((FilamentInstance*) instance)->getJointCountAt(skinIndex);
    for (size_t i = 0; i < count; ++i) {
        joints[i] = src[i].getId();
    }
}

void FilaFilamentInstance_applyMaterialVariant(FilaFilamentInstance* instance, size_t variantIndex) {
    ((FilamentInstance*) instance)->applyMaterialVariant(variantIndex);
}

size_t FilaFilamentInstance_getMaterialInstanceCount(FilaFilamentInstance* instance) {
    return ((FilamentInstance*) instance)->getMaterialInstanceCount();
}

void FilaFilamentInstance_getMaterialInstances(FilaFilamentInstance* instance, FilaMaterialInstance** instances) {
    MaterialInstance* const* src = ((FilamentInstance*) instance)->getMaterialInstances();
    size_t count = ((FilamentInstance*) instance)->getMaterialInstanceCount();
    for (size_t i = 0; i < count; ++i) {
        instances[i] = (FilaMaterialInstance*) src[i];
    }
}

size_t FilaFilamentInstance_getMaterialVariantCount(FilaFilamentInstance* instance) {
    return ((FilamentInstance*) instance)->getMaterialVariantCount();
}

void FilaFilamentInstance_getMaterialVariantNames(FilaFilamentInstance* instance, const char** names) {
    size_t count = ((FilamentInstance*) instance)->getMaterialVariantCount();
    for (size_t i = 0; i < count; ++i) {
        names[i] = ((FilamentInstance*) instance)->getMaterialVariantName(i);
    }
}

}
