package io.github.sceneview.demo.demos

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.android.filament.utils.Manipulator
import io.github.sceneview.SceneView
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.DemoSettings
import io.github.sceneview.demo.LoadingScrim
import io.github.sceneview.demo.R
import io.github.sceneview.demo.common.Axes3DNode
import io.github.sceneview.demo.common.SceneAction
import io.github.sceneview.demo.common.SceneActionBar
import io.github.sceneview.demo.common.rememberModelDemoEnvironment
import io.github.sceneview.demo.initialDemoMode
import io.github.sceneview.demo.rememberFirstFrameState
import io.github.sceneview.gesture.CameraGestureDetector
import io.github.sceneview.gesture.orbitHomePosition
import io.github.sceneview.gesture.targetPosition
import io.github.sceneview.math.Position
import io.github.sceneview.node.ModelNode
import io.github.sceneview.rememberCameraManipulator
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberOnGestureListener
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Unified "Camera & Gestures" demo — consolidates the retired `camera-controls`
 * and `gesture-editing` demos behind a single segmented-button toggle
 * (#2239 Batch 1).
 *
 * - **Camera Modes** — orbit / free-flight / map manipulator presets with a
 *   distance slider and an on-screen flight pad. (Formerly `camera-controls`.)
 * - **Node Gestures** — per-node drag / twist / pinch on a damaged-helmet
 *   `ModelNode`, with per-axis locks, a sensitivity slider, gesture-mode
 *   indicator, and a live transform readout. (Formerly `gesture-editing`.)
 *
 * Each sub-mode keeps its own scaffold + `SceneView` — the camera + gesture
 * pipelines are independent and consolidating them visually risks losing the
 * "two distinct primitives" message. Old deep links route through
 * [io.github.sceneview.demo.DeepLinkRouter.DEMO_ID_ALIASES].
 */
@Composable
fun CameraAndGesturesDemo(onBack: () -> Unit) {
    var mode by remember {
        mutableStateOf(initialDemoMode(CameraGesturesMode.entries, CameraGesturesMode.CameraModes))
    }
    when (mode) {
        CameraGesturesMode.CameraModes -> CameraModesSection(onBack, mode) { mode = it }
        CameraGesturesMode.NodeGestures -> NodeGesturesSection(onBack, mode) { mode = it }
    }
}

private enum class CameraGesturesMode(val label: String) {
    CameraModes("Camera Modes"),
    NodeGestures("Node Gestures"),
}

@Composable
private fun ModeSelector(
    current: CameraGesturesMode,
    onModeChange: (CameraGesturesMode) -> Unit,
) {
    val modes = CameraGesturesMode.entries
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        modes.forEachIndexed { index, m ->
            SegmentedButton(
                selected = m == current,
                onClick = { onModeChange(m) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                label = { Text(m.label) },
            )
        }
    }
    Spacer(modifier = Modifier.height(12.dp))
}

// ─── Camera Modes section ──────────────────────────────────────────────────
//
// Verbatim port of the retired `camera-controls` demo body — orbit / free-flight
// / map manipulator with a distance slider. The only addition is the
// ModeSelector at the top of the controls sheet.

@Composable
private fun CameraModesSection(
    onBack: () -> Unit,
    mode: CameraGesturesMode,
    onModeChange: (CameraGesturesMode) -> Unit,
) {
    val modes = remember {
        listOf(
            "Orbit" to Manipulator.Mode.ORBIT,
            "Free Flight" to Manipulator.Mode.FREE_FLIGHT,
            "Map" to Manipulator.Mode.MAP
        )
    }
    var selectedMode by remember { mutableStateOf(modes[0]) }
    var resetKey by remember { mutableIntStateOf(0) }

    var cameraDistance by remember {
        mutableFloatStateOf(DemoSettings.cameraDistance?.coerceIn(0.5f, 8f) ?: 1.5f)
    }

    val homePosition = Position(0.0f, 0.0f, cameraDistance)
    val target = remember { Position(0.0f, 0.0f, 0.0f) }

    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)
    val cameraNode = rememberCameraNode(engine)
    val modelInstance = rememberModelInstance(modelLoader, "models/khronos_damaged_helmet.glb")

    val firstFrame = rememberFirstFrameState()

    val isFreeFlight = selectedMode.second == Manipulator.Mode.FREE_FLIGHT

    DemoScaffold(
        title = stringResource(R.string.demo_camera_and_gestures_title),
        onBack = onBack,
        firstFrameRendered = firstFrame.rendered,
        controls = {
            ModeSelector(mode, onModeChange)
            Text(
                text = "Camera Mode",
                style = MaterialTheme.typography.labelLarge
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                modes.forEach { m ->
                    FilterChip(
                        selected = selectedMode == m,
                        onClick = { selectedMode = m },
                        label = { Text(m.first) }
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = when (selectedMode.second) {
                    Manipulator.Mode.ORBIT ->
                        "Drag to orbit, two-finger drag to pan, pinch to zoom."
                    Manipulator.Mode.MAP ->
                        "Drag to pan across the model, pinch to zoom."
                    Manipulator.Mode.FREE_FLIGHT ->
                        "Drag to look around, use the on-screen pad to fly."
                },
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Camera distance: %.1f m".format(Locale.US, cameraDistance),
                style = MaterialTheme.typography.labelLarge
            )
            Slider(
                value = cameraDistance,
                onValueChange = { cameraDistance = it },
                valueRange = 0.5f..8f
            )
        }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // One Filament manipulator per (mode, reset, distance). Building it with the
            // FREE_FLIGHT start state pointed *at* the model (yaw faces the camera back
            // toward `target` from `homePosition`) is what keeps Free Flight from opening
            // on a black void (#2357).
            val manipulator = remember(selectedMode, resetKey, cameraDistance) {
                Manipulator.Builder()
                    .orbitHomePosition(homePosition)
                    .targetPosition(target)
                    .flightStartPosition(homePosition.x, homePosition.y, homePosition.z)
                    .flightStartOrientation(
                        flightStartPitch(homePosition, target),
                        flightStartYaw(homePosition, target),
                    )
                    .flightMaxMoveSpeed(2.0f)
                    .orbitSpeed(0.005f, 0.005f)
                    .zoomSpeed(0.05f)
                    .build(selectedMode.second)
            }
            // Swap the live manipulator on the SAME SceneView instead of re-keying the
            // whole subtree. Re-keying tore down + rebuilt the SceneView every time the
            // mode changed, and the rebuilt ModelNode reused the already-attached shared
            // `modelInstance`, leaving the new scene with nothing to render — a black
            // void that then leaked back into Orbit (#2357). SceneView reacts to a new
            // `cameraManipulator` via its internal SideEffect, so a stable subtree + a
            // remembered-per-mode manipulator is all that's needed.
            val cameraManipulator = remember(manipulator) {
                CameraGestureDetector.DefaultCameraManipulator(manipulator)
            }
            SceneView(
                modifier = Modifier.fillMaxSize(),
                onFrame = firstFrame.onFrame,
                engine = engine,
                modelLoader = modelLoader,
                environmentLoader = environmentLoader,
                environment = rememberModelDemoEnvironment(environmentLoader),
                cameraNode = cameraNode,
                cameraManipulator = cameraManipulator
            ) {
                modelInstance?.let { instance ->
                    ModelNode(
                        modelInstance = instance,
                        scaleToUnits = 0.5f,
                    )
                }
            }
            if (isFreeFlight) {
                FreeFlightMovementPad(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 88.dp),
                    onKeyDown = { manipulator.keyDown(it) },
                    onKeyUp = { manipulator.keyUp(it) }
                )
            }
            LoadingScrim(loading = modelInstance == null, label = "Loading helmet…")

            SceneActionBar(
                SceneAction("Reset Camera", onClick = { cameraDistance = 1.5f; resetKey++ }),
            )
        }
    }
}

