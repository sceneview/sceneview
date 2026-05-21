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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.android.filament.utils.Manipulator
import io.github.sceneview.SceneView
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.LoadingScrim
import io.github.sceneview.demo.R
import io.github.sceneview.demo.common.SceneAction
import io.github.sceneview.demo.common.SceneActionBar
import io.github.sceneview.demo.rememberFirstFrameState
import io.github.sceneview.gesture.CameraGestureDetector
import io.github.sceneview.gesture.orbitHomePosition
import io.github.sceneview.gesture.targetPosition
import io.github.sceneview.math.Position
import io.github.sceneview.rememberCameraManipulator
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader

/**
 * Demonstrates camera manipulation modes: orbit, free-flight, and map-style navigation.
 *
 * Users can toggle between [Manipulator.Mode] presets and reset the camera to its home position.
 *
 * ### Touch usability (issue #1428)
 * Filament's [Manipulator] has three modes but only ORBIT and MAP are fully driveable with touch
 * drag gestures. FREE_FLIGHT integrates a velocity from held WASD-style keys — a touch device
 * has no keyboard, so without on-screen controls the camera simply can't move. Worse, FREE_FLIGHT
 * starts at `flightStartPosition` (default `0,0,0`) which is *inside* the helmet model, so the
 * viewport renders black. This demo therefore:
 *  - sets `flightStartPosition` to the same home position as ORBIT/MAP so FREE_FLIGHT opens on a
 *    framed model instead of a black screen, and
 *  - shows an on-screen movement pad (forward / back / strafe / up / down) while FREE_FLIGHT is
 *    selected, wired to [Manipulator.keyDown] / [Manipulator.keyUp].
 */
