package io.github.sceneview.demo.demos

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.android.filament.View.AntiAliasing
import com.google.android.filament.View.Dithering
import io.github.sceneview.SceneView
import io.github.sceneview.createEnvironment
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.LoadingScrim
import io.github.sceneview.demo.R
import io.github.sceneview.demo.common.rememberModelDemoEnvironment
import io.github.sceneview.demo.initialDemoMode
import io.github.sceneview.demo.rememberFirstFrameState
import io.github.sceneview.demo.rememberHeroOrbitCameraManipulator
import io.github.sceneview.environment.Environment
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Scale
import io.github.sceneview.node.DynamicSkyNode
import io.github.sceneview.rememberCameraManipulator
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironment
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberScene
import io.github.sceneview.rememberView
import com.google.android.filament.Scene as FilamentScene
import io.github.sceneview.sample.ui.LabeledSlider
import java.util.Locale

/**
 * Unified "Lighting Lab" demo — consolidates the retired `dynamic-sky`,
 * `environment`, `reflection-probes`, and `post-processing` demos behind a
 * single segmented-button toggle (#2239 Batch 2).
 *
 * Each sub-mode showcases one facet of lighting / environment on the same
 * reflective `khronos_damaged_helmet.glb`:
 *
 * - **Sky** — [DynamicSkyNode] time-of-day sun with turbidity. (Formerly
 *   `dynamic-sky`.)
 * - **Environment** — HDR IBL switching with intensity overrides. (Formerly
 *   `environment`.)
 * - **Reflections** — [ReflectionProbeNode] local IBL override zone. (Formerly
 *   `reflection-probes`.)
 * - **Post-FX** — direct Filament [com.google.android.filament.View]
 *   post-processing (SSAO / MSAA / FXAA / dithering). (Formerly
 *   `post-processing`.)
 *
 * Each sub-mode keeps its own `SceneView` + its own [rememberEngine] / loaders,
 * so switching tabs tears down the inactive section completely — no engine is
 * hoisted above the `when`, which is what prevents resource leaks across tab
 * switches (Batch 1 review confirmed this pattern). Old deep links route
 * through [io.github.sceneview.demo.DeepLinkRouter.DEMO_ID_ALIASES].
 */
@Composable
fun LightingLabDemo(onBack: () -> Unit) {
    var mode by remember {
        mutableStateOf(initialDemoMode(LightingLabMode.entries, LightingLabMode.Sky))
    }
    when (mode) {
        LightingLabMode.Sky -> SkySection(onBack, mode) { mode = it }
        LightingLabMode.Environment -> EnvironmentSection(onBack, mode) { mode = it }
        LightingLabMode.Reflections -> ReflectionsSection(onBack, mode) { mode = it }
        LightingLabMode.PostFx -> PostFxSection(onBack, mode) { mode = it }
    }
}

private enum class LightingLabMode(val label: String) {
    Sky("Sky"),
    Environment("Environment"),
    Reflections("Reflections"),
    PostFx("Post-FX"),
}

/**
 * Two 2-up rows instead of one 4-up row (#3322). A single row split the phone-width sheet
 * evenly across all four modes, and "Environment" — this row's widest label, by rendered
 * glyph width rather than character count ("m"/"n"/"o" run wider than "Reflections"'s
 * "i"/"l"/"t" despite both being 11 characters) — didn't fit even after dropping the
 * selection icon and shrinking the label style, confirmed truncating to "Environme…" on an
 * on-device Pixel 9 emulator pass. Pairing two modes per row instead doubles each segment's
 * share of the width, which is what actually clears the label at normal size rather than
 * trading one degraded fallback (wrap) for another (ellipsis).
 */
@Composable
private fun ModeSelector(
    current: LightingLabMode,
    onModeChange: (LightingLabMode) -> Unit,
) {
    val modes = LightingLabMode.entries
    ModeSelectorRow(modes.subList(0, 2), current, onModeChange)
    Spacer(modifier = Modifier.height(8.dp))
    ModeSelectorRow(modes.subList(2, modes.size), current, onModeChange)
    Spacer(modifier = Modifier.height(12.dp))
}

