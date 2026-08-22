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
        titleRes = R.string.demo_ar_body_tracker_title,
        subtitleRes = R.string.demo_ar_body_tracker_subtitle,
        category = DemoCategory.AUGMENTED_REALITY,
        icon = Icons.Filled.Accessibility,
        order = 38,
        tags = setOf("ar", "body", "pose", "mediapipe", "skeleton", "ml"),
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        ARBodyTrackerDemo(onBack)
    }
}
