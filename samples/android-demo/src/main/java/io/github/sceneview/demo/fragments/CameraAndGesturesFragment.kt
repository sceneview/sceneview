package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.CameraAndGesturesDemo

/**
 * "Camera & Gestures" — one stage, one camera, and every camera capability expressed as
 * something the user *does* to it: orbit with inertia, tap a subject to fly to it, named views,
 * a cinematic turntable, and object gestures in the same scene (#3500).
 *
 * It also absorbs the retired `camera-controls` and `gesture-editing` demos (#2239 Batch 1) —
 * their deep links stay routable through
 * [io.github.sceneview.demo.DeepLinkRouter.DEMO_ID_ALIASES].
 */
object CameraAndGesturesFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "camera-gestures",
        titleRes = R.string.demo_camera_and_gestures_title,
        subtitleRes = R.string.demo_camera_and_gestures_subtitle,
        category = DemoCategory.INTERACTION,
        icon = Icons.Filled.PhotoCamera,
        order = 13,
        tags = setOf("camera", "orbit", "gesture", "pan", "zoom", "manipulator", "edit"),
        // #3500 rebuilt the screen from scratch around one stage.
        updatedIn = "4.35.0",
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        CameraAndGesturesDemo(onBack)
    }
}
