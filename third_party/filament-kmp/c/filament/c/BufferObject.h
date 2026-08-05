#ifndef FILAMENT_C_BUFFER_OBJECT_H
#define FILAMENT_C_BUFFER_OBJECT_H

#include "FilaTypes.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef enum FilaBufferObjectBindingType {
    FILA_BUFFER_OBJECT_BINDING_TYPE_VERTEX = 0,
    FILA_BUFFER_OBJECT_BINDING_TYPE_UNIFORM = 1,
    FILA_BUFFER_OBJECT_BINDING_TYPE_SHADER_STORAGE = 2,
} FilaBufferObjectBindingType;

// Builder
typedef struct FilaBufferObjectBuilder FilaBufferObjectBuilder;

FilaBufferObjectBuilder* FilaBufferObjectBuilder_create(void);
void FilaBufferObjectBuilder_destroy(FilaBufferObjectBuilder* builder);
FilaBufferObject* FilaBufferObjectBuilder_build(FilaBufferObjectBuilder* builder, FilaEngine* engine);

void FilaBufferObjectBuilder_size(FilaBufferObjectBuilder* builder, uint32_t byteCount);
void FilaBufferObjectBuilder_bindingType(FilaBufferObjectBuilder* builder, FilaBufferObjectBindingType bindingType);

// BufferObject
size_t FilaBufferObject_getByteCount(const FilaBufferObject* bufferObject);
void FilaBufferObject_setBuffer(FilaBufferObject* bufferObject, FilaEngine* engine, void* buffer, size_t sizeInBytes, uint32_t destOffsetInBytes, FilaCallbackHandler* handler, FilaBufferCallback callback, void* userData);

#ifdef __cplusplus
}
#endif

#endif // FILAMENT_C_BUFFER_OBJECT_H
