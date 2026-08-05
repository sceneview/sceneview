#include <filament/Fence.h>
#include <backend/DriverEnums.h>

#include "FilaCommon.h"
#include "../c/Fence.h"

using namespace filament;

extern "C" {

FilaFenceStatus FilaFence_wait(FilaFence* fence, FilaFenceMode mode, uint64_t timeoutNanoSeconds) {
    return static_cast<FilaFenceStatus>(FILA_CAST(Fence, fence)->wait(
        mode == FILA_FENCE_MODE_FLUSH ? Fence::Mode::FLUSH : Fence::Mode::DONT_FLUSH,
        timeoutNanoSeconds
    ));
}

FilaFenceStatus FilaFence_waitAndDestroy(FilaFence* fence, FilaFenceMode mode) {
    return static_cast<FilaFenceStatus>(Fence::waitAndDestroy(
        FILA_CAST(Fence, fence),
        mode == FILA_FENCE_MODE_FLUSH ? Fence::Mode::FLUSH : Fence::Mode::DONT_FLUSH
    ));
}

} // extern "C"
