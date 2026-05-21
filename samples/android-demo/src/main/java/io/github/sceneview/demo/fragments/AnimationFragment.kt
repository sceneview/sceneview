package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.AnimationDemo

/** Append-only fragment for the `animation` demo. See [DemoFragment]. */
object AnimationFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "animation",
        titleRes = R.string.demo_animation_title,
        subtitleRes = R.string.demo_animation_subtitle,
        category = DemoCategory.BASICS_3D,
        icon = Icons.Filled.RotateRight,
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        AnimationDemo(onBack)
    }
}
