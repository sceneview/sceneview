package io.github.erkko68.filament

import io.github.erkko68.filament.ffm.FilamentC
import io.github.erkko68.filament.ffm.FilaRendererClearOptions
import io.github.erkko68.filament.ffm.FilaRendererDisplayInfo
import io.github.erkko68.filament.ffm.FilaRendererFrameRateOptions
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

actual class Renderer constructor(private val engineRef: Engine, public var nativeHandle: MemorySegment?) {
    actual class DisplayInfo actual constructor() {
        actual var refreshRate: Float = 60.0f
    }

    actual class FrameRateOptions actual constructor() {
        actual var interval: Float = 1.0f
        actual var headRoomRatio: Float = 0.0f
        actual var scaleRate: Float = 1.0f / 15.0f
        actual var history: Int = 15
    }

    actual class ClearOptions actual constructor() {
        actual var clearColor: DoubleArray = doubleArrayOf(0.0, 0.0, 0.0, 0.0)
        actual var clear: Boolean = false
        actual var discard: Boolean = true
    }

    actual companion object {
        actual val MIRROR_FRAME_FLAG_COMMIT: Int = 0x1
        actual val MIRROR_FRAME_FLAG_SET_PRESENTATION_TIME: Int = 0x2
        actual val MIRROR_FRAME_FLAG_CLEAR: Int = 0x4
    }

    actual val engine: Engine get() = engineRef

    private var _displayInfo = DisplayInfo()
    actual var displayInfo: DisplayInfo
        get() = _displayInfo
        set(value) {
            _displayInfo = value
            confined { arena ->
                val c = FilaRendererDisplayInfo.allocate(arena)
                FilaRendererDisplayInfo.refreshRate(c, value.refreshRate)
                FilamentC.FilaRenderer_setDisplayInfo(nativeHandle, c)
            }
        }

    private var _frameRateOptions = FrameRateOptions()
    actual var frameRateOptions: FrameRateOptions
        get() = _frameRateOptions
        set(value) {
            _frameRateOptions = value
            confined { arena ->
                val c = FilaRendererFrameRateOptions.allocate(arena)
                FilaRendererFrameRateOptions.interval(c, value.interval)
                FilaRendererFrameRateOptions.headRoomRatio(c, value.headRoomRatio)
                FilaRendererFrameRateOptions.scaleRate(c, value.scaleRate)
                FilaRendererFrameRateOptions.history(c, value.history.toByte())
                FilamentC.FilaRenderer_setFrameRateOptions(nativeHandle, c)
            }
        }

    actual var clearOptions: ClearOptions
        get() = confined { arena ->
            val out = FilaRendererClearOptions.allocate(arena)
            FilamentC.FilaRenderer_getClearOptions(nativeHandle, out)
            val cc = FilaRendererClearOptions.clearColor(out)
            ClearOptions().apply {
                // Filament 1.71.5: ClearOptions.clearColor is double[4] in the C wrapper too.
                clearColor = DoubleArray(4) { cc.getAtIndex(ValueLayout.JAVA_DOUBLE, it.toLong()) }
                clear = FilaRendererClearOptions.clear(out)
                discard = FilaRendererClearOptions.discard(out)
            }
        }
        set(value) {
            confined { arena ->
                val c = FilaRendererClearOptions.allocate(arena)
                val cc = FilaRendererClearOptions.clearColor(c)
                for (i in 0 until 4.coerceAtMost(value.clearColor.size)) cc.setAtIndex(ValueLayout.JAVA_DOUBLE, i.toLong(), value.clearColor[i])
                FilaRendererClearOptions.clear(c, value.clear)
                FilaRendererClearOptions.discard(c, value.discard)
                FilamentC.FilaRenderer_setClearOptions(nativeHandle, c)
            }
        }

    actual fun setPresentationTime(monotonicClockNanos: Long) = FilamentC.FilaRenderer_setPresentationTime(nativeHandle, monotonicClockNanos)
    actual fun setDesiredPresentationTime(monotonicClockNanos: Long) = FilamentC.FilaRenderer_setDesiredPresentationTime(nativeHandle, monotonicClockNanos)
    actual fun setRenderingDeadline(monotonicClockNanos: Long) = FilamentC.FilaRenderer_setRenderingDeadline(nativeHandle, monotonicClockNanos)
    actual fun setVsyncTime(steadyClockTimeNano: Long) = FilamentC.FilaRenderer_setVsyncTime(nativeHandle, steadyClockTimeNano)
    actual fun skipFrame(vsyncSteadyClockTimeNano: Long) = FilamentC.FilaRenderer_skipFrame(nativeHandle, vsyncSteadyClockTimeNano)
    actual fun shouldRenderFrame(): Boolean = FilamentC.FilaRenderer_shouldRenderFrame(nativeHandle)
    actual fun beginFrame(swapChain: SwapChain, frameTimeNanos: Long): Boolean = FilamentC.FilaRenderer_beginFrame(nativeHandle, swapChain.nativeHandle, frameTimeNanos)
    actual fun endFrame() = FilamentC.FilaRenderer_endFrame(nativeHandle)
    actual fun render(view: View) = FilamentC.FilaRenderer_render(nativeHandle, view.nativeHandle)
    actual fun renderStandaloneView(view: View) = FilamentC.FilaRenderer_renderStandaloneView(nativeHandle, view.nativeHandle)
    @PlatformGap(platforms = [FilamentPlatform.WEB], behavior = "silent no-op — Renderer.copyFrame is not bound in filament.js.")
    actual fun copyFrame(dstSwapChain: SwapChain, dstViewport: Viewport, srcViewport: Viewport, flags: Int) {
        FilamentC.FilaRenderer_copyFrame(nativeHandle, dstSwapChain.nativeHandle,
            dstViewport.left, dstViewport.bottom, dstViewport.width, dstViewport.height,
            srcViewport.left, srcViewport.bottom, srcViewport.width, srcViewport.height,
            flags)
    }

    // readPixels writes pixels into an off-heap buffer asynchronously; on completion we copy them
    // back into the caller's ByteArray, invoke the callback, and free the buffer.
    private fun readPixelsInto(buffer: Texture.PixelBufferDescriptor): Pair<MemorySegment, MemorySegment> {
        val dataArena = Arena.ofShared()
        val seg = dataArena.allocate(buffer.storage.size.toLong())
        val userData = Completions.register {
            try {
                MemorySegment.copy(seg, ValueLayout.JAVA_BYTE, 0L, buffer.storage, 0, buffer.storage.size)
                buffer.callback?.invoke()
            } finally {
                dataArena.close()
            }
        }
        return seg to userData
    }

    @PlatformGap(platforms = [FilamentPlatform.WEB], behavior = "silent no-op — Renderer.readPixels is not bound in filament.js.")
    actual fun readPixels(xoffset: Int, yoffset: Int, width: Int, height: Int, buffer: Texture.PixelBufferDescriptor) {
        val (seg, userData) = readPixelsInto(buffer)
        FilamentC.FilaRenderer_readPixels(
            nativeHandle,
            xoffset, yoffset, width, height,
            seg, buffer.sizeInBytes.toLong(),
            buffer.format.toNative(), buffer.type.toNative(),
            buffer.alignment.toByte(), buffer.left, buffer.top, buffer.stride,
            NULL, Completions.bufferStub, userData
        )
    }

    @PlatformGap(platforms = [FilamentPlatform.WEB], behavior = "silent no-op — Renderer.readPixels is not bound in filament.js.")
    actual fun readPixels(renderTarget: RenderTarget, xoffset: Int, yoffset: Int, width: Int, height: Int, buffer: Texture.PixelBufferDescriptor) {
        val (seg, userData) = readPixelsInto(buffer)
        FilamentC.FilaRenderer_readPixelsRenderTarget(
            nativeHandle, renderTarget.nativeHandle,
            xoffset, yoffset, width, height,
            seg, buffer.sizeInBytes.toLong(),
            buffer.format.toNative(), buffer.type.toNative(),
            buffer.alignment.toByte(), buffer.left, buffer.top, buffer.stride,
            NULL, Completions.bufferStub, userData
        )
    }

    actual val userTime: Double get() = FilamentC.FilaRenderer_getUserTime(nativeHandle)
    actual fun resetUserTime() = FilamentC.FilaRenderer_resetUserTime(nativeHandle)
    actual fun skipNextFrames(frameCount: Int) = FilamentC.FilaRenderer_skipNextFrames(nativeHandle, frameCount)
    actual val frameToSkipCount: Int get() = FilamentC.FilaRenderer_getFrameToSkipCount(nativeHandle)
}
