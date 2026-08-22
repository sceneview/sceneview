package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.ARInstantPlacementDemo

/** Append-only fragment for the `ar-instant-placement` demo. See [DemoFragment]. */
object ArInstantPlacementFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "ar-instant-placement",
        titleRes = R.string.demo_ar_instant_placement_title,
        subtitleRes = R.string.demo_ar_instant_placement_subtitle,
        category = DemoCategory.AUGMENTED_REALITY,
        icon = Icons.Filled.Bolt,
        order = 23,
        tags = setOf("ar", "instant-placement", "plane", "anchor", "arcore"),
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        ARInstantPlacementDemo(onBack)
    }
}
