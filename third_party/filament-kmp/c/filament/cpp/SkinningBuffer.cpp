#include <filament/SkinningBuffer.h>
#include <filament/Engine.h>
#include <math/mat4.h>

#include "FilaCommon.h"
#include "../c/SkinningBuffer.h"

using namespace filament;

extern "C" {

FilaSkinningBufferBuilder* FilaSkinningBufferBuilder_create() {
    return reinterpret_cast<FilaSkinningBufferBuilder*>(new SkinningBuffer::Builder());
}

void FilaSkinningBufferBuilder_destroy(FilaSkinningBufferBuilder* builder) {
    delete reinterpret_cast<SkinningBuffer::Builder*>(builder);
}

FilaSkinningBuffer* FilaSkinningBufferBuilder_build(FilaSkinningBufferBuilder* builder, FilaEngine* engine) {
    return reinterpret_cast<FilaSkinningBuffer*>(FILA_CAST(SkinningBuffer::Builder, builder)->build(*FILA_CAST(Engine, engine)));
}

void FilaSkinningBufferBuilder_boneCount(FilaSkinningBufferBuilder* builder, uint32_t boneCount) {
    FILA_CAST(SkinningBuffer::Builder, builder)->boneCount(boneCount);
}

void FilaSkinningBufferBuilder_initialize(FilaSkinningBufferBuilder* builder, bool initialize) {
    FILA_CAST(SkinningBuffer::Builder, builder)->initialize(initialize);
}

// SkinningBuffer instance methods
size_t FilaSkinningBuffer_getBoneCount(const FilaSkinningBuffer* buffer) {
    return FILA_CONST_CAST(SkinningBuffer, buffer)->getBoneCount();
}

void FilaSkinningBuffer_setBonesMat4f(FilaSkinningBuffer* buffer, FilaEngine* engine, const float* matrices, size_t boneCount, size_t offset) {
    FILA_CAST(SkinningBuffer, buffer)->setBones(*FILA_CAST(Engine, engine), 
        reinterpret_cast<const math::mat4f*>(matrices), boneCount, offset);
}

void FilaSkinningBuffer_setBonesQuaternions(FilaSkinningBuffer* buffer, FilaEngine* engine, const FilaBone* bones, size_t boneCount, size_t offset) {
    FILA_CAST(SkinningBuffer, buffer)->setBones(*FILA_CAST(Engine, engine), 
        reinterpret_cast<const RenderableManager::Bone*>(bones), boneCount, offset);
}

} // extern "C"

// Layout guard: FilaBone is reinterpret_cast onto filament::RenderableManager::Bone
// here and in RenderableManager.cpp. Keep layouts identical.
#include <filament/RenderableManager.h>
static_assert(sizeof(FilaBone) == sizeof(filament::RenderableManager::Bone), "Bone size mismatch");
