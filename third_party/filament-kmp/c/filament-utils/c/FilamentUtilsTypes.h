#ifndef FILAMENT_UTILS_C_TYPES_H
#define FILAMENT_UTILS_C_TYPES_H

#include "../../filament/c/FilaTypes.h"

#ifdef __cplusplus
extern "C" {
#endif

// IBL prefilter opaque handles
typedef struct FilaIBLPrefilterContext FilaIBLPrefilterContext;
typedef struct FilaIBLPrefilterEquirectangularToCubemap FilaIBLPrefilterEquirectangularToCubemap;
typedef struct FilaIBLPrefilterSpecularFilter FilaIBLPrefilterSpecularFilter;

// Camera manipulator opaque handles
typedef struct FilaManipulator FilaManipulator;
typedef struct FilaManipulatorBuilder FilaManipulatorBuilder;
typedef struct FilaBookmark FilaBookmark;

#ifdef __cplusplus
}
#endif

#endif // FILAMENT_UTILS_C_TYPES_H
