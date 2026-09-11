package io.github.sceneview.demo.demos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import io.github.sceneview.SceneView
import io.github.sceneview.demo.DemoPreviewPlaceholder
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.DemoSettings
import io.github.sceneview.demo.DockItem
import io.github.sceneview.demo.R
import io.github.sceneview.demo.SceneViewColors
import io.github.sceneview.demo.common.rememberModelDemoEnvironment
import io.github.sceneview.demo.demos.internal.CameraRig
import io.github.sceneview.demo.demos.internal.CameraView
import io.github.sceneview.demo.demos.internal.OrbitPose
import io.github.sceneview.demo.demos.internal.RigGesture
import io.github.sceneview.demo.demos.internal.RigSubject
import io.github.sceneview.demo.demos.internal.StudioCameraManipulator
import io.github.sceneview.demo.rememberFirstFrameState
import io.github.sceneview.demo.rememberFitOrbitRadius
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.ui.GlassSurface
import io.github.sceneview.gesture.NodeEditingOverlay
import io.github.sceneview.gesture.rememberNodeEditingFeedback
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Size
import io.github.sceneview.node.ModelNode
import io.github.sceneview.node.Node
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberOnGestureListener
import io.github.sceneview.rememberView
import io.github.sceneview.sample.ui.LabeledSlider
import java.util.Locale
import kotlin.math.roundToInt

/**
 * **Camera & Gestures** — the screen that shows what SceneView's camera can do, on one stage, with
 * one rig.
 *
 * ### What was here before, and why none of it survived (#3500)
 *
 * The previous version was three demos behind a segmented toggle: a manipulator-mode picker
 * (Orbit / Free Flight / Map) with a distance slider, a per-node edit screen with four switches and
 * a slider, and a third mode showing the editing affordances the second mode deliberately drew
 * without. It was an inventory of API surface, not a demonstration: every mode tore down its own
 * engine on a switch, the distance slider rebuilt the Filament `Manipulator` on every step so each
 * change teleported the camera, and the one thing a camera screen exists to convey — that the
 * camera is a place you move through, not a parameter you set — was nowhere on screen.
 *
 * This is a rebuild, not a refactor. One scene, one camera, and every camera capability expressed
 * as something you *do* to it:
 *
 * - **Orbit, pan, zoom — with inertia.** One finger orbits, two pan, a pinch dollies, and a
 *   release *coasts* to a stop. [StudioCameraManipulator] owns the spherical pose so a flick has
 *   an angular velocity to carry, which the stock Filament manipulator cannot express.
 * - **Tap a subject to fly to it.** A tap picks the model under the finger and the camera flies —
 *   eased over `motion-entrance`'s 700 ms — to a framing computed from *that subject's* size.
 *   Double-tap anywhere returns to the whole stage.
 * - **Named views.** Five chips over the scene (Hero / Front / Side / Top / Close) fly to an
 *   angle relative to whatever currently has focus, always by the shortest arc round the subject.
 * - **A cinematic turntable.** The dock's Cinematic item hands the camera to the SDK's own eased
 *   orbit ramp ([io.github.sceneview.cinematicAzimuth]), spinning the framing the user chose
 *   rather than teleporting to a canonical one. A touch takes it back; the next release gives it
 *   up again.
 * - **Object gestures, in the same scene.** The dock's Move item makes the focused subject
 *   editable, so a drag / twist / pinch moves the *object* instead of the camera, with the SDK's
 *   on-model affordances ([NodeEditingOverlay]) drawn over it. The old build spent two of its
 *   three modes on this and still never showed it next to the camera it competes with for the
 *   same gesture.
 * - **A readout of where the camera is.** A glass HUD prints the live azimuth, elevation and
 *   distance, plus the name of the focused subject and a badge naming the gesture *while* it runs.
 *   A camera demo that never tells you where the camera is asks the user to infer it from pixels.
 *
 * ### Why one scene and not one model
 *
 * Half of what is on screen is a statement about *choosing* a subject — tap-to-focus, per-subject
 * framing, presets relative to the focus. None of it exists with a single model centred on the
 * origin. Three subjects standing on a floor is also what makes orbit and pan distinguishable at a
 * glance: orbit swings the floor's perspective, pan slides it. The composition and its framing
 * constants live in [CameraRig].
 *
 * ### Threading
 *
 * Every model is loaded through [rememberModelInstance] (main-thread Filament JNI), and the rig's
 * per-frame integration runs inside `SceneView`'s own frame loop. The HUD reads the rig from a
 * `withFrameNanos` poll and publishes only when a *displayed* value changes, so a 60 Hz camera does
 * not drive 60 recompositions a second.
 */
