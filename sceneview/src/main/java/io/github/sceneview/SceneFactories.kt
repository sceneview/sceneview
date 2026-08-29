package io.github.sceneview

import android.content.Context
import android.opengl.EGLContext
import com.google.android.filament.ColorGrading
import com.google.android.filament.Colors
import com.google.android.filament.Engine
import com.google.android.filament.Filament
import com.google.android.filament.IndirectLight
import com.google.android.filament.LightManager
import com.google.android.filament.Renderer
import com.google.android.filament.Skybox
import com.google.android.filament.ToneMapper
import com.google.android.filament.View
import com.google.android.filament.View.AntiAliasing
import com.google.android.filament.View.QualityLevel
import com.google.android.filament.gltfio.Gltfio
import com.google.android.filament.utils.KTX1Loader
import com.google.android.filament.utils.Utils
import io.github.sceneview.collision.CollisionSystem
import io.github.sceneview.environment.Environment
import io.github.sceneview.gesture.CameraGestureDetector
import io.github.sceneview.loaders.EnvironmentLoader
import io.github.sceneview.managers.color
import io.github.sceneview.math.Position
import io.github.sceneview.math.colorOf
import io.github.sceneview.math.toColor
import io.github.sceneview.node.CameraNode
import io.github.sceneview.node.LightNode
import io.github.sceneview.node.ViewNode
import io.github.sceneview.utils.OpenGL
import io.github.sceneview.utils.readBuffer

// Initialize Filament once (triggered when this file's class is first loaded)
private val filamentInit: Unit = run {
    Gltfio.init()
    Filament.init()
    Utils.init()
}

const val DEFAULT_MAIN_LIGHT_COLOR_TEMPERATURE = 6_500.0f

/**
 * Main directional light intensity (lux).
 *
 * Lowered from the previous photographic value of `100_000` (full noon sun) to `10_000` —
 * closer to RealityKit's default sun on iOS — so default Filament renders no longer look
 * washed out / blown out. Combined with the secondary fill light
 * ([DEFAULT_FILL_LIGHT_COLOR_INTENSITY]), this yields a balanced 3-point–style key+fill
 * setup out of the box. See audit `project_plan_v1_hybrid_2026-05-10`.
 */
const val DEFAULT_MAIN_LIGHT_COLOR_INTENSITY = 10_000.0f
val DEFAULT_MAIN_LIGHT_COLOR = Colors.cct(DEFAULT_MAIN_LIGHT_COLOR_TEMPERATURE).toColor()
val DEFAULT_MAIN_LIGHT_INTENSITY = DEFAULT_MAIN_LIGHT_COLOR_INTENSITY

/**
 * Secondary "fill" directional light — softens the shadows cast by the main light.
 *
 * Color temperature matches the main light (neutral 6500 K). Intensity is 30 % of the main
 * light, matching the ratio used by RealityKit's default scene lighting (sun ~1000, fill ~300).
 */
const val DEFAULT_FILL_LIGHT_COLOR_TEMPERATURE = 6_500.0f
const val DEFAULT_FILL_LIGHT_COLOR_INTENSITY = 3_000.0f
val DEFAULT_FILL_LIGHT_COLOR = Colors.cct(DEFAULT_FILL_LIGHT_COLOR_TEMPERATURE).toColor()
val DEFAULT_FILL_LIGHT_INTENSITY = DEFAULT_FILL_LIGHT_COLOR_INTENSITY

val DEFAULT_OBJECT_POSITION = Position(0.0f, 0.0f, -4.0f)

/**
 * Default `IndirectLight` intensity (lux).
 *
 * Lowered from Filament's hard-coded `30_000` default — too bright after the v4.1.0
 * main+fill rebalancing (10k+3k direct + 30k IBL = ambient dominated everything,
 * shadows looked weak, key-vs-fill ratio invisible). 10k matches the main light so
 * direct + indirect are roughly balanced (≈ 60/40), giving the carefully-tuned 3-point
 * setup actual visible contrast. See [#1075](https://github.com/sceneview/sceneview/issues/1075).
 *
 * Cross-platform parity note: iOS RealityKit uses `IBLComponent.intensityExponent = 0`
 * which exposes-out at ≈1000 lux equivalent. Android stays at 10× that for now (Filament
 * doesn't have an exposure-relative IBL knob); the absolute values diverge but the
 * key:IBL ratio matches.
 */
