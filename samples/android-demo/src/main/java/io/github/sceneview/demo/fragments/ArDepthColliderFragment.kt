package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SportsBasketball
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.ARDepthColliderDemo

/** Append-only fragment for the `ar-depth-collider` demo. See [DemoFragment]. */
object ArDepthColliderFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "ar-depth-collider",
        titleRes = R.string.demo_ar_depth_collider_title,
        subtitleRes = R.string.demo_ar_depth_collider_subtitle,
        category = DemoCategory.UNDERSTAND,
        icon = Icons.Filled.SportsBasketball,
        order = 42,
        addedIn = "4.11.1",
        tags = setOf("ar", "depth", "physics", "collision", "rigid-body"),
        status = io.github.sceneview.demo.DemoStatus.KnownIssue,
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        ARDepthColliderDemo(onBack)
    }
}
