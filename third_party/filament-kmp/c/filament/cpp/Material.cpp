#include <filament/Material.h>
#include <filament/MaterialInstance.h>
#include <filament/Engine.h>

#include <utils/Entity.h>
#include <utils/EntityManager.h>
#include <math/vec4.h>

#include <assert.h>
#include <vector>

#include "FilaCommon.h"
#include "../c/Material.h"

using namespace filament;

extern "C" {

FilaMaterial_Builder* FilaMaterial_Builder_create() {
    return reinterpret_cast<FilaMaterial_Builder*>(new Material::Builder());
}

void FilaMaterial_Builder_destroy(FilaMaterial_Builder* builder) {
    delete reinterpret_cast<Material::Builder*>(builder);
}

void FilaMaterial_Builder_package(FilaMaterial_Builder* builder, const void* payload, size_t size) {
    reinterpret_cast<Material::Builder*>(builder)->package(payload, size);
}

void FilaMaterial_Builder_sphericalHarmonicsBandCount(FilaMaterial_Builder* builder, int count) {
    reinterpret_cast<Material::Builder*>(builder)->sphericalHarmonicsBandCount(count);
}

void FilaMaterial_Builder_shadowSamplingQuality(FilaMaterial_Builder* builder, FilaMaterialShadowSamplingQuality quality) {
    reinterpret_cast<Material::Builder*>(builder)->shadowSamplingQuality(
        static_cast<Material::Builder::ShadowSamplingQuality>(quality));
}

void FilaMaterial_Builder_uboBatching(FilaMaterial_Builder* builder, FilaMaterialUboBatchingMode mode) {
    reinterpret_cast<Material::Builder*>(builder)->uboBatching(
        static_cast<Material::UboBatchingMode>(mode));
}

FilaMaterial* FilaMaterial_Builder_build(FilaMaterial_Builder* builder, FilaEngine* engine) {
    return reinterpret_cast<FilaMaterial*>(
        reinterpret_cast<Material::Builder*>(builder)->build(*FILA_CAST(Engine, engine))
    );
}

FilaMaterialInstance* FilaMaterial_getDefaultInstance(const FilaMaterial* material) {
    return reinterpret_cast<FilaMaterialInstance*>(
        const_cast<MaterialInstance*>(FILA_CONST_CAST(Material, material)->getDefaultInstance())
    );
}

FilaMaterialInstance* FilaMaterial_createInstance(FilaMaterial* material) {
    return reinterpret_cast<FilaMaterialInstance*>(FILA_CAST(Material, material)->createInstance());
}

FilaMaterialInstance* FilaMaterial_createInstanceWithName(FilaMaterial* material, const char* name) {
    return reinterpret_cast<FilaMaterialInstance*>(FILA_CAST(Material, material)->createInstance(name));
}

const char* FilaMaterial_getName(const FilaMaterial* material) {
    return FILA_CONST_CAST(Material, material)->getName();
}

FilaMaterialShading FilaMaterial_getShading(const FilaMaterial* material) {
    return static_cast<FilaMaterialShading>(FILA_CONST_CAST(Material, material)->getShading());
}

FilaMaterialInterpolation FilaMaterial_getInterpolation(const FilaMaterial* material) {
    return static_cast<FilaMaterialInterpolation>(FILA_CONST_CAST(Material, material)->getInterpolation());
}

FilaMaterialBlendingMode FilaMaterial_getBlendingMode(const FilaMaterial* material) {
    return static_cast<FilaMaterialBlendingMode>(FILA_CONST_CAST(Material, material)->getBlendingMode());
}

FilaMaterialTransparencyMode FilaMaterial_getTransparencyMode(const FilaMaterial* material) {
    return static_cast<FilaMaterialTransparencyMode>(FILA_CONST_CAST(Material, material)->getTransparencyMode());
}

int FilaMaterial_getRefractionMode(const FilaMaterial* material) {
    return static_cast<int>(FILA_CONST_CAST(Material, material)->getRefractionMode());
}

int FilaMaterial_getRefractionType(const FilaMaterial* material) {
    return static_cast<int>(FILA_CONST_CAST(Material, material)->getRefractionType());
}

int FilaMaterial_getReflectionMode(const FilaMaterial* material) {
    return static_cast<int>(FILA_CONST_CAST(Material, material)->getReflectionMode());
}

FilaMaterialVertexDomain FilaMaterial_getVertexDomain(const FilaMaterial* material) {
    return static_cast<FilaMaterialVertexDomain>(FILA_CONST_CAST(Material, material)->getVertexDomain());
}

FilaMaterialCullingMode FilaMaterial_getCullingMode(const FilaMaterial* material) {
    return static_cast<FilaMaterialCullingMode>(FILA_CONST_CAST(Material, material)->getCullingMode());
}

bool FilaMaterial_isColorWriteEnabled(const FilaMaterial* material) {
    return FILA_CONST_CAST(Material, material)->isColorWriteEnabled();
}

bool FilaMaterial_isDepthWriteEnabled(const FilaMaterial* material) {
    return FILA_CONST_CAST(Material, material)->isDepthWriteEnabled();
}

bool FilaMaterial_isDepthCullingEnabled(const FilaMaterial* material) {
    return FILA_CONST_CAST(Material, material)->isDepthCullingEnabled();
}

bool FilaMaterial_isDoubleSided(const FilaMaterial* material) {
    return FILA_CONST_CAST(Material, material)->isDoubleSided();
}

bool FilaMaterial_isAlphaToCoverageEnabled(const FilaMaterial* material) {
    return FILA_CONST_CAST(Material, material)->isAlphaToCoverageEnabled();
}

float FilaMaterial_getMaskThreshold(const FilaMaterial* material) {
    return FILA_CONST_CAST(Material, material)->getMaskThreshold();
}

float FilaMaterial_getSpecularAntiAliasingVariance(const FilaMaterial* material) {
    return FILA_CONST_CAST(Material, material)->getSpecularAntiAliasingVariance();
}

float FilaMaterial_getSpecularAntiAliasingThreshold(const FilaMaterial* material) {
    return FILA_CONST_CAST(Material, material)->getSpecularAntiAliasingThreshold();
}
 
FilaEngineFeatureLevel FilaMaterial_getFeatureLevel(const FilaMaterial* material) {
    return static_cast<FilaEngineFeatureLevel>(FILA_CONST_CAST(Material, material)->getFeatureLevel());
}

uint32_t FilaMaterial_getParameterCount(const FilaMaterial* material) {
    return static_cast<uint32_t>(FILA_CONST_CAST(Material, material)->getParameterCount());
}

uint32_t FilaMaterial_getParameters(const FilaMaterial* material, FilaMaterialParameterInfo* out, uint32_t count) {
    auto m = FILA_CONST_CAST(Material, material);
    // Field-by-field copy (not a reinterpret_cast) so FilaMaterialParameterInfo's
    // layout is independent of filament::Material::ParameterInfo.
    std::vector<Material::ParameterInfo> tmp(count);
    uint32_t n = static_cast<uint32_t>(m->getParameters(tmp.data(), count));
    for (uint32_t i = 0; i < n; i++) {
        out[i].name      = tmp[i].name;
        out[i].isSampler = tmp[i].isSampler;
        out[i].isSubpass = tmp[i].isSubpass;
        out[i].type      = static_cast<uint8_t>(tmp[i].type);
        out[i].count     = tmp[i].count;
        out[i].precision = static_cast<uint8_t>(tmp[i].precision);
    }
    return n;
}

uint32_t FilaMaterial_getRequiredAttributes(const FilaMaterial* material) {
    return FILA_CONST_CAST(Material, material)->getRequiredAttributes().getValue();
}

void FilaMaterial_compile(FilaMaterial* material, FilaMaterialCompilerPriorityQueue priority, uint32_t variants, void* handler, FilaMaterialCompileCallback callback, void* userData) {
    FILA_CAST(Material, material)->compile(
        static_cast<Material::CompilerPriorityQueue>(priority),
        UserVariantFilterMask(variants),
        reinterpret_cast<filament::backend::CallbackHandler*>(handler),
        [callback, userData](Material* m) {
            if (callback) {
                callback(reinterpret_cast<FilaMaterial*>(m), userData);
            }
        }
    );
}

const char* FilaMaterial_getParameterTransformName(const FilaMaterial* material, const char* samplerName) {
    return FILA_CONST_CAST(Material, material)->getParameterTransformName(samplerName);
}

bool FilaMaterial_hasParameter(const FilaMaterial* material, const char* name) {
    return FILA_CONST_CAST(Material, material)->hasParameter(name);
}

void FilaMaterial_setDefaultParameter_bool(FilaMaterial* material, const char* name, bool value) {
    FILA_CAST(Material, material)->setDefaultParameter(name, value);
}

void FilaMaterial_setDefaultParameter_float(FilaMaterial* material, const char* name, float value) {
    FILA_CAST(Material, material)->setDefaultParameter(name, value);
}

void FilaMaterial_setDefaultParameter_int(FilaMaterial* material, const char* name, int32_t value) {
    FILA_CAST(Material, material)->setDefaultParameter(name, value);
}

void FilaMaterial_setDefaultParameter_float2(FilaMaterial* material, const char* name, float x, float y) {
    FILA_CAST(Material, material)->setDefaultParameter(name, math::float2{x, y});
}

void FilaMaterial_setDefaultParameter_float3(FilaMaterial* material, const char* name, float x, float y, float z) {
    FILA_CAST(Material, material)->setDefaultParameter(name, math::float3{x, y, z});
}

void FilaMaterial_setDefaultParameter_float4(FilaMaterial* material, const char* name, float x, float y, float z, float w) {
    FILA_CAST(Material, material)->setDefaultParameter(name, math::float4{x, y, z, w});
}

} // extern "C"
