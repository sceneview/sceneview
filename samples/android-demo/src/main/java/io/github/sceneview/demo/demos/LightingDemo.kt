package io.github.sceneview.demo.demos

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.google.android.filament.LightManager
import io.github.sceneview.RenderQuality
import io.github.sceneview.SceneView
import io.github.sceneview.createEnvironment
import io.github.sceneview.demo.DemoPreviewPlaceholder
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.DockItem
import io.github.sceneview.demo.LoadingScrim
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.internal.LightingStage
import io.github.sceneview.demo.initialDemoMode
import io.github.sceneview.demo.rememberFirstFrameState
import io.github.sceneview.demo.rememberFitOrbitRadius
import io.github.sceneview.demo.rememberHeroOrbitCameraManipulator
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.environment.Environment
import io.github.sceneview.math.Position
import io.github.sceneview.math.Size
import io.github.sceneview.math.colorOf
import io.github.sceneview.node.DynamicSkyNode
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.sample.rememberMaterialInstance
import io.github.sceneview.sample.rememberUnlitMaterialInstance
import io.github.sceneview.sample.ui.LabeledSlider
import java.util.Locale

/**
 * **Lighting** — the showcase half of the lighting pair: *where does the light come from?*
 *
 * ## What this screen is, and what the lab is
 *
 * Until #3496 / #3497 the catalogue carried seven lighting modes across two cards, split on no
 * principle anyone could state: `lighting` was "pick a `LightManager.Type`", `lighting-lab` was
 * "sky, environments, probes, post-FX and fog" — a drawer whose last two entries are not lighting
 * at all. Each mode built its own engine and reloaded the same helmet, so switching tab meant a
 * black flash, and no two of them could be seen together.
 *
 * The pair is now split by **role**, over one shared stage ([LightingStage]):
 *
 * - **this screen** — three *rigs*, the three real answers to where a frame's light comes from,
 *   with the handful of controls each one needs;
 * - **[LightingLabDemo]** — one workbench, every knob live on the same frame at once.
 *
 * Both ids survive, so every deep link into either keeps working; the retired ids that used to
 * land inside the old lab are re-pointed at whichever half now hosts their subject (see
 * `DeepLinkRouter.DEMO_ID_ALIASES`).
 *
 * ## The three rigs
 *
 * - **[LightingRig.Image]** — an HDR environment and nothing else. Pick one of the seven bundled
 *   environments from a swatch row, show or hide its sky, and rotate the environment: the chrome
 *   probe ball mirrors it turning while the helmet's specular travels with it. This is the rig
 *   most apps actually ship, and it is deliberately the one that opens.
 * - **[LightingRig.Studio]** — a three-point rig of analytic lights: a focused-spot key with a
 *   shadow, an opposing point fill and a spot rim, each drawn as a small unlit marker so a light
 *   is a thing you can *see* rather than infer. The key's angle, colour and intensity are the
 *   controls; fill and rim are kept in ratio to the key so the rig stays balanced as it moves.
 * - **[LightingRig.Sun]** — `DynamicSkyNode`, a sun on a clock. The hour drives the sun's colour,
 *   elevation and intensity, and the skybox swaps with it, so midnight is night rather than a
 *   dark noon.
 *
 * Exposure sits outside the rigs because it belongs to the camera, not to a light: it is the one
 * control on every rig, and it is what makes "add more light" and "open the lens" visibly
 * different operations.
 *
 * ## Threading
 *
 * Every Filament call here runs on the composition (main) thread, as the JNI contract requires:
 * the model comes from `rememberModelInstance`, and the HDR environment is built inside a
 * `remember` keyed on the asset path. That build is *synchronous* on purpose — the asynchronous
 * `rememberHDREnvironment` returns null while it decodes, which on an environment **swap** means
 * a frame or two of the neutral fallback, i.e. a black sky flashing between two HDRs. A ~200 ms
 * hitch on a deliberate tap reads as loading; a black flash reads as a bug.
 */
