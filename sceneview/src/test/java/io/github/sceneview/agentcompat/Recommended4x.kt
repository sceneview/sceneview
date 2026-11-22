// The 4.x code that the deprecation messages of Compat2x.kt tell an agent to write instead of
// answers 1 and 3 (issue #5216). It must keep compiling: if an API it uses is renamed, update
// the messages in Compat2x.kt with it.
// Compile-only: nothing here runs.
package io.github.sceneview.agentcompat

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.sceneview.SceneView
import io.github.sceneview.rememberCameraManipulator
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader

@Composable
fun RecommendedProductViewer(modifier: Modifier = Modifier) {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    SceneView(
        modifier = modifier.fillMaxSize(),
        engine = engine,
        modelLoader = modelLoader,
        cameraManipulator = rememberCameraManipulator(),
    ) {
        rememberModelInstance(modelLoader, "models/model.glb")?.let {
            ModelNode(modelInstance = it, scaleToUnits = 1.0f)
        }
    }
}
