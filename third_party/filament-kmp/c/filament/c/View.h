#ifndef FILAMENT_C_VIEW_H
#define FILAMENT_C_VIEW_H

#include "FilaTypes.h"

#ifdef __cplusplus
extern "C" {
#endif



typedef enum FilaViewAntiAliasing {
    FILA_VIEW_ANTI_ALIASING_NONE = 0,
    FILA_VIEW_ANTI_ALIASING_FXAA = 1,
} FilaViewAntiAliasing;

typedef enum FilaViewDithering {
    FILA_VIEW_DITHERING_NONE = 0,
    FILA_VIEW_DITHERING_TEMPORAL = 1,
} FilaViewDithering;

typedef enum FilaViewShadowType {
    FILA_VIEW_SHADOW_TYPE_PCF = 0,
    FILA_VIEW_SHADOW_TYPE_VSM = 1,
} FilaViewShadowType;

typedef enum FilaViewBlendMode {
    FILA_VIEW_BLEND_MODE_OPAQUE = 0,
    FILA_VIEW_BLEND_MODE_TRANSLUCENT = 1,
} FilaViewBlendMode;

typedef enum FilaViewQualityLevel {
    FILA_VIEW_QUALITY_LEVEL_LOW = 0,
    FILA_VIEW_QUALITY_LEVEL_MEDIUM = 1,
    FILA_VIEW_QUALITY_LEVEL_HIGH = 2,
    FILA_VIEW_QUALITY_LEVEL_ULTRA = 3,
} FilaViewQualityLevel;

typedef struct FilaViewDynamicResolutionOptions {
    float minScale[2];
    float maxScale[2];
    float sharpness;
    bool enabled;
    bool homogeneousScaling;
    FilaViewQualityLevel quality;
} FilaViewDynamicResolutionOptions;

typedef struct FilaViewBloomOptions {
    FilaTexture* dirt;
    float dirtStrength;
    float strength;
    uint32_t resolution;
    uint8_t levels;
    int blendMode;
    bool threshold;
    bool enabled;
    float highlight;
    FilaViewQualityLevel quality;
    bool lensFlare;
    bool starburst;
    float chromaticAberration;
    uint8_t ghostCount;
    float ghostSpacing;
    float ghostThreshold;
    float haloThickness;
    float haloRadius;
    float haloThreshold;
} FilaViewBloomOptions;

typedef struct FilaViewFogOptions {
    float distance;
    float cutOffDistance;
    float maximumOpacity;
    float height;
    float heightFalloff;
    float color[3];
    float density;
    float inScatteringStart;
    float inScatteringSize;
    bool fogColorFromIbl;
    FilaTexture* skyColor;
    bool enabled;
} FilaViewFogOptions;

typedef struct FilaViewDepthOfFieldOptions {
    float cocScale;
    float maxApertureDiameter;
    bool enabled;
    int filter;
    bool nativeResolution;
    uint8_t foregroundRingCount;
    uint8_t backgroundRingCount;
    uint8_t fastGatherRingCount;
    uint16_t maxForegroundCOC;
    uint16_t maxBackgroundCOC;
} FilaViewDepthOfFieldOptions;

typedef struct FilaViewVignetteOptions {
    float midPoint;
    float roundness;
    float feather;
    float color[4];
    bool enabled;
} FilaViewVignetteOptions;

typedef struct FilaViewAmbientOcclusionOptions {
    float radius;
    float bias;
    float power;
    float resolution;
    float intensity;
    float bilateralThreshold;
    FilaViewQualityLevel quality;
    FilaViewQualityLevel lowPassFilter;
    FilaViewQualityLevel upsampling;
    bool enabled;
    bool bentNormals;
    float minHorizonAngleRad;
    struct {
        float lightConeRad;
        float shadowDistance;
        float contactDistanceMax;
        float intensity;
        float lightDirection[3];
        float depthBias;
        float depthSlopeBias;
        uint8_t sampleCount;
        uint8_t rayCount;
        bool enabled;
    } ssct;
    int aoType;
} FilaViewAmbientOcclusionOptions;

typedef struct FilaViewTemporalAntiAliasingOptions {
    float filterWidth;
    float feedback;
    float lodBias;
    float sharpness;
    bool enabled;
    float upscaling;
    bool filterHistory;
    bool filterInput;
    bool useYCoCg;
    bool hdr;
    int boxType;
    int boxClipping;
    int jitterPattern;
    float varianceGamma;
    bool preventFlickering;
    bool historyReprojection;
} FilaViewTemporalAntiAliasingOptions;

typedef struct FilaViewMultiSampleAntiAliasingOptions {
    bool enabled;
    uint8_t sampleCount;
    bool customResolve;
} FilaViewMultiSampleAntiAliasingOptions;

typedef struct FilaViewScreenSpaceReflectionsOptions {
    float thickness;
    float bias;
    float maxDistance;
    float stride;
    bool enabled;
} FilaViewScreenSpaceReflectionsOptions;

typedef struct FilaViewVsmShadowOptions {
    uint8_t anisotropy;
    bool mipmapping;
    uint8_t msaaSamples;
    bool highPrecision;
    float minVarianceScale;
    float lightBleedReduction;
} FilaViewVsmShadowOptions;

typedef struct FilaViewSoftShadowOptions {
    float penumbraScale;
    float penumbraRatioScale;
} FilaViewSoftShadowOptions;

typedef struct FilaViewGuardBandOptions {
    bool enabled;
} FilaViewGuardBandOptions;

typedef struct FilaViewStereoscopicOptions {
    bool enabled;
} FilaViewStereoscopicOptions;

typedef struct FilaViewPickingQueryResult {
    FilaEntity renderable;
    float depth;
    float fragCoords[3];
} FilaViewPickingQueryResult;

typedef void (*FilaViewPickingCallback)(const FilaViewPickingQueryResult* result, void* userData);

// View
void FilaView_setName(FilaView* view, const char* name);
const char* FilaView_getName(const FilaView* view);
void FilaView_setScene(FilaView* view, FilaScene* scene);
void FilaView_setCamera(FilaView* view, FilaCamera* camera);
bool FilaView_hasCamera(const FilaView* view);

void FilaView_setColorGrading(FilaView* view, FilaColorGrading* colorGrading);
void FilaView_setViewport(FilaView* view, int left, int bottom, uint32_t width, uint32_t height);
void FilaView_getViewport(const FilaView* view, int* left, int* bottom, uint32_t* width, uint32_t* height);
void FilaView_setVisibleLayers(FilaView* view, uint8_t select, uint8_t value);
uint8_t FilaView_getVisibleLayers(const FilaView* view);

void FilaView_setRenderTarget(FilaView* view, FilaRenderTarget* renderTarget);

void FilaView_setAntiAliasing(FilaView* view, FilaViewAntiAliasing type);
FilaViewAntiAliasing FilaView_getAntiAliasing(const FilaView* view);

void FilaView_setDithering(FilaView* view, FilaViewDithering dithering);
FilaViewDithering FilaView_getDithering(const FilaView* view);

void FilaView_setDynamicResolutionOptions(FilaView* view, const FilaViewDynamicResolutionOptions* options);
void FilaView_getDynamicResolutionOptions(const FilaView* view, FilaViewDynamicResolutionOptions* out);
void FilaView_getLastDynamicResolutionScale(const FilaView* view, float out[2]);

void FilaView_setShadowType(FilaView* view, FilaViewShadowType type);
void FilaView_setVsmShadowOptions(FilaView* view, const FilaViewVsmShadowOptions* options);
void FilaView_getVsmShadowOptions(const FilaView* view, FilaViewVsmShadowOptions* out);
void FilaView_setSoftShadowOptions(FilaView* view, const FilaViewSoftShadowOptions* options);
void FilaView_getSoftShadowOptions(const FilaView* view, FilaViewSoftShadowOptions* out);

void FilaView_setRenderQuality(FilaView* view, FilaViewQualityLevel hdrColorBufferQuality);
FilaViewQualityLevel FilaView_getRenderQuality(const FilaView* view);
void FilaView_setDynamicLightingOptions(FilaView* view, float zLightNear, float zLightFar);

void FilaView_setShadowingEnabled(FilaView* view, bool enabled);
bool FilaView_isShadowingEnabled(const FilaView* view);

void FilaView_setPostProcessingEnabled(FilaView* view, bool enabled);
bool FilaView_isPostProcessingEnabled(const FilaView* view);

void FilaView_setFrontFaceWindingInverted(FilaView* view, bool inverted);
bool FilaView_isFrontFaceWindingInverted(const FilaView* view);

void FilaView_setTransparentPickingEnabled(FilaView* view, bool enabled);
bool FilaView_isTransparentPickingEnabled(const FilaView* view);

void FilaView_setAmbientOcclusionOptions(FilaView* view, const FilaViewAmbientOcclusionOptions* options);
void FilaView_getAmbientOcclusionOptions(const FilaView* view, FilaViewAmbientOcclusionOptions* out);
void FilaView_setBloomOptions(FilaView* view, const FilaViewBloomOptions* options);
void FilaView_getBloomOptions(const FilaView* view, FilaViewBloomOptions* out);
void FilaView_setFogOptions(FilaView* view, const FilaViewFogOptions* options);
void FilaView_getFogOptions(const FilaView* view, FilaViewFogOptions* out);
void FilaView_setBlendMode(FilaView* view, FilaViewBlendMode blendMode);
FilaViewBlendMode FilaView_getBlendMode(const FilaView* view);
void FilaView_setDepthOfFieldOptions(FilaView* view, const FilaViewDepthOfFieldOptions* options);
void FilaView_getDepthOfFieldOptions(const FilaView* view, FilaViewDepthOfFieldOptions* out);
void FilaView_setVignetteOptions(FilaView* view, const FilaViewVignetteOptions* options);
void FilaView_getVignetteOptions(const FilaView* view, FilaViewVignetteOptions* out);
void FilaView_setTemporalAntiAliasingOptions(FilaView* view, const FilaViewTemporalAntiAliasingOptions* options);
void FilaView_getTemporalAntiAliasingOptions(const FilaView* view, FilaViewTemporalAntiAliasingOptions* out);
void FilaView_setMultiSampleAntiAliasingOptions(FilaView* view, const FilaViewMultiSampleAntiAliasingOptions* options);
void FilaView_getMultiSampleAntiAliasingOptions(const FilaView* view, FilaViewMultiSampleAntiAliasingOptions* out);
void FilaView_setScreenSpaceReflectionsOptions(FilaView* view, const FilaViewScreenSpaceReflectionsOptions* options);
void FilaView_getScreenSpaceReflectionsOptions(const FilaView* view, FilaViewScreenSpaceReflectionsOptions* out);

void FilaView_setStereoscopicOptions(FilaView* view, const FilaViewStereoscopicOptions* options);
void FilaView_getStereoscopicOptions(const FilaView* view, FilaViewStereoscopicOptions* out);

void FilaView_setGuardBandOptions(FilaView* view, const FilaViewGuardBandOptions* options);
void FilaView_getGuardBandOptions(const FilaView* view, FilaViewGuardBandOptions* out);

void FilaView_setFrustumCullingEnabled(FilaView* view, bool enabled);
bool FilaView_isFrustumCullingEnabled(const FilaView* view);

void FilaView_setScreenSpaceRefractionEnabled(FilaView* view, bool enabled);
bool FilaView_isScreenSpaceRefractionEnabled(const FilaView* view);

void FilaView_pick(FilaView* view, uint32_t x, uint32_t y, FilaCallbackHandler* handler, FilaViewPickingCallback callback, void* userData);

void FilaView_setStencilBufferEnabled(FilaView* view, bool enabled);
bool FilaView_isStencilBufferEnabled(const FilaView* view);

void FilaView_setGridSize(FilaView* view, double size);
double FilaView_getGridSize(const FilaView* view);
double FilaView_getEffectiveGridSize(const FilaView* view);
void FilaView_setMaterialGlobal(FilaView* view, uint32_t index, float x, float y, float z, float w);
void FilaView_getMaterialGlobal(const FilaView* view, uint32_t index, float out[4]);

FilaEntity FilaView_getFogEntity(const FilaView* view);
int32_t FilaView_getVisibleRenderableCount(const FilaView* view);
void FilaView_clearFrameHistory(FilaView* view, FilaEngine* engine);

void FilaView_setChannelDepthClearEnabled(FilaView* view, uint8_t channel, bool enabled);
bool FilaView_isChannelDepthClearEnabled(const FilaView* view, uint8_t channel);

#ifdef __cplusplus
}
#endif

#endif // FILAMENT_C_VIEW_H
