package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.DemoStatus
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.ARGeospatialAnchorsDemo

/**
 * Append-only fragment for the `ar-geospatial-anchors` demo, which absorbed the
 * retired `ar-terrain` and `ar-rooftop` cards (#2239). See [DemoFragment].
 *
 * Status stays [DemoStatus.KnownIssue]: both absorbed demos carried it, and the
 * merge changed no runtime behaviour, so claiming Working here would be the
 * badge lying about a screen nobody re-verified outdoors.
 */
object ArGeospatialAnchorsFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "ar-geospatial-anchors",
        titleRes = R.string.demo_ar_geospatial_anchors_title,
        subtitleRes = R.string.demo_ar_geospatial_anchors_subtitle,
        category = DemoCategory.AR_ANCHORS,
        icon = Icons.Filled.Landscape,
        order = 44,
        tags = setOf("ar", "geospatial", "terrain", "rooftop", "anchor", "vps", "earth"),
        status = DemoStatus.KnownIssue,
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        ARGeospatialAnchorsDemo(onBack)
    }
}
