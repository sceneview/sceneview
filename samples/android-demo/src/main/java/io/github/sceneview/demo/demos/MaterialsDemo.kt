@file:OptIn(io.github.sceneview.ExperimentalSceneViewApi::class)

package io.github.sceneview.demo.demos

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import io.github.sceneview.rememberView
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.utils.worldToScreen
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Lens
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import io.github.sceneview.SceneView
import io.github.sceneview.demo.DemoPreviewPlaceholder
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.DemoSettings
import io.github.sceneview.demo.DockItem
import io.github.sceneview.demo.LoadingScrim
import io.github.sceneview.demo.R
import io.github.sceneview.demo.common.rememberModelDemoEnvironment
import io.github.sceneview.demo.demos.internal.MaterialStudio
import io.github.sceneview.demo.demos.internal.MaterialTrait
import io.github.sceneview.demo.demos.internal.StudioMaterial
import io.github.sceneview.demo.initialDemoMode
import io.github.sceneview.demo.rememberFirstFrameState
import io.github.sceneview.demo.rememberFitOrbitRadius
import io.github.sceneview.demo.rememberHeroOrbitCameraManipulator
import io.github.sceneview.demo.HeroOrbitCameraManipulator
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.environment.rememberHDREnvironment
import io.github.sceneview.haptic.rememberHapticFeedback
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.material.setMetallic
import io.github.sceneview.material.setReflectance
import io.github.sceneview.material.setRoughness
import io.github.sceneview.math.Position
import io.github.sceneview.math.Size
import io.github.sceneview.math.colorOf
import io.github.sceneview.rememberCameraManipulator
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironment
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberOnGestureListener
import io.github.sceneview.sample.LifecycleAwareLaunchedEffect
import io.github.sceneview.sample.rememberOcclusionMaterialInstance
import io.github.sceneview.sample.rememberUnlitMaterialInstance
import io.github.sceneview.sample.ui.LabeledSlider
import kotlinx.coroutines.launch

/**
 * **Materials** — what a physically based surface is, shown rather than described.
 *
 * ## The screen
 *
 * A studio with an HDR sky the user can change, and nine spheres in it. Nothing is loaded
 * from the network, nothing is streamed, and the first frame is the finished picture: the
 * whole wall is built from primitives and material parameters, so it is on screen as soon as
 * the environment's IBL finishes prefiltering.
 *
 * - **Gallery** — the nine materials at once, under a slow camera *sweep* rather than an
 *   orbit. Tap a sphere and the camera flies onto it, then hands over to *Inspect*;
 *   leaving Inspect flies back out to the wall (#3609).
 * - **Inspect** — one of them, large, with its parameters on live sliders. *Compare* splits
 *   the stage so the material you were looking at a moment ago stays on screen next to the
 *   one you moved to, which is the only way to see a roughness difference of 0.1.
 * - **Occlusion** — the odd one out, and deliberately kept (#2239 Batch 4 folded the retired
 *   `occlusion-material` demo in here): `MaterialLoader.createOcclusionInstance()` is a
 *   material too, just one whose entire job is to paint nothing.
 *
 * ## Why the surfaces are real and not approximations
 *
 * Four of the nine — clear coat, sheen, transmission, emission — cannot be expressed by the
 * `color / metallic / roughness / reflectance` set that `MaterialLoader.createColorInstance`
 * exposes, and SceneView's SDK ships no `.filamat` that can. So the demo ships two of its
 * own, `studio_pbr` and `studio_glass`, compiled by `tools/GenerateFilamat.sh` with the same
 * pinned `matc` as every other blob in the repo. See [StudioMaterials] for why the obvious
 * shortcut — Filament's gltfio ubershader, already in the AAR — renders nine black spheres on
 * procedural geometry.
 *
 * ## Threading
 *
 * Every `MaterialInstance` here is allocated and destroyed by [rememberStudioMaterial], a
 * `DisposableEffect`-backed composable — so all Filament JNI allocation happens on the main
 * thread and every handle is released when the screen leaves the composition. Parameter
 * pushes ride `LaunchedEffect`, which runs on the composition's own main dispatcher.
 */
@Composable
fun MaterialsDemo(onBack: () -> Unit) {
    // Inspection mode (Android Studio @Preview pane, Roborazzi snapshot tests): bypass the
    // Filament-backed body BEFORE rememberEngine(), which needs .so files LayoutLib lacks.
    if (LocalInspectionMode.current) {
        DemoPreviewPlaceholder(title = "Materials", onBack = onBack)
        return
    }

    var mode by remember {
        mutableStateOf(initialDemoMode(MaterialsMode.entries, MaterialsMode.Gallery))
    }
    when (mode) {
        // One call site for both: Gallery and Inspect are two framings of the same scene, and
        // sharing the composable means the engine, the environment and the nine material
        // instances survive the toggle. Switching modes is then a camera change, not a
        // teardown — no reload, no black frame, and a slider tweak is still there when the
        // user comes back to the wall.
        MaterialsMode.Gallery, MaterialsMode.Inspect -> StudioSection(onBack, mode) { mode = it }
        // Occlusion gets its own engine on purpose: it is a different scene with a different
        // camera and a loaded GLB, and giving it a separate `rememberEngine()` means leaving
        // the tab tears its resources down completely.
        MaterialsMode.Occlusion -> OcclusionSection(onBack, mode) { mode = it }
    }
}

