#ifndef FILAMENT_C_MATH_UTILS_H
#define FILAMENT_C_MATH_UTILS_H

#include "FilaTypes.h"

#ifdef __cplusplus
extern "C" {
#endif

/**
 * Packs the tangent frame represented by the specified tangent, bitangent, and normal into a
 * quaternion.
 */
void FilaMathUtils_packTangentFrame(
    float tangentX, float tangentY, float tangentZ,
    float bitangentX, float bitangentY, float bitangentZ,
    float normalX, float normalY, float normalZ,
    float* outQuaternion);

#ifdef __cplusplus
}
#endif

#endif // FILAMENT_C_MATH_UTILS_H
