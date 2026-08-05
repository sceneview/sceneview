#include "KTX1Loader.h"

#include <filament/Engine.h>
#include <filament/IndirectLight.h>
#include <filament/Skybox.h>
#include <filament/Texture.h>
#include <ktxreader/Ktx1Reader.h>

#include <math/vec3.h>

using namespace filament;
using namespace ktxreader;
using namespace filament::math;

extern "C" {

FilaTexture* FilaKTX1Loader_createTexture(FilaEngine* engine, const void* buffer, size_t size, bool srgb) {
    Engine* nativeEngine = reinterpret_cast<Engine*>(engine);
    Ktx1Bundle* bundle = new Ktx1Bundle(reinterpret_cast<const uint8_t*>(buffer), size);

    // This Ktx1Reader::createTexture overload takes ownership of `bundle`: it deletes it via a
    // PixelBufferDescriptor callback once the GPU has consumed the upload (async, on the engine
    // thread during purge). Deleting it here too double-frees and aborts on the render thread.
    // Ktx1Bundle copies the bytes internally, so `buffer` need only stay valid for this call.
    Texture* texture = Ktx1Reader::createTexture(nativeEngine, bundle, srgb);

    return reinterpret_cast<FilaTexture*>(texture);
}

FilaIndirectLight* FilaKTX1Loader_createIndirectLight(FilaEngine* engine, FilaTexture* texture, const FilaFloat3* sh) {
    Engine* nativeEngine = reinterpret_cast<Engine*>(engine);
    Texture* nativeTexture = reinterpret_cast<Texture*>(texture);
    
    IndirectLight* indirectLight = IndirectLight::Builder()
            .reflections(nativeTexture)
            .irradiance(3, reinterpret_cast<const float3*>(sh))
            .build(*nativeEngine);
            
    return reinterpret_cast<FilaIndirectLight*>(indirectLight);
}

FilaSkybox* FilaKTX1Loader_createSkybox(FilaEngine* engine, FilaTexture* texture) {
    Engine* nativeEngine = reinterpret_cast<Engine*>(engine);
    Texture* nativeTexture = reinterpret_cast<Texture*>(texture);
    
    Skybox* skybox = Skybox::Builder()
            .environment(nativeTexture)
            .build(*nativeEngine);
            
    return reinterpret_cast<FilaSkybox*>(skybox);
}

bool FilaKTX1Loader_getSphericalHarmonics(const void* buffer, size_t size, FilaFloat3* outSh) {
    Ktx1Bundle bundle(reinterpret_cast<const uint8_t*>(buffer), size);
    return bundle.getSphericalHarmonics(reinterpret_cast<float3*>(outSh));
}

}
