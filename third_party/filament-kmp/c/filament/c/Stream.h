#ifndef FILAMENT_C_STREAM_H
#define FILAMENT_C_STREAM_H

#include "FilaTypes.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef enum FilaStreamType {
    FILA_STREAM_TYPE_NATIVE = 0,
    FILA_STREAM_TYPE_ACQUIRED = 1,
} FilaStreamType;

// Builder
typedef struct FilaStreamBuilder FilaStreamBuilder;

FilaStreamBuilder* FilaStreamBuilder_create(void);
void FilaStreamBuilder_destroy(FilaStreamBuilder* builder);
FilaStream* FilaStreamBuilder_build(FilaStreamBuilder* builder, FilaEngine* engine);

void FilaStreamBuilder_stream(FilaStreamBuilder* builder, void* nativeStream);
void FilaStreamBuilder_width(FilaStreamBuilder* builder, uint32_t width);
void FilaStreamBuilder_height(FilaStreamBuilder* builder, uint32_t height);

// Stream
FilaStreamType FilaStream_getStreamType(const FilaStream* stream);
void FilaStream_setAcquiredImage(FilaStream* stream, FilaEngine* engine, void* image, FilaCallbackHandler* handler, FilaStreamCallback callback, void* userdata, const float* transform);
void FilaStream_setDimensions(FilaStream* stream, uint32_t width, uint32_t height);
int64_t FilaStream_getTimestamp(const FilaStream* stream);

#ifdef __cplusplus
}
#endif

#endif // FILAMENT_C_STREAM_H
