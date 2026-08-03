#include <filament/BufferObject.h>
#include <filament/Engine.h>
#include <backend/BufferDescriptor.h>

#include "FilaCommon.h"
#include "../c/BufferObject.h"

using namespace filament;
using namespace backend;
using namespace filament_c;

extern "C" {

FilaBufferObjectBuilder* FilaBufferObjectBuilder_create() {
    return reinterpret_cast<FilaBufferObjectBuilder*>(new BufferObject::Builder());
}

void FilaBufferObjectBuilder_destroy(FilaBufferObjectBuilder* builder) {
    delete reinterpret_cast<BufferObject::Builder*>(builder);
}

FilaBufferObject* FilaBufferObjectBuilder_build(FilaBufferObjectBuilder* builder, FilaEngine* engine) {
    return reinterpret_cast<FilaBufferObject*>(FILA_CAST(BufferObject::Builder, builder)->build(*FILA_CAST(Engine, engine)));
}

void FilaBufferObjectBuilder_size(FilaBufferObjectBuilder* builder, uint32_t byteCount) {
    FILA_CAST(BufferObject::Builder, builder)->size(byteCount);
}

void FilaBufferObjectBuilder_bindingType(FilaBufferObjectBuilder* builder, FilaBufferObjectBindingType bindingType) {
    FILA_CAST(BufferObject::Builder, builder)->bindingType(static_cast<BufferObject::BindingType>(bindingType));
}

// BufferObject
size_t FilaBufferObject_getByteCount(const FilaBufferObject* bufferObject) {
    return FILA_CONST_CAST(BufferObject, bufferObject)->getByteCount();
}

void FilaBufferObject_setBuffer(FilaBufferObject* bufferObject, FilaEngine* engine, void* buffer, size_t sizeInBytes, uint32_t destOffsetInBytes, FilaCallbackHandler* handler, FilaBufferCallback callback, void* userData) {
    auto wrapper = new BufferCallbackWrapper{callback, userData};
    BufferDescriptor desc(buffer, sizeInBytes, 
        reinterpret_cast<backend::CallbackHandler*>(handler),
        bufferCallback, wrapper);
    FILA_CAST(BufferObject, bufferObject)->setBuffer(*FILA_CAST(Engine, engine), std::move(desc), destOffsetInBytes);
}

} // extern "C"
