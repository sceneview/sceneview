#ifndef FILAMENT_C_INDEX_BUFFER_H
#define FILAMENT_C_INDEX_BUFFER_H

#include "FilaTypes.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef enum FilaIndexBufferType {
    FILA_INDEX_BUFFER_TYPE_USHORT = 0,
    FILA_INDEX_BUFFER_TYPE_UINT = 1,
} FilaIndexBufferType;

// Builder
typedef struct FilaIndexBufferBuilder FilaIndexBufferBuilder;

FilaIndexBufferBuilder* FilaIndexBufferBuilder_create(void);
void FilaIndexBufferBuilder_destroy(FilaIndexBufferBuilder* builder);
FilaIndexBuffer* FilaIndexBufferBuilder_build(FilaIndexBufferBuilder* builder, FilaEngine* engine);

void FilaIndexBufferBuilder_indexCount(FilaIndexBufferBuilder* builder, uint32_t indexCount);
void FilaIndexBufferBuilder_bufferType(FilaIndexBufferBuilder* builder, FilaIndexBufferType indexType);

// IndexBuffer
size_t FilaIndexBuffer_getIndexCount(const FilaIndexBuffer* indexBuffer);
void FilaIndexBuffer_setBuffer(FilaIndexBuffer* indexBuffer, FilaEngine* engine, void* buffer, size_t sizeInBytes, uint32_t destOffsetInBytes, FilaCallbackHandler* handler, FilaBufferCallback callback, void* userData);

#ifdef __cplusplus
}
#endif

#endif // FILAMENT_C_INDEX_BUFFER_H