@Composable
fun LightingDemo(onBack: () -> Unit) {
    // Inspection mode (@Preview pane, Roborazzi snapshots): bail out BEFORE rememberEngine(),
    // which needs Filament .so files LayoutLib does not have.
    if (LocalInspectionMode.current) {
        DemoPreviewPlaceholder(title = stringResource(R.string.demo_lighting_title), onBack = onBack)
        return
    }

    var rig by remember {
        mutableStateOf(initialDemoMode(LightingRig.entries, LightingRig.Image))
    }

    // ── Rig state ────────────────────────────────────────────────────────────────────────────
    var environmentOption by remember { mutableStateOf(LightingStage.defaultEnvironment) }
    var environmentRotation by remember { mutableFloatStateOf(0f) }
    var showSky by remember { mutableStateOf(true) }

    var keyAzimuth by remember { mutableFloatStateOf(DEFAULT_KEY_AZIMUTH) }
    var keyIntensity by remember { mutableFloatStateOf(LightingStage.KEY_INTENSITY_DEFAULT) }
    var keyColor by remember { mutableStateOf(LightingStage.keyColors.first()) }
    var fillEnabled by remember { mutableStateOf(true) }
    var rimEnabled by remember { mutableStateOf(true) }
    var showMarkers by remember { mutableStateOf(true) }

    var hour by remember { mutableFloatStateOf(DEFAULT_HOUR) }
    var haze by remember { mutableFloatStateOf(DEFAULT_HAZE) }

    var exposure by remember { mutableFloatStateOf(LightingStage.EXPOSURE_DEFAULT) }
    var orbiting by remember { mutableStateOf(true) }

    // ── Engine + stage resources ─────────────────────────────────────────────────────────────
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)
    val cameraNode = rememberCameraNode(engine)
    val heroInstance = rememberModelInstance(modelLoader, LightingStage.HERO_MODEL)

    val floorMaterial = rememberMaterialInstance(
        materialLoader,
        color = LightingStage.FLOOR_COLOR,
        metallic = 0f,
        roughness = LightingStage.FLOOR_ROUGHNESS,
        reflectance = LightingStage.FLOOR_REFLECTANCE,
    )
    // The chrome probe: a mirror. Metallic 1 / roughness ~0 is the ball that shows what the
    // environment *is*, which is what makes an IBL rotation legible.
    val chromeMaterial = rememberMaterialInstance(
        materialLoader,
        color = Color.White,
        metallic = 1f,
        roughness = 0.05f,
        reflectance = 1f,
    )
    // The matte probe: a diffuse grey. Its terminator is the key direction and the softness of
    // that terminator is the source size — the ball a gaffer reads the light off.
    val matteMaterial = rememberMaterialInstance(
        materialLoader,
        color = Color(0xFFB9BEC6),
        metallic = 0f,
        roughness = 0.85f,
        reflectance = 0.35f,
    )
    // Markers are unlit so a light's own marker never goes dark when the light turns away from
    // the camera — the marker's job is "the source is here", not "the source is lit".
    val keyMarkerMaterial = rememberUnlitMaterialInstance(materialLoader, keyColor.swatch)
    val fillMarkerMaterial = rememberUnlitMaterialInstance(materialLoader, FILL_MARKER_COLOR)
    val rimMarkerMaterial = rememberUnlitMaterialInstance(materialLoader, RIM_MARKER_COLOR)

    // ── Environment ──────────────────────────────────────────────────────────────────────────
    // One HDR at a time, whichever the current rig asks for. Loading three and switching between
    // them would hold three prefiltered cubemaps resident for a screen that shows one.
    val environmentFile = when (rig) {
        LightingRig.Image -> environmentOption.file
        LightingRig.Studio -> STUDIO_ENVIRONMENT_FILE
        LightingRig.Sun -> LightingStage.skyEnvironmentFor(hour).file
    }
    // The sky is drawn when the rig is *about* the world around the subject. A studio is a dark
    // surround by definition, so Studio never draws one.
    val skyVisible = when (rig) {
        LightingRig.Image -> showSky
        LightingRig.Studio -> false
        LightingRig.Sun -> true
    }
    val loadedEnvironment: Environment? = remember(environmentLoader, environmentFile) {
        environmentLoader.createHDREnvironment(assetFileLocation = environmentFile)
    }
    DisposableEffect(loadedEnvironment) {
        onDispose { loadedEnvironment?.let { environmentLoader.destroyEnvironment(it) } }
    }
    val fallbackEnvironment = remember(environmentLoader) { createEnvironment(environmentLoader) }
    DisposableEffect(fallbackEnvironment) {
        onDispose { environmentLoader.destroyEnvironment(fallbackEnvironment) }
    }
    // `copy` shares the loaded environment's Filament handles and is never itself destroyed —
    // only `loadedEnvironment` is, by the DisposableEffect above. Hiding the sky is therefore a
    // free operation rather than a rebuild of the whole prefiltered chain.
    val environment = remember(loadedEnvironment, fallbackEnvironment, skyVisible) {
        loadedEnvironment?.let { if (skyVisible) it else it.copy(skybox = null) } ?: fallbackEnvironment
    }
    // Filament rotates the *lighting* — irradiance and reflections — and leaves the skybox alone,
    // so a rotation applied while the sky is drawn would slide the reflections off the picture
    // behind them. The control is disabled in that state; this keeps the engine agreeing with it.
    val effectiveRotation = if (skyVisible) 0f else environmentRotation
    // The IBL is dimmed for the analytic rigs: at full strength the ambient does the modelling
    // the key light is there to do, and moving the key barely changes the frame.
    val iblIntensity = when (rig) {
        LightingRig.Image -> LightingStage.IBL_INTENSITY_DEFAULT
        LightingRig.Studio -> STUDIO_IBL_INTENSITY
        LightingRig.Sun -> SUN_IBL_INTENSITY
    }
    SideEffect {
        loadedEnvironment?.indirectLight?.let { light ->
            light.setRotation(LightingStage.iblRotation(effectiveRotation))
            light.intensity = iblIntensity
        }
        cameraNode.setExposure(
            aperture = LightingStage.CAMERA_APERTURE,
            shutterSpeed = LightingStage.CAMERA_SHUTTER_SPEED,
            sensitivity = LightingStage.sensitivityFor(exposure),
        )
    }

    // ── Rig geometry ─────────────────────────────────────────────────────────────────────────
    val keyPosition = LightingStage.rigPosition(keyAzimuth, LightingStage.KEY_ELEVATION_DEGREES)
    val fillPosition = LightingStage.rigPosition(
        keyAzimuth + LightingStage.FILL_AZIMUTH_OFFSET_DEGREES,
        LightingStage.FILL_ELEVATION_DEGREES,
    )
    val rimPosition = LightingStage.rigPosition(
        keyAzimuth + LightingStage.RIM_AZIMUTH_OFFSET_DEGREES,
        LightingStage.RIM_ELEVATION_DEGREES,
    )

    val firstFrame = rememberFirstFrameState()
    val orbitRadius = rememberFitOrbitRadius(
        extentX = LightingStage.SUBJECT_EXTENT_X,
        extentY = LightingStage.SUBJECT_EXTENT_Y,
        extentZ = LightingStage.SUBJECT_EXTENT_Z,
        elevationDegrees = LightingStage.ORBIT_ELEVATION_DEGREES,
    )

    DemoScaffold(
        title = stringResource(R.string.demo_lighting_title),
        onBack = onBack,
        firstFrameRendered = firstFrame.rendered,
        loadingLabel = stringResource(R.string.demo_lighting_loading),
        peekHeader = when (rig) {
            LightingRig.Image -> stringResource(
                R.string.demo_lighting_status_image,
                environmentOption.label,
                environmentRotation.toInt(),
            )
            LightingRig.Studio -> stringResource(
                R.string.demo_lighting_status_studio,
                keyColor.label,
                (keyIntensity / 1000f).toInt(),
                1 + (if (fillEnabled) 1 else 0) + (if (rimEnabled) 1 else 0),
            )
            LightingRig.Sun -> stringResource(
                R.string.demo_lighting_status_sun,
                "%.1f".format(Locale.US, hour),
                LightingStage.periodLabel(hour),
            )
        },
        onResetSettings = {
            environmentOption = LightingStage.defaultEnvironment
            environmentRotation = 0f
            showSky = true
            keyAzimuth = DEFAULT_KEY_AZIMUTH
            keyIntensity = LightingStage.KEY_INTENSITY_DEFAULT
            keyColor = LightingStage.keyColors.first()
            fillEnabled = true
            rimEnabled = true
            showMarkers = true
            hour = DEFAULT_HOUR
            haze = DEFAULT_HAZE
            exposure = LightingStage.EXPOSURE_DEFAULT
            orbiting = true
        },
        dock = listOf(
            DockItem(
                icon = Icons.Filled.Cloud,
                label = "Sky",
                // Only the image rig owns this choice: the studio has no sky by definition and
                // the sun rig's sky *is* the demonstration. A dock item that silently does
                // nothing is worse than one that says it cannot.
                enabled = rig == LightingRig.Image,
                selected = skyVisible,
                onClick = { showSky = !showSky },
            ),
            DockItem(
                icon = Icons.Filled.Lightbulb,
                label = "Markers",
                enabled = rig == LightingRig.Studio,
                selected = showMarkers && rig == LightingRig.Studio,
                onClick = { showMarkers = !showMarkers },
            ),
            DockItem(
                icon = if (orbiting) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                label = "Animate",
                selected = orbiting,
                onClick = { orbiting = !orbiting },
            ),
        ),
        controls = {
            RigSelector(rig) { rig = it }
            Text(
                text = stringResource(rig.explainerRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(SceneViewTokens.Space.md))

            when (rig) {
                LightingRig.Image -> {
                    Text(
                        stringResource(R.string.demo_lighting_environment),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Spacer(modifier = Modifier.height(SceneViewTokens.Space.sm))
                    EnvironmentSwatches(
                        selected = environmentOption,
                        onSelect = { environmentOption = it },
                    )
                    Spacer(modifier = Modifier.height(SceneViewTokens.Space.md))
                    LabeledSlider(
                        label = stringResource(R.string.demo_lighting_rotation),
                        value = environmentRotation,
                        onValueChange = { environmentRotation = it },
                        valueRange = 0f..360f,
                        decimals = 0,
                        unit = "°",
                        enabled = !skyVisible,
                    )
                    Text(
                        text = stringResource(R.string.demo_lighting_rotation_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                LightingRig.Studio -> {
                    LabeledSlider(
                        label = stringResource(R.string.demo_lighting_key_angle),
                        value = keyAzimuth,
                        onValueChange = { keyAzimuth = it },
                        valueRange = 0f..360f,
                        decimals = 0,
                        unit = "°",
                    )
                    Spacer(modifier = Modifier.height(SceneViewTokens.Space.sm))
                    LabeledSlider(
                        label = stringResource(R.string.demo_lighting_key_intensity),
                        value = keyIntensity,
                        onValueChange = { keyIntensity = it },
                        valueRange = LightingStage.KEY_INTENSITY_MIN..LightingStage.KEY_INTENSITY_MAX,
                        decimals = 0,
                        unit = "cd",
                    )
                    Spacer(modifier = Modifier.height(SceneViewTokens.Space.md))
                    Text(
                        stringResource(R.string.demo_lighting_key_color),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Spacer(modifier = Modifier.height(SceneViewTokens.Space.sm))
                    KeyColorSwatches(selected = keyColor, onSelect = { keyColor = it })
                    Spacer(modifier = Modifier.height(SceneViewTokens.Space.md))
                    SwitchRow(
                        label = stringResource(R.string.demo_lighting_fill),
                        checked = fillEnabled,
                        onCheckedChange = { fillEnabled = it },
                    )
                    SwitchRow(
                        label = stringResource(R.string.demo_lighting_rim),
                        checked = rimEnabled,
                        onCheckedChange = { rimEnabled = it },
                    )
                }

                LightingRig.Sun -> {
                    LabeledSlider(
                        label = stringResource(R.string.demo_lighting_time_of_day),
                        value = hour,
                        onValueChange = { hour = it },
                        valueRange = 0f..24f,
                        valueText = "%.1f h · %s".format(
                            Locale.US,
                            hour,
                            LightingStage.periodLabel(hour),
                        ),
                    )
                    Spacer(modifier = Modifier.height(SceneViewTokens.Space.sm))
                    LabeledSlider(
                        label = stringResource(R.string.demo_lighting_haze),
                        value = haze,
                        onValueChange = { haze = it },
                        valueRange = 1f..10f,
                        decimals = 1,
                    )
                }
            }

            Spacer(modifier = Modifier.height(SceneViewTokens.Space.md))
            LabeledSlider(
                label = stringResource(R.string.demo_lighting_exposure),
                value = exposure,
                onValueChange = { exposure = it },
                valueRange = LightingStage.EXPOSURE_MIN..LightingStage.EXPOSURE_MAX,
                decimals = 2,
            )
        },
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            SceneView(
                modifier = Modifier.fillMaxSize(),
                onFrame = firstFrame.onFrame,
                engine = engine,
                modelLoader = modelLoader,
                materialLoader = materialLoader,
                environmentLoader = environmentLoader,
                environment = environment,
                cameraNode = cameraNode,
                // Bloom, 4× MSAA and high-quality SSAO. This screen is the SDK's hero shot for
                // lighting and it renders one 0.5 m subject; the budget is there to spend.
                renderQuality = RenderQuality.Cinematic,
                // Every rig brings its own light. The library's stock 110 klx main + fill would
                // sit on top of all three and flatten exactly the differences the screen exists
                // to show — a key light you move would barely change the frame.
                mainLightNode = null,
                fillLightNode = null,
                // The stage is authored in world space: the floor, the two probes and the light
                // markers all sit at deliberate offsets around a hero on the origin. Auto-centring
                // would take the union of all of that — floor included — and slide the helmet off
                // the orbit pivot.
                autoCenterContent = false,
                cameraManipulator = rememberHeroOrbitCameraManipulator(
                    trigger = orbiting && heroInstance != null,
                    radius = orbitRadius,
                    yHeight = LightingStage.orbitHeight(orbitRadius),
                    durationMillis = LightingStage.ORBIT_DURATION_MILLIS,
                    staticYaw = LightingStage.STATIC_YAW,
                ),
            ) {
                // ── The stage: identical on both lighting screens ────────────────────────────
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

                // ── The rig ──────────────────────────────────────────────────────────────────
                when (rig) {
                    // Image-based: the environment *is* the rig. Nothing analytic is added, which
                    // is the point — an IBL casts no shadow of its own, and the contact under the
                    // helmet comes from ambient occlusion.
                    LightingRig.Image -> Unit

                    LightingRig.Studio -> {
                        LightNode(
                            type = LightManager.Type.FOCUSED_SPOT,
                            intensity = keyIntensity,
                            direction = LightingStage.aimAtStage(keyPosition),
                            position = keyPosition,
                            color = colorOf(keyColor.r, keyColor.g, keyColor.b),
                            apply = {
                                // A soft-edged cone: 25° of full intensity inside a 40° falloff,
                                // wide enough to cover helmet and both probes from any azimuth.
                                spotLightCone(KEY_CONE_INNER, KEY_CONE_OUTER)
                                falloff(RIG_FALLOFF)
                                // The key is the only shadow caster. Two casting spots on one
                                // subject give it two hard shadows, which reads as a rendering
                                // bug rather than as lighting.
                                castShadows(true)
                            },
                        )
                        if (fillEnabled) {
                            LightNode(
                                type = LightManager.Type.POINT,
                                intensity = keyIntensity * LightingStage.FILL_RATIO,
                                position = fillPosition,
                                color = colorOf(FILL_R, FILL_G, FILL_B),
                                apply = { falloff(RIG_FALLOFF) },
                            )
                        }
                        if (rimEnabled) {
                            LightNode(
                                type = LightManager.Type.FOCUSED_SPOT,
                                intensity = keyIntensity * LightingStage.RIM_RATIO,
                                direction = LightingStage.aimAtStage(rimPosition),
                                position = rimPosition,
                                color = colorOf(RIM_R, RIM_G, RIM_B),
                                apply = {
                                    spotLightCone(RIM_CONE_INNER, RIM_CONE_OUTER)
                                    falloff(RIG_FALLOFF)
                                },
                            )
                        }
                        if (showMarkers) {
                            SphereNode(
                                radius = LightingStage.MARKER_RADIUS,
                                materialInstance = keyMarkerMaterial,
                                position = keyPosition,
                            )
                            if (fillEnabled) {
                                SphereNode(
                                    radius = LightingStage.MARKER_RADIUS,
                                    materialInstance = fillMarkerMaterial,
                                    position = fillPosition,
                                )
                            }
                            if (rimEnabled) {
                                SphereNode(
                                    radius = LightingStage.MARKER_RADIUS,
                                    materialInstance = rimMarkerMaterial,
                                    position = rimPosition,
                                )
                            }
                        }
                    }

                    LightingRig.Sun -> DynamicSkyNode(
                        timeOfDay = hour,
                        turbidity = haze,
                        sunIntensity = LightingStage.SUN_INTENSITY,
                    )
                }
            }
            LoadingScrim(
                loading = heroInstance == null,
                label = stringResource(R.string.demo_lighting_loading),
            )
        }
    }
}

/**
 * Declaration order is the segmented-button order, and
 * [io.github.sceneview.demo.DeepLinkRouter.ALIAS_INITIAL_TAB] indexes into it — `environment` = 0,
 * `movable-light` = 1, `dynamic-sky` = 2. Append, never reorder, or a retired deep link lands on
 * the wrong rig.
 */
private enum class LightingRig(
    @StringRes val labelRes: Int,
    @StringRes val explainerRes: Int,
) {
    Image(R.string.demo_lighting_rig_image, R.string.demo_lighting_rig_image_explainer),
    Studio(R.string.demo_lighting_rig_studio, R.string.demo_lighting_rig_studio_explainer),
    Sun(R.string.demo_lighting_rig_sun, R.string.demo_lighting_rig_sun_explainer),
}

@Composable
private fun RigSelector(current: LightingRig, onRigChange: (LightingRig) -> Unit) {
    val rigs = LightingRig.entries
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        rigs.forEachIndexed { index, rig ->
            SegmentedButton(
                selected = rig == current,
                onClick = { onRigChange(rig) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = rigs.size),
                label = { Text(stringResource(rig.labelRes)) },
            )
        }
    }
    Spacer(modifier = Modifier.height(SceneViewTokens.Space.sm))
}

/**
 * The visual environment picker: a vertical sky-over-ground gradient per environment, which is
 * how the eye tells a sunset from an overcast without reading a word.
 */
@Composable
private fun EnvironmentSwatches(
    selected: LightingStage.EnvironmentOption,
    onSelect: (LightingStage.EnvironmentOption) -> Unit,
) {
    // Seven 34 dp circles plus gaps overflow the sheet on the narrowest phones, and a clipped
    // swatch is an environment the user cannot reach — so the row scrolls rather than truncates.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm),
    ) {
        LightingStage.environments.forEach { option ->
            Box(
                modifier = Modifier
                    .size(SWATCH_SIZE)
                    .clip(CircleShape)
                    .background(
                        Brush.verticalGradient(listOf(option.swatchTop, option.swatchBottom)),
                        CircleShape,
                    )
                    .then(
                        if (option == selected) {
                            Modifier.border(
                                SWATCH_SELECTED_BORDER,
                                MaterialTheme.colorScheme.primary,
                                CircleShape,
                            )
                        } else {
                            Modifier.border(
                                SWATCH_BORDER,
                                MaterialTheme.colorScheme.outlineVariant,
                                CircleShape,
                            )
                        }
                    )
                    .clickable { onSelect(option) }
                    .semantics { contentDescription = option.label },
            )
        }
    }
    Spacer(modifier = Modifier.height(SceneViewTokens.Space.xs))
    Text(
        text = selected.label,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun KeyColorSwatches(
    selected: LightingStage.LightColor,
    onSelect: (LightingStage.LightColor) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.md),
    ) {
        LightingStage.keyColors.forEach { option ->
            Box(
                modifier = Modifier
                    .size(SWATCH_SIZE)
                    .clip(CircleShape)
                    .background(option.swatch, CircleShape)
                    .then(
                        if (option == selected) {
                            Modifier.border(
                                SWATCH_SELECTED_BORDER,
                                MaterialTheme.colorScheme.primary,
                                CircleShape,
                            )
                        } else {
                            Modifier.border(
                                SWATCH_BORDER,
                                MaterialTheme.colorScheme.outlineVariant,
                                CircleShape,
                            )
                        }
                    )
                    .clickable { onSelect(option) }
                    .semantics { contentDescription = option.label },
            )
        }
    }
}

/**
 * A label + switch row that is toggleable as a whole — tapping the words flips the state, and
 * UiAutomator finds one clickable ancestor instead of hunting the thumb.
 */
@Composable
internal fun SwitchRow(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, onValueChange = onCheckedChange),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, enabled = enabled, onCheckedChange = null)
    }
}

