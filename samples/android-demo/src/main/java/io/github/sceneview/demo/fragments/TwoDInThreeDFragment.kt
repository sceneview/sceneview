package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WebAsset
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.DemoMode
import io.github.sceneview.demo.DemoModeHost
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.TwoDInThreeDInspectDemo
import io.github.sceneview.demo.demos.TwoDInThreeDMediaDemo

/**
 * Inspect a procedural rocket with live Compose controls, or explore flat content in Media.
 * Retired text/image/video/billboard links open Media through DeepLinkRouter.ALIAS_INITIAL_TAB.
 */
object TwoDInThreeDFragment : DemoFragment {
    val modes = listOf(
        DemoMode("inspect", R.string.demo_mode_inspect),
        DemoMode("media", R.string.demo_mode_media),
    )

    override val entry: DemoEntry = DemoEntry(
        id = "two-d-in-three-d",
        titleRes = R.string.demo_two_d_in_three_d_title,
        subtitleRes = R.string.demo_two_d_in_three_d_subtitle,
        category = DemoCategory.CREATE,
        icon = Icons.Filled.WebAsset,
        order = 9,
        addedIn = "4.17.0",
        updatedIn = "4.52.0",
        tags = setOf(
            "2d", "viewnode", "compose", "billboard", "quad", "label", "annotation",
            "text", "image", "video", "occlusion", "picking", "material",
        ),
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        DemoModeHost(modes = modes, tabToMode = mapOf(0 to 0, 1 to 1)) { mode ->
            when (mode) {
                1 -> TwoDInThreeDMediaDemo(onBack)
                else -> TwoDInThreeDInspectDemo(onBack)
            }
        }
    }
}
