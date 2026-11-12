package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Layers
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.ARDepthOcclusionDemo

/** Append-only fragment for the `ar-depth-occlusion` demo. See [DemoFragment]. */
object ArDepthOcclusionFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "ar-depth-occlusion",
        sourcePath = "src/main/java/io/github/sceneview/demo/demos/ARDepthOcclusionDemo.kt",
        titleRes = R.string.demo_ar_depth_occlusion_title,
        subtitleRes = R.string.demo_ar_depth_occlusion_subtitle,
        category = DemoCategory.UNDERSTAND,
        icon = Icons.Filled.Layers,
        order = 25,
        addedIn = "4.0.8",
        tags = setOf("depth occlusion", "ar", "depth", "occlusion", "arcore"),
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        ARDepthOcclusionDemo(onBack)
    }
}
