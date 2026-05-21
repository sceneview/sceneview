package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pentagon
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.ShapeDemo

/** Append-only fragment for the `shape` demo. See [DemoFragment]. */
object ShapeFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "shape",
        titleRes = R.string.demo_shape_title,
        subtitleRes = R.string.demo_shape_subtitle,
        category = DemoCategory.ADVANCED,
        icon = Icons.Filled.Pentagon,
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        ShapeDemo(onBack)
    }
}
