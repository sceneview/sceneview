#ifndef FILAMENT_CPP_COMMON_H
#define FILAMENT_CPP_COMMON_H

#include <filament/Engine.h>
#include <filament/Camera.h>
#include <filament/Renderer.h>
#include <filament/Scene.h>
#include <filament/View.h>
#include <filament/Viewport.h>
#include <filament/SwapChain.h>
#include <filament/IndirectLight.h>
#include <filament/Skybox.h>
#include <filament/ColorGrading.h>
#include <filament/RenderTarget.h>
#include <filament/VertexBuffer.h>
#include <filament/IndexBuffer.h>
#include <filament/BufferObject.h>
#include <filament/Texture.h>
#include <filament/Stream.h>
#include <filament/Material.h>
#include <filament/MaterialInstance.h>
#include <filament/RenderableManager.h>
#include <filament/TransformManager.h>
#include <filament/LightManager.h>
#include <filament/Fence.h>
#include <filament/SkinningBuffer.h>
#include <filament/MorphTargetBuffer.h>
#include <filament/ToneMapper.h>
#include <utils/EntityManager.h>
#include <utils/Entity.h>

#include "../c/FilaTypes.h"

// Helper macros for casting to/from opaque pointers
#define FILA_CAST(type, ptr) reinterpret_cast<filament::type*>(ptr)
#define FILA_CONST_CAST(type, ptr) reinterpret_cast<const filament::type*>(ptr)

#define UTILS_CAST(type, ptr) reinterpret_cast<utils::type*>(ptr)
#define UTILS_CONST_CAST(type, ptr) reinterpret_cast<const utils::type*>(ptr)

namespace filament_c {
    // One-shot wrapper for FilaBufferCallback (buffer/pixel uploads, readPixels):
    // bridges the (buffer, size, userData) C callback and frees itself after firing.
    struct BufferCallbackWrapper {
        FilaBufferCallback callback;
        void* userData;
    };

    inline void bufferCallback(void* buffer, size_t size, void* user) {
        auto wrapper = reinterpret_cast<BufferCallbackWrapper*>(user);
        if (wrapper->callback) {
            wrapper->callback(buffer, size, wrapper->userData);
        }
        delete wrapper;
    }

    struct StreamCallbackWrapper {
        FilaStreamCallback callback;
        void* userData;
    };

    inline void streamCallback(void* image, void* user) {
        auto wrapper = reinterpret_cast<StreamCallbackWrapper*>(user);
        if (wrapper->callback) {
            wrapper->callback(image, wrapper->userData);
        }
        delete wrapper;
    }
}

#endif // FILAMENT_CPP_COMMON_H
