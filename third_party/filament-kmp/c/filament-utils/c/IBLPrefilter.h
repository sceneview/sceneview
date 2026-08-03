#ifndef FILAMENT_UTILS_C_IBL_PREFILTER_H
#define FILAMENT_UTILS_C_IBL_PREFILTER_H

#include "FilamentUtilsTypes.h"

#ifdef __cplusplus
extern "C" {
#endif

// IBLPrefilterContext
FilaIBLPrefilterContext* FilaIBLPrefilterContext_create(FilaEngine* engine);
void FilaIBLPrefilterContext_destroy(FilaIBLPrefilterContext* context);

// EquirectangularToCubemap
FilaIBLPrefilterEquirectangularToCubemap* FilaIBLPrefilterEquirectangularToCubemap_create(FilaIBLPrefilterContext* context);
void FilaIBLPrefilterEquirectangularToCubemap_destroy(FilaIBLPrefilterEquirectangularToCubemap* helper);
FilaTexture* FilaIBLPrefilterEquirectangularToCubemap_run(FilaIBLPrefilterEquirectangularToCubemap* helper, FilaTexture* equirect);

// SpecularFilter
FilaIBLPrefilterSpecularFilter* FilaIBLPrefilterSpecularFilter_create(FilaIBLPrefilterContext* context);
void FilaIBLPrefilterSpecularFilter_destroy(FilaIBLPrefilterSpecularFilter* helper);
FilaTexture* FilaIBLPrefilterSpecularFilter_run(FilaIBLPrefilterSpecularFilter* helper, FilaTexture* skybox);

#ifdef __cplusplus
}
#endif

#endif // FILAMENT_UTILS_C_IBL_PREFILTER_H
