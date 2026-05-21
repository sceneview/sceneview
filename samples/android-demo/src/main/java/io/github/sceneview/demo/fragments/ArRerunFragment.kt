package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.ARRerunDemo

/** Append-only fragment for the `ar-rerun` demo. See [DemoFragment]. */
object ArRerunFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "ar-rerun",
        titleRes = R.string.demo_ar_rerun_title,
        subtitleRes = R.string.demo_ar_rerun_subtitle,
        category = DemoCategory.AUGMENTED_REALITY,
        icon = Icons.Filled.BugReport,
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        ARRerunDemo(onBack)
    }
}
