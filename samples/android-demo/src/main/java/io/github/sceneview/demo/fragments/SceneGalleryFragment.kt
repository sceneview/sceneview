package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Collections
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.DemoStatus
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.SceneGalleryDemo

/** Append-only fragment for the `scene-gallery` demo. See [DemoFragment]. */
object SceneGalleryFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "scene-gallery",
        titleRes = R.string.demo_scene_gallery_title,
        subtitleRes = R.string.demo_scene_gallery_subtitle,
        category = DemoCategory.BASICS_3D,
        icon = Icons.Filled.Collections,
        // The `gallery` Sketchfab slugs are now real, validated, downloadable
        // models (#2095) — the demo streams them successfully and falls back to
        // bundled assets when offline. The #2088 known-issue chip is removed.
        status = DemoStatus.Working,
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        SceneGalleryDemo(onBack)
    }
}
