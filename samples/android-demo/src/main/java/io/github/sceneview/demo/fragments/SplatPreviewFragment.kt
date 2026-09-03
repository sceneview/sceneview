package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ScatterPlot
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.SplatPreviewDemo

/** Append-only fragment for the `splat-preview` demo (3D Gaussian Splatting, #2646). See [DemoFragment]. */
object SplatPreviewFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "splat-preview",
        titleRes = R.string.demo_splat_preview_title,
        subtitleRes = R.string.demo_splat_preview_subtitle,
        category = DemoCategory.VIEWER,
        icon = Icons.Filled.ScatterPlot,
        order = 2,
        tags = setOf("splat", "gaussian", "radiance-field", "point-cloud", "ply"),
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        SplatPreviewDemo(onBack)
    }
}
