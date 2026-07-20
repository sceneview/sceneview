package io.github.sceneview.demo.demos

import android.view.MotionEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.romainguy.kotlin.math.Float3
import io.github.sceneview.SceneView
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.R
import io.github.sceneview.demo.SceneViewColors
import io.github.sceneview.demo.common.SceneAction
import io.github.sceneview.demo.common.SceneActionBar
import io.github.sceneview.demo.initialDemoMode
import io.github.sceneview.demo.rememberFirstFrameState
import io.github.sceneview.demo.rememberPausableHeroYaw
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Size
import io.github.sceneview.node.Node
import io.github.sceneview.node.ViewNode
import io.github.sceneview.rememberCameraManipulator
import io.github.sceneview.rememberCollisionSystem
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberOnGestureListener
import io.github.sceneview.rememberView
import io.github.sceneview.rememberViewNodeManager
import io.github.sceneview.sample.rememberUnlitMaterialInstance

/**
 * Unified "Picking & Collision" demo — consolidates the retired `collision` and
 * `view-node` demos behind a single segmented-button toggle (#2239 Batch 1).
 *
 * - **Ray Hit-Test** — tap-driven ray casts highlight 3D primitives via the
 *   library's `CollisionSystem`. (Formerly the `collision` demo.)
 * - **View Node** — embedding live Compose UI as a textured quad inside a 3D
 *   scene, with hoisted tap state. (Formerly the `view-node` demo.)
 *
 * The two sub-modes share the "interactive picking surface" theme but require
 * different camera framings and gesture pipelines, so each keeps its own
 * `SceneView`. Old deep links route through
 * [io.github.sceneview.demo.DeepLinkRouter.DEMO_ID_ALIASES].
 */
@Composable
fun PickingAndCollisionDemo(onBack: () -> Unit) {
    var mode by remember {
        mutableStateOf(initialDemoMode(PickingMode.entries, PickingMode.RayHitTest))
    }
    when (mode) {
        PickingMode.RayHitTest -> RayHitTestSection(onBack, mode) { mode = it }
        PickingMode.ViewNode -> ViewNodeSection(onBack, mode) { mode = it }
    }
}

private enum class PickingMode(val label: String) {
    RayHitTest("Ray Hit-Test"),
    ViewNode("View Node"),
}