/**
 * Declaration order is the segmented-button order and
 * [io.github.sceneview.demo.DeepLinkRouter.ALIAS_INITIAL_TAB] indexes into it: the retired
 * `texture-streaming` id resolves to 1 and `occlusion-material` to 2. Append, never reorder.
 *
 * `texture-streaming` landing on **Inspect** is not a coincidence kept for the link's sake —
 * Inspect *is* the runtime-swap section. Its sliders and its picker rewrite the parameters of
 * a `MaterialInstance` that is already bound to a live renderable, which is the thing that
 * demo existed to show, minus the sphere it showed it on.
 */
private enum class MaterialsMode(@StringRes val labelRes: Int) {
    Gallery(R.string.demo_materials_mode_gallery),
    Inspect(R.string.demo_materials_mode_inspect),
    Occlusion(R.string.demo_materials_mode_occlusion),
}

@Composable
private fun ModeSelector(
    current: MaterialsMode,
    onModeChange: (MaterialsMode) -> Unit,
) {
    val modes = MaterialsMode.entries
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        modes.forEachIndexed { index, m ->
            SegmentedButton(
                selected = m == current,
                onClick = { onModeChange(m) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                label = { Text(stringResource(m.labelRes)) },
            )
        }
    }
    Spacer(modifier = Modifier.height(SceneViewTokens.Space.sm))
}

// ─── Gallery + Inspect ───────────────────────────────────────────────────────────────────

