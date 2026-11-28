@file:Suppress("DEPRECATION", "unused")

package io.github.sceneview.ar.publicapi

import androidx.compose.runtime.Composable
import com.google.ar.core.Config
import io.github.sceneview.ar.ARScene
import io.github.sceneview.ar.ARSceneView

/** Compile both entry points, including a trailing node block and a plane-only opt-out. */
@Composable
private fun planeDepthOcclusionCalls() {
    ARSceneView(depthMode = Config.DepthMode.AUTOMATIC) { }
    ARSceneView(depthMode = Config.DepthMode.RAW_DEPTH_ONLY, planeDepthOcclusion = false) { }
    ARScene(planeDepthOcclusion = false, sessionConfiguration = { _, config ->
        config.depthMode = Config.DepthMode.AUTOMATIC
    }) { }
}