@Composable
private fun ModeSelector(
    current: PickingMode,
    onModeChange: (PickingMode) -> Unit,
) {
    val modes = PickingMode.entries
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

@Composable
private fun RayHitTestSection(
    onBack: () -> Unit,
    mode: PickingMode,
    onModeChange: (PickingMode) -> Unit,
) {
    var highlightedIndices by remember { mutableStateOf(setOf<Int>()) }

    val engine = rememberEngine()
    val materialLoader = rememberMaterialLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)
    val view = rememberView(engine)
    val collisionSystem = rememberCollisionSystem(view)

    val defaultMaterial = rememberUnlitMaterialInstance(materialLoader, SceneViewColors.Primary)
    val highlightedMaterial = rememberUnlitMaterialInstance(materialLoader, SceneViewColors.Accent)

    data class ShapeSpec(
        val index: Int,
        val isSphere: Boolean,
        val position: Position
    )

    val shapes = remember {
        listOf(
            ShapeSpec(0, isSphere = false, position = Position(x = -0.6f, y = 0f, z = -2f)),
            ShapeSpec(1, isSphere = true, position = Position(x = -0.3f, y = 0.3f, z = -2f)),
            ShapeSpec(2, isSphere = false, position = Position(x = 0f, y = 0f, z = -2f)),
            ShapeSpec(3, isSphere = true, position = Position(x = 0.3f, y = 0.3f, z = -2f)),
            ShapeSpec(4, isSphere = false, position = Position(x = 0.6f, y = 0f, z = -2f))
        )
    }

    val gestureListener = rememberOnGestureListener(
        onSingleTapUp = { _: MotionEvent, node: Node? ->
            if (node != null) {
                val idx = node.name?.removePrefix("shape_")?.toIntOrNull()
                if (idx != null) {
                    highlightedIndices = if (idx in highlightedIndices) {
                        highlightedIndices - idx
                    } else {
                        highlightedIndices + idx
                    }
                }
            }
        }
    )

    val firstFrame = rememberFirstFrameState()

    DemoScaffold(
        title = stringResource(R.string.demo_picking_collision_title),
        onBack = onBack,
        firstFrameRendered = firstFrame.rendered,
        controls = {
            ModeSelector(mode, onModeChange)
            Text(
                "Tap a shape to highlight it. Use the on-screen \"Reset Colors\" " +
                    "button to clear all highlights.",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            SceneView(
                modifier = Modifier.fillMaxSize(),
                onFrame = firstFrame.onFrame,
                engine = engine,
                materialLoader = materialLoader,
                environmentLoader = environmentLoader,
                view = view,
                collisionSystem = collisionSystem,
                autoCenterContent = false,
                cameraManipulator = rememberCameraManipulator(
                    orbitHomePosition = Position(0f, 0.2f, 1f),
                    targetPosition = Position(0f, 0.15f, -2f),
                ),
                onGestureListener = gestureListener
            ) {
                for (shape in shapes) {
                    val mat = if (shape.index in highlightedIndices) {
                        highlightedMaterial
                    } else {
                        defaultMaterial
                    }
                    if (shape.isSphere) {
                        SphereNode(
                            radius = 0.15f,
                            materialInstance = mat,
                            position = shape.position,
                            apply = {
                                name = "shape_${shape.index}"
                                isHittable = true
                            }
                        )
                    } else {
                        CubeNode(
                            size = Size(x = 0.25f, y = 0.25f, z = 0.25f),
                            materialInstance = mat,
                            position = shape.position,
                            apply = {
                                name = "shape_${shape.index}"
                                isHittable = true
                            }
                        )
                    }
                }
            }

            SceneActionBar(
                SceneAction("Reset Colors", onClick = { highlightedIndices = emptySet() }),
            )
        }
    }
}

@Composable
private fun ViewNodeSection(
    onBack: () -> Unit,
    mode: PickingMode,
    onModeChange: (PickingMode) -> Unit,
) {
    var isVisible by remember { mutableStateOf(true) }
    var tapCount by remember { mutableIntStateOf(0) }

    val engine = rememberEngine()
    val materialLoader = rememberMaterialLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)
    val windowManager = rememberViewNodeManager()

    val (heroYaw, onHeroGesture) = rememberPausableHeroYaw(
        trigger = true,
        durationMillis = 14_000,
        staticYaw = 20f,
        idleResumeMillis = 3_000L,
    )

    val gestureListener = rememberOnGestureListener(
        // A tap anywhere in the scene must NOT bump the counter — only the "Tap me"
        // button inside the ViewNode card does, via its own onTap (see EmbeddedCard).
        // Otherwise tapping empty space (or the back of the card) increments too, which
        // defeats the whole point of the picking demo. The scene tap only nudges the
        // idle hero-orbit so a drag pauses the auto-rotation.
        onSingleTapUp = { _, _ -> onHeroGesture() },
        onDoubleTap = { _, _ -> onHeroGesture() },
        onScroll = { _, _, _, _ -> onHeroGesture() },
    )

    val firstFrame = rememberFirstFrameState()

    var renderedFrames by remember { mutableIntStateOf(0) }
    val onWarmedUpFrame: (Long) -> Unit = { frameTimeNanos ->
        if (renderedFrames < VIEW_NODE_WARMUP_FRAMES) {
            renderedFrames++
        } else {
            firstFrame.onFrame(frameTimeNanos)
        }
    }

    DemoScaffold(
        title = stringResource(R.string.demo_picking_collision_title),
        onBack = onBack,
        firstFrameRendered = firstFrame.rendered,
        controls = {
            ModeSelector(mode, onModeChange)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = isVisible,
                        onValueChange = { isVisible = it },
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Visible", style = MaterialTheme.typography.labelLarge)
                Spacer(modifier = Modifier.width(12.dp))
                Switch(checked = isVisible, onCheckedChange = null)
            }
        }
    ) {
        SceneView(
            modifier = Modifier.fillMaxSize(),
            onFrame = onWarmedUpFrame,
            engine = engine,
            materialLoader = materialLoader,
            environmentLoader = environmentLoader,
            viewNodeWindowManager = windowManager,
            cameraManipulator = rememberCameraManipulator(),
            onGestureListener = gestureListener,
        ) {
            Node(
                position = Position(x = 0f, y = 0f, z = -1f),
                rotation = Rotation(y = heroYaw),
            ) {
                ViewNode(
                    windowManager = windowManager,
                    unlit = true,
                    position = Position(x = 0f, y = 0f, z = 0.005f),
                    scale = Float3(0.35f),
                    isVisible = isVisible
                ) {
                    EmbeddedCard(tapCount = tapCount, onTap = { tapCount++ })
                }
                ViewNode(
                    windowManager = windowManager,
                    unlit = true,
                    position = Position(x = 0f, y = 0f, z = -0.005f),
                    rotation = Rotation(y = 180f),
                    scale = Float3(0.35f),
                    isVisible = isVisible
                ) {
                    BackCard(tapCount = tapCount)
                }
            }
        }
    }
}

@Composable
private fun EmbeddedCard(tapCount: Int, onTap: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        modifier = Modifier.padding(8.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Hello from 3D!",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Tapped $tapCount times",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = onTap) {
                Text("Tap me")
            }
        }
    }
}

@Composable
private fun BackCard(tapCount: Int) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        ),
        modifier = Modifier.padding(8.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "← the back",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Real 3D Compose.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Front: $tapCount taps",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

private const val VIEW_NODE_WARMUP_FRAMES = 18
