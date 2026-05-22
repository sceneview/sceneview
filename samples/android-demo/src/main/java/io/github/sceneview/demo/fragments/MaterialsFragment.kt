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
        // The `materials` Sketchfab slugs are now real, validated, downloadable
        // models (#2095) — the demo streams them successfully and falls back to
        // bundled assets when offline. The #2088 known-issue chip is removed.
        status = DemoStatus.Working,
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        MaterialsDemo(onBack)
    }
}
