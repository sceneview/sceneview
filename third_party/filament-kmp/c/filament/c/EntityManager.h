#ifndef FILAMENT_C_ENTITY_MANAGER_H
#define FILAMENT_C_ENTITY_MANAGER_H

#include "FilaTypes.h"

#ifdef __cplusplus
extern "C" {
#endif

// EntityManager is a singleton
FilaEntityManager* FilaEntityManager_get(void);

FilaEntity FilaEntityManager_create(FilaEntityManager* em);
void FilaEntityManager_createArray(FilaEntityManager* em, size_t n, FilaEntity* outEntities);

void FilaEntityManager_destroy(FilaEntityManager* em, FilaEntity entity);
void FilaEntityManager_destroyArray(FilaEntityManager* em, size_t n, const FilaEntity* entities);

bool FilaEntityManager_isAlive(FilaEntityManager* em, FilaEntity entity);

#ifdef __cplusplus
}
#endif

#endif // FILAMENT_C_ENTITY_MANAGER_H
