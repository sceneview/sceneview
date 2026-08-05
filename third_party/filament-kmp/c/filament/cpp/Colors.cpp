#include <filament/Color.h>
#include <math/vec3.h>

#include "FilaCommon.h"
#include "../c/Colors.h"

using namespace filament;

extern "C" {

void FilaColors_cct(float temperature, float* outColor) {
    const math::float3 cct = Color::cct(temperature);
    outColor[0] = cct.r;
    outColor[1] = cct.g;
    outColor[2] = cct.b;
}

void FilaColors_illuminantD(float temperature, float* outColor) {
    const math::float3 illuminantD = Color::illuminantD(temperature);
    outColor[0] = illuminantD.r;
    outColor[1] = illuminantD.g;
    outColor[2] = illuminantD.b;
}

void FilaColors_toLinearRgb(FilaRgbType type, const float* inRgb, float* outRgb) {
    const math::float3 color = *reinterpret_cast<const math::float3*>(inRgb);
    const math::float3 linear = Color::toLinear(static_cast<filament::RgbType>(type), color);
    outRgb[0] = linear.r;
    outRgb[1] = linear.g;
    outRgb[2] = linear.b;
}

void FilaColors_toLinearRgba(FilaRgbaType type, const float* inRgba, float* outRgba) {
    const math::float4 color = *reinterpret_cast<const math::float4*>(inRgba);
    const math::float4 linear = Color::toLinear(static_cast<filament::RgbaType>(type), color);
    outRgba[0] = linear.r;
    outRgba[1] = linear.g;
    outRgba[2] = linear.b;
    outRgba[3] = linear.a;
}

void FilaColors_toLinearConvert(FilaColorConversion conversion, const float* inRgb, float* outRgb) {
    math::float3 color = *reinterpret_cast<const math::float3*>(inRgb);
    if (conversion == FILA_COLOR_CONVERSION_ACCURATE) {
        color = Color::toLinear<ACCURATE>(color);
    } else {
        color = Color::toLinear<FAST>(color);
    }
    outRgb[0] = color.r;
    outRgb[1] = color.g;
    outRgb[2] = color.b;
}

} // extern "C"
