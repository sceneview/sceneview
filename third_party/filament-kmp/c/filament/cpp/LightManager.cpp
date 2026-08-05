#include <filament/LightManager.h>
#include <filament/Engine.h>
#include <utils/Entity.h>
#include <math/vec3.h>
#include <algorithm>

#include "FilaCommon.h"
#include "../c/LightManager.h"

using namespace filament;
using namespace utils;

extern "C" {

FilaLightManagerBuilder* FilaLightManagerBuilder_create(FilaLightManagerType type) {
    return reinterpret_cast<FilaLightManagerBuilder*>(new LightManager::Builder(static_cast<LightManager::Type>(type)));
}

void FilaLightManagerBuilder_destroy(FilaLightManagerBuilder* builder) {
    delete reinterpret_cast<LightManager::Builder*>(builder);
}

bool FilaLightManagerBuilder_build(FilaLightManagerBuilder* builder, FilaEngine* engine, FilaEntity entity) {
    return FILA_CAST(LightManager::Builder, builder)->build(*FILA_CAST(Engine, engine), Entity::import(entity)) 
           == LightManager::Builder::Success;
}

void FilaLightManagerBuilder_castShadows(FilaLightManagerBuilder* builder, bool enable) {
    FILA_CAST(LightManager::Builder, builder)->castShadows(enable);
}

void FilaLightManagerBuilder_shadowOptions(FilaLightManagerBuilder* builder, const FilaLightManagerShadowOptions* options) {
    LightManager::ShadowOptions shadowOptions;
    shadowOptions.mapSize = options->mapSize;
    shadowOptions.shadowCascades = (uint8_t)options->shadowCascades;
    std::copy_n(options->cascadeSplitPositions, 3, shadowOptions.cascadeSplitPositions);
    shadowOptions.constantBias = options->constantBias;
    shadowOptions.normalBias = options->normalBias;
    shadowOptions.shadowFar = options->shadowFar;
    shadowOptions.shadowNearHint = options->shadowNearHint;
    shadowOptions.shadowFarHint = options->shadowFarHint;
    shadowOptions.stable = options->stable;
    shadowOptions.lispsm = options->lispsm;
    shadowOptions.polygonOffsetConstant = options->polygonOffsetConstant;
    shadowOptions.polygonOffsetSlope = options->polygonOffsetSlope;
    shadowOptions.screenSpaceContactShadows = options->screenSpaceContactShadows;
    shadowOptions.stepCount = options->stepCount;
    shadowOptions.maxShadowDistance = options->maxShadowDistance;
    shadowOptions.vsm.elvsm = options->vsm.elvsm;
    shadowOptions.vsm.blurWidth = options->vsm.blurWidth;
    shadowOptions.shadowBulbRadius = options->shadowBulbRadius;
    std::copy_n(options->transform, 4, shadowOptions.transform.xyzw.v);

    FILA_CAST(LightManager::Builder, builder)->shadowOptions(shadowOptions);
}

void FilaLightManagerBuilder_castLight(FilaLightManagerBuilder* builder, bool enable) {
    FILA_CAST(LightManager::Builder, builder)->castLight(enable);
}

void FilaLightManagerBuilder_position(FilaLightManagerBuilder* builder, float x, float y, float z) {
    FILA_CAST(LightManager::Builder, builder)->position({x, y, z});
}

void FilaLightManagerBuilder_direction(FilaLightManagerBuilder* builder, float x, float y, float z) {
    FILA_CAST(LightManager::Builder, builder)->direction({x, y, z});
}

void FilaLightManagerBuilder_color(FilaLightManagerBuilder* builder, float linearR, float linearG, float linearB) {
    FILA_CAST(LightManager::Builder, builder)->color({linearR, linearG, linearB});
}

void FilaLightManagerBuilder_intensity(FilaLightManagerBuilder* builder, float intensity) {
    FILA_CAST(LightManager::Builder, builder)->intensity(intensity);
}

void FilaLightManagerBuilder_intensityEfficiency(FilaLightManagerBuilder* builder, float watts, float efficiency) {
    FILA_CAST(LightManager::Builder, builder)->intensity(watts, efficiency);
}

void FilaLightManagerBuilder_intensityCandela(FilaLightManagerBuilder* builder, float intensity) {
    FILA_CAST(LightManager::Builder, builder)->intensityCandela(intensity);
}

void FilaLightManagerBuilder_falloff(FilaLightManagerBuilder* builder, float radius) {
    FILA_CAST(LightManager::Builder, builder)->falloff(radius);
}

void FilaLightManagerBuilder_spotLightCone(FilaLightManagerBuilder* builder, float inner, float outer) {
    FILA_CAST(LightManager::Builder, builder)->spotLightCone(inner, outer);
}

void FilaLightManagerBuilder_sunAngularRadius(FilaLightManagerBuilder* builder, float angularRadius) {
    FILA_CAST(LightManager::Builder, builder)->sunAngularRadius(angularRadius);
}

void FilaLightManagerBuilder_sunHaloSize(FilaLightManagerBuilder* builder, float haloSize) {
    FILA_CAST(LightManager::Builder, builder)->sunHaloSize(haloSize);
}

void FilaLightManagerBuilder_sunHaloFalloff(FilaLightManagerBuilder* builder, float haloFalloff) {
    FILA_CAST(LightManager::Builder, builder)->sunHaloFalloff(haloFalloff);
}

void FilaLightManagerBuilder_lightChannel(FilaLightManagerBuilder* builder, unsigned int channel, bool enable) {
    FILA_CAST(LightManager::Builder, builder)->lightChannel(channel, enable);
}

// Utils
void FilaLightManager_computeUniformSplits(float* splitPositions, uint8_t cascades) {
    LightManager::ShadowCascades::computeUniformSplits(splitPositions, cascades);
}

void FilaLightManager_computeLogSplits(float* splitPositions, uint8_t cascades, float nearPlane, float farPlane) {
    LightManager::ShadowCascades::computeLogSplits(splitPositions, cascades, nearPlane, farPlane);
}

void FilaLightManager_computePracticalSplits(float* splitPositions, uint8_t cascades, float nearPlane, float farPlane, float lambda) {
    LightManager::ShadowCascades::computePracticalSplits(splitPositions, cascades, nearPlane, farPlane, lambda);
}

// LightManager
size_t FilaLightManager_getComponentCount(const FilaLightManager* lm) {
    return FILA_CONST_CAST(LightManager, lm)->getComponentCount();
}

bool FilaLightManager_hasComponent(const FilaLightManager* lm, FilaEntity entity) {
    return FILA_CONST_CAST(LightManager, lm)->hasComponent(Entity::import(entity));
}

FilaLightManagerInstance FilaLightManager_getInstance(const FilaLightManager* lm, FilaEntity entity) {
    return FILA_CONST_CAST(LightManager, lm)->getInstance(Entity::import(entity)).asValue();
}

void FilaLightManager_destroy(FilaLightManager* lm, FilaEntity entity) {
    FILA_CAST(LightManager, lm)->destroy(Entity::import(entity));
}

FilaLightManagerType FilaLightManager_getType(const FilaLightManager* lm, FilaLightManagerInstance instance) {
    return static_cast<FilaLightManagerType>(FILA_CONST_CAST(LightManager, lm)->getType(LightManager::Instance(instance)));
}

void FilaLightManager_setPosition(FilaLightManager* lm, FilaLightManagerInstance instance, float x, float y, float z) {
    FILA_CAST(LightManager, lm)->setPosition(LightManager::Instance(instance), {x, y, z});
}

void FilaLightManager_getPosition(const FilaLightManager* lm, FilaLightManagerInstance instance, float out[3]) {
    filament::math::float3 pos = FILA_CONST_CAST(LightManager, lm)->getPosition(LightManager::Instance(instance));
    out[0] = pos.x; out[1] = pos.y; out[2] = pos.z;
}

void FilaLightManager_setDirection(FilaLightManager* lm, FilaLightManagerInstance instance, float x, float y, float z) {
    FILA_CAST(LightManager, lm)->setDirection(LightManager::Instance(instance), {x, y, z});
}

void FilaLightManager_getDirection(const FilaLightManager* lm, FilaLightManagerInstance instance, float out[3]) {
    filament::math::float3 dir = FILA_CONST_CAST(LightManager, lm)->getDirection(LightManager::Instance(instance));
    out[0] = dir.x; out[1] = dir.y; out[2] = dir.z;
}

void FilaLightManager_setColor(FilaLightManager* lm, FilaLightManagerInstance instance, float linearR, float linearG, float linearB) {
    FILA_CAST(LightManager, lm)->setColor(LightManager::Instance(instance), {linearR, linearG, linearB});
}

void FilaLightManager_getColor(const FilaLightManager* lm, FilaLightManagerInstance instance, float out[3]) {
    filament::math::float3 color = FILA_CONST_CAST(LightManager, lm)->getColor(LightManager::Instance(instance));
    out[0] = color.r; out[1] = color.g; out[2] = color.b;
}

void FilaLightManager_setIntensity(FilaLightManager* lm, FilaLightManagerInstance instance, float intensity) {
    FILA_CAST(LightManager, lm)->setIntensity(LightManager::Instance(instance), intensity);
}

void FilaLightManager_setIntensityEfficiency(FilaLightManager* lm, FilaLightManagerInstance instance, float watts, float efficiency) {
    FILA_CAST(LightManager, lm)->setIntensity(LightManager::Instance(instance), watts, efficiency);
}

void FilaLightManager_setIntensityCandela(FilaLightManager* lm, FilaLightManagerInstance instance, float intensity) {
    FILA_CAST(LightManager, lm)->setIntensityCandela(LightManager::Instance(instance), intensity);
}

float FilaLightManager_getIntensity(const FilaLightManager* lm, FilaLightManagerInstance instance) {
    return FILA_CONST_CAST(LightManager, lm)->getIntensity(LightManager::Instance(instance));
}

void FilaLightManager_setFalloff(FilaLightManager* lm, FilaLightManagerInstance instance, float radius) {
    FILA_CAST(LightManager, lm)->setFalloff(LightManager::Instance(instance), radius);
}

float FilaLightManager_getFalloff(const FilaLightManager* lm, FilaLightManagerInstance instance) {
    return FILA_CONST_CAST(LightManager, lm)->getFalloff(LightManager::Instance(instance));
}

void FilaLightManager_setSpotLightCone(FilaLightManager* lm, FilaLightManagerInstance instance, float inner, float outer) {
    FILA_CAST(LightManager, lm)->setSpotLightCone(LightManager::Instance(instance), inner, outer);
}

float FilaLightManager_getSpotLightInnerCone(const FilaLightManager* lm, FilaLightManagerInstance instance) {
    return FILA_CONST_CAST(LightManager, lm)->getSpotLightInnerCone(LightManager::Instance(instance));
}

float FilaLightManager_getSpotLightOuterCone(const FilaLightManager* lm, FilaLightManagerInstance instance) {
    return FILA_CONST_CAST(LightManager, lm)->getSpotLightOuterCone(LightManager::Instance(instance));
}

void FilaLightManager_setSunAngularRadius(FilaLightManager* lm, FilaLightManagerInstance instance, float angularRadius) {
    FILA_CAST(LightManager, lm)->setSunAngularRadius(LightManager::Instance(instance), angularRadius);
}

float FilaLightManager_getSunAngularRadius(const FilaLightManager* lm, FilaLightManagerInstance instance) {
    return FILA_CONST_CAST(LightManager, lm)->getSunAngularRadius(LightManager::Instance(instance));
}

void FilaLightManager_setSunHaloSize(FilaLightManager* lm, FilaLightManagerInstance instance, float haloSize) {
    FILA_CAST(LightManager, lm)->setSunHaloSize(LightManager::Instance(instance), haloSize);
}

float FilaLightManager_getSunHaloSize(const FilaLightManager* lm, FilaLightManagerInstance instance) {
    return FILA_CONST_CAST(LightManager, lm)->getSunHaloSize(LightManager::Instance(instance));
}

void FilaLightManager_setSunHaloFalloff(FilaLightManager* lm, FilaLightManagerInstance instance, float haloFalloff) {
    FILA_CAST(LightManager, lm)->setSunHaloFalloff(LightManager::Instance(instance), haloFalloff);
}

float FilaLightManager_getSunHaloFalloff(const FilaLightManager* lm, FilaLightManagerInstance instance) {
    return FILA_CONST_CAST(LightManager, lm)->getSunHaloFalloff(LightManager::Instance(instance));
}

void FilaLightManager_setShadowCaster(FilaLightManager* lm, FilaLightManagerInstance instance, bool shadowCaster) {
    FILA_CAST(LightManager, lm)->setShadowCaster(LightManager::Instance(instance), shadowCaster);
}

bool FilaLightManager_isShadowCaster(const FilaLightManager* lm, FilaLightManagerInstance instance) {
    return FILA_CONST_CAST(LightManager, lm)->isShadowCaster(LightManager::Instance(instance));
}

void FilaLightManager_setLightChannel(FilaLightManager* lm, FilaLightManagerInstance instance, unsigned int channel, bool enable) {
    FILA_CAST(LightManager, lm)->setLightChannel(LightManager::Instance(instance), channel, enable);
}

bool FilaLightManager_getLightChannel(const FilaLightManager* lm, FilaLightManagerInstance instance, unsigned int channel) {
    return FILA_CONST_CAST(LightManager, lm)->getLightChannel(LightManager::Instance(instance), channel);
}

} // extern "C"
