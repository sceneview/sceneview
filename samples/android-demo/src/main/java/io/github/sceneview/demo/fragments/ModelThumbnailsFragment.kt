package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Collections
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.ModelThumbnailsDemo

/**
 * Append-only fragment for the `model-thumbnails` demo — a model library whose thumbnails are
 * rendered off screen by `ModelSnapshot`. See [DemoFragment].
 */
object ModelThumbnailsFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "model-thumbnails",
        titleRes = R.string.demo_model_thumbnails_title,
        subtitleRes = R.string.demo_model_thumbnails_subtitle,
        category = DemoCategory.DEV_TOOLS,
        icon = Icons.Filled.Collections,
        order = 13,
        addedIn = "4.63.0",
        tags = setOf("thumbnail", "snapshot", "offscreen", "bitmap", "library", "grid", "preview"),
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        ModelThumbnailsDemo(onBack)
    }
}
