package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ViewInAr
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
        category = DemoCategory.PLACE_AR,
        icon = Icons.Filled.ViewInAr,
        order = 17,
        // 4.39.0: the cursor and the tap are gone — the model lands on the first
        // usable surface by itself (#3766, #3771).
        updatedIn = "4.39.0",
        tags = setOf("ar", "plane", "auto-place", "anchor", "gltf", "model", "floor", "wall", "tv"),
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        ARPlacementDemo(onBack)
    }
}
