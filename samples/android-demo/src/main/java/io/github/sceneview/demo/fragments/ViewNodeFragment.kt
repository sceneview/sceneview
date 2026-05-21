package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.ViewNodeDemo

/** Append-only fragment for the `view-node` demo. See [DemoFragment]. */
object ViewNodeFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "view-node",
        titleRes = R.string.demo_view_node_title,
        subtitleRes = R.string.demo_view_node_subtitle,
        category = DemoCategory.INTERACTION,
        icon = Icons.Filled.Dashboard,
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        ViewNodeDemo(onBack)
    }
}
