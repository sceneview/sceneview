#ifndef FILAMENT_C_COLOR_GRADING_H
#define FILAMENT_C_COLOR_GRADING_H

#include "FilaTypes.h"
#include "ToneMapper.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef enum FilaColorGradingQualityLevel {
    FILA_COLOR_GRADING_QUALITY_LOW = 0,
    FILA_COLOR_GRADING_QUALITY_MEDIUM = 1,
    FILA_COLOR_GRADING_QUALITY_HIGH = 2,
    FILA_COLOR_GRADING_QUALITY_ULTRA = 3,
} FilaColorGradingQualityLevel;

typedef enum FilaColorGradingLutFormat {
    FILA_COLOR_GRADING_LUT_FORMAT_INTEGER = 0,
    FILA_COLOR_GRADING_LUT_FORMAT_FLOAT = 1,
} FilaColorGradingLutFormat;


// Builder
typedef struct FilaColorGradingBuilder FilaColorGradingBuilder;

FilaColorGradingBuilder* FilaColorGradingBuilder_create(void);
void FilaColorGradingBuilder_destroy(FilaColorGradingBuilder* builder);
FilaColorGrading* FilaColorGradingBuilder_build(FilaColorGradingBuilder* builder, FilaEngine* engine);

void FilaColorGradingBuilder_quality(FilaColorGradingBuilder* builder, FilaColorGradingQualityLevel quality);
void FilaColorGradingBuilder_format(FilaColorGradingBuilder* builder, FilaColorGradingLutFormat format);
void FilaColorGradingBuilder_dimensions(FilaColorGradingBuilder* builder, uint8_t dim);
void FilaColorGradingBuilder_toneMapper(FilaColorGradingBuilder* builder, const FilaToneMapper* toneMapper);
void FilaColorGradingBuilder_luminanceScaling(FilaColorGradingBuilder* builder, bool luminanceScaling);
void FilaColorGradingBuilder_gamutMapping(FilaColorGradingBuilder* builder, bool gamutMapping);
void FilaColorGradingBuilder_exposure(FilaColorGradingBuilder* builder, float exposure);
void FilaColorGradingBuilder_nightAdaptation(FilaColorGradingBuilder* builder, float adaptation);
void FilaColorGradingBuilder_whiteBalance(FilaColorGradingBuilder* builder, float temperature, float tint);
void FilaColorGradingBuilder_channelMixer(FilaColorGradingBuilder* builder, const float* outRed, const float* outGreen, const float* outBlue);
void FilaColorGradingBuilder_shadowsMidtonesHighlights(FilaColorGradingBuilder* builder, const float* shadows, const float* midtones, const float* highlights, const float* ranges);
void FilaColorGradingBuilder_slopeOffsetPower(FilaColorGradingBuilder* builder, const float* slope, const float* offset, const float* power);
void FilaColorGradingBuilder_contrast(FilaColorGradingBuilder* builder, float contrast);
void FilaColorGradingBuilder_vibrance(FilaColorGradingBuilder* builder, float vibrance);
void FilaColorGradingBuilder_saturation(FilaColorGradingBuilder* builder, float saturation);
void FilaColorGradingBuilder_curves(FilaColorGradingBuilder* builder, const float* shadowGamma, const float* midPoint, const float* highlightScale);
void FilaColorGradingBuilder_customLut(FilaColorGradingBuilder* builder, const float* data, uint8_t dimension);
void FilaColorGradingBuilder_fastMath(FilaColorGradingBuilder* builder, bool fastMath);

#ifdef __cplusplus
}
#endif

#endif // FILAMENT_C_COLOR_GRADING_H