const val DEFAULT_IBL_INTENSITY = 10_000.0f

fun createEglContext(): EGLContext {
    filamentInit  // ensure init
    return OpenGL.createEglContext()
}

fun createEngine(eglContext: EGLContext): Engine = Engine.create(eglContext)

fun createScene(engine: Engine): com.google.android.filament.Scene = engine.createScene()

fun createView(engine: Engine): View = engine.createView().apply {
    renderQuality = renderQuality.apply {
        hdrColorBuffer = QualityLevel.MEDIUM
    }
    dynamicResolutionOptions = dynamicResolutionOptions.apply {
        enabled = false
        homogeneousScaling = true
        quality = QualityLevel.MEDIUM
    }
    multiSampleAntiAliasingOptions = multiSampleAntiAliasingOptions.apply {
        enabled = false
    }
    antiAliasing = AntiAliasing.FXAA
    // SSAO on by default — adds visible grounding under geometry crevices (toy_car, helmet)
    // without artifacts on diffuse-only models. Validated 2026-05-11 on Pixel_7a GPU host.
    ambientOcclusionOptions = ambientOcclusionOptions.apply {
        enabled = true
    }
    // Subtle bloom on by default — strength 0.10 lifts metallic/emissive highlights
    // (satin chrome, light filaments) but is invisible on diffuse-only assets, so it costs
    // nothing on plain models. Push higher only for cinematic scenes.
    bloomOptions = bloomOptions.apply {
        enabled = true
        strength = 0.1f
    }
    // Keep Filmic tone mapper as default. ACES was tested on 2026-05-11 and shifts PBR
    // hero shots (DamagedHelmet) toward a cooler/desaturated film grade — fine for cinema
    // but not the SDK's job to impose. Users can opt into ACES via `view.colorGrading`.
    colorGrading = ColorGrading.Builder()
        .toneMapper(ToneMapper.Filmic())
        .build(engine)
    // Shadows on by default — matches RealityKit on iOS and produces a more grounded look
    // out of the box. Disable via `View.setShadowingEnabled(false)` when not needed.
    setShadowingEnabled(true)
}

