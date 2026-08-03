#ifndef FILAMENT_C_TEXTURE_SAMPLER_H
#define FILAMENT_C_TEXTURE_SAMPLER_H

#include "FilaTypes.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef enum FilaTextureSamplerMinFilter {
    FILA_TEXTURE_SAMPLER_MIN_FILTER_NEAREST = 0,
    FILA_TEXTURE_SAMPLER_MIN_FILTER_LINEAR = 1,
    FILA_TEXTURE_SAMPLER_MIN_FILTER_NEAREST_MIPMAP_NEAREST = 2,
    FILA_TEXTURE_SAMPLER_MIN_FILTER_LINEAR_MIPMAP_NEAREST = 3,
    FILA_TEXTURE_SAMPLER_MIN_FILTER_NEAREST_MIPMAP_LINEAR = 4,
    FILA_TEXTURE_SAMPLER_MIN_FILTER_LINEAR_MIPMAP_LINEAR = 5,
} FilaTextureSamplerMinFilter;

typedef enum FilaTextureSamplerMagFilter {
    FILA_TEXTURE_SAMPLER_MAG_FILTER_NEAREST = 0,
    FILA_TEXTURE_SAMPLER_MAG_FILTER_LINEAR = 1,
} FilaTextureSamplerMagFilter;

typedef enum FilaTextureSamplerWrapMode {
    FILA_TEXTURE_SAMPLER_WRAP_MODE_CLAMP_TO_EDGE = 0,
    FILA_TEXTURE_SAMPLER_WRAP_MODE_REPEAT = 1,
    FILA_TEXTURE_SAMPLER_WRAP_MODE_MIRRORED_REPEAT = 2,
} FilaTextureSamplerWrapMode;

typedef enum FilaTextureSamplerCompareMode {
    FILA_TEXTURE_SAMPLER_COMPARE_MODE_NONE = 0,
    FILA_TEXTURE_SAMPLER_COMPARE_MODE_COMPARE_TO_TEXTURE = 1,
} FilaTextureSamplerCompareMode;

typedef enum FilaTextureSamplerCompareFunc {
    FILA_TEXTURE_SAMPLER_COMPARE_FUNC_LE = 0,
    FILA_TEXTURE_SAMPLER_COMPARE_FUNC_GE = 1,
    FILA_TEXTURE_SAMPLER_COMPARE_FUNC_L = 2,
    FILA_TEXTURE_SAMPLER_COMPARE_FUNC_G = 3,
    FILA_TEXTURE_SAMPLER_COMPARE_FUNC_E = 4,
    FILA_TEXTURE_SAMPLER_COMPARE_FUNC_NE = 5,
    FILA_TEXTURE_SAMPLER_COMPARE_FUNC_A = 6,
    FILA_TEXTURE_SAMPLER_COMPARE_FUNC_N = 7,
} FilaTextureSamplerCompareFunc;

// Sampler creation
FilaTextureSampler FilaTextureSampler_create(FilaTextureSamplerMinFilter min, FilaTextureSamplerMagFilter mag, FilaTextureSamplerWrapMode s, FilaTextureSamplerWrapMode t, FilaTextureSamplerWrapMode r);
FilaTextureSampler FilaTextureSampler_createCompare(FilaTextureSamplerCompareMode mode, FilaTextureSamplerCompareFunc func);

// Getters/Setters
FilaTextureSamplerMinFilter FilaTextureSampler_getMinFilter(FilaTextureSampler sampler);
FilaTextureSampler FilaTextureSampler_setMinFilter(FilaTextureSampler sampler, FilaTextureSamplerMinFilter filter);
FilaTextureSamplerMagFilter FilaTextureSampler_getMagFilter(FilaTextureSampler sampler);
FilaTextureSampler FilaTextureSampler_setMagFilter(FilaTextureSampler sampler, FilaTextureSamplerMagFilter filter);
FilaTextureSamplerWrapMode FilaTextureSampler_getWrapModeS(FilaTextureSampler sampler);
FilaTextureSampler FilaTextureSampler_setWrapModeS(FilaTextureSampler sampler, FilaTextureSamplerWrapMode mode);
FilaTextureSamplerWrapMode FilaTextureSampler_getWrapModeT(FilaTextureSampler sampler);
FilaTextureSampler FilaTextureSampler_setWrapModeT(FilaTextureSampler sampler, FilaTextureSamplerWrapMode mode);
FilaTextureSamplerWrapMode FilaTextureSampler_getWrapModeR(FilaTextureSampler sampler);
FilaTextureSampler FilaTextureSampler_setWrapModeR(FilaTextureSampler sampler, FilaTextureSamplerWrapMode mode);
FilaTextureSamplerCompareMode FilaTextureSampler_getCompareMode(FilaTextureSampler sampler);
FilaTextureSampler FilaTextureSampler_setCompareMode(FilaTextureSampler sampler, FilaTextureSamplerCompareMode mode);
FilaTextureSamplerCompareFunc FilaTextureSampler_getCompareFunction(FilaTextureSampler sampler);
FilaTextureSampler FilaTextureSampler_setCompareFunction(FilaTextureSampler sampler, FilaTextureSamplerCompareFunc func);
float FilaTextureSampler_getAnisotropy(FilaTextureSampler sampler);
FilaTextureSampler FilaTextureSampler_setAnisotropy(FilaTextureSampler sampler, float anisotropy);

#ifdef __cplusplus
}
#endif

#endif // FILAMENT_C_TEXTURE_SAMPLER_H
