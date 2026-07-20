package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tv
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.DemoStatus
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.WallPlacementDemo

/** Append-only fragment for the `wall-placement` demo (#2740). See [DemoFragment]. */
object ArWallPlacementFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "wall-placement",
        titleRes = R.string.demo_wall_placement_title,
        subtitleRes = R.string.demo_wall_placement_subtitle,
        category = DemoCategory.AUGMENTED_REALITY,
        icon = Icons.Filled.Tv,
        // In review: shipped for on-device validation (#2740) — flip to Working
        // once the wall flow is signed off on hardware.
        status = DemoStatus.InReview,
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        WallPlacementDemo(onBack)
    }
}
