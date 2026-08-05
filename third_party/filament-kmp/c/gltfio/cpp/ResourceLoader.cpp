#include <gltfio/ResourceLoader.h>
#include <gltfio/FilamentAsset.h>
#include <filament/Engine.h>
#include <gltfio/TextureProvider.h>

#include "../c/ResourceLoader.h"

using namespace filament;
using namespace filament::gltfio;

extern "C" {

FilaResourceLoader* FilaResourceLoader_create(FilaEngine* engine, bool normalizeSkinningWeights) {
    ResourceConfiguration config;
    config.engine = (Engine*) engine;
    config.gltfPath = nullptr;
    config.normalizeSkinningWeights = normalizeSkinningWeights;
    return (FilaResourceLoader*) new ResourceLoader(config);
}

void FilaResourceLoader_destroy(FilaResourceLoader* loader) {
    delete (ResourceLoader*) loader;
}

void FilaResourceLoader_addResourceData(FilaResourceLoader* loader, const char* uri, const void* buffer, size_t bufferByteCount) {
    ((ResourceLoader*) loader)->addResourceData(uri, {
        (const uint8_t*) buffer,
        (uint32_t) bufferByteCount
    });
}

bool FilaResourceLoader_loadResources(FilaResourceLoader* loader, FilaFilamentAsset* asset) {
    return ((ResourceLoader*) loader)->loadResources((FilamentAsset*) asset);
}

bool FilaResourceLoader_asyncBeginLoad(FilaResourceLoader* loader, FilaFilamentAsset* asset) {
    return ((ResourceLoader*) loader)->asyncBeginLoad((FilamentAsset*) asset);
}

float FilaResourceLoader_asyncGetLoadProgress(FilaResourceLoader* loader) {
    return ((ResourceLoader*) loader)->asyncGetLoadProgress();
}

void FilaResourceLoader_asyncUpdateLoad(FilaResourceLoader* loader) {
    ((ResourceLoader*) loader)->asyncUpdateLoad();
}

void FilaResourceLoader_asyncCancelLoad(FilaResourceLoader* loader) {
    ((ResourceLoader*) loader)->asyncCancelLoad();
}

void FilaResourceLoader_evictResourceData(FilaResourceLoader* loader) {
    ((ResourceLoader*) loader)->evictResourceData();
}

bool FilaResourceLoader_hasResourceData(FilaResourceLoader* loader, const char* uri) {
    return ((ResourceLoader*) loader)->hasResourceData(uri);
}

FilaTextureProvider* FilaResourceLoader_createStbProvider(FilaEngine* engine) {
    return (FilaTextureProvider*) createStbProvider((Engine*) engine);
}

FilaTextureProvider* FilaResourceLoader_createKtx2Provider(FilaEngine* engine) {
    return (FilaTextureProvider*) createKtx2Provider((Engine*) engine);
}

FilaTextureProvider* FilaResourceLoader_createWebpProvider(FilaEngine* engine) {
    // WebP support might be platform-dependent or require additional setup. 
    // Usually STB or a dedicated WebP provider is used. 
    // For now we'll return nullptr or use a placeholder if not directly available.
    return nullptr; 
}

void FilaResourceLoader_destroyTextureProvider(FilaTextureProvider* provider) {
    delete (TextureProvider*) provider;
}

void FilaResourceLoader_addTextureProvider(FilaResourceLoader* loader, const char* mimeType, FilaTextureProvider* provider) {
    ((ResourceLoader*) loader)->addTextureProvider(mimeType, (TextureProvider*) provider);
}

}
