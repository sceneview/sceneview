package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Roofing
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.ARRooftopAnchorDemo

/** Append-only fragment for the `ar-rooftop` demo. See [DemoFragment]. */
object ArRooftopFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "ar-rooftop",
        titleRes = R.string.demo_ar_rooftop_title,
        subtitleRes = R.string.demo_ar_rooftop_subtitle,
        category = DemoCategory.AUGMENTED_REALITY,
        icon = Icons.Filled.Roofing,
        order = 46,
        tags = setOf("ar", "geospatial", "rooftop", "anchor"),
        status = io.github.sceneview.demo.DemoStatus.KnownIssue,
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        ARRooftopAnchorDemo(onBack)
    }
}
