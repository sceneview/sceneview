package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FormatShapes
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.TextDemo

/** Append-only fragment for the `text` demo. See [DemoFragment]. */
object TextFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "text",
        titleRes = R.string.demo_text_title,
        subtitleRes = R.string.demo_text_subtitle,
        category = DemoCategory.CONTENT,
        icon = Icons.Filled.FormatShapes,
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        TextDemo(onBack)
    }
}
