package io.github.erkko68.filament

import io.github.erkko68.filament.ffm.FilamentC
import java.lang.foreign.MemorySegment

@PlatformGap(platforms = [FilamentPlatform.WEB], behavior = "unusable — Engine.createFence throws; filament.js exposes no GPU/CPU fence API.")
actual class Fence internal constructor(internal var nativeHandle: MemorySegment?) {
    actual enum class Mode { FLUSH, DONT_FLUSH }
    actual enum class FenceStatus { ERROR, ALREADY_SIGNALED, TIMEOUT_EXPIRED, CONDITION_SATISFIED }

    actual fun wait(mode: Mode, timeout: Long): FenceStatus {
        val result = FilamentC.FilaFence_wait(nativeHandle, mode.ordinal, timeout)
        return FenceStatus.values()[result + 1] // ERROR is -1, ordinal 0
    }

    actual val nativeObject: Long get() = nativeHandle?.address() ?: 0L

    actual companion object {
        actual fun waitAndDestroy(fence: Fence, mode: Mode): FenceStatus {
            val result = FilamentC.FilaFence_waitAndDestroy(fence.nativeHandle, mode.ordinal)
            fence.nativeHandle = null
            return FenceStatus.values()[result + 1]
        }
    }
}
