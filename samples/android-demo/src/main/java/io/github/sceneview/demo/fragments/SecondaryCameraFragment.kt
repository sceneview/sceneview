package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.SecondaryCameraDemo

/** Append-only fragment for the `secondary-camera` demo. See [DemoFragment]. */
object SecondaryCameraFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "secondary-camera",
        titleRes = R.string.demo_secondary_camera_title,
        subtitleRes = R.string.demo_secondary_camera_subtitle,
        category = DemoCategory.ADVANCED,
        icon = Icons.Filled.PictureInPicture,
        order = 18,
        tags = setOf("camera", "pip", "multi-view", "render-target"),
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        SecondaryCameraDemo(onBack)
    }
}
