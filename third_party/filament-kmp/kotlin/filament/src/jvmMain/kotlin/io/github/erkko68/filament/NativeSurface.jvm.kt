package io.github.erkko68.filament

import java.lang.foreign.MemorySegment

/**
 * JVM implementation of NativeSurface. Wraps a raw native-window/layer pointer (passed as a
 * Long address, or a MemorySegment) handed to `FilaEngine_createSwapChain` as a `void*`.
 */
actual class NativeSurface(val nativeWindow: Any) {
    internal val handle: MemorySegment
        get() = when (nativeWindow) {
            is MemorySegment -> nativeWindow
            is Long -> if (nativeWindow == 0L) NULL else MemorySegment.ofAddress(nativeWindow)
            is Int -> if (nativeWindow == 0) NULL else MemorySegment.ofAddress(nativeWindow.toLong())
            else -> NULL
        }
}
