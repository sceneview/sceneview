#ifndef FILAMENT_C_SCENE_H
#define FILAMENT_C_SCENE_H

#include "FilaTypes.h"

#ifdef __cplusplus
extern "C" {
#endif

// Scene
void FilaScene_setSkybox(FilaScene* scene, FilaSkybox* skybox);
void FilaScene_setIndirectLight(FilaScene* scene, FilaIndirectLight* indirectLight);

void FilaScene_addEntity(FilaScene* scene, FilaEntity entity);
void FilaScene_addEntities(FilaScene* scene, const FilaEntity* entities, size_t count);

void FilaScene_remove(FilaScene* scene, FilaEntity entity);
void FilaScene_removeEntities(FilaScene* scene, const FilaEntity* entities, size_t count);

size_t FilaScene_getEntityCount(const FilaScene* scene);
size_t FilaScene_getRenderableCount(const FilaScene* scene);
size_t FilaScene_getLightCount(const FilaScene* scene);

bool FilaScene_hasEntity(const FilaScene* scene, FilaEntity entity);

void FilaScene_getEntities(const FilaScene* scene, FilaEntity* out, size_t length);

#ifdef __cplusplus
}
#endif

#endif // FILAMENT_C_SCENE_H
