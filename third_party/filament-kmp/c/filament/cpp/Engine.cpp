#include <filament/BufferObject.h>
#include <filament/Camera.h>
#include <filament/ColorGrading.h>
#include <filament/Engine.h>
#include <filament/Fence.h>
#include <filament/IndexBuffer.h>
#include <filament/IndirectLight.h>
#include <filament/Material.h>
#include <filament/MaterialInstance.h>
#include <filament/MorphTargetBuffer.h>
#include <filament/RenderTarget.h>
#include <filament/Renderer.h>
#include <filament/Scene.h>
#include <filament/SkinningBuffer.h>
#include <filament/Skybox.h>
#include <filament/Stream.h>
#include <filament/SwapChain.h>
#include <filament/Texture.h>
#include <filament/VertexBuffer.h>
#include <filament/View.h>

#include <utils/Entity.h>
#include <utils/EntityManager.h>
#include <utils/Invocable.h>
#include <utils/tribool.h>

#include "../c/Engine.h"
#include "FilaCommon.h"

using namespace filament;
using namespace utils;

extern "C" {

struct FilaEngineBuilderWrapper {
    Engine::Builder builder;
    Engine::Config config;
};

FilaEngineBuilder *FilaEngineBuilder_create() {
  return reinterpret_cast<FilaEngineBuilder *>(new FilaEngineBuilderWrapper());
}

void FilaEngineBuilder_destroy(FilaEngineBuilder *builder) {
  delete reinterpret_cast<FilaEngineBuilderWrapper *>(builder);
}

void FilaEngineBuilder_backend(FilaEngineBuilder *builder,
                               FilaEngineBackend backend) {
  reinterpret_cast<FilaEngineBuilderWrapper *>(builder)->builder.backend(static_cast<Engine::Backend>(backend));
}

void FilaEngineBuilder_config(FilaEngineBuilder *builder,
                               const FilaEngineConfig *config) {
  if (!config)
    return;
  auto* wrapper = reinterpret_cast<FilaEngineBuilderWrapper *>(builder);
  wrapper->config.commandBufferSizeMB = config->commandBufferSizeMB;
  wrapper->config.perRenderPassArenaSizeMB = config->perRenderPassArenaSizeMB;
  wrapper->config.driverHandleArenaSizeMB = config->driverHandleArenaSizeMB;
  wrapper->config.minCommandBufferSizeMB = config->minCommandBufferSizeMB;
  wrapper->config.perFrameCommandsSizeMB = config->perFrameCommandsSizeMB;
  wrapper->config.jobSystemThreadCount = config->jobSystemThreadCount;
  wrapper->config.stereoscopicType =
      static_cast<Engine::StereoscopicType>(config->stereoscopicType);
  wrapper->config.stereoscopicEyeCount = config->stereoscopicEyeCount;
  wrapper->config.resourceAllocatorCacheSizeMB = config->resourceAllocatorCacheSizeMB;
  wrapper->config.resourceAllocatorCacheMaxAge = config->resourceAllocatorCacheMaxAge;
  wrapper->config.preferredShaderLanguage =
      static_cast<Engine::Config::ShaderLanguage>(
          config->preferredShaderLanguage);
  wrapper->config.forceGLES2Context = config->forceGLES2Context;
  wrapper->config.gpuContextPriority =
      static_cast<Engine::GpuContextPriority>(config->gpuContextPriority);
  wrapper->config.sharedUboInitialSizeInBytes = config->sharedUboInitialSizeInBytes;

  wrapper->builder.config(&wrapper->config);
}

void FilaEngineBuilder_featureLevel(FilaEngineBuilder *builder,
                                    FilaEngineFeatureLevel featureLevel) {
  reinterpret_cast<FilaEngineBuilderWrapper *>(builder)->builder.featureLevel(static_cast<Engine::FeatureLevel>(featureLevel));
}

void FilaEngineBuilder_sharedContext(FilaEngineBuilder *builder,
                                     void *sharedContext) {
  reinterpret_cast<FilaEngineBuilderWrapper *>(builder)->builder.sharedContext(sharedContext);
}

void FilaEngineBuilder_paused(FilaEngineBuilder *builder, bool paused) {
  reinterpret_cast<FilaEngineBuilderWrapper *>(builder)->builder.paused(paused);
}

void FilaEngineBuilder_feature(FilaEngineBuilder *builder, const char *name,
                               bool value) {
  reinterpret_cast<FilaEngineBuilderWrapper *>(builder)->builder.feature(name, value);
}

void FilaEngineBuilder_colorGrading(FilaEngineBuilder *builder,
                                    const FilaColorGradingBuilder *colorGrading) {
  reinterpret_cast<FilaEngineBuilderWrapper *>(builder)->builder.colorGrading(
      *reinterpret_cast<const ColorGrading::Builder *>(colorGrading));
}

FilaEngine *FilaEngineBuilder_build(FilaEngineBuilder *builder) {
  return reinterpret_cast<FilaEngine *>(
      reinterpret_cast<FilaEngineBuilderWrapper *>(builder)->builder.build());
}

// Engine
void FilaEngine_destroy(FilaEngine *engine) {
  Engine *cppEngine = FILA_CAST(Engine, engine);
  Engine::destroy(&cppEngine);
}

int32_t FilaEngine_getBackend(FilaEngine *engine) {
  return static_cast<int32_t>(FILA_CAST(Engine, engine)->getBackend());
}

FilaSwapChain *FilaEngine_createSwapChain(FilaEngine *engine,
                                          void *nativeWindow, uint64_t flags) {
  return reinterpret_cast<FilaSwapChain *>(
      FILA_CAST(Engine, engine)->createSwapChain(nativeWindow, flags));
}

FilaSwapChain *FilaEngine_createSwapChainHeadless(FilaEngine *engine,
                                                  uint32_t width,
                                                  uint32_t height,
                                                  uint64_t flags) {
  return reinterpret_cast<FilaSwapChain *>(
      FILA_CAST(Engine, engine)->createSwapChain(width, height, flags));
}

bool FilaEngine_destroySwapChain(FilaEngine *engine, FilaSwapChain *swapChain) {
  return FILA_CAST(Engine, engine)->destroy(FILA_CAST(SwapChain, swapChain));
}

FilaView *FilaEngine_createView(FilaEngine *engine) {
  return reinterpret_cast<FilaView *>(FILA_CAST(Engine, engine)->createView());
}

bool FilaEngine_destroyView(FilaEngine *engine, FilaView *view) {
  return FILA_CAST(Engine, engine)->destroy(FILA_CAST(View, view));
}

FilaRenderer *FilaEngine_createRenderer(FilaEngine *engine) {
  return reinterpret_cast<FilaRenderer *>(
      FILA_CAST(Engine, engine)->createRenderer());
}

bool FilaEngine_destroyRenderer(FilaEngine *engine, FilaRenderer *renderer) {
  return FILA_CAST(Engine, engine)->destroy(FILA_CAST(Renderer, renderer));
}

FilaCamera *FilaEngine_createCamera(FilaEngine *engine, FilaEntity entity) {
  return reinterpret_cast<FilaCamera *>(
      FILA_CAST(Engine, engine)->createCamera(Entity::import(entity)));
}

FilaCamera *FilaEngine_createCameraAuto(FilaEngine *engine) {
  return reinterpret_cast<FilaCamera *>(
      FILA_CAST(Engine, engine)->createCamera(EntityManager::get().create()));
}

FilaCamera *FilaEngine_getCameraComponent(FilaEngine *engine,
                                          FilaEntity entity) {
  return reinterpret_cast<FilaCamera *>(
      FILA_CAST(Engine, engine)->getCameraComponent(Entity::import(entity)));
}

bool FilaEngine_destroyCamera(FilaEngine *engine, FilaCamera *camera) {
  Camera *cppCamera = FILA_CAST(Camera, camera);
  FILA_CAST(Engine, engine)->destroyCameraComponent(cppCamera->getEntity());
  return true;
}

void FilaEngine_destroyCameraComponent(FilaEngine *engine, FilaEntity entity) {
  FILA_CAST(Engine, engine)->destroyCameraComponent(Entity::import(entity));
}

FilaScene *FilaEngine_createScene(FilaEngine *engine) {
  return reinterpret_cast<FilaScene *>(
      FILA_CAST(Engine, engine)->createScene());
}

bool FilaEngine_destroyScene(FilaEngine *engine, FilaScene *scene) {
  return FILA_CAST(Engine, engine)->destroy(FILA_CAST(Scene, scene));
}

FilaFence *FilaEngine_createFence(FilaEngine *engine) {
  return reinterpret_cast<FilaFence *>(
      FILA_CAST(Engine, engine)->createFence());
}

bool FilaEngine_destroyFence(FilaEngine *engine, FilaFence *fence) {
  return FILA_CAST(Engine, engine)->destroy(FILA_CAST(Fence, fence));
}

bool FilaEngine_destroyStream(FilaEngine *engine, FilaStream *stream) {
  return FILA_CAST(Engine, engine)->destroy(FILA_CAST(Stream, stream));
}

bool FilaEngine_destroyIndexBuffer(FilaEngine *engine,
                                   FilaIndexBuffer *indexBuffer) {
  return FILA_CAST(Engine, engine)
      ->destroy(FILA_CAST(IndexBuffer, indexBuffer));
}

bool FilaEngine_destroyVertexBuffer(FilaEngine *engine,
                                    FilaVertexBuffer *vertexBuffer) {
  return FILA_CAST(Engine, engine)
      ->destroy(FILA_CAST(VertexBuffer, vertexBuffer));
}

bool FilaEngine_destroyBufferObject(FilaEngine *engine,
                                    FilaBufferObject *bufferObject) {
  return FILA_CAST(Engine, engine)
      ->destroy(FILA_CAST(BufferObject, bufferObject));
}

bool FilaEngine_destroySkinningBuffer(FilaEngine *engine,
                                      FilaSkinningBuffer *skinningBuffer) {
  return FILA_CAST(Engine, engine)
      ->destroy(FILA_CAST(SkinningBuffer, skinningBuffer));
}

bool FilaEngine_destroyMorphTargetBuffer(
    FilaEngine *engine, FilaMorphTargetBuffer *morphTargetBuffer) {
  return FILA_CAST(Engine, engine)
      ->destroy(FILA_CAST(MorphTargetBuffer, morphTargetBuffer));
}

bool FilaEngine_destroyIndirectLight(FilaEngine *engine,
                                     FilaIndirectLight *indirectLight) {
  return FILA_CAST(Engine, engine)
      ->destroy(FILA_CAST(IndirectLight, indirectLight));
}

bool FilaEngine_destroyMaterial(FilaEngine *engine, FilaMaterial *material) {
  return FILA_CAST(Engine, engine)->destroy(FILA_CAST(Material, material));
}

bool FilaEngine_destroyMaterialInstance(
    FilaEngine *engine, FilaMaterialInstance *materialInstance) {
  return FILA_CAST(Engine, engine)
      ->destroy(FILA_CAST(MaterialInstance, materialInstance));
}

bool FilaEngine_destroySkybox(FilaEngine *engine, FilaSkybox *skybox) {
  return FILA_CAST(Engine, engine)->destroy(FILA_CAST(Skybox, skybox));
}

bool FilaEngine_destroyColorGrading(FilaEngine *engine,
                                    FilaColorGrading *colorGrading) {
  return FILA_CAST(Engine, engine)
      ->destroy(FILA_CAST(ColorGrading, colorGrading));
}

bool FilaEngine_destroyTexture(FilaEngine *engine, FilaTexture *texture) {
  return FILA_CAST(Engine, engine)->destroy(FILA_CAST(Texture, texture));
}

bool FilaEngine_destroyRenderTarget(FilaEngine *engine,
                                    FilaRenderTarget *target) {
  return FILA_CAST(Engine, engine)->destroy(FILA_CAST(RenderTarget, target));
}

void FilaEngine_destroyEntity(FilaEngine *engine, FilaEntity entity) {
  FILA_CAST(Engine, engine)->destroy(Entity::import(entity));
}

bool FilaEngine_isValidRenderer(FilaEngine *engine, FilaRenderer *renderer) {
  return FILA_CAST(Engine, engine)->isValid(FILA_CAST(Renderer, renderer));
}

bool FilaEngine_isValidView(FilaEngine *engine, FilaView *view) {
  return FILA_CAST(Engine, engine)->isValid(FILA_CAST(View, view));
}

bool FilaEngine_isValidScene(FilaEngine *engine, FilaScene *scene) {
  return FILA_CAST(Engine, engine)->isValid(FILA_CAST(Scene, scene));
}

bool FilaEngine_isValidFence(FilaEngine *engine, FilaFence *fence) {
  return FILA_CAST(Engine, engine)->isValid(FILA_CAST(Fence, fence));
}

bool FilaEngine_isValidStream(FilaEngine *engine, FilaStream *stream) {
  return FILA_CAST(Engine, engine)->isValid(FILA_CAST(Stream, stream));
}

bool FilaEngine_isValidIndexBuffer(FilaEngine *engine,
                                   FilaIndexBuffer *indexBuffer) {
  return FILA_CAST(Engine, engine)
      ->isValid(FILA_CAST(IndexBuffer, indexBuffer));
}

bool FilaEngine_isValidVertexBuffer(FilaEngine *engine,
                                    FilaVertexBuffer *vertexBuffer) {
  return FILA_CAST(Engine, engine)
      ->isValid(FILA_CAST(VertexBuffer, vertexBuffer));
}

bool FilaEngine_isValidBufferObject(FilaEngine *engine,
                                    FilaBufferObject *bufferObject) {
  return FILA_CAST(Engine, engine)
      ->isValid(FILA_CAST(BufferObject, bufferObject));
}

bool FilaEngine_isValidSkinningBuffer(FilaEngine *engine,
                                      FilaSkinningBuffer *skinningBuffer) {
  return FILA_CAST(Engine, engine)
      ->isValid(FILA_CAST(SkinningBuffer, skinningBuffer));
}

bool FilaEngine_isValidMorphTargetBuffer(
    FilaEngine *engine, FilaMorphTargetBuffer *morphTargetBuffer) {
  return FILA_CAST(Engine, engine)
      ->isValid(FILA_CAST(MorphTargetBuffer, morphTargetBuffer));
}

bool FilaEngine_isValidIndirectLight(FilaEngine *engine,
                                     FilaIndirectLight *indirectLight) {
  return FILA_CAST(Engine, engine)
      ->isValid(FILA_CAST(IndirectLight, indirectLight));
}

bool FilaEngine_isValidMaterial(FilaEngine *engine, FilaMaterial *material) {
  return FILA_CAST(Engine, engine)->isValid(FILA_CAST(Material, material));
}

bool FilaEngine_isValidMaterialInstance(
    FilaEngine *engine, FilaMaterial *material,
    FilaMaterialInstance *materialInstance) {
  return FILA_CAST(Engine, engine)
      ->isValid(FILA_CAST(Material, material),
                FILA_CAST(MaterialInstance, materialInstance));
}

bool FilaEngine_isValidExpensiveMaterialInstance(
    FilaEngine *engine, FilaMaterialInstance *materialInstance) {
  return FILA_CAST(Engine, engine)
      ->isValidExpensive(FILA_CAST(MaterialInstance, materialInstance));
}

bool FilaEngine_isValidSkybox(FilaEngine *engine, FilaSkybox *skybox) {
  return FILA_CAST(Engine, engine)->isValid(FILA_CAST(Skybox, skybox));
}

bool FilaEngine_isValidColorGrading(FilaEngine *engine,
                                    FilaColorGrading *colorGrading) {
  return FILA_CAST(Engine, engine)
      ->isValid(FILA_CAST(ColorGrading, colorGrading));
}

bool FilaEngine_isValidTexture(FilaEngine *engine, FilaTexture *texture) {
  return FILA_CAST(Engine, engine)->isValid(FILA_CAST(Texture, texture));
}

bool FilaEngine_isValidRenderTarget(FilaEngine *engine,
                                    FilaRenderTarget *target) {
  return FILA_CAST(Engine, engine)->isValid(FILA_CAST(RenderTarget, target));
}

bool FilaEngine_isValidSwapChain(FilaEngine *engine, FilaSwapChain *swapChain) {
  return FILA_CAST(Engine, engine)->isValid(FILA_CAST(SwapChain, swapChain));
}

bool FilaEngine_flushAndWait(FilaEngine *engine, uint64_t timeout) {
  return FILA_CAST(Engine, engine)->flushAndWait(timeout);
}

void FilaEngine_flush(FilaEngine *engine) {
  FILA_CAST(Engine, engine)->flush();
}

bool FilaEngine_hasUnrecoverableFailure(FilaEngine *engine) {
  return FILA_CAST(Engine, engine)->hasUnrecoverableFailure();
}

bool FilaEngine_isPaused(FilaEngine *engine) {
  return FILA_CAST(Engine, engine)->isPaused();
}

void FilaEngine_setPaused(FilaEngine *engine, bool paused) {
  FILA_CAST(Engine, engine)->setPaused(paused);
}

void FilaEngine_unprotected(FilaEngine *engine) {
  FILA_CAST(Engine, engine)->unprotected();
}

FilaTransformManager *FilaEngine_getTransformManager(FilaEngine *engine) {
  return reinterpret_cast<FilaTransformManager *>(
      &FILA_CAST(Engine, engine)->getTransformManager());
}

FilaLightManager *FilaEngine_getLightManager(FilaEngine *engine) {
  return reinterpret_cast<FilaLightManager *>(
      &FILA_CAST(Engine, engine)->getLightManager());
}

FilaRenderableManager *FilaEngine_getRenderableManager(FilaEngine *engine) {
  return reinterpret_cast<FilaRenderableManager *>(
      &FILA_CAST(Engine, engine)->getRenderableManager());
}

FilaEntityManager *FilaEngine_getEntityManager(FilaEngine *engine) {
  return reinterpret_cast<FilaEntityManager *>(
      &FILA_CAST(Engine, engine)->getEntityManager());
}

void FilaEngine_setAutomaticInstancingEnabled(FilaEngine *engine, bool enable) {
  FILA_CAST(Engine, engine)->setAutomaticInstancingEnabled(enable);
}

bool FilaEngine_isAutomaticInstancingEnabled(FilaEngine *engine) {
  return FILA_CAST(Engine, engine)->isAutomaticInstancingEnabled();
}

size_t FilaEngine_getMaxStereoscopicEyes(FilaEngine *engine) {
  return FILA_CAST(Engine, engine)->getMaxStereoscopicEyes();
}

FilaEngineFeatureLevel FilaEngine_getSupportedFeatureLevel(FilaEngine *engine) {
  return static_cast<FilaEngineFeatureLevel>(
      FILA_CAST(Engine, engine)->getSupportedFeatureLevel());
}

FilaEngineFeatureLevel FilaEngine_getActiveFeatureLevel(FilaEngine *engine) {
  return static_cast<FilaEngineFeatureLevel>(
      FILA_CAST(Engine, engine)->getActiveFeatureLevel());
}

FilaEngineFeatureLevel
FilaEngine_setActiveFeatureLevel(FilaEngine *engine,
                                 FilaEngineFeatureLevel featureLevel) {
  return static_cast<FilaEngineFeatureLevel>(
      FILA_CAST(Engine, engine)
          ->setActiveFeatureLevel(
              static_cast<Engine::FeatureLevel>(featureLevel)));
}

bool FilaEngine_hasFeatureFlag(FilaEngine *engine, const char *name) {
  return FILA_CAST(Engine, engine)->getFeatureFlag(name).has_value();
}

void FilaEngine_setFeatureFlag(FilaEngine *engine, const char *name,
                               bool value) {
  FILA_CAST(Engine, engine)->setFeatureFlag(name, value);
}

bool FilaEngine_getFeatureFlag(FilaEngine *engine, const char *name) {
  return FILA_CAST(Engine, engine)->getFeatureFlag(name).value_or(false);
}

uint64_t FilaEngine_getSteadyClockTimeNano() {
  return Engine::getSteadyClockTimeNano();
}

void FilaEngine_enableAccurateTranslations(FilaEngine* engine) {
    FILA_CAST(Engine, engine)->enableAccurateTranslations();
}

void FilaEngine_compile(FilaEngine* engine, uint8_t priority, FilaMaterial* material, FilaView* view,
        uint8_t shadowReceiver, uint8_t skinning, FilaEngineCompileCallback callback, void* userData) {
    using CPQ = filament::backend::CompilerPriorityQueue;
    auto toTribool = [](uint8_t v) -> utils::tribool {
        if (v == 0) return utils::tribool(false);
        if (v == 1) return utils::tribool(true);
        return utils::tribool(utils::tribool::kIndeterminate);
    };
    auto cb = callback
        ? utils::Invocable<void(Material* UTILS_NONNULL)>([callback, userData](Material*) { callback(userData); })
        : utils::Invocable<void(Material* UTILS_NONNULL)>{};
    FILA_CAST(Engine, engine)->compile(
        static_cast<CPQ>(priority),
        FILA_CAST(Material, material),
        FILA_CAST(View, view),
        toTribool(shadowReceiver),
        toTribool(skinning),
        nullptr,
        std::move(cb)
    );
}

} // extern "C"
