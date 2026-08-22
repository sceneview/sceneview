package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lens
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.ARDepthOfFieldDemo

/** Append-only fragment for the `ar-depth-of-field` demo. See [DemoFragment]. */
object ArDepthOfFieldFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "ar-depth-of-field",
        titleRes = R.string.demo_ar_depth_of_field_title,
        subtitleRes = R.string.demo_ar_depth_of_field_subtitle,
        category = DemoCategory.AUGMENTED_REALITY,
        icon = Icons.Filled.Lens,
        order = 32,
        tags = setOf("ar", "depth", "bokeh", "focus", "post-fx"),
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        ARDepthOfFieldDemo(onBack)
    }
}
