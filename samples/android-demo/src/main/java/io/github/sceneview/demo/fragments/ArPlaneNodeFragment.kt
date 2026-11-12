package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewQuilt
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.ARPlaneNodeDemo

/** Append-only fragment for the `ar-plane-node` demo. See [DemoFragment]. */
object ArPlaneNodeFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "ar-plane-node",
        sourcePath = "src/main/java/io/github/sceneview/demo/demos/ARPlaneNodeDemo.kt",
        titleRes = R.string.demo_ar_plane_node_title,
        subtitleRes = R.string.demo_ar_plane_node_subtitle,
        category = DemoCategory.DEV_TOOLS,
        icon = Icons.AutoMirrored.Filled.ViewQuilt,
        order = 35,
        addedIn = "4.13.0",
        updatedIn = "4.52.0",
        tags = setOf("plane lifecycle", "ar", "plane", "planenode", "lifecycle", "callback"),
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        ARPlaneNodeDemo(onBack)
    }
}
