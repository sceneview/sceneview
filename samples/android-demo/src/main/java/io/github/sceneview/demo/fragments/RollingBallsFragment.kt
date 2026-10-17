package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Workspaces
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.DemoMode
import io.github.sceneview.demo.DemoModeHost
import io.github.sceneview.demo.R
import io.github.sceneview.demo.common.ArDemoPermissionGate
import io.github.sceneview.demo.demos.DoublePendulumDemo
import io.github.sceneview.demo.demos.RollingBallsDemo
import io.github.sceneview.demo.demos.RollingBallsTableDemo

/**
 * Append-only fragment for the `rolling-balls` demo. See [DemoFragment].
 *
 * The tray of rubber, steel and foam balls was the Physics tab of `animation-physics`
 * until #4083 made it its own demo. The retired `physics` deep link routes here through
 * [io.github.sceneview.demo.DeepLinkRouter.DEMO_ID_ALIASES].
 *
 * Samples step 0 added `double-pendulum` as the Pendulum mode (`?tab=pendulum`).
 *
 * The AR catalogue clean-up, wave 1, added **On your table** (`?tab=table`): the balls
 * dropped into the room, bouncing off the real surfaces ARCore sees in depth — the former
 * `ar-depth-collider` card. The card stays a 3D card, so only that mode asks for the
 * camera, through [ArDemoPermissionGate], when it is picked.
 */
object RollingBallsFragment : DemoFragment {
    val modes = listOf(
        DemoMode("balls", R.string.demo_mode_balls),
        DemoMode("pendulum", R.string.demo_mode_pendulum),
        DemoMode("table", R.string.demo_mode_table),
    )

    override val entry: DemoEntry = DemoEntry(
        id = "rolling-balls",
        titleRes = R.string.demo_rolling_balls_title,
        subtitleRes = R.string.demo_rolling_balls_subtitle,
        category = DemoCategory.VIEW_3D,
        icon = Icons.Filled.Workspaces,
        order = 30,
        addedIn = "4.48.0",
        updatedIn = "4.57.0",
        tags = setOf(
            "physics", "rigid-body", "collision", "simulation", "tilt", "balls",
            "pendulum", "chaos", "kmp", "ar", "depth", "depth-collider",
        ),
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        DemoModeHost(
            modes = modes,
            tabToMode = mapOf(0 to 0, 1 to 1, 2 to 2),
        ) { mode ->
            when (mode) {
                1 -> DoublePendulumDemo(onBack)
                2 -> ArDemoPermissionGate(
                    title = stringResource(R.string.demo_rolling_balls_title),
                    onBack = onBack,
                ) { RollingBallsTableDemo(onBack) }
                else -> RollingBallsDemo(onBack)
            }
        }
    }
}
