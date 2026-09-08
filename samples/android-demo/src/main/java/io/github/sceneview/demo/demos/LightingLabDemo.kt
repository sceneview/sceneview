package io.github.sceneview.demo.demos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.Lens
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import com.google.android.filament.LightManager
import com.google.android.filament.View.AntiAliasing
import com.google.android.filament.View.Dithering
import io.github.sceneview.SceneView
import io.github.sceneview.createEnvironment
import io.github.sceneview.demo.DemoPreviewPlaceholder
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.DockItem
import io.github.sceneview.demo.LoadingScrim
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.internal.LightingStage
import io.github.sceneview.demo.rememberFirstFrameState
import io.github.sceneview.demo.rememberFitOrbitRadius
import io.github.sceneview.demo.rememberHeroOrbitCameraManipulator
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.environment.Environment
import io.github.sceneview.math.Position
import io.github.sceneview.math.Size
import io.github.sceneview.math.colorOf
import io.github.sceneview.node.FogNode
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberScene
import io.github.sceneview.rememberView
import io.github.sceneview.sample.rememberMaterialInstance
import io.github.sceneview.sample.rememberUnlitMaterialInstance
import io.github.sceneview.sample.ui.LabeledSlider
import java.util.Locale
import com.google.android.filament.Scene as FilamentScene

/**
 * **Lighting Lab** — the workbench half of the lighting pair: *what can I turn?*
 *
 * ## Why this is one screen and no longer five tabs
 *
 * This card shipped as five modes — Sky, Environment, Reflections, Post-FX, Fog — each with its
 * own `Engine`, its own loaders and its own copy of the helmet. Three things followed, and all
 * three are what #3497 reports:
 *
 * - switching mode tore the engine down and reloaded a 3.5 MB model, so every tap was a black
 *   flash and a second of nothing;
 * - no two settings could be seen **together** — the one question a lab exists to answer is "what
 *   does ambient occlusion look like *with* fog, at *this* exposure", and five tabs made that
 *   question unaskable;
 * - two of the five (Post-FX, Fog) are not lighting, which is what made the pair's split
 *   unstateable in the first place.
 *
 * So the modes are gone and the knobs are all here, live, on the one stage
 * ([LightingStage]) that [LightingDemo] also lights. That shared stage is the point: the showcase
 * and the workbench differ by *role*, not by subject, and a user who moves between them
 * recognises the same helmet, the same floor and the same two probe balls.
 *
 * `DynamicSkyNode` — the old Sky tab — moved to [LightingDemo]'s Sun rig, where "where does the
 * light come from" is the question it answers. Every retired deep link still resolves; the ones
 * whose subject moved now point at the half that hosts it (see `DeepLinkRouter.DEMO_ID_ALIASES`).
 *
 * ## What is on the bench
 *
 * - **Camera** — `CameraNode.setExposure`, the control that separates "add light" from "open the
 *   lens".
 * - **Environment** — `IndirectLight.intensity` and `IndirectLight.setRotation`, the skybox, and
 *   `ReflectionProbeNode`: a local IBL override that swaps the reflection for a sunset while the
 *   camera is inside its zone.
 * - **Frame** — the per-`View` options: SSAO, `FogNode`, MSAA, FXAA and dithering, each starting
 *   from the value `SceneView` actually ships so a flipped switch shows the contrast with the
 *   library default rather than teaching a wrong one.
 *
 * The rig is fixed on purpose: one shadow-casting key over a studio IBL. A workbench whose
 * lighting also moves has two variables in every comparison.
 *
 * ## Threading
 *
 * Filament JNI, main thread: the model comes from `rememberModelInstance`, the environments are
 * built inside `remember` blocks that run in composition, and the `View` options are pushed from
 * a `SideEffect` — never from a background coroutine.
 */
