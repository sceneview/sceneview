#ifndef FILAMENT_C_TYPES_H
#define FILAMENT_C_TYPES_H

#include <stdint.h>
#include <stdbool.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

// Core filament opaque handles. Per-module handles live in that module's own
// types header (filamat/c/FilamatTypes.h, filament-utils/c/FilamentUtilsTypes.h,
// gltfio/c/GltfioTypes.h), each of which includes this one for the shared core.
typedef struct FilaEngine FilaEngine;
typedef struct FilaCamera FilaCamera;
typedef struct FilaRenderer FilaRenderer;
typedef struct FilaScene FilaScene;
typedef struct FilaView FilaView;
typedef struct FilaSwapChain FilaSwapChain;
typedef struct FilaIndirectLight FilaIndirectLight;
typedef struct FilaSkybox FilaSkybox;
typedef struct FilaColorGrading FilaColorGrading;
typedef struct FilaRenderTarget FilaRenderTarget;

typedef struct FilaVertexBuffer FilaVertexBuffer;
typedef struct FilaIndexBuffer FilaIndexBuffer;
typedef struct FilaBufferObject FilaBufferObject;
typedef struct FilaTexture FilaTexture;
typedef struct FilaStream FilaStream;
typedef struct FilaMaterial FilaMaterial;
typedef struct FilaMaterial_Builder FilaMaterial_Builder;
typedef struct FilaMaterialInstance FilaMaterialInstance;

typedef struct FilaRenderableManager FilaRenderableManager;
typedef struct FilaTransformManager FilaTransformManager;
typedef struct FilaLightManager FilaLightManager;
typedef struct FilaEntityManager FilaEntityManager;

typedef struct FilaFence FilaFence;
typedef struct FilaSkinningBuffer FilaSkinningBuffer;
typedef struct FilaMorphTargetBuffer FilaMorphTargetBuffer;
typedef struct FilaToneMapper FilaToneMapper;

// Packed texture sampler parameters (matches JNI long)
typedef uint64_t FilaTextureSampler;

typedef struct FilaCallbackHandler FilaCallbackHandler;

typedef enum FilaVertexAttribute {
    FILA_VERTEX_ATTRIBUTE_POSITION = 0,
    FILA_VERTEX_ATTRIBUTE_TANGENTS = 1,
    FILA_VERTEX_ATTRIBUTE_COLOR = 2,
    FILA_VERTEX_ATTRIBUTE_UV0 = 3,
    FILA_VERTEX_ATTRIBUTE_UV1 = 4,
    FILA_VERTEX_ATTRIBUTE_BONE_INDICES = 5,
    FILA_VERTEX_ATTRIBUTE_BONE_WEIGHTS = 6,
    FILA_VERTEX_ATTRIBUTE_CUSTOM0 = 8,
    FILA_VERTEX_ATTRIBUTE_CUSTOM1 = 9,
    FILA_VERTEX_ATTRIBUTE_CUSTOM2 = 10,
    FILA_VERTEX_ATTRIBUTE_CUSTOM3 = 11,
    FILA_VERTEX_ATTRIBUTE_CUSTOM4 = 12,
    FILA_VERTEX_ATTRIBUTE_CUSTOM5 = 13,
    FILA_VERTEX_ATTRIBUTE_CUSTOM6 = 14,
    FILA_VERTEX_ATTRIBUTE_CUSTOM7 = 15,
} FilaVertexAttribute;

// Matches filament::backend::PixelDataFormat
typedef enum FilaPixelDataFormat {
    FILA_PIXEL_DATA_FORMAT_R = 0,
    FILA_PIXEL_DATA_FORMAT_R_INTEGER = 1,
    FILA_PIXEL_DATA_FORMAT_RG = 2,
    FILA_PIXEL_DATA_FORMAT_RG_INTEGER = 3,
    FILA_PIXEL_DATA_FORMAT_RGB = 4,
    FILA_PIXEL_DATA_FORMAT_RGB_INTEGER = 5,
    FILA_PIXEL_DATA_FORMAT_RGBA = 6,
    FILA_PIXEL_DATA_FORMAT_RGBA_INTEGER = 7,
    FILA_PIXEL_DATA_FORMAT_UNUSED = 8,
    FILA_PIXEL_DATA_FORMAT_DEPTH_COMPONENT = 9,
    FILA_PIXEL_DATA_FORMAT_DEPTH_STENCIL = 10,
    FILA_PIXEL_DATA_FORMAT_STENCIL_INDEX = 11,
    FILA_PIXEL_DATA_FORMAT_ALPHA = 12,
} FilaPixelDataFormat;

// Matches filament::backend::PixelDataType
typedef enum FilaPixelDataType {
    FILA_PIXEL_DATA_TYPE_UBYTE = 0,
    FILA_PIXEL_DATA_TYPE_BYTE = 1,
    FILA_PIXEL_DATA_TYPE_USHORT = 2,
    FILA_PIXEL_DATA_TYPE_SHORT = 3,
    FILA_PIXEL_DATA_TYPE_UINT = 4,
    FILA_PIXEL_DATA_TYPE_INT = 5,
    FILA_PIXEL_DATA_TYPE_HALF = 6,
    FILA_PIXEL_DATA_TYPE_FLOAT = 7,
    FILA_PIXEL_DATA_TYPE_COMPRESSED = 8,
    FILA_PIXEL_DATA_TYPE_UINT_10F_11F_11F_REV = 9,
    FILA_PIXEL_DATA_TYPE_USHORT_565 = 10,
    FILA_PIXEL_DATA_TYPE_UINT_2_10_10_10_REV = 11,
} FilaPixelDataType;

typedef enum FilaEngineFeatureLevel {
    FILA_ENGINE_FEATURE_LEVEL_0 = 0,
    FILA_ENGINE_FEATURE_LEVEL_1 = 1,
    FILA_ENGINE_FEATURE_LEVEL_2 = 2,
    FILA_ENGINE_FEATURE_LEVEL_3 = 3,
} FilaEngineFeatureLevel;

typedef struct FilaQuat {
    float x, y, z, w;
} FilaQuat;

typedef struct FilaFloat3 {
    float x, y, z;
} FilaFloat3;

typedef struct FilaBox {
    float centerX, centerY, centerZ;
    float halfExtentX, halfExtentY, halfExtentZ;
} FilaBox;

typedef struct FilaBone {
    FilaQuat unitQuaternion;
    FilaFloat3 translation;
    float reserved;
} FilaBone;

// Entity type (matches utils::Entity::Type)
typedef uint32_t FilaEntity;

typedef enum FilaRgbType {
    FILA_RGB_TYPE_SRGB = 0,
    FILA_RGB_TYPE_LINEAR = 1,
} FilaRgbType;

typedef enum FilaRgbaType {
    FILA_RGBA_TYPE_SRGB = 0,
    FILA_RGBA_TYPE_LINEAR = 1,
    FILA_RGBA_TYPE_PREMULTIPLIED_SRGB = 2,
    FILA_RGBA_TYPE_PREMULTIPLIED_LINEAR = 3,
} FilaRgbaType;

typedef enum FilaColorConversion {
    FILA_COLOR_CONVERSION_ACCURATE = 0,
    FILA_COLOR_CONVERSION_FAST = 1,
} FilaColorConversion;

// Callback types
typedef void (*FilaBufferCallback)(void* buffer, size_t size, void* userData);
typedef void (*FilaStreamCallback)(void* image, void* userData);

#ifdef __cplusplus
}
#endif

#endif // FILAMENT_C_TYPES_H