@Composable
private fun StudioSection(
    onBack: () -> Unit,
    mode: MaterialsMode,
    onModeChange: (MaterialsMode) -> Unit,
) {
    val inspecting = mode == MaterialsMode.Inspect
    val library = MaterialStudio.library

    // Bumped by the sheet's Reset. It is a `remember` key for the three slider states and a
    // `LaunchedEffect` key for the re-push below, so one tap puts every material back to its
    // declared values — including the ones the user tweaked and then navigated away from.
    var resetTick by remember { mutableIntStateOf(0) }

    var selectedIndex by remember { mutableIntStateOf(MaterialStudio.DEFAULT_INDEX) }
    var environmentIndex by remember { mutableIntStateOf(MaterialStudio.DEFAULT_ENVIRONMENT_INDEX) }
    var compare by remember { mutableStateOf(true) }
    var animating by remember { mutableStateOf(true) }

    val selected = library[selectedIndex]

    // Live parameter overrides. Re-seeded from the material whenever the selection changes,
    // so the sliders always open on the values the ball is actually wearing.
    var metallic by remember(selectedIndex, resetTick) { mutableFloatStateOf(selected.metallic) }
    var roughness by remember(selectedIndex, resetTick) { mutableFloatStateOf(selected.roughness) }
    var traitAmount by remember(selectedIndex, resetTick) {
        mutableFloatStateOf(selected.traitAmount)
    }

    val engine = rememberEngine()
    val materialLoader = rememberMaterialLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)

    val environmentOption = MaterialStudio.environments[environmentIndex]
    // The skybox is DRAWN here, unlike the demo this replaces. See
    // `MaterialStudio.environments` for why that reverses #2874 without reopening it: a
    // material demo that hides the environment is asking the viewer to take the reflections
    // on faith, and the reproducibility problem #2874 hit was the 360° orbit, which this
    // screen no longer has in Gallery and pins in QA mode everywhere.
    val hdrEnvironment = rememberHDREnvironment(
        environmentLoader,
        environmentOption.assetPath,
        createSkybox = true,
    )
    // Neutral default while the HDR decodes and prefilters — without it the first frames of
    // an environment change are black, which reads as a crash rather than as a load.
    val neutralEnvironment = rememberEnvironment(environmentLoader)
    val environment = hdrEnvironment ?: neutralEnvironment

    // One MaterialInstance per library entry, allocated once for the life of the screen and
    // shared by the wall and the hero. That sharing is the point rather than an economy: the
    // slider moves *the material*, so the ball in Inspect and the same ball in the Gallery
    // change together, which is what "a MaterialInstance is bound to many renderables"
    // actually looks like.
    val materials = rememberStudioMaterials(materialLoader)
    val instances = library.map { rememberStudioMaterial(materialLoader, materials, it) }
    // Keep the base fixed while the right-hand preset responds to its sliders.
    val base = selected.copy(
        id = "base-${selected.id}",
        metallic = if (selected.trait == MaterialTrait.None) 0f else selected.metallic,
        roughness = if (selected.trait == MaterialTrait.None) 0.6f else selected.roughness,
        reflectance = if (selected.trait == MaterialTrait.None) 0.5f else selected.reflectance,
        trait = MaterialTrait.None,
        traitAmount = 0f,
    )
    val baseInstance = rememberStudioMaterial(materialLoader, materials, base)
    val view = rememberView(engine)
    val comparisonCamera = rememberCameraNode(engine)
    var labelFrame by remember { mutableIntStateOf(0) }

    // ── Tap-to-focus (#3609) ─────────────────────────────────────────────────────────────
    //
    // Thomas's in-app note on 4.35.0: a tap on a sphere teleported to Inspect. The jump is
    // now an eased dolly — the Gallery camera flies from the wall onto the picked sphere and
    // only then hands over to Inspect, so the eye keeps track of which ball it followed.
    //
    // `focusIndex` is the picked sphere for the whole flight *and* for the flight back: it is
    // cleared only once the ease-out finishes, which is what lets the reverse leg aim at the
    // ball the user left rather than snapping through the origin.
    var focusIndex by remember { mutableStateOf<Int?>(null) }
    val focusZoom = remember { Animatable(0f) }
    val focusing = focusIndex != null
    val focusScope = rememberCoroutineScope()
    val haptic = rememberHapticFeedback()
    val wallPositions = remember { MaterialStudio.wallPositions() }

    // Push the live overrides onto the selected instance. Keyed on the values rather than on
    // `instances` — the map above produces a new List every recomposition, so keying on it
    // would restart the effect on every frame of a drag.
    LaunchedEffect(selectedIndex, metallic, roughness, traitAmount) {
        instances[selectedIndex].push(selected, metallic, roughness, traitAmount)
    }
    // Reset: re-push every material's declared values, not just the selected one.
    LaunchedEffect(resetTick) {
        if (resetTick > 0) {
            library.forEachIndexed { index, material -> instances[index].push(material) }
        }
    }

    // ── Camera ───────────────────────────────────────────────────────────────────────────
    //
    // Two manipulators, both built unconditionally (a composable cannot be called from one
    // branch of an `if`), and the mode picks which one the view gets.

    // Gallery: a flat wall cannot be orbited — a quarter turn shows the spheres edge-on and a
    // half turn shows the back of the grid. The phase drives a bounded cosine sweep instead.
    val sweepPhase = remember { mutableFloatStateOf(MaterialStudio.STATIC_SWEEP_PHASE) }
    // Two ways to stop the sweep, and they end differently. `sweepPinned` — the pause button
    // or QA mode — also returns the phase to its canonical value, so the wall is framed the
    // same way every time it is stilled. A focus flight (#3609) merely *suspends* it: a yaw
    // still travelling under the dolly drags the picked sphere out of frame, and resetting
    // the phase mid-flight would snap it there in one frame.
    val sweepPinned = !animating || DemoSettings.qaMode
    val sweepStopped = inspecting || sweepPinned || focusing
    LifecycleAwareLaunchedEffect(animating, inspecting, focusing, DemoSettings.qaMode) {
        if (sweepStopped) {
            if (sweepPinned && !focusing) {
                sweepPhase.floatValue = MaterialStudio.STATIC_SWEEP_PHASE
            }
            return@LifecycleAwareLaunchedEffect
        }
        var lastNanos = 0L
        while (true) {
            withFrameNanos { nanos ->
                if (lastNanos != 0L) {
                    val advance =
                        (nanos - lastNanos) / (MaterialStudio.SWEEP_PERIOD_MILLIS * 1_000_000.0)
                    sweepPhase.floatValue = ((sweepPhase.floatValue + advance) % 1.0).toFloat()
                }
                lastNanos = nanos
            }
        }
    }

    val galleryRadius = rememberFitOrbitRadius(
        extentX = MaterialStudio.wallExtentX(),
        extentY = MaterialStudio.wallExtentY(),
        extentZ = 2f * MaterialStudio.BALL_RADIUS,
        // The camera sweeps but never turns broadside, so the fit does not have to reserve
        // room for a rotation that cannot happen.
        azimuthInvariant = false,
        fill = GALLERY_FILL,
    )
    val heroExtent = if (compare) {
        2f * MaterialStudio.COMPARE_OFFSET + 2f * MaterialStudio.COMPARE_RADIUS
    } else {
        2f * MaterialStudio.HERO_RADIUS
    }
    val heroDepth = if (compare) {
        2f * MaterialStudio.COMPARE_RADIUS
    } else {
        2f * MaterialStudio.HERO_RADIUS
    }
    val heroRadius = rememberFitOrbitRadius(
        extentX = heroExtent,
        extentY = heroDepth,
        extentZ = heroDepth,
        fill = HERO_FILL,
    )
    val heroManipulator = rememberHeroOrbitCameraManipulator(
        trigger = inspecting && animating,
        radius = heroRadius,
        yHeight = 0.12f,
        durationMillis = MaterialStudio.ORBIT_PERIOD_MILLIS,
        staticYaw = MaterialStudio.STATIC_ORBIT_YAW,
    )

    // Where the tap-to-focus dolly stops. Not an arbitrary "close enough" distance: it is the
    // distance at which a wall sphere covers the same fraction of the frame as the ball
    // Inspect is about to draw, so the hand-over from this camera to Inspect's changes the
    // subject's size by nothing. Apparent size is radius / distance, hence the ratio.
    val inspectBallRadius =
        if (compare) MaterialStudio.COMPARE_RADIUS else MaterialStudio.HERO_RADIUS
    val focusRadius = (heroRadius * MaterialStudio.BALL_RADIUS / inspectBallRadius)
        // Never let the eye reach into the sphere it is flying at.
        .coerceAtLeast(3f * MaterialStudio.BALL_RADIUS)
    // Read by the manipulator's providers on the render thread, so `compare` flipping while a
    // flight is in the air retargets it instead of stranding it at a stale distance.
    val focusRadiusState = rememberUpdatedState(focusRadius)

    val galleryManipulator = remember(galleryRadius) {
        HeroOrbitCameraManipulator(
            yawProvider = { MaterialStudio.sweepYaw(sweepPhase.floatValue) },
            radius = galleryRadius,
            yHeight = 0f,
            target = Position(0f, 0f, 0f),
            resumeAfterMillis = 4_000L,
            // The dolly is expressed as two per-frame reads rather than as a rebuilt
            // manipulator: rebuilding one mid-flight would drop the user-control fallback and
            // the camera would jump.
            radiusProvider = {
                val t = focusZoom.value
                galleryRadius + (focusRadiusState.value - galleryRadius) * t
            },
            targetProvider = {
                val t = focusZoom.value
                val anchor = focusIndex?.let { wallPositions[it] } ?: Position(0f, 0f, 0f)
                Position(anchor.x * t, anchor.y * t, anchor.z * t)
            },
        )
    }

    /**
     * Fly onto [index], then open Inspect on it. In QA mode the flight is instantaneous so
     * the screenshot harness never captures a half-travelled camera.
     */
    fun focusOn(index: Int) {
        focusIndex = index
        haptic.selection()
        // A drag earlier in the session leaves the manipulator in user control, where the two
        // providers above are ignored — without this the dolly would simply not play.
        galleryManipulator.resumeAuto()
        focusScope.launch {
            focusZoom.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = if (DemoSettings.qaMode) 0 else FOCUS_FLIGHT_MILLIS,
                    easing = FastOutSlowInEasing,
                ),
            )
            selectedIndex = index
            onModeChange(MaterialsMode.Inspect)
        }
    }

    // The flight back. Leaving Inspect — by the dock's Gallery button or by the segmented
    // control — eases the wall back in from wherever the camera had landed, which is the
    // "tap again to go back out" half of #3609.
    LaunchedEffect(inspecting) {
        if (!inspecting && focusZoom.value > 0f) {
            focusZoom.animateTo(
                targetValue = 0f,
                animationSpec = tween(
                    durationMillis = if (DemoSettings.qaMode) 0 else FOCUS_FLIGHT_MILLIS,
                    easing = FastOutSlowInEasing,
                ),
            )
            focusIndex = null
        }
    }

    // Re-frame the Compare pair on every entry into it, not just when the fitted radius
    // changes. With `compare` on there is no manipulator at all, so the camera node simply
    // keeps the pose the *gallery* manipulator last wrote — which since #3609 is the dolly's
    // landing pose, parked off-centre on the sphere that was tapped, leaving one of the two
    // balls out of frame. Keying on `inspecting` and `compare` puts the pair back on the axis
    // the moment the hand-over happens.
    LaunchedEffect(heroRadius, inspecting, compare) {
        comparisonCamera.position = Position(0f, 0f, heroRadius)
        comparisonCamera.lookAt(Position(0f))
    }
    val firstFrame = rememberFirstFrameState()

    // A tap on a gallery sphere flies the camera onto it and then moves to Inspect.
    // `Node.name` carries the material id — the picker hands back the picked Node, not an
    // index, and matching on the name is what keeps that mapping readable when the wall order
    // changes. Guarded on `focusing` so a second tap during the flight cannot start a second
    // one; the camera is already travelling and a re-aim mid-flight reads as a glitch.
    val gestureListener = rememberOnGestureListener(
        onSingleTapUp = { _, node ->
            if (!inspecting && !focusing) {
                val tapped = library.indexOfFirst { it.id == node?.name }
                if (tapped >= 0) focusOn(tapped)
            }
        },
    )

    DemoScaffold(
        title = stringResource(R.string.demo_materials_title),
        onBack = onBack,
        firstFrameRendered = firstFrame.rendered,
        loadingLabel = stringResource(R.string.demo_materials_loading),
        peekHeader = if (inspecting) {
            stringResource(selected.nameRes)
        } else {
            stringResource(R.string.demo_materials_gallery_hint)
        },
        onResetSettings = {
            selectedIndex = MaterialStudio.DEFAULT_INDEX
            environmentIndex = MaterialStudio.DEFAULT_ENVIRONMENT_INDEX
            compare = true
            animating = true
            resetTick++
        },
        dock = buildList {
            add(
                DockItem(
                    // The caption names the destination, the way a button does: from the
                    // wall it reads "Inspect", from a single ball it reads "Gallery". It is
                    // the VISIBLE one-word text (`DESIGN.md`, "Floating Dock"); `label` is
                    // the accessible name and carries the full phrase.
                    icon = if (inspecting) Icons.Filled.GridView else Icons.Filled.Lens,
                    caption = stringResource(
                        if (inspecting) {
                            R.string.demo_materials_mode_gallery
                        } else {
                            R.string.demo_materials_mode_inspect
                        },
                    ),
                    label = stringResource(
                        if (inspecting) {
                            R.string.demo_materials_back_gallery
                        } else {
                            R.string.demo_materials_inspect_preset
                        },
                    ),
                    onClick = {
                        onModeChange(
                            if (inspecting) MaterialsMode.Gallery else MaterialsMode.Inspect
                        )
                    },
                )
            )
            if (inspecting) {
                add(
                    DockItem(
                        icon = Icons.Filled.Compare,
                        caption = stringResource(
                            if (compare) R.string.demo_materials_single else R.string.demo_materials_compare,
                        ),
                        label = stringResource(
                            if (compare) {
                                R.string.demo_materials_hide_base
                            } else {
                                R.string.demo_materials_show_base
                            },
                        ),
                        onClick = { compare = !compare },
                        selected = compare,
                    )
                )
            }
            if (!inspecting || !compare) add(
                DockItem(
                    icon = if (animating) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    caption = stringResource(R.string.demo_materials_animate),
                    label = stringResource(
                        if (animating) {
                            R.string.demo_materials_pause_camera
                        } else {
                            R.string.demo_materials_animate_camera
                        },
                    ),
                    onClick = { animating = !animating },
                    selected = animating,
                )
            )
        },
        controls = {
            ModeSelector(mode, onModeChange)

            // The wall does not look interactive — nine spheres read as a picture (#3609).
            // The sheet says so once, in Gallery only, where it is true.
            if (!inspecting) {
                Text(
                    text = stringResource(R.string.demo_materials_tap_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(SceneViewTokens.Space.md))
            }

            Text(stringResource(R.string.demo_materials_picker_label), style = MaterialTheme.typography.labelLarge)
            Spacer(modifier = Modifier.height(SceneViewTokens.Space.sm))
            // A LazyRow rather than a scrolling Row, for the scroll state: nine chips are
            // three screens wide, and the selection can change from the scene (a tap on a
            // sphere) or from Reset, not just from a tap on the row itself. Without the
            // effect below, opening Inspect on the sixth material shows a picker parked on
            // the first three chips with nothing visibly selected.
            val pickerState = rememberLazyListState()
            LaunchedEffect(selectedIndex) { pickerState.animateScrollToItem(selectedIndex) }
            LazyRow(
                state = pickerState,
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm),
            ) {
                itemsIndexed(library, key = { _, material -> material.id }) { index, material ->
                    FilterChip(
                        selected = index == selectedIndex,
                        onClick = {
                            if (index != selectedIndex) {
                                selectedIndex = index
                            }
                        },
                        // A swatch, not a colour block: the ball is a sphere under a key
                        // light, so the chip shows a sphere under a key light. A flat square
                        // of a metal's reflectance value is a muddy brown and tells the user
                        // nothing about the material it stands for.
                        leadingIcon = { MaterialSwatch(material) },
                        label = { Text(stringResource(material.nameRes)) },
                    )
                }
            }
            Spacer(modifier = Modifier.height(SceneViewTokens.Space.sm))
            Text(
                text = stringResource(selected.explainerRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(SceneViewTokens.Space.md))

            LabeledSlider(
                label = stringResource(R.string.demo_materials_metallic),
                value = metallic,
                onValueChange = { metallic = it },
                valueRange = 0f..1f,
            )
            Spacer(modifier = Modifier.height(SceneViewTokens.Space.sm))
            LabeledSlider(
                label = stringResource(R.string.demo_materials_roughness),
                value = roughness,
                onValueChange = { roughness = it },
                valueRange = 0f..1f,
            )
            // The third slider is the material's own extension, and only materials that have
            // one get it. A permanently-disabled "Clear coat" track under a gold ball would
            // be four extra pixels of chrome saying "not applicable".
            if (selected.trait != MaterialTrait.None) {
                Spacer(modifier = Modifier.height(SceneViewTokens.Space.sm))
                LabeledSlider(
                    label = stringResource(when (selected.trait) {
                        MaterialTrait.ClearCoat -> R.string.demo_materials_clearcoat
                        MaterialTrait.Sheen -> R.string.demo_materials_sheen
                        MaterialTrait.Transmission -> R.string.demo_materials_transmission
                        else -> R.string.demo_materials_emissive
                    }),
                    value = traitAmount,
                    onValueChange = { traitAmount = it },
                    valueRange = 0f..traitSliderMax(selected.trait),
                )

            }

            Spacer(modifier = Modifier.height(SceneViewTokens.Space.md))

            Text(stringResource(R.string.demo_materials_environment_label), style = MaterialTheme.typography.labelLarge)
            Spacer(modifier = Modifier.height(SceneViewTokens.Space.sm))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm),
            ) {
                MaterialStudio.environments.forEachIndexed { index, _ ->
                    FilterChip(
                        selected = index == environmentIndex,
                        onClick = { environmentIndex = index },
                        label = { Text(stringResource(listOf(
                            R.string.demo_materials_env_studio, R.string.demo_materials_env_interior,
                            R.string.demo_materials_env_sunset, R.string.demo_materials_env_night,
                        )[index])) },
                    )
                }
            }
        },
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            SceneView(
                modifier = Modifier.fillMaxSize(),
                view = view,
                cameraNode = comparisonCamera,
                onFrame = { nanos -> firstFrame.onFrame(nanos); labelFrame++ },
                engine = engine,
                materialLoader = materialLoader,
                environmentLoader = environmentLoader,
                environment = environment,
                cameraManipulator = when {
                    inspecting && compare -> null
                    inspecting -> heroManipulator
                    else -> galleryManipulator
                },
                onGestureListener = gestureListener,
                // The wall's positions are the layout; letting the union bounding box
                // re-centre the scene would move them, and the Compare pair's symmetry about
                // the origin is exactly what makes the two balls read as a pair.
                autoCenterContent = false,
            ) {
                if (inspecting) {
                    if (compare) {
                        SphereNode(
                            radius = MaterialStudio.COMPARE_RADIUS,
                            stacks = MaterialStudio.BALL_STACKS,
                            slices = MaterialStudio.BALL_SLICES,
                            materialInstance = baseInstance,
                            position = Position(-MaterialStudio.COMPARE_OFFSET, 0f, 0f),
                        )
                        SphereNode(
                            radius = MaterialStudio.COMPARE_RADIUS,
                            stacks = MaterialStudio.BALL_STACKS,
                            slices = MaterialStudio.BALL_SLICES,
                            materialInstance = instances[selectedIndex],
                            position = Position(MaterialStudio.COMPARE_OFFSET, 0f, 0f),
                        )
                    } else {
                        SphereNode(
                            radius = MaterialStudio.HERO_RADIUS,
                            stacks = MaterialStudio.BALL_STACKS,
                            slices = MaterialStudio.BALL_SLICES,
                            materialInstance = instances[selectedIndex],
                        )
                    }
                } else {
                    val positions = MaterialStudio.wallPositions()
                    library.forEachIndexed { index, material ->
                        key(material.id) {
                            SphereNode(
                                radius = MaterialStudio.BALL_RADIUS,
                                stacks = MaterialStudio.BALL_STACKS,
                                slices = MaterialStudio.BALL_SLICES,
                                materialInstance = instances[index],
                                position = positions[index],
                                // Picked back out by name in `onSingleTapUp` above.
                                apply = { name = material.id },
                            )
                        }
                    }
                }
            }
            // Project ordinary Compose labels from the actual view, so names follow the sweep.
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val density = LocalDensity.current
                val captionWidth = if (inspecting) maxWidth / 2 else maxWidth * GALLERY_FILL / MaterialStudio.COLUMNS
                val halfWidthPx = with(density) { captionWidth.toPx() / 2f }
                val gapPx = with(density) { SceneViewTokens.Space.xs.toPx() }
                val positions = if (!inspecting) MaterialStudio.wallPositions().map {
                    Position(it.x, it.y - MaterialStudio.BALL_RADIUS, it.z)
                } else if (compare) listOf(
                    Position(-MaterialStudio.COMPARE_OFFSET, -MaterialStudio.COMPARE_RADIUS, 0f),
                    Position(MaterialStudio.COMPARE_OFFSET, -MaterialStudio.COMPARE_RADIUS, 0f),
                ) else listOf(Position(0f, -MaterialStudio.HERO_RADIUS, 0f))
                positions.forEachIndexed { index, anchor ->
                    val caption = if (!inspecting) stringResource(library[index].nameRes)
                    else if (compare && index == 0) stringResource(
                        if (selected.trait == MaterialTrait.None) R.string.demo_materials_base_matte
                        else R.string.demo_materials_base_layer
                    ) else stringResource(selected.nameRes)
                    // #3609: the labels carry NO pointer-input modifier, on purpose. They
                    // used to be `clickable`, and a Compose node that takes pointer input
                    // wins the hit test outright — the `SceneView` sibling underneath was
                    // never even offered the event, so a drag that happened to start on a
                    // name did nothing at all while the same drag two pixels lower orbited
                    // the camera. Non-consumption is not enough to fix that: hit testing
                    // stops at the topmost node that accepts pointers, whatever it then does
                    // with them. So the labels are pure decoration and the spheres behind
                    // them stay both draggable and tappable.
                    val focused = !inspecting && focusIndex == index
                    Text(
                        caption,
                        modifier = Modifier.offset {
                            @Suppress("UNUSED_EXPRESSION")
                            labelFrame
                            val point = view.worldToScreen(anchor)
                            IntOffset(((point?.x ?: -view.viewport.width.toFloat()) - halfWidthPx).toInt(),
                                ((point?.y ?: -view.viewport.height.toFloat()) + gapPx).toInt())
                        }.width(captionWidth)
                            .padding(horizontal = SceneViewTokens.Space.xs)
                            .background(
                                if (focused) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surface
                                },
                                MaterialTheme.shapes.small,
                            )
                            .padding(SceneViewTokens.Space.xs),
                        style = if (inspecting) {
                            MaterialTheme.typography.titleSmall
                        } else {
                            MaterialTheme.typography.labelSmall
                        },
                        color = if (focused) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        textAlign = TextAlign.Center,
                    )
                }
            }
            // The scene is procedural, so there is nothing to decode — but the environment's
            // IBL prefilter is real work, and until it lands the spheres have nothing to
            // reflect. The cover follows the environment, not a model.
            LoadingScrim(
                loading = hdrEnvironment == null,
                label = stringResource(R.string.demo_materials_loading),
            )
        }
    }
}

