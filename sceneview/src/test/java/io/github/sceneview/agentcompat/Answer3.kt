// SceneView 2.x code that an AI coding agent writes from memory — it must keep compiling.
//
// Source: answer 3 of the 2026-11-22 agent measurement. Claude Code, with no tools and no MCP,
// was asked: "How do I display a .glb file in a Jetpack Compose screen?" It recommended
// `io.github.sceneview:sceneview:2.3.0` and wrote the code below. Agents keep emitting this 2.x
// spelling, so SceneView 4.x keeps it compiling through deprecated shims whose warning
// gives the 4.x spelling (see Compat2x.kt; issue #5216).
//
// The code is verbatim, SceneView imports included. Additions, none of them SceneView API:
// - the `package` line;
// - the three Compose imports the answer left out (`Composable`, `Modifier`, `fillMaxSize`),
//   which any IDE adds on paste.
// The deprecation warnings it raises are left on purpose: they are what the shims tell the agent.
// Compile-only: nothing here runs. If this file stops compiling, a 2.x shim broke.
package io.github.sceneview.agentcompat

// Added: Compose imports missing from the answer.
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
// Verbatim from the answer.
import io.github.sceneview.Scene
import io.github.sceneview.math.Position
import io.github.sceneview.node.ModelNode
import io.github.sceneview.rememberCameraManipulator
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberNode

@Composable
fun ModelScreen() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val cameraNode = rememberCameraNode(engine) {
        position = Position(z = 4.0f)
    }

    Scene(
        modifier = Modifier.fillMaxSize(),
        engine = engine,
        modelLoader = modelLoader,
        cameraNode = cameraNode,
        // Lets the user orbit, pan and zoom with touch gestures
        cameraManipulator = rememberCameraManipulator(),
        childNodes = listOf(
            rememberNode {
                ModelNode(
                    modelInstance = modelLoader.createModelInstance(
                        assetFileLocation = "models/my_model.glb"
                    ),
                    scaleToUnits = 1.0f // resize the model to fit in a 1-unit cube
                )
            }
        )
    )
}
