#ifndef FILAMENT_CPP_SAMPLER_UTILS_H
#define FILAMENT_CPP_SAMPLER_UTILS_H

#include <filament/TextureSampler.h>
#include <utils/algorithm.h>
#include "../c/FilaTypes.h"

namespace filament::SamplerUtils {

inline FilaTextureSampler to_c(TextureSampler const& sampler) noexcept {
    return FilaTextureSampler(utils::bit_cast<uint32_t>(sampler.getSamplerParams()));
}

inline TextureSampler from_c(FilaTextureSampler params) noexcept {
    return TextureSampler{
            utils::bit_cast<backend::SamplerParams>(
                    static_cast<uint32_t>(params))};
}

} // namespace filament::SamplerUtils

#endif // FILAMENT_CPP_SAMPLER_UTILS_H
