package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Palette
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.ARDepthVisualizationDemo

/** Append-only fragment for the `ar-depth-visualization` demo. See [DemoFragment]. */
object ArDepthVisualizationFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "ar-depth-visualization",
        titleRes = R.string.demo_ar_depth_visualization_title,
        subtitleRes = R.string.demo_ar_depth_visualization_subtitle,
        category = DemoCategory.AUGMENTED_REALITY,
        icon = Icons.Filled.Palette,
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        ARDepthVisualizationDemo(onBack)
    }
}
