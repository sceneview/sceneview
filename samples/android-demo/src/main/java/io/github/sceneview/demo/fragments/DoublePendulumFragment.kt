package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.DoublePendulumDemo

/** Append-only fragment for the `double-pendulum` demo. See [DemoFragment]. */
object DoublePendulumFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "double-pendulum",
        titleRes = R.string.demo_double_pendulum_title,
        subtitleRes = R.string.demo_double_pendulum_subtitle,
        category = DemoCategory.VIEW_3D,
        icon = Icons.Filled.Gesture,
        order = 31,
        addedIn = "4.4.0",
        updatedIn = "4.48.0",
        tags = setOf("physics", "pendulum", "chaos", "simulation", "kmp"),
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        DoublePendulumDemo(onBack)
    }
}