/**
 * Duration of the tap-to-focus dolly, in and out (#3609).
 *
 * Long enough to read as travel rather than as a cut — under ~300 ms the eye registers a jump
 * — and short enough that it never feels like a wait before Inspect opens.
 */
private const val FOCUS_FLIGHT_MILLIS: Int = 520

/** Fraction of the frame the gallery wall spans. Leaves the chrome bands their own air. */
private const val GALLERY_FILL: Float = 0.88f

/** Fraction of the frame the Inspect hero spans — tighter, because there is one subject. */
private const val HERO_FILL: Float = 0.8f

/**
 * Upper bound of the trait slider.
 *
 * Every factor is a `0..1` weight except emissive strength, which is a multiplier on the
 * emitted colour and only starts to read above 1.
 */
private fun traitSliderMax(trait: MaterialTrait): Float =
    if (trait == MaterialTrait.Emissive) 8f else 1f

// ─── The material instances ──────────────────────────────────────────────────────────────

/**
 * The two materials the studio renders with, compiled from
 * `samples/android-demo/src/main/materials/` by `tools/GenerateFilamat.sh`.
 *
 * ## Why two, and why not the gltfio ubershader
 *
 * The first cut of this screen reached for Filament's gltfio **ubershader**, on the theory
 * that the capability was already in the AAR and only needed calling from Kotlin. It renders
 * nine black spheres. The ubershader declares the full glTF vertex layout as *required* —
 * `position | tangents | color | uv0 | uv1`, `0x1f` — and SceneView's procedural `SphereNode`
 * supplies `0x1b`… `0xb`: position, tangents, uv0. Filament logs
 * `missing required attributes (0x1f), declared=0xb` once per sphere and shades them with a
 * zero vertex colour, which multiplies `baseColorFactor` to black. Metals gave it away first:
 * a metal has no diffuse term, so a black base colour turns chrome, gold, copper and
 * aluminium into four identical black balls. Binding dummy white textures to the unbound
 * samplers — the other half of what gltfio's `ResourceLoader` does — does not help, because
 * the missing attribute is a *vertex* attribute, not a sampler.
 *
 * So the demo ships its own materials, which require only what the primitive actually has.
 * `studio_pbr` covers metallic-roughness plus clear coat, sheen and emission in one shader;
 * `studio_glass` exists separately only because Filament's sheen lobe and its refraction path
 * cannot coexist in a single material.
 */
