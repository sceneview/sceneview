package io.github.sceneview.ar

import com.google.android.filament.Engine
import com.google.android.filament.utils.KTX1Loader
import io.github.sceneview.ar.camera.ARCameraStream
import io.github.sceneview.ar.node.ARCameraNode
import io.github.sceneview.createEnvironment
import io.github.sceneview.loaders.MaterialLoader
import java.nio.Buffer

// ── AR-specific factory functions ────────────────────────────────────────────────────────────────
//
// Only factories that depend on ARCore types live here. Filament-only factories (including
// createARView) are in SceneFactories.kt in the base `sceneview` module.
// Corresponding remember* hooks are in ARSceneView.kt.
//
// AR-specific factory composables. These live in `arsceneview` to keep the ARCore dependency
// out of the lightweight `sceneview` module.
//

/**
 * Creates an [ARCameraNode] with default AR-appropriate exposure settings.
 *
 * The default exposure (`f/12, 1/200 s, ISO 200` ≈ EV 11.6) matches the 3D
 * `DefaultCameraNode` and the v4.1.0 main+fill light setup (10k + 3k lux). See
 * [ARDefaultCameraNode] for why the previous "sunny-16" exposure broke after
 * the v4.1.0 light rebalance (#1067).
 */
fun createARCameraNode(engine: Engine): ARCameraNode = ARDefaultCameraNode(engine)

/**
 * Creates an [ARCameraStream] that renders the device camera feed as the scene background.
 *
 * @param materialLoader The [MaterialLoader] used to build the camera background material.
 */
fun createARCameraStream(materialLoader: MaterialLoader) = ARCameraStream(materialLoader)

/**
 * Creates an AR-optimised [io.github.sceneview.environment.Environment] with no skybox
 * (transparent background so the camera feed shows through) and an optional IBL baseline.
 *
 * Pass [iblBuffer] (e.g. read from `assets/environments/neutral/neutral_ibl.ktx`) to give
 * PBR materials something sensible to reflect in the first frames before ARCore's
 * `ENVIRONMENTAL_HDR` light estimate stabilises (#1063). ARCore replaces the IBL each frame
 * in [ARScene]'s update loop once the estimate is available; without this baseline metals
 * show up jet-black until ARCore has had several frames of camera motion to learn the
 * environment.
 *
 * Prefer the [rememberAREnvironment] composable, which reads the bundled
 * `environments/neutral/neutral_ibl.ktx` automatically.
 *
 * Note: AR environments are inherently non-opaque — the camera feed is always behind the
 * scene, and `skybox` is hard-coded to `null` so the camera passthrough shows through.
 * `createEnvironment(isOpaque = …)` only feeds its default skybox alpha, which is bypassed
 * here, so this factory does not expose an `isOpaque` parameter (#1121).
 */
fun createAREnvironment(
    engine: Engine,
    iblBuffer: Buffer? = null,
) = createEnvironment(
    engine = engine,
    // Apply DEFAULT_IBL_INTENSITY (10k lux) so the AR baseline IBL doesn't blow out
    // direct lighting once ARCore ENVIRONMENTAL_HDR replaces it (#1075). Same pattern
    // as the 3D `createEnvironment` path.
    indirectLight = iblBuffer?.let {
        KTX1Loader.createIndirectLight(engine, it).indirectLight
            ?.also { ibl -> ibl.intensity = io.github.sceneview.DEFAULT_IBL_INTENSITY }
    },
    // Skybox = null: camera feed passes through. `isOpaque` is intentionally omitted from
    // the call — it only drives the default skybox alpha and is bypassed by this explicit
    // null (#1121). The remaining `Skybox.Builder()` evaluation in the default-arg path is
    // never reached because `skybox` is named and assigned `null` here.
    skybox = null,
)

/**
 * Default AR camera node, exposure realigned with the v4.1.0 main+fill light setup
 * (10k + 3k lux + 10k IBL after #1075).
 *
 * Mirrors the 3D `DefaultCameraNode` exposure at `f/12, 1/200 s, ISO 200` (≈ EV 11.6).
 * The previous `f/16, 1/125 s, ISO 100` ("sunny-16", ≈ EV 15) assumed the pre-v4.1.0
 * 100k lux main light. After the v4.1.0 rebalance the AR camera was 10× too dim,
 * which is why every AR demo had to override `cameraExposure = -1.0f` as a workaround.
 * With #1067 those workarounds become removable (track via the AR demo audit).
 */
class ARDefaultCameraNode(engine: Engine) : ARCameraNode(engine) {
    init {
        setExposure(DEFAULT_APERTURE, DEFAULT_SHUTTER_SPEED, DEFAULT_ISO)
    }

    companion object {
        /**
         * Aperture (f-stop). Re-exports the 3D [io.github.sceneview.DefaultCameraNode]
         * value so the AR camera stays in cross-mode parity even if the 3D side bumps it.
         */
        const val DEFAULT_APERTURE = io.github.sceneview.DefaultCameraNode.DEFAULT_APERTURE

        /** Shutter speed in seconds. See [DEFAULT_APERTURE] for the parity rule. */
        const val DEFAULT_SHUTTER_SPEED = io.github.sceneview.DefaultCameraNode.DEFAULT_SHUTTER_SPEED

        /** ISO sensitivity. See [DEFAULT_APERTURE] for the parity rule. */
        const val DEFAULT_ISO = io.github.sceneview.DefaultCameraNode.DEFAULT_ISO
    }
}