@Composable
fun CameraAndGesturesDemo(onBack: () -> Unit) {
    // Inspection mode (Android Studio @Preview, Roborazzi): bail out BEFORE rememberEngine(),
    // which needs Filament .so files LayoutLib does not load.
    if (LocalInspectionMode.current) {
        DemoPreviewPlaceholder(
            title = stringResource(R.string.demo_camera_and_gestures_title),
            onBack = onBack,
        )
        return
    }

    var focus by remember { mutableStateOf<RigSubject?>(null) }
    var selectedView by remember { mutableStateOf(CameraView.Hero) }
    var cinematic by remember { mutableStateOf(false) }
    var moveMode by remember { mutableStateOf(false) }
    var sensitivity by remember { mutableFloatStateOf(CameraRig.DEFAULT_SENSITIVITY) }
    var inertia by remember { mutableStateOf(true) }

    val engine = rememberEngine()
    val view = rememberView(engine)
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)

    val instances = RigSubject.entries.associateWith { subject ->
        rememberModelInstance(modelLoader, subject.assetPath)
    }
    val allLoaded = instances.values.all { it != null }

    // The floor is what makes a camera move legible: orbit swings its perspective lines, pan
    // slides them, and a dolly changes how much of it is in frame. A subject alone on a black
    // field gives a drag nothing to move *against*.
    val floorMaterial = remember(materialLoader) {
        materialLoader.createColorInstance(
            SceneViewColors.SurfaceDim,
            metallic = 0f,
            roughness = 0.62f,
        )
    }

    // Auto-fit distance per focus, from the demo's real viewport aspect (#3426). Computed once
    // each, not tuned by hand, so a preset's `distanceScale` means the same thing on every device.
    val sceneFit = rememberFitOrbitRadius(
        extentX = CameraRig.SCENE_EXTENT_X,
        extentY = CameraRig.SCENE_EXTENT_Y,
        extentZ = CameraRig.SCENE_EXTENT_Z,
        elevationDegrees = CameraView.Hero.elevationDegrees,
        fill = CameraRig.STAGE_FILL,
    )
    val subjectFits = RigSubject.entries.associateWith { subject ->
        rememberFitOrbitRadius(
            extentX = subject.extent,
            extentY = subject.extent,
            extentZ = subject.extent,
            elevationDegrees = CameraView.Hero.elevationDegrees,
        )
    }
    val focusTarget = focus?.let { CameraRig.focusTarget(it) } ?: CameraRig.SCENE_TARGET
    val focusFit = focus?.let { subjectFits.getValue(it) } ?: sceneFit

    // Read through refs so the rig never has to be rebuilt: a new manipulator instance would drop
    // the pose, and the whole point of this screen is that the camera is continuous.
    val fitRef = rememberUpdatedState(focusFit)
    val sensitivityRef = rememberUpdatedState(sensitivity)
    val inertiaRef = rememberUpdatedState(inertia)

    val rig = remember {
        StudioCameraManipulator(
            initialPose = CameraRig.poseFor(
                view = CameraView.Hero,
                focusTarget = CameraRig.SCENE_TARGET,
                fitDistance = sceneFit,
            ),
            fitDistance = { fitRef.value },
            sensitivity = { sensitivityRef.value },
            inertiaEnabled = { inertiaRef.value },
        )
    }

    // In QA mode the turntable and the coast are both suppressed by construction (the dock item
    // starts off), so the only thing that could move the camera between two captures is a flight —
    // and a flight lands on a deterministic pose. That is what keeps this screen's golden stable.
    LaunchedEffect(cinematic) { rig.cinematic = cinematic && !DemoSettings.qaMode }

    val readout = rememberRigReadout(rig)

    /** Flies to [target]'s [view], keeping the chips and the HUD in step with the camera. */
    val flyTo: (RigSubject?, CameraView) -> Unit = { subject, cameraView ->
        focus = subject
        selectedView = cameraView
        rig.flyTo(
            CameraRig.poseFor(
                view = cameraView,
                focusTarget = subject?.let { CameraRig.focusTarget(it) } ?: CameraRig.SCENE_TARGET,
                fitDistance = subject?.let { subjectFits.getValue(it) } ?: sceneFit,
                awayFrom = rig.pose.azimuthDegrees,
            )
        )
    }

    // Node → subject, so a tap that lands on one of a model's renderable children still resolves
    // to the subject it belongs to. A plain map, not snapshot state: it is written from the node
    // factory during composition and only ever read from a gesture callback.
    val subjectNodes = remember { mutableMapOf<Node, RigSubject>() }
    // The editable node, as state, because the affordance overlay is a composable that has to
    // recompose when Move mode changes which node is live.
    var editableNode by remember { mutableStateOf<ModelNode?>(null) }
    LaunchedEffect(moveMode, focus, allLoaded) {
        editableNode = if (moveMode) {
            subjectNodes.entries.firstOrNull { it.value == focus }?.key as? ModelNode
        } else {
            null
        }
    }
    // Move mode needs something to move: focusing the stage as a whole leaves it with no subject,
    // so the first subject takes focus rather than the toggle silently doing nothing.
    LaunchedEffect(moveMode) {
        if (moveMode && focus == null) flyTo(RigSubject.Helmet, CameraView.Hero)
    }

    val firstFrame = rememberFirstFrameState()

    val resetAll = {
        cinematic = false
        moveMode = false
        sensitivity = CameraRig.DEFAULT_SENSITIVITY
        inertia = true
        flyTo(null, CameraView.Hero)
    }

    DemoScaffold(
        title = stringResource(R.string.demo_camera_and_gestures_title),
        onBack = onBack,
        firstFrameRendered = firstFrame.rendered,
        loadingLabel = stringResource(R.string.camera_gestures_loading),
        onReset = resetAll,
        dock = listOf(
            DockItem(
                icon = Icons.Filled.CenterFocusStrong,
                label = stringResource(R.string.camera_gestures_action_recenter),
                onClick = { flyTo(null, CameraView.Hero) },
            ),
            DockItem(
                icon = Icons.Filled.Movie,
                label = stringResource(R.string.camera_gestures_action_cinematic),
                onClick = { cinematic = !cinematic },
                selected = cinematic,
            ),
            DockItem(
                icon = Icons.Filled.OpenWith,
                label = stringResource(R.string.camera_gestures_action_move),
                onClick = { moveMode = !moveMode },
                selected = moveMode,
            ),
        ),
        controls = {
            LabeledSlider(
                label = stringResource(R.string.camera_gestures_control_distance),
                value = readout.distance,
                onValueChange = { rig.setDistance(it) },
                valueRange = (focusFit * CameraRig.MIN_DISTANCE_SCALE)..
                    (focusFit * CameraRig.MAX_DISTANCE_SCALE),
                decimals = 2,
                unit = "m",
            )

            Spacer(modifier = Modifier.height(SceneViewTokens.Space.md))

            LabeledSlider(
                label = stringResource(R.string.camera_gestures_control_sensitivity),
                value = sensitivity,
                onValueChange = { sensitivity = it },
                valueRange = CameraRig.MIN_SENSITIVITY..CameraRig.MAX_SENSITIVITY,
                valueText = "${(sensitivity * 100f).roundToInt()}%",
            )

            Spacer(modifier = Modifier.height(SceneViewTokens.Space.sm))

            // Toggleable on the whole row so the label is part of the target and UiAutomator finds
            // one clickable ancestor — the same contract every other demo switch uses.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(value = inertia, onValueChange = { inertia = it }),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.camera_gestures_control_inertia),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = stringResource(R.string.camera_gestures_control_inertia_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = inertia, onCheckedChange = null)
            }
        },
        topOverlay = {
            CameraHud(
                focusLabel = focus?.label
                    ?: stringResource(R.string.camera_gestures_focus_scene),
                readout = readout,
                moveMode = moveMode,
            )
        },
        bottomOverlay = {
            CameraViewChips(
                selected = selectedView,
                onSelect = { flyTo(focus, it) },
            )
        },
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            SceneView(
                modifier = Modifier.fillMaxSize(),
                onFrame = firstFrame.onFrame,
                engine = engine,
                view = view,
                modelLoader = modelLoader,
                materialLoader = materialLoader,
                environmentLoader = environmentLoader,
                environment = rememberModelDemoEnvironment(environmentLoader),
                // The three subjects are placed *as a composition*: re-centring their union on the
                // origin would move the stage every time an async model finished loading, and the
                // per-subject focus targets would then point at nothing.
                autoCenterContent = false,
                cameraManipulator = rig,
                onGestureListener = rememberOnGestureListener(
                    onSingleTapConfirmed = { _, node ->
                        // A tap that lands on a subject flies to it; a tap on the floor or the
                        // void is not an accident to punish — it leaves the camera alone.
                        subjectOf(node, subjectNodes)?.let { flyTo(it, CameraView.Hero) }
                    },
                    // This screen deliberately keeps its own meaning for the double-tap: back
                    // to the whole stage. The SDK's built-in double-tap zoom (#3608) still runs
                    // first — `onDoubleTapCamera` is dispatched before `listener.onDoubleTap` —
                    // and this flight then supersedes it, which is exactly the priority a
                    // consumer callback is supposed to have.
                    onDoubleTap = { _, _ -> flyTo(null, CameraView.Hero) },
                ),
            ) {
                PlaneNode(
                    size = Size(x = FLOOR_SIZE, y = 0f, z = FLOOR_SIZE),
                    normal = Direction(y = 1f),
                    materialInstance = floorMaterial,
                )

                RigSubject.entries.forEach { subject ->
                    instances[subject]?.let { instance ->
                        ModelNode(
                            modelInstance = instance,
                            scaleToUnits = subject.extent,
                            // Bottom-aligned, so `position` is the spot on the floor the subject
                            // stands on and the composition is authored in floor coordinates.
                            centerOrigin = Position(y = -1f),
                            position = Position(
                                x = subject.groundX,
                                y = 0f,
                                z = subject.groundZ,
                            ),
                            rotation = Rotation(y = subject.yawDegrees),
                            isEditable = moveMode && focus == subject,
                            apply = { subjectNodes[this] = subject },
                        )
                    }
                }
            }

            // The SDK's on-model affordances — twist ring, pinch badge, drag shadow — drawn only
            // for the node Move mode has made editable, so the overlay's opt-in nature is visible
            // rather than asserted.
            editableNode?.let { node ->
                NodeEditingOverlay(
                    state = rememberNodeEditingFeedback(node),
                    view = view,
                    modifier = Modifier.fillMaxSize(),
                    selected = true,
                )
            }
        }
    }
}