private class StudioMaterials(val pbr: Material, val glass: Material)

/** Loads both material blobs once for the life of the screen. */
@Composable
private fun rememberStudioMaterials(materialLoader: MaterialLoader): StudioMaterials =
    remember(materialLoader) {
        StudioMaterials(
            pbr = materialLoader.createMaterial("materials/studio_pbr.filamat"),
            glass = materialLoader.createMaterial("materials/studio_glass.filamat"),
        )
    }

/**
 * Allocates the `MaterialInstance` for [material] and ties it to the composition — the
 * `rememberMaterialInstance` contract (#937).
 *
 * Keyed on the material's id rather than on the whole value: the sliders rewrite parameters on
 * the instance, and re-keying on the parameters would destroy and rebuild a JNI handle on
 * every frame of a drag.
 */
@Composable
private fun rememberStudioMaterial(
    materialLoader: MaterialLoader,
    materials: StudioMaterials,
    material: StudioMaterial,
): MaterialInstance {
    val instance = remember(materialLoader, materials, material.id) {
        materialLoader.createInstance(
            if (material.trait == MaterialTrait.Transmission) materials.glass else materials.pbr
        )
    }
    DisposableEffect(instance) {
        instance.push(material)
        onDispose { materialLoader.destroyMaterialInstance(instance) }
    }
    return instance
}

