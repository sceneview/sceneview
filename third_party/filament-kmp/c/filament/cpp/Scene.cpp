#include <filament/Scene.h>
#include <filament/Skybox.h>
#include <filament/IndirectLight.h>
#include <utils/Entity.h>

#include "FilaCommon.h"
#include "../c/Scene.h"

using namespace filament;
using namespace utils;

extern "C" {

void FilaScene_setSkybox(FilaScene* scene, FilaSkybox* skybox) {
    FILA_CAST(Scene, scene)->setSkybox(FILA_CAST(Skybox, skybox));
}

void FilaScene_setIndirectLight(FilaScene* scene, FilaIndirectLight* indirectLight) {
    FILA_CAST(Scene, scene)->setIndirectLight(FILA_CAST(IndirectLight, indirectLight));
}

void FilaScene_addEntity(FilaScene* scene, FilaEntity entity) {
    FILA_CAST(Scene, scene)->addEntity(Entity::import(entity));
}

void FilaScene_addEntities(FilaScene* scene, const FilaEntity* entities, size_t count) {
    FILA_CAST(Scene, scene)->addEntities(reinterpret_cast<const Entity*>(entities), count);
}

void FilaScene_remove(FilaScene* scene, FilaEntity entity) {
    FILA_CAST(Scene, scene)->remove(Entity::import(entity));
}

void FilaScene_removeEntities(FilaScene* scene, const FilaEntity* entities, size_t count) {
    FILA_CAST(Scene, scene)->removeEntities(reinterpret_cast<const Entity*>(entities), count);
}

size_t FilaScene_getEntityCount(const FilaScene* scene) {
    return FILA_CONST_CAST(Scene, scene)->getEntityCount();
}

size_t FilaScene_getRenderableCount(const FilaScene* scene) {
    return FILA_CONST_CAST(Scene, scene)->getRenderableCount();
}

size_t FilaScene_getLightCount(const FilaScene* scene) {
    return FILA_CONST_CAST(Scene, scene)->getLightCount();
}

bool FilaScene_hasEntity(const FilaScene* scene, FilaEntity entity) {
    return FILA_CONST_CAST(Scene, scene)->hasEntity(Entity::import(entity));
}

void FilaScene_getEntities(const FilaScene* scene, FilaEntity* out, size_t length) {
    size_t count = 0;
    FILA_CONST_CAST(Scene, scene)->forEach([out, length, &count](Entity entity) {
        if (count < length) {
            out[count++] = entity.getId();
        }
    });
}

} // extern "C"
