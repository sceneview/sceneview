package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.ARSplatRoomDemo

/**
 * Append-only fragment for the `ar-splat-room` demo: the `splat-preview` capture placed in the
 * user's room at its real size (#4023). See [DemoFragment].
 */
object ArSplatRoomFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "ar-splat-room",
        titleRes = R.string.demo_ar_splat_room_title,
        subtitleRes = R.string.demo_ar_splat_room_subtitle,
        category = DemoCategory.PLACE_AR,
        icon = Icons.Filled.Landscape,
        order = 18,
        tags = setOf("ar", "splat", "gaussian", "scan", "spz", "real-scale", "placement"),
        sinceVersion = "4.45.0",
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        ARSplatRoomDemo(onBack)
    }
}
