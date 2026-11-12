package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ScatterPlot
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.ARRawDepthPointCloudDemo

/** Append-only fragment for the `ar-raw-depth-point-cloud` demo. See [DemoFragment]. */
object ArRawDepthPointCloudFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "ar-raw-depth-point-cloud",
        sourcePath = "src/main/java/io/github/sceneview/demo/demos/ARRawDepthPointCloudDemo.kt",
        titleRes = R.string.demo_ar_raw_depth_cloud_title,
        subtitleRes = R.string.demo_ar_raw_depth_cloud_subtitle,
        category = DemoCategory.DEV_TOOLS,
        icon = Icons.Filled.ScatterPlot,
        order = 39,
        addedIn = "4.11.1",
        tags = setOf("raw depth point cloud", "ar", "depth", "raw-depth", "point-cloud", "confidence"),
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        ARRawDepthPointCloudDemo(onBack)
    }
}
