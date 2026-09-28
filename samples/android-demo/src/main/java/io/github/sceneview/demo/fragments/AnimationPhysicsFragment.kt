package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.AnimationPhysicsDemo

/**
 * The Animation demo. It consolidated the retired `animation` and `physics` demos
 * behind one entry in #2239 Batch 3; since #4083 its Physics tab is the separate
 * `rolling-balls` demo, and the id stays `animation-physics` so links keep working.
 * The old deep-link ids stay routable through
 * [io.github.sceneview.demo.DeepLinkRouter.DEMO_ID_ALIASES].
 */
object AnimationPhysicsFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "animation-physics",
        titleRes = R.string.demo_animation_physics_title,
        subtitleRes = R.string.demo_animation_physics_subtitle,
        category = DemoCategory.VIEW_3D,
        icon = Icons.Filled.RotateRight,
        order = 3,
        // 4.48.0: the Physics tab left for its own `rolling-balls` demo (#4083).
        updatedIn = "4.48.0",
        tags = setOf("animation", "skeletal", "camera", "gltf"),
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        AnimationPhysicsDemo(onBack)
    }
}
