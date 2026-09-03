package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.AnimationPhysicsDemo

/**
 * Unified "Animation & Physics" demo — consolidates the retired `animation` and
 * `physics` demos behind one entry with an internal segmented-button toggle
 * (#2239 Batch 3). The old deep-link ids stay routable through
 * [io.github.sceneview.demo.DeepLinkRouter.DEMO_ID_ALIASES].
 */
object AnimationPhysicsFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "animation-physics",
        titleRes = R.string.demo_animation_physics_title,
        subtitleRes = R.string.demo_animation_physics_subtitle,
        category = DemoCategory.VIEWER,
        icon = Icons.Filled.RotateRight,
        order = 3,
        tags = setOf("animation", "skeletal", "physics", "rigid-body", "collision", "gltf"),
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        AnimationPhysicsDemo(onBack)
    }
}
