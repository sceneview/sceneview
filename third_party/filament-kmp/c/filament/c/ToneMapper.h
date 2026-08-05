#ifndef FILAMENT_C_TONE_MAPPER_H
#define FILAMENT_C_TONE_MAPPER_H

#include "FilaTypes.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef enum FilaAgxLook {
    FILA_AGX_LOOK_NONE = 0,
    FILA_AGX_LOOK_PUNCHY = 1,
    FILA_AGX_LOOK_GOLDEN = 2,
} FilaAgxLook;

// ToneMapper Factories
FilaToneMapper* FilaToneMapper_Linear(void);
FilaToneMapper* FilaToneMapper_ACES(void);
FilaToneMapper* FilaToneMapper_ACESLegacy(void);
FilaToneMapper* FilaToneMapper_Filmic(void);
FilaToneMapper* FilaToneMapper_PBRNeutral(void);
FilaToneMapper* FilaToneMapper_GT7(void);
FilaToneMapper* FilaToneMapper_Agx(FilaAgxLook look);
FilaToneMapper* FilaToneMapper_Generic(float contrast, float midGrayIn, float midGrayOut, float hdrMax);
FilaToneMapper* FilaToneMapper_DisplayRange(void);

void FilaToneMapper_destroy(FilaToneMapper* toneMapper);

float FilaToneMapper_Generic_getContrast(const FilaToneMapper* toneMapper);
float FilaToneMapper_Generic_getMidGrayIn(const FilaToneMapper* toneMapper);
float FilaToneMapper_Generic_getMidGrayOut(const FilaToneMapper* toneMapper);
float FilaToneMapper_Generic_getHdrMax(const FilaToneMapper* toneMapper);
void FilaToneMapper_Generic_setContrast(FilaToneMapper* toneMapper, float contrast);
void FilaToneMapper_Generic_setMidGrayIn(FilaToneMapper* toneMapper, float midGrayIn);
void FilaToneMapper_Generic_setMidGrayOut(FilaToneMapper* toneMapper, float midGrayOut);
void FilaToneMapper_Generic_setHdrMax(FilaToneMapper* toneMapper, float hdrMax);

#ifdef __cplusplus
}
#endif

#endif // FILAMENT_C_TONE_MAPPER_H
