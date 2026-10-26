package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.DemoStatus
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.CameraPathDemo

/** Append-only fragment for the `camera-path` demo. See [DemoFragment]. */
object CameraPathFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "camera-path",
        titleRes = R.string.demo_camera_path_title,
        subtitleRes = R.string.demo_camera_path_subtitle,
        category = DemoCategory.VIEW_3D,
        icon = Icons.Filled.Videocam,
        order = 31,
        addedIn = "4.63.0",
        tags = setOf("camera", "keyframes", "path", "orbit", "cinematic", "timeline", "no-camera"),
        status = DemoStatus.InReview,
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        CameraPathDemo(onBack)
    }
}
