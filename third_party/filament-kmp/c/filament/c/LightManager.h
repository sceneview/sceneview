#ifndef FILAMENT_C_LIGHT_MANAGER_H
#define FILAMENT_C_LIGHT_MANAGER_H

#include "FilaTypes.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef enum FilaLightManagerType {
    FILA_LIGHT_MANAGER_TYPE_SUN = 0,
    FILA_LIGHT_MANAGER_TYPE_DIRECTIONAL = 1,
    FILA_LIGHT_MANAGER_TYPE_POINT = 2,
    FILA_LIGHT_MANAGER_TYPE_FOCUSED_SPOT = 3,
    FILA_LIGHT_MANAGER_TYPE_SPOT = 4,
} FilaLightManagerType;

typedef struct FilaLightManagerVsmShadowOptions {
    bool elvsm;
    float blurWidth;
} FilaLightManagerVsmShadowOptions;

typedef struct FilaLightManagerShadowOptions {
    uint32_t mapSize;
    uint32_t shadowCascades;
    float cascadeSplitPositions[3];
    float constantBias;
    float normalBias;
    float shadowFar;
    float shadowNearHint;
    float shadowFarHint;
    bool stable;
    bool lispsm;
    float polygonOffsetConstant;
    float polygonOffsetSlope;
    bool screenSpaceContactShadows;
    uint8_t stepCount;
    float maxShadowDistance;
    FilaLightManagerVsmShadowOptions vsm;
    float shadowBulbRadius;
    float transform[4];
} FilaLightManagerShadowOptions;

// Instance
typedef uint32_t FilaLightManagerInstance;

// Builder
typedef struct FilaLightManagerBuilder FilaLightManagerBuilder;

FilaLightManagerBuilder* FilaLightManagerBuilder_create(FilaLightManagerType type);
void FilaLightManagerBuilder_destroy(FilaLightManagerBuilder* builder);
bool FilaLightManagerBuilder_build(FilaLightManagerBuilder* builder, FilaEngine* engine, FilaEntity entity);

void FilaLightManagerBuilder_castShadows(FilaLightManagerBuilder* builder, bool enable);
void FilaLightManagerBuilder_shadowOptions(FilaLightManagerBuilder* builder, const FilaLightManagerShadowOptions* options);
void FilaLightManagerBuilder_castLight(FilaLightManagerBuilder* builder, bool enable);
void FilaLightManagerBuilder_position(FilaLightManagerBuilder* builder, float x, float y, float z);
void FilaLightManagerBuilder_direction(FilaLightManagerBuilder* builder, float x, float y, float z);
void FilaLightManagerBuilder_color(FilaLightManagerBuilder* builder, float linearR, float linearG, float linearB);
void FilaLightManagerBuilder_intensity(FilaLightManagerBuilder* builder, float intensity);
void FilaLightManagerBuilder_intensityEfficiency(FilaLightManagerBuilder* builder, float watts, float efficiency);
void FilaLightManagerBuilder_intensityCandela(FilaLightManagerBuilder* builder, float intensity);
void FilaLightManagerBuilder_falloff(FilaLightManagerBuilder* builder, float radius);
void FilaLightManagerBuilder_spotLightCone(FilaLightManagerBuilder* builder, float inner, float outer);
void FilaLightManagerBuilder_sunAngularRadius(FilaLightManagerBuilder* builder, float angularRadius);
void FilaLightManagerBuilder_sunHaloSize(FilaLightManagerBuilder* builder, float haloSize);
void FilaLightManagerBuilder_sunHaloFalloff(FilaLightManagerBuilder* builder, float haloFalloff);
void FilaLightManagerBuilder_lightChannel(FilaLightManagerBuilder* builder, unsigned int channel, bool enable);

