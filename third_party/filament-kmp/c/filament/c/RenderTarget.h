#ifndef FILAMENT_C_RENDER_TARGET_H
#define FILAMENT_C_RENDER_TARGET_H

#include "FilaTypes.h"
#include "Texture.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef enum FilaRenderTargetAttachmentPoint {
    FILA_RENDER_TARGET_ATTACHMENT_POINT_COLOR0 = 0,
    FILA_RENDER_TARGET_ATTACHMENT_POINT_COLOR1 = 1,
    FILA_RENDER_TARGET_ATTACHMENT_POINT_COLOR2 = 2,
    FILA_RENDER_TARGET_ATTACHMENT_POINT_COLOR3 = 3,
    FILA_RENDER_TARGET_ATTACHMENT_POINT_COLOR4 = 4,
    FILA_RENDER_TARGET_ATTACHMENT_POINT_COLOR5 = 5,
    FILA_RENDER_TARGET_ATTACHMENT_POINT_COLOR6 = 6,
    FILA_RENDER_TARGET_ATTACHMENT_POINT_COLOR7 = 7,
    FILA_RENDER_TARGET_ATTACHMENT_POINT_DEPTH = 8,
} FilaRenderTargetAttachmentPoint;

typedef enum FilaRenderTargetCubemapFace {
    FILA_RENDER_TARGET_CUBEMAP_FACE_POSITIVE_X = 0,
    FILA_RENDER_TARGET_CUBEMAP_FACE_NEGATIVE_X = 1,
    FILA_RENDER_TARGET_CUBEMAP_FACE_POSITIVE_Y = 2,
    FILA_RENDER_TARGET_CUBEMAP_FACE_NEGATIVE_Y = 3,
    FILA_RENDER_TARGET_CUBEMAP_FACE_POSITIVE_Z = 4,
    FILA_RENDER_TARGET_CUBEMAP_FACE_NEGATIVE_Z = 5,
} FilaRenderTargetCubemapFace;

// Builder
typedef struct FilaRenderTargetBuilder FilaRenderTargetBuilder;

FilaRenderTargetBuilder* FilaRenderTargetBuilder_create(void);
void FilaRenderTargetBuilder_destroy(FilaRenderTargetBuilder* builder);
FilaRenderTarget* FilaRenderTargetBuilder_build(FilaRenderTargetBuilder* builder, FilaEngine* engine);

void FilaRenderTargetBuilder_texture(FilaRenderTargetBuilder* builder, FilaRenderTargetAttachmentPoint attachment, FilaTexture* texture);
void FilaRenderTargetBuilder_mipLevel(FilaRenderTargetBuilder* builder, FilaRenderTargetAttachmentPoint attachment, uint8_t level);
void FilaRenderTargetBuilder_face(FilaRenderTargetBuilder* builder, FilaRenderTargetAttachmentPoint attachment, FilaRenderTargetCubemapFace face);
void FilaRenderTargetBuilder_layer(FilaRenderTargetBuilder* builder, FilaRenderTargetAttachmentPoint attachment, uint32_t layer);
void FilaRenderTargetBuilder_samples(FilaRenderTargetBuilder* builder, uint8_t samples);

// RenderTarget
FilaTexture* FilaRenderTarget_getTexture(const FilaRenderTarget* renderTarget, FilaRenderTargetAttachmentPoint attachment);
uint8_t FilaRenderTarget_getMipLevel(const FilaRenderTarget* renderTarget, FilaRenderTargetAttachmentPoint attachment);
FilaRenderTargetCubemapFace FilaRenderTarget_getFace(const FilaRenderTarget* renderTarget, FilaRenderTargetAttachmentPoint attachment);
uint32_t FilaRenderTarget_getLayer(const FilaRenderTarget* renderTarget, FilaRenderTargetAttachmentPoint attachment);
uint8_t FilaRenderTarget_getSupportedColorAttachmentsCount(const FilaRenderTarget* renderTarget);

#ifdef __cplusplus
}
#endif

#endif // FILAMENT_C_RENDER_TARGET_H
