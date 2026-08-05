#include <filament/MaterialInstance.h>
#include <filament/Texture.h>
#include <math/vec2.h>
#include <math/vec3.h>
#include <math/vec4.h>
#include <math/mat3.h>
#include <math/mat4.h>

#include "FilaCommon.h"
#include "SamplerUtils.h"
#include "../c/MaterialInstance.h"

using namespace filament;
using namespace filament::math;

extern "C" {

const char* FilaMaterialInstance_getName(const FilaMaterialInstance* instance) {
    return FILA_CONST_CAST(MaterialInstance, instance)->getName();
}

FilaMaterial* FilaMaterialInstance_getMaterial(const FilaMaterialInstance* instance) {
    return reinterpret_cast<FilaMaterial*>(const_cast<Material*>(FILA_CONST_CAST(MaterialInstance, instance)->getMaterial()));
}

FilaMaterialInstance* FilaMaterialInstance_duplicate(const FilaMaterialInstance* other, const char* name) {
    return reinterpret_cast<FilaMaterialInstance*>(MaterialInstance::duplicate(FILA_CONST_CAST(MaterialInstance, other), name));
}

// SetParameter
void FilaMaterialInstance_setParameterBool(FilaMaterialInstance* instance, const char* name, bool x) {
    FILA_CAST(MaterialInstance, instance)->setParameter(name, x);
}

void FilaMaterialInstance_setParameterBool2(FilaMaterialInstance* instance, const char* name, bool x, bool y) {
    FILA_CAST(MaterialInstance, instance)->setParameter(name, bool2{x, y});
}

void FilaMaterialInstance_setParameterBool3(FilaMaterialInstance* instance, const char* name, bool x, bool y, bool z) {
    FILA_CAST(MaterialInstance, instance)->setParameter(name, bool3{x, y, z});
}

void FilaMaterialInstance_setParameterBool4(FilaMaterialInstance* instance, const char* name, bool x, bool y, bool z, bool w) {
    FILA_CAST(MaterialInstance, instance)->setParameter(name, bool4{x, y, z, w});
}

void FilaMaterialInstance_setParameterInt(FilaMaterialInstance* instance, const char* name, int32_t x) {
    FILA_CAST(MaterialInstance, instance)->setParameter(name, x);
}

void FilaMaterialInstance_setParameterInt2(FilaMaterialInstance* instance, const char* name, int32_t x, int32_t y) {
    FILA_CAST(MaterialInstance, instance)->setParameter(name, int2{x, y});
}

void FilaMaterialInstance_setParameterInt3(FilaMaterialInstance* instance, const char* name, int32_t x, int32_t y, int32_t z) {
    FILA_CAST(MaterialInstance, instance)->setParameter(name, int3{x, y, z});
}

void FilaMaterialInstance_setParameterInt4(FilaMaterialInstance* instance, const char* name, int32_t x, int32_t y, int32_t z, int32_t w) {
    FILA_CAST(MaterialInstance, instance)->setParameter(name, int4{x, y, z, w});
}

void FilaMaterialInstance_setParameterFloat(FilaMaterialInstance* instance, const char* name, float x) {
    FILA_CAST(MaterialInstance, instance)->setParameter(name, x);
}

void FilaMaterialInstance_setParameterFloat2(FilaMaterialInstance* instance, const char* name, float x, float y) {
    FILA_CAST(MaterialInstance, instance)->setParameter(name, float2{x, y});
}

void FilaMaterialInstance_setParameterFloat3(FilaMaterialInstance* instance, const char* name, float x, float y, float z) {
    FILA_CAST(MaterialInstance, instance)->setParameter(name, float3{x, y, z});
}

void FilaMaterialInstance_setParameterFloat4(FilaMaterialInstance* instance, const char* name, float x, float y, float z, float w) {
    FILA_CAST(MaterialInstance, instance)->setParameter(name, float4{x, y, z, w});
}

void FilaMaterialInstance_setParameterMat3(FilaMaterialInstance* instance, const char* name, const float* v) {
    FILA_CAST(MaterialInstance, instance)->setParameter(name, *reinterpret_cast<const mat3f*>(v));
}

void FilaMaterialInstance_setParameterMat4(FilaMaterialInstance* instance, const char* name, const float* v) {
    FILA_CAST(MaterialInstance, instance)->setParameter(name, *reinterpret_cast<const mat4f*>(v));
}

void FilaMaterialInstance_setParameterTexture(FilaMaterialInstance* instance, const char* name, const FilaTexture* texture, uint64_t samplerParams) {
    FILA_CAST(MaterialInstance, instance)->setParameter(name, reinterpret_cast<const Texture*>(texture), SamplerUtils::from_c(samplerParams));
}

// SetParameter Arrays
void FilaMaterialInstance_setBooleanParameterArray(FilaMaterialInstance* instance, const char* name, uint32_t elementSize, const bool* v, size_t count) {
    switch (elementSize) {
        case 1: FILA_CAST(MaterialInstance, instance)->setParameter(name, v, count); break;
        case 2: FILA_CAST(MaterialInstance, instance)->setParameter(name, reinterpret_cast<const bool2*>(v), count); break;
        case 3: FILA_CAST(MaterialInstance, instance)->setParameter(name, reinterpret_cast<const bool3*>(v), count); break;
        case 4: FILA_CAST(MaterialInstance, instance)->setParameter(name, reinterpret_cast<const bool4*>(v), count); break;
    }
}

void FilaMaterialInstance_setIntParameterArray(FilaMaterialInstance* instance, const char* name, uint32_t elementSize, const int32_t* v, size_t count) {
    switch (elementSize) {
        case 1: FILA_CAST(MaterialInstance, instance)->setParameter(name, v, count); break;
        case 2: FILA_CAST(MaterialInstance, instance)->setParameter(name, reinterpret_cast<const int2*>(v), count); break;
        case 3: FILA_CAST(MaterialInstance, instance)->setParameter(name, reinterpret_cast<const int3*>(v), count); break;
        case 4: FILA_CAST(MaterialInstance, instance)->setParameter(name, reinterpret_cast<const int4*>(v), count); break;
    }
}

void FilaMaterialInstance_setFloatParameterArray(FilaMaterialInstance* instance, const char* name, uint32_t elementSize, const float* v, size_t count) {
    switch (elementSize) {
        case 1: FILA_CAST(MaterialInstance, instance)->setParameter(name, v, count); break;
        case 2: FILA_CAST(MaterialInstance, instance)->setParameter(name, reinterpret_cast<const float2*>(v), count); break;
        case 3: FILA_CAST(MaterialInstance, instance)->setParameter(name, reinterpret_cast<const float3*>(v), count); break;
        case 4: FILA_CAST(MaterialInstance, instance)->setParameter(name, reinterpret_cast<const float4*>(v), count); break;
        case 9: FILA_CAST(MaterialInstance, instance)->setParameter(name, reinterpret_cast<const mat3f*>(v), count); break;
        case 16: FILA_CAST(MaterialInstance, instance)->setParameter(name, reinterpret_cast<const mat4f*>(v), count); break;
    }
}

// State management
void FilaMaterialInstance_setScissor(FilaMaterialInstance* instance, int32_t left, int32_t bottom, uint32_t width, uint32_t height) {
    FILA_CAST(MaterialInstance, instance)->setScissor(left, bottom, width, height);
}

void FilaMaterialInstance_unsetScissor(FilaMaterialInstance* instance) {
    FILA_CAST(MaterialInstance, instance)->unsetScissor();
}

void FilaMaterialInstance_setPolygonOffset(FilaMaterialInstance* instance, float scale, float constant) {
    FILA_CAST(MaterialInstance, instance)->setPolygonOffset(scale, constant);
}

bool FilaMaterialInstance_getConstantBool(const FilaMaterialInstance* instance, const char* name) {
    return FILA_CONST_CAST(MaterialInstance, instance)->getConstant<bool>(name);
}

float FilaMaterialInstance_getConstantFloat(const FilaMaterialInstance* instance, const char* name) {
    return FILA_CONST_CAST(MaterialInstance, instance)->getConstant<float>(name);
}

int32_t FilaMaterialInstance_getConstantInt(const FilaMaterialInstance* instance, const char* name) {
    return FILA_CONST_CAST(MaterialInstance, instance)->getConstant<int32_t>(name);
}

void FilaMaterialInstance_setMaskThreshold(FilaMaterialInstance* instance, float threshold) {
    FILA_CAST(MaterialInstance, instance)->setMaskThreshold(threshold);
}

void FilaMaterialInstance_setSpecularAntiAliasingVariance(FilaMaterialInstance* instance, float variance) {
    FILA_CAST(MaterialInstance, instance)->setSpecularAntiAliasingVariance(variance);
}

void FilaMaterialInstance_setSpecularAntiAliasingThreshold(FilaMaterialInstance* instance, float threshold) {
    FILA_CAST(MaterialInstance, instance)->setSpecularAntiAliasingThreshold(threshold);
}

void FilaMaterialInstance_setDoubleSided(FilaMaterialInstance* instance, bool doubleSided) {
    FILA_CAST(MaterialInstance, instance)->setDoubleSided(doubleSided);
}

void FilaMaterialInstance_setCullingMode(FilaMaterialInstance* instance, FilaMaterialInstanceCullingMode cullingMode) {
    FILA_CAST(MaterialInstance, instance)->setCullingMode(static_cast<MaterialInstance::CullingMode>(cullingMode));
}

void FilaMaterialInstance_setCullingModeSeparate(FilaMaterialInstance* instance, FilaMaterialInstanceCullingMode colorPassCullingMode, FilaMaterialInstanceCullingMode shadowPassCullingMode) {
    FILA_CAST(MaterialInstance, instance)->setCullingMode(static_cast<MaterialInstance::CullingMode>(colorPassCullingMode), static_cast<MaterialInstance::CullingMode>(shadowPassCullingMode));
}

void FilaMaterialInstance_setColorWrite(FilaMaterialInstance* instance, bool enable) {
    FILA_CAST(MaterialInstance, instance)->setColorWrite(enable);
}

void FilaMaterialInstance_setDepthWrite(FilaMaterialInstance* instance, bool enable) {
    FILA_CAST(MaterialInstance, instance)->setDepthWrite(enable);
}

void FilaMaterialInstance_setStencilWrite(FilaMaterialInstance* instance, bool enable) {
    FILA_CAST(MaterialInstance, instance)->setStencilWrite(enable);
}

void FilaMaterialInstance_setDepthCulling(FilaMaterialInstance* instance, bool enable) {
    FILA_CAST(MaterialInstance, instance)->setDepthCulling(enable);
}

void FilaMaterialInstance_setDepthFunc(FilaMaterialInstance* instance, FilaMaterialInstanceDepthFunc func) {
    FILA_CAST(MaterialInstance, instance)->setDepthFunc(static_cast<MaterialInstance::DepthFunc>(func));
}

void FilaMaterialInstance_setStencilCompareFunction(FilaMaterialInstance* instance, FilaMaterialInstanceStencilCompareFunc func, FilaMaterialInstanceStencilFace face) {
    FILA_CAST(MaterialInstance, instance)->setStencilCompareFunction(static_cast<MaterialInstance::StencilCompareFunc>(func), static_cast<MaterialInstance::StencilFace>(face));
}

void FilaMaterialInstance_setStencilOpStencilFail(FilaMaterialInstance* instance, FilaMaterialInstanceStencilOperation op, FilaMaterialInstanceStencilFace face) {
    FILA_CAST(MaterialInstance, instance)->setStencilOpStencilFail(static_cast<MaterialInstance::StencilOperation>(op), static_cast<MaterialInstance::StencilFace>(face));
}

void FilaMaterialInstance_setStencilOpDepthFail(FilaMaterialInstance* instance, FilaMaterialInstanceStencilOperation op, FilaMaterialInstanceStencilFace face) {
    FILA_CAST(MaterialInstance, instance)->setStencilOpDepthFail(static_cast<MaterialInstance::StencilOperation>(op), static_cast<MaterialInstance::StencilFace>(face));
}

void FilaMaterialInstance_setStencilOpDepthStencilPass(FilaMaterialInstance* instance, FilaMaterialInstanceStencilOperation op, FilaMaterialInstanceStencilFace face) {
    FILA_CAST(MaterialInstance, instance)->setStencilOpDepthStencilPass(static_cast<MaterialInstance::StencilOperation>(op), static_cast<MaterialInstance::StencilFace>(face));
}

void FilaMaterialInstance_setStencilReferenceValue(FilaMaterialInstance* instance, uint32_t value, FilaMaterialInstanceStencilFace face) {
    FILA_CAST(MaterialInstance, instance)->setStencilReferenceValue(value, static_cast<MaterialInstance::StencilFace>(face));
}

void FilaMaterialInstance_setStencilReadMask(FilaMaterialInstance* instance, uint32_t readMask, FilaMaterialInstanceStencilFace face) {
    FILA_CAST(MaterialInstance, instance)->setStencilReadMask(readMask, static_cast<MaterialInstance::StencilFace>(face));
}

void FilaMaterialInstance_setStencilWriteMask(FilaMaterialInstance* instance, uint32_t writeMask, FilaMaterialInstanceStencilFace face) {
    FILA_CAST(MaterialInstance, instance)->setStencilWriteMask(writeMask, static_cast<MaterialInstance::StencilFace>(face));
}

void FilaMaterialInstance_setTransparencyMode(FilaMaterialInstance* instance, FilaMaterialInstanceTransparencyMode mode) {
    FILA_CAST(MaterialInstance, instance)->setTransparencyMode(static_cast<MaterialInstance::TransparencyMode>(mode));
}

// Getters
float FilaMaterialInstance_getMaskThreshold(const FilaMaterialInstance* instance) {
    return FILA_CONST_CAST(MaterialInstance, instance)->getMaskThreshold();
}

float FilaMaterialInstance_getSpecularAntiAliasingVariance(const FilaMaterialInstance* instance) {
    return FILA_CONST_CAST(MaterialInstance, instance)->getSpecularAntiAliasingVariance();
}

float FilaMaterialInstance_getSpecularAntiAliasingThreshold(const FilaMaterialInstance* instance) {
    return FILA_CONST_CAST(MaterialInstance, instance)->getSpecularAntiAliasingThreshold();
}

bool FilaMaterialInstance_isDoubleSided(const FilaMaterialInstance* instance) {
    return FILA_CONST_CAST(MaterialInstance, instance)->isDoubleSided();
}

FilaMaterialInstanceCullingMode FilaMaterialInstance_getCullingMode(const FilaMaterialInstance* instance) {
    return static_cast<FilaMaterialInstanceCullingMode>(FILA_CONST_CAST(MaterialInstance, instance)->getCullingMode());
}

FilaMaterialInstanceCullingMode FilaMaterialInstance_getShadowCullingMode(const FilaMaterialInstance* instance) {
    return static_cast<FilaMaterialInstanceCullingMode>(FILA_CONST_CAST(MaterialInstance, instance)->getShadowCullingMode());
}

bool FilaMaterialInstance_isColorWriteEnabled(const FilaMaterialInstance* instance) {
    return FILA_CONST_CAST(MaterialInstance, instance)->isColorWriteEnabled();
}

bool FilaMaterialInstance_isDepthWriteEnabled(const FilaMaterialInstance* instance) {
    return FILA_CONST_CAST(MaterialInstance, instance)->isDepthWriteEnabled();
}

bool FilaMaterialInstance_isStencilWriteEnabled(const FilaMaterialInstance* instance) {
    return FILA_CONST_CAST(MaterialInstance, instance)->isStencilWriteEnabled();
}

bool FilaMaterialInstance_isDepthCullingEnabled(const FilaMaterialInstance* instance) {
    return FILA_CONST_CAST(MaterialInstance, instance)->isDepthCullingEnabled();
}

FilaMaterialInstanceDepthFunc FilaMaterialInstance_getDepthFunc(const FilaMaterialInstance* instance) {
    return static_cast<FilaMaterialInstanceDepthFunc>(FILA_CONST_CAST(MaterialInstance, instance)->getDepthFunc());
}

FilaMaterialInstanceTransparencyMode FilaMaterialInstance_getTransparencyMode(const FilaMaterialInstance* instance) {
    return static_cast<FilaMaterialInstanceTransparencyMode>(FILA_CONST_CAST(MaterialInstance, instance)->getTransparencyMode());
}

} // extern "C"
