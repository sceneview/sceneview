package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Workspaces
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.RollingBallsDemo

/**
 * Append-only fragment for the `rolling-balls` demo. See [DemoFragment].
 *
 * The tray of rubber, steel and foam balls was the Physics tab of `animation-physics`
 * until #4083 made it its own demo. The retired `physics` deep link routes here through
 * [io.github.sceneview.demo.DeepLinkRouter.DEMO_ID_ALIASES].
 */
object RollingBallsFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "rolling-balls",
        titleRes = R.string.demo_rolling_balls_title,
        subtitleRes = R.string.demo_rolling_balls_subtitle,
        category = DemoCategory.VIEW_3D,
        icon = Icons.Filled.Workspaces,
        order = 30,
        sinceVersion = "4.48.0",
        tags = setOf("physics", "rigid-body", "collision", "simulation", "tilt", "balls"),
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        RollingBallsDemo(onBack)
    }
}
