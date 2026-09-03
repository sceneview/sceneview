package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Speed
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.DebugOverlayDemo

/** Append-only fragment for the `debug-overlay` demo. See [DemoFragment]. */
object DebugOverlayFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "debug-overlay",
        titleRes = R.string.demo_debug_overlay_title,
        subtitleRes = R.string.demo_debug_overlay_subtitle,
        category = DemoCategory.PLATFORM,
        icon = Icons.Filled.Speed,
        order = 48,
        tags = setOf("debug", "fps", "stats", "performance", "overlay"),
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        DebugOverlayDemo(onBack)
    }
}
