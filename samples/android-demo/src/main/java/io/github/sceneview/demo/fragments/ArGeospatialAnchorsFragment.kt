package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.DemoStatus
import io.github.sceneview.demo.DemoMode
import io.github.sceneview.demo.DemoModeHost
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.ARStreetscapeDemo
import io.github.sceneview.demo.demos.ARGeospatialAnchorsDemo

/**
 * Append-only fragment for the `ar-geospatial-anchors` demo, which absorbed the
 * retired `ar-terrain` and `ar-rooftop` cards (#2239). See [DemoFragment].
 *
 * Status stays [DemoStatus.KnownIssue]: both absorbed demos carried it, and the
 * merge changed no runtime behaviour, so claiming Working here would be the
 * badge lying about a screen nobody re-verified outdoors.
 *
 * Samples step 0 moved Streetscape here from `ar-scene-mesh` as the second mode
 * (`?tab=streetscape`, launch tab 2; the retired `ar-streetscape` link lands on it). Launch
 * tabs 0 and 1 stay with [ARGeospatialAnchorsDemo]: terrain and rooftop.
 */
object ArGeospatialAnchorsFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "ar-geospatial-anchors",
        titleRes = R.string.demo_ar_geospatial_anchors_title,
        subtitleRes = R.string.demo_ar_geospatial_anchors_subtitle,
        category = DemoCategory.PLACE_AR,
        icon = Icons.Filled.Explore,
        order = 19,
        addedIn = "4.35.0",
        updatedIn = "4.51.0",
        tags = setOf(
            "ar", "geospatial", "terrain", "rooftop", "anchor", "vps", "earth",
            "streetscape", "mesh", "building", "classification",
        ),
        status = DemoStatus.KnownIssue,
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        DemoModeHost(
            modes = listOf(
                DemoMode("anchors", R.string.demo_mode_anchors),
                DemoMode("streetscape", R.string.demo_mode_streetscape),
            ),
            tabToMode = mapOf(STREETSCAPE_TAB to 1),
            defaultModeReadsTab = true,
        ) { mode ->
            when (mode) {
                1 -> ARStreetscapeDemo(onBack)
                else -> ARGeospatialAnchorsDemo(onBack)
            }
        }
    }
}

/** Launch tab of the Streetscape mode; 0 and 1 are terrain and rooftop anchors. */
private const val STREETSCAPE_TAB = 2
