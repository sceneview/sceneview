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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
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
import dev.romainguy.kotlin.math.length
import io.github.sceneview.RenderQuality
import io.github.sceneview.SceneView
import io.github.sceneview.createEnvironment
import io.github.sceneview.demo.DemoPreviewPlaceholder
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.DemoSettings
import io.github.sceneview.demo.DockItem
import io.github.sceneview.demo.LoadingScrim
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.internal.LightingStage
import io.github.sceneview.demo.demos.internal.LightingStageFloor
import io.github.sceneview.demo.demos.internal.StageFade
import io.github.sceneview.demo.demos.internal.rememberResidentEnvironment
import io.github.sceneview.demo.initialDemoMode
import io.github.sceneview.demo.rememberFirstFrameState
import io.github.sceneview.demo.rememberFitOrbitRadius
import io.github.sceneview.demo.rememberHeroOrbitCameraManipulator
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.ui.ConnectedChoiceRow
import io.github.sceneview.math.Position
import io.github.sceneview.math.colorOf
import io.github.sceneview.node.DynamicSkyNode
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberView
import io.github.sceneview.sample.rememberMaterialInstance
import io.github.sceneview.sample.rememberUnlitMaterialInstance
import io.github.sceneview.sample.ui.LabeledSlider
import java.util.Locale
import kotlin.math.abs

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
 * the model comes from `rememberModelInstance`; `rememberResidentEnvironment` decodes HDR pixels
 * off main, then uploads and prefilters them on main. It returns null only for the first load.
 * During every later selection it retains the current environment until the replacement skybox
 * and IBL are both ready, swaps them together, then releases the old pair.
 *
 * While the Sun clock runs, the rig's three skies are loaded once and kept resident behind the
 * one on screen, so a sky change is a swap and not a load: a day is ~34 s and crosses four of
 * them, the shortest for 3.3 s — less than a load takes on a slow device. They are released when
 * the clock stops.
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
    val sampleId = io.github.sceneview.demo.telemetry.LocalSampleId.current

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
    // Held here rather than left to `SceneView`'s default so the stage fade can be written to it.
    val view = rememberView(engine)
    val heroInstance = rememberModelInstance(modelLoader, LightingStage.HERO_MODEL)
    val orbitRadius = rememberFitOrbitRadius(
        extentX = LightingStage.SUBJECT_EXTENT_X,
        extentY = LightingStage.SUBJECT_EXTENT_Y,
        extentZ = LightingStage.SUBJECT_EXTENT_Z,
        elevationDegrees = LightingStage.ORBIT_ELEVATION_DEGREES,
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

    // ── Sun clock ────────────────────────────────────────────────────────────────────────────
    // *Animate* animates the rig, not only the camera: on the Sun rig the clock runs, so the sun
    // crosses the sky and the readout counts the hours (#4072 — before, the hour never moved and
    // Animate only turned the camera). QA mode freezes it, like the orbit, so captures compare.
    // The clock waits for the loading cover to lift: running behind it, the day opened on
    // whatever hour the load happened to end on instead of on golden hour.
    val firstFrame = rememberFirstFrameState(engine)
    val sunClockEnabled = rig == LightingRig.Sun && orbiting && !DemoSettings.qaMode
    val sunClockRunning = sunClockEnabled && heroInstance != null && firstFrame.rendered.value
    LaunchedEffect(sunClockRunning) {
        if (!sunClockRunning) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                hour = LightingStage.advanceSunClock(hour, (now - last) / 1_000_000_000f)
                last = now
            }
        }
    }

    // ── Environment ──────────────────────────────────────────────────────────────────────────
    // One HDR on screen, whichever the current rig asks for, loaded asynchronously. The current
    // complete environment is retained while the next one decodes and prefilters, so the scene
    // never falls back to black or neutral between the old skybox and its replacement IBL. A
    // running clock also keeps the Sun rig's skies resident (see `presentedEnvironment` below).
    // The clock writes `hour` every frame. Everything this scope needs from it changes a few
    // times a day, so it reads those through `derivedStateOf`: reading `hour` itself here would
    // recompose the whole screen — and re-run the `SideEffect` below — at 60 Hz. `hour` is read
    // only by the sun (`DynamicSkyNode`, in the scene's own scope), the status pill and the slider.
    val sunSkyFile by remember { derivedStateOf { LightingStage.skyEnvironmentFor(hour).file } }
    val sunFadeSkyTint by remember { derivedStateOf { LightingStage.stageFadeSkyTint(hour) } }
    val sunStatus by remember {
        derivedStateOf { "%.1f".format(Locale.US, hour) to LightingStage.periodLabel(hour) }
    }
    val environmentFile = when (rig) {
        LightingRig.Image -> environmentOption.file
        LightingRig.Studio -> STUDIO_ENVIRONMENT_FILE
        LightingRig.Sun -> sunSkyFile
    }
    val fadeSkyTint = if (rig == LightingRig.Sun) sunFadeSkyTint else LightingStage.STAGE_FADE_SKY_TINT
    // The Image rig's photographs are rooms and streets: the far floor takes their ground tone
    // rather than a sample of their lamps and windows (see `StageFade.apply`).
    val fadeGround = if (rig == LightingRig.Image) LightingStage.groundToneFor(environmentFile) else null
    // The sky is drawn when the rig is *about* the world around the subject. A studio is a dark
    // surround by definition, so Studio never draws one.
    val skyVisible = when (rig) {
        LightingRig.Image -> showSky
        LightingRig.Studio -> false
        LightingRig.Sun -> true
    }
    // The clock crosses four skies a day, the shortest for 3.3 s: loaded on demand, a slice can
    // end before its sky is ready and never be seen. So while it runs, the three sky HDRs are
    // loaded once and kept behind the one on screen; they are released when it stops.
    val residentSkies = if (sunClockRunning) LightingStage.skyEnvironmentFiles else emptyList()
    val presentedEnvironment =
        rememberResidentEnvironment(environmentLoader, environmentFile, warm = residentSkies)
    val loadedEnvironment = presentedEnvironment?.resource
    // "Scene ready" is the helmet under the rig's own light, with its sky when it has one — not
    // the fallback-lit, empty stage the first frames show (#4459).
    firstFrame.holdUntil(landed = loadedEnvironment != null)
    firstFrame.holdUntilModels(modelLoader, instancesLoaded = heroInstance != null)
    // The previous environment stays on screen while the next one loads, and for that stretch
    // `skyVisible` already describes the rig being loaded: applied at once it would draw the
    // studio HDR as a sky on Studio → Sun. So the engine follows `skyOnScreen`, the flag of the
    // environment actually presented, and the controls keep `skyVisible`, the one asked for.
    // The environment is published with the file it was loaded from, so the comparison cannot
    // pair a new instance with a request made since.
    val presentedSky = remember { PresentedSky(skyVisible) }
    if (presentedEnvironment?.file == environmentFile) presentedSky.visible = skyVisible
    val skyOnScreen = presentedSky.visible
    val fallbackEnvironment = remember(environmentLoader) { createEnvironment(environmentLoader) }
    DisposableEffect(fallbackEnvironment) {
        onDispose { environmentLoader.destroyEnvironment(fallbackEnvironment) }
    }
    // With no sky, the backdrop is the stage colour rather than the renderer's black clear: the
    // floor fades into that colour (see `StageFade`), and it has to meet the same colour behind
    // it or the fade ends on a band.
    val stageBackdrop = remember(engine) { StageFade.stageBackdrop(engine) }
    DisposableEffect(stageBackdrop) {
        onDispose { engine.destroySkybox(stageBackdrop) }
    }
    // `copy` shares the loaded environment's Filament handles and is never itself destroyed —
    // `rememberResidentEnvironment` owns and releases `loadedEnvironment`. Hiding the sky is
    // therefore a free operation rather than a rebuild of the whole prefiltered chain.
    val environment = remember(loadedEnvironment, fallbackEnvironment, skyOnScreen, stageBackdrop) {
        loadedEnvironment?.let { if (skyOnScreen) it else it.copy(skybox = stageBackdrop) }
            ?: fallbackEnvironment
    }
    // Filament rotates the *lighting* — irradiance and reflections — and leaves the skybox alone,
    // so a rotation applied while the sky is drawn would slide the reflections off the picture
    // behind them. The control is disabled in that state; this keeps the engine agreeing with it.
    val effectiveRotation = if (skyOnScreen) 0f else environmentRotation
    // The IBL is dimmed for the analytic rigs: at full strength the ambient does the modelling
    // the key light is there to do, and moving the key barely changes the frame.
    val iblIntensity = when (rig) {
        LightingRig.Image -> LightingStage.IBL_INTENSITY_DEFAULT
        LightingRig.Studio -> STUDIO_IBL_INTENSITY
        LightingRig.Sun -> SUN_IBL_INTENSITY
    }
    // Read in the composition, NOT inside the `SideEffect` lambda below (#3718). A state value a
    // composable only reads inside a lambda it hands to someone else is not a composition read, so
    // writing it invalidates nothing here and the effect never re-runs — and the *Exposure* slider
    // lives in the `controls = { … }` lambda, a restart scope of its own, so its own recomposition
    // does not bring this one with it. Measured: one drag wrote the state 20 times (1.00 → 2.70)
    // for 0 runs of the effect and 0 calls to `setExposure`, while *Environment rotation* — whose
    // value is read right here, as `effectiveRotation` — ran it 9 times on one drag.
    val cameraSensitivity = LightingStage.sensitivityFor(exposure)
    // The eye's distance to the orbit target, as of the last presented frame — what the stage
    // fade is solved for. A plain holder, not state: it is written from `onFrame` and must not
    // recompose the screen once per frame.
    val eyeDistance = remember { FloatArray(1) { Float.NaN } }
    SideEffect {
        // The fade's sky sample is sharp only near the far plane (LightingStage.STAGE_FAR).
        if (cameraNode.far != LightingStage.STAGE_FAR) cameraNode.far = LightingStage.STAGE_FAR
        StageFade.apply(
            view = view,
            cameraDistance = eyeDistance[0].takeIf { it.isFinite() } ?: orbitRadius,
            sky = environment.skybox.takeIf { skyOnScreen },
            skyTint = fadeSkyTint,
            ground = fadeGround,
        )
        loadedEnvironment?.indirectLight?.let { light ->
            light.setRotation(LightingStage.iblRotation(effectiveRotation))
            light.intensity = iblIntensity
        }
        cameraNode.setExposure(
            aperture = LightingStage.CAMERA_APERTURE,
            shutterSpeed = LightingStage.CAMERA_SHUTTER_SPEED,
            sensitivity = cameraSensitivity,
        )
        // `IndirectLight` is a *raw* Filament object: the SDK hands it out and never sees it
        // again, so rotating or dimming it reaches the engine and nothing else. Under
        // `OnDemand` — and this screen parks, by design, whenever `Animate` is off — the new
        // lighting would sit in the engine with no frame coming to show it. Measured before
        // this line existed: dragging *Environment rotation* 302° → 100° and *Exposure*
        // 1.00 → 2.72 on the parked scene produced 0 Filament frames and a viewport still lit
        // the old way (#3718). `cameraNode.setExposure` invalidates on its own — it is an SDK
        // mutator — and this covers the two that cannot.
        cameraNode.requestRender()
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

    DemoScaffold(
        title = stringResource(R.string.demo_lighting_title),
        onBack = onBack,
        firstFrameRendered = firstFrame.rendered,
        sceneReady = firstFrame.sceneReady,
        contentFailed = firstFrame.contentFailed,
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
                sunStatus.first,
                sunStatus.second,
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
            RigSelector(rig) { next ->
                if (next != rig) {
                    io.github.sceneview.demo.telemetry.logSampleModeChange(sampleId, next.analyticsMode)
                    rig = next
                }
            }
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
                onFrame = { frameTimeNanos ->
                    firstFrame.onFrame(frameTimeNanos)
                    // Re-solve the fade when a pinch or drag has moved the eye, so zooming out
                    // never brings the floor's edge back into view.
                    val distance = length(cameraNode.worldPosition)
                    if (!(abs(distance - eyeDistance[0]) < EYE_DISTANCE_EPSILON)) {
                        eyeDistance[0] = distance
                        StageFade.apply(
                            view = view,
                            cameraDistance = distance,
                            sky = environment.skybox.takeIf { skyOnScreen },
                            skyTint = fadeSkyTint,
                            ground = fadeGround,
                        )
                        cameraNode.requestRender()
                    }
                },
                engine = engine,
                view = view,
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
                    // Keeps an upward drag from carrying the camera under the floor (#3794).
                    maxPolarDegrees = LightingStage.maxOrbitPolarDegrees(orbitRadius),
                ),
            ) {
                // ── The stage: identical on both lighting screens ────────────────────────────
                LightingStageFloor()
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
internal enum class LightingRig(
    @StringRes val labelRes: Int,
    @StringRes val explainerRes: Int,
    val analyticsMode: String,
) {
    Image(R.string.demo_lighting_rig_image, R.string.demo_lighting_rig_image_explainer, "image"),
    Studio(R.string.demo_lighting_rig_studio, R.string.demo_lighting_rig_studio_explainer, "studio"),
    Sun(R.string.demo_lighting_rig_sun, R.string.demo_lighting_rig_sun_explainer, "sun"),
}

@Composable
private fun RigSelector(current: LightingRig, onRigChange: (LightingRig) -> Unit) {
    ConnectedChoiceRow(
        options = LightingRig.entries,
        selected = current,
        onSelect = onRigChange,
        label = { stringResource(it.labelRes) },
    )
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

/** Whether the environment on screen draws its sky — a plain holder, written in composition. */
private class PresentedSky(var visible: Boolean)

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

/** Eye movement, in metres, below which the stage fade is not re-solved. */
private const val EYE_DISTANCE_EPSILON = 0.01f

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
