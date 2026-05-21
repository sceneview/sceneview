package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Collections
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
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
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        SceneGalleryDemo(onBack)
    }
}
