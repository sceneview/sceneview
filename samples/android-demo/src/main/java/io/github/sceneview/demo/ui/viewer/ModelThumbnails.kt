package io.github.sceneview.demo.ui.viewer

/**
 * Generated thumbnail resources, keyed by asset stem. The image pipeline fills this with
 * `res/drawable-nodpi/model_thumb_<name>.webp`; placeholders render until then.
 */
object ModelThumbnails {
    private val resources: Map<String, Int> = emptyMap()
    fun resourceFor(assetName: String): Int? = resources[assetName]
}

/** Same deferred-resource seam for bundled HDR environment thumbnails. */
object EnvironmentThumbnails {
    private val resources: Map<String, Int> = emptyMap()
    fun resourceFor(assetName: String): Int? = resources[assetName]
}
