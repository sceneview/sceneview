package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.ImageDemo

/** Append-only fragment for the `image` demo. See [DemoFragment]. */
object ImageFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "image",
        titleRes = R.string.demo_image_title,
        subtitleRes = R.string.demo_image_subtitle,
        category = DemoCategory.CONTENT,
        icon = Icons.Filled.Image,
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        ImageDemo(onBack)
    }
}
