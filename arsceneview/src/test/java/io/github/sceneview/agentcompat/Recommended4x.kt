// The 4.x code that the deprecation message of ARCompat2x.kt tells an agent to write instead of
// answer 2 (issue #5216). It must keep compiling: if an API it uses is renamed, update the
// message in ARCompat2x.kt with it.
// Compile-only: nothing here runs.
package io.github.sceneview.agentcompat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.google.ar.core.Anchor
import com.google.ar.core.Frame
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberOnGestureListener

@Composable
fun RecommendedFurnitureArScreen() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val anchors = remember { mutableStateListOf<Anchor>() }
    var frame by remember { mutableStateOf<Frame?>(null) }
    ARSceneView(
        engine = engine,
        modelLoader = modelLoader,
        onSessionUpdated = { _, updatedFrame -> frame = updatedFrame },
        onGestureListener = rememberOnGestureListener(
            onSingleTapConfirmed = { e, _ ->
                frame?.hitTest(e.x, e.y)?.firstOrNull()?.let { hitResult ->
                    anchors += hitResult.createAnchor()
                }
            }
        ),
    ) {
        anchors.forEach { anchor ->
            AnchorNode(anchor) {
                rememberModelInstance(modelLoader, "models/model.glb")?.let {
                    ModelNode(modelInstance = it)
                }
            }
        }
    }
}