/**
 * Pitch (radians) that aims a FREE_FLIGHT camera placed at [eye] back toward
 * [target], matching Filament's `FreeFlightManipulator` convention where the
 * look direction is `eulerZYX(0, yaw, pitch) · (0, 0, -1)` — i.e.
 * `dir = (-sin(yaw)·cos(pitch), sin(pitch), -cos(yaw)·cos(pitch))`.
 *
 * Deriving the orientation from `target - eye` (instead of hard-coding `(0, 0)`)
 * is what keeps Free Flight from opening on a black void regardless of where the
 * camera home sits relative to the model (#2357).
 */
private fun flightStartPitch(eye: Position, target: Position): Float {
    val dir = target - eye
    val len = kotlin.math.sqrt(dir.x * dir.x + dir.y * dir.y + dir.z * dir.z)
    if (len < 1e-6f) return 0.0f
    return kotlin.math.asin((dir.y / len).coerceIn(-1.0f, 1.0f))
}

/** Yaw (radians) companion to [flightStartPitch] — see its docs for the convention. */
private fun flightStartYaw(eye: Position, target: Position): Float {
    val dir = target - eye
    val len = kotlin.math.sqrt(dir.x * dir.x + dir.y * dir.y + dir.z * dir.z)
    if (len < 1e-6f) return 0.0f
    return kotlin.math.atan2(-dir.x / len, -dir.z / len)
}

