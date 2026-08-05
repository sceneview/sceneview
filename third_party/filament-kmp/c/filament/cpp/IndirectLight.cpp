#include <filament/IndirectLight.h>
#include <filament/Texture.h>
#include <filament/Engine.h>
#include <math/mat3.h>
#include <math/vec4.h>

#include "FilaCommon.h"
#include "../c/IndirectLight.h"

using namespace filament;

extern "C" {

FilaIndirectLightBuilder* FilaIndirectLightBuilder_create() {
    return reinterpret_cast<FilaIndirectLightBuilder*>(new IndirectLight::Builder());
}

void FilaIndirectLightBuilder_destroy(FilaIndirectLightBuilder* builder) {
    delete reinterpret_cast<IndirectLight::Builder*>(builder);
}

FilaIndirectLight* FilaIndirectLightBuilder_build(FilaIndirectLightBuilder* builder, FilaEngine* engine) {
    return reinterpret_cast<FilaIndirectLight*>(FILA_CAST(IndirectLight::Builder, builder)->build(*FILA_CAST(Engine, engine)));
}

void FilaIndirectLightBuilder_reflections(FilaIndirectLightBuilder* builder, const FilaTexture* cubemap) {
    FILA_CAST(IndirectLight::Builder, builder)->reflections(reinterpret_cast<const Texture*>(cubemap));
}

void FilaIndirectLightBuilder_irradiance(FilaIndirectLightBuilder* builder, uint8_t bands, const float* sh) {
    FILA_CAST(IndirectLight::Builder, builder)->irradiance(bands, reinterpret_cast<const math::float3*>(sh));
}

void FilaIndirectLightBuilder_radiance(FilaIndirectLightBuilder* builder, uint8_t bands, const float* sh) {
    FILA_CAST(IndirectLight::Builder, builder)->radiance(bands, reinterpret_cast<const math::float3*>(sh));
}

void FilaIndirectLightBuilder_irradianceAsTexture(FilaIndirectLightBuilder* builder, const FilaTexture* cubemap) {
    FILA_CAST(IndirectLight::Builder, builder)->irradiance(reinterpret_cast<const Texture*>(cubemap));
}

void FilaIndirectLightBuilder_intensity(FilaIndirectLightBuilder* builder, float envIntensity) {
    FILA_CAST(IndirectLight::Builder, builder)->intensity(envIntensity);
}

void FilaIndirectLightBuilder_rotation(FilaIndirectLightBuilder* builder, const float* rotation) {
    FILA_CAST(IndirectLight::Builder, builder)->rotation(*reinterpret_cast<const math::mat3f*>(rotation));
}

// IndirectLight instance methods
void FilaIndirectLight_setIntensity(FilaIndirectLight* indirectLight, float intensity) {
    FILA_CAST(IndirectLight, indirectLight)->setIntensity(intensity);
}

float FilaIndirectLight_getIntensity(const FilaIndirectLight* indirectLight) {
    return FILA_CONST_CAST(IndirectLight, indirectLight)->getIntensity();
}

void FilaIndirectLight_setRotation(FilaIndirectLight* indirectLight, const float* rotation) {
    FILA_CAST(IndirectLight, indirectLight)->setRotation(*reinterpret_cast<const math::mat3f*>(rotation));
}

void FilaIndirectLight_getRotation(const FilaIndirectLight* indirectLight, float* outRotation) {
    *reinterpret_cast<math::mat3f*>(outRotation) = FILA_CONST_CAST(IndirectLight, indirectLight)->getRotation();
}

void FilaIndirectLight_getDirectionEstimate(const FilaIndirectLight* indirectLight, float* outDirection) {
    *reinterpret_cast<math::float3*>(outDirection) = FILA_CONST_CAST(IndirectLight, indirectLight)->getDirectionEstimate();
}

void FilaIndirectLight_getColorEstimate(const FilaIndirectLight* indirectLight, float x, float y, float z, float* outColor) {
    *reinterpret_cast<math::float4*>(outColor) = FILA_CONST_CAST(IndirectLight, indirectLight)->getColorEstimate(math::float3{x, y, z});
}

FilaTexture* FilaIndirectLight_getReflectionsTexture(const FilaIndirectLight* indirectLight) {
    return reinterpret_cast<FilaTexture*>(const_cast<Texture*>(FILA_CONST_CAST(IndirectLight, indirectLight)->getReflectionsTexture()));
}

FilaTexture* FilaIndirectLight_getIrradianceTexture(const FilaIndirectLight* indirectLight) {
    return reinterpret_cast<FilaTexture*>(const_cast<Texture*>(FILA_CONST_CAST(IndirectLight, indirectLight)->getIrradianceTexture()));
}

// Static methods
void FilaIndirectLight_getDirectionEstimateStatic(const float* sh, float* outDirection) {
    *reinterpret_cast<math::float3*>(outDirection) = IndirectLight::getDirectionEstimate(reinterpret_cast<const math::float3*>(sh));
}

void FilaIndirectLight_getColorEstimateStatic(const float* sh, float x, float y, float z, float* outColor) {
    *reinterpret_cast<math::float4*>(outColor) = IndirectLight::getColorEstimate(reinterpret_cast<const math::float3*>(sh), math::float3{x, y, z});
}

} // extern "C"
