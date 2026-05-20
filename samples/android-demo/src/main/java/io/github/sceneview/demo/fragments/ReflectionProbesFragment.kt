package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.ReflectionProbesDemo

/** Append-only fragment for the `reflection-probes` demo. See [DemoFragment]. */
object ReflectionProbesFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "reflection-probes",
        titleRes = R.string.demo_reflection_probes_title,
        subtitleRes = R.string.demo_reflection_probes_subtitle,
        category = DemoCategory.ADVANCED,
        icon = Icons.Filled.BlurOn,
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        ReflectionProbesDemo(onBack)
    }
}
