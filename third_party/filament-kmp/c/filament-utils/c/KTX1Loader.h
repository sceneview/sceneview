#ifndef FILAMENT_UTILS_C_KTX1_LOADER_H
#define FILAMENT_UTILS_C_KTX1_LOADER_H

#include "FilamentUtilsTypes.h"

#ifdef __cplusplus
extern "C" {
#endif

// Decodes KTX1 bytes into a Filament Texture.
// Returns a FilaTexture* handle, or NULL on failure.
FilaTexture* FilaKTX1Loader_createTexture(FilaEngine* engine, const void* buffer, size_t size, bool srgb);

// Creates a FilaIndirectLight from a cubemap texture and spherical harmonics.
// sh must be an array of 9 FilaFloat3 values.
FilaIndirectLight* FilaKTX1Loader_createIndirectLight(FilaEngine* engine, FilaTexture* texture, const FilaFloat3* sh);

// Creates a FilaSkybox from a cubemap texture.
FilaSkybox* FilaKTX1Loader_createSkybox(FilaEngine* engine, FilaTexture* texture);

// Extracts spherical harmonics from KTX1 bytes.
// outSh must be an array of 9 FilaFloat3 values.
bool FilaKTX1Loader_getSphericalHarmonics(const void* buffer, size_t size, FilaFloat3* outSh);

#ifdef __cplusplus
}
#endif

#endif // FILAMENT_UTILS_C_KTX1_LOADER_H
