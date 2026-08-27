package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.DemoStatus
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.GestureFeedbackPreviewDemo

/** Append-only fragment for the `gesture-feedback-preview` demo. See [DemoFragment]. */
object GestureFeedbackPreviewFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "gesture-feedback-preview",
        titleRes = R.string.demo_gesture_feedback_preview_title,
        subtitleRes = R.string.demo_gesture_feedback_preview_subtitle,
        // Deliberately NOT under AUGMENTED_REALITY: the API lives in `sceneview` and the
        // demo is a non-AR studio scene QA-able on any emulator (same rationale as
        // `contact-shadow-preview`).
        category = DemoCategory.INTERACTION,
        icon = Icons.Filled.TouchApp,
        order = 55,
        tags = setOf("gesture", "editing", "feedback", "overlay", "rotation", "scale", "no-camera"),
        status = DemoStatus.InReview,
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        GestureFeedbackPreviewDemo(onBack)
    }
}
