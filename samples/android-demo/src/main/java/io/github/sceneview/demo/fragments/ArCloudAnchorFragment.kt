package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudCircle
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.DemoMode
import io.github.sceneview.demo.DemoModeHost
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.ARCloudAnchorDemo
import io.github.sceneview.demo.demos.ARCollaborativeDemo

/**
 * Append-only fragment for the `ar-cloud-anchor` demo. See [DemoFragment].
 *
 * Samples step 0 added `ar-collaborative` as the Collaborative mode (`?tab=collaborative`).
 */
object ArCloudAnchorFragment : DemoFragment {
    val modes = listOf(
        DemoMode("cloud-anchors", R.string.demo_mode_cloud_anchors),
        DemoMode("collaborative", R.string.demo_mode_collaborative),
    )

    override val entry: DemoEntry = DemoEntry(
        id = "ar-cloud-anchor",
        sourcePath = "src/main/java/io/github/sceneview/demo/demos/ARCloudAnchorDemo.kt",
        titleRes = R.string.demo_ar_cloud_anchor_title,
        subtitleRes = R.string.demo_ar_cloud_anchor_subtitle,
        category = DemoCategory.DEV_TOOLS,
        icon = Icons.Filled.CloudCircle,
        order = 40,
        addedIn = "4.0.0",
        updatedIn = "4.56.0",
        tags = setOf(
            "cloud anchors",
            "ar", "cloud-anchor", "multi-user", "persistence", "arcore",
            "sync", "collaboration", "transport",
        ),
        // #3421 rebuilt this screen as an explicit two-step flow. The state machine is
        // unit-tested and every visual state is captured on the emulator, but hosting and
        // resolving themselves need ARCore and a live Cloud project, which no emulator
        // has (#2754) — so it stays In review until a device pass signs it off.
        status = io.github.sceneview.demo.DemoStatus.InReview,
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        DemoModeHost(
            modes = modes,
            tabToMode = mapOf(0 to 0, 1 to 1),
        ) { mode ->
            when (mode) {
                1 -> ARCollaborativeDemo(onBack)
                else -> ARCloudAnchorDemo(onBack)
            }
        }
    }
}
