package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.ARImageDemo

/** Append-only fragment for the `ar-image` demo. See [DemoFragment]. */
object ArImageFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "ar-image",
        titleRes = R.string.demo_ar_image_title,
        subtitleRes = R.string.demo_ar_image_subtitle,
        category = DemoCategory.AR_TRACKING,
        icon = Icons.Filled.Image,
        order = 24,
        tags = setOf("ar", "image", "tracking", "augmented-image", "marker"),
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        ARImageDemo(onBack)
    }
}
