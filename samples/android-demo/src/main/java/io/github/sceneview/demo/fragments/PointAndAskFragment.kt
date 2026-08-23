package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.PointAndAskDemo

/** Append-only fragment for the `point-and-ask` demo. See [DemoFragment]. */
object PointAndAskFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "point-and-ask",
        titleRes = R.string.demo_point_and_ask_title,
        subtitleRes = R.string.demo_point_and_ask_subtitle,
        category = DemoCategory.AUGMENTED_REALITY,
        icon = Icons.Filled.Psychology,
        order = 39,
        tags = setOf("ar", "ai", "gemini", "on-device", "llm", "vision"),
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        PointAndAskDemo(onBack)
    }
}
