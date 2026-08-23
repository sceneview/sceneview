package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.ARPlacementDemo

/** Append-only fragment for the `ar-placement` demo. See [DemoFragment]. */
object ArPlacementFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "ar-placement",
        titleRes = R.string.demo_ar_placement_title,
        subtitleRes = R.string.demo_ar_placement_subtitle,
        category = DemoCategory.AUGMENTED_REALITY,
        icon = Icons.Filled.TouchApp,
        order = 6,
        tags = setOf("ar", "plane", "tap-to-place", "anchor", "gltf", "model"),
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        ARPlacementDemo(onBack)
    }
}
