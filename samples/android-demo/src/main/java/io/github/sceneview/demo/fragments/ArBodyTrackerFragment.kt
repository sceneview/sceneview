package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.ARBodyTrackerDemo

/** Append-only fragment for the `ar-body-tracker` demo. See [DemoFragment]. */
object ArBodyTrackerFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "ar-body-tracker",
        sourcePath = "src/main/java/io/github/sceneview/demo/demos/ARBodyTrackerDemo.kt",
        titleRes = R.string.demo_ar_body_tracker_title,
        subtitleRes = R.string.demo_ar_body_tracker_subtitle,
        category = DemoCategory.UNDERSTAND,
        icon = Icons.Filled.Accessibility,
        order = 29,
        addedIn = "4.14.0",
        tags = setOf("ar", "body", "pose", "mediapipe", "skeleton", "ml", "squat", "fitness", "workout", "coach"),
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        ARBodyTrackerDemo(onBack)
    }
}
