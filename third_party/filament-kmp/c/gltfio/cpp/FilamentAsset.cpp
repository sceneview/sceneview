#include <gltfio/FilamentAsset.h>
#include <gltfio/FilamentInstance.h>
#include <filament/Box.h>
#include <utils/Entity.h>
#include <string.h>

#include "../c/FilamentAsset.h"

using namespace filament;
using namespace filament::gltfio;

extern "C" {

FilaEntity FilaFilamentAsset_getRoot(FilaFilamentAsset* asset) {
    return ((FilamentAsset*) asset)->getRoot().getId();
}

size_t FilaFilamentAsset_getEntityCount(FilaFilamentAsset* asset) {
    return ((FilamentAsset*) asset)->getEntityCount();
}

void FilaFilamentAsset_getEntities(FilaFilamentAsset* asset, FilaEntity* entities) {
    const utils::Entity* src = ((FilamentAsset*) asset)->getEntities();
    size_t count = ((FilamentAsset*) asset)->getEntityCount();
    for (size_t i = 0; i < count; ++i) {
        entities[i] = src[i].getId();
    }
}

size_t FilaFilamentAsset_getLightEntityCount(FilaFilamentAsset* asset) {
    return ((FilamentAsset*) asset)->getLightEntityCount();
}

void FilaFilamentAsset_getLightEntities(FilaFilamentAsset* asset, FilaEntity* entities) {
    const utils::Entity* src = ((FilamentAsset*) asset)->getLightEntities();
    size_t count = ((FilamentAsset*) asset)->getLightEntityCount();
    for (size_t i = 0; i < count; ++i) {
        entities[i] = src[i].getId();
    }
}

size_t FilaFilamentAsset_getRenderableEntityCount(FilaFilamentAsset* asset) {
    return ((FilamentAsset*) asset)->getRenderableEntityCount();
}

void FilaFilamentAsset_getRenderableEntities(FilaFilamentAsset* asset, FilaEntity* entities) {
    const utils::Entity* src = ((FilamentAsset*) asset)->getRenderableEntities();
    size_t count = ((FilamentAsset*) asset)->getRenderableEntityCount();
    for (size_t i = 0; i < count; ++i) {
        entities[i] = src[i].getId();
    }
}

size_t FilaFilamentAsset_getCameraEntityCount(FilaFilamentAsset* asset) {
    return ((FilamentAsset*) asset)->getCameraEntityCount();
}

void FilaFilamentAsset_getCameraEntities(FilaFilamentAsset* asset, FilaEntity* entities) {
    const utils::Entity* src = ((FilamentAsset*) asset)->getCameraEntities();
    size_t count = ((FilamentAsset*) asset)->getCameraEntityCount();
    for (size_t i = 0; i < count; ++i) {
        entities[i] = src[i].getId();
    }
}

FilaEntity FilaFilamentAsset_popRenderable(FilaFilamentAsset* asset) {
    return ((FilamentAsset*) asset)->popRenderable().getId();
}

size_t FilaFilamentAsset_popRenderables(FilaFilamentAsset* asset, FilaEntity* entities, size_t count) {
    return ((FilamentAsset*) asset)->popRenderables((utils::Entity*) entities, count);
}

FilaBox FilaFilamentAsset_getBoundingBox(FilaFilamentAsset* asset) {
    auto aabb = ((FilamentAsset*) asset)->getBoundingBox();
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

const char* FilaFilamentAsset_getName(FilaFilamentAsset* asset, FilaEntity entity) {
    return ((FilamentAsset*) asset)->getName(utils::Entity::import(entity));
}

FilaEntity FilaFilamentAsset_getFirstEntityByName(FilaFilamentAsset* asset, const char* name) {
    return ((FilamentAsset*) asset)->getFirstEntityByName(name).getId();
}

size_t FilaFilamentAsset_getEntitiesByName(FilaFilamentAsset* asset, const char* name, FilaEntity* entities, size_t maxCount) {
    return ((FilamentAsset*) asset)->getEntitiesByName(name, (utils::Entity*) entities, maxCount);
}

size_t FilaFilamentAsset_getEntitiesByPrefix(FilaFilamentAsset* asset, const char* prefix, FilaEntity* entities, size_t maxCount) {
    return ((FilamentAsset*) asset)->getEntitiesByPrefix(prefix, (utils::Entity*) entities, maxCount);
}

const char* FilaFilamentAsset_getExtras(FilaFilamentAsset* asset, FilaEntity entity) {
    return ((FilamentAsset*) asset)->getExtras(utils::Entity::import(entity));
}

size_t FilaFilamentAsset_getMorphTargetCountAt(FilaFilamentAsset* asset, FilaEntity entity) {
    return ((FilamentAsset*) asset)->getMorphTargetCountAt(utils::Entity::import(entity));
}

const char* FilaFilamentAsset_getMorphTargetNameAt(FilaFilamentAsset* asset, FilaEntity entity, size_t targetIndex) {
    return ((FilamentAsset*) asset)->getMorphTargetNameAt(utils::Entity::import(entity), targetIndex);
}

size_t FilaFilamentAsset_getResourceUriCount(FilaFilamentAsset* asset) {
    return ((FilamentAsset*) asset)->getResourceUriCount();
}

void FilaFilamentAsset_getResourceUris(FilaFilamentAsset* asset, const char** uris) {
    const char* const* src = ((FilamentAsset*) asset)->getResourceUris();
    size_t count = ((FilamentAsset*) asset)->getResourceUriCount();
    for (size_t i = 0; i < count; ++i) {
        uris[i] = src[i];
    }
}

size_t FilaFilamentAsset_getAssetInstanceCount(FilaFilamentAsset* asset) {
    return ((FilamentAsset*) asset)->getAssetInstanceCount();
}

void FilaFilamentAsset_getAssetInstances(FilaFilamentAsset* asset, FilaFilamentInstance** instances) {
    FilamentInstance** src = ((FilamentAsset*) asset)->getAssetInstances();
    size_t count = ((FilamentAsset*) asset)->getAssetInstanceCount();
    for (size_t i = 0; i < count; ++i) {
        instances[i] = (FilaFilamentInstance*) src[i];
    }
}

void FilaFilamentAsset_releaseSourceData(FilaFilamentAsset* asset) {
    ((FilamentAsset*) asset)->releaseSourceData();
}

FilaEngine* FilaFilamentAsset_getEngine(const FilaFilamentAsset* asset) {
    return (FilaEngine*) ((const FilamentAsset*) asset)->getEngine();
}

FilaFilamentInstance* FilaFilamentAsset_getInstance(FilaFilamentAsset* asset) {
    return (FilaFilamentInstance*) ((FilamentAsset*) asset)->getInstance();
}

}
