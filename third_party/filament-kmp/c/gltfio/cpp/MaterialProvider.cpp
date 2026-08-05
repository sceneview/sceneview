#include <gltfio/MaterialProvider.h>
#include <gltfio/materials/uberarchive.h>
#include "../c/MaterialProvider.h"

using namespace filament;
using namespace filament::gltfio;

extern "C" {

void FilaMaterialProvider_destroy(FilaMaterialProvider* provider) {
    delete (MaterialProvider*) provider;
}

FilaMaterialProvider* FilaMaterialProvider_createUbershaderProvider(FilaEngine* engine, const void* archive, size_t archiveByteCount) {
    if (archive == nullptr) {
        archive = UBERARCHIVE_DEFAULT_DATA;
        archiveByteCount = UBERARCHIVE_DEFAULT_SIZE;
    }
    return (FilaMaterialProvider*) createUbershaderProvider((Engine*) engine, archive, archiveByteCount);
}


void FilaMaterialProvider_destroyMaterials(FilaMaterialProvider* provider) {
    ((MaterialProvider*) provider)->destroyMaterials();
}

size_t FilaMaterialProvider_getMaterialsCount(FilaMaterialProvider* provider) {
    return ((MaterialProvider*) provider)->getMaterialsCount();
}

void FilaMaterialProvider_getMaterials(FilaMaterialProvider* provider, FilaMaterial** materials) {
    const Material* const* src = ((MaterialProvider*) provider)->getMaterials();
    size_t count = ((MaterialProvider*) provider)->getMaterialsCount();
    for (size_t i = 0; i < count; ++i) {
        materials[i] = (FilaMaterial*) src[i];
    }
}

bool FilaMaterialProvider_needsDummyData(FilaMaterialProvider* provider, int attrib) {
    return ((MaterialProvider*) provider)->needsDummyData((VertexAttribute) attrib);
}

FilaMaterialInstance* FilaMaterialProvider_createMaterialInstance(FilaMaterialProvider* provider, 
    const FilaMaterialKey* key, const uint8_t* uvmap, const char* label, const char* extras) {
    return (FilaMaterialInstance*) ((MaterialProvider*) provider)->createMaterialInstance(
        (MaterialKey*) key, 
        (UvMap*) uvmap, 
        label, 
        extras
    );
}

FilaMaterial* FilaMaterialProvider_getMaterial(FilaMaterialProvider* provider, 
    const FilaMaterialKey* key, const uint8_t* uvmap, const char* label) {
    return (FilaMaterial*) ((MaterialProvider*) provider)->getMaterial(
        (MaterialKey*) key, 
        (UvMap*) uvmap, 
        label
    );
}

void FilaMaterialKey_constrainMaterial(FilaMaterialKey* key, uint8_t* uvmap) {
    constrainMaterial((MaterialKey*) key, (UvMap*) uvmap);
}

void FilaMaterialKey_unpack(const FilaMaterialKey* key, FilaMaterialKeyFields* fields) {
    const MaterialKey* mk = (const MaterialKey*) key;
    fields->doubleSided = mk->doubleSided;
    fields->unlit = mk->unlit;
    fields->hasVertexColors = mk->hasVertexColors;
    fields->hasBaseColorTexture = mk->hasBaseColorTexture;
    fields->hasNormalTexture = mk->hasNormalTexture;
    fields->hasOcclusionTexture = mk->hasOcclusionTexture;
    fields->hasEmissiveTexture = mk->hasEmissiveTexture;
    fields->useSpecularGlossiness = mk->useSpecularGlossiness;
    fields->alphaMode = (uint8_t) mk->alphaMode;
    fields->enableDiagnostics = (uint8_t) mk->enableDiagnostics;
    fields->hasMetallicRoughnessTexture = mk->hasMetallicRoughnessTexture;
    fields->metallicRoughnessUV = mk->metallicRoughnessUV;
    fields->baseColorUV = mk->baseColorUV;
    fields->hasClearCoatTexture = mk->hasClearCoatTexture;
    fields->clearCoatUV = mk->clearCoatUV;
    fields->hasClearCoatRoughnessTexture = mk->hasClearCoatRoughnessTexture;
    fields->clearCoatRoughnessUV = mk->clearCoatRoughnessUV;
    fields->hasClearCoatNormalTexture = mk->hasClearCoatNormalTexture;
    fields->clearCoatNormalUV = mk->clearCoatNormalUV;
    fields->hasClearCoat = mk->hasClearCoat;
    fields->hasTransmission = mk->hasTransmission;
    fields->hasTextureTransforms = mk->hasTextureTransforms;
    fields->emissiveUV = mk->emissiveUV;
    fields->aoUV = mk->aoUV;
    fields->normalUV = mk->normalUV;
    fields->hasTransmissionTexture = mk->hasTransmissionTexture;
    fields->transmissionUV = mk->transmissionUV;
    fields->hasSheenColorTexture = mk->hasSheenColorTexture;
    fields->sheenColorUV = mk->sheenColorUV;
    fields->hasSheenRoughnessTexture = mk->hasSheenRoughnessTexture;
    fields->sheenRoughnessUV = mk->sheenRoughnessUV;
    fields->hasVolumeThicknessTexture = mk->hasVolumeThicknessTexture;
    fields->volumeThicknessUV = mk->volumeThicknessUV;
    fields->hasSheen = mk->hasSheen;
    fields->hasIOR = mk->hasIOR;
}

void FilaMaterialKey_pack(const FilaMaterialKeyFields* fields, FilaMaterialKey* key) {
    MaterialKey* mk = (MaterialKey*) key;
    mk->doubleSided = fields->doubleSided;
    mk->unlit = fields->unlit;
    mk->hasVertexColors = fields->hasVertexColors;
    mk->hasBaseColorTexture = fields->hasBaseColorTexture;
    mk->hasNormalTexture = fields->hasNormalTexture;
    mk->hasOcclusionTexture = fields->hasOcclusionTexture;
    mk->hasEmissiveTexture = fields->hasEmissiveTexture;
    mk->useSpecularGlossiness = fields->useSpecularGlossiness;
    mk->alphaMode = (filament::gltfio::AlphaMode) fields->alphaMode;
    mk->enableDiagnostics = fields->enableDiagnostics;
    mk->hasMetallicRoughnessTexture = fields->hasMetallicRoughnessTexture;
    mk->metallicRoughnessUV = fields->metallicRoughnessUV;
    mk->baseColorUV = fields->baseColorUV;
    mk->hasClearCoatTexture = fields->hasClearCoatTexture;
    mk->clearCoatUV = fields->clearCoatUV;
    mk->hasClearCoatRoughnessTexture = fields->hasClearCoatRoughnessTexture;
    mk->clearCoatRoughnessUV = fields->clearCoatRoughnessUV;
    mk->hasClearCoatNormalTexture = fields->hasClearCoatNormalTexture;
    mk->clearCoatNormalUV = fields->clearCoatNormalUV;
    mk->hasClearCoat = fields->hasClearCoat;
    mk->hasTransmission = fields->hasTransmission;
    mk->hasTextureTransforms = fields->hasTextureTransforms;
    mk->emissiveUV = fields->emissiveUV;
    mk->aoUV = fields->aoUV;
    mk->normalUV = fields->normalUV;
    mk->hasTransmissionTexture = fields->hasTransmissionTexture;
    mk->transmissionUV = fields->transmissionUV;
    mk->hasSheenColorTexture = fields->hasSheenColorTexture;
    mk->sheenColorUV = fields->sheenColorUV;
    mk->hasSheenRoughnessTexture = fields->hasSheenRoughnessTexture;
    mk->sheenRoughnessUV = fields->sheenRoughnessUV;
    mk->hasVolumeThicknessTexture = fields->hasVolumeThicknessTexture;
    mk->volumeThicknessUV = fields->volumeThicknessUV;
    mk->hasSheen = fields->hasSheen;
    mk->hasIOR = fields->hasIOR;
}

}

// Layout guard: FilaMaterialKey (opaque uint32_t words[5]) is reinterpret_cast onto
// filament::gltfio::MaterialKey and the pack/unpack above writes its bitfields.
// If MaterialKey grows past 5 words this silently truncates — fail the build instead.
static_assert(sizeof(FilaMaterialKey) == sizeof(filament::gltfio::MaterialKey), "MaterialKey size mismatch");
