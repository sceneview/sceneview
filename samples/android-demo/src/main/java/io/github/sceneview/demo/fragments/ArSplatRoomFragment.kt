package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.House
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.ARRerunDemo

/**
 * Append-only fragment for the `ar-splat-room` demo: the room you recorded with the Rerun demo,
 * stood on a table in AR as a dollhouse (#4075). It first placed a stock capture (#4023); the id
 * stays, as every id is a stable deep link. See [DemoFragment].
 */
object ArSplatRoomFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "ar-splat-room",
        titleRes = R.string.demo_ar_splat_room_title,
        subtitleRes = R.string.demo_ar_splat_room_subtitle,
        category = DemoCategory.PLACE_AR,
        icon = Icons.Filled.House,
        order = 8,
        tags = setOf("ar", "rerun", "scan", "room", "dollhouse", "miniature", "placement", "real-scale"),
        sinceVersion = "4.45.0",
        updatedIn = "4.46.0",
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        ARRerunDemo(onBack, startInDollhouse = true)
    }
}
