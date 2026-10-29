package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WebAsset
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.DemoMode
import io.github.sceneview.demo.DemoModeHost
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.TwoDInThreeDDemo

/**
 * One showcase: a procedural rocket restyled from live Compose UI, in front of a wall of image,
 * video, billboard and text nodes (#4624). It was two tabs, Inspect and Media, and the second
 * went unnoticed: both are now one scene. The two mode keys stay — `?tab=inspect`, `?tab=media`
 * and the retired text/image/video/billboard links still resolve, and telemetry keeps its
 * history — but no switch is drawn and every mode opens the same screen.
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
        tags = setOf(
            "2d", "viewnode", "compose", "billboard", "quad", "label", "annotation",
            "text", "image", "video", "occlusion", "picking", "material",
        ),
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        DemoModeHost(modes = modes, tabToMode = mapOf(0 to 0, 1 to 1), showSwitch = false) {
            TwoDInThreeDDemo(onBack)
        }
    }
}
