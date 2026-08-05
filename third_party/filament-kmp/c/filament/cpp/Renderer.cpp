#include <filament/Renderer.h>
#include <filament/Engine.h>
#include <filament/SwapChain.h>
#include <filament/View.h>
#include <filament/RenderTarget.h>
#include <filament/Viewport.h>
#include <backend/PixelBufferDescriptor.h>

#include "FilaCommon.h"
#include "../c/Renderer.h"

using namespace filament;
using namespace backend;
using namespace filament_c;

extern "C" {

void FilaRenderer_skipFrame(FilaRenderer* renderer, uint64_t vsyncSteadyClockTimeNano) {
    FILA_CAST(Renderer, renderer)->skipFrame(vsyncSteadyClockTimeNano);
}

bool FilaRenderer_shouldRenderFrame(const FilaRenderer* renderer) {
    return FILA_CONST_CAST(Renderer, renderer)->shouldRenderFrame();
}

bool FilaRenderer_beginFrame(FilaRenderer* renderer, FilaSwapChain* swapChain, uint64_t frameTimeNanos) {
    return FILA_CAST(Renderer, renderer)->beginFrame(FILA_CAST(SwapChain, swapChain), frameTimeNanos);
}

void FilaRenderer_endFrame(FilaRenderer* renderer) {
    FILA_CAST(Renderer, renderer)->endFrame();
}

void FilaRenderer_render(FilaRenderer* renderer, FilaView* view) {
    FILA_CAST(Renderer, renderer)->render(FILA_CAST(View, view));
}

void FilaRenderer_renderStandaloneView(FilaRenderer* renderer, FilaView* view) {
    FILA_CAST(Renderer, renderer)->renderStandaloneView(FILA_CAST(View, view));
}

void FilaRenderer_copyFrame(FilaRenderer* renderer, FilaSwapChain* dstSwapChain,
        int dstLeft, int dstBottom, int dstWidth, int dstHeight,
        int srcLeft, int srcBottom, int srcWidth, int srcHeight,
        uint32_t flags) {
    const filament::Viewport dstViewport{dstLeft, dstBottom, (uint32_t)dstWidth, (uint32_t)dstHeight};
    const filament::Viewport srcViewport{srcLeft, srcBottom, (uint32_t)srcWidth, (uint32_t)srcHeight};
    FILA_CAST(Renderer, renderer)->copyFrame(FILA_CAST(SwapChain, dstSwapChain), dstViewport, srcViewport, flags);
}

void FilaRenderer_readPixels(FilaRenderer* renderer,
        uint32_t xoffset, uint32_t yoffset, uint32_t width, uint32_t height,
        void* buffer, size_t sizeInBytes,
        FilaPixelDataFormat format, FilaPixelDataType type,
        uint8_t alignment, uint32_t left, uint32_t top, uint32_t stride,
        FilaCallbackHandler* handler, FilaBufferCallback callback, void* userData) {
    
    auto wrapper = new BufferCallbackWrapper{callback, userData};
    PixelBufferDescriptor desc(buffer, sizeInBytes, (PixelDataFormat)format, (PixelDataType)type, alignment, left, top, stride,
            reinterpret_cast<CallbackHandler*>(handler),
            bufferCallback, wrapper);
    FILA_CAST(Renderer, renderer)->readPixels(xoffset, yoffset, width, height, std::move(desc));
}

void FilaRenderer_readPixelsRenderTarget(FilaRenderer* renderer, FilaRenderTarget* renderTarget,
        uint32_t xoffset, uint32_t yoffset, uint32_t width, uint32_t height,
        void* buffer, size_t sizeInBytes,
        FilaPixelDataFormat format, FilaPixelDataType type,
        uint8_t alignment, uint32_t left, uint32_t top, uint32_t stride,
        FilaCallbackHandler* handler, FilaBufferCallback callback, void* userData) {
    
    auto wrapper = new BufferCallbackWrapper{callback, userData};
    PixelBufferDescriptor desc(buffer, sizeInBytes, (PixelDataFormat)format, (PixelDataType)type, alignment, left, top, stride,
            reinterpret_cast<CallbackHandler*>(handler),
            bufferCallback, wrapper);
    FILA_CAST(Renderer, renderer)->readPixels(FILA_CAST(RenderTarget, renderTarget), xoffset, yoffset, width, height, std::move(desc));
}

double FilaRenderer_getUserTime(const FilaRenderer* renderer) {
    return FILA_CONST_CAST(Renderer, renderer)->getUserTime();
}

void FilaRenderer_resetUserTime(FilaRenderer* renderer) {
    FILA_CAST(Renderer, renderer)->resetUserTime();
}

void FilaRenderer_setDisplayInfo(FilaRenderer* renderer, const FilaRendererDisplayInfo* info) {
    FILA_CAST(Renderer, renderer)->setDisplayInfo({ .refreshRate = info->refreshRate });
}

void FilaRenderer_setFrameRateOptions(FilaRenderer* renderer, const FilaRendererFrameRateOptions* options) {
    FILA_CAST(Renderer, renderer)->setFrameRateOptions({
        .headRoomRatio = options->headRoomRatio,
        .scaleRate = options->scaleRate,
        .history = options->history,
        .interval = (uint8_t)options->interval
    });
}

void FilaRenderer_setClearOptions(FilaRenderer* renderer, const FilaRendererClearOptions* options) {
    FILA_CAST(Renderer, renderer)->setClearOptions({
        .clearColor = {options->clearColor[0], options->clearColor[1], options->clearColor[2], options->clearColor[3]},
        .clear = options->clear,
        .discard = options->discard
    });
}

void FilaRenderer_getClearOptions(const FilaRenderer* renderer, FilaRendererClearOptions* out) {
    const Renderer::ClearOptions& opts = FILA_CONST_CAST(Renderer, renderer)->getClearOptions();
    out->clearColor[0] = opts.clearColor.r; out->clearColor[1] = opts.clearColor.g;
    out->clearColor[2] = opts.clearColor.b; out->clearColor[3] = opts.clearColor.a;
    out->clear = opts.clear;
    out->discard = opts.discard;
}

void FilaRenderer_setPresentationTime(FilaRenderer* renderer, uint64_t monotonicClockNanos) {
    FILA_CAST(Renderer, renderer)->setPresentationTime(monotonicClockNanos);
}

void FilaRenderer_setDesiredPresentationTime(FilaRenderer* renderer, int64_t monotonicClockNanos) {
    FILA_CAST(Renderer, renderer)->setDesiredPresentationTime(monotonicClockNanos);
}

void FilaRenderer_setRenderingDeadline(FilaRenderer* renderer, int64_t monotonicClockNanos) {
    FILA_CAST(Renderer, renderer)->setRenderingDeadline(monotonicClockNanos);
}

void FilaRenderer_setVsyncTime(FilaRenderer* renderer, uint64_t steadyClockTimeNano) {
    FILA_CAST(Renderer, renderer)->setVsyncTime(steadyClockTimeNano);
}

void FilaRenderer_skipNextFrames(FilaRenderer* renderer, uint32_t frameCount) {
    FILA_CAST(Renderer, renderer)->skipNextFrames(frameCount);
}

uint32_t FilaRenderer_getFrameToSkipCount(const FilaRenderer* renderer) {
    return FILA_CONST_CAST(Renderer, renderer)->getFrameToSkipCount();
}

} // extern "C"
