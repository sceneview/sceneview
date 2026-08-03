#ifndef FILAMENT_C_VERTEX_BUFFER_H
#define FILAMENT_C_VERTEX_BUFFER_H

#include "FilaTypes.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef enum FilaAttributeType {
    FILA_ATTRIBUTE_TYPE_BYTE = 0,
    FILA_ATTRIBUTE_TYPE_BYTE2 = 1,
    FILA_ATTRIBUTE_TYPE_BYTE3 = 2,
    FILA_ATTRIBUTE_TYPE_BYTE4 = 3,
    FILA_ATTRIBUTE_TYPE_UBYTE = 4,
    FILA_ATTRIBUTE_TYPE_UBYTE2 = 5,
    FILA_ATTRIBUTE_TYPE_UBYTE3 = 6,
    FILA_ATTRIBUTE_TYPE_UBYTE4 = 7,
    FILA_ATTRIBUTE_TYPE_SHORT = 8,
    FILA_ATTRIBUTE_TYPE_SHORT2 = 9,
    FILA_ATTRIBUTE_TYPE_SHORT3 = 10,
    FILA_ATTRIBUTE_TYPE_SHORT4 = 11,
    FILA_ATTRIBUTE_TYPE_USHORT = 12,
    FILA_ATTRIBUTE_TYPE_USHORT2 = 13,
    FILA_ATTRIBUTE_TYPE_USHORT3 = 14,
    FILA_ATTRIBUTE_TYPE_USHORT4 = 15,
    FILA_ATTRIBUTE_TYPE_INT = 16,
    FILA_ATTRIBUTE_TYPE_UINT = 17,
    FILA_ATTRIBUTE_TYPE_FLOAT = 18,
    FILA_ATTRIBUTE_TYPE_FLOAT2 = 19,
    FILA_ATTRIBUTE_TYPE_FLOAT3 = 20,
    FILA_ATTRIBUTE_TYPE_FLOAT4 = 21,
    FILA_ATTRIBUTE_TYPE_HALF = 22,
    FILA_ATTRIBUTE_TYPE_HALF2 = 23,
    FILA_ATTRIBUTE_TYPE_HALF3 = 24,
    FILA_ATTRIBUTE_TYPE_HALF4 = 25,
} FilaAttributeType;

// Builder
typedef struct FilaVertexBufferBuilder FilaVertexBufferBuilder;

FilaVertexBufferBuilder* FilaVertexBufferBuilder_create(void);
void FilaVertexBufferBuilder_destroy(FilaVertexBufferBuilder* builder);
FilaVertexBuffer* FilaVertexBufferBuilder_build(FilaVertexBufferBuilder* builder, FilaEngine* engine);

void FilaVertexBufferBuilder_bufferCount(FilaVertexBufferBuilder* builder, uint8_t bufferCount);
void FilaVertexBufferBuilder_vertexCount(FilaVertexBufferBuilder* builder, uint32_t vertexCount);
void FilaVertexBufferBuilder_enableBufferObjects(FilaVertexBufferBuilder* builder, bool enabled);
void FilaVertexBufferBuilder_attribute(FilaVertexBufferBuilder* builder, FilaVertexAttribute attribute, uint8_t bufferIndex, FilaAttributeType attributeType, uint32_t byteOffset, uint8_t byteStride);
void FilaVertexBufferBuilder_normalized(FilaVertexBufferBuilder* builder, FilaVertexAttribute attribute, bool normalized);

// VertexBuffer
size_t FilaVertexBuffer_getVertexCount(const FilaVertexBuffer* vertexBuffer);
void FilaVertexBuffer_setBufferAt(FilaVertexBuffer* vertexBuffer, FilaEngine* engine, uint8_t bufferIndex, void* buffer, size_t sizeInBytes, uint32_t destOffsetInBytes, FilaCallbackHandler* handler, FilaBufferCallback callback, void* userData);
void FilaVertexBuffer_setBufferObjectAt(FilaVertexBuffer* vertexBuffer, FilaEngine* engine, uint8_t bufferIndex, FilaBufferObject* bufferObject);

#ifdef __cplusplus
}
#endif

#endif // FILAMENT_C_VERTEX_BUFFER_H
