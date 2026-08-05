#ifndef FILAMENT_C_FENCE_H
#define FILAMENT_C_FENCE_H

#include "FilaTypes.h"

#ifdef __cplusplus
extern "C" {
#endif

// Matches filament::backend::FenceStatus
typedef enum FilaFenceStatus {
    FILA_FENCE_STATUS_ALREADY_SIGNALED = 0,
    FILA_FENCE_STATUS_TIMEOUT_EXPIRED = 1,
    FILA_FENCE_STATUS_CONDITION_SATISFIED = 2,
    FILA_FENCE_STATUS_ERROR = -1,
} FilaFenceStatus;

// Matches filament::Fence::Mode
typedef enum FilaFenceMode {
    FILA_FENCE_MODE_FLUSH = 0,
    FILA_FENCE_MODE_DONT_FLUSH = 1,
} FilaFenceMode;

#define FILA_FENCE_WAIT_FOR_EVER 0xFFFFFFFFFFFFFFFFULL

// Fence
FilaFenceStatus FilaFence_wait(FilaFence* fence, FilaFenceMode mode, uint64_t timeoutNanoSeconds);
FilaFenceStatus FilaFence_waitAndDestroy(FilaFence* fence, FilaFenceMode mode);

#ifdef __cplusplus
}
#endif

#endif // FILAMENT_C_FENCE_H
