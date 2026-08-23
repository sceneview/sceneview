package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationCity
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.ARStreetscapeDemo

/** Append-only fragment for the `ar-streetscape` demo. See [DemoFragment]. */
object ArStreetscapeFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "ar-streetscape",
        titleRes = R.string.demo_ar_streetscape_title,
        subtitleRes = R.string.demo_ar_streetscape_subtitle,
        category = DemoCategory.AUGMENTED_REALITY,
        icon = Icons.Filled.LocationCity,
        order = 43,
        tags = setOf("ar", "geospatial", "streetscape", "building", "terrain"),
        status = io.github.sceneview.demo.DemoStatus.KnownIssue,
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        ARStreetscapeDemo(onBack)
    }
}
