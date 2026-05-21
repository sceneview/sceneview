package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.CameraControlsDemo

/** Append-only fragment for the `camera-controls` demo. See [DemoFragment]. */
object CameraControlsFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "camera-controls",
        titleRes = R.string.demo_camera_controls_title,
        subtitleRes = R.string.demo_camera_controls_subtitle,
        category = DemoCategory.INTERACTION,
        icon = Icons.Filled.PhotoCamera,
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        CameraControlsDemo(onBack)
    }
}
