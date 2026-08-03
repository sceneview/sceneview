#ifndef FILAMENT_C_MATERIAL_INSTANCE_H
#define FILAMENT_C_MATERIAL_INSTANCE_H

#include "FilaTypes.h"

#ifdef __cplusplus
extern "C" {
#endif

// Matches filament::MaterialInstance::CullingMode
typedef enum FilaMaterialInstanceCullingMode {
    FILA_MATERIAL_INSTANCE_CULLING_NONE = 0,
    FILA_MATERIAL_INSTANCE_CULLING_FRONT = 1,
    FILA_MATERIAL_INSTANCE_CULLING_BACK = 2,
    FILA_MATERIAL_INSTANCE_CULLING_FRONT_AND_BACK = 3
} FilaMaterialInstanceCullingMode;

// Matches filament::MaterialInstance::DepthFunc
typedef enum FilaMaterialInstanceDepthFunc {
    FILA_MATERIAL_INSTANCE_DEPTH_FUNC_OFF = 0,
    FILA_MATERIAL_INSTANCE_DEPTH_FUNC_LESS = 1,
    FILA_MATERIAL_INSTANCE_DEPTH_FUNC_LESS_EQUAL = 2,
    FILA_MATERIAL_INSTANCE_DEPTH_FUNC_GREATER = 3,
    FILA_MATERIAL_INSTANCE_DEPTH_FUNC_GREATER_EQUAL = 4,
    FILA_MATERIAL_INSTANCE_DEPTH_FUNC_EQUAL = 5,
    FILA_MATERIAL_INSTANCE_DEPTH_FUNC_NOT_EQUAL = 6,
    FILA_MATERIAL_INSTANCE_DEPTH_FUNC_ALWAYS = 7
} FilaMaterialInstanceDepthFunc;

// Matches filament::MaterialInstance::StencilCompareFunc
typedef enum FilaMaterialInstanceStencilCompareFunc {
    FILA_MATERIAL_INSTANCE_STENCIL_COMPARE_FUNC_LESS = 0,
    FILA_MATERIAL_INSTANCE_STENCIL_COMPARE_FUNC_LESS_EQUAL = 1,
    FILA_MATERIAL_INSTANCE_STENCIL_COMPARE_FUNC_GREATER = 2,
    FILA_MATERIAL_INSTANCE_STENCIL_COMPARE_FUNC_GREATER_EQUAL = 3,
    FILA_MATERIAL_INSTANCE_STENCIL_COMPARE_FUNC_EQUAL = 4,
    FILA_MATERIAL_INSTANCE_STENCIL_COMPARE_FUNC_NOT_EQUAL = 5,
    FILA_MATERIAL_INSTANCE_STENCIL_COMPARE_FUNC_ALWAYS = 6,
    FILA_MATERIAL_INSTANCE_STENCIL_COMPARE_FUNC_NEVER = 7
} FilaMaterialInstanceStencilCompareFunc;

// Matches filament::MaterialInstance::StencilFace
typedef enum FilaMaterialInstanceStencilFace {
    FILA_MATERIAL_INSTANCE_STENCIL_FACE_FRONT = 1,
    FILA_MATERIAL_INSTANCE_STENCIL_FACE_BACK = 2,
    FILA_MATERIAL_INSTANCE_STENCIL_FACE_FRONT_AND_BACK = 3
} FilaMaterialInstanceStencilFace;

// Matches filament::MaterialInstance::StencilOperation
typedef enum FilaMaterialInstanceStencilOperation {
    FILA_MATERIAL_INSTANCE_STENCIL_OPERATION_KEEP = 0,
    FILA_MATERIAL_INSTANCE_STENCIL_OPERATION_ZERO = 1,
    FILA_MATERIAL_INSTANCE_STENCIL_OPERATION_REPLACE = 2,
    FILA_MATERIAL_INSTANCE_STENCIL_OPERATION_INCREMENT = 3,
    FILA_MATERIAL_INSTANCE_STENCIL_OPERATION_INCREMENT_WRAP = 4,
    FILA_MATERIAL_INSTANCE_STENCIL_OPERATION_DECREMENT = 5,
    FILA_MATERIAL_INSTANCE_STENCIL_OPERATION_DECREMENT_WRAP = 6,
    FILA_MATERIAL_INSTANCE_STENCIL_OPERATION_INVERT = 7
} FilaMaterialInstanceStencilOperation;

// Matches filament::MaterialInstance::TransparencyMode
typedef enum FilaMaterialInstanceTransparencyMode {
    FILA_MATERIAL_INSTANCE_TRANSPARENCY_MODE_DEFAULT = 0,
    FILA_MATERIAL_INSTANCE_TRANSPARENCY_MODE_TWO_PASSES_ONE_SIDE = 1,
    FILA_MATERIAL_INSTANCE_TRANSPARENCY_MODE_TWO_PASSES_TWO_SIDES = 2
} FilaMaterialInstanceTransparencyMode;

// MaterialInstance methods
const char* FilaMaterialInstance_getName(const FilaMaterialInstance* instance);
FilaMaterial* FilaMaterialInstance_getMaterial(const FilaMaterialInstance* instance);
FilaMaterialInstance* FilaMaterialInstance_duplicate(const FilaMaterialInstance* other, const char* name);

// SetParameter
void FilaMaterialInstance_setParameterBool(FilaMaterialInstance* instance, const char* name, bool x);
void FilaMaterialInstance_setParameterBool2(FilaMaterialInstance* instance, const char* name, bool x, bool y);
void FilaMaterialInstance_setParameterBool3(FilaMaterialInstance* instance, const char* name, bool x, bool y, bool z);
void FilaMaterialInstance_setParameterBool4(FilaMaterialInstance* instance, const char* name, bool x, bool y, bool z, bool w);

void FilaMaterialInstance_setParameterInt(FilaMaterialInstance* instance, const char* name, int32_t x);
void FilaMaterialInstance_setParameterInt2(FilaMaterialInstance* instance, const char* name, int32_t x, int32_t y);
void FilaMaterialInstance_setParameterInt3(FilaMaterialInstance* instance, const char* name, int32_t x, int32_t y, int32_t z);
void FilaMaterialInstance_setParameterInt4(FilaMaterialInstance* instance, const char* name, int32_t x, int32_t y, int32_t z, int32_t w);

void FilaMaterialInstance_setParameterFloat(FilaMaterialInstance* instance, const char* name, float x);
void FilaMaterialInstance_setParameterFloat2(FilaMaterialInstance* instance, const char* name, float x, float y);
void FilaMaterialInstance_setParameterFloat3(FilaMaterialInstance* instance, const char* name, float x, float y, float z);
void FilaMaterialInstance_setParameterFloat4(FilaMaterialInstance* instance, const char* name, float x, float y, float z, float w);

void FilaMaterialInstance_setParameterMat3(FilaMaterialInstance* instance, const char* name, const float* v);
void FilaMaterialInstance_setParameterMat4(FilaMaterialInstance* instance, const char* name, const float* v);

// SetParameter Texture
void FilaMaterialInstance_setParameterTexture(FilaMaterialInstance* instance, const char* name, const FilaTexture* texture, uint64_t samplerParams);

// SetParameter Arrays
void FilaMaterialInstance_setBooleanParameterArray(FilaMaterialInstance* instance, const char* name, uint32_t elementSize, const bool* v, size_t count);
void FilaMaterialInstance_setIntParameterArray(FilaMaterialInstance* instance, const char* name, uint32_t elementSize, const int32_t* v, size_t count);
void FilaMaterialInstance_setFloatParameterArray(FilaMaterialInstance* instance, const char* name, uint32_t elementSize, const float* v, size_t count);

// State management
void FilaMaterialInstance_setScissor(FilaMaterialInstance* instance, int32_t left, int32_t bottom, uint32_t width, uint32_t height);
void FilaMaterialInstance_unsetScissor(FilaMaterialInstance* instance);
void FilaMaterialInstance_setPolygonOffset(FilaMaterialInstance* instance, float scale, float constant);

bool FilaMaterialInstance_getConstantBool(const FilaMaterialInstance* instance, const char* name);
float FilaMaterialInstance_getConstantFloat(const FilaMaterialInstance* instance, const char* name);
int32_t FilaMaterialInstance_getConstantInt(const FilaMaterialInstance* instance, const char* name);
void FilaMaterialInstance_setMaskThreshold(FilaMaterialInstance* instance, float threshold);
void FilaMaterialInstance_setSpecularAntiAliasingVariance(FilaMaterialInstance* instance, float variance);
void FilaMaterialInstance_setSpecularAntiAliasingThreshold(FilaMaterialInstance* instance, float threshold);
void FilaMaterialInstance_setDoubleSided(FilaMaterialInstance* instance, bool doubleSided);
void FilaMaterialInstance_setCullingMode(FilaMaterialInstance* instance, FilaMaterialInstanceCullingMode cullingMode);
void FilaMaterialInstance_setCullingModeSeparate(FilaMaterialInstance* instance, FilaMaterialInstanceCullingMode colorPassCullingMode, FilaMaterialInstanceCullingMode shadowPassCullingMode);
void FilaMaterialInstance_setColorWrite(FilaMaterialInstance* instance, bool enable);
void FilaMaterialInstance_setDepthWrite(FilaMaterialInstance* instance, bool enable);
void FilaMaterialInstance_setStencilWrite(FilaMaterialInstance* instance, bool enable);
void FilaMaterialInstance_setDepthCulling(FilaMaterialInstance* instance, bool enable);
void FilaMaterialInstance_setDepthFunc(FilaMaterialInstance* instance, FilaMaterialInstanceDepthFunc func);
void FilaMaterialInstance_setStencilCompareFunction(FilaMaterialInstance* instance, FilaMaterialInstanceStencilCompareFunc func, FilaMaterialInstanceStencilFace face);
void FilaMaterialInstance_setStencilOpStencilFail(FilaMaterialInstance* instance, FilaMaterialInstanceStencilOperation op, FilaMaterialInstanceStencilFace face);
void FilaMaterialInstance_setStencilOpDepthFail(FilaMaterialInstance* instance, FilaMaterialInstanceStencilOperation op, FilaMaterialInstanceStencilFace face);
void FilaMaterialInstance_setStencilOpDepthStencilPass(FilaMaterialInstance* instance, FilaMaterialInstanceStencilOperation op, FilaMaterialInstanceStencilFace face);
void FilaMaterialInstance_setStencilReferenceValue(FilaMaterialInstance* instance, uint32_t value, FilaMaterialInstanceStencilFace face);
void FilaMaterialInstance_setStencilReadMask(FilaMaterialInstance* instance, uint32_t readMask, FilaMaterialInstanceStencilFace face);
void FilaMaterialInstance_setStencilWriteMask(FilaMaterialInstance* instance, uint32_t writeMask, FilaMaterialInstanceStencilFace face);
void FilaMaterialInstance_setTransparencyMode(FilaMaterialInstance* instance, FilaMaterialInstanceTransparencyMode mode);

// Getters
float FilaMaterialInstance_getMaskThreshold(const FilaMaterialInstance* instance);
float FilaMaterialInstance_getSpecularAntiAliasingVariance(const FilaMaterialInstance* instance);
float FilaMaterialInstance_getSpecularAntiAliasingThreshold(const FilaMaterialInstance* instance);
bool FilaMaterialInstance_isDoubleSided(const FilaMaterialInstance* instance);
FilaMaterialInstanceCullingMode FilaMaterialInstance_getCullingMode(const FilaMaterialInstance* instance);
FilaMaterialInstanceCullingMode FilaMaterialInstance_getShadowCullingMode(const FilaMaterialInstance* instance);
bool FilaMaterialInstance_isColorWriteEnabled(const FilaMaterialInstance* instance);
bool FilaMaterialInstance_isDepthWriteEnabled(const FilaMaterialInstance* instance);
bool FilaMaterialInstance_isStencilWriteEnabled(const FilaMaterialInstance* instance);
bool FilaMaterialInstance_isDepthCullingEnabled(const FilaMaterialInstance* instance);
FilaMaterialInstanceDepthFunc FilaMaterialInstance_getDepthFunc(const FilaMaterialInstance* instance);
FilaMaterialInstanceTransparencyMode FilaMaterialInstance_getTransparencyMode(const FilaMaterialInstance* instance);

#ifdef __cplusplus
}
#endif

#endif // FILAMENT_C_MATERIAL_INSTANCE_H
