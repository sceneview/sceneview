#include "TextureLoader.h"

#include <filament/Engine.h>
#include <filament/Texture.h>

#include <cstddef>

using namespace filament;

extern "C" {
unsigned char *stbi_load_from_memory(const unsigned char *buffer, int len, int *x,
                                     int *y, int *channels_in_file, int desired_channels);
void stbi_image_free(void *retval_from_stbi_load);
}

extern "C" {

FilaTexture* FilaTextureLoader_loadTexture(FilaEngine* engine, const void* buffer, size_t size, bool srgb) {
    Engine* e = reinterpret_cast<Engine*>(engine);

    // Decode to 8-bit RGBA (forced 4 channels covers grayscale/palette/RGB/RGBA uniformly).
    int width = 0, height = 0, channels = 0;
    unsigned char* data = stbi_load_from_memory(
            static_cast<const unsigned char*>(buffer), static_cast<int>(size),
            &width, &height, &channels, 4);
    if (!data) return nullptr;

    Texture::InternalFormat internalFormat =
            srgb ? Texture::InternalFormat::SRGB8_A8 : Texture::InternalFormat::RGBA8;

    Texture* texture = Texture::Builder()
            .width(width)
            .height(height)
            .levels(0xff) // generate mipmaps
            .sampler(Texture::Sampler::SAMPLER_2D)
            .format(internalFormat)
            .usage(Texture::Usage::DEFAULT | Texture::Usage::GEN_MIPMAPPABLE)
            .build(*e);

    if (texture == nullptr) {
        stbi_image_free(data);
        return nullptr;
    }

    size_t sizeInBytes = (size_t) width * height * 4;
    Texture::PixelBufferDescriptor pbd(
            data, sizeInBytes,
            Texture::Format::RGBA, Texture::Type::UBYTE,
            [](void* buf, size_t, void*) { stbi_image_free(buf); }, nullptr);

    texture->setImage(*e, 0, std::move(pbd));
    texture->generateMipmaps(*e);

    return reinterpret_cast<FilaTexture*>(texture);
}

}
