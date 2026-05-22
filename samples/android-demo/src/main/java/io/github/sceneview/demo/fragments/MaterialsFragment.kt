package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Palette
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.DemoStatus
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.MaterialsDemo

/** Append-only fragment for the `materials` demo. See [DemoFragment]. */
object MaterialsFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "materials",
        titleRes = R.string.demo_materials_title,
        subtitleRes = R.string.demo_materials_subtitle,
        category = DemoCategory.ADVANCED,
        icon = Icons.Filled.Palette,
        // Streamed Sketchfab slugs can fail to resolve; the demo now surfaces an
        // error scrim with Retry rather than hanging, but the model may still be
        // unavailable, so the grid carries a known-issue chip (#2088).
        status = DemoStatus.KnownIssue,
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        MaterialsDemo(onBack)
    }
}
