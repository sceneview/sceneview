package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Label
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.DemoStatus
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.ARMLObjectLabelDemo

/** Point & Ask's on-device fallback, also listed in Tools. */
object ArMlObjectLabelFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "ar-ml-object-label",
        sourcePath = "src/main/java/io/github/sceneview/demo/demos/ARMLObjectLabelDemo.kt",
        titleRes = R.string.demo_ar_ml_title,
        subtitleRes = R.string.demo_ar_ml_subtitle,
        category = DemoCategory.DEV_TOOLS,
        icon = Icons.Filled.Label,
        order = 43,
        addedIn = "4.12.0",
        tags = setOf("ml kit object labels", "ar", "ml", "mlkit", "object-detection", "label", "hit-test"),
        // Coarse ML Kit categories only, and the scene is dated (samples audit, 2026-10-02):
        // it no longer has a card, only Point & Ask's fallback reaches it.
        status = DemoStatus.KnownIssue,
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        ARMLObjectLabelDemo(onBack)
    }
}