@Composable
private fun FreeFlightMovementPad(
    modifier: Modifier = Modifier,
    onKeyDown: (Manipulator.Key) -> Unit,
    onKeyUp: (Manipulator.Key) -> Unit
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
        tonalElevation = 3.dp
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                HoldKeyButton(
                    icon = Icons.Default.KeyboardArrowUp,
                    contentDescription = "Move forward",
                    key = Manipulator.Key.FORWARD,
                    onKeyDown = onKeyDown,
                    onKeyUp = onKeyUp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HoldKeyButton(
                        icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = "Strafe left",
                        key = Manipulator.Key.LEFT,
                        onKeyDown = onKeyDown,
                        onKeyUp = onKeyUp
                    )
                    HoldKeyButton(
                        icon = Icons.Default.KeyboardArrowDown,
                        contentDescription = "Move backward",
                        key = Manipulator.Key.BACKWARD,
                        onKeyDown = onKeyDown,
                        onKeyUp = onKeyUp
                    )
                    HoldKeyButton(
                        icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "Strafe right",
                        key = Manipulator.Key.RIGHT,
                        onKeyDown = onKeyDown,
                        onKeyUp = onKeyUp
                    )
                }
            }
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                HoldKeyButton(
                    icon = Icons.Default.ArrowUpward,
                    contentDescription = "Move up",
                    key = Manipulator.Key.UP,
                    onKeyDown = onKeyDown,
                    onKeyUp = onKeyUp
                )
                HoldKeyButton(
                    icon = Icons.Default.ArrowDownward,
                    contentDescription = "Move down",
                    key = Manipulator.Key.DOWN,
                    onKeyDown = onKeyDown,
                    onKeyUp = onKeyUp
                )
            }
        }
    }
}

@Composable
private fun HoldKeyButton(
    icon: ImageVector,
    contentDescription: String,
    key: Manipulator.Key,
    onKeyDown: (Manipulator.Key) -> Unit,
    onKeyUp: (Manipulator.Key) -> Unit
) {
    FilledIconButton(
        onClick = {},
        shape = CircleShape,
        modifier = Modifier
            .size(48.dp)
            .pointerInput(key) {
                detectTapGestures(
                    onPress = {
                        onKeyDown(key)
                        try {
                            awaitRelease()
                        } finally {
                            onKeyUp(key)
                        }
                    }
                )
            }
    ) {
        Icon(imageVector = icon, contentDescription = contentDescription)
    }
}

