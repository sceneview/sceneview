#ifndef FILAMENT_UTILS_C_TEXTURE_LOADER_H
#define FILAMENT_UTILS_C_TEXTURE_LOADER_H

#include "FilamentUtilsTypes.h"

#ifdef __cplusplus
extern "C" {
#endif

// Decodes image bytes (PNG, JPG, etc) into a Filament Texture.
// srgb: if true, uses sRGB internal format.
// Returns a FilaTexture* handle, or NULL on failure.
FilaTexture* FilaTextureLoader_loadTexture(FilaEngine* engine, const void* buffer, size_t size, bool srgb);

#ifdef __cplusplus
}
#endif

#endif // FILAMENT_UTILS_C_TEXTURE_LOADER_H
