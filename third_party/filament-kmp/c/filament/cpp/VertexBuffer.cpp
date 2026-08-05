#include <filament/VertexBuffer.h>
#include <filament/Engine.h>
#include <filament/BufferObject.h>
#include <backend/BufferDescriptor.h>
#include <backend/DriverEnums.h>

#include "FilaCommon.h"
#include "../c/VertexBuffer.h"

using namespace filament;
using namespace backend;
using namespace filament_c;

extern "C" {

FilaVertexBufferBuilder* FilaVertexBufferBuilder_create() {
    return reinterpret_cast<FilaVertexBufferBuilder*>(new VertexBuffer::Builder());
}

void FilaVertexBufferBuilder_destroy(FilaVertexBufferBuilder* builder) {
    delete reinterpret_cast<VertexBuffer::Builder*>(builder);
}

FilaVertexBuffer* FilaVertexBufferBuilder_build(FilaVertexBufferBuilder* builder, FilaEngine* engine) {
    return reinterpret_cast<FilaVertexBuffer*>(FILA_CAST(VertexBuffer::Builder, builder)->build(*FILA_CAST(Engine, engine)));
}

void FilaVertexBufferBuilder_bufferCount(FilaVertexBufferBuilder* builder, uint8_t bufferCount) {
    FILA_CAST(VertexBuffer::Builder, builder)->bufferCount(bufferCount);
}

void FilaVertexBufferBuilder_vertexCount(FilaVertexBufferBuilder* builder, uint32_t vertexCount) {
    FILA_CAST(VertexBuffer::Builder, builder)->vertexCount(vertexCount);
}

void FilaVertexBufferBuilder_enableBufferObjects(FilaVertexBufferBuilder* builder, bool enabled) {
    FILA_CAST(VertexBuffer::Builder, builder)->enableBufferObjects(enabled);
}

void FilaVertexBufferBuilder_attribute(FilaVertexBufferBuilder* builder, FilaVertexAttribute attribute, uint8_t bufferIndex, FilaAttributeType attributeType, uint32_t byteOffset, uint8_t byteStride) {
    FILA_CAST(VertexBuffer::Builder, builder)->attribute(
        static_cast<VertexAttribute>(attribute), 
        bufferIndex, 
        static_cast<backend::ElementType>(attributeType), 
        byteOffset, 
        byteStride
    );
}

void FilaVertexBufferBuilder_normalized(FilaVertexBufferBuilder* builder, FilaVertexAttribute attribute, bool normalized) {
    FILA_CAST(VertexBuffer::Builder, builder)->normalized(static_cast<VertexAttribute>(attribute), normalized);
}

// VertexBuffer
size_t FilaVertexBuffer_getVertexCount(const FilaVertexBuffer* vertexBuffer) {
    return FILA_CONST_CAST(VertexBuffer, vertexBuffer)->getVertexCount();
}

void FilaVertexBuffer_setBufferAt(FilaVertexBuffer* vertexBuffer, FilaEngine* engine, uint8_t bufferIndex, void* buffer, size_t sizeInBytes, uint32_t destOffsetInBytes, FilaCallbackHandler* handler, FilaBufferCallback callback, void* userData) {
    auto wrapper = new BufferCallbackWrapper{callback, userData};
    BufferDescriptor desc(buffer, sizeInBytes, 
        reinterpret_cast<backend::CallbackHandler*>(handler),
        bufferCallback, wrapper);
    FILA_CAST(VertexBuffer, vertexBuffer)->setBufferAt(*FILA_CAST(Engine, engine), bufferIndex, std::move(desc), destOffsetInBytes);
}

void FilaVertexBuffer_setBufferObjectAt(FilaVertexBuffer* vertexBuffer, FilaEngine* engine, uint8_t bufferIndex, FilaBufferObject* bufferObject) {
    FILA_CAST(VertexBuffer, vertexBuffer)->setBufferObjectAt(*FILA_CAST(Engine, engine), bufferIndex, reinterpret_cast<BufferObject*>(bufferObject));
}

} // extern "C"
