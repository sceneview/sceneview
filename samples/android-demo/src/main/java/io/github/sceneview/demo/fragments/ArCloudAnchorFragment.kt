package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudCircle
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.ARCloudAnchorDemo

/** Append-only fragment for the `ar-cloud-anchor` demo. See [DemoFragment]. */
object ArCloudAnchorFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "ar-cloud-anchor",
        titleRes = R.string.demo_ar_cloud_anchor_title,
        subtitleRes = R.string.demo_ar_cloud_anchor_subtitle,
        category = DemoCategory.AUGMENTED_REALITY,
        icon = Icons.Filled.CloudCircle,
        order = 41,
        tags = setOf("ar", "cloud-anchor", "multi-user", "persistence", "arcore"),
        // #3421 rebuilt this screen as an explicit two-step flow. The state machine is
        // unit-tested and every visual state is captured on the emulator, but hosting and
        // resolving themselves need ARCore and a live Cloud project, which no emulator
        // has (#2754) — so it stays In review until a device pass signs it off.
        status = io.github.sceneview.demo.DemoStatus.InReview,
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        ARCloudAnchorDemo(onBack)
    }
}
