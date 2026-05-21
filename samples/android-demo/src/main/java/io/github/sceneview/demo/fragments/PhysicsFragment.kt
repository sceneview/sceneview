package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ScatterPlot
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.PhysicsDemo

/** Append-only fragment for the `physics` demo. See [DemoFragment]. */
object PhysicsFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "physics",
        titleRes = R.string.demo_physics_title,
        subtitleRes = R.string.demo_physics_subtitle,
        category = DemoCategory.ADVANCED,
        icon = Icons.Filled.ScatterPlot,
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        PhysicsDemo(onBack)
    }
}
