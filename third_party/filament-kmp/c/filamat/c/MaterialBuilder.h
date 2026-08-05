#ifndef FILAMAT_C_MATERIAL_BUILDER_H
#define FILAMAT_C_MATERIAL_BUILDER_H

#include "FilamatTypes.h"

#ifdef __cplusplus
extern "C" {
#endif

// Matches filamat::MaterialBuilder::MaterialDomain
typedef enum FilaMaterialBuilderMaterialDomain {
    FILA_MATERIAL_BUILDER_MATERIAL_DOMAIN_SURFACE = 0,
    FILA_MATERIAL_BUILDER_MATERIAL_DOMAIN_POST_PROCESS = 1,
} FilaMaterialBuilderMaterialDomain;

// Matches filamat::MaterialBuilder::Shading
typedef enum FilaMaterialBuilderShading {
    FILA_MATERIAL_BUILDER_SHADING_UNLIT = 0,
    FILA_MATERIAL_BUILDER_SHADING_LIT = 1,
    FILA_MATERIAL_BUILDER_SHADING_SUBSURFACE = 2,
    FILA_MATERIAL_BUILDER_SHADING_CLOTH = 3,
    FILA_MATERIAL_BUILDER_SHADING_SPECULAR_GLOSSINESS = 4,
} FilaMaterialBuilderShading;

// Matches filamat::MaterialBuilder::Interpolation
typedef enum FilaMaterialBuilderInterpolation {
    FILA_MATERIAL_BUILDER_INTERPOLATION_SMOOTH = 0,
    FILA_MATERIAL_BUILDER_INTERPOLATION_FLAT = 1,
} FilaMaterialBuilderInterpolation;

// Matches filamat::MaterialBuilder::BlendingMode
typedef enum FilaMaterialBuilderBlendingMode {
    FILA_MATERIAL_BUILDER_BLENDING_MODE_OPAQUE = 0,
    FILA_MATERIAL_BUILDER_BLENDING_MODE_TRANSPARENT = 1,
    FILA_MATERIAL_BUILDER_BLENDING_MODE_ADD = 2,
    FILA_MATERIAL_BUILDER_BLENDING_MODE_MASKED = 3,
    FILA_MATERIAL_BUILDER_BLENDING_MODE_FADE = 4,
    FILA_MATERIAL_BUILDER_BLENDING_MODE_MULTIPLY = 5,
    FILA_MATERIAL_BUILDER_BLENDING_MODE_SCREEN = 6,
    FILA_MATERIAL_BUILDER_BLENDING_MODE_CUSTOM = 7,
} FilaMaterialBuilderBlendingMode;

// Matches filamat::MaterialBuilder::VertexDomain
typedef enum FilaMaterialBuilderVertexDomain {
    FILA_MATERIAL_BUILDER_VERTEX_DOMAIN_OBJECT = 0,
    FILA_MATERIAL_BUILDER_VERTEX_DOMAIN_WORLD = 1,
    FILA_MATERIAL_BUILDER_VERTEX_DOMAIN_VIEW = 2,
    FILA_MATERIAL_BUILDER_VERTEX_DOMAIN_DEVICE = 3,
} FilaMaterialBuilderVertexDomain;

// Matches filamat::MaterialBuilder::MaterialCullingMode
typedef enum FilaMaterialBuilderCullingMode {
    FILA_MATERIAL_BUILDER_CULLING_MODE_NONE = 0,
    FILA_MATERIAL_BUILDER_CULLING_MODE_FRONT = 1,
    FILA_MATERIAL_BUILDER_CULLING_MODE_BACK = 2,
    FILA_MATERIAL_BUILDER_CULLING_MODE_FRONT_AND_BACK = 3,
} FilaMaterialBuilderCullingMode;

// Matches filamat::MaterialBuilder::UniformType (DriverEnums.h)
typedef enum FilaMaterialBuilderUniformType {
    FILA_MATERIAL_BUILDER_UNIFORM_TYPE_BOOL = 0,
    FILA_MATERIAL_BUILDER_UNIFORM_TYPE_BOOL2 = 1,
    FILA_MATERIAL_BUILDER_UNIFORM_TYPE_BOOL3 = 2,
    FILA_MATERIAL_BUILDER_UNIFORM_TYPE_BOOL4 = 3,
    FILA_MATERIAL_BUILDER_UNIFORM_TYPE_FLOAT = 4,
    FILA_MATERIAL_BUILDER_UNIFORM_TYPE_FLOAT2 = 5,
    FILA_MATERIAL_BUILDER_UNIFORM_TYPE_FLOAT3 = 6,
    FILA_MATERIAL_BUILDER_UNIFORM_TYPE_FLOAT4 = 7,
    FILA_MATERIAL_BUILDER_UNIFORM_TYPE_INT = 8,
    FILA_MATERIAL_BUILDER_UNIFORM_TYPE_INT2 = 9,
    FILA_MATERIAL_BUILDER_UNIFORM_TYPE_INT3 = 10,
    FILA_MATERIAL_BUILDER_UNIFORM_TYPE_INT4 = 11,
    FILA_MATERIAL_BUILDER_UNIFORM_TYPE_UINT = 12,
    FILA_MATERIAL_BUILDER_UNIFORM_TYPE_UINT2 = 13,
    FILA_MATERIAL_BUILDER_UNIFORM_TYPE_UINT3 = 14,
    FILA_MATERIAL_BUILDER_UNIFORM_TYPE_UINT4 = 15,
    FILA_MATERIAL_BUILDER_UNIFORM_TYPE_MAT3 = 16,
    FILA_MATERIAL_BUILDER_UNIFORM_TYPE_MAT4 = 17,
} FilaMaterialBuilderUniformType;

// Matches filamat::MaterialBuilder::SamplerType (DriverEnums.h)
typedef enum FilaMaterialBuilderSamplerType {
    FILA_MATERIAL_BUILDER_SAMPLER_TYPE_SAMPLER_2D = 0,
    FILA_MATERIAL_BUILDER_SAMPLER_TYPE_SAMPLER_2D_ARRAY = 1,
    FILA_MATERIAL_BUILDER_SAMPLER_TYPE_SAMPLER_CUBEMAP = 2,
    FILA_MATERIAL_BUILDER_SAMPLER_TYPE_SAMPLER_EXTERNAL = 3,
    FILA_MATERIAL_BUILDER_SAMPLER_TYPE_SAMPLER_3D = 4,
    FILA_MATERIAL_BUILDER_SAMPLER_TYPE_SAMPLER_CUBEMAP_ARRAY = 5,
} FilaMaterialBuilderSamplerType;

// Matches filamat::MaterialBuilder::SamplerFormat (DriverEnums.h)
typedef enum FilaMaterialBuilderSamplerFormat {
    FILA_MATERIAL_BUILDER_SAMPLER_FORMAT_INT = 0,
    FILA_MATERIAL_BUILDER_SAMPLER_FORMAT_UINT = 1,
    FILA_MATERIAL_BUILDER_SAMPLER_FORMAT_FLOAT = 2,
    FILA_MATERIAL_BUILDER_SAMPLER_FORMAT_SHADOW = 3,
} FilaMaterialBuilderSamplerFormat;

// Matches filamat::MaterialBuilder::ParameterPrecision (DriverEnums.h)
typedef enum FilaMaterialBuilderParameterPrecision {
    FILA_MATERIAL_BUILDER_PARAMETER_PRECISION_LOW = 0,
    FILA_MATERIAL_BUILDER_PARAMETER_PRECISION_MEDIUM = 1,
    FILA_MATERIAL_BUILDER_PARAMETER_PRECISION_HIGH = 2,
    FILA_MATERIAL_BUILDER_PARAMETER_PRECISION_DEFAULT = 3,
} FilaMaterialBuilderParameterPrecision;

// Matches filamat::MaterialBuilder::Variable
typedef enum FilaMaterialBuilderVariable {
    FILA_MATERIAL_BUILDER_VARIABLE_CUSTOM0 = 0,
    FILA_MATERIAL_BUILDER_VARIABLE_CUSTOM1 = 1,
    FILA_MATERIAL_BUILDER_VARIABLE_CUSTOM2 = 2,
    FILA_MATERIAL_BUILDER_VARIABLE_CUSTOM3 = 3,
    FILA_MATERIAL_BUILDER_VARIABLE_CUSTOM4 = 4,
} FilaMaterialBuilderVariable;

// Matches filamat::MaterialBuilder::Platform
typedef enum FilaMaterialBuilderPlatform {
    FILA_MATERIAL_BUILDER_PLATFORM_DESKTOP = 0,
    FILA_MATERIAL_BUILDER_PLATFORM_MOBILE = 1,
    FILA_MATERIAL_BUILDER_PLATFORM_ALL = 2,
} FilaMaterialBuilderPlatform;

// Matches filamat::MaterialBuilder::TargetApi (BITMASK)
typedef enum FilaMaterialBuilderTargetApi {
    FILA_MATERIAL_BUILDER_TARGET_API_OPENGL = 0x01,
    FILA_MATERIAL_BUILDER_TARGET_API_VULKAN = 0x02,
    FILA_MATERIAL_BUILDER_TARGET_API_METAL = 0x04,
    FILA_MATERIAL_BUILDER_TARGET_API_WEBGPU = 0x08,
    FILA_MATERIAL_BUILDER_TARGET_API_ALL = 0x0F,
} FilaMaterialBuilderTargetApi;

// Matches filamat::MaterialBuilder::Optimization
typedef enum FilaMaterialBuilderOptimization {
    FILA_MATERIAL_BUILDER_OPTIMIZATION_NONE = 0,
    FILA_MATERIAL_BUILDER_OPTIMIZATION_PREPROCESSOR = 1,
    FILA_MATERIAL_BUILDER_OPTIMIZATION_SIZE = 2,
    FILA_MATERIAL_BUILDER_OPTIMIZATION_PERFORMANCE = 3,
} FilaMaterialBuilderOptimization;

// Matches filament::TransparencyMode
typedef enum FilaMaterialBuilderTransparencyMode {
    FILA_MATERIAL_BUILDER_TRANSPARENCY_MODE_DEFAULT = 0,
    FILA_MATERIAL_BUILDER_TRANSPARENCY_MODE_TWO_PASSES_ONE_SIDE = 1,
    FILA_MATERIAL_BUILDER_TRANSPARENCY_MODE_TWO_PASSES_TWO_SIDES = 2,
} FilaMaterialBuilderTransparencyMode;

// Matches filament::SpecularAmbientOcclusion
typedef enum FilaMaterialBuilderSpecularAmbientOcclusion {
    FILA_MATERIAL_BUILDER_SPECULAR_AMBIENT_OCCLUSION_NONE = 0,
    FILA_MATERIAL_BUILDER_SPECULAR_AMBIENT_OCCLUSION_SIMPLE = 1,
    FILA_MATERIAL_BUILDER_SPECULAR_AMBIENT_OCCLUSION_BENT_NORMALS = 2,
} FilaMaterialBuilderSpecularAmbientOcclusion;

// Matches filament::RefractionMode
typedef enum FilaMaterialBuilderRefractionMode {
    FILA_MATERIAL_BUILDER_REFRACTION_MODE_NONE = 0,
    FILA_MATERIAL_BUILDER_REFRACTION_MODE_CUBEMAP = 1,
    FILA_MATERIAL_BUILDER_REFRACTION_MODE_SCREEN_SPACE = 2,
} FilaMaterialBuilderRefractionMode;

// Matches filament::ReflectionMode
typedef enum FilaMaterialBuilderReflectionMode {
    FILA_MATERIAL_BUILDER_REFLECTION_MODE_DEFAULT = 0,
    FILA_MATERIAL_BUILDER_REFLECTION_MODE_SCREEN_SPACE = 1,
} FilaMaterialBuilderReflectionMode;

// Matches filament::RefractionType
typedef enum FilaMaterialBuilderRefractionType {
    FILA_MATERIAL_BUILDER_REFRACTION_TYPE_SOLID = 0,
    FILA_MATERIAL_BUILDER_REFRACTION_TYPE_THIN = 1,
} FilaMaterialBuilderRefractionType;

// Matches filament::ShaderQuality
typedef enum FilaMaterialBuilderShaderQuality {
    FILA_MATERIAL_BUILDER_SHADER_QUALITY_DEFAULT = -1,
    FILA_MATERIAL_BUILDER_SHADER_QUALITY_LOW = 0,
    FILA_MATERIAL_BUILDER_SHADER_QUALITY_NORMAL = 1,
    FILA_MATERIAL_BUILDER_SHADER_QUALITY_HIGH = 2,
} FilaMaterialBuilderShaderQuality;

// Static methods
void FilaMaterialBuilder_init(void);
void FilaMaterialBuilder_shutdown(void);

// Builder methods
FilaMaterialBuilder* FilaMaterialBuilder_create(void);
void FilaMaterialBuilder_destroy(FilaMaterialBuilder* builder);
FilaPackage* FilaMaterialBuilder_build(FilaMaterialBuilder* builder);

void FilaMaterialBuilder_name(FilaMaterialBuilder* builder, const char* name);
void FilaMaterialBuilder_materialDomain(FilaMaterialBuilder* builder, FilaMaterialBuilderMaterialDomain domain);
void FilaMaterialBuilder_shading(FilaMaterialBuilder* builder, FilaMaterialBuilderShading shading);
void FilaMaterialBuilder_interpolation(FilaMaterialBuilder* builder, FilaMaterialBuilderInterpolation interpolation);
void FilaMaterialBuilder_uniformParameter(FilaMaterialBuilder* builder, FilaMaterialBuilderUniformType type, FilaMaterialBuilderParameterPrecision precision, const char* name);
void FilaMaterialBuilder_uniformParameterArray(FilaMaterialBuilder* builder, FilaMaterialBuilderUniformType type, size_t size, FilaMaterialBuilderParameterPrecision precision, const char* name);
void FilaMaterialBuilder_samplerParameter(FilaMaterialBuilder* builder, FilaMaterialBuilderSamplerType type, FilaMaterialBuilderSamplerFormat format, FilaMaterialBuilderParameterPrecision precision, const char* name);
void FilaMaterialBuilder_variable(FilaMaterialBuilder* builder, FilaMaterialBuilderVariable variable, const char* name);
void FilaMaterialBuilder_require(FilaMaterialBuilder* builder, FilaVertexAttribute attribute);
void FilaMaterialBuilder_material(FilaMaterialBuilder* builder, const char* code);
void FilaMaterialBuilder_materialVertex(FilaMaterialBuilder* builder, const char* code);
void FilaMaterialBuilder_blending(FilaMaterialBuilder* builder, FilaMaterialBuilderBlendingMode mode);
void FilaMaterialBuilder_postLightingBlending(FilaMaterialBuilder* builder, FilaMaterialBuilderBlendingMode mode);
void FilaMaterialBuilder_vertexDomain(FilaMaterialBuilder* builder, FilaMaterialBuilderVertexDomain domain);
void FilaMaterialBuilder_culling(FilaMaterialBuilder* builder, FilaMaterialBuilderCullingMode mode);
void FilaMaterialBuilder_colorWrite(FilaMaterialBuilder* builder, bool enable);
void FilaMaterialBuilder_depthWrite(FilaMaterialBuilder* builder, bool enable);
void FilaMaterialBuilder_depthCulling(FilaMaterialBuilder* builder, bool enable);
void FilaMaterialBuilder_doubleSided(FilaMaterialBuilder* builder, bool doubleSided);
void FilaMaterialBuilder_maskThreshold(FilaMaterialBuilder* builder, float threshold);
void FilaMaterialBuilder_alphaToCoverage(FilaMaterialBuilder* builder, bool enable);
void FilaMaterialBuilder_shadowMultiplier(FilaMaterialBuilder* builder, bool enable);
void FilaMaterialBuilder_transparentShadow(FilaMaterialBuilder* builder, bool enable);
void FilaMaterialBuilder_coloredPenumbra(FilaMaterialBuilder* builder, bool enable);
void FilaMaterialBuilder_specularAntiAliasing(FilaMaterialBuilder* builder, bool enable);
void FilaMaterialBuilder_specularAntiAliasingVariance(FilaMaterialBuilder* builder, float variance);
void FilaMaterialBuilder_specularAntiAliasingThreshold(FilaMaterialBuilder* builder, float threshold);
void FilaMaterialBuilder_clearCoatIorChange(FilaMaterialBuilder* builder, bool enable);
void FilaMaterialBuilder_flipUV(FilaMaterialBuilder* builder, bool enable);
void FilaMaterialBuilder_customSurfaceShading(FilaMaterialBuilder* builder, bool enable);
void FilaMaterialBuilder_multiBounceAmbientOcclusion(FilaMaterialBuilder* builder, bool enable);
void FilaMaterialBuilder_specularAmbientOcclusion(FilaMaterialBuilder* builder, FilaMaterialBuilderSpecularAmbientOcclusion mode);
void FilaMaterialBuilder_refractionMode(FilaMaterialBuilder* builder, FilaMaterialBuilderRefractionMode mode);
void FilaMaterialBuilder_reflectionMode(FilaMaterialBuilder* builder, FilaMaterialBuilderReflectionMode mode);
void FilaMaterialBuilder_refractionType(FilaMaterialBuilder* builder, FilaMaterialBuilderRefractionType type);
void FilaMaterialBuilder_transparencyMode(FilaMaterialBuilder* builder, FilaMaterialBuilderTransparencyMode mode);
void FilaMaterialBuilder_platform(FilaMaterialBuilder* builder, FilaMaterialBuilderPlatform platform);
void FilaMaterialBuilder_targetApi(FilaMaterialBuilder* builder, FilaMaterialBuilderTargetApi targetApi);
void FilaMaterialBuilder_optimization(FilaMaterialBuilder* builder, FilaMaterialBuilderOptimization optimization);
void FilaMaterialBuilder_variantFilter(FilaMaterialBuilder* builder, uint32_t variantFilter);
void FilaMaterialBuilder_useLegacyMorphing(FilaMaterialBuilder* builder);

// New methods added for full parity
void FilaMaterialBuilder_shaderDefine(FilaMaterialBuilder* builder, const char* name, const char* value);
void FilaMaterialBuilder_quality(FilaMaterialBuilder* builder, FilaMaterialBuilderShaderQuality quality);
void FilaMaterialBuilder_featureLevel(FilaMaterialBuilder* builder, uint8_t featureLevel);
void FilaMaterialBuilder_instanced(FilaMaterialBuilder* builder, bool enable);
void FilaMaterialBuilder_linearFog(FilaMaterialBuilder* builder, bool enable);
void FilaMaterialBuilder_shadowFarAttenuation(FilaMaterialBuilder* builder, bool enable);
void FilaMaterialBuilder_useDefaultDepthVariant(FilaMaterialBuilder* builder);

// Package methods
void FilaPackage_destroy(FilaPackage* package);
bool FilaPackage_isValid(const FilaPackage* package);
const void* FilaPackage_getData(const FilaPackage* package);
size_t FilaPackage_getSize(const FilaPackage* package);

#ifdef __cplusplus
}
#endif

#endif // FILAMAT_C_MATERIAL_BUILDER_H
