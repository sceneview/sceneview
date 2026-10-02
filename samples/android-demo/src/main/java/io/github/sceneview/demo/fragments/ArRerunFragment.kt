package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Polyline
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.ARRerunDemo

/** Append-only fragment for the `ar-rerun` demo. See [DemoFragment]. */
object ArRerunFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "ar-rerun",
        titleRes = R.string.demo_ar_rerun_title,
        subtitleRes = R.string.demo_ar_rerun_subtitle,
        category = DemoCategory.DEV_TOOLS,
        icon = Icons.Filled.Polyline,
        order = 12,
        addedIn = "4.0.1",
        updatedIn = "4.49.0",
        tags = setOf("ar", "rerun", "replay", "3d", "streaming", "pose", "plane", "point cloud", "debug"),
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        ARRerunDemo(onBack)
    }
}