/**
 * Writes [material]'s parameters onto the instance. Main thread only — every caller is a
 * `DisposableEffect` or a `LaunchedEffect` on the composition's dispatcher.
 *
 * The three optional layers of `studio_pbr` are **explicitly zeroed** for the materials that
 * do not use them. One shader serves eight of the nine spheres, so "this material has no clear
 * coat" has to be written as `clearCoat = 0`; an unwritten uniform is not a guaranteed zero.
 */
private fun MaterialInstance.push(
    material: StudioMaterial,
    metallic: Float = material.metallic,
    roughness: Float = material.roughness,
    traitAmount: Float = material.traitAmount,
) {
    val base = colorOf(material.color)

    if (material.trait == MaterialTrait.Transmission) {
        setParameter("color", base.x, base.y, base.z, 1f)
        setParameter("roughness", roughness)
        setParameter("reflectance", material.reflectance)
        setParameter("transmission", traitAmount)
        setParameter("ior", material.ior)
        return
    }

    setParameter("color", base.x, base.y, base.z, 1f)
    setParameter("metallic", metallic)
    setParameter("roughness", roughness)
    setParameter("reflectance", material.reflectance)

    val coat = if (material.trait == MaterialTrait.ClearCoat) traitAmount else 0f
    setParameter("clearCoat", coat)
    setParameter("clearCoatRoughness", material.traitRoughness)

    val tint = colorOf(material.traitColor)
    if (material.trait == MaterialTrait.Sheen) {
        setParameter("sheenColor", tint.x * traitAmount, tint.y * traitAmount, tint.z * traitAmount)
    } else {
        setParameter("sheenColor", 0f, 0f, 0f)
    }
    setParameter("sheenRoughness", material.traitRoughness)

    // Emissive strength is a multiplier on the emitted colour, folded in here rather than
    // carried as a second uniform: the shader only ever needs the product.
    if (material.trait == MaterialTrait.Emissive) {
        setParameter("emissive", tint.x * traitAmount, tint.y * traitAmount, tint.z * traitAmount)
    } else {
        setParameter("emissive", 0f, 0f, 0f)
    }
}

