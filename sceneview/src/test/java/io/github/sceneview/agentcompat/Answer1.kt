// SceneView 2.x code that an AI coding agent writes from memory — it must keep compiling.
//
// Source: answer 1 of the 2026-11-22 agent measurement. Claude Code, with no tools and no MCP,
// was asked: "I want to add 3D to my Android app (Kotlin, Jetpack Compose): show a product as a
// 3D model the user can rotate and zoom. Which library should I use? Give the Gradle dependency
// and a minimal example." It recommended `io.github.sceneview:sceneview:2.3.0` and wrote the code
// below. Agents keep emitting this 2.x spelling, so SceneView 4.x keeps it compiling through
// deprecated shims whose warning gives the 4.x spelling (see Compat2x.kt; issue #5216).
//
// The code is verbatim, imports included. The only addition is the `package` line. The
// deprecation warnings it raises are left on purpose: they are what the shims tell the agent.
// Compile-only: nothing here runs. If this file stops compiling, a 2.x shim broke.
package io.github.sceneview.agentcompat

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.sceneview.Scene
import io.github.sceneview.node.ModelNode
import io.github.sceneview.rememberCameraManipulator
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberNodes

@Composable
fun ProductViewer(modifier: Modifier = Modifier) {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)

    Scene(
        modifier = modifier.fillMaxSize(),
        engine = engine,
        modelLoader = modelLoader,
        // One-finger drag = orbit, pinch = zoom, two-finger drag = pan
        cameraManipulator = rememberCameraManipulator(),
        childNodes = rememberNodes {
            add(
                ModelNode(
                    modelInstance = modelLoader.createModelInstance(
                        assetFileLocation = "models/product.glb"
                    ),
                    scaleToUnits = 1.0f,   // normalize size so any model fits the view
                    centerOrigin = io.github.sceneview.math.Position(0f, 0f, 0f) // center it
                )
            )
        }
    )
}
