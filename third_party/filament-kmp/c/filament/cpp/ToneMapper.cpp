#include <filament/ToneMapper.h>

#include "FilaCommon.h"
#include "../c/ToneMapper.h"

using namespace filament;

extern "C" {

FilaToneMapper* FilaToneMapper_Linear() {
    return reinterpret_cast<FilaToneMapper*>(new LinearToneMapper());
}

FilaToneMapper* FilaToneMapper_ACES() {
    return reinterpret_cast<FilaToneMapper*>(new ACESToneMapper());
}

FilaToneMapper* FilaToneMapper_ACESLegacy() {
    return reinterpret_cast<FilaToneMapper*>(new ACESLegacyToneMapper());
}

FilaToneMapper* FilaToneMapper_Filmic() {
    return reinterpret_cast<FilaToneMapper*>(new FilmicToneMapper());
}

FilaToneMapper* FilaToneMapper_PBRNeutral() {
    return reinterpret_cast<FilaToneMapper*>(new PBRNeutralToneMapper());
}

FilaToneMapper* FilaToneMapper_GT7() {
    return reinterpret_cast<FilaToneMapper*>(new GT7ToneMapper());
}

FilaToneMapper* FilaToneMapper_Agx(FilaAgxLook look) {
    return reinterpret_cast<FilaToneMapper*>(new AgxToneMapper(static_cast<AgxToneMapper::AgxLook>(look)));
}

FilaToneMapper* FilaToneMapper_Generic(float contrast, float midGrayIn, float midGrayOut, float hdrMax) {
    return reinterpret_cast<FilaToneMapper*>(new GenericToneMapper(contrast, midGrayIn, midGrayOut, hdrMax));
}

FilaToneMapper* FilaToneMapper_DisplayRange() {
    return reinterpret_cast<FilaToneMapper*>(new DisplayRangeToneMapper());
}

void FilaToneMapper_destroy(FilaToneMapper* toneMapper) {
    delete reinterpret_cast<ToneMapper*>(toneMapper);
}

float FilaToneMapper_Generic_getContrast(const FilaToneMapper* toneMapper) {
    return reinterpret_cast<const GenericToneMapper*>(toneMapper)->getContrast();
}

float FilaToneMapper_Generic_getMidGrayIn(const FilaToneMapper* toneMapper) {
    return reinterpret_cast<const GenericToneMapper*>(toneMapper)->getMidGrayIn();
}

float FilaToneMapper_Generic_getMidGrayOut(const FilaToneMapper* toneMapper) {
    return reinterpret_cast<const GenericToneMapper*>(toneMapper)->getMidGrayOut();
}

float FilaToneMapper_Generic_getHdrMax(const FilaToneMapper* toneMapper) {
    return reinterpret_cast<const GenericToneMapper*>(toneMapper)->getHdrMax();
}

void FilaToneMapper_Generic_setContrast(FilaToneMapper* toneMapper, float contrast) {
    reinterpret_cast<GenericToneMapper*>(toneMapper)->setContrast(contrast);
}

void FilaToneMapper_Generic_setMidGrayIn(FilaToneMapper* toneMapper, float midGrayIn) {
    reinterpret_cast<GenericToneMapper*>(toneMapper)->setMidGrayIn(midGrayIn);
}

void FilaToneMapper_Generic_setMidGrayOut(FilaToneMapper* toneMapper, float midGrayOut) {
    reinterpret_cast<GenericToneMapper*>(toneMapper)->setMidGrayOut(midGrayOut);
}

void FilaToneMapper_Generic_setHdrMax(FilaToneMapper* toneMapper, float hdrMax) {
    reinterpret_cast<GenericToneMapper*>(toneMapper)->setHdrMax(hdrMax);
}

} // extern "C"