// ─── Swatch ──────────────────────────────────────────────────────────────────────────────

/**
 * The little sphere in front of a picker chip.
 *
 * Drawn rather than rendered: a tenth Filament view per chip would cost more than the scene
 * it is labelling. A radial gradient from an off-centre highlight down to a shaded terminator
 * is enough shape for the eye to read "ball", and the two stops come from the material itself
 * — the highlight is brighter and less saturated for a smooth surface, flatter for a rough
 * one, so chrome and brushed aluminium do not get the same dot.
 */
@Composable
private fun MaterialSwatch(material: StudioMaterial) {
    val base = material.color
    // Roughness spreads the highlight and takes its peak down: a mirror keeps a small, near
    // white hotspot, a rough surface barely lifts off its own colour.
    val highlight = lerpColor(Color.White, base, 0.2f + 0.65f * material.roughness)
    val shadow = lerpColor(base, Color.Black, 0.55f)
    Canvas(modifier = Modifier.size(SWATCH_SIZE)) {
        val radius = size.minDimension / 2f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(highlight, base, shadow),
                center = Offset(size.width * 0.35f, size.height * 0.32f),
                radius = radius * 1.55f,
            ),
            radius = radius,
        )
    }
}

/** Chip leading-icon box, the M3 default — a swatch that is not 18 dp misaligns the label. */
private val SWATCH_SIZE = SceneViewTokens.Space.md