@Composable
fun LightingLabDemo(onBack: () -> Unit) {
    if (LocalInspectionMode.current) {
        DemoPreviewPlaceholder(
            title = stringResource(R.string.demo_lighting_lab_title),
            onBack = onBack,
        )
        return
    }

    // ── Bench state ──────────────────────────────────────────────────────────────────────────
    var exposure by remember { mutableFloatStateOf(LightingStage.EXPOSURE_DEFAULT) }
    var iblIntensity by remember { mutableFloatStateOf(LightingStage.IBL_INTENSITY_DEFAULT) }
    var iblRotation by remember { mutableFloatStateOf(0f) }
    var showSky by remember { mutableStateOf(false) }
    var probeEnabled by remember { mutableStateOf(false) }
    var probeZone by remember { mutableFloatStateOf(LightingStage.PROBE_ZONE_DEFAULT) }
    // Defaults mirror the library's own `createView` (SceneFactories.kt): SSAO on, MSAA off,
    // FXAA on, dithering on. A lab that opened with the wrong defaults would teach them.
    var ssaoEnabled by remember { mutableStateOf(true) }
    var msaaEnabled by remember { mutableStateOf(false) }
    var fxaaEnabled by remember { mutableStateOf(true) }
    var ditheringEnabled by remember { mutableStateOf(true) }
    var fogEnabled by remember { mutableStateOf(false) }
    var fogDensity by remember { mutableFloatStateOf(DEFAULT_FOG_DENSITY) }
    var fogColor by remember { mutableStateOf(LightingStage.fogColors.first()) }

    // ── Engine + stage ───────────────────────────────────────────────────────────────────────
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)
    val view = rememberView(engine)
    val scene: FilamentScene = rememberScene(engine)
    val cameraNode = rememberCameraNode(engine)
    val heroInstance = rememberModelInstance(modelLoader, LightingStage.HERO_MODEL)

    val floorMaterial = rememberMaterialInstance(
        materialLoader,
        color = LightingStage.FLOOR_COLOR,
        metallic = 0f,
        roughness = LightingStage.FLOOR_ROUGHNESS,
        reflectance = LightingStage.FLOOR_REFLECTANCE,
    )
    val chromeMaterial = rememberMaterialInstance(
        materialLoader,
        color = Color.White,
        metallic = 1f,
        roughness = 0.05f,
        reflectance = 1f,
    )
    val matteMaterial = rememberMaterialInstance(
        materialLoader,
        color = Color(0xFFB9BEC6),
        metallic = 0f,
        roughness = 0.85f,
        reflectance = 0.35f,
    )
    val keyMarkerMaterial = rememberUnlitMaterialInstance(materialLoader, KEY_MARKER_COLOR)

    // ── Environments ─────────────────────────────────────────────────────────────────────────
    // Built synchronously in composition, on the main thread, and destroyed by an explicit
    // DisposableEffect — the same contract every environment-owning demo uses.
    val benchEnvironment: Environment? = remember(environmentLoader) {
        environmentLoader.createHDREnvironment(assetFileLocation = BENCH_ENVIRONMENT_FILE)
    }
    DisposableEffect(benchEnvironment) {
        onDispose { benchEnvironment?.let { environmentLoader.destroyEnvironment(it) } }
    }
    val probeEnvironment: Environment? = remember(environmentLoader) {
        environmentLoader.createHDREnvironment(
            assetFileLocation = LightingStage.PROBE_ENVIRONMENT_FILE,
        )
    }
    DisposableEffect(probeEnvironment) {
        onDispose { probeEnvironment?.let { environmentLoader.destroyEnvironment(it) } }
    }
    val fallbackEnvironment = remember(environmentLoader) { createEnvironment(environmentLoader) }
    DisposableEffect(fallbackEnvironment) {
        onDispose { environmentLoader.destroyEnvironment(fallbackEnvironment) }
    }
    val environment = remember(benchEnvironment, fallbackEnvironment, showSky) {
        benchEnvironment?.let { if (showSky) it else it.copy(skybox = null) } ?: fallbackEnvironment
    }
    // Rotating the IBL turns the lighting; Filament's skybox does not turn with it. The slider is
    // disabled while the sky is drawn rather than letting the reflections slide off the picture.
    val effectiveRotation = if (showSky) 0f else iblRotation

    // Camera world position, refreshed each frame so the probe's enter/exit test compares against
    // where the camera actually is instead of against the origin.
    var cameraPosition by remember { mutableStateOf(Position()) }

    SideEffect {
        benchEnvironment?.indirectLight?.let { light ->
            light.setRotation(LightingStage.iblRotation(effectiveRotation))
            light.intensity = iblIntensity
        }
        cameraNode.setExposure(
            aperture = LightingStage.CAMERA_APERTURE,
            shutterSpeed = LightingStage.CAMERA_SHUTTER_SPEED,
            sensitivity = LightingStage.sensitivityFor(exposure),
        )
        // Filament's options getters currently hand back the same mutable struct, so writing
        // through them works; going via the setter keeps that an implementation detail rather
        // than a dependency, in case a future release starts returning a defensive copy.
        view.ambientOcclusionOptions = view.ambientOcclusionOptions.apply {
            enabled = ssaoEnabled
        }
        view.multiSampleAntiAliasingOptions = view.multiSampleAntiAliasingOptions.apply {
            enabled = msaaEnabled
        }
        view.antiAliasing = if (fxaaEnabled) AntiAliasing.FXAA else AntiAliasing.NONE
        view.dithering = if (ditheringEnabled) Dithering.TEMPORAL else Dithering.NONE
    }

    val keyPosition = LightingStage.rigPosition(
        BENCH_KEY_AZIMUTH,
        LightingStage.KEY_ELEVATION_DEGREES,
    )
    val firstFrame = rememberFirstFrameState()
    val orbitRadius = rememberFitOrbitRadius(
        extentX = LightingStage.SUBJECT_EXTENT_X,
        extentY = LightingStage.SUBJECT_EXTENT_Y,
        extentZ = LightingStage.SUBJECT_EXTENT_Z,
        elevationDegrees = LightingStage.ORBIT_ELEVATION_DEGREES,
    )

    DemoScaffold(
        title = stringResource(R.string.demo_lighting_lab_title),
        onBack = onBack,
        firstFrameRendered = firstFrame.rendered,
        loadingLabel = stringResource(R.string.demo_lighting_loading),
        peekHeader = stringResource(
            R.string.demo_lighting_lab_status,
            "%.2f".format(Locale.US, exposure),
            (iblIntensity / 1000f).toInt(),
            stringResource(
                if (ssaoEnabled) R.string.demo_lighting_lab_on else R.string.demo_lighting_lab_off,
            ),
            stringResource(
                if (fogEnabled) R.string.demo_lighting_lab_on else R.string.demo_lighting_lab_off,
            ),
        ),
        onResetSettings = {
            exposure = LightingStage.EXPOSURE_DEFAULT
            iblIntensity = LightingStage.IBL_INTENSITY_DEFAULT
            iblRotation = 0f
            showSky = false
            probeEnabled = false
            probeZone = LightingStage.PROBE_ZONE_DEFAULT
            ssaoEnabled = true
            msaaEnabled = false
            fxaaEnabled = true
            ditheringEnabled = true
            fogEnabled = false
            fogDensity = DEFAULT_FOG_DENSITY
            fogColor = LightingStage.fogColors.first()
        },
        // The four switches worth an instant A/B. Everything with a value lives in the sheet;
        // the dock is for the things you flip back and forth while watching the frame.
        dock = listOf(
            DockItem(
                icon = Icons.Filled.Cloud,
                label = "Sky",
                selected = showSky,
                onClick = { showSky = !showSky },
            ),
            DockItem(
                icon = Icons.Filled.Contrast,
                label = "SSAO",
                selected = ssaoEnabled,
                onClick = { ssaoEnabled = !ssaoEnabled },
            ),
            DockItem(
                icon = Icons.Filled.BlurOn,
                label = "Fog",
                selected = fogEnabled,
                onClick = { fogEnabled = !fogEnabled },
            ),
            DockItem(
                icon = Icons.Filled.Lens,
                label = "Probe",
                selected = probeEnabled,
                onClick = { probeEnabled = !probeEnabled },
            ),
        ),
        controls = {
            Text(
                text = stringResource(R.string.demo_lighting_lab_explainer),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(SceneViewTokens.Space.md))

            SectionHeader(stringResource(R.string.demo_lighting_lab_section_camera))
            LabeledSlider(
                label = stringResource(R.string.demo_lighting_exposure),
                value = exposure,
                onValueChange = { exposure = it },
                valueRange = LightingStage.EXPOSURE_MIN..LightingStage.EXPOSURE_MAX,
                decimals = 2,
            )

            Spacer(modifier = Modifier.height(SceneViewTokens.Space.md))
            SectionHeader(stringResource(R.string.demo_lighting_lab_section_environment))
            LabeledSlider(
                label = stringResource(R.string.demo_lighting_ibl_intensity),
                value = iblIntensity,
                onValueChange = { iblIntensity = it },
                valueRange = LightingStage.IBL_INTENSITY_MIN..LightingStage.IBL_INTENSITY_MAX,
                decimals = 0,
                unit = "lx",
            )
            Spacer(modifier = Modifier.height(SceneViewTokens.Space.sm))
            LabeledSlider(
                label = stringResource(R.string.demo_lighting_rotation),
                value = iblRotation,
                onValueChange = { iblRotation = it },
                valueRange = 0f..360f,
                decimals = 0,
                unit = "°",
                enabled = !showSky,
            )
            Text(
                text = stringResource(R.string.demo_lighting_rotation_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(SceneViewTokens.Space.sm))
            SwitchRow(
                label = stringResource(R.string.demo_lighting_lab_sky),
                checked = showSky,
                onCheckedChange = { showSky = it },
            )
            SwitchRow(
                label = stringResource(R.string.demo_lighting_lab_probe),
                checked = probeEnabled,
                onCheckedChange = { probeEnabled = it },
            )
            LabeledSlider(
                label = stringResource(R.string.demo_lighting_lab_probe_zone),
                value = probeZone,
                onValueChange = { probeZone = it },
                valueRange = LightingStage.PROBE_ZONE_MIN..LightingStage.PROBE_ZONE_MAX,
                decimals = 1,
                unit = "m",
                enabled = probeEnabled,
            )

            Spacer(modifier = Modifier.height(SceneViewTokens.Space.md))
            SectionHeader(stringResource(R.string.demo_lighting_lab_section_frame))
            SwitchRow(
                label = stringResource(R.string.demo_lighting_lab_ssao),
                checked = ssaoEnabled,
                onCheckedChange = { ssaoEnabled = it },
            )
            SwitchRow(
                label = stringResource(R.string.demo_lighting_lab_fog),
                checked = fogEnabled,
                onCheckedChange = { fogEnabled = it },
            )
            LabeledSlider(
                label = stringResource(R.string.demo_lighting_lab_fog_density),
                value = fogDensity,
                onValueChange = { fogDensity = it },
                valueRange = 0f..0.6f,
                decimals = 2,
                enabled = fogEnabled,
            )
            Spacer(modifier = Modifier.height(SceneViewTokens.Space.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm)) {
                LightingStage.fogColors.forEach { option ->
                    FilterChip(
                        selected = fogColor == option,
                        onClick = { fogColor = option },
                        label = { Text(option.label) },
                        enabled = fogEnabled,
                    )
                }
            }
            Spacer(modifier = Modifier.height(SceneViewTokens.Space.sm))
            SwitchRow(
                label = stringResource(R.string.demo_lighting_lab_msaa),
                checked = msaaEnabled,
                onCheckedChange = { msaaEnabled = it },
            )
            SwitchRow(
                label = stringResource(R.string.demo_lighting_lab_fxaa),
                checked = fxaaEnabled,
                onCheckedChange = { fxaaEnabled = it },
            )
            SwitchRow(
                label = stringResource(R.string.demo_lighting_lab_dithering),
                checked = ditheringEnabled,
                onCheckedChange = { ditheringEnabled = it },
            )
        },
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            SceneView(
                modifier = Modifier.fillMaxSize(),
                engine = engine,
                modelLoader = modelLoader,
                materialLoader = materialLoader,
                environmentLoader = environmentLoader,
                environment = environment,
                view = view,
                scene = scene,
                cameraNode = cameraNode,
                // Deliberately NOT Cinematic, unlike the showcase: that preset forces MSAA, SSAO
                // quality and bloom on, which would silently override three of the switches on
                // this bench and make the library's real defaults unobservable.
                mainLightNode = null,
                fillLightNode = null,
                autoCenterContent = false,
                cameraManipulator = rememberHeroOrbitCameraManipulator(
                    trigger = heroInstance != null,
                    radius = orbitRadius,
                    yHeight = LightingStage.orbitHeight(orbitRadius),
                    durationMillis = LightingStage.ORBIT_DURATION_MILLIS,
                    staticYaw = LightingStage.STATIC_YAW,
                ),
                onFrame = { frameTimeNanos ->
                    firstFrame.onFrame(frameTimeNanos)
                    cameraPosition = cameraNode.worldPosition
                },
            ) {
                if (probeEnabled && probeEnvironment != null) {
                    ReflectionProbeNode(
                        filamentScene = scene,
                        environment = probeEnvironment,
                        position = Position(0f, 0f, 0f),
                        radius = probeZone,
                        cameraPosition = cameraPosition,
                    )
                }
                FogNode(
                    view = view,
                    enabled = fogEnabled,
                    density = fogDensity,
                    color = fogColor.color,
                )

                // ── The stage, identical to the showcase's ───────────────────────────────────
                CubeNode(
                    size = Size(
                        LightingStage.FLOOR_SIZE,
                        LightingStage.FLOOR_THICKNESS,
                        LightingStage.FLOOR_SIZE,
                    ),
                    materialInstance = floorMaterial,
                    position = LightingStage.floorCenter,
                )
                heroInstance?.let { instance ->
                    ModelNode(
                        modelInstance = instance,
                        scaleToUnits = LightingStage.HERO_UNITS,
                    )
                }
                SphereNode(
                    radius = LightingStage.PROBE_RADIUS,
                    materialInstance = chromeMaterial,
                    position = LightingStage.chromeProbePosition,
                )
                SphereNode(
                    radius = LightingStage.PROBE_RADIUS,
                    materialInstance = matteMaterial,
                    position = LightingStage.matteProbePosition,
                )

                // One fixed key, so every comparison on this bench has exactly one variable.
                LightNode(
                    type = LightManager.Type.FOCUSED_SPOT,
                    intensity = LightingStage.KEY_INTENSITY_DEFAULT,
                    direction = LightingStage.aimAtStage(keyPosition),
                    position = keyPosition,
                    color = colorOf(1f, 0.97f, 0.92f),
                    apply = {
                        spotLightCone(BENCH_CONE_INNER, BENCH_CONE_OUTER)
                        falloff(BENCH_FALLOFF)
                        castShadows(true)
                    },
                )
                SphereNode(
                    radius = LightingStage.MARKER_RADIUS,
                    materialInstance = keyMarkerMaterial,
                    position = keyPosition,
                )
            }
            LoadingScrim(
                loading = heroInstance == null,
                label = stringResource(R.string.demo_lighting_loading),
            )
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Spacer(modifier = Modifier.height(SceneViewTokens.Space.sm))
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(modifier = Modifier.height(SceneViewTokens.Space.sm))
}

/** The bench IBL: the photo studio, so the knobs act on a neutral, legible baseline. */
private const val BENCH_ENVIRONMENT_FILE = "environments/studio_warm_2k.hdr"

private const val BENCH_KEY_AZIMUTH = 48f
private const val BENCH_CONE_INNER = 0.44f
private const val BENCH_CONE_OUTER = 0.70f
private const val BENCH_FALLOFF = 6f
private const val DEFAULT_FOG_DENSITY = 0.12f
private val KEY_MARKER_COLOR = Color(0xFFFFF6E8)
