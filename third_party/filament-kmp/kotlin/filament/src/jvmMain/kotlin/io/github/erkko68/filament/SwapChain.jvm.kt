package io.github.erkko68.filament

import io.github.erkko68.filament.ffm.FilamentC
import io.github.erkko68.filament.ffm.FilaSwapChainFrameCompletedCallback
import io.github.erkko68.filament.ffm.FilaSwapChainFrameScheduledCallback
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment

actual class SwapChain internal constructor(internal var nativeHandle: MemorySegment?) {
    actual enum class FrameRateCompatibility { DEFAULT, FIXED_SOURCE }
    actual enum class ChangeFrameRateStrategy { ONLY_IF_SEAMLESS, ALWAYS }

    actual companion object {
        actual fun isProtectedContentSupported(engine: Engine): Boolean = FilamentC.FilaSwapChain_isProtectedContentSupported(engine.nativeHandle)
        actual fun isSRGBSwapChainSupported(engine: Engine): Boolean = FilamentC.FilaSwapChain_isSRGBSwapChainSupported(engine.nativeHandle)
        actual fun isMSAASwapChainSupported(engine: Engine, samples: Int): Boolean = FilamentC.FilaSwapChain_isMSAASwapChainSupported(engine.nativeHandle, samples)
    }

    actual val nativeWindow: Any? get() = null

    // Persistent upcall stubs: the arena must outlive the swapchain, so it is replaced (and the
    // old one closed) on each re-set. The lambda is captured directly — userData stays NULL.
    private var frameCompletedArena: Arena? = null
    private var frameScheduledArena: Arena? = null

    actual fun setFrameCompletedCallback(callback: () -> Unit) {
        frameCompletedArena?.close()
        val arena = Arena.ofShared()
        frameCompletedArena = arena
        val cb = FilaSwapChainFrameCompletedCallback.allocate({ _, _ -> callback() }, arena)
        FilamentC.FilaSwapChain_setFrameCompletedCallback(nativeHandle, NULL, cb, NULL)
    }

    actual fun setFrameScheduledCallback(callback: () -> Unit) {
        frameScheduledArena?.close()
        val arena = Arena.ofShared()
        frameScheduledArena = arena
        val cb = FilaSwapChainFrameScheduledCallback.allocate({ _ -> callback() }, arena)
        FilamentC.FilaSwapChain_setFrameScheduledCallback(nativeHandle, NULL, cb, NULL)
    }

    actual val isFrameScheduledCallbackSet: Boolean get() = FilamentC.FilaSwapChain_isFrameScheduledCallbackSet(nativeHandle)

    actual fun isFrameRateChangeSupported(): Boolean = FilamentC.FilaSwapChain_isFrameRateChangeSupported(nativeHandle)

    actual fun setFrameRate(frameRate: Float) =
        setFrameRate(frameRate, FrameRateCompatibility.DEFAULT, ChangeFrameRateStrategy.ONLY_IF_SEAMLESS)

    actual fun setFrameRate(frameRate: Float, compatibility: FrameRateCompatibility, strategy: ChangeFrameRateStrategy) {
        FilamentC.FilaSwapChain_setFrameRate(nativeHandle, frameRate, compatibility.ordinal.toByte(), strategy.ordinal.toByte())
    }

    actual val nativeObject: Long get() = nativeHandle?.address() ?: 0L
}
