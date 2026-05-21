package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Layers
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.MultiModelDemo

/** Append-only fragment for the `multi-model` demo. See [DemoFragment]. */
object MultiModelFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "multi-model",
        titleRes = R.string.demo_multi_model_title,
        subtitleRes = R.string.demo_multi_model_subtitle,
        category = DemoCategory.BASICS_3D,
        icon = Icons.Filled.Layers,
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        MultiModelDemo(onBack)
    }
}
