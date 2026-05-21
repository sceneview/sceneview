package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Palette
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
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
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        MaterialsDemo(onBack)
    }
}
