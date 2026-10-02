package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.DemoMode
import io.github.sceneview.demo.DemoModeHost
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.SecondaryCameraDemo
import io.github.sceneview.demo.demos.CameraAndGesturesDemo

/**
 * "Camera & Gestures" — one stage, one camera, and every camera capability expressed as
 * something the user *does* to it: orbit with inertia, tap a subject to fly to it, named views,
 * a cinematic turntable, and object gestures in the same scene (#3500).
 *
 * It also absorbs the retired `camera-controls` and `gesture-editing` demos (#2239 Batch 1) —
 * their deep links stay routable through
 * [io.github.sceneview.demo.DeepLinkRouter.DEMO_ID_ALIASES].
 *
 * Samples step 0 added `secondary-camera` as the PiP mode (`?tab=pip`).
 */
object CameraAndGesturesFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "camera-gestures",
        titleRes = R.string.demo_camera_and_gestures_title,
        subtitleRes = R.string.demo_camera_and_gestures_subtitle,
        category = DemoCategory.VIEW_3D,
        icon = Icons.Filled.PhotoCamera,
        order = 33,
        addedIn = "4.17.0",
        updatedIn = "4.51.0",
        tags = setOf(
            "camera", "orbit", "gesture", "pan", "zoom", "manipulator", "edit",
            "pip", "multi-view", "render-target",
        ),
        // #3500 rebuilt the screen from scratch around one stage.
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        DemoModeHost(
            modes = listOf(
                DemoMode("camera", R.string.demo_mode_camera),
                DemoMode("pip", R.string.demo_mode_pip),
            ),
            tabToMode = mapOf(0 to 0, 1 to 1),
        ) { mode ->
            when (mode) {
                1 -> SecondaryCameraDemo(onBack)
                else -> CameraAndGesturesDemo(onBack)
            }
        }
    }
}
