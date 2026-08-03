#include <filament/RenderTarget.h>
#include <filament/Texture.h>
#include <filament/Engine.h>

#include "FilaCommon.h"
#include "../c/RenderTarget.h"

using namespace filament;

extern "C" {

FilaRenderTargetBuilder* FilaRenderTargetBuilder_create() {
    return reinterpret_cast<FilaRenderTargetBuilder*>(new RenderTarget::Builder());
}

void FilaRenderTargetBuilder_destroy(FilaRenderTargetBuilder* builder) {
    delete reinterpret_cast<RenderTarget::Builder*>(builder);
}

FilaRenderTarget* FilaRenderTargetBuilder_build(FilaRenderTargetBuilder* builder, FilaEngine* engine) {
    return reinterpret_cast<FilaRenderTarget*>(FILA_CAST(RenderTarget::Builder, builder)->build(*FILA_CAST(Engine, engine)));
}

void FilaRenderTargetBuilder_texture(FilaRenderTargetBuilder* builder, FilaRenderTargetAttachmentPoint attachment, FilaTexture* texture) {
    FILA_CAST(RenderTarget::Builder, builder)->texture(static_cast<RenderTarget::AttachmentPoint>(attachment), reinterpret_cast<Texture*>(texture));
}

void FilaRenderTargetBuilder_mipLevel(FilaRenderTargetBuilder* builder, FilaRenderTargetAttachmentPoint attachment, uint8_t level) {
    FILA_CAST(RenderTarget::Builder, builder)->mipLevel(static_cast<RenderTarget::AttachmentPoint>(attachment), level);
}

void FilaRenderTargetBuilder_face(FilaRenderTargetBuilder* builder, FilaRenderTargetAttachmentPoint attachment, FilaRenderTargetCubemapFace face) {
    FILA_CAST(RenderTarget::Builder, builder)->face(static_cast<RenderTarget::AttachmentPoint>(attachment), static_cast<RenderTarget::CubemapFace>(face));
}

void FilaRenderTargetBuilder_layer(FilaRenderTargetBuilder* builder, FilaRenderTargetAttachmentPoint attachment, uint32_t layer) {
    FILA_CAST(RenderTarget::Builder, builder)->layer(static_cast<RenderTarget::AttachmentPoint>(attachment), layer);
}

void FilaRenderTargetBuilder_samples(FilaRenderTargetBuilder* builder, uint8_t samples) {
    FILA_CAST(RenderTarget::Builder, builder)->samples(samples);
}

// RenderTarget instance methods
FilaTexture* FilaRenderTarget_getTexture(const FilaRenderTarget* renderTarget, FilaRenderTargetAttachmentPoint attachment) {
    return reinterpret_cast<FilaTexture*>(FILA_CONST_CAST(RenderTarget, renderTarget)->getTexture(static_cast<RenderTarget::AttachmentPoint>(attachment)));
}

uint8_t FilaRenderTarget_getMipLevel(const FilaRenderTarget* renderTarget, FilaRenderTargetAttachmentPoint attachment) {
    return FILA_CONST_CAST(RenderTarget, renderTarget)->getMipLevel(static_cast<RenderTarget::AttachmentPoint>(attachment));
}

FilaRenderTargetCubemapFace FilaRenderTarget_getFace(const FilaRenderTarget* renderTarget, FilaRenderTargetAttachmentPoint attachment) {
    return static_cast<FilaRenderTargetCubemapFace>(FILA_CONST_CAST(RenderTarget, renderTarget)->getFace(static_cast<RenderTarget::AttachmentPoint>(attachment)));
}

uint32_t FilaRenderTarget_getLayer(const FilaRenderTarget* renderTarget, FilaRenderTargetAttachmentPoint attachment) {
    return FILA_CONST_CAST(RenderTarget, renderTarget)->getLayer(static_cast<RenderTarget::AttachmentPoint>(attachment));
}

uint8_t FilaRenderTarget_getSupportedColorAttachmentsCount(const FilaRenderTarget* renderTarget) {
    return FILA_CONST_CAST(RenderTarget, renderTarget)->getSupportedColorAttachmentsCount();
}

} // extern "C"
