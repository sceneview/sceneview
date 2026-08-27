package io.github.sceneview.demo.demos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import io.github.sceneview.RenderQuality
import io.github.sceneview.SceneView
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.R
import io.github.sceneview.demo.common.rememberModelDemoEnvironment
import io.github.sceneview.demo.rememberFirstFrameState
import io.github.sceneview.gesture.NodeEditingOverlay
import io.github.sceneview.gesture.rememberNodeEditingFeedback
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Position
import io.github.sceneview.math.Size
import io.github.sceneview.node.ModelNode
import io.github.sceneview.rememberCameraManipulator
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberView

/**
 * **Gesture Feedback Preview** — a *non-AR* scene exercising the opt-in on-model gesture
 * feedback API ([NodeEditingOverlay] + [rememberNodeEditingFeedback]) on an editable
 * model, so the visuals can be QA'd on any emulator without an ARCore session:
 *
 * - **Two-finger twist** → accent ring around the base, live sweep arc and yaw badge.
 * - **Pinch** → percentage badge above the model; the `editableScaleRange` here is
 *   deliberately narrow (0.5×–2× the start scale) so the limit **bounce** is easy to hit.
 * - **Drag** → soft contact shadow following the base. The model is a child of the floor
 *   plane, so drags hit-test the floor and re-place the model on it.
 * - **Selected** toggle → the white selection ring, visible only while no gesture is
 *   active (selection and gesture-active are distinct states).
 */
@Composable
fun GestureFeedbackPreviewDemo(onBack: () -> Unit) {
    var selected by remember { mutableStateOf(true) }
    val modelNodeRef = remember { mutableStateOf<ModelNode?>(null) }

    val engine = rememberEngine()
    val view = rememberView(engine)
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)
    val modelInstance = rememberModelInstance(modelLoader, "models/khronos_damaged_helmet.glb")

    val floorMaterial = remember(engine) {
        materialLoader.createColorInstance(Color(0xFF2A2E36), metallic = 0f, roughness = 0.9f)
    }

    val firstFrame = rememberFirstFrameState()

    DemoScaffold(
        title = stringResource(R.string.demo_gesture_feedback_preview_title),
        onBack = onBack,
        firstFrameRendered = firstFrame.rendered,
        controls = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = selected,
                        onValueChange = { selected = it },
                    ),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Selected (ring when idle)", style = MaterialTheme.typography.bodyMedium)
                Switch(checked = selected, onCheckedChange = null)
            }
        }
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
                // Match ARSceneView's render pipeline so the overlay is judged against the
                // same image AR users see (see PlaneGridPreviewDemo, #2224).
                renderQuality = RenderQuality.Performance,
                // Keep the hand-placed floor + model framing deterministic.
                autoCenterContent = false,
                cameraManipulator = rememberCameraManipulator(
                    // ~35° above the floor, close enough that the base ring spans a good
                    // third of the viewport width.
                    orbitHomePosition = Position(x = 0f, y = 1.1f, z = 1.7f),
                    targetPosition = Position(x = 0f, y = 0.3f, z = 0f),
                ),
            ) {
                // Floor: parent of the model, so a drag hit-tests it and re-places the
                // child on the hit point (NodeGestureDelegate.onMove semantics).
                PlaneNode(
                    size = Size(x = 4f, y = 0f, z = 4f),
                    normal = Direction(y = 1f),
                    materialInstance = floorMaterial,
                ) {
                    modelInstance?.let { instance ->
                        ModelNode(
                            modelInstance = instance,
                            scaleToUnits = 0.6f,
                            isEditable = true,
                            apply = {
                                isPositionEditable = true
                                // Narrow window around the as-placed scale so the badge's
                                // limit bounce is reachable in a couple of pinches. The
                                // range is ABSOLUTE local scale — scaleToUnits means the
                                // start scale is nowhere near 1.0.
                                editableScaleRange = (scale.x * 0.5f)..(scale.x * 2f)
                                // The helmet asset's origin is its AABB center; sit the
                                // model ON the floor instead of half-burying it, both at
                                // placement and on every drag (a drag puts the node
                                // ORIGIN at the floor hit point).
                                fun baseLift() = -(center.y - halfExtent.y) * scale.x
                                position = Position(y = baseLift())
                                onMove = { _, _, worldPos ->
                                    worldPosition = worldPos + Position(y = baseLift())
                                    false
                                }
                                modelNodeRef.value = this
                            }
                        )
                    }
                }
            }

            modelNodeRef.value?.let { node ->
                NodeEditingOverlay(
                    state = rememberNodeEditingFeedback(node),
                    view = view,
                    modifier = Modifier.matchParentSize(),
                    selected = selected,
                )
            }
        }
    }
}
