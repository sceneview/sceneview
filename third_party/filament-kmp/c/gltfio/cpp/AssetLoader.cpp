#include <gltfio/AssetLoader.h>
#include <gltfio/FilamentAsset.h>
#include <gltfio/FilamentInstance.h>
#include <gltfio/MaterialProvider.h>
#include <filament/Engine.h>
#include <utils/EntityManager.h>

#include "../c/AssetLoader.h"

using namespace filament;
using namespace filament::gltfio;

extern "C" {

FilaAssetLoader* FilaAssetLoader_create(FilaEngine* engine, FilaMaterialProvider* materialProvider, FilaEntityManager* entityManager) {
    AssetConfiguration config;
    config.engine = (Engine*) engine;
    config.materials = (MaterialProvider*) materialProvider;
    config.entities = (utils::EntityManager*) entityManager;
    return (FilaAssetLoader*) AssetLoader::create(config);
}

void FilaAssetLoader_destroy(FilaAssetLoader* loader) {
    AssetLoader::destroy((AssetLoader**) &loader);
}

FilaFilamentAsset* FilaAssetLoader_createAsset(FilaAssetLoader* loader, const void* buffer, size_t bufferByteCount) {
    return (FilaFilamentAsset*) ((AssetLoader*) loader)->createAsset((const uint8_t*) buffer, (uint32_t) bufferByteCount);
}

FilaFilamentAsset* FilaAssetLoader_createInstancedAsset(FilaAssetLoader* loader, const void* buffer, size_t bufferByteCount, FilaFilamentInstance** instances, size_t instanceCount) {
    return (FilaFilamentAsset*) ((AssetLoader*) loader)->createInstancedAsset((const uint8_t*) buffer, (uint32_t) bufferByteCount, (FilamentInstance**) instances, instanceCount);
}

FilaFilamentInstance* FilaAssetLoader_createInstance(FilaAssetLoader* loader, FilaFilamentAsset* asset) {
    return (FilaFilamentInstance*) ((AssetLoader*) loader)->createInstance((FilamentAsset*) asset);
}

void FilaAssetLoader_enableDiagnostics(FilaAssetLoader* loader, bool enable) {
    ((AssetLoader*) loader)->enableDiagnostics(enable);
}

void FilaAssetLoader_destroyAsset(FilaAssetLoader* loader, FilaFilamentAsset* asset) {
    ((AssetLoader*) loader)->destroyAsset((FilamentAsset*) asset);
}

}
