package io.github.erkko68.filament

import io.github.erkko68.filament.ffm.FilamentC
import io.github.erkko68.filament.ffm.FilaViewAmbientOcclusionOptions
import io.github.erkko68.filament.ffm.FilaViewBloomOptions
import io.github.erkko68.filament.ffm.FilaViewDepthOfFieldOptions
import io.github.erkko68.filament.ffm.FilaViewDynamicResolutionOptions
import io.github.erkko68.filament.ffm.FilaViewFogOptions
import io.github.erkko68.filament.ffm.FilaViewGuardBandOptions
import io.github.erkko68.filament.ffm.FilaViewMultiSampleAntiAliasingOptions
import io.github.erkko68.filament.ffm.FilaViewPickingCallback
import io.github.erkko68.filament.ffm.FilaViewPickingQueryResult
import io.github.erkko68.filament.ffm.FilaViewScreenSpaceReflectionsOptions
import io.github.erkko68.filament.ffm.FilaViewSoftShadowOptions
import io.github.erkko68.filament.ffm.FilaViewStereoscopicOptions
import io.github.erkko68.filament.ffm.FilaViewTemporalAntiAliasingOptions
import io.github.erkko68.filament.ffm.FilaViewVignetteOptions
import io.github.erkko68.filament.ffm.FilaViewVsmShadowOptions
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

// One-shot picking callback: persistent stub keyed by userData address (same approach as Completions).
private object Picking {
    private val arena = Arena.ofShared()
    private val actions = ConcurrentHashMap<Long, (View.PickingQueryResult) -> Unit>()
    private val counter = AtomicLong(1L)

    val stub: MemorySegment by lazy {
        FilaViewPickingCallback.allocate({ result, userData ->
            val cb = actions.remove(userData.address())
            if (cb != null && !result.isNullPtr()) {
                val r = result.reinterpret(FilaViewPickingQueryResult.layout().byteSize())
                val fragCoordsSeg = FilaViewPickingQueryResult.fragCoords(r)
                cb(View.PickingQueryResult(
                    FilaViewPickingQueryResult.renderable(r),
                    FilaViewPickingQueryResult.depth(r),
                    floatArrayOf(
                        fragCoordsSeg.getAtIndex(ValueLayout.JAVA_FLOAT, 0L),
                        fragCoordsSeg.getAtIndex(ValueLayout.JAVA_FLOAT, 1L),
                        fragCoordsSeg.getAtIndex(ValueLayout.JAVA_FLOAT, 2L),
                    )
                ))
            }
        }, arena)
    }

    fun register(cb: (View.PickingQueryResult) -> Unit): MemorySegment {
        val id = counter.getAndIncrement()
        actions[id] = cb
        return MemorySegment.ofAddress(id)
    }
}

