package io.github.sceneview.web.nodes

import io.github.sceneview.web.bindings.FilamentAsset

/**
 * Model configuration for SceneView web.
 *
 * ```kotlin
 * model("models/moto_helmet.glb") {
 *     onLoaded { asset ->
 *         println("Model loaded: ${asset.getEntities()}")
 *     }
 * }
 * ```
 */
class ModelConfig(val url: String) {
    var onLoaded: ((FilamentAsset) -> Unit)? = null; private set
    var autoAnimate = true; private set
    var scale = 1.0f; private set

    fun onLoaded(block: (FilamentAsset) -> Unit) {
        onLoaded = block
    }

    fun autoAnimate(enabled: Boolean) {
        autoAnimate = enabled
    }

    fun scale(value: Float) {
        scale = value
    }
}
