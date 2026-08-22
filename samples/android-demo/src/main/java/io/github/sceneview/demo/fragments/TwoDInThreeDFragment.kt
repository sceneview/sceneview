package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Layers
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.TwoDInThreeDDemo

/**
 * Unified "2D in 3D" demo — consolidates the retired `text`, `image`, `video`,
 * and `billboard` demos behind one entry with an internal segmented-button
 * toggle (#2239 Batch 1). The old deep-link ids stay routable through
 * [io.github.sceneview.demo.DeepLinkRouter.DEMO_ID_ALIASES].
 */
object TwoDInThreeDFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "two-d-in-three-d",
        titleRes = R.string.demo_two_d_in_three_d_title,
        subtitleRes = R.string.demo_two_d_in_three_d_subtitle,
        category = DemoCategory.CONTENT,
        icon = Icons.Filled.Layers,
        order = 11,
        tags = setOf("2d", "text", "image", "video", "billboard", "quad", "viewnode"),
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        TwoDInThreeDDemo(onBack)
    }
}
