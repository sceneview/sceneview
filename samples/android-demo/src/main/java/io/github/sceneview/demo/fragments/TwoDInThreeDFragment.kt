package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Layers
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.TwoDInThreeDDemo

/**
 * "2D in 3D" — Compose UI on `ViewNode` quads anchored in world space, rebuilt from
 * scratch in #3424 around one annotated model instead of the four unrelated scenes it
 * used to hold. It still owns the retired `text`, `image`, `video` and `billboard` ids
 * (#2239 Batch 1), which stay routable through
 * [io.github.sceneview.demo.DeepLinkRouter.DEMO_ID_ALIASES] — but the demo no longer has
 * tabs, so none of them carries an initial-tab hint any more.
 */
object TwoDInThreeDFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "two-d-in-three-d",
        titleRes = R.string.demo_two_d_in_three_d_title,
        subtitleRes = R.string.demo_two_d_in_three_d_subtitle,
        category = DemoCategory.CONTENT,
        icon = Icons.Filled.Layers,
        order = 11,
        tags = setOf(
            "2d", "viewnode", "compose", "billboard", "quad", "label", "annotation",
            "text", "image", "video", "occlusion",
        ),
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        TwoDInThreeDDemo(onBack)
    }
}
