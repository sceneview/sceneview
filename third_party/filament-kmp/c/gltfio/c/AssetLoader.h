#ifndef GLTFIO_C_ASSET_LOADER_H
#define GLTFIO_C_ASSET_LOADER_H

#include "GltfioTypes.h"

#ifdef __cplusplus
extern "C" {
#endif

FilaAssetLoader* FilaAssetLoader_create(FilaEngine* engine, FilaMaterialProvider* materialProvider, FilaEntityManager* entityManager);
void FilaAssetLoader_destroy(FilaAssetLoader* loader);

FilaFilamentAsset* FilaAssetLoader_createAsset(FilaAssetLoader* loader, const void* buffer, size_t bufferByteCount);
FilaFilamentAsset* FilaAssetLoader_createInstancedAsset(FilaAssetLoader* loader, const void* buffer, size_t bufferByteCount, FilaFilamentInstance** instances, size_t instanceCount);

FilaFilamentInstance* FilaAssetLoader_createInstance(FilaAssetLoader* loader, FilaFilamentAsset* asset);
void FilaAssetLoader_enableDiagnostics(FilaAssetLoader* loader, bool enable);

void FilaAssetLoader_destroyAsset(FilaAssetLoader* loader, FilaFilamentAsset* asset);

#ifdef __cplusplus
}
#endif

#endif // GLTFIO_C_ASSET_LOADER_H
