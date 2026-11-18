package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Label
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.DemoStatus
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.ARMLObjectLabelDemo

/**
 * Append-only fragment for the `ar-ml-object-label` demo. See [DemoFragment].
 *
 * No catalogue card since the AR catalogue clean-up (wave 1, #4608): the id is in
 * [io.github.sceneview.demo.FALLBACK_ONLY_DEMO_IDS]. It stays registered because it is Point &
 * Ask's on-device fallback on phones without Google AICore (#2648), opened by deep link.
 */
object ArMlObjectLabelFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "ar-ml-object-label",
        sourcePath = "src/main/java/io/github/sceneview/demo/demos/ARMLObjectLabelDemo.kt",
        titleRes = R.string.demo_ar_ml_title,
        subtitleRes = R.string.demo_ar_ml_subtitle,
        category = DemoCategory.DEV_TOOLS,
        icon = Icons.Filled.Label,
        order = 44,
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
