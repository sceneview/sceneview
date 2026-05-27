package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Hexagon
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.CustomGeometryDemo

/**
 * Unified "Custom Geometry" demo — consolidates the retired `custom-mesh` and
 * `shape` demos behind one entry with an internal segmented-button toggle
 * (#2239 Batch 1). The old `custom-mesh` and `shape` deep-link ids stay
 * routable through [io.github.sceneview.demo.DeepLinkRouter.DEMO_ID_ALIASES].
 */
object CustomGeometryFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "custom-geometry",
        titleRes = R.string.demo_custom_geometry_title,
        subtitleRes = R.string.demo_custom_geometry_subtitle,
        category = DemoCategory.ADVANCED,
        icon = Icons.Filled.Hexagon,
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        CustomGeometryDemo(onBack)
    }
}
