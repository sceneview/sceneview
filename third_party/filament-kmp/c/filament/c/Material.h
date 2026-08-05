#ifndef FILAMENT_C_MATERIAL_H
#define FILAMENT_C_MATERIAL_H

#include "FilaTypes.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef enum FilaMaterialShading {
    FILA_MATERIAL_SHADING_UNLIT = 0,
    FILA_MATERIAL_SHADING_LIT = 1,
    FILA_MATERIAL_SHADING_SUBSURFACE = 2,
    FILA_MATERIAL_SHADING_CLOTH = 3,
    FILA_MATERIAL_SHADING_SPECULAR_GLOSSINESS = 4,
} FilaMaterialShading;

typedef enum FilaMaterialShadowSamplingQuality {
    FILA_MATERIAL_SHADOW_SAMPLING_QUALITY_HARD = 0,
    FILA_MATERIAL_SHADOW_SAMPLING_QUALITY_LOW = 1,
} FilaMaterialShadowSamplingQuality;

typedef enum FilaMaterialUboBatchingMode {
    FILA_MATERIAL_UBO_BATCHING_MODE_DEFAULT = 0,
    FILA_MATERIAL_UBO_BATCHING_MODE_DISABLED = 1,
} FilaMaterialUboBatchingMode;

typedef enum FilaMaterialInterpolation {
    FILA_MATERIAL_INTERPOLATION_SMOOTH = 0,
    FILA_MATERIAL_INTERPOLATION_FLAT = 1,
} FilaMaterialInterpolation;

typedef enum FilaMaterialBlendingMode {
    FILA_MATERIAL_BLENDING_MODE_OPAQUE = 0,
    FILA_MATERIAL_BLENDING_MODE_TRANSPARENT = 1,
    FILA_MATERIAL_BLENDING_MODE_ADD = 2,
    FILA_MATERIAL_BLENDING_MODE_MASKED = 3,
    FILA_MATERIAL_BLENDING_MODE_FADE = 4,
    FILA_MATERIAL_BLENDING_MODE_MULTIPLY = 5,
    FILA_MATERIAL_BLENDING_MODE_SCREEN = 6,
} FilaMaterialBlendingMode;

typedef enum FilaMaterialTransparencyMode {
    FILA_MATERIAL_TRANSPARENCY_MODE_DEFAULT = 0,
    FILA_MATERIAL_TRANSPARENCY_MODE_TWO_PASSES_ONE_SIDE = 1,
    FILA_MATERIAL_TRANSPARENCY_MODE_TWO_PASSES_TWO_SIDES = 2,
} FilaMaterialTransparencyMode;

typedef enum FilaMaterialCullingMode {
    FILA_MATERIAL_CULLING_MODE_NONE = 0,
    FILA_MATERIAL_CULLING_MODE_FRONT = 1,
    FILA_MATERIAL_CULLING_MODE_BACK = 2,
    FILA_MATERIAL_CULLING_MODE_FRONT_AND_BACK = 3,
} FilaMaterialCullingMode;

typedef enum FilaMaterialVertexDomain {
    FILA_MATERIAL_VERTEX_DOMAIN_OBJECT = 0,
    FILA_MATERIAL_VERTEX_DOMAIN_WORLD = 1,
    FILA_MATERIAL_VERTEX_DOMAIN_VIEW = 2,
    FILA_MATERIAL_VERTEX_DOMAIN_DEVICE = 3,
} FilaMaterialVertexDomain;

typedef enum FilaMaterialPrecision {
    FILA_MATERIAL_PRECISION_LOW = 0,
    FILA_MATERIAL_PRECISION_MEDIUM = 1,
    FILA_MATERIAL_PRECISION_HIGH = 2,
    FILA_MATERIAL_PRECISION_DEFAULT = 3,
} FilaMaterialPrecision;

typedef enum FilaMaterialCompilerPriorityQueue {
    FILA_MATERIAL_COMPILER_PRIORITY_QUEUE_CRITICAL = 0,
    FILA_MATERIAL_COMPILER_PRIORITY_QUEUE_HIGH = 1,
    FILA_MATERIAL_COMPILER_PRIORITY_QUEUE_LOW = 2,
} FilaMaterialCompilerPriorityQueue;

// ABI struct read by the FFM/cinterop bindings. The bridge copies it field-by-field
// from filament::Material::ParameterInfo, so this layout is independent of upstream.
// type/precision mirror the uint8_t enums (ParameterType / backend::Precision).
typedef struct FilaMaterialParameterInfo {
    const char* name;
    uint8_t isSampler;
    uint8_t isSubpass;
    uint8_t type;
    uint32_t count;
    uint8_t precision;
} FilaMaterialParameterInfo;

