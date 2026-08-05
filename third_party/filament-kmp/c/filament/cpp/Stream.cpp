#include <filament/Stream.h>
#include <filament/Engine.h>
#include <math/mat3.h>

#include "FilaCommon.h"
#include "../c/Stream.h"

using namespace filament;
using namespace filament_c;

extern "C" {

FilaStreamBuilder* FilaStreamBuilder_create() {
    return reinterpret_cast<FilaStreamBuilder*>(new Stream::Builder());
}

void FilaStreamBuilder_destroy(FilaStreamBuilder* builder) {
    delete reinterpret_cast<Stream::Builder*>(builder);
}

FilaStream* FilaStreamBuilder_build(FilaStreamBuilder* builder, FilaEngine* engine) {
    return reinterpret_cast<FilaStream*>(FILA_CAST(Stream::Builder, builder)->build(*FILA_CAST(Engine, engine)));
}

void FilaStreamBuilder_stream(FilaStreamBuilder* builder, void* nativeStream) {
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wdeprecated-declarations"
    FILA_CAST(Stream::Builder, builder)->stream(nativeStream);
#pragma clang diagnostic pop
}

void FilaStreamBuilder_width(FilaStreamBuilder* builder, uint32_t width) {
    FILA_CAST(Stream::Builder, builder)->width(width);
}

void FilaStreamBuilder_height(FilaStreamBuilder* builder, uint32_t height) {
    FILA_CAST(Stream::Builder, builder)->height(height);
}

// Stream instance methods
FilaStreamType FilaStream_getStreamType(const FilaStream* stream) {
    return static_cast<FilaStreamType>(FILA_CONST_CAST(Stream, stream)->getStreamType());
}

void FilaStream_setAcquiredImage(FilaStream* stream, FilaEngine* engine, void* image, FilaCallbackHandler* handler, FilaStreamCallback callback, void* userdata, const float* transform) {
    math::mat3f t = transform ? *reinterpret_cast<const math::mat3f*>(transform) : math::mat3f();
    auto wrapper = new StreamCallbackWrapper{callback, userdata};
    FILA_CAST(Stream, stream)->setAcquiredImage(image, 
        reinterpret_cast<backend::CallbackHandler*>(handler),
        streamCallback, wrapper, t);
}

void FilaStream_setDimensions(FilaStream* stream, uint32_t width, uint32_t height) {
    FILA_CAST(Stream, stream)->setDimensions(width, height);
}

int64_t FilaStream_getTimestamp(const FilaStream* stream) {
    return FILA_CONST_CAST(Stream, stream)->getTimestamp();
}

} // extern "C"
