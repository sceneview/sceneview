package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.SplatPreviewDemo

/**
 * Append-only fragment for the `splat-preview` demo — a real phone capture rendered as Gaussian
 * splats (#2646, rebuilt around a concrete capture in #3620). See [DemoFragment].
 */
object SplatPreviewFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "splat-preview",
        titleRes = R.string.demo_splat_preview_title,
        subtitleRes = R.string.demo_splat_preview_subtitle,
        category = DemoCategory.VIEW_3D,
        icon = Icons.Filled.Landscape,
        order = 2,
        tags = setOf("splat", "gaussian", "radiance-field", "point-cloud", "scan", "spz", "ply"),
        // #3620 replaced the procedural sphere with a real phone capture.
        updatedIn = "4.37.0",
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        SplatPreviewDemo(onBack)
    }
}
