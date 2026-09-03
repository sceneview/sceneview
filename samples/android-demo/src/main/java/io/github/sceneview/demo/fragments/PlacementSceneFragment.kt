package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.PlacementSceneDemo

/** Append-only fragment for the `placement-scene` demo. See [DemoFragment]. */
object PlacementSceneFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "placement-scene",
        titleRes = R.string.demo_placement_scene_title,
        subtitleRes = R.string.demo_placement_scene_subtitle,
        category = DemoCategory.AR_PLACEMENT,
        icon = Icons.Filled.AddLocationAlt,
        order = 16,
        tags = setOf("ar", "plane", "tap-to-place", "sceneform", "anchor"),
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        PlacementSceneDemo(onBack)
    }
}
