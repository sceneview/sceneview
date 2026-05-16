package io.github.sceneview.demo.demos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.sceneview.SceneView
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.R
import io.github.sceneview.demo.rememberFirstFrameState
import io.github.sceneview.demo.common.Axes3DNode
import io.github.sceneview.math.Position
import io.github.sceneview.node.ModelNode
import io.github.sceneview.rememberCameraManipulator
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberOnGestureListener
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Demonstrates gesture-based editing of a 3D model.
 *
 * Center stage is a damaged helmet [ModelNode]; a Blender-style 3D axes gizmo
 * ([Axes3DNode]) sits at the world origin so the user always knows where (0, 0, 0)
 * is and how their gestures map to X (red), Y (green), and Z (blue).
 *
 * Controls in the bottom panel:
 * - **Editable** switch — when off, all per-node gestures (drag / rotate / scale)
 *   are disabled and the helmet behaves as a pure observed object. When on,
 *   gestures move/rotate/scale the helmet directly.
 * - **Per-axis edit toggles** — independently enable position, rotation, and
 *   scale editing. Useful to lock one axis while exposing the others.
 * - **Scale sensitivity** slider — exposes [io.github.sceneview.node.Node.scaleGestureSensitivity].
 *   Lower values make pinch-to-scale more progressive (smaller per-frame delta).
 * - **Reset Position** — recreates the model at its default transform.
 *
 * Two on-screen overlays:
 * 1. Top-start pill: the active gesture mode — "Editing" (per-node touch on
 *    the editable model) or "Moving camera" (touch on empty space, falling
 *    through to the camera manipulator).
 * 2. Top-end card ([LiveTransformOverlay]): live transform of the helmet (X/Y/Z
 *    position + Euler rotation), polled every frame from the node. The two
 *    overlays sit on opposite horizontal anchors so a wide gesture-mode label
 *    can never overlap the transform readout.
 *
 * Gesture isolation: when a touch lands on the editable helmet, the camera
 * orbit/pan/zoom manipulator is skipped for the duration of that touch (handled
 * by [SceneView]'s touch dispatcher). Without this the same drag would also
 * orbit the camera, making the helmet appear "pinned to the camera" — which in
 * turn made the world-fixed axes look like they were following the helmet.
 */
@Composable
fun GestureEditingDemo(onBack: () -> Unit) {
    var editable by remember { mutableStateOf(true) }
    // Per-axis editability — gated by [editable] in the underlying Node, but
    // exposed here so the user can lock single axes (e.g. allow rotation only).
    var positionEditable by remember { mutableStateOf(true) }
    var rotationEditable by remember { mutableStateOf(true) }
    var scaleEditable by remember { mutableStateOf(true) }

    // Pinch-to-scale damping — passed straight through to Node.scaleGestureSensitivity.
    // 0.1 = very smooth / progressive; 1.0 = raw detector factor.
    var scaleSensitivity by remember { mutableStateOf(0.5f) }

    // Incrementing the key forces a full recomposition of the SceneView content,
    // which recreates the ModelNode at its default position.
    var resetKey by remember { mutableStateOf(0) }

    // Active-gesture label shown at the top of the scene. `null` ⇒ no overlay.
    // Updated from the gesture listener: when a per-node gesture starts/ends we know
    // which target the touch is being applied to (node != null AND editable ⇒ helmet;
    // otherwise the touch hands off to camera orbit/pan/zoom).
    var gestureMode by remember { mutableStateOf<String?>(null) }

    // Live transform tracking — we hold a reference to the active ModelNode so the
    // overlay can poll its position/rotation each frame. The reference is reset
    // whenever the node is recreated (via [resetKey]).
    //
    // The 60 Hz transform poll that drives the overlay deliberately lives inside the
    // separate [LiveTransformOverlay] composable, NOT here. Polling writes Compose
    // state, and if that state were read in this function's body it would recompose
    // GestureEditingDemo — and therefore the SceneView call — 60 times a second.
    // SceneScope.ModelNode re-applies its declared `position`/`rotation` on every
    // recomposition (via a SideEffect), so a frame-rate recomposition would reset the
    // helmet's transform every ~16 ms, reverting any gesture-driven rotation before
    // the user ever sees it. That was the "gestures do nothing" bug: isolating the
    // poll keeps the recomposition scoped to the overlay and leaves gesture
    // transforms intact.
    val modelNodeRef = remember { mutableStateOf<ModelNode?>(null) }

    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)
    // Damaged helmet — visually rich PBR materials with
    // metallic, roughness, and emissive maps make rotation gestures actually
    // useful (you see the lighting react). Centered via centerOrigin = (0,0,0).
    val modelInstance = rememberModelInstance(modelLoader, "models/khronos_damaged_helmet.glb")

    // Derived label so the overlay reflects the actual editability state instead
    // of always saying "Editing" the moment a touch lands on the node — that was
    // confusing because gestures are blocked when [editable] is false.
    val effectiveEditable by remember {
        derivedStateOf { editable && (positionEditable || rotationEditable || scaleEditable) }
    }

    val firstFrame = rememberFirstFrameState()

    DemoScaffold(
        title = stringResource(R.string.demo_gesture_editing_title),
        onBack = onBack,
        firstFrameRendered = firstFrame.rendered,
        controls = {
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

            // Per-axis locks — only meaningful when the master switch is on.
            // Greyed out via inherited alpha if needed; here we keep them
            // active (the underlying Node still gates on isEditable AND the
            // sub-flag, so toggling them is harmless when editable=false).
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

            // Scale sensitivity — exposes Node.scaleGestureSensitivity (0..1).
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
            cameraManipulator = rememberCameraManipulator(),
            // Distinguish per-node gestures from camera gestures via the `node`
            // parameter: when the gesture system identifies a target node, it's a
            // per-node edit; when `node == null`, the gesture falls through to the
            // CameraGestureDetector. We additionally consult [effectiveEditable]
            // because gestures are silently blocked when editing is off — without
            // this check the overlay would say "Editing" while nothing actually
            // moves, which was the original "no-op toggle" complaint.
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
            // Static Blender-style 3D axes at the world origin — never editable,
            // serves as a visual anchor for the user's gestures. Declared at the
            // SceneScope root (sibling of the ModelNode below, NOT a child) so it
            // stays world-fixed when the helmet moves/rotates.
            //
            // Length 1 m (each side reaches ±0.5 m from the origin) so the axes
            // visually extend past the helmet's bounding box even at 0.3 m scale —
            // makes the (0, 0, 0) reference unambiguous when the helmet is dragged.
            Axes3DNode(
                materialLoader = materialLoader,
                length = 1f,
                thickness = 0.008f,
            )

            // The key(resetKey) block ensures the node is recreated from scratch on reset.
            key(resetKey) {
                modelInstance?.let { instance ->
                    ModelNode(
                        modelInstance = instance,
                        scaleToUnits = 0.3f,
                        centerOrigin = Position(x = 0f, y = 0f, z = 0f),
                        isEditable = editable,
                        // The `apply` runs once on creation — push the per-axis
                        // flags, the scale-sensitivity slider, and capture the node
                        // ref for the live-transform overlay. The SideEffect inside
                        // SceneScope.ModelNode does NOT mirror these because they
                        // live on the underlying Node, not the composable signature
                        // — so we additionally re-push them via the apply block.
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

        // Re-push reactive props onto the existing node when the user toggles
        // per-axis flags or moves the slider — the apply{} block above only
        // runs once at creation. Without this LaunchedEffect, the slider would
        // be inert post-creation.
        LaunchedEffect(positionEditable, rotationEditable, scaleEditable, scaleSensitivity) {
            modelNodeRef.value?.apply {
                isPositionEditable = positionEditable
                isRotationEditable = rotationEditable
                isScaleEditable = scaleEditable
                scaleGestureSensitivity = scaleSensitivity
            }
        }

        // On-screen indicator: small pill showing what the current gesture is doing.
        // Hidden when no gesture is active. Anchored to the TOP-START corner — the
        // live-transform card owns the TOP-END corner. The pill used to sit at
        // Alignment.TopCenter and visually collided with the readout card once the
        // card grew wide enough (the "Moving camera" label overlapping the pos/rot
        // text). Separate horizontal anchors guarantee they can never overlap
        // regardless of label width.
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

        // Live-transform readout — top-end corner, in its own composable so the
        // 60 Hz transform poll recomposes only this overlay, never the SceneView.
        LiveTransformOverlay(
            modelNodeRef = modelNodeRef,
            resetKey = resetKey,
            modifier = Modifier.align(Alignment.TopEnd)
        )
    }
}

/**
 * Top-end overlay showing the live position/rotation of the edited helmet.
 *
 * Polls the node's transform on a 60 Hz tick. Crucially this lives in its **own**
 * composable: the polled values are Compose state, and were they read inside
 * [GestureEditingDemo]'s body the whole demo — including the `SceneView` call —
 * would recompose every frame. `SceneScope.ModelNode` re-applies its declared
 * transform on each recomposition, so a frame-rate recomposition would silently
 * revert every gesture-driven rotation. Scoping the poll here keeps the
 * recomposition isolated to this overlay and leaves the gesture transforms intact.
 */
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

    // Frame-poll the node's transform — Filament does NOT route Compose state, so
    // gesture-driven mutations to position/rotation never trigger recomposition.
    // We snapshot the values on a 60 Hz tick and compare; this overlay only
    // recomposes when something actually changed past a small threshold (avoids
    // burning composition on floating-point noise). Restarted whenever [resetKey]
    // changes — the node is recreated then, so the previous reference is stale.
    LaunchedEffect(resetKey) {
        // Wait for the node to be created and for its ref to be populated by the
        // ModelNode `apply` block — async model load means we hit this before
        // the ref is ready on first composition.
        while (true) {
            val node = modelNodeRef.value
            if (node != null) {
                val p = node.position
                val r = node.rotation
                // Snap thresholds: 0.5 mm for position, 0.1° for rotation. Small
                // enough to feel real-time, big enough to avoid a recomposition
                // every frame from FP jitter.
                if (abs(p.x - liveX) > 0.0005f) liveX = p.x
                if (abs(p.y - liveY) > 0.0005f) liveY = p.y
                if (abs(p.z - liveZ) > 0.0005f) liveZ = p.z
                if (abs(r.x - liveRX) > 0.1f) liveRX = r.x
                if (abs(r.y - liveRY) > 0.1f) liveRY = r.y
                if (abs(r.z - liveRZ) > 0.1f) liveRZ = r.z
            }
            // ~16 ms = 60 Hz. Compose's withFrameNanos would be ideal but adds
            // complexity for a debug overlay; a plain delay is fine here.
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
                text = "pos  X %+.2f  Y %+.2f  Z %+.2f".format(liveX, liveY, liveZ),
                style = MaterialTheme.typography.labelSmall
            )
            Text(
                text = "rot  X %+.0f°  Y %+.0f°  Z %+.0f°".format(liveRX, liveRY, liveRZ),
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}
