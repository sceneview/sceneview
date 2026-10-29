package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.DemoMode
import io.github.sceneview.demo.DemoModeHost
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.FingerDrawingArDemo
import io.github.sceneview.demo.demos.FingerDrawingDemo
import io.github.sceneview.demo.demos.rememberFingerBrush

/**
 * Append-only fragment for the `finger-drawing` demo (#4082). See [DemoFragment].
 *
 * Two modes on one card: 3D (`?tab=3d`), where the strokes hang in an orbit stage, and AR
 * (`?tab=ar`), where they hang in the room. The card is filed under Create & Record and
 * opens on the 3D mode, so it is not an AR demo for the registry: only the AR mode opens the
 * camera, and it carries its own permission gate (`ArDemoPermissionGate`).
 */
object FingerDrawingFragment : DemoFragment {
    val modes = listOf(
        DemoMode("3d", R.string.demo_mode_3d),
        DemoMode("ar", R.string.demo_mode_ar),
    )

    override val entry: DemoEntry = DemoEntry(
        id = "finger-drawing",
        titleRes = R.string.demo_finger_drawing_title,
        subtitleRes = R.string.demo_finger_drawing_subtitle,
        category = DemoCategory.CREATE,
        icon = Icons.Filled.Gesture,
        order = 7,
        addedIn = "4.56.0",
        updatedIn = "4.64.0",
        tags = setOf(
            "drawing", "draw", "paint", "brush", "stroke", "finger", "touch", "sketch",
            "tube", "mesh", "geometry", "procedural", "ar", "anchor",
        ),
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        // Above the mode switch: the colour and thickness in hand survive 3D <-> AR.
        val brush = rememberFingerBrush()
        DemoModeHost(
            modes = modes,
            tabToMode = mapOf(0 to 0, 1 to 1),
        ) { mode ->
            when (mode) {
                1 -> FingerDrawingArDemo(onBack, brush)
                else -> FingerDrawingDemo(onBack, brush)
            }
        }
    }
}
