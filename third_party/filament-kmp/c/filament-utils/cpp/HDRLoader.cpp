#include <filament/Engine.h>
#include <filament/Texture.h>
#include <utils/Log.h>

#include "../../filament/cpp/FilaCommon.h"
#include "../c/HDRLoader.h"

using namespace filament;
using namespace utils;

extern "C" {
float *stbi_loadf_from_memory(const unsigned char *buffer, int len, int *x,
                              int *y, int *channels_in_file,
                              int desired_channels);
void stbi_image_free(void *retval_from_stbi_load);
}

extern "C" {

FilaTexture *FilaHDRLoader_createTexture(FilaEngine *engine, const void *buffer,
                                         size_t size, int32_t internalFormat) {
  Engine *e = FILA_CAST(Engine, engine);

  int width = 0, height = 0, channels = 0;
  float *data =
      stbi_loadf_from_memory(static_cast<const unsigned char *>(buffer), size,
                             &width, &height, &channels, 0);

  if (!data) {
    return nullptr;
  }

  Texture::Format format =
      (channels == 3) ? Texture::Format::RGB : Texture::Format::RGBA;

  Texture *texture =
      Texture::Builder()
          .width(width)
          .height(height)
          .levels(0xff)
          .sampler(Texture::Sampler::SAMPLER_2D)
          .usage(Texture::Usage::DEFAULT | Texture::Usage::GEN_MIPMAPPABLE)
          .format((Texture::InternalFormat)internalFormat)
          .build(*e);

  if (texture == nullptr) {
    stbi_image_free(data);
    return nullptr;
  }

  size_t sizeInBytes = (size_t)width * height * channels * sizeof(float);

  Texture::PixelBufferDescriptor pbd(
      data, sizeInBytes, format,
      Texture::PixelBufferDescriptor::PixelDataType::FLOAT,
      [](void *buf, size_t, void *) { stbi_image_free(buf); }, nullptr);

  texture->setImage(*e, 0, std::move(pbd));
  texture->generateMipmaps(*e);

  return reinterpret_cast<FilaTexture *>(texture);
}

} // extern "C"
