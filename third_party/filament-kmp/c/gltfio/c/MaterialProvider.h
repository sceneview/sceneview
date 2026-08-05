#ifndef GLTFIO_C_MATERIAL_PROVIDER_H
#define GLTFIO_C_MATERIAL_PROVIDER_H

#include "GltfioTypes.h"

#ifdef __cplusplus
extern "C" {
#endif

void FilaMaterialProvider_destroy(FilaMaterialProvider* provider);

FilaMaterialProvider* FilaMaterialProvider_createUbershaderProvider(FilaEngine* engine, const void* archive, size_t archiveByteCount);

void FilaMaterialProvider_destroyMaterials(FilaMaterialProvider* provider);
size_t FilaMaterialProvider_getMaterialsCount(FilaMaterialProvider* provider);
void FilaMaterialProvider_getMaterials(FilaMaterialProvider* provider, FilaMaterial** materials);

bool FilaMaterialProvider_needsDummyData(FilaMaterialProvider* provider, int attrib);

FilaMaterialInstance* FilaMaterialProvider_createMaterialInstance(FilaMaterialProvider* provider, 
    const FilaMaterialKey* key, const uint8_t* uvmap, const char* label, const char* extras);

FilaMaterial* FilaMaterialProvider_getMaterial(FilaMaterialProvider* provider, 
    const FilaMaterialKey* key, const uint8_t* uvmap, const char* label);

void FilaMaterialKey_constrainMaterial(FilaMaterialKey* key, uint8_t* uvmap);

#ifdef __cplusplus
}
#endif

#endif // GLTFIO_C_MATERIAL_PROVIDER_H
