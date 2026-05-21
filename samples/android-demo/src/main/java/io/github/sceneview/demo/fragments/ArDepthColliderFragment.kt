package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ScatterPlot
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.ARDepthColliderDemo

/** Append-only fragment for the `ar-depth-collider` demo. See [DemoFragment]. */
object ArDepthColliderFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "ar-depth-collider",
        titleRes = R.string.demo_ar_depth_collider_title,
        subtitleRes = R.string.demo_ar_depth_collider_subtitle,
        category = DemoCategory.AUGMENTED_REALITY,
        icon = Icons.Filled.ScatterPlot,
        status = io.github.sceneview.demo.DemoStatus.KnownIssue,
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        ARDepthColliderDemo(onBack)
    }
}
