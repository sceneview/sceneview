package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.PostProcessingDemo

/** Append-only fragment for the `post-processing` demo. See [DemoFragment]. */
object PostProcessingFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "post-processing",
        titleRes = R.string.demo_post_processing_title,
        subtitleRes = R.string.demo_post_processing_subtitle,
        category = DemoCategory.ADVANCED,
        icon = Icons.Filled.Tune,
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        PostProcessingDemo(onBack)
    }
}
