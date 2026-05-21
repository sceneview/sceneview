package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.DynamicSkyDemo

/** Append-only fragment for the `dynamic-sky` demo. See [DemoFragment]. */
object DynamicSkyFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "dynamic-sky",
        titleRes = R.string.demo_dynamic_sky,
        subtitleRes = R.string.demo_dynamic_sky_subtitle,
        category = DemoCategory.LIGHTING_ENVIRONMENT,
        icon = Icons.Filled.WbSunny,
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        DynamicSkyDemo(onBack)
    }
}