/**
 * Creates a [View] tuned for AR (ARScene).
 *
 * **Note:** This factory lives in the `sceneview` module (not `arsceneview`) because it only
 * depends on Filament — no ARCore types are involved. The `arsceneview` module calls it via
 * [rememberARView]. This avoids duplicating Filament View configuration across modules.
 *
 * The AR camera stream materials (`camera_stream_flat.mat`, `camera_stream_depth.mat`,
 * `camera_stream_person_occlusion.mat`) draw the live camera feed. ARCore hands the buffer over
 * with dataspace `0x08810000` (`STANDARD_BT709 | TRANSFER_SRGB | RANGE_FULL`), so the EGL external
 * sampler has already done YUV→RGB and what the fragment shader reads is sRGB-encoded, full-range
 * BT.709 RGB. To reach the framebuffer untouched, that pixel must be pre-distorted by the exact
 * inverse of everything Filament applies downstream. Two legs, both of which must cancel exactly:
 *
 *   camera sRGB → sRGB EOTF (exact) → Inverse_Tonemap_Filmic → working space
 *               → ToneMapper.Filmic → sRGB OETF (exact) → output = original camera image ✓
 *
 * **Tone-map leg.** `Inverse_Tonemap_Filmic` is the exact analytic inverse of [ToneMapper.Filmic],
 * so the pair cancels term for term. Using [ToneMapper.Linear] here (as a previous fix for #657
 * did) leaves the inverse-Filmic curve baked into the feed with nothing to cancel it — the camera
 * background comes out washed-out and low-contrast (issue #1434). [ToneMapper.Filmic] is the only
 * tone mapper that cancels the shader's curve, and it is also the right curve for virtual content.
 *
 * **Transfer leg.** Filament's color-grading output stage is Rec709-sRGB-D65, i.e. the exact
 * piecewise sRGB OETF. The materials used to decode with Filament's `inverseTonemapSRGB()` helper,
 * which expands to `Inverse_Tonemap_Filmic(pow(c, 2.2))` — and `pow(c, 2.2)` only approximates the
 * sRGB curve, so `sRGB_OETF(pow(c, 2.2))` was not the identity. The residual reached −8.5/255 in
 * the shadows (peak at code 16) and crossed to +1.5/255 in the highlights: achromatic, so not a
 * tint, but an S-curve contrast boost applied to the camera background only, grading the real
 * world differently from the virtual content composited over it (issue #3338). The materials now
 * decode with the exact IEC 61966-2-1 EOTF, which round-trips bit-exact at every 8-bit code.
 *
 * Both legs live in the `.mat` sources — changing the tone mapper here without changing them (or
 * vice versa) breaks the cancellation.
 *
 * Unlike [createView], the AR view keeps bloom and ambient occlusion **off** so they cannot tint
 * the camera background — those were the real source of the oversaturation/vignetting in #657.
 *
 * Shadows are enabled by default because AR users commonly want 3D content to cast shadows on
 * detected planes. Disable via `View.setShadowingEnabled(false)` when not needed.
 */
fun createARView(engine: Engine): View = engine.createView().apply {
    renderQuality = renderQuality.apply {
        hdrColorBuffer = QualityLevel.MEDIUM
    }
    dynamicResolutionOptions = dynamicResolutionOptions.apply {
        enabled = false
        homogeneousScaling = true
        quality = QualityLevel.MEDIUM
    }
    multiSampleAntiAliasingOptions = multiSampleAntiAliasingOptions.apply {
        enabled = false
    }
    antiAliasing = AntiAliasing.FXAA
    ambientOcclusionOptions = ambientOcclusionOptions.apply {
        enabled = false
    }
    // Filmic tone mapper: the AR camera-stream shader pre-applies Inverse_Tonemap_Filmic, so the
    // Filmic post-process re-applies the matching forward curve and the camera background round-
    // trips back to the original pixels. Using ToneMapper.Linear here leaves the inverse curve
    // uncancelled and washes the camera feed out (issue #1434). Its output stage is the exact sRGB
    // OETF, which is why the materials decode with the exact EOTF and not pow(2.2) (issue #3338).
    // See KDoc above for the full chain.
    colorGrading = ColorGrading.Builder()
        .toneMapper(ToneMapper.Filmic())
        .build(engine)
    // Shadows on by default for AR: models casting shadows onto detected planes.
    setShadowingEnabled(true)
}

fun createRenderer(engine: Engine): Renderer = engine.createRenderer()

fun createCameraNode(engine: Engine): CameraNode = DefaultCameraNode(engine)

fun createMainLightNode(engine: Engine): LightNode = DefaultLightNode(engine)

/**
 * Creates the secondary "fill" directional light.
 *
 * Pairs with [createMainLightNode] to produce a soft key+fill setup similar to the default
 * RealityKit lighting on iOS. The fill light is offset from the main light direction so it
 * lifts the unlit side of objects without flattening them, and does not cast shadows
 * (only the main light contributes shadows by default).
 */
fun createFillLightNode(engine: Engine): LightNode = DefaultFillLightNode(engine)

