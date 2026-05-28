package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.CameraAndGesturesDemo

/**
 * Unified "Camera & Gestures" demo — consolidates the retired `camera-controls`
 * and `gesture-editing` demos behind one entry with an internal
 * segmented-button toggle (#2239 Batch 1). Old deep links stay routable
 * through [io.github.sceneview.demo.DeepLinkRouter.DEMO_ID_ALIASES].
 */
object CameraAndGesturesFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "camera-gestures",
        titleRes = R.string.demo_camera_and_gestures_title,
        subtitleRes = R.string.demo_camera_and_gestures_subtitle,
        category = DemoCategory.INTERACTION,
        icon = Icons.Filled.PhotoCamera,
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        CameraAndGesturesDemo(onBack)
    }
}