/**
 * Side of the square floor, in metres.
 *
 * Large enough that its far edge is off-frame at every preset and at every orbit angle: an 8 m
 * slab put a hard horizon line across the upper third with pure black above it, which reads as a
 * broken scene rather than as a studio floor. The environment's image-based lighting does the
 * rest — the plane falls off into the background on its own, so no edge has to be hidden.
 */
private const val FLOOR_SIZE: Float = 90f

/**
 * What the HUD prints, sampled from the rig at most once per displayed frame.
 *
 * Held as its own value class so the poll can compare *displayed* numbers: the camera's azimuth
 * changes continuously, but "34°" does not, and only the latter needs a recomposition.
 */
private data class RigReadout(
    val azimuthDegrees: Int = 0,
    val elevationDegrees: Int = 0,
    val distance: Float = 1f,
    val gesture: RigGesture? = null,
)

/**
 * Samples [rig] every displayed frame and publishes only when a *shown* value changes.
 *
 * The rig's pose is a plain field on purpose (see [StudioCameraManipulator.pose]); this is the one
 * place it crosses into Compose state, and it crosses rounded. Distance is quantised to the
 * centimetre the HUD and the slider both display.
 */
@Composable
private fun rememberRigReadout(rig: StudioCameraManipulator): RigReadout {
    var readout by remember { mutableStateOf(RigReadout(distance = rig.pose.distance)) }
    LaunchedEffect(rig) {
        while (true) {
            withFrameNanos { }
            val pose = rig.pose
            val next = RigReadout(
                azimuthDegrees = CameraRig.normalizeDegrees(pose.azimuthDegrees).roundToInt(),
                elevationDegrees = pose.elevationDegrees.roundToInt(),
                distance = (pose.distance * 100f).roundToInt() / 100f,
                gesture = rig.gesture,
            )
            if (next != readout) readout = next
        }
    }
    return readout
}

