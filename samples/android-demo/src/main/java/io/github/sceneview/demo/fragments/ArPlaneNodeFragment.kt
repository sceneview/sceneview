package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Layers
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.ARPlaneNodeDemo

/** Append-only fragment for the `ar-plane-node` demo. See [DemoFragment]. */
object ArPlaneNodeFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "ar-plane-node",
        titleRes = R.string.demo_ar_plane_node_title,
        subtitleRes = R.string.demo_ar_plane_node_subtitle,
        category = DemoCategory.AUGMENTED_REALITY,
        icon = Icons.Filled.Layers,
        order = 28,
        tags = setOf("ar", "plane", "planenode", "lifecycle", "callback"),
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        ARPlaneNodeDemo(onBack)
    }
}