/** Stage right and a little above — the classic key position, and the one the card art was lit at. */
private const val DEFAULT_KEY_AZIMUTH = 48f
/**
 * The hour the Sun rig opens on.
 *
 * `DynamicSkyNode` scales the sun by `sin(elevation)`, so the late hours are also the dim ones:
 * at 17.5 h the sun is 8° up and worth 20 klx, which the sunset HDR's own ambient nearly matches.
 * 16.5 h puts it at ~26° — 61 klx, warm, and a cast shadow that stays on the 4 m floor. It is also
 * the first hour of the Golden hour bucket, so the pill, the sky and the light all agree.
 */
private const val DEFAULT_HOUR = 16.5f
private const val DEFAULT_HAZE = 2.5f

/** The photo-studio HDR: a dark surround with a few big softboxes, so it fills without modelling. */
private const val STUDIO_ENVIRONMENT_FILE = "environments/studio_warm_2k.hdr"

/** Ambient floor under the studio rig, lux. Enough that a shadow is dark, not empty. */
private const val STUDIO_IBL_INTENSITY = 1_800f

/** The sun rig keeps a fuller ambient: an outdoor sky *is* a big source. */
private const val SUN_IBL_INTENSITY = 14_000f

private const val KEY_CONE_INNER = 0.44f    // ≈ 25°
private const val KEY_CONE_OUTER = 0.70f    // ≈ 40°
private const val RIM_CONE_INNER = 0.20f    // ≈ 11°
private const val RIM_CONE_OUTER = 0.42f    // ≈ 24°

/** Attenuation radius for every rig light — comfortably past the far edge of the stage. */
private const val RIG_FALLOFF = 6f

// Fill is cool and rim is cold-white: the two conventions that keep a three-point rig readable
// whatever colour the key is gelled.
private const val FILL_R = 0.62f
private const val FILL_G = 0.72f
private const val FILL_B = 0.92f
private const val RIM_R = 0.86f
private const val RIM_G = 0.92f
private const val RIM_B = 1f
private val FILL_MARKER_COLOR = Color(0xFF9EB8E8)
private val RIM_MARKER_COLOR = Color(0xFFDDEBFF)

/** Swatch geometry. 34 dp inside a 48 dp row keeps the touch target legal without a grid. */
private val SWATCH_SIZE = 34.dp
private val SWATCH_BORDER = 1.dp
private val SWATCH_SELECTED_BORDER = 3.dp
