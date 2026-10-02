package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Workspaces
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.DemoMode
import io.github.sceneview.demo.DemoModeHost
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.DoublePendulumDemo
import io.github.sceneview.demo.demos.RollingBallsDemo

/**
 * Append-only fragment for the `rolling-balls` demo. See [DemoFragment].
 *
 * The tray of rubber, steel and foam balls was the Physics tab of `animation-physics`
 * until #4083 made it its own demo. The retired `physics` deep link routes here through
 * [io.github.sceneview.demo.DeepLinkRouter.DEMO_ID_ALIASES].
 *
 * Samples step 0 added `double-pendulum` as the Pendulum mode (`?tab=pendulum`).
 */
object RollingBallsFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "rolling-balls",
        titleRes = R.string.demo_rolling_balls_title,
        subtitleRes = R.string.demo_rolling_balls_subtitle,
        category = DemoCategory.VIEW_3D,
        icon = Icons.Filled.Workspaces,
        order = 30,
        addedIn = "4.48.0",
        updatedIn = "4.51.0",
        tags = setOf(
            "physics", "rigid-body", "collision", "simulation", "tilt", "balls",
            "pendulum", "chaos", "kmp",
        ),
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        DemoModeHost(
            modes = listOf(
                DemoMode("balls", R.string.demo_mode_balls),
                DemoMode("pendulum", R.string.demo_mode_pendulum),
            ),
            tabToMode = mapOf(0 to 0, 1 to 1),
        ) { mode ->
            when (mode) {
                1 -> DoublePendulumDemo(onBack)
                else -> RollingBallsDemo(onBack)
            }
        }
    }
}