@Composable
private fun ModeSelectorRow(
    modes: List<LightingLabMode>,
    current: LightingLabMode,
    onModeChange: (LightingLabMode) -> Unit,
) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        modes.forEachIndexed { index, m ->
            SegmentedButton(
                selected = m == current,
                onClick = { onModeChange(m) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                // No selection icon: selection already reads from the segment's own color
                // change, so the checkmark's reserved width was pure waste. `maxLines`/
                // `overflow` stay as a last-resort fallback for a row narrower than
                // anything seen in QA, not the primary fix (that's the 2-up split above).
                icon = {},
                label = {
                    Text(
                        m.label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
        }
    }
}

// ─── Sky section ─────────────────────────────────────────────────────────────
// Formerly DynamicSkyDemo. Demonstrates [DynamicSkyNode] — a time-of-day sun
// that changes colour, intensity, and direction as a slider moves from 0 h
// (midnight) to 24 h.

@Composable
private fun SkySection(
    onBack: () -> Unit,
    mode: LightingLabMode,
    onModeChange: (LightingLabMode) -> Unit,
) {
    var timeOfDay by remember { mutableFloatStateOf(12f) }
    var turbidity by remember { mutableFloatStateOf(2f) }

    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)
    val modelInstance = rememberModelInstance(modelLoader, "models/khronos_damaged_helmet.glb")
    // Pick an HDR environment that matches the current time of day. Without this
    // the SceneView default neutral_ibl gives a black-ish skybox that hides the
    // dynamic sun entirely (QA finding 2026-05-11 — "Dynamic Sky black at noon").
    // Three buckets is a coarse approximation, but it covers the three obvious
    // user expectations: night = stars / lights, dawn / dusk = sunset, daytime
    // = blue sky.
    val envAsset = when {
        timeOfDay < 6f || timeOfDay >= 19f -> "environments/rooftop_night_2k.hdr"
        timeOfDay < 9f || timeOfDay >= 17f -> "environments/sunset_2k.hdr"
        else                               -> "environments/outdoor_cloudy_2k.hdr"
    }
    // `key = envAsset` is load-bearing: the factory closes over `envAsset`, which Compose treats
    // as a stable key, so without an explicit key the HDR is built once for noon and the skybox
    // never swaps as the time-of-day slider crosses a bucket boundary (#2353).
    val environment = rememberEnvironment(
        environmentLoader = environmentLoader,
        key = envAsset,
    ) {
        environmentLoader.createHDREnvironment(envAsset)!!
    }

    // Period label for the user — gives continuous visual feedback as the slider moves,
    // even when the HDR env is the same across multiple hours. QA finding 2026-05-11 :
    // "slider has zero visual effect" — because the HDR env only swaps at 3 buckets and
    // the sun direction change is hard to see against a static skybox. Showing the
    // period label means every slider movement updates SOMETHING the user can see.
    val periodLabel = when {
        timeOfDay < 5f          -> "🌙 Night"
        timeOfDay < 7f          -> "🌅 Dawn"
        timeOfDay < 10f         -> "🌄 Morning"
        timeOfDay < 14f         -> "☀️ Noon"
        timeOfDay < 17f         -> "🌤️ Afternoon"
        timeOfDay < 19f         -> "🌇 Sunset"
        timeOfDay < 21f         -> "🌆 Dusk"
        else                    -> "🌙 Night"
    }

    val firstFrame = rememberFirstFrameState()

    DemoScaffold(
        title = stringResource(R.string.demo_lighting_lab_title),
        onBack = onBack,
        firstFrameRendered = firstFrame.rendered,
        controls = {
            ModeSelector(mode, onModeChange)
            LabeledSlider(
                label = "Time of Day",
                value = timeOfDay,
                onValueChange = { timeOfDay = it },
                valueRange = 0f..24f,
                valueText = "%.1f h  ·  $periodLabel".format(Locale.US, timeOfDay),
            )
            Spacer(modifier = Modifier.height(8.dp))
            LabeledSlider(
                label = "Turbidity",
                value = turbidity,
                onValueChange = { turbidity = it },
                valueRange = 1f..10f,
                valueText = "%.1f".format(Locale.US, turbidity),
            )
        }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            SceneView(
                modifier = Modifier.fillMaxSize(),
                onFrame = firstFrame.onFrame,
                engine = engine,
                modelLoader = modelLoader,
                environmentLoader = environmentLoader,
                environment = environment,
                // Disable the constant 110 klx default main light so the DynamicSkyNode's SUN is
                // the only directional contribution. The HDR IBL ambient fill keeps the helmet
                // visible at night when the sun is below the horizon, and the matching skybox
                // gives the user a clear visual feedback that time-of-day actually changed.
                mainLightNode = null,
                cameraManipulator = rememberCameraManipulator()
            ) {
                DynamicSkyNode(
                    timeOfDay = timeOfDay,
                    turbidity = turbidity,
                    // 500 klx (5x the default) so the sun visibly dominates even with the
                    // default IBL ambient — otherwise the neutral IBL's constant ~30 klx
                    // contribution masks the time-of-day changes on the metallic helmet
                    // (PBR reflections = mostly IBL). Bright sun = visible difference.
                    sunIntensity = 500_000f,
                )

                modelInstance?.let { instance ->
                    ModelNode(
                        modelInstance = instance,
                        scaleToUnits = 0.5f,
                        position = Position(y = 0f)
                    )
                }
            }
            // Mirror every other helmet-loading demo: hold a scrim until the
            // model instance is ready so the helmet's async pop-in is hidden
            // behind a labelled placeholder instead of appearing on a bare sky.
            LoadingScrim(loading = modelInstance == null, label = "Loading helmet…")
        }
    }
}

