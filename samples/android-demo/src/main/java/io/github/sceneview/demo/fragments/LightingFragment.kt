package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.LightingDemo

/** Append-only fragment for the `lighting` demo. See [DemoFragment]. */
object LightingFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "lighting",
        titleRes = R.string.demo_lighting_title,
        subtitleRes = R.string.demo_lighting_subtitle,
        category = DemoCategory.RENDERING,
        icon = Icons.Filled.Lightbulb,
        order = 10,
        tags = setOf("light", "directional", "point", "spot", "shadow", "pbr"),
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        LightingDemo(onBack)
    }
}
