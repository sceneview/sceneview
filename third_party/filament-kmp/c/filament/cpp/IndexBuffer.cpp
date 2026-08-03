#include <filament/IndexBuffer.h>
#include <filament/Engine.h>
#include <backend/BufferDescriptor.h>

#include "FilaCommon.h"
#include "../c/IndexBuffer.h"

using namespace filament;
using namespace backend;
using namespace filament_c;

extern "C" {

FilaIndexBufferBuilder* FilaIndexBufferBuilder_create() {
    return reinterpret_cast<FilaIndexBufferBuilder*>(new IndexBuffer::Builder());
}

void FilaIndexBufferBuilder_destroy(FilaIndexBufferBuilder* builder) {
    delete reinterpret_cast<IndexBuffer::Builder*>(builder);
}

FilaIndexBuffer* FilaIndexBufferBuilder_build(FilaIndexBufferBuilder* builder, FilaEngine* engine) {
    return reinterpret_cast<FilaIndexBuffer*>(FILA_CAST(IndexBuffer::Builder, builder)->build(*FILA_CAST(Engine, engine)));
}

void FilaIndexBufferBuilder_indexCount(FilaIndexBufferBuilder* builder, uint32_t indexCount) {
    FILA_CAST(IndexBuffer::Builder, builder)->indexCount(indexCount);
}

void FilaIndexBufferBuilder_bufferType(FilaIndexBufferBuilder* builder, FilaIndexBufferType indexType) {
    IndexBuffer::IndexType type = (indexType == FILA_INDEX_BUFFER_TYPE_UINT) ? 
        IndexBuffer::IndexType::UINT : IndexBuffer::IndexType::USHORT;
    FILA_CAST(IndexBuffer::Builder, builder)->bufferType(type);
}

// IndexBuffer
size_t FilaIndexBuffer_getIndexCount(const FilaIndexBuffer* indexBuffer) {
    return FILA_CONST_CAST(IndexBuffer, indexBuffer)->getIndexCount();
}

void FilaIndexBuffer_setBuffer(FilaIndexBuffer* indexBuffer, FilaEngine* engine, void* buffer, size_t sizeInBytes, uint32_t destOffsetInBytes, FilaCallbackHandler* handler, FilaBufferCallback callback, void* userData) {
    auto wrapper = new BufferCallbackWrapper{callback, userData};
    BufferDescriptor desc(buffer, sizeInBytes, 
        reinterpret_cast<backend::CallbackHandler*>(handler),
        bufferCallback, wrapper);
    FILA_CAST(IndexBuffer, indexBuffer)->setBuffer(*FILA_CAST(Engine, engine), std::move(desc), destOffsetInBytes);
}

} // extern "C"