// ─── Environment section ───────────────────────────────────────────────────
// Formerly EnvironmentDemo. Demonstrates HDR environment switching. A reflective
// model (damaged helmet) is loaded so the user can clearly see how each HDR
// environment affects reflections and overall scene lighting. Selecting a
// different chip recreates the [Environment] from the corresponding HDR asset.

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EnvironmentSection(
    onBack: () -> Unit,
    mode: LightingLabMode,
    onModeChange: (LightingLabMode) -> Unit,
) {
    data class EnvOption(val label: String, val file: String)
    data class IntensityOption(val label: String, val lux: Float?)

    val environments = remember {
        listOf(
            EnvOption("Studio", "environments/studio_2k.hdr"),
            EnvOption("Studio Warm", "environments/studio_warm_2k.hdr"),
            EnvOption("Outdoor Cloudy", "environments/outdoor_cloudy_2k.hdr"),
            EnvOption("Chinese Garden", "environments/chinese_garden_2k.hdr"),
            EnvOption("Sunset", "environments/sunset_2k.hdr"),
            EnvOption("Rooftop Night", "environments/rooftop_night_2k.hdr"),
            EnvOption("Night Sky", "environments/night_sky_2k.hdr")
        )
    }
    // `lux = null` keeps the v4.1.0 balanced 10k default (#1075). The 30k preset
    // demonstrates the `indirectLightApply` override (#1124) for bright outdoor HDRIs.
    val intensities = remember {
        listOf(
            IntensityOption("Default (10k)", null),
            IntensityOption("Bright (30k)", 30_000f),
            IntensityOption("Dim (3k)", 3_000f),
        )
    }
    var selectedEnv by remember { mutableStateOf(environments[0]) }
    var selectedIntensity by remember { mutableStateOf(intensities[0]) }

    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)

    // Recreate the environment when the HDR or the intensity override changes.
    // `indirectLightApply` is the v4.1.0 hook (#1124) that lets callers tweak the
    // Filament `IndirectLight.Builder` without copying the buffer-loading boilerplate.
    val environment: Environment = remember(environmentLoader, selectedEnv, selectedIntensity) {
        environmentLoader.createHDREnvironment(
            assetFileLocation = selectedEnv.file,
            indirectLightApply = {
                selectedIntensity.lux?.let { intensity(it) }
            }
        ) ?: createEnvironment(environmentLoader)
    }
    DisposableEffect(environment) {
        onDispose { environmentLoader.destroyEnvironment(environment) }
    }

    val modelInstance = rememberModelInstance(modelLoader, "models/khronos_damaged_helmet.glb")

    // Camera orbits the helmet; the helmet itself stays fixed — otherwise an A/B
    // comparison between HDRs is unreadable because both the reflected highlight and
    // the helmet's face would rotate simultaneously. Static pose + moving camera lets
    // the viewer see how each HDR paints the same surface from different angles.
    val cameraManipulator = rememberHeroOrbitCameraManipulator(
        trigger = modelInstance != null,
        radius = 2.0f,
        yHeight = 0.3f,
        durationMillis = 18_000,
        staticYaw = 30f,
    )

    val firstFrame = rememberFirstFrameState()

    DemoScaffold(
        title = stringResource(R.string.demo_lighting_lab_title),
        onBack = onBack,
        firstFrameRendered = firstFrame.rendered,
        controls = {
            ModeSelector(mode, onModeChange)
            Text(
                text = "HDR Environment",
                style = MaterialTheme.typography.labelLarge
            )
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                environments.forEach { env ->
                    FilterChip(
                        selected = selectedEnv == env,
                        onClick = { selectedEnv = env },
                        label = { Text(env.label) }
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "IBL Intensity (indirectLightApply)",
                style = MaterialTheme.typography.labelLarge
            )
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                intensities.forEach { option ->
                    FilterChip(
                        selected = selectedIntensity == option,
                        onClick = { selectedIntensity = option },
                        label = { Text(option.label) }
                    )
                }
            }
        }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            SceneView(
                modifier = Modifier.fillMaxSize(),
                onFrame = firstFrame.onFrame,
                engine = engine,
                modelLoader = modelLoader,
                environmentLoader = environmentLoader,
                environment = environment,
                cameraManipulator = cameraManipulator,
            ) {
                modelInstance?.let { instance ->
                    ModelNode(
                        modelInstance = instance,
                        scaleToUnits = 0.5f,
                    )
                }
            }
            LoadingScrim(loading = modelInstance == null, label = "Loading helmet…")
        }
    }
}

