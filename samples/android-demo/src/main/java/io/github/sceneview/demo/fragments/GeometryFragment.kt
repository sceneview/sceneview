package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Category
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.GeometryDemo

/** Append-only fragment for the `geometry` demo. See [DemoFragment]. */
object GeometryFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "geometry",
        titleRes = R.string.demo_geometry_title,
        subtitleRes = R.string.demo_geometry_subtitle,
        category = DemoCategory.GEOMETRY_MATERIALS,
        icon = Icons.Filled.Category,
        order = 5,
        tags = setOf("geometry", "cube", "sphere", "cylinder", "plane", "primitive"),
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        GeometryDemo(onBack)
    }
}
