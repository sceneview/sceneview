package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PanoramaPhotosphere
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.EnvironmentDemo

/** Append-only fragment for the `environment` demo. See [DemoFragment]. */
object EnvironmentFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "environment",
        titleRes = R.string.demo_environment_title,
        subtitleRes = R.string.demo_environment_subtitle,
        category = DemoCategory.LIGHTING_ENVIRONMENT,
        icon = Icons.Filled.PanoramaPhotosphere,
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        EnvironmentDemo(onBack)
    }
}