// ─── Node Gestures section ─────────────────────────────────────────────────
//
// Verbatim port of the retired `gesture-editing` demo body — per-node
// drag / twist / pinch on the damaged helmet with editability toggles,
// scale-sensitivity slider, gesture-mode indicator, and live transform
// readout. Mode selector injected at the top of the controls sheet.

private const val MODEL_SCALE_UNITS = 0.3f
private const val AXIS_TO_MODEL_RATIO = 1.5f

@Composable
private fun NodeGesturesSection(
    onBack: () -> Unit,
    mode: CameraGesturesMode,
    onModeChange: (CameraGesturesMode) -> Unit,
) {
    var editable by remember { mutableStateOf(true) }
    var positionEditable by remember { mutableStateOf(true) }
    var rotationEditable by remember { mutableStateOf(true) }
    var scaleEditable by remember { mutableStateOf(true) }
    var scaleSensitivity by remember { mutableStateOf(0.5f) }
    var resetKey by remember { mutableStateOf(0) }
    var gestureMode by remember { mutableStateOf<String?>(null) }

    val modelNodeRef = remember { mutableStateOf<ModelNode?>(null) }

    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)
    val modelInstance = rememberModelInstance(modelLoader, "models/khronos_damaged_helmet.glb")

    val effectiveEditable by remember {
        derivedStateOf { editable && (positionEditable || rotationEditable || scaleEditable) }
    }

    val firstFrame = rememberFirstFrameState()

    DemoScaffold(
        title = stringResource(R.string.demo_camera_and_gestures_title),
        onBack = onBack,
        firstFrameRendered = firstFrame.rendered,
        controls = {
            ModeSelector(mode, onModeChange)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = editable,
                        onValueChange = { editable = it },
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Editable", style = MaterialTheme.typography.labelLarge)
                Switch(checked = editable, onCheckedChange = null)
            }

            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = positionEditable,
                        onValueChange = { positionEditable = it },
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("• Position (drag)", style = MaterialTheme.typography.labelMedium)
                Switch(checked = positionEditable, onCheckedChange = null)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = rotationEditable,
                        onValueChange = { rotationEditable = it },
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("• Rotation (twist)", style = MaterialTheme.typography.labelMedium)
                Switch(checked = rotationEditable, onCheckedChange = null)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = scaleEditable,
                        onValueChange = { scaleEditable = it },
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("• Scale (pinch)", style = MaterialTheme.typography.labelMedium)
                Switch(checked = scaleEditable, onCheckedChange = null)
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                "Scale sensitivity: ${(scaleSensitivity * 100f).roundToInt()}%",
                style = MaterialTheme.typography.labelMedium
            )
            Slider(
                value = scaleSensitivity,
                onValueChange = { scaleSensitivity = it },
                valueRange = 0.05f..1f,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = { resetKey++ },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Reset Position")
            }
        }
    ) {
        SceneView(
            modifier = Modifier.fillMaxSize(),
            onFrame = firstFrame.onFrame,
            engine = engine,
            modelLoader = modelLoader,
            materialLoader = materialLoader,
            environmentLoader = environmentLoader,
            environment = rememberModelDemoEnvironment(environmentLoader),
            cameraManipulator = rememberCameraManipulator(),
            onGestureListener = rememberOnGestureListener(
                onDown = { _, node ->
                    gestureMode = when {
                        node == null -> "Moving camera"
                        effectiveEditable -> "Editing helmet"
                        else -> "View only (editing off)"
                    }
                },
                onMoveBegin = { _, _, node ->
                    gestureMode = when {
                        node == null -> "Moving camera"
                        effectiveEditable -> "Editing helmet"
                        else -> "View only (editing off)"
                    }
                },
                onMoveEnd = { _, _, _ -> gestureMode = null },
                onScaleBegin = { _, _, node ->
                    gestureMode = when {
                        node == null -> "Moving camera"
                        effectiveEditable -> "Editing helmet"
                        else -> "View only (editing off)"
                    }
                },
                onScaleEnd = { _, _, _ -> gestureMode = null },
                onRotateBegin = { _, _, node ->
                    gestureMode = when {
                        node == null -> "Moving camera"
                        effectiveEditable -> "Editing helmet"
                        else -> "View only (editing off)"
                    }
                },
                onRotateEnd = { _, _, _ -> gestureMode = null }
            )
        ) {
            Axes3DNode(
                materialLoader = materialLoader,
                length = MODEL_SCALE_UNITS * AXIS_TO_MODEL_RATIO,
                thickness = 0.005f,
            )

            key(resetKey) {
                modelInstance?.let { instance ->
                    ModelNode(
                        modelInstance = instance,
                        scaleToUnits = MODEL_SCALE_UNITS,
                        isEditable = editable,
                        apply = {
                            isPositionEditable = positionEditable
                            isRotationEditable = rotationEditable
                            isScaleEditable = scaleEditable
                            scaleGestureSensitivity = scaleSensitivity
                            modelNodeRef.value = this
                        }
                    )
                }
            }
        }

        LaunchedEffect(positionEditable, rotationEditable, scaleEditable, scaleSensitivity) {
            modelNodeRef.value?.apply {
                isPositionEditable = positionEditable
                isRotationEditable = rotationEditable
                isScaleEditable = scaleEditable
                scaleGestureSensitivity = scaleSensitivity
            }
        }

        gestureMode?.let { label ->
            Surface(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp),
                color = Color.Black.copy(alpha = 0.7f),
                contentColor = Color.White,
                tonalElevation = 4.dp,
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    text = label,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }

        LiveTransformOverlay(
            modelNodeRef = modelNodeRef,
            resetKey = resetKey,
            modifier = Modifier.align(Alignment.TopEnd)
        )
    }
}

