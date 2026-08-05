#ifndef FILAMENT_C_SKYBOX_H
#define FILAMENT_C_SKYBOX_H

#include "FilaTypes.h"

#ifdef __cplusplus
extern "C" {
#endif

// Builder
typedef struct FilaSkyboxBuilder FilaSkyboxBuilder;

FilaSkyboxBuilder* FilaSkyboxBuilder_create(void);
void FilaSkyboxBuilder_destroy(FilaSkyboxBuilder* builder);
FilaSkybox* FilaSkyboxBuilder_build(FilaSkyboxBuilder* builder, FilaEngine* engine);

void FilaSkyboxBuilder_environment(FilaSkyboxBuilder* builder, const FilaTexture* texture);
void FilaSkyboxBuilder_showSun(FilaSkyboxBuilder* builder, bool show);
void FilaSkyboxBuilder_intensity(FilaSkyboxBuilder* builder, float intensity);
void FilaSkyboxBuilder_color(FilaSkyboxBuilder* builder, float r, float g, float b, float a);
void FilaSkyboxBuilder_priority(FilaSkyboxBuilder* builder, uint8_t priority);

// Skybox
void FilaSkybox_setLayerMask(FilaSkybox* skybox, uint8_t select, uint8_t value);
uint8_t FilaSkybox_getLayerMask(const FilaSkybox* skybox);
float FilaSkybox_getIntensity(const FilaSkybox* skybox);
void FilaSkybox_setColor(FilaSkybox* skybox, float r, float g, float b, float a);
FilaTexture* FilaSkybox_getTexture(const FilaSkybox* skybox);

#ifdef __cplusplus
}
#endif

#endif // FILAMENT_C_SKYBOX_H
