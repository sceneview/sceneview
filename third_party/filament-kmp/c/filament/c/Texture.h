#ifndef FILAMENT_C_TEXTURE_H
#define FILAMENT_C_TEXTURE_H

#include "FilaTypes.h"

#ifdef __cplusplus
extern "C" {
#endif

// Matches filament::backend::SamplerType
typedef enum FilaTextureSamplerType {
    FILA_TEXTURE_SAMPLER_2D = 0,
    FILA_TEXTURE_SAMPLER_2D_ARRAY = 1,
    FILA_TEXTURE_SAMPLER_CUBEMAP = 2,
    FILA_TEXTURE_SAMPLER_EXTERNAL = 3,
    FILA_TEXTURE_SAMPLER_3D = 4,
    FILA_TEXTURE_SAMPLER_CUBEMAP_ARRAY = 5,
} FilaTextureSamplerType;

// Matches filament::backend::TextureFormat
typedef enum FilaTextureInternalFormat {
    FILA_TEXTURE_INTERNAL_FORMAT_R8 = 0,
    FILA_TEXTURE_INTERNAL_FORMAT_R8_SNORM = 1,
    FILA_TEXTURE_INTERNAL_FORMAT_R8UI = 2,
    FILA_TEXTURE_INTERNAL_FORMAT_R8I = 3,
    FILA_TEXTURE_INTERNAL_FORMAT_STENCIL8 = 4,
    FILA_TEXTURE_INTERNAL_FORMAT_R16F = 5,
    FILA_TEXTURE_INTERNAL_FORMAT_R16UI = 6,
    FILA_TEXTURE_INTERNAL_FORMAT_R16I = 7,
    FILA_TEXTURE_INTERNAL_FORMAT_RG8 = 8,
    FILA_TEXTURE_INTERNAL_FORMAT_RG8_SNORM = 9,
    FILA_TEXTURE_INTERNAL_FORMAT_RG8UI = 10,
    FILA_TEXTURE_INTERNAL_FORMAT_RG8I = 11,
    FILA_TEXTURE_INTERNAL_FORMAT_RGB565 = 12,
    FILA_TEXTURE_INTERNAL_FORMAT_RGB9_E5 = 13,
    FILA_TEXTURE_INTERNAL_FORMAT_RGB5_A1 = 14,
    FILA_TEXTURE_INTERNAL_FORMAT_RGBA4 = 15,
    FILA_TEXTURE_INTERNAL_FORMAT_DEPTH16 = 16,
    FILA_TEXTURE_INTERNAL_FORMAT_RGB8 = 17,
    FILA_TEXTURE_INTERNAL_FORMAT_SRGB8 = 18,
    FILA_TEXTURE_INTERNAL_FORMAT_RGB8_SNORM = 19,
    FILA_TEXTURE_INTERNAL_FORMAT_RGB8UI = 20,
    FILA_TEXTURE_INTERNAL_FORMAT_RGB8I = 21,
    FILA_TEXTURE_INTERNAL_FORMAT_DEPTH24 = 22,
    FILA_TEXTURE_INTERNAL_FORMAT_R32F = 23,
    FILA_TEXTURE_INTERNAL_FORMAT_R32UI = 24,
    FILA_TEXTURE_INTERNAL_FORMAT_R32I = 25,
    FILA_TEXTURE_INTERNAL_FORMAT_RG16F = 26,
    FILA_TEXTURE_INTERNAL_FORMAT_RG16UI = 27,
    FILA_TEXTURE_INTERNAL_FORMAT_RG16I = 28,
    FILA_TEXTURE_INTERNAL_FORMAT_R11F_G11F_B10F = 29,
    FILA_TEXTURE_INTERNAL_FORMAT_RGBA8 = 30,
    FILA_TEXTURE_INTERNAL_FORMAT_SRGB8_A8 = 31,
    FILA_TEXTURE_INTERNAL_FORMAT_RGBA8_SNORM = 32,
    FILA_TEXTURE_INTERNAL_FORMAT_RGB10_A2 = 34,
    FILA_TEXTURE_INTERNAL_FORMAT_RGBA8UI = 35,
    FILA_TEXTURE_INTERNAL_FORMAT_RGBA8I = 36,
    FILA_TEXTURE_INTERNAL_FORMAT_DEPTH32F = 37,
    FILA_TEXTURE_INTERNAL_FORMAT_DEPTH24_STENCIL8 = 38,
    FILA_TEXTURE_INTERNAL_FORMAT_DEPTH32F_STENCIL8 = 39,
    FILA_TEXTURE_INTERNAL_FORMAT_RGB16F = 40,
    FILA_TEXTURE_INTERNAL_FORMAT_RGB16UI = 41,
    FILA_TEXTURE_INTERNAL_FORMAT_RGB16I = 42,
    FILA_TEXTURE_INTERNAL_FORMAT_RG32F = 43,
    FILA_TEXTURE_INTERNAL_FORMAT_RG32UI = 44,
    FILA_TEXTURE_INTERNAL_FORMAT_RG32I = 45,
    FILA_TEXTURE_INTERNAL_FORMAT_RGBA16F = 46,
    FILA_TEXTURE_INTERNAL_FORMAT_RGBA16UI = 47,
    FILA_TEXTURE_INTERNAL_FORMAT_RGBA16I = 48,
    FILA_TEXTURE_INTERNAL_FORMAT_RGB32F = 49,
    FILA_TEXTURE_INTERNAL_FORMAT_RGB32UI = 50,
    FILA_TEXTURE_INTERNAL_FORMAT_RGB32I = 51,
    FILA_TEXTURE_INTERNAL_FORMAT_RGBA32F = 52,
    FILA_TEXTURE_INTERNAL_FORMAT_RGBA32UI = 53,
    FILA_TEXTURE_INTERNAL_FORMAT_RGBA32I = 54,
} FilaTextureInternalFormat;

// Matches filament::backend::TextureUsage
typedef enum FilaTextureUsage {
    FILA_TEXTURE_USAGE_NONE = 0x0000,
    FILA_TEXTURE_USAGE_COLOR_ATTACHMENT = 0x0001,
    FILA_TEXTURE_USAGE_DEPTH_ATTACHMENT = 0x0002,
    FILA_TEXTURE_USAGE_STENCIL_ATTACHMENT = 0x0004,
    FILA_TEXTURE_USAGE_UPLOADABLE = 0x0008,
    FILA_TEXTURE_USAGE_SAMPLEABLE = 0x0010,
    FILA_TEXTURE_USAGE_SUBPASS_INPUT = 0x0020,
    FILA_TEXTURE_USAGE_BLIT_SRC = 0x0040,
    FILA_TEXTURE_USAGE_BLIT_DST = 0x0080,
    FILA_TEXTURE_USAGE_PROTECTED = 0x0100,
    FILA_TEXTURE_USAGE_GEN_MIPMAPPABLE = 0x0200,
    FILA_TEXTURE_USAGE_DEFAULT = 0x0008 | 0x0010,
} FilaTextureUsage;

// Matches filament::backend::TextureSwizzle
typedef enum FilaTextureSwizzle {
    FILA_TEXTURE_SWIZZLE_SUBSTITUTE_ZERO = 0,
    FILA_TEXTURE_SWIZZLE_SUBSTITUTE_ONE = 1,
    FILA_TEXTURE_SWIZZLE_CHANNEL_0 = 2,
    FILA_TEXTURE_SWIZZLE_CHANNEL_1 = 3,
    FILA_TEXTURE_SWIZZLE_CHANNEL_2 = 4,
    FILA_TEXTURE_SWIZZLE_CHANNEL_3 = 5,
} FilaTextureSwizzle;

// Builder
typedef struct FilaTextureBuilder FilaTextureBuilder;

FilaTextureBuilder* FilaTextureBuilder_create(void);
void FilaTextureBuilder_destroy(FilaTextureBuilder* builder);
FilaTexture* FilaTextureBuilder_build(FilaTextureBuilder* builder, FilaEngine* engine);

void FilaTextureBuilder_width(FilaTextureBuilder* builder, uint32_t width);
void FilaTextureBuilder_height(FilaTextureBuilder* builder, uint32_t height);
void FilaTextureBuilder_depth(FilaTextureBuilder* builder, uint32_t depth);
void FilaTextureBuilder_levels(FilaTextureBuilder* builder, uint8_t levels);
void FilaTextureBuilder_samples(FilaTextureBuilder* builder, uint8_t samples);
void FilaTextureBuilder_sampler(FilaTextureBuilder* builder, FilaTextureSamplerType target);
void FilaTextureBuilder_format(FilaTextureBuilder* builder, FilaTextureInternalFormat format);
void FilaTextureBuilder_usage(FilaTextureBuilder* builder, FilaTextureUsage usage);
void FilaTextureBuilder_swizzle(FilaTextureBuilder* builder, FilaTextureSwizzle r, FilaTextureSwizzle g, FilaTextureSwizzle b, FilaTextureSwizzle a);
void FilaTextureBuilder_importTexture(FilaTextureBuilder* builder, intptr_t id);
void FilaTextureBuilder_external(FilaTextureBuilder* builder);

// Texture
bool FilaTexture_isTextureFormatSupported(FilaEngine* engine, FilaTextureInternalFormat format);
bool FilaTexture_isTextureFormatMipmappable(FilaEngine* engine, FilaTextureInternalFormat format);
bool FilaTexture_isTextureSwizzleSupported(FilaEngine* engine);
size_t FilaTexture_getMaxTextureSize(FilaEngine* engine, FilaTextureSamplerType target);
size_t FilaTexture_getMaxArrayTextureLayers(FilaEngine* engine);
bool FilaTexture_validatePixelFormatAndType(FilaTextureInternalFormat internalFormat, FilaPixelDataFormat format, FilaPixelDataType type);

size_t FilaTexture_getWidth(const FilaTexture* texture, size_t level);
size_t FilaTexture_getHeight(const FilaTexture* texture, size_t level);
size_t FilaTexture_getDepth(const FilaTexture* texture, size_t level);
size_t FilaTexture_getLevels(const FilaTexture* texture);
FilaTextureSamplerType FilaTexture_getTarget(const FilaTexture* texture);
FilaTextureInternalFormat FilaTexture_getFormat(const FilaTexture* texture);

void FilaTexture_setImage(FilaTexture* texture, FilaEngine* engine, size_t level, uint32_t xoffset, uint32_t yoffset, uint32_t zoffset, uint32_t width, uint32_t height, uint32_t depth, void* buffer, size_t sizeInBytes, FilaPixelDataFormat format, FilaPixelDataType type, uint8_t alignment, uint32_t left, uint32_t top, uint32_t stride, FilaCallbackHandler* handler, FilaBufferCallback callback, void* userData);
void FilaTexture_setExternalStream(FilaTexture* texture, FilaEngine* engine, FilaStream* stream);
void FilaTexture_generateMipmaps(const FilaTexture* texture, FilaEngine* engine);
size_t FilaTexture_computeDataSize(FilaPixelDataFormat format, FilaPixelDataType type, size_t stride, size_t height, size_t alignment);

#ifdef __cplusplus
}
#endif

#endif // FILAMENT_C_TEXTURE_H
