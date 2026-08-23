package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.LinesPathsDemo

/** Append-only fragment for the `lines-paths` demo. See [DemoFragment]. */
object LinesPathsFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "lines-paths",
        titleRes = R.string.demo_lines_paths_title,
        subtitleRes = R.string.demo_lines_paths_subtitle,
        category = DemoCategory.CONTENT,
        icon = Icons.Filled.Timeline,
        order = 12,
        tags = setOf("line", "polyline", "path", "helix", "grid", "circle"),
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        LinesPathsDemo(onBack)
    }
}
