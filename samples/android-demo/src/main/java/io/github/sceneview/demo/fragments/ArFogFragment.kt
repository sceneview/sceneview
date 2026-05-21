package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.ARFogDemo

/** Append-only fragment for the `ar-fog` demo (issue #1717). See [DemoFragment]. */
object ArFogFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "ar-fog",
        titleRes = R.string.demo_ar_fog_title,
        subtitleRes = R.string.demo_ar_fog_subtitle,
        category = DemoCategory.AUGMENTED_REALITY,
        icon = Icons.Filled.Cloud,
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        ARFogDemo(onBack)
    }
}
