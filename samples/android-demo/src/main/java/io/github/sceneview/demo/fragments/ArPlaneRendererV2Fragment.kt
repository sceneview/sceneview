package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BorderClear
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.ARPlaneRendererV2Demo

/** Append-only fragment for the `ar-plane-renderer-v2` demo. See [DemoFragment]. */
object ArPlaneRendererV2Fragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "ar-plane-renderer-v2",
        titleRes = R.string.demo_ar_plane_renderer_v2_title,
        subtitleRes = R.string.demo_ar_plane_renderer_v2_subtitle,
        category = DemoCategory.PLACE_AR,
        icon = Icons.Filled.BorderClear,
        order = 26,
        addedIn = "4.16.0",
        updatedIn = "4.52.0",
        tags = setOf("ar", "plane", "renderer", "depth", "pbr", "hdr"),
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        ARPlaneRendererV2Demo(onBack)
    }
}
