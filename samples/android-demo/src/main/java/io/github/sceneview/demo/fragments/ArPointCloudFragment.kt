package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.ARPointCloudDemo

/** Append-only fragment for the `ar-point-cloud` demo. See [DemoFragment]. */
object ArPointCloudFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "ar-point-cloud",
        titleRes = R.string.demo_ar_point_cloud_title,
        subtitleRes = R.string.demo_ar_point_cloud_subtitle,
        category = DemoCategory.AUGMENTED_REALITY,
        icon = Icons.Filled.BlurOn,
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        ARPointCloudDemo(onBack)
    }
}
