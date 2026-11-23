// SceneView 2.x code that an AI coding agent writes from memory — what of it is SceneView 2.x API
// must keep compiling.
//
// Source: the 2026-11-23 Gemini check (OpenRouter, no tools, no MCP server or skill, no web
// search, one answer per prompt). Gemini 3.1 Pro preview, prompt 5: "Load a 3D model from a URL
// with a loading state and an error state, in an Android app with Jetpack Compose." It
// recommended `io.github.sceneview:sceneview-compose:2.2.1`, a version that never existed: that
// artifact is on Maven Central from 4.27.0 only, and exposes `sceneview` at runtime scope only, so
// bumping the coordinate does not make this code compile (#5242). The code is the `sceneview` 2.x
// API; the answer wrote it as below. The 4.x shims are in Compat2x.kt;
// issue #5216.
//
// The code is verbatim, imports included. Changes, none of them SceneView API:
// - the `package` line; the answer's state class (`ModelLoadState`, its own section) and its
//   usage snippet (`MainScreen`) merged into this file;
// - the lines that compile against no SceneView 2.x release are commented out, each under a
//   `// Left out:` line that says why: no `ModelNode` constructor ever took a null model, and
//   assigning `modelInstance` is the 0.x API. They get no shim. The `childNodes` argument that
//   lists the left-out node goes with it, so `Scene` resolves to its 4.x-shaped alias.
// Everything else compiles unchanged against 4.72.0, `loadModelInstance(url)` included. The
// deprecation warning it raises is left on purpose: it is what the alias tells the agent.
// Compile-only: nothing here runs. If this file stops compiling, a 2.x alias broke.
package io.github.sceneview.agentcompat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.sceneview.Scene
import io.github.sceneview.node.ModelNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberModelLoader

sealed interface ModelLoadState {
    data object Loading : ModelLoadState
    data object Success : ModelLoadState
    data class Error(val message: String) : ModelLoadState
}

@Composable
fun Network3DViewer(
    modelUrl: String,
    modifier: Modifier = Modifier
) {
    // Track the loading state
    var loadState by remember { mutableStateOf<ModelLoadState>(ModelLoadState.Loading) }

    // Initialize Sceneview Engine and Loaders
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine = engine)
    val environmentLoader = rememberEnvironmentLoader(engine = engine)

    // Left out: hallucinated. No ModelNode constructor ever accepted a null modelInstance (0.9.5–1.1.0 only had a nullable `var`).
    // // Create the Node that will hold the 3D model
    // val modelNode = remember {
    //     ModelNode(
    //         modelInstance = null,
    //         scaleToUnits = 1.0f // Scales the model to fit a 1x1x1 unit cube
    //     )
    // }

    // Coroutine to load the model asynchronously
    LaunchedEffect(modelUrl) {
        loadState = ModelLoadState.Loading
        try {
            // Sceneview handles the network download automatically
            val modelInstance = modelLoader.loadModelInstance(modelUrl)

            if (modelInstance != null) {
                // Left out: 0.x API (sceneview 0.9.5–1.1.0 `var modelInstance`); a `val` since 2.0, and its node is left out.
                // modelNode.modelInstance = modelInstance
                loadState = ModelLoadState.Success
            } else {
                loadState = ModelLoadState.Error("Model parsed as null.")
            }
        } catch (e: Exception) {
            loadState = ModelLoadState.Error(e.localizedMessage ?: "Failed to load model")
        }
    }

    // UI Layout
    Box(modifier = modifier.fillMaxSize()) {
        // The actual 3D Scene
        Scene(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            modelLoader = modelLoader,
            // Left out: it lists the left-out `modelNode`.
            // childNodes = listOf(modelNode),
            // Optional: Provide an HDR environment for realistic lighting
            // environment = environmentLoader.createHDREnvironment("https://sceneview.github.io/assets/environments/syferfontein_18d_clear_1k.hdr")
        )

        // Overlay UI based on the current state
        when (val state = loadState) {
            is ModelLoadState.Loading -> {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center)
                )
            }
            is ModelLoadState.Error -> {
                Text(
                    text = "Error: ${state.message}",
                    color = Color.Red,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(16.dp)
                )
            }
            is ModelLoadState.Success -> {
                // Render nothing over the model when successful
            }
        }
    }
}

@Composable
fun MainScreen() {
    val sampleModelUrl = "https://sceneview.github.io/assets/models/DamagedHelmet.glb"

    Network3DViewer(
        modelUrl = sampleModelUrl,
        modifier = Modifier.fillMaxSize()
    )
}
