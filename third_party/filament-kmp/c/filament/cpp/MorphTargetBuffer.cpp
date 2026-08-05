#include <filament/MorphTargetBuffer.h>
#include <filament/Engine.h>
#include <math/vec4.h>

#include "FilaCommon.h"
#include "../c/MorphTargetBuffer.h"

using namespace filament;

extern "C" {

FilaMorphTargetBufferBuilder* FilaMorphTargetBufferBuilder_create() {
    return reinterpret_cast<FilaMorphTargetBufferBuilder*>(new MorphTargetBuffer::Builder());
}

void FilaMorphTargetBufferBuilder_destroy(FilaMorphTargetBufferBuilder* builder) {
    delete reinterpret_cast<MorphTargetBuffer::Builder*>(builder);
}

FilaMorphTargetBuffer* FilaMorphTargetBufferBuilder_build(FilaMorphTargetBufferBuilder* builder, FilaEngine* engine) {
    return reinterpret_cast<FilaMorphTargetBuffer*>(FILA_CAST(MorphTargetBuffer::Builder, builder)->build(*FILA_CAST(Engine, engine)));
}

void FilaMorphTargetBufferBuilder_vertexCount(FilaMorphTargetBufferBuilder* builder, size_t vertexCount) {
    FILA_CAST(MorphTargetBuffer::Builder, builder)->vertexCount(vertexCount);
}

void FilaMorphTargetBufferBuilder_count(FilaMorphTargetBufferBuilder* builder, size_t count) {
    FILA_CAST(MorphTargetBuffer::Builder, builder)->count(count);
}

void FilaMorphTargetBufferBuilder_withPositions(FilaMorphTargetBufferBuilder* builder, bool enabled) {
    FILA_CAST(MorphTargetBuffer::Builder, builder)->withPositions(enabled);
}

void FilaMorphTargetBufferBuilder_withTangents(FilaMorphTargetBufferBuilder* builder, bool enabled) {
    FILA_CAST(MorphTargetBuffer::Builder, builder)->withTangents(enabled);
}

void FilaMorphTargetBufferBuilder_enableCustomMorphing(FilaMorphTargetBufferBuilder* builder, bool enabled) {
    FILA_CAST(MorphTargetBuffer::Builder, builder)->enableCustomMorphing(enabled);
}

// MorphTargetBuffer instance methods
size_t FilaMorphTargetBuffer_getVertexCount(const FilaMorphTargetBuffer* buffer) {
    return FILA_CONST_CAST(MorphTargetBuffer, buffer)->getVertexCount();
}

size_t FilaMorphTargetBuffer_getCount(const FilaMorphTargetBuffer* buffer) {
    return FILA_CONST_CAST(MorphTargetBuffer, buffer)->getCount();
}

bool FilaMorphTargetBuffer_hasPositions(const FilaMorphTargetBuffer* buffer) {
    return FILA_CONST_CAST(MorphTargetBuffer, buffer)->hasPositions();
}

bool FilaMorphTargetBuffer_hasTangents(const FilaMorphTargetBuffer* buffer) {
    return FILA_CONST_CAST(MorphTargetBuffer, buffer)->hasTangents();
}

bool FilaMorphTargetBuffer_isCustomMorphingEnabled(const FilaMorphTargetBuffer* buffer) {
    return FILA_CONST_CAST(MorphTargetBuffer, buffer)->isCustomMorphingEnabled();
}

void FilaMorphTargetBuffer_setPositionsAt(FilaMorphTargetBuffer* buffer, FilaEngine* engine, size_t targetIndex, const float* positions, size_t count) {
    FILA_CAST(MorphTargetBuffer, buffer)->setPositionsAt(*FILA_CAST(Engine, engine), targetIndex, 
        reinterpret_cast<const math::float4*>(positions), count);
}

void FilaMorphTargetBuffer_setTangentsAt(FilaMorphTargetBuffer* buffer, FilaEngine* engine, size_t targetIndex, const short* tangents, size_t count) {
    FILA_CAST(MorphTargetBuffer, buffer)->setTangentsAt(*FILA_CAST(Engine, engine), targetIndex, 
        reinterpret_cast<const math::short4*>(tangents), count);
}

} // extern "C"
