#include <filamat/MaterialBuilder.h>
#include <utils/JobSystem.h>

#include "../../filament/cpp/FilaCommon.h"
#include "../c/MaterialBuilder.h"

using namespace filament;
using namespace filamat;

extern "C" {

void FilaMaterialBuilder_init() {
    MaterialBuilder::init();
}

void FilaMaterialBuilder_shutdown() {
    MaterialBuilder::shutdown();
}

FilaMaterialBuilder* FilaMaterialBuilder_create() {
    return reinterpret_cast<FilaMaterialBuilder*>(new MaterialBuilder());
}

void FilaMaterialBuilder_destroy(FilaMaterialBuilder* builder) {
    if (builder) {
        delete reinterpret_cast<MaterialBuilder*>(builder);
    }
}

FilaPackage* FilaMaterialBuilder_build(FilaMaterialBuilder* builder) {
    auto b = reinterpret_cast<MaterialBuilder*>(builder);
    utils::JobSystem jobSystem;
    jobSystem.adopt();
    Package p = b->build(jobSystem);
    jobSystem.emancipate();
    return reinterpret_cast<FilaPackage*>(new Package(std::move(p)));
}

void FilaMaterialBuilder_name(FilaMaterialBuilder* builder, const char* name) {
    reinterpret_cast<MaterialBuilder*>(builder)->name(name);
}

void FilaMaterialBuilder_materialDomain(FilaMaterialBuilder* builder, FilaMaterialBuilderMaterialDomain domain) {
    reinterpret_cast<MaterialBuilder*>(builder)->materialDomain(static_cast<MaterialBuilder::MaterialDomain>(domain));
}

void FilaMaterialBuilder_shading(FilaMaterialBuilder* builder, FilaMaterialBuilderShading shading) {
    reinterpret_cast<MaterialBuilder*>(builder)->shading(static_cast<MaterialBuilder::Shading>(shading));
}

void FilaMaterialBuilder_interpolation(FilaMaterialBuilder* builder, FilaMaterialBuilderInterpolation interpolation) {
    reinterpret_cast<MaterialBuilder*>(builder)->interpolation(static_cast<MaterialBuilder::Interpolation>(interpolation));
}

void FilaMaterialBuilder_uniformParameter(FilaMaterialBuilder* builder, FilaMaterialBuilderUniformType type, FilaMaterialBuilderParameterPrecision precision, const char* name) {
    reinterpret_cast<MaterialBuilder*>(builder)->parameter(name, static_cast<MaterialBuilder::UniformType>(type), static_cast<MaterialBuilder::ParameterPrecision>(precision));
}

void FilaMaterialBuilder_uniformParameterArray(FilaMaterialBuilder* builder, FilaMaterialBuilderUniformType type, size_t size, FilaMaterialBuilderParameterPrecision precision, const char* name) {
    reinterpret_cast<MaterialBuilder*>(builder)->parameter(name, size, static_cast<MaterialBuilder::UniformType>(type), static_cast<MaterialBuilder::ParameterPrecision>(precision));
}

void FilaMaterialBuilder_samplerParameter(FilaMaterialBuilder* builder, FilaMaterialBuilderSamplerType type, FilaMaterialBuilderSamplerFormat format, FilaMaterialBuilderParameterPrecision precision, const char* name) {
    reinterpret_cast<MaterialBuilder*>(builder)->parameter(name, static_cast<backend::SamplerType>(type), static_cast<backend::SamplerFormat>(format), static_cast<backend::Precision>(precision));
}

void FilaMaterialBuilder_variable(FilaMaterialBuilder* builder, FilaMaterialBuilderVariable variable, const char* name) {
    reinterpret_cast<MaterialBuilder*>(builder)->variable(static_cast<MaterialBuilder::Variable>(variable), name);
}

void FilaMaterialBuilder_require(FilaMaterialBuilder* builder, FilaVertexAttribute attribute) {
    reinterpret_cast<MaterialBuilder*>(builder)->require(static_cast<VertexAttribute>(attribute));
}

void FilaMaterialBuilder_material(FilaMaterialBuilder* builder, const char* code) {
    reinterpret_cast<MaterialBuilder*>(builder)->material(code);
}

void FilaMaterialBuilder_materialVertex(FilaMaterialBuilder* builder, const char* code) {
    reinterpret_cast<MaterialBuilder*>(builder)->materialVertex(code);
}

void FilaMaterialBuilder_blending(FilaMaterialBuilder* builder, FilaMaterialBuilderBlendingMode mode) {
    reinterpret_cast<MaterialBuilder*>(builder)->blending(static_cast<MaterialBuilder::BlendingMode>(mode));
}

void FilaMaterialBuilder_postLightingBlending(FilaMaterialBuilder* builder, FilaMaterialBuilderBlendingMode mode) {
    reinterpret_cast<MaterialBuilder*>(builder)->postLightingBlending(static_cast<MaterialBuilder::BlendingMode>(mode));
}

void FilaMaterialBuilder_vertexDomain(FilaMaterialBuilder* builder, FilaMaterialBuilderVertexDomain domain) {
    reinterpret_cast<MaterialBuilder*>(builder)->vertexDomain(static_cast<MaterialBuilder::VertexDomain>(domain));
}

void FilaMaterialBuilder_culling(FilaMaterialBuilder* builder, FilaMaterialBuilderCullingMode mode) {
    reinterpret_cast<MaterialBuilder*>(builder)->culling(static_cast<MaterialBuilder::CullingMode>(mode));
}

void FilaMaterialBuilder_colorWrite(FilaMaterialBuilder* builder, bool enable) {
    reinterpret_cast<MaterialBuilder*>(builder)->colorWrite(enable);
}

void FilaMaterialBuilder_depthWrite(FilaMaterialBuilder* builder, bool enable) {
    reinterpret_cast<MaterialBuilder*>(builder)->depthWrite(enable);
}

void FilaMaterialBuilder_depthCulling(FilaMaterialBuilder* builder, bool enable) {
    reinterpret_cast<MaterialBuilder*>(builder)->depthCulling(enable);
}

void FilaMaterialBuilder_doubleSided(FilaMaterialBuilder* builder, bool doubleSided) {
    reinterpret_cast<MaterialBuilder*>(builder)->doubleSided(doubleSided);
}

void FilaMaterialBuilder_maskThreshold(FilaMaterialBuilder* builder, float threshold) {
    reinterpret_cast<MaterialBuilder*>(builder)->maskThreshold(threshold);
}

void FilaMaterialBuilder_alphaToCoverage(FilaMaterialBuilder* builder, bool enable) {
    reinterpret_cast<MaterialBuilder*>(builder)->alphaToCoverage(enable);
}

void FilaMaterialBuilder_shadowMultiplier(FilaMaterialBuilder* builder, bool enable) {
    reinterpret_cast<MaterialBuilder*>(builder)->shadowMultiplier(enable);
}

void FilaMaterialBuilder_transparentShadow(FilaMaterialBuilder* builder, bool enable) {
    reinterpret_cast<MaterialBuilder*>(builder)->transparentShadow(enable);
}

void FilaMaterialBuilder_coloredPenumbra(FilaMaterialBuilder* builder, bool enable) {
    reinterpret_cast<MaterialBuilder*>(builder)->coloredPenumbra(enable);
}

void FilaMaterialBuilder_specularAntiAliasing(FilaMaterialBuilder* builder, bool enable) {
    reinterpret_cast<MaterialBuilder*>(builder)->specularAntiAliasing(enable);
}

void FilaMaterialBuilder_specularAntiAliasingVariance(FilaMaterialBuilder* builder, float variance) {
    reinterpret_cast<MaterialBuilder*>(builder)->specularAntiAliasingVariance(variance);
}

void FilaMaterialBuilder_specularAntiAliasingThreshold(FilaMaterialBuilder* builder, float threshold) {
    reinterpret_cast<MaterialBuilder*>(builder)->specularAntiAliasingThreshold(threshold);
}

void FilaMaterialBuilder_clearCoatIorChange(FilaMaterialBuilder* builder, bool enable) {
    reinterpret_cast<MaterialBuilder*>(builder)->clearCoatIorChange(enable);
}

void FilaMaterialBuilder_flipUV(FilaMaterialBuilder* builder, bool enable) {
    reinterpret_cast<MaterialBuilder*>(builder)->flipUV(enable);
}

void FilaMaterialBuilder_customSurfaceShading(FilaMaterialBuilder* builder, bool enable) {
    reinterpret_cast<MaterialBuilder*>(builder)->customSurfaceShading(enable);
}

void FilaMaterialBuilder_multiBounceAmbientOcclusion(FilaMaterialBuilder* builder, bool enable) {
    reinterpret_cast<MaterialBuilder*>(builder)->multiBounceAmbientOcclusion(enable);
}

void FilaMaterialBuilder_specularAmbientOcclusion(FilaMaterialBuilder* builder, FilaMaterialBuilderSpecularAmbientOcclusion mode) {
    reinterpret_cast<MaterialBuilder*>(builder)->specularAmbientOcclusion(static_cast<MaterialBuilder::SpecularAmbientOcclusion>(mode));
}

void FilaMaterialBuilder_refractionMode(FilaMaterialBuilder* builder, FilaMaterialBuilderRefractionMode mode) {
    reinterpret_cast<MaterialBuilder*>(builder)->refractionMode(static_cast<MaterialBuilder::RefractionMode>(mode));
}

void FilaMaterialBuilder_reflectionMode(FilaMaterialBuilder* builder, FilaMaterialBuilderReflectionMode mode) {
    reinterpret_cast<MaterialBuilder*>(builder)->reflectionMode(static_cast<MaterialBuilder::ReflectionMode>(mode));
}

void FilaMaterialBuilder_refractionType(FilaMaterialBuilder* builder, FilaMaterialBuilderRefractionType type) {
    reinterpret_cast<MaterialBuilder*>(builder)->refractionType(static_cast<MaterialBuilder::RefractionType>(type));
}

void FilaMaterialBuilder_transparencyMode(FilaMaterialBuilder* builder, FilaMaterialBuilderTransparencyMode mode) {
    reinterpret_cast<MaterialBuilder*>(builder)->transparencyMode(static_cast<MaterialBuilder::TransparencyMode>(mode));
}

void FilaMaterialBuilder_platform(FilaMaterialBuilder* builder, FilaMaterialBuilderPlatform platform) {
    reinterpret_cast<MaterialBuilder*>(builder)->platform(static_cast<MaterialBuilder::Platform>(platform));
}

void FilaMaterialBuilder_targetApi(FilaMaterialBuilder* builder, FilaMaterialBuilderTargetApi targetApi) {
    reinterpret_cast<MaterialBuilder*>(builder)->targetApi(static_cast<MaterialBuilder::TargetApi>(targetApi));
}

void FilaMaterialBuilder_optimization(FilaMaterialBuilder* builder, FilaMaterialBuilderOptimization optimization) {
    reinterpret_cast<MaterialBuilder*>(builder)->optimization(static_cast<MaterialBuilder::Optimization>(optimization));
}

void FilaMaterialBuilder_variantFilter(FilaMaterialBuilder* builder, uint32_t variantFilter) {
    reinterpret_cast<MaterialBuilder*>(builder)->variantFilter(variantFilter);
}

void FilaMaterialBuilder_useLegacyMorphing(FilaMaterialBuilder* builder) {
    reinterpret_cast<MaterialBuilder*>(builder)->useLegacyMorphing();
}

void FilaMaterialBuilder_shaderDefine(FilaMaterialBuilder* builder, const char* name, const char* value) {
    reinterpret_cast<MaterialBuilder*>(builder)->shaderDefine(name, value);
}

void FilaMaterialBuilder_quality(FilaMaterialBuilder* builder, FilaMaterialBuilderShaderQuality quality) {
    reinterpret_cast<MaterialBuilder*>(builder)->quality(static_cast<MaterialBuilder::ShaderQuality>(quality));
}

void FilaMaterialBuilder_featureLevel(FilaMaterialBuilder* builder, uint8_t featureLevel) {
    reinterpret_cast<MaterialBuilder*>(builder)->featureLevel(static_cast<backend::FeatureLevel>(featureLevel));
}

void FilaMaterialBuilder_instanced(FilaMaterialBuilder* builder, bool enable) {
    reinterpret_cast<MaterialBuilder*>(builder)->instanced(enable);
}

void FilaMaterialBuilder_linearFog(FilaMaterialBuilder* builder, bool enable) {
    reinterpret_cast<MaterialBuilder*>(builder)->linearFog(enable);
}

void FilaMaterialBuilder_shadowFarAttenuation(FilaMaterialBuilder* builder, bool enable) {
    reinterpret_cast<MaterialBuilder*>(builder)->shadowFarAttenuation(enable);
}

void FilaMaterialBuilder_useDefaultDepthVariant(FilaMaterialBuilder* builder) {
    reinterpret_cast<MaterialBuilder*>(builder)->useDefaultDepthVariant();
}

// Package
void FilaPackage_destroy(FilaPackage* package) {
    if (package) {
        delete reinterpret_cast<Package*>(package);
    }
}

bool FilaPackage_isValid(const FilaPackage* package) {
    return reinterpret_cast<const Package*>(package)->isValid();
}

const void* FilaPackage_getData(const FilaPackage* package) {
    return reinterpret_cast<const Package*>(package)->getData();
}

size_t FilaPackage_getSize(const FilaPackage* package) {
    return reinterpret_cast<const Package*>(package)->getSize();
}

} // extern "C"
