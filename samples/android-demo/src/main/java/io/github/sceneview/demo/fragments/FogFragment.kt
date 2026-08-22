package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.FogDemo

/** Append-only fragment for the `fog` demo. See [DemoFragment]. */
object FogFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "fog",
        titleRes = R.string.demo_fog,
        subtitleRes = R.string.demo_fog_subtitle,
        category = DemoCategory.LIGHTING_ENVIRONMENT,
        icon = Icons.Filled.Cloud,
        order = 13,
        tags = setOf("fog", "atmosphere", "height-fog", "environment"),
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        FogDemo(onBack)
    }
}
