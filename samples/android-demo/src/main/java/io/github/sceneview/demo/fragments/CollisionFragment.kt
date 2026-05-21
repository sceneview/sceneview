package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.CollisionDemo

/** Append-only fragment for the `collision` demo. See [DemoFragment]. */
object CollisionFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "collision",
        titleRes = R.string.demo_collision_title,
        subtitleRes = R.string.demo_collision_subtitle,
        category = DemoCategory.INTERACTION,
        icon = Icons.Filled.CenterFocusStrong,
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        CollisionDemo(onBack)
    }
}
