#include <filament-iblprefilter/IBLPrefilterContext.h>
#include <filament/Engine.h>
#include <filament/Texture.h>

#include "../../filament/cpp/FilaCommon.h"
#include "../c/IBLPrefilter.h"

using namespace filament;

extern "C" {

FilaIBLPrefilterContext* FilaIBLPrefilterContext_create(FilaEngine* engine) {
    return reinterpret_cast<FilaIBLPrefilterContext*>(new IBLPrefilterContext(*FILA_CAST(Engine, engine)));
}

void FilaIBLPrefilterContext_destroy(FilaIBLPrefilterContext* context) {
    delete reinterpret_cast<IBLPrefilterContext*>(context);
}

// EquirectangularToCubemap
FilaIBLPrefilterEquirectangularToCubemap* FilaIBLPrefilterEquirectangularToCubemap_create(FilaIBLPrefilterContext* context) {
    return reinterpret_cast<FilaIBLPrefilterEquirectangularToCubemap*>(new IBLPrefilterContext::EquirectangularToCubemap(*reinterpret_cast<IBLPrefilterContext*>(context)));
}

void FilaIBLPrefilterEquirectangularToCubemap_destroy(FilaIBLPrefilterEquirectangularToCubemap* helper) {
    delete reinterpret_cast<IBLPrefilterContext::EquirectangularToCubemap*>(helper);
}

FilaTexture* FilaIBLPrefilterEquirectangularToCubemap_run(FilaIBLPrefilterEquirectangularToCubemap* helper, FilaTexture* equirect) {
    auto h = reinterpret_cast<IBLPrefilterContext::EquirectangularToCubemap*>(helper);
    auto t = reinterpret_cast<Texture*>(equirect);
    return reinterpret_cast<FilaTexture*>((*h)(t));
}

// SpecularFilter
FilaIBLPrefilterSpecularFilter* FilaIBLPrefilterSpecularFilter_create(FilaIBLPrefilterContext* context) {
    return reinterpret_cast<FilaIBLPrefilterSpecularFilter*>(new IBLPrefilterContext::SpecularFilter(*reinterpret_cast<IBLPrefilterContext*>(context)));
}

void FilaIBLPrefilterSpecularFilter_destroy(FilaIBLPrefilterSpecularFilter* helper) {
    delete reinterpret_cast<IBLPrefilterContext::SpecularFilter*>(helper);
}

FilaTexture* FilaIBLPrefilterSpecularFilter_run(FilaIBLPrefilterSpecularFilter* helper, FilaTexture* skybox) {
    auto h = reinterpret_cast<IBLPrefilterContext::SpecularFilter*>(helper);
    auto t = reinterpret_cast<Texture*>(skybox);
    return reinterpret_cast<FilaTexture*>((*h)(t));
}

} // extern "C"
