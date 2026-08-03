#ifndef FILAMENT_C_MORPH_TARGET_BUFFER_H
#define FILAMENT_C_MORPH_TARGET_BUFFER_H

#include "FilaTypes.h"

#ifdef __cplusplus
extern "C" {
#endif

// Builder
typedef struct FilaMorphTargetBufferBuilder FilaMorphTargetBufferBuilder;

FilaMorphTargetBufferBuilder* FilaMorphTargetBufferBuilder_create(void);
void FilaMorphTargetBufferBuilder_destroy(FilaMorphTargetBufferBuilder* builder);
FilaMorphTargetBuffer* FilaMorphTargetBufferBuilder_build(FilaMorphTargetBufferBuilder* builder, FilaEngine* engine);

void FilaMorphTargetBufferBuilder_vertexCount(FilaMorphTargetBufferBuilder* builder, size_t vertexCount);
void FilaMorphTargetBufferBuilder_count(FilaMorphTargetBufferBuilder* builder, size_t count);
void FilaMorphTargetBufferBuilder_withPositions(FilaMorphTargetBufferBuilder* builder, bool enabled);
void FilaMorphTargetBufferBuilder_withTangents(FilaMorphTargetBufferBuilder* builder, bool enabled);
void FilaMorphTargetBufferBuilder_enableCustomMorphing(FilaMorphTargetBufferBuilder* builder, bool enabled);

// MorphTargetBuffer
size_t FilaMorphTargetBuffer_getVertexCount(const FilaMorphTargetBuffer* buffer);
size_t FilaMorphTargetBuffer_getCount(const FilaMorphTargetBuffer* buffer);
bool FilaMorphTargetBuffer_hasPositions(const FilaMorphTargetBuffer* buffer);
bool FilaMorphTargetBuffer_hasTangents(const FilaMorphTargetBuffer* buffer);
bool FilaMorphTargetBuffer_isCustomMorphingEnabled(const FilaMorphTargetBuffer* buffer);

void FilaMorphTargetBuffer_setPositionsAt(FilaMorphTargetBuffer* buffer, FilaEngine* engine, size_t targetIndex, const float* positions, size_t count);
void FilaMorphTargetBuffer_setTangentsAt(FilaMorphTargetBuffer* buffer, FilaEngine* engine, size_t targetIndex, const short* tangents, size_t count);

#ifdef __cplusplus
}
#endif

#endif // FILAMENT_C_MORPH_TARGET_BUFFER_H