typedef void (*FilaMaterialCompileCallback)(FilaMaterial* material, void* userData);

// Persistent Builder
FilaMaterial_Builder* FilaMaterial_Builder_create(void);
void FilaMaterial_Builder_destroy(FilaMaterial_Builder* builder);
void FilaMaterial_Builder_package(FilaMaterial_Builder* builder, const void* payload, size_t size);
void FilaMaterial_Builder_sphericalHarmonicsBandCount(FilaMaterial_Builder* builder, int count);
void FilaMaterial_Builder_shadowSamplingQuality(FilaMaterial_Builder* builder, FilaMaterialShadowSamplingQuality quality);
void FilaMaterial_Builder_uboBatching(FilaMaterial_Builder* builder, FilaMaterialUboBatchingMode mode);
FilaMaterial* FilaMaterial_Builder_build(FilaMaterial_Builder* builder, FilaEngine* engine);

// Material methods
FilaMaterialInstance* FilaMaterial_getDefaultInstance(const FilaMaterial* material);
FilaMaterialInstance* FilaMaterial_createInstance(FilaMaterial* material);
FilaMaterialInstance* FilaMaterial_createInstanceWithName(FilaMaterial* material, const char* name);

const char* FilaMaterial_getName(const FilaMaterial* material);
FilaMaterialShading FilaMaterial_getShading(const FilaMaterial* material);
FilaMaterialInterpolation FilaMaterial_getInterpolation(const FilaMaterial* material);
FilaMaterialBlendingMode FilaMaterial_getBlendingMode(const FilaMaterial* material);
FilaMaterialTransparencyMode FilaMaterial_getTransparencyMode(const FilaMaterial* material);
int FilaMaterial_getRefractionMode(const FilaMaterial* material);
int FilaMaterial_getRefractionType(const FilaMaterial* material);
int FilaMaterial_getReflectionMode(const FilaMaterial* material);
FilaMaterialVertexDomain FilaMaterial_getVertexDomain(const FilaMaterial* material);
FilaMaterialCullingMode FilaMaterial_getCullingMode(const FilaMaterial* material);

bool FilaMaterial_isColorWriteEnabled(const FilaMaterial* material);
bool FilaMaterial_isDepthWriteEnabled(const FilaMaterial* material);
bool FilaMaterial_isDepthCullingEnabled(const FilaMaterial* material);
bool FilaMaterial_isDoubleSided(const FilaMaterial* material);
bool FilaMaterial_isAlphaToCoverageEnabled(const FilaMaterial* material);

float FilaMaterial_getMaskThreshold(const FilaMaterial* material);
float FilaMaterial_getSpecularAntiAliasingVariance(const FilaMaterial* material);
float FilaMaterial_getSpecularAntiAliasingThreshold(const FilaMaterial* material);
FilaEngineFeatureLevel FilaMaterial_getFeatureLevel(const FilaMaterial* material);

uint32_t FilaMaterial_getParameterCount(const FilaMaterial* material);
uint32_t FilaMaterial_getParameters(const FilaMaterial* material, FilaMaterialParameterInfo* parameters, uint32_t count);
uint32_t FilaMaterial_getRequiredAttributes(const FilaMaterial* material);

void FilaMaterial_compile(FilaMaterial* material, FilaMaterialCompilerPriorityQueue priority, uint32_t variants, void* handler, FilaMaterialCompileCallback callback, void* userData);
const char* FilaMaterial_getParameterTransformName(const FilaMaterial* material, const char* samplerName);

bool FilaMaterial_hasParameter(const FilaMaterial* material, const char* name);
void FilaMaterial_setDefaultParameter_bool(FilaMaterial* material, const char* name, bool value);
void FilaMaterial_setDefaultParameter_float(FilaMaterial* material, const char* name, float value);
void FilaMaterial_setDefaultParameter_int(FilaMaterial* material, const char* name, int32_t value);
void FilaMaterial_setDefaultParameter_float2(FilaMaterial* material, const char* name, float x, float y);
void FilaMaterial_setDefaultParameter_float3(FilaMaterial* material, const char* name, float x, float y, float z);
void FilaMaterial_setDefaultParameter_float4(FilaMaterial* material, const char* name, float x, float y, float z, float w);

#ifdef __cplusplus
}
#endif

#endif // FILAMENT_C_MATERIAL_H