@Composable
fun CameraControlsDemo(onBack: () -> Unit) {
    val modes = remember {
        listOf(
            "Orbit" to Manipulator.Mode.ORBIT,
            "Free Flight" to Manipulator.Mode.FREE_FLIGHT,
            "Map" to Manipulator.Mode.MAP
        )
    }
    var selectedMode by remember { mutableStateOf(modes[0]) }
    // Incrementing this key invalidates `key(resetKey)` below, which rebuilds the manipulator
    // from scratch at its home position. Both the mode chips and the Reset button go through it.
    var resetKey by remember { mutableIntStateOf(0) }

    // Home camera at 1.5 m so the 0.3 m helmet fills a meaningful fraction of the
    // viewport. Previous z = 4 made the model render at ~10% of frame — far too small.
    // QA finding 2026-05-11.
    val homePosition = remember { Position(0.0f, 0.0f, 1.5f) }
    val target = remember { Position(0.0f, 0.0f, 0.0f) }

    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    // IBL environment so the PBR helmet actually has ambient + specular — without
    // this the damaged-helmet glTF shows up almost black against the dark surface
    // and users can't tell the camera modes apart visually.
    val environmentLoader = rememberEnvironmentLoader(engine)
    val cameraNode = rememberCameraNode(engine)
    val modelInstance = rememberModelInstance(modelLoader, "models/khronos_damaged_helmet.glb")

    // Remembered outside the key(selectedMode, resetKey) block so the scrim
    // only covers the genuine cold start, not every camera-mode swap.
    val firstFrame = rememberFirstFrameState()

    val isFreeFlight = selectedMode.second == Manipulator.Mode.FREE_FLIGHT

    DemoScaffold(
        title = stringResource(R.string.demo_camera_controls_title),
        onBack = onBack,
        firstFrameRendered = firstFrame.rendered,
        // The camera-mode picker is genuine configuration and stays in the
        // sheet. "Reset Camera" is the demo's primary reset action, so it lives
        // on-screen via SceneActionBar (#1964).
        controls = {
            Text(
                text = "Camera Mode",
                style = MaterialTheme.typography.labelLarge
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                modes.forEach { mode ->
                    FilterChip(
                        selected = selectedMode == mode,
                        onClick = { selectedMode = mode },
                        label = { Text(mode.first) }
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
        }
    ) {
        // key(selectedMode, resetKey) forces a fresh rememberCameraManipulator on either
        // a mode change or a reset tap — the creator lambda captures the current mode and
        // rebuilds the Manipulator from its home position. This is the only reliable way
        // to swap Manipulator.Mode mid-session since Manipulator itself has no setMode API.
        Box(modifier = Modifier.fillMaxSize()) {
            androidx.compose.runtime.key(selectedMode, resetKey) {
                // Build the underlying Manipulator here and keep our own reference so the
                // FREE_FLIGHT movement pad can drive it through keyDown/keyUp. ORBIT and MAP
                // ignore the flight* builder values; FREE_FLIGHT needs flightStartPosition set
                // away from the origin, otherwise the camera spawns inside the helmet (#1428).
                val manipulator = remember(selectedMode, resetKey) {
                    Manipulator.Builder()
                        .orbitHomePosition(homePosition)
                        .targetPosition(target)
                        .flightStartPosition(homePosition.x, homePosition.y, homePosition.z)
                        // Look down -Z toward the model from the flight start position.
                        .flightStartOrientation(0.0f, 0.0f)
                        .flightMaxMoveSpeed(2.0f)
                        .orbitSpeed(0.005f, 0.005f)
                        .zoomSpeed(0.05f)
                        .build(selectedMode.second)
                }
                val cameraManipulator = rememberCameraManipulator(
                    orbitHomePosition = homePosition,
                    targetPosition = target,
                    creator = {
                        CameraGestureDetector.DefaultCameraManipulator(manipulator)
                    }
                )
                SceneView(
                    modifier = Modifier.fillMaxSize(),
                    onFrame = firstFrame.onFrame,
                    engine = engine,
                    modelLoader = modelLoader,
                    environmentLoader = environmentLoader,
                    cameraNode = cameraNode,
                    cameraManipulator = cameraManipulator
                ) {
                    modelInstance?.let { instance ->
                        ModelNode(
                            modelInstance = instance,
                            scaleToUnits = 0.5f,
                            centerOrigin = Position(0.0f, 0.0f, 0.0f)
                        )
                    }
                }
                // FREE_FLIGHT has no touch translation gesture — Filament drives flight
                // movement from held keys. Surface an on-screen pad so the mode is usable
                // on a touch device (#1428). The pad sits above the on-screen
                // Reset Camera action bar so the two never overlap (#1964).
                if (isFreeFlight) {
                    FreeFlightMovementPad(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 88.dp),
                        onKeyDown = { manipulator.keyDown(it) },
                        onKeyUp = { manipulator.keyUp(it) }
                    )
                }
            }
            LoadingScrim(loading = modelInstance == null, label = "Loading helmet…")

            // Primary action on-screen (#1964) — "Reset Camera" is the demo's
            // reset action, so it lives bottom-start instead of in the Settings
            // sheet. In FREE_FLIGHT mode the movement pad above is offset up so
            // the two never collide.
            SceneActionBar(
                SceneAction("Reset Camera", onClick = { resetKey++ }),
            )
        }
    }
}

/**
 * On-screen movement pad for FREE_FLIGHT mode. Each button presses the matching
 * [Manipulator.Key] on touch-down and releases it on touch-up, mirroring how a desktop
 * keyboard would drive Filament's free-flight camera.
 */
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
            // Horizontal movement cluster: forward / back / strafe left-right.
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
            // Vertical movement cluster: up / down.
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

/**
 * A round icon button that holds a [Manipulator.Key] pressed for as long as the finger is
 * down. Uses a raw pointer-input loop (not [Button.onClick]) so the key is released exactly
 * on finger-up — and on gesture cancel — instead of firing a single discrete tap.
 */
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
                        // Suspends until finger-up or cancel, then always releases the key
                        // so a lifted finger or interrupted gesture can't strand a held key.
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