actual class View internal constructor(internal var nativeHandle: MemorySegment?) {
    actual enum class Dithering { NONE, TEMPORAL }
    actual enum class BlendMode { OPAQUE, TRANSLUCENT }
    actual enum class Quality { LOW, MEDIUM, HIGH, ULTRA }
    actual enum class ShadowType { PCF, VSM, DPCF, PCSS, PCFd }
    actual enum class AntiAliasing { NONE, FXAA }

    actual class PickingQueryResult actual constructor(
        actual val renderable: Int,
        actual val depth: Float,
        actual val fragCoords: FloatArray
    )

    private var mScene: Scene? = null
    private var mCamera: Camera? = null
    private var mRenderTarget: RenderTarget? = null
    private var mShadowType: ShadowType = ShadowType.PCF
    private var mColorGrading: ColorGrading? = null

    actual class DynamicResolutionOptions actual constructor() {
        actual var enabled: Boolean = false
        actual var homogeneousScaling: Boolean = false
        actual var minScale: Float = 0.5f
        actual var maxScale: Float = 1.0f
        actual var sharpness: Float = 0.9f
        actual var quality: Quality = Quality.LOW
    }

    actual class RenderQuality actual constructor() {
        actual var hdrColorBuffer: Quality = Quality.HIGH
    }

    actual class BloomOptions actual constructor() {
        actual var enabled: Boolean = false
        actual var levels: Int = 6
        actual var resolution: Int = 384
        actual var strength: Float = 0.10f
        actual var threshold: Boolean = true
        actual var dirt: Texture? = null
        actual var dirtStrength: Float = 0.2f
        actual var quality: Quality = Quality.LOW
        actual var lensFlare: Boolean = false
        actual var starburst: Boolean = true
        actual var chromaticAberration: Float = 0.005f
        actual var ghostCount: Int = 4
        actual var ghostSpacing: Float = 0.6f
        actual var ghostThreshold: Float = 10.0f
        actual var haloRadius: Float = 0.4f
        actual var haloThickness: Float = 0.1f
        actual var haloThreshold: Float = 10.0f
        actual var highlight: Float = 1000.0f
        actual var blendMode: BlendMode = BlendMode.ADD
        actual enum class BlendMode { ADD, INTERPOLATE }
    }

    actual class FogOptions actual constructor() {
        actual var enabled: Boolean = false
        actual var distance: Float = 0.0f
        actual var density: Float = 0.1f
        actual var height: Float = 0.0f
        actual var heightFalloff: Float = 1.0f
        actual var color: FloatArray = floatArrayOf(1.0f, 1.0f, 1.0f)
        actual var densityMap: Texture? = null
        actual var cutOffDistance: Float = Float.POSITIVE_INFINITY
        actual var maximumOpacity: Float = 1.0f
        actual var inScatteringStart: Float = 0.0f
        actual var inScatteringSize: Float = -1.0f
        actual var fogColorFromIbl: Boolean = false
        actual var skyColor: Texture? = null
    }

    actual class DepthOfFieldOptions actual constructor() {
        actual var enabled: Boolean = false
        actual var cocScale: Float = 1.0f
        actual var maxApertureDiameter: Float = 0.01f
        actual var filter: Filter = Filter.MEDIAN
        actual var nativeResolution: Boolean = false
        actual var foregroundRingCount: Int = 0
        actual var backgroundRingCount: Int = 0
        actual var fastGatherRingCount: Int = 0
        actual var maxForegroundCOC: Int = 0
        actual var maxBackgroundCOC: Int = 0
        actual enum class Filter { NONE, UNUSED, MEDIAN }
    }

    actual class VignetteOptions actual constructor() {
        actual var enabled: Boolean = false
        actual var midPoint: Float = 0.5f
        actual var roundness: Float = 0.5f
        actual var feather: Float = 0.5f
        actual var color: FloatArray = floatArrayOf(0.0f, 0.0f, 0.0f, 1.0f)
    }

    actual class AmbientOcclusionOptions actual constructor() {
        actual enum class AmbientOcclusionType { SAO, GTAO }
        actual var aoType: AmbientOcclusionType = AmbientOcclusionType.SAO
        actual var radius: Float = 0.3f
        actual var bias: Float = 0.0005f
        actual var intensity: Float = 1.0f
        actual var power: Float = 1.0f
        actual var minHorizonAngleRad: Float = 0.0f
        actual var quality: Quality = Quality.LOW
        actual var lowPassFilter: Quality = Quality.MEDIUM
        actual var upsampling: Quality = Quality.LOW
        actual var enabled: Boolean = false
        actual var bentNormals: Boolean = false
        actual var bilateralThreshold: Float = 0.05f
        actual var resolution: Float = 0.5f
        actual var ssct: Ssct = Ssct()
        actual class Ssct actual constructor() {
            actual var enabled: Boolean = false
            actual var lightConeRad: Float = 1.0f
            actual var shadowDistance: Float = 0.3f
            actual var contactDistanceMax: Float = 1.0f
            actual var intensity: Float = 0.8f
            actual var lightDirection: FloatArray = floatArrayOf(0f, -1f, 0f)
            actual var depthBias: Float = 0.01f
            actual var depthSlopeBias: Float = 0.01f
            actual var sampleCount: Int = 4
            actual var rayCount: Int = 1
        }
    }

    actual class TemporalAntiAliasingOptions actual constructor() {
        actual enum class BoxType { AABB, AABB_VARIANCE }
        actual enum class BoxClipping { ACCURATE, CLAMP, NONE }
        actual enum class JitterPattern { RGSS_X4, UNIFORM_HELIX_X4, HALTON_23_X8, HALTON_23_X16, HALTON_23_X32 }
        actual var feedback: Float = 0.12f
        actual var enabled: Boolean = false
        actual var lodBias: Float = -1.0f
        actual var sharpness: Float = 0.0f
        actual var upscaling: Float = 1.0f
        actual var filterHistory: Boolean = true
        actual var filterInput: Boolean = true
        actual var useYCoCg: Boolean = false
        actual var hdr: Boolean = true
        actual var boxType: BoxType = BoxType.AABB
        actual var boxClipping: BoxClipping = BoxClipping.ACCURATE
        actual var jitterPattern: JitterPattern = JitterPattern.HALTON_23_X16
        actual var varianceGamma: Float = 1.0f
        actual var preventFlickering: Boolean = false
        actual var historyReprojection: Boolean = true
    }

    actual class ScreenSpaceReflectionsOptions actual constructor() {
        actual var enabled: Boolean = false
        actual var thickness: Float = 0.1f
        actual var bias: Float = 0.01f
        actual var maxDistance: Float = 3.0f
        actual var stride: Float = 2.0f
    }

    actual class VsmShadowOptions actual constructor() {
        actual var anisotropy: Int = 0
        actual var mipmapping: Boolean = false
        actual var msaaSamples: Int = 1
        actual var highPrecision: Boolean = false
        actual var lightBleedReduction: Float = 0.15f
    }

    actual class SoftShadowOptions actual constructor() {
        actual var penumbraScale: Float = 1.0f
        actual var penumbraRatioScale: Float = 1.0f
    }

    actual class GuardBandOptions actual constructor() {
        actual var enabled: Boolean = false
    }

    actual class StereoscopicOptions actual constructor() {
        actual var enabled: Boolean = false
    }

    actual class MultiSampleAntiAliasingOptions actual constructor() {
        actual var enabled: Boolean = false
        actual var sampleCount: Int = 4
        actual var customResolve: Boolean = false
    }

    actual var name: String?
        get() = FilamentC.FilaView_getName(nativeHandle).let { if (it.isNullPtr()) null else it.cString() }
        set(value) { confined { arena -> FilamentC.FilaView_setName(nativeHandle, arena.cstr(value ?: "")) } }

    actual var scene: Scene?
        get() = mScene
        set(value) {
            mScene = value
            FilamentC.FilaView_setScene(nativeHandle, value?.nativeHandle ?: NULL)
        }

    actual var camera: Camera?
        get() = mCamera
        set(value) {
            mCamera = value
            FilamentC.FilaView_setCamera(nativeHandle, value?.nativeHandle ?: NULL)
        }
    actual val hasCamera: Boolean get() = FilamentC.FilaView_hasCamera(nativeHandle)

    actual var viewport: Viewport
        get() = confined { arena ->
            val left = arena.allocate(ValueLayout.JAVA_INT)
            val bottom = arena.allocate(ValueLayout.JAVA_INT)
            val width = arena.allocate(ValueLayout.JAVA_INT)
            val height = arena.allocate(ValueLayout.JAVA_INT)
            FilamentC.FilaView_getViewport(nativeHandle, left, bottom, width, height)
            Viewport(left.get(ValueLayout.JAVA_INT, 0), bottom.get(ValueLayout.JAVA_INT, 0), width.get(ValueLayout.JAVA_INT, 0), height.get(ValueLayout.JAVA_INT, 0))
        }
        set(value) { FilamentC.FilaView_setViewport(nativeHandle, value.left, value.bottom, value.width, value.height) }

    actual var blendMode: BlendMode
        get() = BlendMode.values()[FilamentC.FilaView_getBlendMode(nativeHandle)]
        set(value) { FilamentC.FilaView_setBlendMode(nativeHandle, value.ordinal) }

    actual fun setVisibleLayers(select: Int, values: Int) { FilamentC.FilaView_setVisibleLayers(nativeHandle, select.toByte(), values.toByte()) }
    actual fun setLayerEnabled(layer: Int, enabled: Boolean) {
        val mask = (1 shl layer).toByte()
        FilamentC.FilaView_setVisibleLayers(nativeHandle, mask, if (enabled) mask else 0)
    }
    actual fun getVisibleLayers(): Int = FilamentC.FilaView_getVisibleLayers(nativeHandle).toInt()

    actual var isPostProcessingEnabled: Boolean
        get() = FilamentC.FilaView_isPostProcessingEnabled(nativeHandle)
        set(value) { FilamentC.FilaView_setPostProcessingEnabled(nativeHandle, value) }

    actual var dithering: Dithering
        get() = Dithering.values()[FilamentC.FilaView_getDithering(nativeHandle)]
        set(value) { FilamentC.FilaView_setDithering(nativeHandle, value.ordinal) }

    actual var dynamicResolutionOptions: DynamicResolutionOptions
        get() = confined { arena ->
            val out = FilaViewDynamicResolutionOptions.allocate(arena)
            FilamentC.FilaView_getDynamicResolutionOptions(nativeHandle, out)
            DynamicResolutionOptions().apply {
                enabled = FilaViewDynamicResolutionOptions.enabled(out)
                homogeneousScaling = FilaViewDynamicResolutionOptions.homogeneousScaling(out)
                val minScaleSeg = FilaViewDynamicResolutionOptions.minScale(out)
                minScale = minScaleSeg.getAtIndex(ValueLayout.JAVA_FLOAT, 0L)
                val maxScaleSeg = FilaViewDynamicResolutionOptions.maxScale(out)
                maxScale = maxScaleSeg.getAtIndex(ValueLayout.JAVA_FLOAT, 0L)
                sharpness = FilaViewDynamicResolutionOptions.sharpness(out)
                quality = Quality.entries[FilaViewDynamicResolutionOptions.quality(out)]
            }
        }
        set(value) {
            confined { arena ->
                val c = FilaViewDynamicResolutionOptions.allocate(arena)
                FilaViewDynamicResolutionOptions.enabled(c, value.enabled)
                FilaViewDynamicResolutionOptions.homogeneousScaling(c, value.homogeneousScaling)
                val minScaleSeg = FilaViewDynamicResolutionOptions.minScale(c)
                minScaleSeg.setAtIndex(ValueLayout.JAVA_FLOAT, 0L, value.minScale)
                minScaleSeg.setAtIndex(ValueLayout.JAVA_FLOAT, 1L, value.minScale)
                val maxScaleSeg = FilaViewDynamicResolutionOptions.maxScale(c)
                maxScaleSeg.setAtIndex(ValueLayout.JAVA_FLOAT, 0L, value.maxScale)
                maxScaleSeg.setAtIndex(ValueLayout.JAVA_FLOAT, 1L, value.maxScale)
                FilaViewDynamicResolutionOptions.sharpness(c, value.sharpness)
                FilaViewDynamicResolutionOptions.quality(c, value.quality.ordinal)
                FilamentC.FilaView_setDynamicResolutionOptions(nativeHandle, c)
            }
        }

    actual fun getLastDynamicResolutionScale(): FloatArray = confined { arena ->
        val out = arena.floatArr(2)
        FilamentC.FilaView_getLastDynamicResolutionScale(nativeHandle, out)
        out.toFloats()
    }

    actual var renderQuality: RenderQuality
        get() = RenderQuality().apply {
            hdrColorBuffer = Quality.entries[FilamentC.FilaView_getRenderQuality(nativeHandle)]
        }
        set(value) { FilamentC.FilaView_setRenderQuality(nativeHandle, value.hdrColorBuffer.ordinal) }

    actual var bloomOptions: BloomOptions
        get() = confined { arena ->
            val out = FilaViewBloomOptions.allocate(arena)
            FilamentC.FilaView_getBloomOptions(nativeHandle, out)
            BloomOptions().apply {
                enabled = FilaViewBloomOptions.enabled(out)
                levels = FilaViewBloomOptions.levels(out).toInt()
                resolution = FilaViewBloomOptions.resolution(out)
                strength = FilaViewBloomOptions.strength(out)
                threshold = FilaViewBloomOptions.threshold(out)
                dirtStrength = FilaViewBloomOptions.dirtStrength(out)
                quality = Quality.entries[FilaViewBloomOptions.quality(out)]
                lensFlare = FilaViewBloomOptions.lensFlare(out)
                starburst = FilaViewBloomOptions.starburst(out)
                chromaticAberration = FilaViewBloomOptions.chromaticAberration(out)
                ghostCount = FilaViewBloomOptions.ghostCount(out).toInt()
                ghostSpacing = FilaViewBloomOptions.ghostSpacing(out)
                ghostThreshold = FilaViewBloomOptions.ghostThreshold(out)
                haloRadius = FilaViewBloomOptions.haloRadius(out)
                haloThickness = FilaViewBloomOptions.haloThickness(out)
                haloThreshold = FilaViewBloomOptions.haloThreshold(out)
                highlight = FilaViewBloomOptions.highlight(out)
                blendMode = BloomOptions.BlendMode.entries[FilaViewBloomOptions.blendMode(out)]
            }
        }
        set(value) {
            confined { arena ->
                val c = FilaViewBloomOptions.allocate(arena)
                FilaViewBloomOptions.enabled(c, value.enabled)
                FilaViewBloomOptions.levels(c, value.levels.toByte())
                FilaViewBloomOptions.resolution(c, value.resolution)
                FilaViewBloomOptions.strength(c, value.strength)
                FilaViewBloomOptions.threshold(c, value.threshold)
                FilaViewBloomOptions.dirt(c, value.dirt?.nativeHandle ?: NULL)
                FilaViewBloomOptions.dirtStrength(c, value.dirtStrength)
                FilaViewBloomOptions.quality(c, value.quality.ordinal)
                FilaViewBloomOptions.highlight(c, value.highlight)
                FilaViewBloomOptions.blendMode(c, value.blendMode.ordinal)
                FilaViewBloomOptions.chromaticAberration(c, value.chromaticAberration)
                FilaViewBloomOptions.lensFlare(c, value.lensFlare)
                FilaViewBloomOptions.starburst(c, value.starburst)
                FilaViewBloomOptions.ghostCount(c, value.ghostCount.toByte())
                FilaViewBloomOptions.ghostSpacing(c, value.ghostSpacing)
                FilaViewBloomOptions.ghostThreshold(c, value.ghostThreshold)
                FilaViewBloomOptions.haloRadius(c, value.haloRadius)
                FilaViewBloomOptions.haloThickness(c, value.haloThickness)
                FilaViewBloomOptions.haloThreshold(c, value.haloThreshold)
                FilamentC.FilaView_setBloomOptions(nativeHandle, c)
            }
        }

    actual var fogOptions: FogOptions
        get() = confined { arena ->
            val out = FilaViewFogOptions.allocate(arena)
            FilamentC.FilaView_getFogOptions(nativeHandle, out)
            FogOptions().apply {
                enabled = FilaViewFogOptions.enabled(out)
                distance = FilaViewFogOptions.distance(out)
                density = FilaViewFogOptions.density(out)
                height = FilaViewFogOptions.height(out)
                heightFalloff = FilaViewFogOptions.heightFalloff(out)
                val fogColorSeg = FilaViewFogOptions.color(out)
                color = floatArrayOf(fogColorSeg.getAtIndex(ValueLayout.JAVA_FLOAT, 0L), fogColorSeg.getAtIndex(ValueLayout.JAVA_FLOAT, 1L), fogColorSeg.getAtIndex(ValueLayout.JAVA_FLOAT, 2L))
                cutOffDistance = FilaViewFogOptions.cutOffDistance(out)
                maximumOpacity = FilaViewFogOptions.maximumOpacity(out)
                inScatteringStart = FilaViewFogOptions.inScatteringStart(out)
                inScatteringSize = FilaViewFogOptions.inScatteringSize(out)
                fogColorFromIbl = FilaViewFogOptions.fogColorFromIbl(out)
            }
        }
        set(value) {
            confined { arena ->
                val c = FilaViewFogOptions.allocate(arena)
                FilaViewFogOptions.enabled(c, value.enabled)
                FilaViewFogOptions.distance(c, value.distance)
                FilaViewFogOptions.density(c, value.density)
                FilaViewFogOptions.height(c, value.height)
                FilaViewFogOptions.heightFalloff(c, value.heightFalloff)
                val fogColorSeg = FilaViewFogOptions.color(c)
                fogColorSeg.setAtIndex(ValueLayout.JAVA_FLOAT, 0L, value.color[0])
                fogColorSeg.setAtIndex(ValueLayout.JAVA_FLOAT, 1L, value.color[1])
                fogColorSeg.setAtIndex(ValueLayout.JAVA_FLOAT, 2L, value.color[2])
                FilaViewFogOptions.cutOffDistance(c, value.cutOffDistance)
                FilaViewFogOptions.maximumOpacity(c, value.maximumOpacity)
                FilaViewFogOptions.inScatteringStart(c, value.inScatteringStart)
                FilaViewFogOptions.inScatteringSize(c, value.inScatteringSize)
                FilaViewFogOptions.fogColorFromIbl(c, value.fogColorFromIbl)
                FilaViewFogOptions.skyColor(c, value.skyColor?.nativeHandle ?: NULL)
                FilamentC.FilaView_setFogOptions(nativeHandle, c)
            }
        }

    actual var depthOfFieldOptions: DepthOfFieldOptions
        get() = confined { arena ->
            val out = FilaViewDepthOfFieldOptions.allocate(arena)
            FilamentC.FilaView_getDepthOfFieldOptions(nativeHandle, out)
            DepthOfFieldOptions().apply {
                enabled = FilaViewDepthOfFieldOptions.enabled(out)
                cocScale = FilaViewDepthOfFieldOptions.cocScale(out)
                maxApertureDiameter = FilaViewDepthOfFieldOptions.maxApertureDiameter(out)
                filter = DepthOfFieldOptions.Filter.entries[FilaViewDepthOfFieldOptions.filter(out)]
                nativeResolution = FilaViewDepthOfFieldOptions.nativeResolution(out)
                foregroundRingCount = FilaViewDepthOfFieldOptions.foregroundRingCount(out).toInt()
                backgroundRingCount = FilaViewDepthOfFieldOptions.backgroundRingCount(out).toInt()
                fastGatherRingCount = FilaViewDepthOfFieldOptions.fastGatherRingCount(out).toInt()
                maxForegroundCOC = FilaViewDepthOfFieldOptions.maxForegroundCOC(out).toInt()
                maxBackgroundCOC = FilaViewDepthOfFieldOptions.maxBackgroundCOC(out).toInt()
            }
        }
        set(value) {
            confined { arena ->
                val c = FilaViewDepthOfFieldOptions.allocate(arena)
                FilaViewDepthOfFieldOptions.enabled(c, value.enabled)
                FilaViewDepthOfFieldOptions.cocScale(c, value.cocScale)
                FilaViewDepthOfFieldOptions.maxApertureDiameter(c, value.maxApertureDiameter)
                FilaViewDepthOfFieldOptions.filter(c, value.filter.ordinal)
                FilaViewDepthOfFieldOptions.nativeResolution(c, value.nativeResolution)
                FilaViewDepthOfFieldOptions.foregroundRingCount(c, value.foregroundRingCount.toByte())
                FilaViewDepthOfFieldOptions.backgroundRingCount(c, value.backgroundRingCount.toByte())
                FilaViewDepthOfFieldOptions.fastGatherRingCount(c, value.fastGatherRingCount.toByte())
                FilaViewDepthOfFieldOptions.maxForegroundCOC(c, value.maxForegroundCOC.toShort())
                FilaViewDepthOfFieldOptions.maxBackgroundCOC(c, value.maxBackgroundCOC.toShort())
                FilamentC.FilaView_setDepthOfFieldOptions(nativeHandle, c)
            }
        }

    actual var vignetteOptions: VignetteOptions
        get() = confined { arena ->
            val out = FilaViewVignetteOptions.allocate(arena)
            FilamentC.FilaView_getVignetteOptions(nativeHandle, out)
            VignetteOptions().apply {
                enabled = FilaViewVignetteOptions.enabled(out)
                midPoint = FilaViewVignetteOptions.midPoint(out)
                roundness = FilaViewVignetteOptions.roundness(out)
                feather = FilaViewVignetteOptions.feather(out)
                val vigColorSeg = FilaViewVignetteOptions.color(out)
                color = floatArrayOf(vigColorSeg.getAtIndex(ValueLayout.JAVA_FLOAT, 0L), vigColorSeg.getAtIndex(ValueLayout.JAVA_FLOAT, 1L), vigColorSeg.getAtIndex(ValueLayout.JAVA_FLOAT, 2L), vigColorSeg.getAtIndex(ValueLayout.JAVA_FLOAT, 3L))
            }
        }
        set(value) {
            confined { arena ->
                val c = FilaViewVignetteOptions.allocate(arena)
                FilaViewVignetteOptions.enabled(c, value.enabled)
                FilaViewVignetteOptions.midPoint(c, value.midPoint)
                FilaViewVignetteOptions.roundness(c, value.roundness)
                FilaViewVignetteOptions.feather(c, value.feather)
                val vigColorSeg = FilaViewVignetteOptions.color(c)
                vigColorSeg.setAtIndex(ValueLayout.JAVA_FLOAT, 0L, value.color[0])
                vigColorSeg.setAtIndex(ValueLayout.JAVA_FLOAT, 1L, value.color[1])
                vigColorSeg.setAtIndex(ValueLayout.JAVA_FLOAT, 2L, value.color[2])
                vigColorSeg.setAtIndex(ValueLayout.JAVA_FLOAT, 3L, value.color[3])
                FilamentC.FilaView_setVignetteOptions(nativeHandle, c)
            }
        }

    actual var ambientOcclusionOptions: AmbientOcclusionOptions
        get() = confined { arena ->
            val out = FilaViewAmbientOcclusionOptions.allocate(arena)
            FilamentC.FilaView_getAmbientOcclusionOptions(nativeHandle, out)
            val ssctSeg = FilaViewAmbientOcclusionOptions.ssct(out)
            AmbientOcclusionOptions().apply {
                enabled = FilaViewAmbientOcclusionOptions.enabled(out)
                aoType = AmbientOcclusionOptions.AmbientOcclusionType.entries[FilaViewAmbientOcclusionOptions.aoType(out)]
                radius = FilaViewAmbientOcclusionOptions.radius(out)
                bias = FilaViewAmbientOcclusionOptions.bias(out)
                intensity = FilaViewAmbientOcclusionOptions.intensity(out)
                resolution = FilaViewAmbientOcclusionOptions.resolution(out)
                power = FilaViewAmbientOcclusionOptions.power(out)
                minHorizonAngleRad = FilaViewAmbientOcclusionOptions.minHorizonAngleRad(out)
                quality = Quality.entries[FilaViewAmbientOcclusionOptions.quality(out)]
                lowPassFilter = Quality.entries[FilaViewAmbientOcclusionOptions.lowPassFilter(out)]
                upsampling = Quality.entries[FilaViewAmbientOcclusionOptions.upsampling(out)]
                bentNormals = FilaViewAmbientOcclusionOptions.bentNormals(out)
                bilateralThreshold = FilaViewAmbientOcclusionOptions.bilateralThreshold(out)
                ssct = AmbientOcclusionOptions.Ssct().apply {
                    enabled = FilaViewAmbientOcclusionOptions.ssct.enabled(ssctSeg)
                    lightConeRad = FilaViewAmbientOcclusionOptions.ssct.lightConeRad(ssctSeg)
                    shadowDistance = FilaViewAmbientOcclusionOptions.ssct.shadowDistance(ssctSeg)
                    contactDistanceMax = FilaViewAmbientOcclusionOptions.ssct.contactDistanceMax(ssctSeg)
                    intensity = FilaViewAmbientOcclusionOptions.ssct.intensity(ssctSeg)
                    val lightDirSeg = FilaViewAmbientOcclusionOptions.ssct.lightDirection(ssctSeg)
                    lightDirection = floatArrayOf(
                        lightDirSeg.getAtIndex(ValueLayout.JAVA_FLOAT, 0L),
                        lightDirSeg.getAtIndex(ValueLayout.JAVA_FLOAT, 1L),
                        lightDirSeg.getAtIndex(ValueLayout.JAVA_FLOAT, 2L)
                    )
                    depthBias = FilaViewAmbientOcclusionOptions.ssct.depthBias(ssctSeg)
                    depthSlopeBias = FilaViewAmbientOcclusionOptions.ssct.depthSlopeBias(ssctSeg)
                    sampleCount = FilaViewAmbientOcclusionOptions.ssct.sampleCount(ssctSeg).toInt()
                    rayCount = FilaViewAmbientOcclusionOptions.ssct.rayCount(ssctSeg).toInt()
                }
            }
        }
        set(value) {
            confined { arena ->
                val c = FilaViewAmbientOcclusionOptions.allocate(arena)
                FilaViewAmbientOcclusionOptions.enabled(c, value.enabled)
                FilaViewAmbientOcclusionOptions.aoType(c, value.aoType.ordinal)
                FilaViewAmbientOcclusionOptions.radius(c, value.radius)
                FilaViewAmbientOcclusionOptions.bias(c, value.bias)
                FilaViewAmbientOcclusionOptions.intensity(c, value.intensity)
                FilaViewAmbientOcclusionOptions.resolution(c, value.resolution)
                FilaViewAmbientOcclusionOptions.power(c, value.power)
                FilaViewAmbientOcclusionOptions.minHorizonAngleRad(c, value.minHorizonAngleRad)
                FilaViewAmbientOcclusionOptions.quality(c, value.quality.ordinal)
                FilaViewAmbientOcclusionOptions.lowPassFilter(c, value.lowPassFilter.ordinal)
                FilaViewAmbientOcclusionOptions.upsampling(c, value.upsampling.ordinal)
                FilaViewAmbientOcclusionOptions.bentNormals(c, value.bentNormals)
                FilaViewAmbientOcclusionOptions.bilateralThreshold(c, value.bilateralThreshold)
                val ssctSeg = FilaViewAmbientOcclusionOptions.ssct(c)
                FilaViewAmbientOcclusionOptions.ssct.enabled(ssctSeg, value.ssct.enabled)
                FilaViewAmbientOcclusionOptions.ssct.lightConeRad(ssctSeg, value.ssct.lightConeRad)
                FilaViewAmbientOcclusionOptions.ssct.shadowDistance(ssctSeg, value.ssct.shadowDistance)
                FilaViewAmbientOcclusionOptions.ssct.contactDistanceMax(ssctSeg, value.ssct.contactDistanceMax)
                FilaViewAmbientOcclusionOptions.ssct.intensity(ssctSeg, value.ssct.intensity)
                val lightDirSeg = FilaViewAmbientOcclusionOptions.ssct.lightDirection(ssctSeg)
                lightDirSeg.setAtIndex(ValueLayout.JAVA_FLOAT, 0L, value.ssct.lightDirection[0])
                lightDirSeg.setAtIndex(ValueLayout.JAVA_FLOAT, 1L, value.ssct.lightDirection[1])
                lightDirSeg.setAtIndex(ValueLayout.JAVA_FLOAT, 2L, value.ssct.lightDirection[2])
                FilaViewAmbientOcclusionOptions.ssct.depthBias(ssctSeg, value.ssct.depthBias)
                FilaViewAmbientOcclusionOptions.ssct.depthSlopeBias(ssctSeg, value.ssct.depthSlopeBias)
                FilaViewAmbientOcclusionOptions.ssct.sampleCount(ssctSeg, value.ssct.sampleCount.toByte())
                FilaViewAmbientOcclusionOptions.ssct.rayCount(ssctSeg, value.ssct.rayCount.toByte())
                FilamentC.FilaView_setAmbientOcclusionOptions(nativeHandle, c)
            }
        }

    actual var temporalAntiAliasingOptions: TemporalAntiAliasingOptions
        get() = confined { arena ->
            val out = FilaViewTemporalAntiAliasingOptions.allocate(arena)
            FilamentC.FilaView_getTemporalAntiAliasingOptions(nativeHandle, out)
            TemporalAntiAliasingOptions().apply {
                enabled = FilaViewTemporalAntiAliasingOptions.enabled(out)
                feedback = FilaViewTemporalAntiAliasingOptions.feedback(out)
                lodBias = FilaViewTemporalAntiAliasingOptions.lodBias(out)
                sharpness = FilaViewTemporalAntiAliasingOptions.sharpness(out)
                upscaling = FilaViewTemporalAntiAliasingOptions.upscaling(out)
                filterHistory = FilaViewTemporalAntiAliasingOptions.filterHistory(out)
                filterInput = FilaViewTemporalAntiAliasingOptions.filterInput(out)
                useYCoCg = FilaViewTemporalAntiAliasingOptions.useYCoCg(out)
                hdr = FilaViewTemporalAntiAliasingOptions.hdr(out)
                boxType = TemporalAntiAliasingOptions.BoxType.entries[FilaViewTemporalAntiAliasingOptions.boxType(out)]
                boxClipping = TemporalAntiAliasingOptions.BoxClipping.entries[FilaViewTemporalAntiAliasingOptions.boxClipping(out)]
                jitterPattern = TemporalAntiAliasingOptions.JitterPattern.entries[FilaViewTemporalAntiAliasingOptions.jitterPattern(out)]
                varianceGamma = FilaViewTemporalAntiAliasingOptions.varianceGamma(out)
                preventFlickering = FilaViewTemporalAntiAliasingOptions.preventFlickering(out)
                historyReprojection = FilaViewTemporalAntiAliasingOptions.historyReprojection(out)
            }
        }
        set(value) {
            confined { arena ->
                val c = FilaViewTemporalAntiAliasingOptions.allocate(arena)
                FilaViewTemporalAntiAliasingOptions.enabled(c, value.enabled)
                FilaViewTemporalAntiAliasingOptions.feedback(c, value.feedback)
                FilaViewTemporalAntiAliasingOptions.lodBias(c, value.lodBias)
                FilaViewTemporalAntiAliasingOptions.sharpness(c, value.sharpness)
                FilaViewTemporalAntiAliasingOptions.upscaling(c, value.upscaling)
                FilaViewTemporalAntiAliasingOptions.filterHistory(c, value.filterHistory)
                FilaViewTemporalAntiAliasingOptions.filterInput(c, value.filterInput)
                FilaViewTemporalAntiAliasingOptions.useYCoCg(c, value.useYCoCg)
                FilaViewTemporalAntiAliasingOptions.hdr(c, value.hdr)
                FilaViewTemporalAntiAliasingOptions.boxType(c, value.boxType.ordinal)
                FilaViewTemporalAntiAliasingOptions.boxClipping(c, value.boxClipping.ordinal)
                FilaViewTemporalAntiAliasingOptions.jitterPattern(c, value.jitterPattern.ordinal)
                FilaViewTemporalAntiAliasingOptions.varianceGamma(c, value.varianceGamma)
                FilaViewTemporalAntiAliasingOptions.preventFlickering(c, value.preventFlickering)
                FilaViewTemporalAntiAliasingOptions.historyReprojection(c, value.historyReprojection)
                FilamentC.FilaView_setTemporalAntiAliasingOptions(nativeHandle, c)
            }
        }

    actual var screenSpaceReflectionsOptions: ScreenSpaceReflectionsOptions
        get() = confined { arena ->
            val out = FilaViewScreenSpaceReflectionsOptions.allocate(arena)
            FilamentC.FilaView_getScreenSpaceReflectionsOptions(nativeHandle, out)
            ScreenSpaceReflectionsOptions().apply {
                enabled = FilaViewScreenSpaceReflectionsOptions.enabled(out)
                thickness = FilaViewScreenSpaceReflectionsOptions.thickness(out)
                bias = FilaViewScreenSpaceReflectionsOptions.bias(out)
                maxDistance = FilaViewScreenSpaceReflectionsOptions.maxDistance(out)
                stride = FilaViewScreenSpaceReflectionsOptions.stride(out)
            }
        }
        set(value) {
            confined { arena ->
                val c = FilaViewScreenSpaceReflectionsOptions.allocate(arena)
                FilaViewScreenSpaceReflectionsOptions.enabled(c, value.enabled)
                FilaViewScreenSpaceReflectionsOptions.thickness(c, value.thickness)
                FilaViewScreenSpaceReflectionsOptions.bias(c, value.bias)
                FilaViewScreenSpaceReflectionsOptions.maxDistance(c, value.maxDistance)
                FilaViewScreenSpaceReflectionsOptions.stride(c, value.stride)
                FilamentC.FilaView_setScreenSpaceReflectionsOptions(nativeHandle, c)
            }
        }

    actual var renderTarget: RenderTarget?
        get() = mRenderTarget
        set(value) {
            mRenderTarget = value
            FilamentC.FilaView_setRenderTarget(nativeHandle, value?.nativeHandle ?: NULL)
        }

    @PlatformGap(platforms = [FilamentPlatform.WEB], behavior = "silent no-op unless the filament.js build binds setShadowType (stock upstream prebuilts do not) — web stays on PCF shadows.")
    actual var shadowType: ShadowType
        get() = mShadowType
        set(value) {
            mShadowType = value
            FilamentC.FilaView_setShadowType(nativeHandle, value.ordinal)
        }

    actual var vsmShadowOptions: VsmShadowOptions
        get() = confined { arena ->
            val out = FilaViewVsmShadowOptions.allocate(arena)
            FilamentC.FilaView_getVsmShadowOptions(nativeHandle, out)
            VsmShadowOptions().apply {
                anisotropy = FilaViewVsmShadowOptions.anisotropy(out).toInt()
                mipmapping = FilaViewVsmShadowOptions.mipmapping(out)
                msaaSamples = FilaViewVsmShadowOptions.msaaSamples(out).toInt()
                highPrecision = FilaViewVsmShadowOptions.highPrecision(out)
                lightBleedReduction = FilaViewVsmShadowOptions.lightBleedReduction(out)
            }
        }
        set(value) {
            confined { arena ->
                val c = FilaViewVsmShadowOptions.allocate(arena)
                FilaViewVsmShadowOptions.anisotropy(c, value.anisotropy.toByte())
                FilaViewVsmShadowOptions.mipmapping(c, value.mipmapping)
                FilaViewVsmShadowOptions.msaaSamples(c, value.msaaSamples.toByte())
                FilaViewVsmShadowOptions.highPrecision(c, value.highPrecision)
                FilaViewVsmShadowOptions.lightBleedReduction(c, value.lightBleedReduction)
                FilamentC.FilaView_setVsmShadowOptions(nativeHandle, c)
            }
        }

    actual var softShadowOptions: SoftShadowOptions
        get() = confined { arena ->
            val out = FilaViewSoftShadowOptions.allocate(arena)
            FilamentC.FilaView_getSoftShadowOptions(nativeHandle, out)
            SoftShadowOptions().apply {
                penumbraScale = FilaViewSoftShadowOptions.penumbraScale(out)
                penumbraRatioScale = FilaViewSoftShadowOptions.penumbraRatioScale(out)
            }
        }
        set(value) {
            confined { arena ->
                val c = FilaViewSoftShadowOptions.allocate(arena)
                FilaViewSoftShadowOptions.penumbraScale(c, value.penumbraScale)
                FilaViewSoftShadowOptions.penumbraRatioScale(c, value.penumbraRatioScale)
                FilamentC.FilaView_setSoftShadowOptions(nativeHandle, c)
            }
        }

    actual var guardBandOptions: GuardBandOptions
        get() = confined { arena ->
            val out = FilaViewGuardBandOptions.allocate(arena)
            FilamentC.FilaView_getGuardBandOptions(nativeHandle, out)
            GuardBandOptions().apply { enabled = FilaViewGuardBandOptions.enabled(out) }
        }
        set(value) {
            confined { arena ->
                val c = FilaViewGuardBandOptions.allocate(arena)
                FilaViewGuardBandOptions.enabled(c, value.enabled)
                FilamentC.FilaView_setGuardBandOptions(nativeHandle, c)
            }
        }

    actual var stereoscopicOptions: StereoscopicOptions
        get() = confined { arena ->
            val out = FilaViewStereoscopicOptions.allocate(arena)
            FilamentC.FilaView_getStereoscopicOptions(nativeHandle, out)
            StereoscopicOptions().apply { enabled = FilaViewStereoscopicOptions.enabled(out) }
        }
        set(value) {
            confined { arena ->
                val c = FilaViewStereoscopicOptions.allocate(arena)
                FilaViewStereoscopicOptions.enabled(c, value.enabled)
                FilamentC.FilaView_setStereoscopicOptions(nativeHandle, c)
            }
        }

    actual var multiSampleAntiAliasingOptions: MultiSampleAntiAliasingOptions
        get() = confined { arena ->
            val out = FilaViewMultiSampleAntiAliasingOptions.allocate(arena)
            FilamentC.FilaView_getMultiSampleAntiAliasingOptions(nativeHandle, out)
            MultiSampleAntiAliasingOptions().apply {
                enabled = FilaViewMultiSampleAntiAliasingOptions.enabled(out)
                sampleCount = FilaViewMultiSampleAntiAliasingOptions.sampleCount(out).toInt()
                customResolve = FilaViewMultiSampleAntiAliasingOptions.customResolve(out)
            }
        }
        set(value) {
            confined { arena ->
                val c = FilaViewMultiSampleAntiAliasingOptions.allocate(arena)
                FilaViewMultiSampleAntiAliasingOptions.enabled(c, value.enabled)
                FilaViewMultiSampleAntiAliasingOptions.sampleCount(c, value.sampleCount.toByte())
                FilaViewMultiSampleAntiAliasingOptions.customResolve(c, value.customResolve)
                FilamentC.FilaView_setMultiSampleAntiAliasingOptions(nativeHandle, c)
            }
        }

    @PlatformGap(platforms = [FilamentPlatform.WEB], behavior = "setter is a silent no-op — setFrustumCullingEnabled is not bound in filament.js; the getter reflects the locally tracked value.")
    actual var isFrustumCullingEnabled: Boolean
        get() = FilamentC.FilaView_isFrustumCullingEnabled(nativeHandle)
        set(value) { FilamentC.FilaView_setFrustumCullingEnabled(nativeHandle, value) }
    actual var isShadowingEnabled: Boolean
        get() = FilamentC.FilaView_isShadowingEnabled(nativeHandle)
        set(value) { FilamentC.FilaView_setShadowingEnabled(nativeHandle, value) }
    actual var isScreenSpaceRefractionEnabled: Boolean
        get() = FilamentC.FilaView_isScreenSpaceRefractionEnabled(nativeHandle)
        set(value) { FilamentC.FilaView_setScreenSpaceRefractionEnabled(nativeHandle, value) }
    actual var isStencilBufferEnabled: Boolean
        get() = FilamentC.FilaView_isStencilBufferEnabled(nativeHandle)
        set(value) { FilamentC.FilaView_setStencilBufferEnabled(nativeHandle, value) }
    actual var isFrontFaceWindingInverted: Boolean
        get() = FilamentC.FilaView_isFrontFaceWindingInverted(nativeHandle)
        set(value) { FilamentC.FilaView_setFrontFaceWindingInverted(nativeHandle, value) }
    actual var isTransparentPickingEnabled: Boolean
        get() = FilamentC.FilaView_isTransparentPickingEnabled(nativeHandle)
        set(value) { FilamentC.FilaView_setTransparentPickingEnabled(nativeHandle, value) }

    actual var gridSize: Double
        get() = FilamentC.FilaView_getGridSize(nativeHandle)
        set(value) { FilamentC.FilaView_setGridSize(nativeHandle, value) }
    actual val effectiveGridSize: Double
        get() = FilamentC.FilaView_getEffectiveGridSize(nativeHandle)

    actual fun setMaterialGlobal(index: Int, value: FloatArray) {
        FilamentC.FilaView_setMaterialGlobal(nativeHandle, index, value[0], value[1], value[2], value[3])
    }
    actual fun getMaterialGlobal(index: Int): FloatArray = confined { arena ->
        val out = arena.floatArr(4)
        FilamentC.FilaView_getMaterialGlobal(nativeHandle, index, out)
        out.toFloats()
    }
    actual val fogEntity: Int get() = FilamentC.FilaView_getFogEntity(nativeHandle)
    actual fun getVisibleRenderableCount(): Int = FilamentC.FilaView_getVisibleRenderableCount(nativeHandle)
    actual fun clearFrameHistory(engine: Engine) { FilamentC.FilaView_clearFrameHistory(nativeHandle, engine.nativeHandle) }

    actual fun setDynamicLightingOptions(zNear: Float, zFar: Float) {
        FilamentC.FilaView_setDynamicLightingOptions(nativeHandle, zNear, zFar)
    }

    actual var antiAliasing: AntiAliasing
        get() = AntiAliasing.values()[FilamentC.FilaView_getAntiAliasing(nativeHandle)]
        set(value) { FilamentC.FilaView_setAntiAliasing(nativeHandle, value.ordinal) }

    actual var colorGrading: ColorGrading?
        get() = mColorGrading
        set(value) {
            mColorGrading = value
            FilamentC.FilaView_setColorGrading(nativeHandle, value?.nativeHandle ?: NULL)
        }

    actual fun pick(x: Int, y: Int, callback: (PickingQueryResult) -> Unit) {
        val userData = Picking.register(callback)
        FilamentC.FilaView_pick(nativeHandle, x, y, NULL, Picking.stub, userData)
    }
}
