package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.ARPoseDemo

/** Append-only fragment for the `ar-pose` demo. See [DemoFragment]. */
object ArPoseFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "ar-pose",
        titleRes = R.string.demo_ar_pose_title,
        subtitleRes = R.string.demo_ar_pose_subtitle,
        category = DemoCategory.AUGMENTED_REALITY,
        icon = Icons.Filled.MyLocation,
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        ARPoseDemo(onBack)
    }
}
