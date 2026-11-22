// SceneView 2.x code that an AI coding agent writes from memory — it must keep compiling.
//
// Source: answer 2 of the 2026-11-22 agent measurement. Claude Code, with no tools and no MCP,
// was asked: "Add AR to my Android app: the user should be able to place a 3D model of a piece of
// furniture in their room at real size. What's the best way to do it today? Dependencies and code
// please." It recommended `io.github.sceneview:arsceneview:2.3.0` and wrote the code below.
// Agents keep emitting this 2.x spelling, so SceneView 4.x keeps it compiling through deprecated
// shims whose warning gives the 4.x spelling (see ar/ARCompat2x.kt and Compat2x.kt; issue #5216).
//
// The code is verbatim, imports included. The only addition is the `package` line. The
// deprecation warnings it raises are left on purpose: they are what the shims tell the agent.
// Compile-only: nothing here runs. If this file stops compiling, a 2.x shim broke.
package io.github.sceneview.agentcompat

import android.view.MotionEvent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.android.filament.Engine
import com.google.ar.core.*
import io.github.sceneview.ar.ARScene
import io.github.sceneview.ar.arcore.createAnchorOrNull
import io.github.sceneview.ar.arcore.isValid
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.ar.rememberARCameraNode
import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.node.ModelNode
import io.github.sceneview.rememberCollisionSystem
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberNodes
import io.github.sceneview.rememberOnGestureListener
import io.github.sceneview.rememberView

@Composable
fun FurnitureArScreen(modelAssetPath: String = "models/armchair.glb") {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val view = rememberView(engine)
    val collisionSystem = rememberCollisionSystem(view)
    val cameraNode = rememberARCameraNode(engine)
    val childNodes = rememberNodes()

    var frame by remember { mutableStateOf<Frame?>(null) }
    var showPlanes by remember { mutableStateOf(true) }
    var trackingFailure by remember { mutableStateOf<TrackingFailureReason?>(null) }
    var placedNode by remember { mutableStateOf<AnchorNode?>(null) }

    Box(Modifier.fillMaxSize()) {
        ARScene(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            view = view,
            modelLoader = modelLoader,
            collisionSystem = collisionSystem,
            cameraNode = cameraNode,
            childNodes = childNodes,
            planeRenderer = showPlanes,
            sessionConfiguration = { session, config ->
                config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL // floor
                config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
                config.depthMode =
                    if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC))
                        Config.DepthMode.AUTOMATIC   // occlusion + better hit tests
                    else Config.DepthMode.DISABLED
                config.instantPlacementMode = Config.InstantPlacementMode.DISABLED // need real scale
                config.focusMode = Config.FocusMode.AUTO
            },
            onSessionUpdated = { _, updatedFrame -> frame = updatedFrame },
            onTrackingFailureChanged = { trackingFailure = it },
            onGestureListener = rememberOnGestureListener(
                onSingleTapConfirmed = { e: MotionEvent, node ->
                    if (node != null) return@rememberOnGestureListener // tapped the model itself
                    val anchor = frame?.hitTest(e.x, e.y)
                        ?.firstOrNull { it.isValid(depthPoint = false, point = false) } // planes only
                        ?.createAnchorOrNull()
                        ?: return@rememberOnGestureListener

                    // One piece of furniture at a time: tapping again moves it
                    placedNode?.let { childNodes -= it; it.destroy() }
                    placedNode = createFurnitureNode(engine, modelLoader, anchor, modelAssetPath)
                        .also { childNodes += it }
                    showPlanes = false
                }
            )
        )

        val hint = when {
            trackingFailure != null -> "Move your phone slowly to scan the room"
            placedNode == null -> "Point at the floor, then tap to place"
            else -> "Drag to move • Two fingers to rotate • Tap the floor to re-place"
        }
        Text(hint, Modifier.align(Alignment.TopCenter).padding(top = 48.dp))
    }
}

private fun createFurnitureNode(
    engine: Engine,
    modelLoader: ModelLoader,
    anchor: Anchor,
    assetPath: String,
): AnchorNode {
    val anchorNode = AnchorNode(engine = engine, anchor = anchor)
    val modelNode = ModelNode(
        modelInstance = modelLoader.createModelInstance(assetFileLocation = assetPath),
        scaleToUnits = null,  // KEY: keep the glTF's real-world size (1 unit = 1 m)
        centerOrigin = null,  // keep the authored origin (bottom-center = sits on floor)
    ).apply {
        isEditable = true
        isPositionEditable = true
        isRotationEditable = true
        isScaleEditable = false  // KEY: no pinch-to-resize, so it stays real size
    }
    anchorNode.addChildNode(modelNode)
    return anchorNode
}
