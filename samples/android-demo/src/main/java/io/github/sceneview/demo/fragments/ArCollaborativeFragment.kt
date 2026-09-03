package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Groups
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.ARCollaborativeDemo

/** Append-only fragment for the `ar-collaborative` demo. See [DemoFragment]. */
object ArCollaborativeFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "ar-collaborative",
        titleRes = R.string.demo_ar_collaborative_title,
        subtitleRes = R.string.demo_ar_collaborative_subtitle,
        category = DemoCategory.AR_ANCHORS,
        icon = Icons.Filled.Groups,
        order = 43,
        tags = setOf("ar", "multi-user", "sync", "collaboration", "transport"),
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        ARCollaborativeDemo(onBack)
    }
}