/** Component-wise mix, so the swatch needs no `androidx.compose.ui.graphics.lerp` import. */
private fun lerpColor(from: Color, to: Color, amount: Float): Color {
    val t = amount.coerceIn(0f, 1f)
    return Color(
        red = from.red + (to.red - from.red) * t,
        green = from.green + (to.green - from.green) * t,
        blue = from.blue + (to.blue - from.blue) * t,
        alpha = 1f,
    )
}

// ─── Occlusion ───────────────────────────────────────────────────────────────────────────

/**
 * `MaterialLoader.createOcclusionInstance()` — the SceneView equivalent of RealityKit's
 * `OcclusionMaterial` and Sceneform's `MaterialFactory.makeOcclusionMaterial(...)`. Carried
 * over from the retired `occlusion-material` demo (#1776), which #2239 Batch 4 folded in
 * here, with its framing unchanged (#2304).
 *
 * A helmet sits at the origin under a static camera. A plane stands between the two at
 * `z = +0.7 m` with its edge on the helmet's centre line.
 *
 * - **Occluder visible ON** — the plane wears a tinted unlit material, so the user can see
 *   where it is. It hides the half of the helmet behind it because it is painted over it.
 * - **Occluder visible OFF** — the same plane wears the occlusion material. It paints no
 *   pixels at all, yet still writes depth, so the half of the helmet behind it fails the
 *   depth test and disappears into the background. A sharp vertical cut, and nothing on top
 *   of it. That is the whole feature.
 *
 * The section is non-AR, so the comparison reproduces on any device. For occluding virtual
 * content against the **live camera depth image** instead of a static mesh, use
 * [`ARCameraStream.isDepthOcclusionEnabled`][io.github.sceneview.ar.camera.ARCameraStream].
 */
@Composable
private fun OcclusionSection(
    onBack: () -> Unit,
    mode: MaterialsMode,
    onModeChange: (MaterialsMode) -> Unit,
) {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)

    // Hoisted so the helmet loads once — re-toggling the occluder never re-parses the GLB.
    val helmetInstance = rememberModelInstance(modelLoader, "models/khronos_damaged_helmet.glb")

    val occlusionMaterial = rememberOcclusionMaterialInstance(materialLoader)
    // Ground truth, not the feature: a translucent slate plate that shows WHERE the plane is.
    val debugVisibleMaterial = rememberUnlitMaterialInstance(
        materialLoader,
        Color(0.4f, 0.4f, 0.45f, 1f),
    )

    // Default `false` so the section opens on the actual feature — a helmet visibly cut by an
    // invisible plane — and the ground truth is one tap away, not the other way round.
    var occluderVisible by remember { mutableStateOf(false) }

    val firstFrame = rememberFirstFrameState()

    DemoScaffold(
        title = stringResource(R.string.demo_materials_title),
        onBack = onBack,
        firstFrameRendered = firstFrame.rendered,
        loadingLabel = stringResource(R.string.demo_materials_loading),
        peekHeader = stringResource(
            if (occluderVisible) {
                R.string.demo_occlusion_material_status_visible
            } else {
                R.string.demo_occlusion_material_status_occluding
            }
        ),
        onResetSettings = { occluderVisible = false },
        dock = listOf(
            DockItem(
                icon = Icons.Filled.Compare,
                caption = stringResource(R.string.demo_materials_occluder),
                label = stringResource(R.string.demo_materials_show_occluder),
                onClick = { occluderVisible = !occluderVisible },
                selected = occluderVisible,
            ),
        ),
        controls = {
            ModeSelector(mode, onModeChange)
            // Toggleable on the whole row so tapping the label flips the state and
            // UiAutomator finds a clickable ancestor — the contract the Post-FX switches in
            // LightingLabDemo and the Lines & Paths switches share.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = occluderVisible,
                        onValueChange = { occluderVisible = it },
                    ),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.demo_occlusion_material_toggle),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Switch(checked = occluderVisible, onCheckedChange = null)
            }
            Spacer(modifier = Modifier.height(SceneViewTokens.Space.sm))
            Text(
                text = stringResource(R.string.demo_occlusion_material_explainer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                // Studio IBL, no skybox: the occluded region has to read as *gone*, and it
                // can only do that against a background the hidden half melts into.
                environment = rememberModelDemoEnvironment(environmentLoader),
                // Static camera — the section is about depth ordering at a fixed viewpoint.
                // eye x == target x == 0, so the occluder's edge (world x = 0) projects to
                // the screen centre: a clean vertical cut down the helmet's middle (#2304).
                cameraManipulator = rememberCameraManipulator(
                    orbitHomePosition = Position(0f, 0.2f, 1.4f),
                    targetPosition = Position(0f, 0f, 0f),
                ),
                // The hand-authored helmet + plane positions are meaningful — keep them in
                // world space instead of letting the union bbox auto-centre move them.
                autoCenterContent = false,
            ) {
                helmetInstance?.let { instance ->
                    ModelNode(modelInstance = instance, scaleToUnits = 0.6f)
                }
                PlaneNode(
                    size = Size(x = 1.4f, y = 1.4f, z = 0f),
                    materialInstance =
                        if (occluderVisible) debugVisibleMaterial else occlusionMaterial,
                    position = Position(0.7f, 0f, 0.7f),
                )
            }
            LoadingScrim(
                loading = helmetInstance == null,
                label = stringResource(R.string.demo_materials_loading),
            )
        }
    }
}