/**
 * The camera's own readout: what has focus, where the camera is, and what the hands are doing.
 *
 * Glass over media, so it is theme-independent (`DESIGN.md` → Liquid Glass, Button-glass row):
 * the ground behind it is a rendered scene of arbitrary brightness, not an app surface, and the
 * scaffold already lays a scrim band under this slot.
 */
@Composable
private fun CameraHud(
    focusLabel: String,
    readout: RigReadout,
    moveMode: Boolean,
) {
    GlassSurface(
        modifier = Modifier.padding(horizontal = SceneViewTokens.Space.md),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(SceneViewTokens.Radius.md),
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = SceneViewTokens.Space.md,
                vertical = SceneViewTokens.Space.sm,
            ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = if (moveMode) {
                    stringResource(R.string.camera_gestures_hud_moving, focusLabel)
                } else {
                    focusLabel
                },
                style = SceneViewTokens.Type.caption,
                color = SceneViewTokens.Glass.onGlass,
                textAlign = TextAlign.Center,
            )
            Text(
                // Monospaced-by-padding is not worth a font here: the values are formatted with a
                // sign and a fixed decimal count, so the line does not jitter as the camera moves.
                text = stringResource(
                    R.string.camera_gestures_hud_pose,
                    readout.azimuthDegrees,
                    readout.elevationDegrees,
                    String.format(Locale.US, "%.2f", readout.distance),
                ),
                style = SceneViewTokens.Type.caption,
                color = SceneViewTokens.Glass.onGlassMuted,
                textAlign = TextAlign.Center,
            )
            readout.gesture?.let { gesture ->
                Text(
                    text = gesture.label,
                    style = SceneViewTokens.Type.caption,
                    color = SceneViewTokens.Glass.onGlass,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * The five named views, as a chip row over the scene.
 *
 * On the scene rather than in the settings sheet because they are the demo's primary verb: a
 * camera preset you have to open a sheet to reach is a setting, and this screen's claim is that
 * moving the camera is an action. Five one-word chips are what fits a phone width; the row is why
 * the labels in [CameraView] are single words.
 */
@Composable
private fun CameraViewChips(
    selected: CameraView,
    onSelect: (CameraView) -> Unit,
) {
    Row(
        modifier = Modifier.padding(horizontal = SceneViewTokens.Space.sm),
        horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CameraView.entries.forEach { view ->
            FilterChip(
                selected = view == selected,
                onClick = { onSelect(view) },
                label = { Text(view.label, style = SceneViewTokens.Type.caption) },
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = SceneViewTokens.Glass.surface,
                    labelColor = SceneViewTokens.Glass.onGlass,
                    selectedContainerColor = SceneViewTokens.Glass.onGlass,
                    selectedLabelColor = SceneViewTokens.Stage.background,
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = view == selected,
                    borderColor = SceneViewTokens.Glass.border,
                    selectedBorderColor = SceneViewTokens.Glass.onGlass,
                    borderWidth = SceneViewTokens.Glass.borderWidth,
                    selectedBorderWidth = SceneViewTokens.Glass.borderWidth,
                ),
            )
        }
    }
}

/**
 * Resolves a picked [node] to the subject it belongs to, walking up to the model root.
 *
 * A `ModelNode` exposes one child per glTF renderable, and picking returns the deepest touchable
 * hit — so a tap on the helmet's visor arrives as a child node the demo never registered. Walking
 * `parent` is what makes "tap the object" mean the object rather than one of its meshes.
 */
private fun subjectOf(node: Node?, subjects: Map<Node, RigSubject>): RigSubject? =
    generateSequence(node) { it.parent }
        .firstNotNullOfOrNull { subjects[it] }
