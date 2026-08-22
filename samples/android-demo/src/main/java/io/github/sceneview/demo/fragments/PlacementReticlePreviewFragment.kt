package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.PlacementReticlePreviewDemo

/** Append-only fragment for the `placement-reticle-preview` demo. See [DemoFragment]. */
object PlacementReticlePreviewFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "placement-reticle-preview",
        titleRes = R.string.demo_placement_reticle_preview_title,
        subtitleRes = R.string.demo_placement_reticle_preview_subtitle,
        category = DemoCategory.AUGMENTED_REALITY,
        icon = Icons.Filled.GpsFixed,
        order = 20,
        tags = setOf("ar", "reticle", "placement", "shadow", "preview", "no-camera"),
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        PlacementReticlePreviewDemo(onBack)
    }
}
