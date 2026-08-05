#include <filament/SwapChain.h>
#include <filament/Engine.h>
#include <backend/CallbackHandler.h>

#include "FilaCommon.h"
#include "../c/SwapChain.h"

using namespace filament;

extern "C" {

void FilaSwapChain_setFrameCompletedCallback(FilaSwapChain* swapChain, FilaCallbackHandler* handler, FilaSwapChainFrameCompletedCallback callback, void* userData) {
    FILA_CAST(SwapChain, swapChain)->setFrameCompletedCallback(
        reinterpret_cast<backend::CallbackHandler*>(handler),
        [callback, userData](SwapChain* sc) {
            if (callback) {
                callback(reinterpret_cast<FilaSwapChain*>(sc), userData);
            }
        }
    );
}

bool FilaSwapChain_isSRGBSwapChainSupported(FilaEngine* engine) {
    return SwapChain::isSRGBSwapChainSupported(*FILA_CAST(Engine, engine));
}

bool FilaSwapChain_isMSAASwapChainSupported(FilaEngine* engine, int samples) {
    return SwapChain::isMSAASwapChainSupported(*FILA_CAST(Engine, engine), samples);
}

bool FilaSwapChain_isProtectedContentSupported(FilaEngine* engine) {
    return SwapChain::isProtectedContentSupported(*FILA_CAST(Engine, engine));
}

void FilaSwapChain_setFrameScheduledCallback(FilaSwapChain* swapChain, FilaCallbackHandler* handler, FilaSwapChainFrameScheduledCallback callback, void* userData) {
    FILA_CAST(SwapChain, swapChain)->setFrameScheduledCallback(
        reinterpret_cast<backend::CallbackHandler*>(handler),
        [callback, userData](backend::PresentCallable) {
            // Ignore PresentCallable, which is only meaningful with the Metal backend.
            if (callback) {
                callback(userData);
            }
        }
    );
}

bool FilaSwapChain_isFrameScheduledCallbackSet(const FilaSwapChain* swapChain) {
    return FILA_CONST_CAST(SwapChain, swapChain)->isFrameScheduledCallbackSet();
}

bool FilaSwapChain_isFrameRateChangeSupported(const FilaSwapChain* swapChain) {
    return FILA_CONST_CAST(SwapChain, swapChain)->isFrameRateChangeSupported().is_true();
}

void FilaSwapChain_setFrameRate(FilaSwapChain* swapChain, float frameRate, uint8_t compatibility, uint8_t strategy) {
    FILA_CAST(SwapChain, swapChain)->setFrameRate(frameRate,
            static_cast<SwapChain::FrameRateCompatibility>(compatibility),
            static_cast<SwapChain::ChangeFrameRateStrategy>(strategy));
}

} // extern "C"
