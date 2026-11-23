// SceneView 2.x code that an AI coding agent writes from memory — what of it is SceneView 2.x API
// must keep compiling.
//
// Source: the 2026-11-23 Gemini check (OpenRouter, no tools, no MCP server or skill, no web
// search, one answer per prompt). Gemini 3.1 Pro preview, prompt 2: "Let the user place a 3D chair
// on the floor with ARCore, in Compose." It recommended `io.github.sceneview:arsceneview:2.1.1`
// and wrote the code below. The 4.x shims are in Compat2x.kt and ar/ARCompat2x.kt; issue #5216.
//
// The code is verbatim, imports included. Changes, none of them SceneView API:
// - the `package` line;
// - the lines that compile against no SceneView 2.x release are commented out, each under a
//   `// Left out:` line that says why. They get no shim: either they never existed in any
//   SceneView release, or they are the pre-2.x API (0.x, or arsceneview 1.1.0–1.2.2), which is
//   out of the scope of the 2.x shims. `onSessionConfiguration` gets no shim on purpose: it was
//   never in a 2.x artifact, a shim would rescue no answer, and it would add public API to a
//   frozen major. The imports that resolve to nothing are commented out with
//   them; the others stay, unused.
// `setContent` needs `activity-compose` on the test classpath, as in an app (build.gradle).
// Everything else compiles unchanged against 4.72.0 through the `rememberNodes` and
// `ARScene(childNodes = ...)` shims of #5217. The deprecation warnings it raises are left on
// purpose: they are what the shims tell the agent.
// Compile-only: nothing here runs. If this file stops compiling, a 2.x shim broke.
package io.github.sceneview.agentcompat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.google.ar.core.Config
import io.github.sceneview.ar.ARScene
// Left out: 0.x API (arsceneview 0.10.x and earlier), removed before 2.0.
// import io.github.sceneview.ar.node.ArModelNode
// import io.github.sceneview.ar.node.PlacementMode
import io.github.sceneview.ar.rememberARCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberNodes

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ARChairScreen()
        }
    }
}

@Composable
fun ARChairScreen() {
    // The engine handles the 3D rendering context
    val engine = rememberEngine()

    // ModelLoader loads 3D models from your assets
    val modelLoader = rememberModelLoader(engine)

    // A list of nodes (objects) in our 3D scene
    val childNodes = rememberNodes()

    // The AR Camera
    val cameraNode = rememberARCameraNode(engine)

    Box(modifier = Modifier.fillMaxSize()) {
        ARScene(
            modifier = Modifier.fillMaxSize(),
            childNodes = childNodes,
            engine = engine,
            modelLoader = modelLoader,
            cameraNode = cameraNode,
            planeRenderer = true, // Shows dots on the floor to indicate detected planes
            // Left out: arsceneview 1.1.0–1.2.2 only (renamed in 1.2.3); 2.x and 4.x call it `sessionConfiguration`.
            // onSessionConfiguration = { session, config ->
            //     // Look for horizontal planes (floors/tables)
            //     config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
            // },
            onSessionUpdated = { _, frame ->
                // Optional: You can track AR frame updates here
            },
            onGestureListener = io.github.sceneview.rememberOnGestureListener(
                onSingleTapConfirmed = { motionEvent, node ->
                    // If we tapped on an existing 3D model, don't place a new one
                    if (node != null) return@rememberOnGestureListener

                    // Perform a hit test on the tapped screen coordinate
                    val hitResults = cameraNode.hitTest(motionEvent.x, motionEvent.y)

                    // Left out: hallucinated. CameraNode.hitTest returns collision hits (2.x and 4.x), not ARCore's: no `trackable`, no `createAnchor()`.
                    // // Get the first hit result that intersects with a detected plane
                    // val hitResult = hitResults.firstOrNull {
                    //     it.trackable is com.google.ar.core.Plane
                    // }
                    //
                    // hitResult?.let { hit ->
                    //     // Create an anchor at the tapped real-world location
                    //     val anchor = hit.createAnchor()
                    //
                    // Left out: 0.x API (ArModelNode, PlacementMode, loadModelGlbAsync, ArModelNode.anchor), removed before 2.0.
                    //     // Create a new 3D model node
                    //     val chairNode = ArModelNode(
                    //         engine = engine,
                    //         placementMode = PlacementMode.PLANE_HORIZONTAL
                    //     ).apply {
                    //         loadModelGlbAsync(
                    //             glbFileLocation = "chair.glb", // Loads from the assets folder
                    //             scaleToUnits = 0.5f // Scales the model to 0.5 meters to prevent it being massive
                    //         )
                    //         this.anchor = anchor
                    //     }
                    //
                    // Left out: it adds the left-out `chairNode`.
                    //     // Add the chair to our scene
                    //     childNodes.add(chairNode)
                    // }
                }
            )
        )

        // Simple UI overlay instructions
        Text(
            text = "Point at the floor and tap the dots to place a chair",
            color = Color.White,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(32.dp)
        )
    }
}
