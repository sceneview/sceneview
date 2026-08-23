package io.github.sceneview.demo.ui.viewer

import io.github.sceneview.demo.R

/**
 * Generated thumbnail resources, keyed by asset stem. The image pipeline fills this with
 * `res/drawable-nodpi/model_thumb_<name>.webp`; placeholders render until then.
 */
object ModelThumbnails {
    private val resources: Map<String, Int> = mapOf(
        "khronos_damaged_helmet" to R.drawable.model_thumb_khronos_damaged_helmet,
        "khronos_fox" to R.drawable.model_thumb_khronos_fox,
        "khronos_lantern" to R.drawable.model_thumb_khronos_lantern,
        "khronos_toy_car" to R.drawable.model_thumb_khronos_toy_car,
        "shiba" to R.drawable.model_thumb_shiba,
        "threejs_soldier" to R.drawable.model_thumb_threejs_soldier,
    )
    fun resourceFor(assetName: String): Int? = resources[assetName]
}

/** Same deferred-resource seam for bundled HDR environment thumbnails. */
object EnvironmentThumbnails {
    private val resources: Map<String, Int> = mapOf(
        "studio" to R.drawable.env_thumb_studio,
        "studio_warm" to R.drawable.env_thumb_studio_warm,
        "sunset" to R.drawable.env_thumb_sunset,
        "chinese_garden" to R.drawable.env_thumb_chinese_garden,
        "outdoor_cloudy" to R.drawable.env_thumb_outdoor_cloudy,
        "night_sky" to R.drawable.env_thumb_night_sky,
        "rooftop_night" to R.drawable.env_thumb_rooftop_night,
    )
    fun resourceFor(assetName: String): Int? = resources[assetName]
}
