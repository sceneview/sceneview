// SceneView 2.x code that an AI coding agent writes from memory — it must keep compiling.
//
// Source: the 2026-11-23 Gemini check (OpenRouter, no tools, no MCP server or skill, no web
// search, one answer per prompt). Gemini 3.1 Pro preview, prompt 1: "Add a screen that shows a GLB
// model the user can rotate, in Jetpack Compose." It recommended
// `io.github.sceneview:sceneview-compose:2.2.1`, a version that never existed: that artifact is
// on Maven Central from 4.27.0 only, and exposes `sceneview` at runtime scope only, so bumping
// the coordinate does not make this code compile (#5242). The code is the `sceneview` 2.x API.
// The 4.x shims are in Compat2x.kt; issue #5216.
//
// The code is verbatim, imports included. Changes, none of them SceneView API:
// - the `package` line;
// - the answer's "loading from a URL" variant is a single argument line, `modelInstance =
//   modelLoader.createModelInstance("https://...")`: it is wrapped in `GlbModelFromUrl` below, the
//   loader becoming the parameter (and its import) and the argument a local `val`.
// It compiled unchanged against 4.72.0: the `rememberNodes` and `Scene(childNodes = ...)` shims
// of #5217 cover it. The deprecation warnings it raises are left on purpose: they are what the
// shims tell the agent. Compile-only: nothing here runs. If this file stops compiling, a 2.x shim
// broke.
package io.github.sceneview.agentcompat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.github.sceneview.Scene
import io.github.sceneview.node.ModelNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberNodes
// Added: the parameter type of the wrapper around the URL variant.
import io.github.sceneview.loaders.ModelLoader

@Composable
fun GlbModelScreen() {
    // The Engine and Loaders are the core of Sceneview
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)

    // State to show a loading indicator until the model is ready
    var isLoading by remember { mutableStateOf(true) }

    // Create the Model Node
    val childNodes = rememberNodes {
        add(
            ModelNode(
                // Load the model from your assets folder
                modelInstance = modelLoader.createModelInstance("models/my_model.glb"),
                // Automatically scales the model to fit within a 1x1x1 unit cube
                scaleToUnits = 1.0f
            ).apply {
                // Optional: You can set a starting rotation or position here
                // rotation = Position(x = 0f, y = 45f, z = 0f)
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scene(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            modelLoader = modelLoader,
            childNodes = childNodes,
            onFrame = {
                // This block is called on every frame.
                // We set isLoading to false once the scene starts rendering.
                if (isLoading) isLoading = false
            }
        )

        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}

// The answer's URL variant (see the header): only its call shape is checked.
fun GlbModelFromUrl(modelLoader: ModelLoader) {
    val modelInstance = modelLoader.createModelInstance("https://example.com/my_model.glb")
}