/**
 * Creates the default orbit/pan/zoom [CameraGestureDetector.DefaultCameraManipulator] — the
 * non-Compose twin of `rememberCameraManipulator`, for `SceneView` used as a plain View.
 *
 * @param eyePosition    Camera's initial eye position in **world space** (optional). It is
 *                       Filament's `orbitHomePosition` — there is no "home" gesture, only a
 *                       starting point. Under `SceneView`'s default `autoCenterContent = true`
 *                       the subject is framed from `|eyePosition|`; see
 *                       `rememberCameraManipulator`. `null` keeps Filament's `(0, 0, 1)`.
 * @param targetPosition Point in world space the camera orbits around and initially looks at
 *                       (optional; defaults to the origin). Does not affect the distance.
 */
fun createDefaultCameraManipulator(
    eyePosition: Position? = null,
    targetPosition: Position? = null
) = CameraGestureDetector.DefaultCameraManipulator(
    eyePosition = eyePosition,
    targetPosition = targetPosition
)

/**
 * Creates the default orbit/pan/zoom [CameraGestureDetector.DefaultCameraManipulator] with the
 * camera [orbitRadius] metres from [targetPosition], along
 * [io.github.sceneview.gesture.DEFAULT_ORBIT_DIRECTION] (see
 * [io.github.sceneview.gesture.orbitEyePosition]).
 *
 * @param orbitRadius    Camera-to-target distance in metres. Must be `> 0`.
 * @param targetPosition Point in world space the camera orbits around and initially looks at
 *                       (optional; defaults to the origin).
 */
fun createDefaultCameraManipulator(
    orbitRadius: Float,
    targetPosition: Position? = null
) = CameraGestureDetector.DefaultCameraManipulator(
    orbitRadius = orbitRadius,
    targetPosition = targetPosition
)

/**
 * Deprecated spelling of [createDefaultCameraManipulator] — `orbitHomePosition` promised a
 * "home" gesture that does not exist (#2932). Same behaviour, new name.
 */
@Deprecated(
    message = "orbitHomePosition is the camera's initial eye position — there is no home " +
        "gesture. Use eyePosition, or the orbitRadius overload (#2932).",
    replaceWith = ReplaceWith(
        "createDefaultCameraManipulator(eyePosition = orbitHomePosition, targetPosition = targetPosition)"
    ),
    level = DeprecationLevel.WARNING
)
@JvmName("createDefaultCameraManipulatorOrbitHome")
fun createDefaultCameraManipulator(
    orbitHomePosition: Position,
    targetPosition: Position? = null
) = createDefaultCameraManipulator(eyePosition = orbitHomePosition, targetPosition = targetPosition)

fun createViewNodeManager(context: Context) = ViewNode.WindowManager(context)

fun createEnvironment(
    environmentLoader: EnvironmentLoader,
    isOpaque: Boolean = true
) = createEnvironment(
    engine = environmentLoader.engine,
    isOpaque = isOpaque,
    indirectLight = KTX1Loader.createIndirectLight(
        environmentLoader.engine,
        environmentLoader.context.assets.readBuffer("environments/neutral/neutral_ibl.ktx"),
    ).indirectLight?.also { it.intensity = DEFAULT_IBL_INTENSITY },
)

fun createEnvironment(
    engine: Engine,
    isOpaque: Boolean = true,
    indirectLight: IndirectLight? = null,
    skybox: Skybox? = Skybox.Builder()
        .color(colorOf(rgb = 0.0f, a = if (isOpaque) 1.0f else 0.0f).toFloatArray())
        .build(engine),
    sphericalHarmonics: List<Float>? = null
) = Environment(indirectLight, skybox, sphericalHarmonics)

fun createCollisionSystem(view: View) = CollisionSystem(view)

