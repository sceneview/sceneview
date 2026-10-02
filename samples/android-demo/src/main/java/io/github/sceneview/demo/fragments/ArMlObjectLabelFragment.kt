package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Label
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.DemoStatus
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.ARMLObjectLabelDemo

/** Append-only fragment for the `ar-ml-object-label` demo. See [DemoFragment]. */
object ArMlObjectLabelFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "ar-ml-object-label",
        titleRes = R.string.demo_ar_ml_title,
        subtitleRes = R.string.demo_ar_ml_subtitle,
        category = DemoCategory.UNDERSTAND,
        icon = Icons.Filled.Label,
        order = 47,
        addedIn = "4.12.0",
        tags = setOf("ar", "ml", "mlkit", "object-detection", "label", "hit-test"),
        // Crashes past about four labels on screen and the scene is dated: it is being rebuilt
        // from scratch as the offline Label mode of `point-and-ask` (samples audit, 2026-10-02).
        status = DemoStatus.KnownIssue,
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        ARMLObjectLabelDemo(onBack)
    }
}