// ─── Reflections section ───────────────────────────────────────────────────
// Formerly ReflectionProbesDemo. Demonstrates [ReflectionProbeNode] — a local
// IBL override zone. The probe uses a separate HDR environment that overrides
// the scene's default IBL when the camera is within `radius` metres of the
// probe position. Sliders control the probe radius and its Y-axis position.

@Composable
private fun ReflectionsSection(
    onBack: () -> Unit,
    mode: LightingLabMode,
    onModeChange: (LightingLabMode) -> Unit,
) {
    var probeRadius by remember { mutableFloatStateOf(3f) }
    var probeY by remember { mutableFloatStateOf(0.5f) }
    var cameraPos by remember { mutableStateOf(Position()) }

    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)
    val scene: FilamentScene = rememberScene(engine)
    // Hold the camera so we can read its world position every frame. Without this the probe's
    // distance check always compares against Position() (origin) and the probe silently
    // disables itself as soon as the user orbits away from the origin.
    val cameraNode = rememberCameraNode(engine)

    val modelInstance = rememberModelInstance(modelLoader, "models/khronos_damaged_helmet.glb")

    // Probe environment — load a DIFFERENT HDR than the scene default so the probe's reflection
    // is visually distinct when the camera enters its zone. Scene uses the loader's neutral
    // default; probe uses a warm sunset, giving the helmet an orange cast when active.
    val probeEnvironment: Environment = remember(environmentLoader) {
        environmentLoader.createHDREnvironment(assetFileLocation = "environments/sunset_2k.hdr")
            ?: createEnvironment(environmentLoader)
    }
    DisposableEffect(probeEnvironment) {
        onDispose { environmentLoader.destroyEnvironment(probeEnvironment) }
    }

    // Camera orbits the probe volume: this is the whole point of the demo — watching
    // the reflection flip as the camera crosses the probe sphere boundary. Rotating
    // the model instead kept the camera static, so the probe's enter/exit check never
    // fired and the toggle felt broken.
    val cameraManipulator = rememberHeroOrbitCameraManipulator(
        trigger = modelInstance != null,
        radius = 2.2f,
        yHeight = 0.4f,
        durationMillis = 20_000,
        staticYaw = 30f,
    )

    val firstFrame = rememberFirstFrameState()

    DemoScaffold(
        title = stringResource(R.string.demo_lighting_lab_title),
        onBack = onBack,
        firstFrameRendered = firstFrame.rendered,
        controls = {
            ModeSelector(mode, onModeChange)
            LabeledSlider(
                label = "Probe Radius",
                value = probeRadius,
                onValueChange = { probeRadius = it },
                valueRange = 0f..10f,
                valueText = "%.1f m".format(Locale.US, probeRadius),
            )
            Spacer(modifier = Modifier.height(8.dp))
            LabeledSlider(
                label = "Probe Y Position",
                value = probeY,
                onValueChange = { probeY = it },
                valueRange = -2f..3f,
                valueText = "%.1f".format(Locale.US, probeY),
            )
        }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            SceneView(
                modifier = Modifier.fillMaxSize(),
                engine = engine,
                modelLoader = modelLoader,
                environmentLoader = environmentLoader,
                scene = scene,
                cameraNode = cameraNode,
                cameraManipulator = cameraManipulator,
                onFrame = { frameTimeNanos ->
                    // Dismiss the first-frame scrim once Filament presents a frame.
                    firstFrame.onFrame(frameTimeNanos)
                    // Push the latest camera world position into compose state so the
                    // ReflectionProbeNode below can enable/disable itself based on
                    // actual distance instead of always comparing against the origin.
                    cameraPos = cameraNode.worldPosition
                },
            ) {
                ReflectionProbeNode(
                    filamentScene = scene,
                    environment = probeEnvironment,
                    position = Position(x = 0f, y = probeY, z = 0f),
                    radius = probeRadius,
                    cameraPosition = cameraPos,
                )

                modelInstance?.let { instance ->
                    ModelNode(
                        modelInstance = instance,
                        scaleToUnits = 0.5f,
                        position = Position(y = 0f),
                    )
                }
            }
            LoadingScrim(loading = modelInstance == null, label = "Loading helmet…")
        }
    }
}