class DefaultCameraNode(engine: Engine) : CameraNode(engine) {
    init {
        // 3/4 view: slightly elevated and pulled back so a typical 0.3–1 m model placed
        // at the world origin is fully framed and centered (#1080, re-tuned in #1427).
        // The original `(0, 0, 1)` placement sat the camera 1 m dead-ahead of origin —
        // too close for anything but a tiny object and, with no Y elevation, produced a
        // flat, under-framed front-on view. The #1080 `(0, 0.3, 2)` placement was still
        // "beaucoup trop zoomé" in the 2026-05-16 on-device QA, so #1427 pulls the camera
        // further back to `(0, DEFAULT_Y, DEFAULT_Z)` + `lookAt(origin)` for more headroom.
        // iOS RealityKit still uses `[0, 0.3, 2]` (`SceneViewSwift/.../SceneView.swift`) —
        // a matching iOS bump is tracked alongside the auto-framing work (#1439). Pinned
        // in `SceneFactoriesTest.defaultCameraNodePositionIsPinned()`.
        position = Position(0.0f, DEFAULT_Y, DEFAULT_Z)
        lookAt(Position(0.0f, 0.0f, 0.0f))
        // Neutral, less photographic exposure.
        // The previous setting (`f/16, 1/125 s, ISO 100` ≈ EV 15) is "sunny-16" — a real-world
        // outdoor exposure that makes Filament renders look much darker than the iOS
        // RealityKit defaults. Opening up the aperture, slowing the shutter and bumping
        // the ISO produces a brighter, more predictable baseline that matches the
        // RealityKit look out of the box. AR mirrors these values via
        // `ARDefaultCameraNode.DEFAULT_APERTURE/SHUTTER_SPEED/ISO`; the pin lives in
        // `SceneFactoriesTest.defaultExposureMatchesAR()`.
        setExposure(DEFAULT_APERTURE, DEFAULT_SHUTTER_SPEED, DEFAULT_ISO)
    }

    companion object {
        /**
         * Default camera Y elevation. Small positive lift gives a natural 3/4 angle
         * looking slightly down on origin-placed content. Re-tuned in #1427 from `0.3`
         * to keep the 3/4 angle proportional to the larger [DEFAULT_Z] dolly-back.
         */
        const val DEFAULT_Y = 0.4f

        /**
         * Default camera Z distance from origin. Pulls the camera back far enough to
         * frame a typical 0.3–1 m model with comfortable headroom. Re-tuned in #1427
         * from `2.0` — the pre-#1427 value framed origin-placed models too tight
         * ("beaucoup trop zoomé", 2026-05-16 on-device QA).
         */
        const val DEFAULT_Z = 2.75f

        /** Aperture (f-stop). AR mirrors via `ARDefaultCameraNode.DEFAULT_APERTURE`. */
        const val DEFAULT_APERTURE = 12.0f

        /** Shutter speed (seconds). AR mirrors via `ARDefaultCameraNode.DEFAULT_SHUTTER_SPEED`. */
        const val DEFAULT_SHUTTER_SPEED = 1.0f / 200.0f

        /** ISO sensitivity. AR mirrors via `ARDefaultCameraNode.DEFAULT_ISO`. */
        const val DEFAULT_ISO = 200.0f
    }
}

class DefaultLightNode(engine: Engine) : LightNode(
    engine = engine,
    type = LightManager.Type.DIRECTIONAL,
    apply = {
        color(DEFAULT_MAIN_LIGHT_COLOR)
        intensity(DEFAULT_MAIN_LIGHT_COLOR_INTENSITY)
        direction(0.0f, -1.0f, 0.0f)
        castShadows(true)
    }
)

/**
 * Default secondary "fill" directional light.
 *
 * Direction is offset from the main light so the unlit side of objects gets a soft kick,
 * matching the RealityKit-style key+fill look. Does not cast shadows (only the main light
 * contributes shadows by default).
 */
class DefaultFillLightNode(engine: Engine) : LightNode(
    engine = engine,
    type = LightManager.Type.DIRECTIONAL,
    apply = {
        color(DEFAULT_FILL_LIGHT_COLOR)
        intensity(DEFAULT_FILL_LIGHT_COLOR_INTENSITY)
        // Offset direction: lights the side opposite to the main light from a slightly
        // higher angle. The main light points straight down (0, -1, 0); this fill comes
        // from upper-back-left to lift shadow-side faces without flattening the model.
        direction(0.5f, -0.5f, 0.5f)
        castShadows(false)
    }
)
