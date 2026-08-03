#ifndef GLTFIO_C_TYPES_H
#define GLTFIO_C_TYPES_H

#include "../../filament/c/FilaTypes.h"

#ifdef __cplusplus
extern "C" {
#endif

// gltfio opaque handles
typedef struct FilaAssetLoader FilaAssetLoader;
typedef struct FilaFilamentAsset FilaFilamentAsset;
typedef struct FilaFilamentInstance FilaFilamentInstance;
typedef struct FilaAnimator FilaAnimator;
typedef struct FilaMaterialProvider FilaMaterialProvider;
typedef struct FilaResourceLoader FilaResourceLoader;
typedef struct FilaTextureProvider FilaTextureProvider;

typedef struct FilaMaterialKey {
    uint32_t words[5];
} FilaMaterialKey;

typedef struct FilaMaterialKeyFields {
    bool doubleSided;
    bool unlit;
    bool hasVertexColors;
    bool hasBaseColorTexture;
    bool hasNormalTexture;
    bool hasOcclusionTexture;
    bool hasEmissiveTexture;
    bool useSpecularGlossiness;
    uint8_t alphaMode;
    uint8_t enableDiagnostics;
    bool hasMetallicRoughnessTexture;
    uint8_t metallicRoughnessUV;
    uint8_t baseColorUV;
    bool hasClearCoatTexture;
    uint8_t clearCoatUV;
    bool hasClearCoatRoughnessTexture;
    uint8_t clearCoatRoughnessUV;
    bool hasClearCoatNormalTexture;
    uint8_t clearCoatNormalUV;
    bool hasClearCoat;
    bool hasTransmission;
    uint8_t hasTextureTransforms;
    uint8_t emissiveUV;
    uint8_t aoUV;
    uint8_t normalUV;
    bool hasTransmissionTexture;
    uint8_t transmissionUV;
    bool hasSheenColorTexture;
    uint8_t sheenColorUV;
    bool hasSheenRoughnessTexture;
    uint8_t sheenRoughnessUV;
    bool hasVolumeThicknessTexture;
    uint8_t volumeThicknessUV;
    bool hasSheen;
    bool hasIOR;
} FilaMaterialKeyFields;

void FilaMaterialKey_unpack(const FilaMaterialKey* key, FilaMaterialKeyFields* fields);
void FilaMaterialKey_pack(const FilaMaterialKeyFields* fields, FilaMaterialKey* key);

#ifdef __cplusplus
}
#endif

#endif // GLTFIO_C_TYPES_H
