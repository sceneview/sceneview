#include <filament/Skybox.h>
#include <filament/Texture.h>
#include <filament/Engine.h>
#include <math/vec4.h>

#include "FilaCommon.h"
#include "../c/Skybox.h"

using namespace filament;

extern "C" {

FilaSkyboxBuilder* FilaSkyboxBuilder_create() {
    return reinterpret_cast<FilaSkyboxBuilder*>(new Skybox::Builder());
}

void FilaSkyboxBuilder_destroy(FilaSkyboxBuilder* builder) {
    delete reinterpret_cast<Skybox::Builder*>(builder);
}

FilaSkybox* FilaSkyboxBuilder_build(FilaSkyboxBuilder* builder, FilaEngine* engine) {
    return reinterpret_cast<FilaSkybox*>(FILA_CAST(Skybox::Builder, builder)->build(*FILA_CAST(Engine, engine)));
}

void FilaSkyboxBuilder_environment(FilaSkyboxBuilder* builder, const FilaTexture* texture) {
    FILA_CAST(Skybox::Builder, builder)->environment(const_cast<Texture*>(FILA_CONST_CAST(Texture, texture)));
}

void FilaSkyboxBuilder_showSun(FilaSkyboxBuilder* builder, bool show) {
    FILA_CAST(Skybox::Builder, builder)->showSun(show);
}

void FilaSkyboxBuilder_intensity(FilaSkyboxBuilder* builder, float intensity) {
    FILA_CAST(Skybox::Builder, builder)->intensity(intensity);
}

void FilaSkyboxBuilder_color(FilaSkyboxBuilder* builder, float r, float g, float b, float a) {
    FILA_CAST(Skybox::Builder, builder)->color({r, g, b, a});
}

void FilaSkyboxBuilder_priority(FilaSkyboxBuilder* builder, uint8_t priority) {
    FILA_CAST(Skybox::Builder, builder)->priority(priority);
}

// Skybox instance methods
void FilaSkybox_setLayerMask(FilaSkybox* skybox, uint8_t select, uint8_t value) {
    FILA_CAST(Skybox, skybox)->setLayerMask(select, value);
}

uint8_t FilaSkybox_getLayerMask(const FilaSkybox* skybox) {
    return FILA_CONST_CAST(Skybox, skybox)->getLayerMask();
}

float FilaSkybox_getIntensity(const FilaSkybox* skybox) {
    return FILA_CONST_CAST(Skybox, skybox)->getIntensity();
}

void FilaSkybox_setColor(FilaSkybox* skybox, float r, float g, float b, float a) {
    FILA_CAST(Skybox, skybox)->setColor({r, g, b, a});
}

FilaTexture* FilaSkybox_getTexture(const FilaSkybox* skybox) {
    return reinterpret_cast<FilaTexture*>(const_cast<Texture*>(FILA_CONST_CAST(Skybox, skybox)->getTexture()));
}

} // extern "C"