// ─── Post-FX section ───────────────────────────────────────────────────────
// Formerly PostProcessingDemo. Demonstrates direct Filament [View]
// post-processing controls: SSAO, anti-aliasing, and dithering.
//
// The Filament [View] is created via [rememberView] and passed to [SceneView].
// Toggle switches modify the view's properties on every recomposition via
// [SideEffect]-style updates.
//
// The helmet is staged sitting **on a ground plane** rather than floating in
// the void: SSAO darkens contact zones and crevices, so the soft shadow where
// the helmet meets the floor only exists with SSAO on. Toggling the SSAO switch
// makes that contact shadow flatly appear and disappear — the post-processing
// difference reads at a glance instead of being a subtle change a user can
// easily miss (#1443).

@Composable
private fun PostFxSection(
    onBack: () -> Unit,
    mode: LightingLabMode,
    onModeChange: (LightingLabMode) -> Unit,
) {
    // Initial state mirrors the SDK's library defaults from `SceneFactories.kt:93-112`
    // (`createView`): SSAO on, MSAA off, FXAA on, dithering on. Pre-#1076 this demo
    // shipped `ssaoEnabled = false` which silently disabled SSAO on first paint,
    // teaching users the wrong default. Now the toggles reflect what the library
    // actually does out of the box; flipping them shows the user the contrast
    // vs. the default.
    var ssaoEnabled by remember { mutableStateOf(true) }
    var msaaEnabled by remember { mutableStateOf(false) }
    var fxaaEnabled by remember { mutableStateOf(true) }
    var ditheringEnabled by remember { mutableStateOf(true) }

    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)
    val view = rememberView(engine)
    val modelInstance = rememberModelInstance(modelLoader, "models/khronos_damaged_helmet.glb")

    // A light, matte ground the helmet rests on. SSAO darkens the contact zone
    // between the model and this plane, so toggling SSAO makes the soft contact
    // shadow visibly appear/disappear — the whole point of the demo (#1443).
    val groundBitmap = remember { createMattGroundBitmap() }

    // Apply post-processing settings to the Filament View after composition lands —
    // a SideEffect runs on every successful recomposition so toggles actually take
    // effect on the next rendered frame. Writing to `view` directly inside the body
    // would work today but is fragile: Filament's options getters currently return
    // the same mutable struct instance, so any future change that returns a defensive
    // copy would silently drop SSAO/FXAA/dithering writes.
    SideEffect {
        view.ambientOcclusionOptions = view.ambientOcclusionOptions.apply {
            enabled = ssaoEnabled
        }
        view.multiSampleAntiAliasingOptions = view.multiSampleAntiAliasingOptions.apply {
            enabled = msaaEnabled
        }
        view.antiAliasing = if (fxaaEnabled) AntiAliasing.FXAA else AntiAliasing.NONE
        view.dithering = if (ditheringEnabled) Dithering.TEMPORAL else Dithering.NONE
    }

    // Camera orbits the helmet from a slightly raised angle so the ground plane
    // reads as a floor receding into the scene — that angle is what makes the
    // SSAO contact shadow under the helmet visible. SSAO / FXAA / dithering read
    // best on a static model where the user can catch aliasing at grazing angles
    // as the camera moves; a spinning helmet would sweep its surface through the
    // same screen pixels so edge aliasing is harder to compare between AA modes.
    val cameraManipulator = rememberHeroOrbitCameraManipulator(
        trigger = modelInstance != null,
        radius = 2.0f,
        yHeight = 0.7f,
        durationMillis = 20_000,
        staticYaw = 30f,
    )

    val firstFrame = rememberFirstFrameState()

    DemoScaffold(
        title = stringResource(R.string.demo_lighting_lab_title),
        onBack = onBack,
        firstFrameRendered = firstFrame.rendered,
        controls = {
            ModeSelector(mode, onModeChange)
            Text(
                "Toggle SSAO and watch the soft contact shadow where the helmet " +
                    "meets the floor appear and disappear.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text("Render Effects", style = MaterialTheme.typography.labelLarge)
            Spacer(modifier = Modifier.height(4.dp))
            ToggleRow("SSAO (Ambient Occlusion)", ssaoEnabled) { ssaoEnabled = it }
            ToggleRow("MSAA (4x Multi-Sample)", msaaEnabled) { msaaEnabled = it }
            ToggleRow("FXAA (Fast Approx. AA)", fxaaEnabled) { fxaaEnabled = it }
            ToggleRow("Temporal Dithering", ditheringEnabled) { ditheringEnabled = it }
        }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            SceneView(
                modifier = Modifier.fillMaxSize(),
                onFrame = firstFrame.onFrame,
                engine = engine,
                modelLoader = modelLoader,
                materialLoader = materialLoader,
                environmentLoader = environmentLoader,
                environment = rememberModelDemoEnvironment(environmentLoader),
                view = view,
                cameraManipulator = cameraManipulator,
            ) {
                // Ground plane the helmet rests on. Laid flat (rotated -90° about
                // X) and pushed down to the base of the model so SSAO has a
                // contact surface to darken.
                ImageNode(
                    bitmap = groundBitmap,
                    position = Position(x = 0f, y = -0.27f, z = 0f),
                    rotation = Rotation(x = -90f),
                    scale = Scale(2.6f),
                )

                modelInstance?.let { instance ->
                    ModelNode(
                        modelInstance = instance,
                        scaleToUnits = 0.5f,
                    )
                }
            }
            LoadingScrim(loading = modelInstance == null, label = "Loading helmet…")
        }
    }
}

/**
 * A plain, light, matte ground bitmap. Kept deliberately flat and uniform so the
 * only thing the eye picks up near the helmet's base is the SSAO contact shadow —
 * a textured or patterned floor would compete with it.
 */
private fun createMattGroundBitmap(): Bitmap {
    val size = 256
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(0xFFB8BCC4.toInt())
    // A faint inset border so the plane reads as a finite surface, not an
    // infinite void-colored quad.
    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFA0A4AC.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }
    val inset = 10f
    canvas.drawRect(inset, inset, size - inset, size - inset, borderPaint)
    return bitmap
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                onValueChange = onCheckedChange,
            ),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = null)
    }
}
