#ifndef FILAMENT_C_INDIRECT_LIGHT_H
#define FILAMENT_C_INDIRECT_LIGHT_H

#include "FilaTypes.h"

#ifdef __cplusplus
extern "C" {
#endif

// Builder
typedef struct FilaIndirectLightBuilder FilaIndirectLightBuilder;

FilaIndirectLightBuilder* FilaIndirectLightBuilder_create(void);
void FilaIndirectLightBuilder_destroy(FilaIndirectLightBuilder* builder);
FilaIndirectLight* FilaIndirectLightBuilder_build(FilaIndirectLightBuilder* builder, FilaEngine* engine);

void FilaIndirectLightBuilder_reflections(FilaIndirectLightBuilder* builder, const FilaTexture* cubemap);
void FilaIndirectLightBuilder_irradiance(FilaIndirectLightBuilder* builder, uint8_t bands, const float* sh);
void FilaIndirectLightBuilder_radiance(FilaIndirectLightBuilder* builder, uint8_t bands, const float* sh);
void FilaIndirectLightBuilder_irradianceAsTexture(FilaIndirectLightBuilder* builder, const FilaTexture* cubemap);
void FilaIndirectLightBuilder_intensity(FilaIndirectLightBuilder* builder, float envIntensity);
void FilaIndirectLightBuilder_rotation(FilaIndirectLightBuilder* builder, const float* rotation);

// IndirectLight
void FilaIndirectLight_setIntensity(FilaIndirectLight* indirectLight, float intensity);
float FilaIndirectLight_getIntensity(const FilaIndirectLight* indirectLight);
void FilaIndirectLight_setRotation(FilaIndirectLight* indirectLight, const float* rotation);
void FilaIndirectLight_getRotation(const FilaIndirectLight* indirectLight, float* outRotation);
void FilaIndirectLight_getDirectionEstimate(const FilaIndirectLight* indirectLight, float* outDirection);
void FilaIndirectLight_getColorEstimate(const FilaIndirectLight* indirectLight, float x, float y, float z, float* outColor);
FilaTexture* FilaIndirectLight_getReflectionsTexture(const FilaIndirectLight* indirectLight);
FilaTexture* FilaIndirectLight_getIrradianceTexture(const FilaIndirectLight* indirectLight);

// Static methods
void FilaIndirectLight_getDirectionEstimateStatic(const float* sh, float* outDirection);
void FilaIndirectLight_getColorEstimateStatic(const float* sh, float x, float y, float z, float* outColor);

#ifdef __cplusplus
}
#endif

#endif // FILAMENT_C_INDIRECT_LIGHT_H
