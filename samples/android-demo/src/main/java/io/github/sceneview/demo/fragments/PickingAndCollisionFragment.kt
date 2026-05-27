package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.PickingAndCollisionDemo

/**
 * Unified "Picking & Collision" demo — consolidates the retired `collision` and
 * `view-node` demos behind one entry with an internal segmented-button toggle
 * (#2239 Batch 1). The old `collision` and `view-node` deep-link ids stay
 * routable through [io.github.sceneview.demo.DeepLinkRouter.DEMO_ID_ALIASES].
 */
object PickingAndCollisionFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "picking-collision",
        titleRes = R.string.demo_picking_collision_title,
        subtitleRes = R.string.demo_picking_collision_subtitle,
        category = DemoCategory.INTERACTION,
        icon = Icons.Filled.CenterFocusStrong,
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        PickingAndCollisionDemo(onBack)
    }
}