// Utils
void FilaLightManager_computeUniformSplits(float* splitPositions, uint8_t cascades);
void FilaLightManager_computeLogSplits(float* splitPositions, uint8_t cascades, float nearPlane, float farPlane);
void FilaLightManager_computePracticalSplits(float* splitPositions, uint8_t cascades, float nearPlane, float farPlane, float lambda);

// LightManager
size_t FilaLightManager_getComponentCount(const FilaLightManager* lm);
bool FilaLightManager_hasComponent(const FilaLightManager* lm, FilaEntity entity);
FilaLightManagerInstance FilaLightManager_getInstance(const FilaLightManager* lm, FilaEntity entity);
void FilaLightManager_destroy(FilaLightManager* lm, FilaEntity entity);

FilaLightManagerType FilaLightManager_getType(const FilaLightManager* lm, FilaLightManagerInstance instance);

void FilaLightManager_setPosition(FilaLightManager* lm, FilaLightManagerInstance instance, float x, float y, float z);
void FilaLightManager_getPosition(const FilaLightManager* lm, FilaLightManagerInstance instance, float out[3]);
void FilaLightManager_setDirection(FilaLightManager* lm, FilaLightManagerInstance instance, float x, float y, float z);
void FilaLightManager_getDirection(const FilaLightManager* lm, FilaLightManagerInstance instance, float out[3]);
void FilaLightManager_setColor(FilaLightManager* lm, FilaLightManagerInstance instance, float linearR, float linearG, float linearB);
void FilaLightManager_getColor(const FilaLightManager* lm, FilaLightManagerInstance instance, float out[3]);

void FilaLightManager_setIntensity(FilaLightManager* lm, FilaLightManagerInstance instance, float intensity);
void FilaLightManager_setIntensityEfficiency(FilaLightManager* lm, FilaLightManagerInstance instance, float watts, float efficiency);
void FilaLightManager_setIntensityCandela(FilaLightManager* lm, FilaLightManagerInstance instance, float intensity);
float FilaLightManager_getIntensity(const FilaLightManager* lm, FilaLightManagerInstance instance);

void FilaLightManager_setFalloff(FilaLightManager* lm, FilaLightManagerInstance instance, float radius);
float FilaLightManager_getFalloff(const FilaLightManager* lm, FilaLightManagerInstance instance);

void FilaLightManager_setSpotLightCone(FilaLightManager* lm, FilaLightManagerInstance instance, float inner, float outer);
float FilaLightManager_getSpotLightInnerCone(const FilaLightManager* lm, FilaLightManagerInstance instance);
float FilaLightManager_getSpotLightOuterCone(const FilaLightManager* lm, FilaLightManagerInstance instance);

void FilaLightManager_setSunAngularRadius(FilaLightManager* lm, FilaLightManagerInstance instance, float angularRadius);
float FilaLightManager_getSunAngularRadius(const FilaLightManager* lm, FilaLightManagerInstance instance);
void FilaLightManager_setSunHaloSize(FilaLightManager* lm, FilaLightManagerInstance instance, float haloSize);
float FilaLightManager_getSunHaloSize(const FilaLightManager* lm, FilaLightManagerInstance instance);
void FilaLightManager_setSunHaloFalloff(FilaLightManager* lm, FilaLightManagerInstance instance, float haloFalloff);
float FilaLightManager_getSunHaloFalloff(const FilaLightManager* lm, FilaLightManagerInstance instance);

void FilaLightManager_setShadowCaster(FilaLightManager* lm, FilaLightManagerInstance instance, bool shadowCaster);
bool FilaLightManager_isShadowCaster(const FilaLightManager* lm, FilaLightManagerInstance instance);

void FilaLightManager_setLightChannel(FilaLightManager* lm, FilaLightManagerInstance instance, unsigned int channel, bool enable);
bool FilaLightManager_getLightChannel(const FilaLightManager* lm, FilaLightManagerInstance instance, unsigned int channel);

#ifdef __cplusplus
}
#endif

#endif // FILAMENT_C_LIGHT_MANAGER_H
