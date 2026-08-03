package io.github.erkko68.filament

/**
 * A View encompasses all the state needed for rendering a Scene.
 *
 * Renderer.render() operates on View objects. These View objects specify important parameters
 * such as the Scene, Camera, Viewport, and various rendering parameters.
 *
 * View instances are heavy objects that internally cache a lot of data needed for rendering.
 * It is not advised for an application to use many View objects. For example, in a game, a View
 * could be used for the main scene and another one for the game's user interface. More View
 * instances could be used for creating special effects (a View is akin to a rendering pass).
 *
 * @see Scene, Camera, RenderTarget
 */
expect class View {
    /**
     * Dithering mode for temporal coherence in rendering.
     *
     * - NONE: No dithering applied
     * - TEMPORAL: Temporal dithering for reduced color banding
     */
    enum class Dithering { NONE, TEMPORAL }

    /**
     * Blending mode for the view.
     *
     * - OPAQUE: View renders opaque content
     * - TRANSLUCENT: View renders translucent content
     */
    enum class BlendMode { OPAQUE, TRANSLUCENT }

    /**
     * Generic quality level for various rendering options.
     *
     * - LOW: Lowest quality, best performance
     * - MEDIUM: Medium quality and performance balance
     * - HIGH: High quality, moderate performance impact
     * - ULTRA: Highest quality, greatest performance impact
     */
    enum class Quality { LOW, MEDIUM, HIGH, ULTRA }

    /**
     * Shadow rendering technique.
     *
     * - PCF: Percentage Closer Filtering (standard soft shadows)
     * - VSM: Variance Shadow Maps
     * - DPCF: Directional Percentage Closer Filtering
     * - PCSS: Percentage Closer Soft Shadows (physically-based)
     * - PCFd: Directional PCF variant
     */
    enum class ShadowType { PCF, VSM, DPCF, PCSS, PCFd }
    /**
     * Anti-aliasing technique.
     *
     * - NONE: No anti-aliasing
     * - FXAA: Fast Approximate Anti-Aliasing (post-process)
     */
    enum class AntiAliasing { NONE, FXAA }

    /**
     * Result of a picking (color-picking) query.
     *
     * @param renderable Entity ID of the picked renderable
     * @param depth Depth of the picked fragment
     * @param fragCoords Fragment coordinates (x, y) of the pick location
     */
    class PickingQueryResult(
        renderable: Int,
        depth: Float,
        fragCoords: FloatArray
    ) {
        val renderable: Int
        val depth: Float
        val fragCoords: FloatArray
    }

    /**
     * Dynamic resolution options control rendering resolution scaling to meet target frame rates.
     *
     * Dynamic resolution can be used to either reach a desired target frame rate by lowering the
     * resolution of a View, or to increase the quality when rendering is faster than the target
     * frame rate. The scale factors can be controlled on each X and Y axis independently.
     * By default, all scale factors are set to 1.0.
     *
     * Dynamic resolution is only supported on platforms where the time to render a frame can be
     * measured accurately. On platforms where this is not supported, Dynamic Resolution can't be
     * enabled unless minScale == maxScale.
     */
    class DynamicResolutionOptions() {
        /**
         * Enable or disable dynamic resolution on this View. Default: false.
         */
        var enabled: Boolean

        /**
         * By default the system scales the major axis first. Set this to true to force
         * homogeneous scaling. Default: false.
         */
        var homogeneousScaling: Boolean

        /**
         * The minimum scale in X and Y this View should use. Default: (0.5, 0.5).
         */
        var minScale: Float

        /**
         * The maximum scale in X and Y this View should use. Default: (1.0, 1.0).
         */
        var maxScale: Float

        /**
         * Sharpness when Quality.MEDIUM or higher is used [0 (disabled), 1 (sharpest)].
         * Default: 0.9.
         */
        var sharpness: Float

        /**
         * Upscaling quality.
         * - LOW: bilinear filtered blit. Fastest, poor quality
         * - MEDIUM: Qualcomm Snapdragon Game Super Resolution (SGSR) 1.0
         * - HIGH: AMD FidelityFX FSR1 w/ mobile optimizations
         * - ULTRA: AMD FidelityFX FSR1
         *
         * FSR1 and SGSR require a well anti-aliased (MSAA or TAA), noise free scene.
         * Avoid FXAA and dithering. Default: LOW.
         */
        var quality: Quality
    }

    /**
     * Options to control color buffer precision and quality settings.
     *
     * A quality of HIGH or ULTRA means using an RGB16F or RGBA16F color buffer. Colors in the
     * LDR range (0..1) have a 10 bit precision. A quality of LOW or MEDIUM means using an
     * R11G11B10F opaque color buffer or an RGBA16F transparent color buffer. With R11G11B10F,
     * colors in the LDR range have a precision of either 6 bits (red and green) or 5 bits (blue).
     */
    class RenderQuality() {
        /**
         * Sets the quality of the HDR color buffer. Default: HIGH.
         */
        var hdrColorBuffer: Quality
    }

    /**
     * Options to control the bloom post-processing effect.
     *
     * Bloom allows bright areas to glow and bleed into surrounding areas, creating a
     * luminous quality. The effect can be enhanced with lens flare, lens artifacts, and
     * customizable bloom color and spread.
     */
    class BloomOptions() {
        /**
         * Enable or disable the bloom post-processing effect. Default: false.
         */
        var enabled: Boolean

        /**
         * Number of successive blurs to achieve the blur effect. Minimum is 3 and maximum is 12.
         * This value together with resolution influences the spread of the blur effect.
         * This value can be silently reduced to accommodate the original image size. Default: 6.
         */
        var levels: Int

        /**
         * Resolution of bloom's minor axis. Minimum value is 2^levels and maximum is lower of
         * the original resolution and 4096. This parameter is silently clamped to the minimum
         * and maximum. Default: 384.
         */
        var resolution: Int

        /**
         * How much of the bloom is added to the original image, between 0 and 1. Default: 0.10.
         */
        var strength: Float

        /**
         * When enabled, a threshold at 1.0 is applied on the source image, useful for artistic
         * reasons and usually needed when a dirt texture is used. Default: true.
         */
        var threshold: Boolean

        /**
         * A dirt/scratch/smudges texture (RGB) which gets added to the bloom effect.
         * Smudges are visible where bloom occurs. Threshold must be enabled for the dirt
         * effect to work properly. Default: null.
         */
        var dirt: Texture?

        /**
         * Strength of the dirt texture. Default: 0.2.
         */
        var dirtStrength: Float

        /**
         * Bloom quality level.
         * - LOW (default): use a more optimized down-sampling filter, however there can be
         *   artifacts with dynamic resolution
         * - MEDIUM: Good balance between quality and performance
         * - HIGH: Bloom resolution is automatically increased to avoid artifacts. Can be
         *   significantly slower on mobile.
         *
         * Default: LOW.
         */
        var quality: Quality

        /**
         * Enable screen-space lens flare effect. Default: false.
         */
        var lensFlare: Boolean

        /**
         * Enable starburst effect on lens flare. Default: true.
         */
        var starburst: Boolean

        /**
         * Amount of chromatic aberration in the lens flare effect. Default: 0.005.
         */
        var chromaticAberration: Float

        /**
         * Number of flare "ghosts" (lens artifacts). Default: 4.
         */
        var ghostCount: Int

        /**
         * Spacing of the ghost in screen units [0, 1). Default: 0.6.
         */
        var ghostSpacing: Float

        /**
         * HDR threshold for the ghosts. Default: 10.0.
         */
        var ghostThreshold: Float

        /**
         * Radius of halo in vertical screen units [0, 0.5]. Default: 0.4.
         */
        var haloRadius: Float

        /**
         * Thickness of halo in vertical screen units, 0 to disable. Default: 0.1.
         */
        var haloThickness: Float

        /**
         * HDR threshold for the halo. Default: 10.0.
         */
        var haloThreshold: Float

        /**
         * Limit highlights to this value before bloom, range [10, +inf]. Default: 1000.0.
         */
        var highlight: Float

        /**
         * How the bloom effect is applied.
         *
         * - ADD: Bloom is modulated by the strength parameter and added to the scene
         * - INTERPOLATE: Bloom is interpolated with the scene using the strength parameter
         *
         * Default: ADD.
         */
        var blendMode: BlendMode

        /**
         * Bloom blending mode.
         *
         * - ADD: Bloom is modulated by strength and added to the scene
         * - INTERPOLATE: Bloom is interpolated with the scene using strength
         */
        enum class BlendMode { ADD, INTERPOLATE }
    }

    /**
     * Options to control large-scale fog in the scene.
     *
     * Materials can enable the linearFog property, which uses a simplified, linear equation for
     * fog calculation; in this mode, the heightFalloff is ignored as well as the mipmap selection
     * in IBL or skyColor mode.
     */
    class FogOptions() {
        /**
         * Enable or disable large-scale fog. Default: false.
         */
        var enabled: Boolean

        /**
         * Distance in world units [m] from the camera to where the fog starts (>= 0.0).
         * Default: 0.0.
         */
        var distance: Float

        /**
         * Extinction factor in [1/m] at the fog height. Controls how much light is absorbed and
         * out-scattered per unit of distance. Each unit of extinction reduces incoming light to
         * 37% of its original value. In linearFog mode, this is the slope of the linear equation
         * if heightFalloff is 0. Default: 0.1.
         */
        var density: Float

        /**
         * Fog's floor in world units [m]. This sets the "sea level". Default: 0.0.
         */
        var height: Float

        /**
         * How fast the fog dissipates with altitude. heightFalloff has a unit of [1/m].
         * It can be expressed as 1/H, where H is the altitude change in world units [m] that
         * causes a factor 2.78 (e) change in fog density. A falloff of 0 means the fog density
         * is constant everywhere. Ignored in linearFog mode if set to 0. Default: 1.0.
         */
        var heightFalloff: Float

        /**
         * Fog's color used for ambient light in-scattering. A good value is the average of the
         * ambient light, possibly tinted towards blue for outdoor environments. Color components
         * should be between 0 and 1; values above 1 are allowed but could create a non
         * energy-conservative fog. Used as a tint when fogColorFromIbl is enabled. Default: white.
         */
        var color: FloatArray

        /**
         * Optional density map texture for varying fog density across the scene. Default: null.
         */
        var densityMap: Texture?

        /**
         * Distance in world units [m] after which the fog calculation is disabled. This can be
         * used to exclude the skybox. The SkyBox is typically at a distance of 1e19 in world
         * space. Default: infinity.
         */
        var cutOffDistance: Float

        /**
         * Fog's maximum opacity between 0 and 1. Ignored in linearFog mode. Default: 1.0.
         */
        var maximumOpacity: Float

        /**
         * Distance in world units [m] from the camera where the Sun in-scattering starts.
         * Ignored in linearFog mode. Default: 0.0.
         */
        var inScatteringStart: Float

        /**
         * Very inaccurately simulates the Sun's in-scattering. Size of the Sun in-scattering
         * (>0 to activate). Good values are >> 1 (e.g., ~10 - 100). Smaller values result in a
         * larger scattering size. Ignored in linearFog mode. Default: -1.0.
         */
        var inScatteringSize: Float

        /**
         * The fog color will be sampled from the IBL in the view direction and tinted by the
         * color parameter. This simulates a more anisotropic phase-function. Ignored when
         * skyColor is specified. Default: false.
         */
        var fogColorFromIbl: Boolean

        /**
         * Optional sky texture (mipmapped cubemap) for fog color sampling. When provided, the
         * fog color will be sampled from this texture, with higher resolution mip levels used
         * for objects at the far clip plane and lower resolution mip levels for closer objects.
         * fogColorFromIbl is ignored when this is specified. In linearFog mode, mipmap level 0
         * is always used. Default: null.
         */
        var skyColor: Texture?
    }

    /**
     * Options to control Depth of Field (DoF) effect in the scene.
     *
     * cocScale can be used to set the depth of field blur independently of the camera aperture,
     * e.g., for artistic reasons. This can be achieved by setting:
     * cocScale = cameraAperture / desiredDoFAperture.
     */
    class DepthOfFieldOptions() {
        /**
         * Enable or disable depth of field effect. Default: false.
         */
        var enabled: Boolean

        /**
         * Circle of confusion scale factor (amount of blur). Default: 1.0.
         */
        var cocScale: Float

        /**
         * Maximum aperture diameter in meters (zero to disable rotation). Default: 0.01.
         */
        var maxApertureDiameter: Float

        /**
         * Filter to use for filling gaps in the kernel. Default: MEDIAN.
         */
        var filter: Filter

        /**
         * Perform DoF processing at native resolution. Default: false.
         */
        var nativeResolution: Boolean

        /**
         * Number of rings used by the gather kernels for foreground. The number of rings affects
         * quality and performance. The actual number of samples per pixel is (ringCount * 2 - 1)².
         * Examples: 3 rings = 25 (5x5), 4 rings = 49 (7x7), 5 rings = 81 (9x9), 17 rings = 1089 (33x33).
         * A value of 0 means default (5 on desktop, 3 on mobile). Default: 0.
         */
        var foregroundRingCount: Int

        /**
         * Number of rings used by the gather kernels for background. Default: 0.
         */
        var backgroundRingCount: Int

        /**
         * Number of rings used by the gather kernels for fast tiles (regions with similar CoC).
         * Default: 0.
         */
        var fastGatherRingCount: Int

        /**
         * Maximum circle-of-confusion in pixels for the foreground, must be in [0, 32] range.
         * A value of 0 means default (32 on desktop, 24 on mobile). Default: 0.
         */
        var maxForegroundCOC: Int

        /**
         * Maximum circle-of-confusion in pixels for the background, must be in [0, 32] range.
         * A value of 0 means default (32 on desktop, 24 on mobile). Default: 0.
         */
        var maxBackgroundCOC: Int

        /**
         * Depth of Field filter types.
         *
         * - NONE: No filtering
         * - UNUSED: Unused filter type
         * - MEDIAN: Median filtering for gap filling
         */
        enum class Filter { NONE, UNUSED, MEDIAN }
    }

    /**
     * Options to control the vignetting effect (darkening at screen edges).
     */
    class VignetteOptions() {
        /**
         * Enable or disable the vignette effect. Default: false.
         */
        var enabled: Boolean

        /**
         * High values restrict the vignette closer to the corners, between 0 and 1.
         * Default: 0.5.
         */
        var midPoint: Float

        /**
         * Controls the shape of the vignette, from a rounded rectangle (0.0), to an oval (0.5),
         * to a circle (1.0). Default: 0.5.
         */
        var roundness: Float

        /**
         * Softening amount of the vignette effect, between 0 and 1. Default: 0.5.
         */
        var feather: Float

        /**
         * Color of the vignette effect (alpha is currently ignored). Default: black.
         */
        var color: FloatArray
    }

    /**
     * Options for screen space Ambient Occlusion (SSAO) and Screen Space Cone Tracing (SSCT).
     *
     * Ambient occlusion darkens crevices and contact points, adding realism and depth to scenes.
     */
    class AmbientOcclusionOptions() {
        /**
         * The occlusion algorithm to use.
         */
        enum class AmbientOcclusionType {
            /** Scalable Ambient Occlusion. */
            SAO,
            /** Ground Truth-based Ambient Occlusion. */
            GTAO
        }

        /**
         * Enable or disable screen-space ambient occlusion. Default: false.
         */
        var enabled: Boolean

        /**
         * Type of ambient occlusion algorithm. Default: [AmbientOcclusionType.SAO].
         */
        var aoType: AmbientOcclusionType

        /**
         * Ambient Occlusion radius in meters, between 0 and ~10. Default: 0.3.
         */
        var radius: Float

        /**
         * Self-occlusion bias in meters. Use to avoid self-occlusion. Between 0 and a few mm.
         * No effect when aoType is set to GTAO. Default: 0.0005.
         */
        var bias: Float

        /**
         * Controls ambient occlusion's contrast. Must be positive. Default: 1.0.
         */
        var power: Float

        /**
         * Strength of the Ambient Occlusion effect. Default: 1.0.
         */
        var intensity: Float

        /**
         * How each dimension of the AO buffer is scaled. Must be either 0.5 or 1.0. Default: 0.5.
         */
        var resolution: Float

        /**
         * Depth distance that constitutes an edge for filtering. Default: 0.05.
         */
        var bilateralThreshold: Float

        /**
         * Minimum angle in radians to consider. No effect when aoType is set to GTAO. Default: 0.0.
         */
        var minHorizonAngleRad: Float

        /**
         * Affects number of samples used for AO and parameters for filtering. Default: LOW.
         */
        var quality: Quality

        /**
         * Affects AO smoothness. Recommended setting to HIGH when aoType is set to GTAO.
         * Default: MEDIUM.
         */
        var lowPassFilter: Quality

        /**
         * Affects AO buffer upsampling quality. Default: LOW.
         */
        var upsampling: Quality

        /**
         * Enable bent normals computation from AO, and specular AO. Default: false.
         */
        var bentNormals: Boolean

        /**
         * Screen Space Cone Tracing (SSCT) options for ambient shadows from dominant light.
         */
        var ssct: Ssct

        /**
         * Screen Space Cone Tracing options for ambient shadows.
         */
        class Ssct() {
            /**
             * Enable or disable SSCT. Default: false.
             */
            var enabled: Boolean

            /**
             * Full cone angle in radians, between 0 and pi/2. Default: 1.0.
             */
            var lightConeRad: Float

            /**
             * How far shadows can be cast. Default: 0.3.
             */
            var shadowDistance: Float

            /**
             * Maximum distance for contact. Default: 1.0.
             */
            var contactDistanceMax: Float

            /**
             * Intensity of SSCT effect. Default: 0.8.
             */
            var intensity: Float

            /**
             * Light direction vector. Default: (0, -1, 0).
             */
            var lightDirection: FloatArray

            /**
             * Depth bias in world units to mitigate self shadowing. Default: 0.01.
             */
            var depthBias: Float

            /**
             * Depth slope bias to mitigate self shadowing. Default: 0.01.
             */
            var depthSlopeBias: Float

            /**
             * Tracing sample count, between 1 and 255. Default: 4.
             */
            var sampleCount: Int

            /**
             * Number of rays to trace, between 1 and 255. Default: 1.
             */
            var rayCount: Int
        }
    }

    /**
     * Options for Temporal Anti-aliasing (TAA).
     *
     * Most TAA parameters are extremely costly to change, as they will trigger the TAA post-process
     * shaders to be recompiled. These options should be changed or set during initialization.
     * `feedback` and `jitterPattern`, however, can be changed at any time. A feedback of 0.1
     * effectively accumulates a maximum of 19 samples in steady state.
     */
    class TemporalAntiAliasingOptions() {
        /**
         * Type of color gamut box used for history rejection.
         */
        enum class BoxType {
            /** Use an AABB neighborhood. */
            AABB,
            /** Use both AABB and variance. */
            AABB_VARIANCE
        }

        /**
         * Clipping algorithm for history rejection.
         */
        enum class BoxClipping {
            /** Accurate box clipping. */
            ACCURATE,
            /** Clamping. */
            CLAMP,
            /** No rejections (use for debugging). */
            NONE
        }

        /**
         * Jitter pattern used for sampling.
         */
        enum class JitterPattern {
            /** 4-sample rotated grid sampling. */
            RGSS_X4,
            /** 4-sample uniform grid in helix sequence. */
            UNIFORM_HELIX_X4,
            /** 8 samples of Halton 2,3. */
            HALTON_23_X8,
            /** 16 samples of Halton 2,3. */
            HALTON_23_X16,
            /** 32 samples of Halton 2,3. */
            HALTON_23_X32
        }

        /**
         * Enable or disable temporal anti-aliasing. Default: false.
         */
        var enabled: Boolean

        /**
         * History feedback, between 0 (maximum temporal AA) and 1 (no temporal AA). Default: 0.12.
         */
        var feedback: Float

        /**
         * Texturing LOD bias (typically -1 or -2). Default: -1.0.
         */
        var lodBias: Float

        /**
         * Post-TAA sharpening, especially useful when upscaling is true. Default: 0.0.
         */
        var sharpness: Float

        /**
         * Upscaling factor. Disables Dynamic Resolution. Default: 1.0 (Beta).
         */
        var upscaling: Float

        /**
         * Whether to filter the history buffer. Default: true.
         */
        var filterHistory: Boolean

        /**
         * Whether to apply the reconstruction filter to the input. Default: true.
         */
        var filterInput: Boolean

        /**
         * Whether to use the YcoCg color-space for history rejection. Default: false.
         */
        var useYCoCg: Boolean

        /**
         * Set to true for HDR content. Default: true.
         */
        var hdr: Boolean

        /**
         * Type of color gamut box. Default: [BoxType.AABB].
         */
        var boxType: BoxType

        /**
         * Clipping algorithm. Default: [BoxClipping.ACCURATE].
         */
        var boxClipping: BoxClipping

        /**
         * Jitter pattern for sampling. Default: [JitterPattern.HALTON_23_X16].
         */
        var jitterPattern: JitterPattern

        /**
         * High values increase ghosting artifacts, lower values increase jittering, range [0.75, 1.25].
         * Default: 1.0.
         */
        var varianceGamma: Float

        /**
         * Adjust the feedback dynamically to reduce flickering. Default: false.
         */
        var preventFlickering: Boolean

        /**
         * Whether to apply history reprojection (debug option). Default: true.
         */
        var historyReprojection: Boolean
    }

    /**
     * Options for Screen-space Reflections (SSR).
     *
     * SSR allows objects to reflect their environment in real-time using only screen-space
     * information, making it very efficient but limited to on-screen reflections.
     */
    class ScreenSpaceReflectionsOptions() {
        /**
         * Enable or disable screen-space reflections. Default: false.
         */
        var enabled: Boolean

        /**
         * Ray thickness in world units. Default: 0.1.
         */
        var thickness: Float

        /**
         * Bias in world units to prevent self-intersections. Default: 0.01.
         */
        var bias: Float

        /**
         * Maximum distance in world units to raycast. Default: 3.0.
         */
        var maxDistance: Float

        /**
         * Stride in texels for samples along the ray. Default: 2.0.
         */
        var stride: Float
    }

    /**
     * View-level options for VSM (Variance Shadow Maps) shadowing.
     *
     * Warning: This API is still experimental and subject to change.
     */
    class VsmShadowOptions() {
        /**
         * Number of anisotropic samples to use when sampling a VSM shadow map. If greater than 0,
         * mipmaps will automatically be generated each frame for all lights. The number of
         * anisotropic samples = 2 ^ anisotropy. Default: 0.
         */
        var anisotropy: Int

        /**
         * Whether to generate mipmaps for all VSM shadow maps. Default: false.
         */
        var mipmapping: Boolean

        /**
         * The number of MSAA samples to use when rendering VSM shadow maps. Must be a power-of-two
         * and greater than or equal to 1. A value of 1 effectively turns off MSAA. Higher values
         * may not be available depending on the underlying hardware. Default: 1.
         */
        var msaaSamples: Int

        /**
         * Whether to use a 32-bits or 16-bits texture format for VSM shadow maps. 32-bits precision
         * is rarely needed, but it does reduce light leaks as well as "fading" of the shadows.
         * Setting this to true for a single shadow map will double the memory usage of all shadow
         * maps. This may not be supported on all mobile devices. Default: false.
         */
        var highPrecision: Boolean

        /**
         * VSM light bleeding reduction amount, between 0 and 1. Default: 0.15.
         */
        var lightBleedReduction: Float
    }

    /**
     * View-level options for DPCF and PCSS (soft) shadowing.
     *
     * Warning: This API is still experimental and subject to change.
     */
    class SoftShadowOptions() {
        /**
         * Globally scales the penumbra of all DPCF and PCSS shadows. Acceptable values are greater
         * than 0. Default: 1.0.
         */
        var penumbraScale: Float

        /**
         * Globally scales the computed penumbra ratio of all DPCF and PCSS shadows. This effectively
         * controls the strength of contact hardening effect and is useful for artistic purposes.
         * Higher values make the shadows become softer faster. Acceptable values are equal to or
         * greater than 1. Default: 1.0.
         */
        var penumbraRatioScale: Float
    }

    /**
     * Options for the screen-space guard band.
     *
     * A guard band can be enabled to avoid artifacts towards the edge of the screen when using
     * screen-space effects such as SSAO. Enabling the guard band reduces performance slightly.
     * Currently the guard band can only be enabled or disabled.
     */
    class GuardBandOptions() {
        /**
         * Enable or disable the guard band. Default: false.
         */
        var enabled: Boolean
    }

    /**
     * Options for stereoscopic (multi-eye) rendering.
     *
     * Used for VR and other multi-view rendering scenarios.
     */
    class StereoscopicOptions() {
        /**
         * Enable or disable stereoscopic rendering. Default: false.
         */
        var enabled: Boolean
    }

    /**
     * Options for Multi-Sample Anti-aliasing (MSAA).
     *
     * MSAA is a GPU-native anti-aliasing technique that reduces jagged edges by sampling multiple
     * points per pixel.
     */
    class MultiSampleAntiAliasingOptions() {
        /**
         * Enable or disable MSAA. Default: false.
         */
        var enabled: Boolean

        /**
         * Number of samples to use for multi-sampled anti-aliasing.
         * - 0: treated as 1
         * - 1: no anti-aliasing
         * - n: sample count. Effective sample could be different depending on the GPU capabilities.
         *
         * Default: 4.
         */
        var sampleCount: Int

        /**
         * Custom resolve improves quality for HDR scenes, but may impact performance. Default: false.
         */
        var customResolve: Boolean
    }

    /** Debug name of this View, shown in diagnostic tools. */
    var name: String?

    /**
     * The [Scene] associated with this View. A Scene can be associated to several Views.
     *
     * Set to `null` to dissociate the current Scene. The View does not take ownership of the Scene.
     *
     * There is no reference-counting: if a Scene is destroyed while still associated with a View, it
     * is automatically dissociated (the View's scene becomes `null`).
     */
    var scene: Scene?

    /**
     * The [Camera] this View is rendered from. A Camera can be associated to several Views.
     *
     * Set to `null` to dissociate the current Camera; the View does not take ownership.
     */
    var camera: Camera?

    /** Whether a [Camera] is currently associated with this View. */
    val hasCamera: Boolean

    /** The rectangular region of the render target this View renders into. */
    var viewport: Viewport

    /** How this View's result blends over the render target's existing content. */
    var blendMode: BlendMode

    /**
     * Sets which layers are visible: for each bit set in [select], visibility is taken from the
     * corresponding bit in [values]. Renderables are assigned layers via
     * `RenderableManager.setLayerMask`. By default all layers are visible.
     */
    fun setVisibleLayers(select: Int, values: Int)

    /** Convenience over [setVisibleLayers] toggling a single layer (0–7). */
    fun setLayerEnabled(layer: Int, enabled: Boolean)

    /** Returns the current visible-layer bitmask. */
    fun getVisibleLayers(): Int

    /**
     * Enables or disables the post-processing stage (tone mapping, bloom, color grading, FXAA,
     * dynamic scaling, …). Disabling it also disables features that depend on it. Default: enabled.
     */
    var isPostProcessingEnabled: Boolean

    /** Dithering applied to the final render to hide banding. Default: [Dithering.TEMPORAL]. */
    var dithering: Dithering

    /** Dynamic-resolution (render scaling) configuration for this View. */
    var dynamicResolutionOptions: DynamicResolutionOptions

    /** Returns the `[x, y]` scale factors dynamic resolution used on the last frame. */
    fun getLastDynamicResolutionScale(): FloatArray

    /** Global quality/performance trade-offs (e.g. color-buffer precision) for this View. */
    var renderQuality: RenderQuality

    /** Bloom post-processing configuration (requires post-processing enabled). */
    var bloomOptions: BloomOptions

    /** Large-scale atmospheric fog configuration. */
    var fogOptions: FogOptions

    /** Depth-of-field post-processing configuration (needs a focused [camera]). */
    var depthOfFieldOptions: DepthOfFieldOptions

    /** Vignette post-processing configuration. */
    var vignetteOptions: VignetteOptions

    /** Screen-space ambient occlusion (SSAO) configuration. */
    var ambientOcclusionOptions: AmbientOcclusionOptions

    /** Temporal anti-aliasing (TAA) configuration; effective when [antiAliasing] permits it. */
    var temporalAntiAliasingOptions: TemporalAntiAliasingOptions

    /** Screen-space reflections configuration. */
    var screenSpaceReflectionsOptions: ScreenSpaceReflectionsOptions

    /**
     * Off-screen [RenderTarget] to render into, or `null` to render into the SwapChain.
     * The render target is not owned by the View.
     */
    var renderTarget: RenderTarget?

    /** Shadow mapping technique for the whole View ([ShadowType.PCF], VSM, DPCF, PCSS). */
    @PlatformGap(platforms = [FilamentPlatform.WEB], behavior = "silent no-op unless the filament.js build binds setShadowType (stock upstream prebuilts do not) — web stays on PCF shadows.")
    var shadowType: ShadowType

    /** Variance shadow mapping options; only applies when [shadowType] is [ShadowType.VSM]. */
    var vsmShadowOptions: VsmShadowOptions

    /** Soft shadow options; only applies when [shadowType] is DPCF or PCSS. */
    var softShadowOptions: SoftShadowOptions

    /** Guard-band configuration, letting some effects sample outside the viewport. */
    var guardBandOptions: GuardBandOptions

    /** Stereoscopic (VR) rendering configuration; must be set before the first frame. */
    var stereoscopicOptions: StereoscopicOptions

    /** Hardware MSAA configuration (independent of [antiAliasing]/TAA). */
    var multiSampleAntiAliasingOptions: MultiSampleAntiAliasingOptions

    /** Culls renderables outside the camera frustum. Default: true (disable only for debugging). */
    @PlatformGap(platforms = [FilamentPlatform.WEB], behavior = "setter is a silent no-op — setFrustumCullingEnabled is not bound in filament.js; the getter reflects the locally tracked value.")
    var isFrustumCullingEnabled: Boolean

    /** Master switch for shadow mapping in this View. Default: true. */
    var isShadowingEnabled: Boolean

    /** Enables screen-space refraction for refractive materials. Default: true. */
    var isScreenSpaceRefractionEnabled: Boolean

    /** Allocates a stencil buffer for this View (required for stencil-based effects). Default: false. */
    var isStencilBufferEnabled: Boolean

    /**
     * Inverts the winding order considered front-facing (counter-clockwise by default).
     * Useful for mirror-like reflections rendered with a flipped camera.
     */
    var isFrontFaceWindingInverted: Boolean

    /** Includes transparent renderables in [pick] results. Default: true. */
    var isTransparentPickingEnabled: Boolean

    /**
     * Grid size in world units used for grid-based world-origin snapping. 0 or negative means the
     * size is calculated automatically from the camera frustum. Default: 0 (automatic).
     */
    var gridSize: Double

    /**
     * The effective grid size used for world-origin snapping: [gridSize] when positive, otherwise
     * the automatically calculated size.
     */
    val effectiveGridSize: Double

    /** Sets the float4 material-global value at [index] (0–3), readable from all materials. */
    fun setMaterialGlobal(index: Int, value: FloatArray)

    /** Returns the float4 material-global value at [index] (0–3). */
    fun getMaterialGlobal(index: Int): FloatArray

    /** Discards accumulated frame history (TAA, SSR). Call after a camera cut to avoid ghosting. */
    fun clearFrameHistory(engine: Engine)

    /**
     * Sets the near/far planes (in world units, > 0) used to compute the froxel grid for dynamic
     * lighting. Only lights within this range are lit. Defaults: 5 / 100.
     */
    fun setDynamicLightingOptions(zNear: Float, zFar: Float)

    /** Entity representing the large-scale fog object; can be transformed via TransformManager. */
    val fogEntity: Int

    /** Post-process anti-aliasing operator ([AntiAliasing.FXAA] by default). */
    var antiAliasing: AntiAliasing

    /** Color grading to apply, or `null` for the default. The View does not own it. */
    var colorGrading: ColorGrading?

    /**
     * Returns the most recent number of visible renderables for the current Scene, as calculated
     * the last time Renderer.render() was called with this View and Scene.
     *
     * @return the number of visible renderables, or -1 if no value is available (e.g. before the
     *         first render call, or if the scene was detached).
     */
    fun getVisibleRenderableCount(): Int

    /**
     * Asynchronously picks the renderable at viewport coordinates ([x], [y]) — origin bottom-left —
     * and invokes [callback] with the result a few frames later. Requires the picking feature
     * (enabled by default) and a rendered frame.
     */
    fun pick(x: Int, y: Int, callback: (PickingQueryResult) -> Unit)
}

