package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.MovableLightDemo

/** Append-only fragment for the `movable-light` demo. See [DemoFragment]. */
object MovableLightFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "movable-light",
        titleRes = R.string.demo_movable_light_title,
        subtitleRes = R.string.demo_movable_light_subtitle,
        category = DemoCategory.LIGHTING_ENVIRONMENT,
        icon = Icons.Filled.AutoFixHigh,
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        MovableLightDemo(onBack)
    }
}
