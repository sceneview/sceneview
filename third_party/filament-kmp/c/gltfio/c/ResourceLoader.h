#ifndef GLTFIO_C_RESOURCE_LOADER_H
#define GLTFIO_C_RESOURCE_LOADER_H

#include "GltfioTypes.h"

#ifdef __cplusplus
extern "C" {
#endif

FilaResourceLoader* FilaResourceLoader_create(FilaEngine* engine, bool normalizeSkinningWeights);
void FilaResourceLoader_destroy(FilaResourceLoader* loader);

void FilaResourceLoader_addResourceData(FilaResourceLoader* loader, const char* uri, const void* buffer, size_t bufferByteCount);
bool FilaResourceLoader_loadResources(FilaResourceLoader* loader, FilaFilamentAsset* asset);

bool FilaResourceLoader_asyncBeginLoad(FilaResourceLoader* loader, FilaFilamentAsset* asset);
float FilaResourceLoader_asyncGetLoadProgress(FilaResourceLoader* loader);
void FilaResourceLoader_asyncUpdateLoad(FilaResourceLoader* loader);
void FilaResourceLoader_asyncCancelLoad(FilaResourceLoader* loader);

void FilaResourceLoader_evictResourceData(FilaResourceLoader* loader);
bool FilaResourceLoader_hasResourceData(FilaResourceLoader* loader, const char* uri);

FilaTextureProvider* FilaResourceLoader_createStbProvider(FilaEngine* engine);
FilaTextureProvider* FilaResourceLoader_createKtx2Provider(FilaEngine* engine);
FilaTextureProvider* FilaResourceLoader_createWebpProvider(FilaEngine* engine);
void FilaResourceLoader_destroyTextureProvider(FilaTextureProvider* provider);
void FilaResourceLoader_addTextureProvider(FilaResourceLoader* loader, const char* mimeType, FilaTextureProvider* provider);

#ifdef __cplusplus
}
#endif

#endif // GLTFIO_C_RESOURCE_LOADER_H
