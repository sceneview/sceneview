package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Texture
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.ARImageStabilizationDemo

/** Append-only fragment for the `ar-image-stabilization` demo. See [DemoFragment]. */
object ArImageStabilizationFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "ar-image-stabilization",
        titleRes = R.string.demo_ar_image_stabilization_title,
        subtitleRes = R.string.demo_ar_image_stabilization_subtitle,
        category = DemoCategory.AUGMENTED_REALITY,
        icon = Icons.Filled.Texture,
        order = 40,
        tags = setOf("ar", "camera", "stabilization", "eis"),
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        ARImageStabilizationDemo(onBack)
    }
}
