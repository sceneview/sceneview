package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VideoStable
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.ARImageStabilizationDemo

/** Append-only fragment for the `ar-image-stabilization` demo. See [DemoFragment]. */
object ArImageStabilizationFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "ar-image-stabilization",
        sourcePath = "src/main/java/io/github/sceneview/demo/demos/ARImageStabilizationDemo.kt",
        titleRes = R.string.demo_ar_image_stabilization_title,
        subtitleRes = R.string.demo_ar_image_stabilization_subtitle,
        category = DemoCategory.DEV_TOOLS,
        icon = Icons.Filled.VideoStable,
        order = 40,
        addedIn = "4.0.8",
        tags = setOf("image stabilization", "ar", "camera", "stabilization", "eis"),
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        ARImageStabilizationDemo(onBack)
    }
}
