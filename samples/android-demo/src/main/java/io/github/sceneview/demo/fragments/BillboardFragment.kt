package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.BillboardDemo

/** Append-only fragment for the `billboard` demo. See [DemoFragment]. */
object BillboardFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "billboard",
        titleRes = R.string.demo_billboard_title,
        subtitleRes = R.string.demo_billboard_subtitle,
        category = DemoCategory.CONTENT,
        icon = Icons.Filled.Visibility,
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        BillboardDemo(onBack)
    }
}
