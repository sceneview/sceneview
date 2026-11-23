// SceneView code that an AI coding agent writes from memory, pinned to 2.x but written in the 0.x
// Compose API — what of it is SceneView 2.x API must keep compiling.
//
// Source: the 2026-11-23 Gemini check (OpenRouter, no tools, no MCP server or skill, no web
// search, one answer per prompt). Gemini 3.1 Pro preview, prompt 4: "Measure the distance between
// two tapped points in AR, in an Android app with Jetpack Compose." It recommended
// `io.github.sceneview:arsceneview:2.2.1` and wrote the code below. Its `ARScene(nodes = ...,
// onCreate = { arSceneView -> ... }, onTap = { hitResult -> ... })` and `ArNode` are the
// arsceneview 0.10.x Compose API, removed before 2.0. The 4.x shims are in ar/ARCompat2x.kt;
// issue #5216.
//
// The code is verbatim, imports included. Changes, none of them SceneView API:
// - the `package` line; the answer's math helper (`calculateDistance`, its own section with its
//   own imports) merged into this file, its imports into one list;
// - the lines that compile against no SceneView 2.x release are commented out, each under a
//   `// Left out:` line that says why. They get no shim: the 0.x API is out of the scope of the
//   2.x shims, and `hitResult.session` never existed. The imports that resolve to nothing are
//   commented out with them; the others stay, unused.
// What remains (the `ARScene` call itself and the Compose overlay) compiles unchanged against
// 4.72.0 through the deprecated `ARScene` alias. The deprecation warning it raises is left on
// purpose: it is what the alias tells the agent.
// Kept as a record of the answer: it only guards the bare deprecated `ARScene` alias.
// Compile-only: nothing here runs. If this file stops compiling, a 2.x alias broke.
package io.github.sceneview.agentcompat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.ar.core.Config
import com.google.ar.core.Pose
import io.github.sceneview.ar.ARScene
import io.github.sceneview.ar.arcore.createAnchorOrNull
// Left out: 0.x API (arsceneview 0.10.x and earlier), removed before 2.0.
// import io.github.sceneview.ar.node.ArNode
import io.github.sceneview.math.Position
import io.github.sceneview.node.ModelNode
import java.util.Locale
import kotlin.math.sqrt

fun calculateDistance(pose1: Pose, pose2: Pose): Float {
    val dx = pose1.tx() - pose2.tx()
    val dy = pose1.ty() - pose2.ty()
    val dz = pose1.tz() - pose2.tz()
    // Returns distance in meters
    return sqrt(dx * dx + dy * dy + dz * dz)
}

@Composable
fun ARMeasurementScreen() {
    // Left out: 0.x API (ArNode), removed before 2.0.
    // // State to hold the nodes (markers) we place in the AR world
    // val nodes = remember { mutableStateListOf<ArNode>() }

    // State to hold the poses (3D coordinates) of our taps
    var startPose by remember { mutableStateOf<Pose?>(null) }
    var endPose by remember { mutableStateOf<Pose?>(null) }

    // State to display to the user
    var distanceText by remember { mutableStateOf("Tap a surface to place the first point") }

    Box(modifier = Modifier.fillMaxSize()) {
        ARScene(
            modifier = Modifier.fillMaxSize(),
            // Left out: 0.x API, the 0.x ARScene `nodes` list (2.x: `childNodes`); and the list it names is left out.
            // nodes = nodes,
            planeRenderer = true, // Shows dotted dots on detected surfaces
            // Left out: 0.x API, ARScene `onCreate` with the 0.x ArSceneView View (2.x: `sessionConfiguration`).
            // onCreate = { arSceneView ->
            //     // Configure ARCore to focus on finding flat surfaces
            //     arSceneView.configureSession { session, config ->
            //         config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
            //     }
            // },
            // Left out: 0.x API, ARScene `onTap` (2.x: `onGestureListener`); inside, `hitResult.session` never existed and `ArNode` is 0.x.
            // onTap = { hitResult ->
            //     // If we already have 2 points, ignore further taps until reset
            //     if (startPose != null && endPose != null) return@ARScene
            //
            //     // Create an anchor at the tapped location
            //     val anchor = hitResult.createAnchorOrNull() ?: return@ARScene
            //     val hitPose = anchor.pose
            //
            //     // Create a visible node (a standard Sceneview 3D model, e.g., a sphere)
            //     // For a production app, you would load a .glb 3D model here.
            //     val anchorNode = ArNode(hitResult.session?.engine!!).apply {
            //         this.anchor = anchor
            //         // Note: Ideally, load a small red sphere .glb model here
            //         // model = engine.createModel(...)
            //     }
            //     nodes.add(anchorNode)
            //
            //     // Logic for First vs Second tap
            //     if (startPose == null) {
            //         startPose = hitPose
            //         distanceText = "First point placed. Tap for the second point."
            //     } else {
            //         endPose = hitPose
            //         val distanceMeters = calculateDistance(startPose!!, endPose!!)
            //         // Convert to centimeters and format
            //         val distanceCm = distanceMeters * 100
            //         distanceText = String.format(Locale.getDefault(), "Distance: %.2f cm", distanceCm)
            //     }
            // }
        )

        // UI Overlay
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Text displaying instructions or the measurement
            Text(
                text = distanceText,
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                    .padding(16.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Reset Button
            Button(onClick = {
                // Clear the AR nodes and reset states
                // Left out: it clears the left-out 0.x `nodes` list.
                // nodes.clear()
                startPose = null
                endPose = null
                distanceText = "Tap a surface to place the first point"
            }) {
                Text("Reset Measurement")
            }
        }
    }
}
