#include <filament/TextureSampler.h>
#include "SamplerUtils.h"
#include "../c/TextureSampler.h"

using namespace filament;
using namespace filament::SamplerUtils;

extern "C" {

FilaTextureSampler FilaTextureSampler_create(FilaTextureSamplerMinFilter min, FilaTextureSamplerMagFilter mag, FilaTextureSamplerWrapMode s, FilaTextureSamplerWrapMode t, FilaTextureSamplerWrapMode r) {
    TextureSampler sampler(static_cast<TextureSampler::MinFilter>(min),
                           static_cast<TextureSampler::MagFilter>(mag),
                           static_cast<TextureSampler::WrapMode>(s),
                           static_cast<TextureSampler::WrapMode>(t),
                           static_cast<TextureSampler::WrapMode>(r));
    return to_c(sampler);
}

FilaTextureSampler FilaTextureSampler_createCompare(FilaTextureSamplerCompareMode mode, FilaTextureSamplerCompareFunc func) {
    TextureSampler sampler(static_cast<TextureSampler::CompareMode>(mode),
                           static_cast<TextureSampler::CompareFunc>(func));
    return to_c(sampler);
}

FilaTextureSamplerMinFilter FilaTextureSampler_getMinFilter(FilaTextureSampler sampler) {
    return static_cast<FilaTextureSamplerMinFilter>(from_c(sampler).getMinFilter());
}

FilaTextureSampler FilaTextureSampler_setMinFilter(FilaTextureSampler sampler, FilaTextureSamplerMinFilter filter) {
    TextureSampler s{from_c(sampler)};
    s.setMinFilter(static_cast<TextureSampler::MinFilter>(filter));
    return to_c(s);
}

FilaTextureSamplerMagFilter FilaTextureSampler_getMagFilter(FilaTextureSampler sampler) {
    return static_cast<FilaTextureSamplerMagFilter>(from_c(sampler).getMagFilter());
}

FilaTextureSampler FilaTextureSampler_setMagFilter(FilaTextureSampler sampler, FilaTextureSamplerMagFilter filter) {
    TextureSampler s{from_c(sampler)};
    s.setMagFilter(static_cast<TextureSampler::MagFilter>(filter));
    return to_c(s);
}

FilaTextureSamplerWrapMode FilaTextureSampler_getWrapModeS(FilaTextureSampler sampler) {
    return static_cast<FilaTextureSamplerWrapMode>(from_c(sampler).getWrapModeS());
}

FilaTextureSampler FilaTextureSampler_setWrapModeS(FilaTextureSampler sampler, FilaTextureSamplerWrapMode mode) {
    TextureSampler s{from_c(sampler)};
    s.setWrapModeS(static_cast<TextureSampler::WrapMode>(mode));
    return to_c(s);
}

FilaTextureSamplerWrapMode FilaTextureSampler_getWrapModeT(FilaTextureSampler sampler) {
    return static_cast<FilaTextureSamplerWrapMode>(from_c(sampler).getWrapModeT());
}

FilaTextureSampler FilaTextureSampler_setWrapModeT(FilaTextureSampler sampler, FilaTextureSamplerWrapMode mode) {
    TextureSampler s{from_c(sampler)};
    s.setWrapModeT(static_cast<TextureSampler::WrapMode>(mode));
    return to_c(s);
}

FilaTextureSamplerWrapMode FilaTextureSampler_getWrapModeR(FilaTextureSampler sampler) {
    return static_cast<FilaTextureSamplerWrapMode>(from_c(sampler).getWrapModeR());
}

FilaTextureSampler FilaTextureSampler_setWrapModeR(FilaTextureSampler sampler, FilaTextureSamplerWrapMode mode) {
    TextureSampler s{from_c(sampler)};
    s.setWrapModeR(static_cast<TextureSampler::WrapMode>(mode));
    return to_c(s);
}

FilaTextureSamplerCompareMode FilaTextureSampler_getCompareMode(FilaTextureSampler sampler) {
    return static_cast<FilaTextureSamplerCompareMode>(from_c(sampler).getCompareMode());
}

FilaTextureSampler FilaTextureSampler_setCompareMode(FilaTextureSampler sampler, FilaTextureSamplerCompareMode mode) {
    TextureSampler s{from_c(sampler)};
    s.setCompareMode(static_cast<TextureSampler::CompareMode>(mode), s.getCompareFunc());
    return to_c(s);
}

FilaTextureSamplerCompareFunc FilaTextureSampler_getCompareFunction(FilaTextureSampler sampler) {
    return static_cast<FilaTextureSamplerCompareFunc>(from_c(sampler).getCompareFunc());
}

FilaTextureSampler FilaTextureSampler_setCompareFunction(FilaTextureSampler sampler, FilaTextureSamplerCompareFunc func) {
    TextureSampler s{from_c(sampler)};
    s.setCompareMode(s.getCompareMode(), static_cast<TextureSampler::CompareFunc>(func));
    return to_c(s);
}

float FilaTextureSampler_getAnisotropy(FilaTextureSampler sampler) {
    return from_c(sampler).getAnisotropy();
}

FilaTextureSampler FilaTextureSampler_setAnisotropy(FilaTextureSampler sampler, float anisotropy) {
    TextureSampler s{from_c(sampler)};
    s.setAnisotropy(anisotropy);
    return to_c(s);
}

} // extern "C"
