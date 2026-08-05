#ifndef FILAMENT_UTILS_C_HDR_LOADER_H
#define FILAMENT_UTILS_C_HDR_LOADER_H

#include "FilamentUtilsTypes.h"

#ifdef __cplusplus
extern "C" {
#endif

// Decodes HDR bytes into a Filament Texture.
// Returns a FilaTexture* handle, or NULL on failure.
FilaTexture* FilaHDRLoader_createTexture(FilaEngine* engine, const void* buffer, size_t size, int32_t internalFormat);

#ifdef __cplusplus
}
#endif

#endif // FILAMENT_UTILS_C_HDR_LOADER_H
