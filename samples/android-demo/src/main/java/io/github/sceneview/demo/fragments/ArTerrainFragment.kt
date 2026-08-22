package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.ARTerrainAnchorDemo

/** Append-only fragment for the `ar-terrain` demo. See [DemoFragment]. */
object ArTerrainFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "ar-terrain",
        titleRes = R.string.demo_ar_terrain_title,
        subtitleRes = R.string.demo_ar_terrain_subtitle,
        category = DemoCategory.AUGMENTED_REALITY,
        icon = Icons.Filled.Landscape,
        order = 45,
        tags = setOf("ar", "geospatial", "terrain", "anchor"),
        status = io.github.sceneview.demo.DemoStatus.KnownIssue,
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        ARTerrainAnchorDemo(onBack)
    }
}