@Composable
private fun LiveTransformOverlay(
    modelNodeRef: MutableState<ModelNode?>,
    resetKey: Int,
    modifier: Modifier = Modifier
) {
    var liveX by remember { mutableStateOf(0f) }
    var liveY by remember { mutableStateOf(0f) }
    var liveZ by remember { mutableStateOf(0f) }
    var liveRX by remember { mutableStateOf(0f) }
    var liveRY by remember { mutableStateOf(0f) }
    var liveRZ by remember { mutableStateOf(0f) }

    LaunchedEffect(resetKey) {
        while (true) {
            val node = modelNodeRef.value
            if (node != null) {
                val p = node.position
                val r = node.rotation
                if (abs(p.x - liveX) > 0.0005f) liveX = p.x
                if (abs(p.y - liveY) > 0.0005f) liveY = p.y
                if (abs(p.z - liveZ) > 0.0005f) liveZ = p.z
                if (abs(r.x - liveRX) > 0.1f) liveRX = r.x
                if (abs(r.y - liveRY) > 0.1f) liveRY = r.y
                if (abs(r.z - liveRZ) > 0.1f) liveRZ = r.z
            }
            kotlinx.coroutines.delay(16)
        }
    }

    Surface(
        modifier = modifier.padding(8.dp),
        color = Color.Black.copy(alpha = 0.7f),
        contentColor = Color.White,
        tonalElevation = 4.dp,
        shape = MaterialTheme.shapes.small
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = "pos  X %+.2f  Y %+.2f  Z %+.2f".format(Locale.US, liveX, liveY, liveZ),
                style = MaterialTheme.typography.labelSmall
            )
            Text(
                text = "rot  X %+.0f°  Y %+.0f°  Z %+.0f°".format(Locale.US, liveRX, liveRY, liveRZ),
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}
