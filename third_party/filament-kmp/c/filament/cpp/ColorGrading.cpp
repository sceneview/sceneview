#include <filament/ColorGrading.h>
#include <filament/Engine.h>
#include <filament/ToneMapper.h>
#include <math/vec3.h>
#include <math/vec4.h>
#include <utils/FixedCapacityVector.h>

#include "FilaCommon.h"
#include "../c/ColorGrading.h"

using namespace filament;

extern "C" {

FilaColorGradingBuilder* FilaColorGradingBuilder_create() {
    return reinterpret_cast<FilaColorGradingBuilder*>(new ColorGrading::Builder());
}

void FilaColorGradingBuilder_destroy(FilaColorGradingBuilder* builder) {
    delete reinterpret_cast<ColorGrading::Builder*>(builder);
}

FilaColorGrading* FilaColorGradingBuilder_build(FilaColorGradingBuilder* builder, FilaEngine* engine) {
    return reinterpret_cast<FilaColorGrading*>(FILA_CAST(ColorGrading::Builder, builder)->build(*FILA_CAST(Engine, engine)));
}

void FilaColorGradingBuilder_quality(FilaColorGradingBuilder* builder, FilaColorGradingQualityLevel quality) {
    FILA_CAST(ColorGrading::Builder, builder)->quality(static_cast<ColorGrading::QualityLevel>(quality));
}

void FilaColorGradingBuilder_format(FilaColorGradingBuilder* builder, FilaColorGradingLutFormat format) {
    FILA_CAST(ColorGrading::Builder, builder)->format(static_cast<ColorGrading::LutFormat>(format));
}

void FilaColorGradingBuilder_dimensions(FilaColorGradingBuilder* builder, uint8_t dim) {
    FILA_CAST(ColorGrading::Builder, builder)->dimensions(dim);
}

void FilaColorGradingBuilder_toneMapper(FilaColorGradingBuilder* builder, const FilaToneMapper* toneMapper) {
    FILA_CAST(ColorGrading::Builder, builder)->toneMapper(reinterpret_cast<const ToneMapper*>(toneMapper));
}


void FilaColorGradingBuilder_luminanceScaling(FilaColorGradingBuilder* builder, bool luminanceScaling) {
    FILA_CAST(ColorGrading::Builder, builder)->luminanceScaling(luminanceScaling);
}

void FilaColorGradingBuilder_gamutMapping(FilaColorGradingBuilder* builder, bool gamutMapping) {
    FILA_CAST(ColorGrading::Builder, builder)->gamutMapping(gamutMapping);
}

void FilaColorGradingBuilder_exposure(FilaColorGradingBuilder* builder, float exposure) {
    FILA_CAST(ColorGrading::Builder, builder)->exposure(exposure);
}

void FilaColorGradingBuilder_nightAdaptation(FilaColorGradingBuilder* builder, float adaptation) {
    FILA_CAST(ColorGrading::Builder, builder)->nightAdaptation(adaptation);
}

void FilaColorGradingBuilder_whiteBalance(FilaColorGradingBuilder* builder, float temperature, float tint) {
    FILA_CAST(ColorGrading::Builder, builder)->whiteBalance(temperature, tint);
}

void FilaColorGradingBuilder_channelMixer(FilaColorGradingBuilder* builder, const float* outRed, const float* outGreen, const float* outBlue) {
    FILA_CAST(ColorGrading::Builder, builder)->channelMixer(
        *reinterpret_cast<const math::float3*>(outRed),
        *reinterpret_cast<const math::float3*>(outGreen),
        *reinterpret_cast<const math::float3*>(outBlue)
    );
}

void FilaColorGradingBuilder_shadowsMidtonesHighlights(FilaColorGradingBuilder* builder, const float* shadows, const float* midtones, const float* highlights, const float* ranges) {
    FILA_CAST(ColorGrading::Builder, builder)->shadowsMidtonesHighlights(
        *reinterpret_cast<const math::float4*>(shadows),
        *reinterpret_cast<const math::float4*>(midtones),
        *reinterpret_cast<const math::float4*>(highlights),
        *reinterpret_cast<const math::float4*>(ranges)
    );
}

void FilaColorGradingBuilder_slopeOffsetPower(FilaColorGradingBuilder* builder, const float* slope, const float* offset, const float* power) {
    FILA_CAST(ColorGrading::Builder, builder)->slopeOffsetPower(
        *reinterpret_cast<const math::float3*>(slope),
        *reinterpret_cast<const math::float3*>(offset),
        *reinterpret_cast<const math::float3*>(power)
    );
}

void FilaColorGradingBuilder_contrast(FilaColorGradingBuilder* builder, float contrast) {
    FILA_CAST(ColorGrading::Builder, builder)->contrast(contrast);
}

void FilaColorGradingBuilder_vibrance(FilaColorGradingBuilder* builder, float vibrance) {
    FILA_CAST(ColorGrading::Builder, builder)->vibrance(vibrance);
}

void FilaColorGradingBuilder_saturation(FilaColorGradingBuilder* builder, float saturation) {
    FILA_CAST(ColorGrading::Builder, builder)->saturation(saturation);
}

void FilaColorGradingBuilder_curves(FilaColorGradingBuilder* builder, const float* shadowGamma, const float* midPoint, const float* highlightScale) {
    FILA_CAST(ColorGrading::Builder, builder)->curves(
        *reinterpret_cast<const math::float3*>(shadowGamma),
        *reinterpret_cast<const math::float3*>(midPoint),
        *reinterpret_cast<const math::float3*>(highlightScale)
    );
}

void FilaColorGradingBuilder_customLut(FilaColorGradingBuilder* builder, const float* data, uint8_t dimension) {
    const size_t count = size_t(dimension) * dimension * dimension;
    utils::FixedCapacityVector<math::float3> lut(count);
    for (size_t i = 0; i < count; i++) {
        lut[i] = { data[i * 3 + 0], data[i * 3 + 1], data[i * 3 + 2] };
    }
    FILA_CAST(ColorGrading::Builder, builder)->customLut(std::move(lut), dimension);
}

void FilaColorGradingBuilder_fastMath(FilaColorGradingBuilder* builder, bool fastMath) {
    FILA_CAST(ColorGrading::Builder, builder)->fastMath(fastMath);
}

} // extern "C"
