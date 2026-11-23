// SceneView 2.x code that an AI coding agent writes from memory — what of it is SceneView 2.x API
// must keep compiling.
//
// Source: the 2026-11-23 Gemini check (OpenRouter, no tools, no MCP server or skill, no web
// search, one answer per prompt). Gemini 3.1 Pro preview, prompt 3: "Show a 3D model in a
// LazyColumn item without leaking the renderer." It recommended
// `io.github.sceneview:arsceneview:2.0.3` (for a non-AR viewer: every call below is `sceneview`
// API, so the fixture lives in this module) and wrote the code below. The 4.x shims are in
// Compat2x.kt; issue #5216.
//
// The code is verbatim, imports included. Changes, none of them SceneView API:
// - the `package` line;
// - the `onDispose = { ... }` argument is commented out under a `// Left out:` line: no SceneView
//   release ever had it, so it gets no shim. Its body (destroying the remembered engine and
//   loader by hand) is not needed in 4.x either: `rememberEngine` and `rememberModelLoader`
//   destroy them when the composable leaves;
// - the answer's last snippet (an `AndroidView` around `MyLegacy3DView`, a class it does not
//   define, "if you are using an older library") is left out: it calls no SceneView API.
// Everything else compiles unchanged against 4.72.0 through the `rememberNodes` and
// `Scene(childNodes = ...)` shims of #5217. `createModelInstance` runs in a `LaunchedEffect`,
// which is on the main thread, as Filament requires. The deprecation warnings it raises are left
// on purpose: they are what the shims tell the agent.
// Compile-only: nothing here runs. If this file stops compiling, a 2.x shim broke.
package io.github.sceneview.agentcompat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.sceneview.Scene
import io.github.sceneview.math.Position
import io.github.sceneview.node.ModelNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberNodes

@Composable
fun LazyColumn3DList(modelUrls: List<String>) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(modelUrls) { url ->
            Lazy3DItem(modelUrl = url)
        }
    }
}

@Composable
fun Lazy3DItem(modelUrl: String) {
    // STATE: Keep track of whether this specific item is active
    var is3DActive by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(300.dp)
            .padding(8.dp)
            .background(Color.LightGray)
    ) {
        if (is3DActive) {
            // Render the 3D Model
            Safe3DViewer(modelUrl = modelUrl)

            // Button to close and free memory
            Text(
                text = "Close 3D",
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .clickable { is3DActive = false }
            )
        } else {
            // Show Placeholder / Thumbnail when scrolled or inactive
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable { is3DActive = true },
                contentAlignment = Alignment.Center
            ) {
                Text("Tap to load 3D Model")
            }
        }
    }
}

@Composable
fun Safe3DViewer(modelUrl: String) {
    // 1. Create engine and loaders scoped to THIS specific Composable
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val childNodes = rememberNodes()

    // 2. Load the model safely
    LaunchedEffect(modelUrl) {
        val modelInstance = modelLoader.createModelInstance(
            assetFileLocation = modelUrl
        )
        if (modelInstance != null) {
            val modelNode = ModelNode(
                modelInstance = modelInstance,
                scaleToUnits = 1.0f,
                centerOrigin = Position(x = 0f, y = 0f, z = 0f)
            )
            childNodes.add(modelNode)
        }
    }

    // 3. The SceneView Compose Wrapper
    Scene(
        modifier = Modifier.fillMaxSize(),
        engine = engine,
        modelLoader = modelLoader,
        childNodes = childNodes,
        // Left out: hallucinated. No SceneView release has an `onDispose` parameter on Scene / SceneView.
        // onDispose = {
        //     // --- CRITICAL ANTI-LEAK SECTION ---
        //     // This block runs when the item scrolls off-screen or is closed
        //     childNodes.clear()
        //     modelLoader.destroy()
        //     engine.destroy() // Destroys Filament native pointers
        // }
    )
}
